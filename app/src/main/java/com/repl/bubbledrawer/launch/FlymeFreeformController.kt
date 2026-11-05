package com.repl.bubbledrawer.launch

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.InputEvent
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import com.repl.bubbledrawer.contactbar.FreeformTask
import com.repl.bubbledrawer.xposed.RemotePrefs
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Controller and Xposed hooks for Flyme-style lightweight freeform window (轻量小窗).
 */
class FlymeFreeformController(
    val module: XposedModule,
    private val prefs: SharedPreferences,
    val context: Context,
    private val classLoader: ClassLoader,
) {
    val handler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val vibrator = runCatching { context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }.getOrNull()

    // -----------------------------------------------------------------------------------------
    // Live 小窗 bookkeeping — main thread only; every hook funnels through [onMain].
    //
    // The Flyme-style mask is ONE full-screen SurfaceControl, so it can only ever sit under a single
    // window; all of the state below exists to answer "which window owns it right now". The previous
    // design tracked exactly one window (the last one that appeared) and never re-armed, which broke
    // as soon as two 小窗 were open at the same time: the moment the window the mask was bound to went
    // away, the survivor was left with no 黑色遮罩 and no 窗外点击关闭 — nothing re-showed it, because
    // a window that is already open never "appears" again.
    // -----------------------------------------------------------------------------------------

    /** taskId → window object (richest one we have), in the order we learned about them. */
    private val windows = LinkedHashMap<Int, Any>()

    /** taskId → latch expiry: windows the mask (and the avoid pass) must stay away from (closing, or mid-gesture). */
    private val suspended = ConcurrentHashMap<Int, Long>()

    /** The window the mask belongs to right now (null = no mask). */
    private var maskTaskId: Int? = null

    /** The user handed this window back to the ROM (上滑悬停 → 原生小窗); no mask until it changes. */
    private var takeoverCancelledFor: Int? = null

    private var tickScheduled = false

    /** Last mask log line — the 700 ms tick must not repeat it on every pass. */
    private var lastDimLog = ""

    /**
     * Shell-thread readable mirror of the mask's window. The bottom-caption gesture hooks run on the
     * WMShell thread and only need "is the user touching the window the mask follows", so these stay
     * plain volatile fields instead of forcing them onto the main thread.
     */
    @Volatile var activeTaskId: Int? = null
    @Volatile var activeTaskInfo: Any? = null
    @Volatile var isTakeover: Boolean = false

    // System-level SurfaceControl background decoration and outside-tap receiver
    private var backgroundDecoration: FreeformBackgroundDecoration? = null

    /**
     * taskId → 迷你小窗的 `MiuiFreeformModeTaskInfo`（长按菜单要用它调 ROM 的动画与音频）。
     *
     * 只在 `MiuiFreeformModeTouchState.handleMotionEvents`（钩子，见 [hookMiniTouchForMenu]）里写：
     * 那是 ROM 唯一会把「这条触摸属于哪个迷你小窗」交给我们的地方。
     *
     * 「是不是放大状态」**不**记在这里 —— 那个状态属于 ROM（`baseAnimTarget.scaleX` + 框架的
     * `MiuiFreeFormStackInfo.freeFormScale`），我们只按当前 scale 和 ROM 算出的正常迷你缩放现场判断
     * （[isMiniScaleEnlarged]），否则记的判断迟早会和 ROM 的真相打架（2026-10-18 用户报的「移动时
     * 大迷你小窗状态会重置」就是这么来的）。
     */
    val miniTaskInfoMap = ConcurrentHashMap<Int, Any>()

    // Flyme-style long-press floating action menu overlay for mini windows (静音 / 放大)
    val miniMenuOverlay by lazy {
        FlymeMiniMenuOverlay(
            context = context,
            handler = handler,
            onScaleClicked = { taskId ->
                toggleMiniWindowScale(taskId)
            },
            onDismissed = { taskId ->
                log("MINI_MENU_DISMISSED taskId=$taskId")
            }
        )
    }

    // Gesture tracking for bottom bar
    var gestureDownY = 0f
    var gestureDownTime = 0L
    var gestureHoverArmed = false
    var hoverScheduled = false

    /** The window whose bottom caption is being dragged (set on ACTION_DOWN, WMShell thread). */
    @Volatile private var gestureTaskId = 0

    /**
     * Bumped whenever the caption gesture moves on (a new ACTION_DOWN, a cancel, a reload): a 迷你小窗
     * conversion the previous gesture scheduled must not land after the user has started something else.
     * See [scheduleMiniFallback].
     */
    private var miniFallbackGeneration = 0

    val hoverRunnable = Runnable {
        hoverScheduled = false
        gestureHoverArmed = true
        // Cancel module takeover -> window becomes standard native freeform window!
        cancelTakeover("SWIPE_UP_HOLD")
        triggerHapticFeedback()
        log("SWIPE_UP_HOLD_TRIGGERED_NATIVE_FREEFORM taskId=$activeTaskId")
    }

    var pilferCallback: (() -> Boolean)? = null
    fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    init {
        instance = this
        currentModule = module
        savedContext = context
        // 上个进程（SystemUI 重启前）没来得及还原的静音音量：这次启动补上，见 MiniAppAudioHelper.attach。
        MiniAppAudioHelper.attach(context)
        // 「保存的大迷你」状态必须写在 SystemUI 自己的持久化存储里：`prefs` 是模块组的只读镜像，
        // 它的 edit() 在 hook 进程里会抛 UnsupportedOperationException（2026-10-24 真机崩溃）。
        miniScaleStatePrefs = runCatching {
            val storage = if (context.isDeviceProtectedStorage) {
                context
            } else {
                context.createDeviceProtectedStorageContext()
            }
            storage.getSharedPreferences(MINI_STATE_PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull()
        // Outside tap (the 黑色遮罩 itself is the touch target: a trusted overlay with an input
        // receiver registered for it, see FreeformBackgroundDecoration). Runs on the main thread.
        backgroundDecoration = FreeformBackgroundDecoration(context, classLoader, prefs) {
            val snap = RemotePrefs.read(prefs)
            val target = currentTarget()
            val taskId = target?.let { getTaskId(it) } ?: -1
            if (snap.flymeFreeformOutsideDismiss && target != null && taskId > 0) {
                log("OUTSIDE_SURFACE_TAP_DISMISS taskId=$taskId pkg=${FreeformTask.packageName(target)}")
                suspendWindow(taskId, "OUTSIDE_CLOSE")
                closeTask(target, taskId)
                // 两个小窗: the survivor must keep its mask. Re-arm immediately instead of waiting for
                // the focus change to be reported — if the ROM never reports it, the survivor would be
                // left unclosable, which is exactly the reported bug.
                maskTaskId = null
                refreshMask("AFTER_OUTSIDE_CLOSE")
            } else {
                log("OUTSIDE_SURFACE_TAP_IGNORED dismiss=${snap.flymeFreeformOutsideDismiss} target=${target != null}")
            }
        }
    }

    fun start() {
        log("FLYME_FREEFORM_CONTROLLER_STARTED")
    }

    fun dispose() {
        gestureReset()
        cancelMiniFallback()
        runCatching { miniMenuOverlay.dismiss(animate = false) }
        hideDim()
        windows.clear()
        suspended.clear()
        maskTaskId = null
        takeoverCancelledFor = null
        handler.removeCallbacks(dimTick)
        tickScheduled = false
        activeTaskId = null
        activeTaskInfo = null
        isTakeover = false
        if (instance === this) {
            instance = null
        }
        log("FLYME_FREEFORM_CONTROLLER_DISPOSED")
    }

    // -------------------------------------------------------------------------
    // Window bookkeeping: which 小窗 owns the 黑色遮罩
    // -------------------------------------------------------------------------

    /** Run [block] on the main thread — all window state above is main-thread only. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    /** A 小窗 appeared (`MiuiFreeformModeTaskRepository.onTaskAppeared`). */
    fun onTaskAppeared(taskInfo: Any) = onMain {
        // The bookkeeping runs even while the Flyme-style takeover is switched off, so that turning the
        // setting on picks up a 小窗 that is already open (the tick re-evaluates within 700 ms).
        val taskId = rememberWindow(taskInfo)
        if (taskId <= 0) return@onMain
        log("TASK_APPEARED taskId=$taskId bounds=${getVisualBounds(taskInfo)}")
        // A brand new window is the one the user is looking at: it takes the mask.
        focusWindow(taskInfo, "APPEARED")
        // The window may need a frame or two before it has a leash; the tick would catch it, this
        // retry just makes the mask appear immediately.
        handler.postDelayed({ onMain { refreshMask("APPEARED_RETRY") } }, 200L)
    }

    /**
     * The raw shell signal: `MultiTaskingTaskRepository.onTaskAppeared(RunningTaskInfo, leash)`.
     *
     * Deliberately independent of the freeform pipeline above — that one only announces a window after
     * an "open transition ready" (`MultiTaskingTaskRepository.updateFreeformTaskInfo`), so a second
     * 小窗 can be created without ever producing `onTaskAppeared(MiuiFreeformModeTaskInfo)`. This hook
     * sees every task with `windowingMode == 5` the moment the shell gets it, which is what makes the
     * mask work for the second window.
     */
    fun onRawTaskAppeared(taskId: Int, leash: SurfaceControl?, repository: Any?) = onMain {
        if (taskId <= 0) return@onMain
        if (windows.containsKey(taskId) || suspended.containsKey(taskId)) return@onMain
        val info = resolveWindow(taskId, repository)
            ?: leash?.takeIf { it.isValid }?.let { RawWindow(it, taskId) }
            ?: return@onMain
        rememberWindow(info)
        log("TASK_APPEARED_RAW taskId=$taskId leash=${leash != null}")
        focusWindow(info, "APPEARED_RAW")
    }

    fun onTaskVanished(taskId: Int) = onMain {
        windows.remove(taskId)
        suspended.remove(taskId)
        releaseMiniAudio(taskId)
        if (takeoverCancelledFor == taskId) takeoverCancelledFor = null
        if (maskTaskId == taskId) maskTaskId = null
        // A pending 迷你小窗 conversion must not outlive its window.
        if (miniSwipeTaskId == taskId) cancelMiniFallback()
        // The window the mask followed is gone — forget it as "another 小窗" as well. Leaving it in
        // place made a *closed* window a dodge target for the next launch, and since a new 小窗 opens
        // exactly where the dead one was, that always "overlapped" (see [applyRomAvoidOffset]).
        if (activeTaskId == taskId) {
            activeTaskId = null
            activeTaskInfo = null
        }
        log("TASK_VANISHED taskId=$taskId remaining=${windows.keys}")
        refreshMask("VANISHED")
        if (windows.isEmpty()) backgroundDecoration?.release()
    }

    /** A 小窗 became the focused one (`MultiTaskingTaskRepository.updateFreeformTaskToTop`). */
    fun onTaskToFront(taskId: Int, repository: Any?) = onMain {
        if (taskId <= 0) return@onMain
        val info = resolveWindow(taskId, repository)
        if (info == null) {
            // A focused window we can neither remember nor resolve: the ROM's repository knows nothing
            // about it (its `needSkip` filtered it out). Keep whatever the mask has — dropping it here
            // is exactly the "第二个小窗没有遮罩" failure — but do not re-anchor to something unknown.
            log("DIM_TARGET_UNRESOLVED taskId=$taskId")
            refreshMask("TO_FRONT_UNRESOLVED")
            return@onMain
        }
        rememberWindow(info)
        if (maskTaskId != null && maskTaskId != taskId) {
            log(
                "DIM_FOCUS_SWITCH taskId=$taskId pkg=${FreeformTask.packageName(info)} " +
                    "from=$maskTaskId",
            )
        }
        focusWindow(info, "TO_FRONT")
    }

    /** The window the user is manipulating right now (拖拽/拉伸手势). */
    fun onTaskFocused(taskInfo: Any) = onMain {
        val taskId = rememberWindow(taskInfo)
        if (taskId <= 0) return@onMain
        if (maskTaskId != taskId && FreeformTask.isPlain(taskInfo)) {
            log("DIM_FOCUS_SWITCH taskId=$taskId pkg=${FreeformTask.packageName(taskInfo)} reason=GESTURE")
            focusWindow(taskInfo, "GESTURE")
        } else {
            refreshMask("GESTURE")
        }
    }

    fun onTaskModeChanged(taskInfo: Any, oldMode: Int, newMode: Int) = onMain {
        val taskId = rememberWindow(taskInfo)
        if (taskId <= 0) return@onMain
        log("TASK_MODE_CHANGED taskId=$taskId old=$oldMode new=$newMode")

        if (newMode == FreeformTask.MODE_NORMAL) {
            // 不是迷你小窗了：菜单没意义，替它静的音也还回去（见 [releaseMiniAudio]）。
            releaseMiniAudio(taskId)
            if (miniMenuOverlay.isShowingForTask(taskId)) {
                miniMenuOverlay.dismiss(animate = false)
            }
            // 迷你/贴边 → 普通小窗: the mask comes back for it.
            log("TASK_RESTORED_TO_FREEFORM taskId=$taskId oldMode=$oldMode -> RETAKE TAKEOVER")
            takeoverCancelledFor = null
            suspended.remove(taskId)
            gestureReset()
            focusWindow(taskInfo, "MODE_NORMAL")
            handler.postDelayed({ onMain { refreshMask("MODE_NORMAL_RETRY") } }, 350L)
        } else {
            // 迷你 / 贴边 / 退出中: this window must not carry the mask.
            if (maskTaskId == taskId) maskTaskId = null
            refreshMask("MODE_$newMode")
        }
    }

    fun onTaskClosing(taskId: Int) = onMain {
        releaseMiniAudio(taskId)
        if (miniMenuOverlay.isShowingForTask(taskId)) {
            miniMenuOverlay.dismiss(animate = false)
        }
        suspendWindow(taskId, "CLOSING")
    }

    /**
     * 窗口关掉了：把它还欠着的「静音」还回去（见 [MiniAppAudioHelper.release]）。
     *
     * 静音是这个迷你窗口的属性，窗口没了就没人能解除它了 —— 真会留下"从桌面打开同一个应用一声不
     * 出、也没有入口恢复"的死状态。先取包名，再把 taskInfo 从表里删掉。
     */
    private fun releaseMiniAudio(taskId: Int) {
        val taskInfo = miniTaskInfoMap.remove(taskId) ?: return
        val packageName = callNoArg(taskInfo, "getPackageName") as? String ?: return
        val runningTaskInfo = callNoArg(taskInfo, "getTaskInfo")
        val uid = runCatching {
            runningTaskInfo?.javaClass?.getField("effectiveUid")?.getInt(runningTaskInfo)
        }.getOrNull() ?: -1
        MiniAppAudioHelper.release(context, packageName, uid)
    }

    /** The user is grabbing this window's bottom caption: it becomes the mask's window. */
    fun onWindowInteracting(taskInfo: Any) = onMain {
        val taskId = rememberWindow(taskInfo)
        if (taskId <= 0) return@onMain
        if (activeTaskId != taskId || !isTakeover) {
            activeTaskId = taskId
            activeTaskInfo = taskInfo
            isTakeover = true
        }
    }

    /** Remember a window (and keep the richer object when we learn about the same one twice). */
    private fun rememberWindow(taskInfo: Any): Int {
        val taskId = getTaskId(taskInfo)
        if (taskId <= 0) return -1
        if (windows.size > MAX_TRACKED_WINDOWS) windows.clear()
        val previous = windows[taskId]
        // 小窗风暴熔断报点（用户 2026-10-23，见 FreeformStormGuard）：**新**小窗任务就是风暴的
        // 直接信号（酷安那次每 ~300ms 弹一对）。只在第一次记住时报 —— 同一任务的
        // raw→rich 升级、聚焦、回前台都不算第二次；正常人手速开不出这种密度。
        if (previous == null) {
            val stormPkg = FreeformTask.packageName(taskInfo)
            if (stormPkg != null && com.repl.bubbledrawer.lightopen.FreeformStormGuard.processWide.record(stormPkg)) {
                log("STORM_TRIGGER pkg=$stormPkg")
            }
        }
        val previousIsRaw = previous != null && FreeformTask.mode(previous) == FreeformTask.MODE_NONE
        if (previous == null || (previousIsRaw && FreeformTask.mode(taskInfo) != FreeformTask.MODE_NONE)) {
            windows[taskId] = taskInfo
        }
        return taskId
    }

    /**
     * The object behind a task id, asking the ROM's own repository when we have never seen it:
     * `getMiuiFreeformTaskInfo` (mode/geometry aware) first, then `getMultiTaskingTaskInfo`, which
     * always exists for a freeform task and still carries the leash the mask is layered against.
     */
    private fun resolveWindow(taskId: Int, repository: Any?): Any? {
        windows[taskId]?.let { return it }
        val repo = repository ?: getMultiTaskingTaskRepository()
        val rich = callWithTaskId(repo, "getMiuiFreeformTaskInfo", taskId)
        if (rich != null) return rich
        return callWithTaskId(repo, "getMultiTaskingTaskInfo", taskId)
    }

    private fun callWithTaskId(target: Any?, name: String, taskId: Int): Any? {
        if (target == null) return null
        return runCatching {
            target.javaClass.getMethod(name, Int::class.javaPrimitiveType).invoke(target, taskId)
        }.getOrNull()
    }

    /** `MiuiFreeformModeController.mMultiTaskingTaskRepository` — the freeform task state owner. */
    private fun getMultiTaskingTaskRepository(): Any? {
        savedRepository?.let { return it }
        val controller = getMiuiFreeformModeController() ?: return null
        val repo = runCatching {
            controller.javaClass.getDeclaredField("mMultiTaskingTaskRepository")
                .apply { isAccessible = true }.get(controller)
        }.getOrNull() ?: return null
        savedRepository = repo
        return repo
    }

    /** A window known only by task id + leash (the repository could not hand us the real objects). */
    private class RawWindow(private val surface: SurfaceControl, private val id: Int) {
        // Deliberately methods, not properties: `getLeash`/`getTaskId` are what FreeformTask looks up.
        fun getLeash(): SurfaceControl = surface
        fun getTaskId(): Int = id
        /** Always a freeform task: the hook that creates this only fires for `windowingMode == 5`. */
        fun getWindowingMode(): Int = FreeformTask.WINDOWING_MODE_FREEFORM
    }

    // -------------------------------------------------------------------------
    // Mask lifecycle
    // -------------------------------------------------------------------------

    /** Point the mask at a window and show it (main thread). */
    private fun focusWindow(taskInfo: Any, reason: String) {
        val taskId = getTaskId(taskInfo)
        if (taskId <= 0) return
        // A suspension (closing / mid-transition) is deliberately NOT cleared here: an incidental event
        // during a close animation must not put the mask back over a window that is going away. It
        // expires on its own (SUSPEND_MS) or is cleared by an explicit 普通小窗 restore.
        maskTaskId = taskId
        activeTaskId = taskId
        activeTaskInfo = taskInfo
        isTakeover = true
        log(
            "DIM_TARGET taskId=$taskId reason=$reason pkg=${FreeformTask.packageName(taskInfo)} " +
                "mode=${FreeformTask.mode(taskInfo)}",
        )
        refreshMask(reason)
    }

    /**
     * Bring the mask in line with reality: show it under the window that should own it, hide it when
     * there is none. Called on every window event *and* from the 700 ms tick, so a missed event (or a
     * window the freeform pipeline never announced) cannot leave the user without a mask.
     */
    private fun refreshMask(reason: String) {
        try {
            applyMaskState(reason)
        } finally {
            // Keep the self-heal alive for as long as any 小窗 is around, whichever way we exited.
            if (windows.isNotEmpty()) scheduleTick()
        }
    }

    private fun applyMaskState(reason: String) {
        val snap = RemotePrefs.read(prefs)
        if (!snap.flymeFreeformEnabled || (!snap.flymeFreeformDimBg && !snap.flymeFreeformOutsideDismiss)) {
            clearMask("DISABLED")
            return
        }
        pruneSuspended()
        pruneDeadWindows()
        val target = currentTarget()
        if (target == null) {
            clearMask("NO_WINDOW")
            return
        }
        val taskId = getTaskId(target)
        if (takeoverCancelledFor == taskId) {
            clearMask("TAKEOVER_CANCELLED")
            return
        }
        maskTaskId = taskId
        activeTaskId = taskId
        activeTaskInfo = target
        isTakeover = true
        showDim(target)
    }

    /**
     * Forget windows whose surface is already gone. The vanish signal travels the same freeform
     * pipeline the appear signal does, so a window that was never announced there would otherwise stay
     * in the map forever and keep the mask pointed at a dead leash.
     */
    private fun pruneDeadWindows() {
        if (windows.isEmpty()) return
        val dead = windows.entries.filter { (id, info) ->
            val leash = FreeformTask.leash(info)
            leash != null && !leash.isValid && suspended.containsKey(id)
        }
        if (dead.isEmpty()) return
        dead.forEach { (id, _) ->
            windows.remove(id)
            suspended.remove(id)
            if (maskTaskId == id) maskTaskId = null
        }
        logDimOnce("DIM_WINDOW_PRUNED taskIds=${dead.map { it.key }}")
    }

    /** The window that should own the mask: the focused one, else the newest usable one. */
    private fun currentTarget(): Any? {
        maskTaskId?.let { id -> windows[id]?.let { if (isTargetable(id, it)) return it } }
        val entries = windows.entries.toList()
        for (i in entries.indices.reversed()) {
            val (id, info) = entries[i]
            if (isTargetable(id, info)) return info
        }
        return null
    }

    fun isWindowSuspended(taskId: Int): Boolean {
        val exp = suspended[taskId] ?: return false
        if (exp >= SystemClock.uptimeMillis()) return true
        suspended.remove(taskId, exp)
        return false
    }

    private fun isTargetable(taskId: Int, info: Any): Boolean {
        if (isWindowSuspended(taskId)) return false
        // A window whose surface is already gone must not be anchored to (the ROM may not have told us
        // it vanished yet); the entry itself stays until the tick can prove every window is gone.
        if (!isLive(info)) return false
        return FreeformTask.isPlain(info)
    }

    /** False only once the window's surface has really been released (unknown leashes count as live). */
    private fun isLive(info: Any): Boolean {
        val leash = FreeformTask.leash(info) ?: return true
        return leash.isValid
    }

    private fun clearMask(reason: String) {
        if (maskTaskId != null || isTakeover) logDimOnce("DIM_HIDDEN reason=$reason")
        maskTaskId = null
        isTakeover = false
        hideDim()
    }

    /** Keep the mask away from a window for a while (it is closing, or mid-transition). */
    private fun suspendWindow(taskId: Int?, reason: String) {
        if (taskId == null || taskId <= 0) return
        suspended[taskId] = SystemClock.uptimeMillis() + SUSPEND_MS
        onMain {
            if (maskTaskId == taskId) maskTaskId = null
            log("DIM_SUSPENDED taskId=$taskId reason=$reason")
            isTakeover = false
            hideDim()
        }
    }

    /** Suspend the window the current bottom-caption gesture was performed on. */
    private fun suspendGesture(reason: String) {
        suspendWindow(if (gestureTaskId > 0) gestureTaskId else activeTaskId, reason)
    }

    /** 上滑悬停: the user wants the ROM's own 小窗 — no mask for this window any more. */
    private fun cancelTakeover(reason: String) {
        onMain {
            val taskId = activeTaskId
            takeoverCancelledFor = taskId
            maskTaskId = null
            isTakeover = false
            log("DIM_TAKEOVER_CANCELLED taskId=$taskId reason=$reason")
            hideDim()
        }
    }

    private fun pruneSuspended() {
        val now = SystemClock.uptimeMillis()
        suspended.entries.removeAll { it.value < now }
    }

    private fun gestureReset() {
        gestureHoverArmed = false
        if (hoverScheduled) {
            handler.removeCallbacks(hoverRunnable)
            hoverScheduled = false
        }
    }

    /**
     * The 700 ms self-heal: re-asserts the mask while any 小窗 is alive, so a layer the ROM re-ordered,
     * a leash that was not ready yet, or a focus change nobody reported cannot leave it missing.
     */
    private fun scheduleTick() {
        if (tickScheduled) return
        tickScheduled = true
        handler.postDelayed(dimTick, DIM_TICK_MS)
    }

    private val dimTick = object : Runnable {
        override fun run() {
            tickScheduled = false
            refreshMask("TICK")
            if (windows.isEmpty()) return
            if (windows.values.any { isLive(it) }) {
                scheduleTick()
            } else {
                // Every remembered window's surface is gone and the ROM never said so: drop the map so
                // the next 小窗 starts from a clean slate (and the tick stops burning frames).
                logDimOnce("DIM_WINDOWS_CLEARED taskIds=${windows.keys}")
                windows.clear()
                suspended.clear()
                maskTaskId = null
                clearMask("NO_LIVE_WINDOW")
            }
        }
    }

    /** Log a mask line only when it differs from the previous one (the tick runs forever). */
    private fun logDimOnce(message: String) {
        if (message == lastDimLog) return
        lastDimLog = message
        log(message)
    }

    // -------------------------------------------------------------------------
    // Bottom Caption Gestures (Action delegates)
    // -------------------------------------------------------------------------

    fun onBottomCaptionDown(y: Float, taskId: Int) {
        // Recorded synchronously (this runs on the WMShell thread): a gesture transition must suspend
        // the window it was performed on, not whatever the main thread last decided the mask follows.
        gestureTaskId = taskId
        gestureDownY = y
        gestureDownTime = SystemClock.uptimeMillis()
        gestureHoverArmed = false
        // A new gesture supersedes whatever the previous one scheduled (see [scheduleMiniFallback]):
        // if that conversion has not happened yet the user has moved on, so it must not land later.
        cancelMiniFallback()
        if (hoverScheduled) {
            handler.removeCallbacks(hoverRunnable)
            hoverScheduled = false
        }
    }

    fun onBottomCaptionMove(dy: Float, snap: RemotePrefs.Snapshot) {
        if (snap.flymeFreeformSwipeUpHoldFree && !gestureHoverArmed) {
            if (dy < -45f) {
                if (!hoverScheduled) {
                    hoverScheduled = true
                    handler.postDelayed(hoverRunnable, 300L)
                }
            } else if (dy > -25f && hoverScheduled) {
                handler.removeCallbacks(hoverRunnable)
                hoverScheduled = false
            }
        }
    }

    fun onBottomCaptionUp(dy: Float, snap: RemotePrefs.Snapshot, proceed: () -> Any?): Any? {
        if (hoverScheduled) {
            handler.removeCallbacks(hoverRunnable)
            hoverScheduled = false
        }

        if (gestureHoverArmed) {
            log("ACTION_UP_HOVER_REBOUND_TO_FREEFORM taskId=$activeTaskId dy=$dy")
            return try {
                sForceRebound = true
                proceed()
            } finally {
                sForceRebound = false
                gestureHoverArmed = false
                // The user asked for the ROM's own 小窗: no mask for this window from now on.
                cancelTakeover("HOVER_REBOUND")
            }
        }

        if (dy < -50f && snap.flymeFreeformSwipeUpMini) {
            val taskId = if (gestureTaskId > 0) gestureTaskId else (activeTaskId ?: 0)
            log("ACTION_UP_SWIPE_MINI taskId=$taskId dy=$dy")
            return try {
                miniSwipeTaskId = taskId
                miniSwipeConvertedAt = 0L
                sForceMiniTransition = true
                proceed()
                // HyperOS 4 and later decide this gesture inside `onBottomCaptionHandleMotionEvents`
                // itself: with the velocity pinned to 0 and `getMiniBottomUpLimit()` inflated (see
                // [hookVelocityMonitor] / [hookMiniBottomUpLimit]) its ACTION_UP branch turns the window
                // into 迷你小窗 before the release animation is even built — and announces that through
                // `onTaskModeChanged`, which is what [miniSwipeConvertedAt] records synchronously.
                //
                // HyperOS 3 has no such branch (its ACTION_UP can only close, fullscreen or spring
                // back), so those two hooks change nothing there and the swipe-up simply did nothing.
                // Its ROM still knows how to *do* the conversion — `MiuiFreeformModeController
                // .fromFreeformToMini(int)` is the entry point its own 控制中心 uses — so that is driven
                // here instead, once the gesture's release animation has let go of the window.
                if (miniSwipeConvertedAt != 0L) {
                    log("MINI_SWIPE_NATIVE taskId=$taskId")
                } else {
                    log("MINI_SWIPE_FALLBACK taskId=$taskId")
                    scheduleMiniFallback(taskId)
                }
            } finally {
                sForceMiniTransition = false
                // 迷你小窗 carries no mask: keep it away while the transition runs (the mode change
                // that follows settles it for good).
                suspendGesture("GESTURE_MINI")
            }
        }


        return proceed()
    }

    fun onBottomCaptionCancel() {
        if (hoverScheduled) {
            handler.removeCallbacks(hoverRunnable)
            hoverScheduled = false
        }
        gestureHoverArmed = false
    }

    // -------------------------------------------------------------------------
    // 上滑小白条 → 迷你小窗 where the ROM cannot do it itself (HyperOS 3)
    // -------------------------------------------------------------------------

    /**
     * Drive the ROM's own 小窗 → 迷你小窗 conversion for the window whose 小白条 was just swiped up.
     *
     * Why this exists: the gesture used to work by *steering* the ROM — `getVelocity()` was pinned to 0
     * and `getMiniBottomUpLimit()` inflated, which makes HyperOS 4's `onBottomCaptionHandleMotionEvents`
     * take its 迷你 branch. HyperOS 3's ACTION_UP has no 迷你 branch at all (only close / fullscreen /
     * spring back), so on that ROM the same steering left the window animating back to where it started
     * — the reported bug. The conversion itself is not missing there, only this gesture's route to it:
     * `MiuiFreeformModeController.fromFreeformToMini(int)` is what the ROM's own 控制中心 calls, and it
     * does the whole job (mode 1, mini scale, side placement, shell transaction, 迷你动画, avoid pass).
     *
     * Two things make this a retry loop rather than one call:
     *
     *  - That method refuses to run while the window is animating (`isRunningContinuousAnimation()`, i.e.
     *    any of animation types 1/2/3 still registered — the bottom-caption drag registers 2). The
     *    release animation started by `proceed()` clears it when it finishes, and how long that takes is
     *    not observable from here, so the call is simply repeated until it takes. A repeat *after* it
     *    worked is harmless: `fromFreeformToMini` bails out as soon as the window is not in the normal
     *    state any more, and the loop stops on [miniSwipeConvertedAt] anyway.
     *  - If it still has not taken after [MINI_FALLBACK_UNBLOCK_AT] attempts, the release marker is the
     *    only thing that can still be holding it (a release animation that never reports completion
     *    leaves `isRunningContinuousAnimation()` true forever). Clearing that one marker is then the
     *    difference between "the gesture does nothing" and "the gesture works": the ROM's own conversion
     *    takes over the window from wherever it is, and its 迷你动画 supersedes the release animation.
     */
    private fun scheduleMiniFallback(taskId: Int) {
        if (taskId <= 0) {
            log("MINI_FALLBACK_NO_TASK")
            return
        }
        val generation = ++miniFallbackGeneration
        val startedAt = SystemClock.uptimeMillis()
        var attempt = 0
        var unblocked = false

        val step = object : Runnable {
            override fun run() {
                if (generation != miniFallbackGeneration) return
                if (miniSwipeConvertedAt != 0L) {
                    log("MINI_FALLBACK_CONVERTED taskId=$taskId afterMs=${SystemClock.uptimeMillis() - startedAt}")
                    return
                }
                attempt++
                if (attempt == MINI_FALLBACK_UNBLOCK_AT && !unblocked) {
                    unblocked = true
                    log("MINI_FALLBACK_UNBLOCK taskId=$taskId animating=${animatingTypes(taskId)}")
                    clearReleaseAnimationMarker(taskId)
                }
                if (attempt > MINI_FALLBACK_ATTEMPTS) {
                    log(
                        "MINI_FALLBACK_GAVE_UP taskId=$taskId attempts=$attempt " +
                            "afterMs=${SystemClock.uptimeMillis() - startedAt} animating=${animatingTypes(taskId)}",
                    )
                    return
                }
                if (!fromFreeformToMini(taskId, verbose = false)) {
                    log("MINI_FALLBACK_UNAVAILABLE taskId=$taskId")
                    return
                }
                handler.postDelayed(this, MINI_FALLBACK_INTERVAL_MS)
            }
        }
        handler.postDelayed(step, MINI_FALLBACK_INTERVAL_MS)
    }

    /** Forget whatever [scheduleMiniFallback] was doing — another gesture, a reload, a vanished window. */
    private fun cancelMiniFallback() {
        miniFallbackGeneration++
    }

    /**
     * Drop the bottom-caption drag marker (`removeRunningAnimationType(2)`) so the ROM's conversion is
     * no longer held back by [MINI_FALLBACK_UNBLOCK_AT]'s "the release animation never finished" case.
     */
    private fun clearReleaseAnimationMarker(taskId: Int): Boolean {
        val info = windows[taskId] ?: return false
        return runCatching {
            info.javaClass
                .getMethod("removeRunningAnimationType", Int::class.javaPrimitiveType)
                .invoke(info, ANIMATION_TYPE_BOTTOM_CAPTION_DRAG)
            true
        }.getOrDefault(false)
    }

    /** `MiuiFreeformModeTaskInfo.mAnimatingTypes` — diagnosis only, so the unblock is explainable. */
    private fun animatingTypes(taskId: Int): Any? {
        val info = windows[taskId] ?: return null
        return runCatching {
            info.javaClass.getDeclaredField("mAnimatingTypes").apply { isAccessible = true }.get(info)
        }.getOrNull()
    }

    // -------------------------------------------------------------------------
    // Window Controller Actions (Executed on WMShell Executor thread)
    // -------------------------------------------------------------------------

    fun executeOnShell(action: () -> Unit) {
        val controller = getMiuiFreeformModeController()
        val executor = controller?.let {
            runCatching {
                it.javaClass.getDeclaredField("mMainExecutor").apply { isAccessible = true }.get(it) as? Executor
            }.getOrNull()
        } ?: savedExecutor

        if (executor != null) {
            executor.execute(action)
        } else {
            handler.post(action)
        }
    }

    fun closeTask(taskInfo: Any, taskId: Int) {
        executeOnShell {
            val controller = getMiuiFreeformModeController()
            var closed = false
            if (controller != null) {
                // 1. Try starter.closeFullOrFreeform(runningTaskInfo)
                closed = runCatching {
                    val starter = controller.javaClass.getDeclaredField("mMulWinSwitchAnimStarter")
                        .apply { isAccessible = true }.get(controller)
                    val running = getRunningTaskInfo(taskInfo)
                    if (starter != null && running != null) {
                        val m = starter.javaClass.getMethod("closeFullOrFreeform", ActivityManager.RunningTaskInfo::class.java)
                        m.invoke(starter, running)
                        log("CLOSED_VIA_STARTER taskId=$taskId")
                        true
                    } else false
                }.getOrDefault(false)

                // 2. Fallback to exitFreeformTask(taskId, true)
                if (!closed) {
                    runCatching {
                        val m = controller.javaClass.methods.firstOrNull {
                            it.name == "exitFreeformTask" && it.parameterCount == 2
                        }
                        if (m != null) {
                            m.invoke(controller, taskId, true)
                            log("CLOSED_VIA_EXIT_TASK taskId=$taskId")
                            closed = true
                        }
                    }
                }
            }

            if (!closed) {
                runCatching {
                    val controlImpl = controller?.let {
                        it.javaClass.getDeclaredField("mMiuiFreeformModeControl").apply { isAccessible = true }.get(it)
                    }
                    if (controlImpl != null) {
                        val m = controlImpl.javaClass.methods.firstOrNull {
                            it.name == "exitFreeformTask" && it.parameterCount == 2
                        }
                        if (m != null) {
                            m.invoke(controlImpl, taskId, true)
                            log("CLOSED_VIA_CONTROL_IMPL taskId=$taskId")
                            closed = true
                        }
                    }
                }
            }

            // 3. Fallback to ActivityTaskManager.removeTask
            if (!closed) {
                runCatching {
                    val atm = classLoader.loadClass("android.app.ActivityTaskManager")
                    val service = atm.getMethod("getService").invoke(null)
                    service.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(service, taskId)
                    log("CLOSED_VIA_ATM_REMOVE_TASK taskId=$taskId")
                }
            }
        }
    }

    /**
     * `MiuiFreeformModeController.fromFreeformToMini(int)` — the ROM's own 小窗 → 迷你小窗 conversion
     * (mode 1, mini scale, side placement, shell transaction, 迷你动画, avoid pass). It is what the
     * ROM's own 控制中心 uses, and on HyperOS 3 it is the *only* route into 迷你小窗 that exists — the
     * 小白条 gesture has no branch for it there (see [scheduleMiniFallback]).
     *
     * Fire-and-forget by design: the invocation has to happen on the WMShell thread, and the ROM refuses
     * it while the window is still animating, so callers retry instead of reading a result. What this
     * returns is therefore only "keep retrying" — false means this ROM has no such entry point at all.
     *
     * @param verbose log the dispatch; the retry loop passes false and logs its own progress.
     */
    fun fromFreeformToMini(taskId: Int, verbose: Boolean = true): Boolean {
        val target = miniConversionTarget()
        if (target == null) {
            // Two different "no": the controller is not built yet (retry — a 小窗 cannot exist before it
            // is, but nothing is lost by waiting) versus this ROM having no such entry point at all.
            if (verbose) log("MINI_CONVERSION_UNAVAILABLE taskId=$taskId")
            return !miniConversionUnavailable
        }
        val (method, receiver) = target
        executeOnShell {
            runCatching { method.invoke(receiver, taskId) }.onFailure {
                log("MINI_CONVERSION_FAILED taskId=$taskId via=${method.declaringClass.simpleName}", it)
            }
        }
        if (verbose) log("MINI_CONVERSION_DISPATCHED taskId=$taskId via=${method.declaringClass.simpleName}")
        return true
    }

    /**
     * Restores a mini window back to standard freeform mode (放大 / 还原).
     */
    fun restoreMiniToFreeform(taskId: Int): Boolean {
        val controller = getMiuiFreeformModeController()
        if (controller == null) {
            log("RESTORE_MINI_UNAVAILABLE controller is null taskId=$taskId")
            return false
        }
        executeOnShell {
            runCatching {
                val miniHandler = runCatching {
                    controller.javaClass.getDeclaredField("mMiuiFreeformModeMiniStateHandler")
                        .apply { isAccessible = true }.get(controller)
                }.getOrNull()
                if (miniHandler != null) {
                    val method = miniHandler.javaClass.getMethod("restoreMiniToFreeformMode", Int::class.javaPrimitiveType)
                    method.isAccessible = true
                    method.invoke(miniHandler, taskId)
                    log("RESTORE_MINI_TO_FREEFORM taskId=$taskId via miniHandler")
                    return@executeOnShell
                }
                val method = controller.javaClass.declaredMethods.firstOrNull {
                    it.name == "restoreMiniToFreeformMode" && it.parameterCount == 1
                }
                if (method != null) {
                    method.isAccessible = true
                    method.invoke(controller, taskId)
                    log("RESTORE_MINI_TO_FREEFORM taskId=$taskId via controller")
                    return@executeOnShell
                }
            }.onFailure {
                log("RESTORE_MINI_TO_FREEFORM_FAILED taskId=$taskId", it)
            }
        }
        return true
    }

    /**
     * 迷你小窗「放大 / 还原」：把窗口缩放到「正常迷你的 1.5 倍」，再点一次回到正常迷你。
     *
     * 为什么不再自己动 SurfaceControl（2026-10-18，用户报「移动的时候大迷你小窗状态可能会重置」
     * 「有时候会丢动画」）：那条 leash 的主人是 WMShell，拖动的缩放取自
     * `MiuiFreeformModeTaskInfo.setDownParams:1167-1184`（`getDownScaleX` = `baseAnimTarget.scaleX`），
     * 我们只改 SurfaceControl 不改 baseAnimTarget，于是第一次拖动就被 ROM 拉回迷你尺寸；而两条线程
     * 同时往同一条 leash 提交事务，还会让其中一方的动画直接消失。
     *
     * 现在整件事交给 ROM 自己的迷你动画入口：
     * `MiuiFreeformModeAnimation.startGestureAnimation(11 /*FREEFORM_TO_MINI*/, taskInfo, target):5527-5529`
     * → `applyFreeformToMiniAnimation:2348-2406`：
     *
     *  - Folme 从 `baseAnimTarget` 的当前几何动画到 target 的几何（原生弹簧/阴影/圆角，不丢帧）；
     *  - 同时下发 `MiuiFreeFormInfoChange.setMiuiFreeformScale(target.scale)` + `setBounds(token, …)`，
     *    由 `MiuiFreeFormManagerService.setFreeformTaskChangesIfNeed:3221-3222` 落到 `Task.setMiuiFreeformScale`，
     *    框架的 `MiuiFreeFormStackInfo.freeFormScale` 也跟着变 —— 命中区域、角落吸附、框架侧查询一致；
     *  - 动画结束（或被打断）时 `MultiTaskingFolmeAnimExecutor:104-106/123-126` →
     *    `MultiTaskingFolmeControl.updateBaseAnimParam:519-525` 把最终 scale **写回 baseAnimTarget**，
     *    所以放大状态从这一刻起属于 ROM：之后的拖动、松手、避让、翻转全按放大后的 scale 算，
     *    我们不需要再补任何一帧，也不用记「哪个 taskId 是放大的」。
     *
     * 目标几何（scale / 落点）由纯逻辑 [decideMiniScale] 算，规则是「窗口靠哪边就往哪边长」。
     *
     * 失败兜底：ROM 结构对不上（读不到几何、没有这个方法）时退回 ROM 自己的
     * `restoreMiniToFreeformMode`（[restoreMiniToFreeform]），这一下点击至少不是没反应。
     */
    fun toggleMiniWindowScale(taskId: Int): Boolean {
        val taskInfo = miniTaskInfoMap[taskId]
        if (taskInfo == null) {
            log("TOGGLE_MINI_SCALE_FAILED reason=no_task_info taskId=$taskId")
            return false
        }
        val packageName = callNoArg(taskInfo, "getPackageName") as? String
        // 读几何、建动画参数、起动画都放在 WMShell 线程上：taskInfo 是它的状态，跨线程读会读到
        // 动画中间的一帧（scale 可能还是 0），也会和 `updateBaseAnimParam` 打架。
        executeOnShell {
            val decision = miniScaleDecisionFor(taskInfo)
            if (decision == null) {
                log("TOGGLE_MINI_SCALE_FALLBACK reason=no_geometry taskId=$taskId")
                restoreMiniToFreeform(taskId)
                return@executeOnShell
            }
            val target = buildMiniScaleAnimTarget(taskInfo, decision)
            val animation = getMiuiFreeformModeAnimation()
            if (target == null || animation == null || !dispatchMiniScaleAnimation(animation, taskInfo, target)) {
                log(
                    "TOGGLE_MINI_SCALE_FALLBACK reason=no_animation taskId=$taskId " +
                        "target=${target != null} animation=${animation != null}",
                )
                restoreMiniToFreeform(taskId)
                return@executeOnShell
            }
            log(
                "TOGGLE_MINI_SCALE taskId=$taskId enlarged=${decision.enlarged} " +
                    "scale=${decision.scale} left=${decision.left} top=${decision.top}",
            )
            // 保存「这个应用下次进迷你也要这么大」：失败/回退路径不写，避免把没有真正发生的状态记下来。
            if (packageName != null) saveMiniEnlargeState(packageName, decision.enlarged)
        }
        return true
    }

    /** 读 ROM 的几何 + 算下一状态；读不出来返回 null（调用方兜底）。只在 WMShell 线程上调用。 */
    private fun miniScaleDecisionFor(taskInfo: Any): MiniScaleDecision? {
        val scale = (callNoArg(taskInfo, "getFreeformScale") as? Number)?.toFloat() ?: return null
        // `getBounds()` = 未缩放 task bounds 摆在视觉位置；`getScaledBounds()` = 它原地放大后的矩形。
        val base = callNoArg(taskInfo, "getBounds") as? Rect ?: return null
        val visual = (callNoArg(taskInfo, "getScaledBounds") as? Rect)?.takeUnless { it.isEmpty } ?: base
        val normal = normalMiniScaleOf(taskInfo, base) ?: return null
        val dm = context.resources.displayMetrics
        // 倍率来自设置里的滑块（默认 1.5 倍），每次点击现读：改完滑块不用重启系统界面。
        val ratio = miniEnlargeRatioFromPct(RemotePrefs.read(prefs).flymeMiniEnlargePct)
        return decideMiniScale(
            taskWidth = base.width(),
            taskHeight = base.height(),
            currentScale = scale,
            visualLeft = visual.left,
            visualTop = visual.top,
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            normalMiniScale = normal,
            margin = (dm.density * 8f).toInt(),
            ratio = ratio,
        )
    }

    /** 迷你窗口此刻算不算放大状态（菜单图标用，判断规则与 [decideMiniScale] 完全一致）。 */
    fun isMiniEnlarged(taskInfo: Any): Boolean {
        val scale = (callNoArg(taskInfo, "getFreeformScale") as? Number)?.toFloat() ?: return false
        val base = callNoArg(taskInfo, "getBounds") as? Rect ?: return false
        val normal = normalMiniScaleOf(taskInfo, base) ?: return false
        return isMiniScaleEnlarged(scale, normal)
    }

    /**
     * ROM 眼里的「正常迷你缩放」：`MiuiMultiWindowUtils.getMiniFreeformScale(context, landscape, 原 bounds, pkg)`
     * —— 迷你窗口每次进迷你都由 ROM 用这个方法重新算（`MiuiFreeformModeAnimation:3941/4083/4613/5863`），
     * 所以它才是「不放大时应该多大」的权威，不需要我们记。
     */
    private fun normalMiniScaleOf(taskInfo: Any, originBounds: Rect): Float? {
        val packageName = callNoArg(taskInfo, "getPackageName") as? String ?: return null
        val landscape = (callNoArg(taskInfo, "isLandscapeFreeform") as? Boolean) ?: false
        return withoutMiniScaleHook {
            runCatching {
                val utils = classLoader.loadClass("android.util.MiuiMultiWindowUtils")
                val method = utils.getMethod(
                    "getMiniFreeformScale",
                    Context::class.java,
                    Boolean::class.javaPrimitiveType,
                    Rect::class.java,
                    String::class.java,
                )
                (method.invoke(null, context, landscape, originBounds, packageName) as? Number)?.toFloat()
            }.getOrNull()
        }?.takeIf { it.isFinite() && it > 0f }
    }

    private fun buildMiniScaleAnimTarget(taskInfo: Any, decision: MiniScaleDecision): Any? {
        val base = callNoArg(taskInfo, "getBounds") as? Rect ?: return null
        return runCatching {
            val targetClass = Class.forName(
                "com.android.wm.shell.multitasking.common.animation.MultiTaskingAnimTarget",
                false,
                taskInfo.javaClass.classLoader,
            )
            val target = targetClass.getDeclaredConstructor().newInstance()
            // `setAnimParam(bounds, scaleX, scaleY, anchorY)` 的 bounds 是**未缩放**的 task bounds
            // 摆在目标位置（`MultiTaskingAnimTarget.getCurrentBounds:88-92`）：缩放由 scale 施加，
            // 视觉左上角就是 bounds.left/top。
            val rect = Rect(
                decision.left,
                decision.top,
                decision.left + base.width(),
                decision.top + base.height(),
            )
            targetClass.getMethod(
                "setAnimParam",
                Rect::class.java,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            ).invoke(target, rect, decision.scale, decision.scale, 0f)
            target
        }.onFailure {
            log("TOGGLE_MINI_SCALE_TARGET_FAILED", it)
        }.getOrNull()
    }

    /** 11 = ROM 的 `FREEFORM_TO_MINI` 手势动画 id（`MiuiFreeformModeAnimation.startGestureAnimation:5527`）。 */
    private fun dispatchMiniScaleAnimation(animation: Any, taskInfo: Any, target: Any): Boolean =
        runCatching {
            val method = animation.javaClass.methods.firstOrNull {
                it.name == "startGestureAnimation" &&
                    it.parameterCount == 3 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: return@runCatching false
            method.invoke(animation, 11, taskInfo, target)
            true
        }.onFailure {
            log("TOGGLE_MINI_SCALE_DISPATCH_FAILED", it)
        }.getOrDefault(false)

    private fun getMiuiFreeformModeAnimation(): Any? {
        val controller = getMiuiFreeformModeController() ?: return null
        return runCatching {
            controller.javaClass.getMethod("getMiuiFreeformModeAnimation").invoke(controller)
        }.getOrNull() ?: runCatching {
            controller.javaClass.getDeclaredField("mMiuiFreeformModeAnimation")
                .apply { isAccessible = true }
                .get(controller)
        }.getOrNull()
    }

    private fun callNoArg(receiver: Any, name: String): Any? =
        runCatching { receiver.javaClass.getMethod(name).invoke(receiver) }.getOrNull()

    /**
     * The ROM's conversion entry point: the controller's own `fromFreeformToMini(int)` when it has one
     * (HyperOS 3/4: private, hence `declaredMethods`), else the same method on the control facade the
     * ROM exposes to its own SystemUI. Resolved once and cached; a ROM with neither is remembered as
     * such so the retry loop stops immediately instead of hammering reflection.
     */
    private fun miniConversionTarget(): Pair<Method, Any>? {
        miniConversionMethod?.let { method -> miniConversionReceiver?.let { return method to it } }
        if (miniConversionUnavailable) return null

        val controller = getMiuiFreeformModeController() ?: return null
        val direct = declaredOneArg(controller.javaClass, "fromFreeformToMini")
        if (direct != null) {
            direct.isAccessible = true
            miniConversionMethod = direct
            miniConversionReceiver = controller
            return direct to controller
        }

        val impl = runCatching {
            val implCls = classLoader.loadClass(
                "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeController\$MiuiFreeformModeControlImpl",
            )
            val ctor = implCls.getDeclaredConstructor(controller.javaClass, Int::class.javaPrimitiveType)
            ctor.isAccessible = true
            ctor.newInstance(controller, 0)
        }.getOrNull()
        val viaImpl = impl?.let { declaredOneArg(it.javaClass, "fromFreeformToMini") }
        if (viaImpl != null && impl != null) {
            viaImpl.isAccessible = true
            miniConversionMethod = viaImpl
            miniConversionReceiver = impl
            return viaImpl to impl
        }

        miniConversionUnavailable = true
        log("MINI_CONVERSION_NOT_ON_THIS_ROM controller=${controller.javaClass.simpleName}")
        return null
    }

    /** `name(int)` on [cls] or any of its supertypes — the ROM keeps these private, so `declared` too. */
    private fun declaredOneArg(cls: Class<*>, name: String): Method? =
        (cls.declaredMethods.toList() + cls.methods.toList()).firstOrNull {
            it.name == name && it.parameterCount == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
        }

    /** Cached answer of [miniConversionTarget]; volatile because the retry loop runs on the main thread. */
    @Volatile private var miniConversionMethod: Method? = null
    @Volatile private var miniConversionReceiver: Any? = null
    @Volatile private var miniConversionUnavailable = false

    fun fullscreenFreeformTask(taskId: Int) {
        executeOnShell {
            val controller = getMiuiFreeformModeController() ?: return@executeOnShell
            runCatching {
                val m = (controller.javaClass.declaredMethods + controller.javaClass.methods).firstOrNull {
                    it.name == "fullscreenFreeformWithoutAnim" && it.parameterCount == 2
                }
                if (m != null) {
                    m.isAccessible = true
                    m.invoke(controller, taskId, true)
                    log("FULLSCREEN_VIA_CONTROLLER taskId=$taskId")
                } else {
                    val m2 = (controller.javaClass.declaredMethods + controller.javaClass.methods).firstOrNull {
                        it.name == "fullscreenFreeformTask" && it.parameterCount == 1
                    }
                    if (m2 != null) {
                        m2.isAccessible = true
                        m2.invoke(controller, taskId)
                        log("FULLSCREEN_VIA_CONTROLLER_TASK taskId=$taskId")
                    }
                }
            }
        }
    }

    fun getMiuiFreeformModeController(): Any? {
        savedController?.let { return it }

        val fromStub = runCatching {
            val cls = classLoader.loadClass("com.android.wm.shell.dagger.MultiTaskingControllerStub")
            val instance = cls.getMethod("getInstance").invoke(null)
            instance.javaClass.getMethod("getMiuiFreeformModeController").invoke(instance)
        }.getOrNull()
        if (fromStub != null) {
            savedController = fromStub
            val org = runCatching {
                fromStub.javaClass.getDeclaredField("mRootTaskDisplayAreaOrganizer").apply { isAccessible = true }.get(fromStub)
            }.getOrNull()
            if (org != null) savedOrganizer = org
            return fromStub
        }

        val fromImpl = runCatching {
            val cls = classLoader.loadClass("com.android.wm.shell.dagger.MultiTaskingControllerImpl")
            val instance = cls.getMethod("getInstance").invoke(null)
            cls.getMethod("getMiuiFreeformModeController").invoke(instance)
        }.getOrNull()
        if (fromImpl != null) {
            savedController = fromImpl
            val org = runCatching {
                fromImpl.javaClass.getDeclaredField("mRootTaskDisplayAreaOrganizer").apply { isAccessible = true }.get(fromImpl)
            }.getOrNull()
            if (org != null) savedOrganizer = org
            return fromImpl
        }

        return null
    }

    fun getRootTaskDisplayAreaOrganizer(): Any? {
        savedOrganizer?.let { return it }
        val ctrl = getMiuiFreeformModeController()
        if (ctrl != null) {
            val org = runCatching {
                ctrl.javaClass.getDeclaredField("mRootTaskDisplayAreaOrganizer").apply { isAccessible = true }.get(ctrl)
            }.getOrNull()
            if (org != null) {
                savedOrganizer = org
                return org
            }
        }
        return null
    }

    fun getTaskLeash(taskInfo: Any): SurfaceControl? {
        FreeformTask.leash(taskInfo)?.let { return it }
        return runCatching {
            taskInfo.javaClass.getMethod("getLeash").invoke(taskInfo) as? SurfaceControl
        }.getOrNull() ?: runCatching {
            val running = getRunningTaskInfo(taskInfo)
            running?.javaClass?.getField("leash")?.get(running) as? SurfaceControl
        }.getOrNull() ?: runCatching {
            taskInfo.javaClass.getDeclaredField("mLeash").apply { isAccessible = true }.get(taskInfo) as? SurfaceControl
        }.getOrNull()
    }

    // -------------------------------------------------------------------------
    // SurfaceControl Background Decoration & Outside Tap Interception
    // -------------------------------------------------------------------------

    /**
     * Put the mask under [taskInfo]'s window. Returns false when the window's leash is not available
     * yet — the 700 ms tick retries, which is what makes this reliable for a window that is still
     * being created.
     */
    fun showDim(taskInfo: Any): Boolean {
        val snap = RemotePrefs.read(prefs)
        if (!snap.flymeFreeformDimBg && !snap.flymeFreeformOutsideDismiss) {
            hideDim()
            return false
        }

        val organizer = getRootTaskDisplayAreaOrganizer()
        val leash = getTaskLeash(taskInfo)
        if (organizer == null || leash == null) {
            logDimOnce(
                "SHOW_DIM_DEFERRED taskId=${getTaskId(taskInfo)} organizer=${organizer != null} " +
                    "leash=${leash != null}",
            )
            return false
        }

        val blurRadius = 0
        val dimAlpha = if (snap.flymeFreeformDimBg) 0.35f else 0.0f
        val allowOutsideDismiss = snap.flymeFreeformOutsideDismiss

        logDimOnce(
            "SHOW_DIM_SURFACE taskId=${getTaskId(taskInfo)} blur=0 alpha=$dimAlpha " +
                "outsideDismiss=$allowOutsideDismiss",
        )
        if (Looper.myLooper() == Looper.getMainLooper()) {
            backgroundDecoration?.show(organizer, leash, blurRadius, dimAlpha, allowOutsideDismiss)
        } else {
            handler.post {
                backgroundDecoration?.show(organizer, leash, blurRadius, dimAlpha, allowOutsideDismiss)
            }
        }
        return true
    }

    fun hideDim() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            backgroundDecoration?.hide()
        } else {
            handler.post {
                backgroundDecoration?.hide()
            }
        }
    }

    private class FreeformBackgroundDecoration(
        private val context: Context,
        private val classLoader: ClassLoader,
        private val prefs: SharedPreferences,
        private val onOutsideClick: () -> Unit,
    ) {
        private var surfaceControl: SurfaceControl? = null
        private val transaction = SurfaceControl.Transaction()
        private val windowManager = context.getSystemService(WindowManager::class.java)
        private var isReceiverRegistered = false

        /** Whether the transaction currently has the layer visible (hide must stay idempotent). */
        private var shown = false

        /**
         * `SurfaceControl.Transaction` members, resolved once. The mask is re-asserted by a 700 ms tick,
         * so a `getMethod` per call would be pure overhead.
         */
        private val txSetTrustedOverlay by lazy { txMethod("setTrustedOverlay", SurfaceControl::class.java, java.lang.Boolean.TYPE) }
        private val txSetRelativeLayer by lazy { txMethod("setRelativeLayer", SurfaceControl::class.java, SurfaceControl::class.java, java.lang.Integer.TYPE) }
        private val txSetBlur by lazy { txMethod("setBackgroundBlurRadius", SurfaceControl::class.java, java.lang.Integer.TYPE) }
        private val txSetColor by lazy { txMethod("setColor", SurfaceControl::class.java, FloatArray::class.java) }
        private val txSetAlpha by lazy { txMethod("setAlpha", SurfaceControl::class.java, java.lang.Float.TYPE) }
        private val txShow by lazy { txMethod("show", SurfaceControl::class.java) }
        private val txHide by lazy { txMethod("hide", SurfaceControl::class.java) }
        private val txRemove by lazy { txMethod("remove", SurfaceControl::class.java) }

        private fun txMethod(name: String, vararg params: Class<*>): java.lang.reflect.Method? =
            runCatching { SurfaceControl.Transaction::class.java.getMethod(name, *params) }.getOrNull()

        private var touchDownX = 0f
        private var touchDownY = 0f
        private var touchDownTime = 0L

        private var lastTapUpTime = 0L
        private var lastTapUpX = 0f
        private var lastTapUpY = 0f

        fun show(
            organizer: Any,
            leash: SurfaceControl,
            blurRadius: Int,
            dimAlpha: Float,
            allowOutsideDismiss: Boolean,
        ) {
            if (surfaceControl == null) {
                val builder = SurfaceControl.Builder().setName("FlymeFreeformBackgroundDecoration")
                runCatching {
                    builder.javaClass.getMethod("setContainerLayer").invoke(builder)
                }
                val attachMethod = organizer.javaClass.getMethod(
                    "attachToDisplayArea",
                    Int::class.javaPrimitiveType,
                    SurfaceControl.Builder::class.java,
                )
                attachMethod.isAccessible = true
                attachMethod.invoke(organizer, 0, builder)
                surfaceControl = builder.build()
                shown = false
                log("BACKGROUND_SURFACE_CREATED")
            }

            val sc = surfaceControl ?: return
            runCatching {
                runCatching { txSetTrustedOverlay?.invoke(transaction, sc, true) }
                // Just below the window the mask belongs to. With two 小窗 open this is re-applied
                // whenever the focus moves (and every tick), so the mask always sits under the window
                // the user is working with instead of the one it was first created for.
                runCatching { txSetRelativeLayer?.invoke(transaction, sc, leash, -1) }
                runCatching { txSetBlur?.invoke(transaction, sc, blurRadius) }
                runCatching { txSetColor?.invoke(transaction, sc, floatArrayOf(0f, 0f, 0f)) }
                runCatching { txSetAlpha?.invoke(transaction, sc, dimAlpha) }
                runCatching { txShow?.invoke(transaction, sc) }
                transaction.apply()
                shown = true
            }.onFailure {
                log("BACKGROUND_SURFACE_SHOW_FAILED", it)
            }

            if (allowOutsideDismiss) {
                registerInputReceiver(sc)
            } else {
                unregisterInputReceiver(sc)
            }
        }

        fun hide() {
            lastTapUpTime = 0L
            val sc = surfaceControl ?: return
            unregisterInputReceiver(sc)
            if (!shown) return
            runCatching {
                runCatching { txHide?.invoke(transaction, sc) }
                transaction.apply()
                shown = false
                log("BACKGROUND_SURFACE_HIDDEN")
            }.onFailure {
                log("BACKGROUND_SURFACE_HIDE_FAILED", it)
            }
        }

        fun release() {
            lastTapUpTime = 0L
            val sc = surfaceControl ?: return
            unregisterInputReceiver(sc)
            runCatching {
                runCatching { txRemove?.invoke(transaction, sc) }
                transaction.apply()
                surfaceControl = null
                shown = false
                log("BACKGROUND_SURFACE_RELEASED")
            }.onFailure {
                log("BACKGROUND_SURFACE_RELEASE_FAILED", it)
            }
        }

        private fun registerInputReceiver(sc: SurfaceControl) {
            if (isReceiverRegistered) return
            runCatching {
                val tokenCls = classLoader.loadClass("android.window.InputTransferToken")
                val token = tokenCls.getConstructor().newInstance()
                val receiverInterface = classLoader.loadClass("android.view.SurfaceControlInputReceiver")

                val proxy = Proxy.newProxyInstance(classLoader, arrayOf(receiverInterface)) { instance, method, args ->
                    when (method.name) {
                        // Same proxy pitfall as the notification listener: a null `equals` result
                        // breaks any Set/List operation that compares this object.
                        "equals" -> instance === args?.firstOrNull()
                        "hashCode" -> System.identityHashCode(instance)
                        "toString" -> "SurfaceControlInputReceiverProxy"
                        // `onInputEvent` returns a PRIMITIVE boolean (consumed?). Returning null here
                        // unboxes to a NullPointerException inside InputEventReceiver.dispatchInputEvent
                        // on the very first event — i.e. as soon as the user taps outside the window.
                        "onInputEvent" -> handleInputEvent(args?.firstOrNull() as? InputEvent)
                        // Anything the framework adds later: answer with the return type's zero value
                        // instead of null, so a primitive can never be unboxed from nothing.
                        else -> proxyDefault(method.returnType)
                    }
                }

                val registerMethod = windowManager.javaClass.getMethod(
                    "registerUnbatchedSurfaceControlInputReceiver",
                    tokenCls,
                    SurfaceControl::class.java,
                    Looper::class.java,
                    receiverInterface,
                )
                registerMethod.invoke(windowManager, token, sc, Looper.getMainLooper(), proxy)
                isReceiverRegistered = true
                log("INPUT_RECEIVER_REGISTERED")
            }.onFailure {
                log("INPUT_RECEIVER_REGISTER_FAILED", it)
            }
        }

        private fun unregisterInputReceiver(sc: SurfaceControl) {
            if (!isReceiverRegistered) return
            runCatching {
                val unregisterMethod = windowManager.javaClass.getMethod(
                    "unregisterSurfaceControlInputReceiver",
                    SurfaceControl::class.java,
                )
                unregisterMethod.invoke(windowManager, sc)
                log("INPUT_RECEIVER_UNREGISTERED")
            }.onFailure {
                log("INPUT_RECEIVER_UNREGISTER_FAILED", it)
            }
            // Either the receiver is gone or WMS never had it for this surface; in both cases the layer
            // is no longer listening, so a stale `true` here would silently drop the next tap.
            isReceiverRegistered = false
        }

        private fun handleInputEvent(event: InputEvent?): Boolean {
            if (event !is MotionEvent) return false
            val slop = ViewConfiguration.get(context).scaledTouchSlop
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.x
                    touchDownY = event.y
                    touchDownTime = event.eventTime
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.x - touchDownX)
                    val dy = abs(event.y - touchDownY)
                    val elapsed = event.eventTime - touchDownTime
                    if (dx <= slop && dy <= slop && elapsed <= 400L) {
                        val snap = RemotePrefs.read(prefs)
                        if (snap.flymeFreeformOutsideDismissAction == RemotePrefs.DISMISS_OUTSIDE_DOUBLE) {
                            val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong().coerceAtLeast(350L)
                            val doubleTapSlop = ViewConfiguration.get(context).scaledDoubleTapSlop
                            val interval = event.eventTime - lastTapUpTime
                            val tapDx = abs(event.x - lastTapUpX)
                            val tapDy = abs(event.y - lastTapUpY)
                            if (interval in 40L..doubleTapTimeout && tapDx <= doubleTapSlop && tapDy <= doubleTapSlop) {
                                log("OUTSIDE_DOUBLE_TAP_DETECTED interval=$interval tapDx=$tapDx tapDy=$tapDy")
                                lastTapUpTime = 0L
                                onOutsideClick()
                            } else {
                                log("OUTSIDE_FIRST_TAP_RECORDED interval=$interval")
                                lastTapUpTime = event.eventTime
                                lastTapUpX = event.x
                                lastTapUpY = event.y
                            }
                        } else {
                            log("OUTSIDE_SINGLE_TAP_DETECTED dx=$dx dy=$dy elapsed=$elapsed")
                            lastTapUpTime = 0L
                            onOutsideClick()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    lastTapUpTime = 0L
                    return true
                }
                else -> return false
            }
        }
    }

    fun triggerHapticFeedback() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(25L)
            }
        }
    }

    // -------------------------------------------------------------------------
    // TaskInfo Reflection Helpers
    // -------------------------------------------------------------------------

    fun getVisualBounds(taskInfo: Any): Rect {
        // The window's own scaled rect is authoritative, and after 横屏 it is the only thing that
        // knows the scale the ROM settled on (hookFreeformScale clamps it to fit, which is not the
        // setting verbatim). The preference-based math below stays as the fallback for the objects
        // that cannot answer it (a bare `RawWindow` leash, a task mid-creation).
        FreeformTask.visualBounds(taskInfo)?.let { if (!it.isEmpty) return it }

        val isAnim = runCatching {
            taskInfo.javaClass.getMethod("isInAnimating").invoke(taskInfo) as? Boolean ?: false
        }.getOrDefault(false)

        val dest = if (isAnim) {
            runCatching {
                taskInfo.javaClass.getMethod("getDestinationBounds").invoke(taskInfo) as? Rect
            }.getOrNull()
        } else null

        val bounds = (if (dest != null && !dest.isEmpty) dest else null) ?: getTaskBounds(taskInfo)
        if (bounds != null && !bounds.isEmpty) {
            val snap = RemotePrefs.read(prefs)
            val targetScale = if (snap.flymeFreeformEnabled) {
                (snap.flymeFreeformScale.coerceIn(50, 95)) / 100f
            } else getTaskScale(taskInfo)
            val w = (bounds.width() * targetScale).toInt()
            val h = (bounds.height() * targetScale).toInt()
            return Rect(bounds.left, bounds.top, bounds.left + w, bounds.top + h)
        }

        val scaled = runCatching {
            taskInfo.javaClass.getMethod("getScaledBounds").invoke(taskInfo) as? Rect
        }.getOrNull()
        if (scaled != null && !scaled.isEmpty) {
            return Rect(scaled)
        }

        val dm = context.resources.displayMetrics
        val w = (dm.widthPixels * 0.80f).toInt()
        val h = (dm.heightPixels * 0.80f).toInt()
        val left = (dm.widthPixels - w) / 2
        val top = (dm.heightPixels - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    companion object {
        const val TAG = "BubbleDrawer"
        @Volatile var instance: FlymeFreeformController? = null
        @Volatile var suppressSingleTapTaskId: Int = -1

        /**
         * 聚合支付换窗的落位窗口：置位后的时限内，ROM 的避让（`avoidAsPossible`）一律跳过、
         * **不看**「禁止小窗偏移」开关 —— 换支付入口时旧窗正在关、新窗跟着就位，此时 ROM 的
         * 错开（78dp/44dp）会让支付小窗和它下面的支付条一起挪到偏位上。ContactBarController
         * 在发射支付启动前 [suppressAvoidOffsetFor]，新窗 adopt 到位（或超时）后清除。
         */
        @Volatile var avoidOffsetSuppressedUntil: Long = 0L

        fun suppressAvoidOffsetFor(durationMs: Long) {
            avoidOffsetSuppressedUntil = android.os.SystemClock.uptimeMillis() + durationMs
        }

        fun avoidOffsetSuppressionActive(): Boolean =
            android.os.SystemClock.uptimeMillis() < avoidOffsetSuppressedUntil


        @JvmStatic
        fun setDisableTouch(disabled: Boolean) {
            runCatching {
                val ctrl = instance?.getMiuiFreeformModeController() ?: savedController
                if (ctrl == null) {
                    log("SET_DISABLE_TOUCH_SKIPPED ctrl is null")
                    return
                }
                val method = ctrl.javaClass.declaredMethods.firstOrNull {
                    it.name == "setDisableTouch" && it.parameterTypes.firstOrNull() == Boolean::class.javaPrimitiveType
                }
                if (method != null) {
                    method.isAccessible = true
                    method.invoke(ctrl, disabled)
                    log("SET_DISABLE_TOUCH ctrl.setDisableTouch($disabled)")
                } else {
                    val gh = ctrl.javaClass.getDeclaredField("mMiuiFreeformModeGestureHandler")
                        .apply { isAccessible = true }.get(ctrl)
                    gh?.javaClass?.getMethod("setDisableTouch", Boolean::class.javaPrimitiveType)?.invoke(gh, disabled)
                    log("SET_DISABLE_TOUCH gh.setDisableTouch($disabled)")
                }
            }.onFailure {
                log("SET_DISABLE_TOUCH_FAILED disabled=$disabled error=${it.message}")
            }
        }

        /** Bound on the remembered window map (the ROM itself allows two 小窗). */
        private const val MAX_TRACKED_WINDOWS = 4

        /**
         * How long a window is left out of mask targeting after a deliberate hide (closing, or a
         * mini/fullscreen transition). Long enough to cover the animation, short enough that an
         * aborted transition cannot leave the mask off forever.
         */
        private const val SUSPEND_MS = 1200L

        /**
         * Mask re-assert interval. This is the self-healing part: a layer the ROM re-ordered, a leash
         * that was not ready when the window appeared, or a focus change nobody reported all get fixed
         * within one tick instead of leaving the user without 黑色遮罩 / 窗外点击关闭.
         */
        private const val DIM_TICK_MS = 700L

        /**
         * Freeform state fan-out for consumers that are independent of the Flyme-style takeover
         * (today: the floating contact bar). Set by `SystemUiHookInstaller` right after this
         * controller is created, cleared on dispose/hot reload.
         */
        @Volatile var freeformObserver: com.repl.bubbledrawer.contactbar.FreeformObserver? = null

        /** Captured `MiuiFreeformModeDisplayInfo` + its IME state (see [hookImeState]). */
        @Volatile private var imeDisplayInfo: Any? = null
        @Volatile private var imeShowing = false
        @Volatile private var imeHeight = 0
        @Volatile var currentModule: XposedModule? = null
        @Volatile var savedController: Any? = null
        @Volatile var savedExecutor: Executor? = null
        @Volatile var savedOrganizer: Any? = null
        @Volatile var savedContext: Context? = null

        fun getRootTaskDisplayAreaOrganizer(): Any? =
            instance?.getRootTaskDisplayAreaOrganizer() ?: savedOrganizer

        /** `MiuiFreeformModeController.mMultiTaskingTaskRepository` — task id → window object. */
        @Volatile var savedRepository: Any? = null
        @Volatile var sForceMiniTransition = false
        @Volatile var sForceFullscreenTransition = false
        @Volatile var sForceRebound = false

        /** `MiuiFreeformModeTaskInfo`'s bottom-caption drag marker — see [clearReleaseAnimationMarker]. */
        private const val ANIMATION_TYPE_BOTTOM_CAPTION_DRAG = 2

        /** Callback interval while waiting for the ROM to be able to convert 小窗 → 迷你小窗. */
        private const val MINI_FALLBACK_INTERVAL_MS = 60L

        /** Give up after this many attempts — far longer than any release animation (≈1.5 s). */
        private const val MINI_FALLBACK_ATTEMPTS = 25

        /** Attempt at which the release marker is cleared in case the release animation never ends. */
        private const val MINI_FALLBACK_UNBLOCK_AT = 10

        /**
         * How long the scale the module's own hook gave the ROM still counts as "the scale of the
         * window being placed" (see [appliedScaleFor]). The ROM asks for the scale and for the rect a
         * few instructions apart on the same thread, so a couple of seconds is generous — and short
         * enough that a later, unrelated placement never inherits a stale answer.
         */
        private const val APPLIED_SCALE_TTL_MS = 3000L

        /**
         * How long after the module centred the 迷你 → 普通 restore rect the ROM's own avoid pass may
         * still be recognised as "the one that would undo it" (see [hookRestoreAvoid]). The two calls
         * are adjacent statements inside one `singleTap`; anything older is somebody else's rect.
         */
        private const val RESTORE_GUARD_MS = 2000L

        /**
         * The window whose 小白条 up-swipe is being turned into 迷你小窗 right now, and when the ROM
         * announced that conversion (`onTaskModeChanged` to a non-normal mode), 0 while it has not.
         *
         * Both are written from the WMShell thread and read from the main thread, and the timestamp in
         * particular has to be visible the instant the gesture's `proceed()` returns: on HyperOS 4 the
         * ROM performs the conversion *inside* that call, and that is exactly how the module knows the
         * steering worked and no fallback is needed.
         */
        @Volatile var miniSwipeTaskId = 0
        @Volatile var miniSwipeConvertedAt = 0L

        /**
         * `MiuiFreeformModeTaskRepository.onTaskModeChanged` — on the WMShell thread, before the main
         * thread hears about it (see [miniSwipeConvertedAt]).
         */
        fun noteMiniSwipeConversion(taskInfo: Any, oldMode: Int, newMode: Int) {
            // Only the 迷你 states are evidence that the gesture did what it was asked to do: the same
            // repository call announces 贴边/全屏 conversions too, and those must not stop the fallback.
            if (newMode != FreeformTask.MODE_MINI && newMode != FreeformTask.MODE_MINI_PINNED) return
            val taskId = getTaskId(taskInfo)
            if (taskId <= 0 || taskId != miniSwipeTaskId) return
            if (miniSwipeConvertedAt != 0L) return
            miniSwipeConvertedAt = SystemClock.uptimeMillis()
            log("MINI_SWIPE_CONVERTED_BY_ROM taskId=$taskId old=$oldMode new=$newMode")
        }

        /**
         * 反射问 ROM「正常迷你缩放」时，临时让 [hookMiniFreeformScale] 放行原始值。
         *
         * 这个标记必须是线程局部的：hook 和 `normalMiniScaleOf` 的反射调用发生在同一条 WMShell 线程上，
         * 但别的窗口/别的线程可能正需要按保存的倍率算尺寸。用布尔量会让两个窗口互相看见对方的临时状态。
         */
        private val miniScaleHookBypass = ThreadLocal<Boolean>()

        private inline fun <T> withoutMiniScaleHook(block: () -> T): T {
            val previous = miniScaleHookBypass.get()
            miniScaleHookBypass.set(true)
            return try {
                block()
            } finally {
                if (previous == null) miniScaleHookBypass.remove() else miniScaleHookBypass.set(previous)
            }
        }

        /** SystemUI-local, device-protected storage for the saved 「大迷你」 packages. */
        private const val MINI_STATE_PREFS_NAME = "bubbledrawer_mini_state"

        /**
         * `prefs`（LSPosed 的 remote group 视图）在 hook 进程里是**只读**的（FanHost 同样踩过：
         * edit() 抛 `UnsupportedOperationException: Read only implementation`）。所以保存状态走
         * SystemUI 自己的 device-protected SharedPreferences —— 跨 SystemUI 重启、模块热重载、
         * 手机重启都还在，而且不会因为模块 App 侧 syncLocal 把它顶掉。
         */
        @Volatile private var miniScaleStatePrefs: SharedPreferences? = null

        /** 保存的「大迷你」包名集合；SharedPreferences 返回的 Set 不能直接改，先复制。 */
        private fun miniEnlargedPackages(): Set<String> =
            miniScaleStatePrefs
                ?.getStringSet(RemotePrefs.KEY_FLYME_MINI_ENLARGED_PACKAGES, emptySet<String>())
                ?.toSet()
                .orEmpty()

        private fun saveMiniEnlargeState(packageName: String, enlarged: Boolean) {
            if (packageName.isBlank()) return
            val storage = miniScaleStatePrefs ?: run {
                log("MINI_ENLARGE_SAVE_SKIPPED reason=no_storage pkg=$packageName")
                return
            }
            val current = miniEnlargedPackages()
            val next = updateMiniEnlargedPackages(current, packageName, enlarged)
            if (next == current) return
            // commit：下一次进迷你可能就在几百毫秒后，必须立刻对 hook 可见；失败也不能影响动画线程。
            runCatching {
                storage.edit()
                    .putStringSet(RemotePrefs.KEY_FLYME_MINI_ENLARGED_PACKAGES, next)
                    .commit()
            }.onSuccess {
                log("MINI_ENLARGE_SAVED pkg=$packageName enlarged=$enlarged total=${next.size}")
            }.onFailure {
                log("MINI_ENLARGE_SAVE_FAILED pkg=$packageName", it)
            }
        }

        /**
         * The zero value for a proxy's declared return type — so an unhandled interface method can
         * never hand `null` back to a caller that expects a primitive.
         */
        fun proxyDefault(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> ' '
            else -> null
        }

        fun log(msg: String, error: Throwable? = null) {            if (error != null) {
                Log.e(TAG, msg, error)
            } else {
                Log.i(TAG, msg)
            }
            val mod = currentModule ?: instance?.module
            if (mod != null) {
                if (error != null) {
                    mod.log(Log.ERROR, TAG, msg, error)
                } else {
                    mod.log(Log.INFO, TAG, msg)
                }
            }
        }

        /**
         * The scale hook's own log line. Deduped on the numbers it reports: the ROM asks for this
         * value whenever it sets up an animation or a resize setup, and an unchanged answer is not
         * news — but this is the line to look at when a 小窗's size is questioned (`clamped=true`
         * means the setting would not have fitted, i.e. 横屏 inside the window).
         *
         * The hot path stays allocation-free: only a changed answer builds a string.
         */
        @Volatile private var scaleLogged = false
        @Volatile private var lastScaleKey = 0

        private fun logScale(method: String, args: List<Any?>, target: Float, rom: Float?, used: Float) {
            val key = ((method.hashCode() * 31 + target.hashCode()) * 31 + (rom?.hashCode() ?: 0)) * 31 + used.hashCode()
            if (scaleLogged && key == lastScaleKey) return
            scaleLogged = true
            lastScaleKey = key
            log(
                "FREEFORM_SCALE $method(${args.joinToString(",") { argText(it) }}) " +
                    "target=$target rom=$rom used=$used clamped=${rom != null && rom < target}",
            )
        }

        private fun argText(arg: Any?): String = when (arg) {
            null -> "null"
            is Boolean, is Number -> arg.toString()
            is String -> arg
            else -> arg.javaClass.simpleName
        }

        /**
         * The package of the window a hook is currently placing: a plain `String` argument in the
         * framework's rect functions (`getFreeformRect` / `getCustomFreeformRect`, where the package is
         * the only String of the signature), the task's own package in the shell's
         * `MulWinSwitchInteractUtil.*(RunningTaskInfo)`.
         *
         * Two consumers: the avoid step must never dodge the window it is placing, and the applied-scale
         * memo is keyed by package (see [appliedScaleFor]).
         */
        private fun packageOf(args: List<Any?>): String? =
            args.filterIsInstance<String>().lastOrNull()
                ?: args.filterIsInstance<ActivityManager.RunningTaskInfo>().firstOrNull()?.let { info ->
                    // `realActivity` is not in the public SDK the module compiles against, hence the
                    // reflection; `topActivity` is, and is the same window in practice.
                    runCatching {
                        val real = info.javaClass.getField("realActivity").get(info)
                        (real as? android.content.ComponentName)?.packageName
                    }.getOrNull()
                        ?: runCatching { info.topActivity?.packageName }.getOrNull()
                }

        fun getTaskId(taskInfo: Any): Int {
            return runCatching {
                taskInfo.javaClass.getMethod("getTaskId").invoke(taskInfo) as Int
            }.getOrElse {
                runCatching {
                    taskInfo.javaClass.getField("mTaskId").get(taskInfo) as Int
                }.getOrDefault(-1)
            }
        }

        fun getTaskBounds(taskInfo: Any): Rect? {
            return runCatching {
                taskInfo.javaClass.getMethod("getTaskBounds").invoke(taskInfo) as? Rect
            }.getOrElse {
                runCatching {
                    taskInfo.javaClass.getField("mTaskBounds").get(taskInfo) as? Rect
                }.getOrNull()
            }
        }

        fun getTaskScale(taskInfo: Any): Float {
            return runCatching {
                (taskInfo.javaClass.getMethod("getTaskScale").invoke(taskInfo) as? Number)?.toFloat()
            }.getOrNull() ?: runCatching {
                (taskInfo.javaClass.getField("mTaskScale").get(taskInfo) as? Number)?.toFloat()
            }.getOrNull() ?: 1f
        }

        fun getRunningTaskInfo(taskInfo: Any): ActivityManager.RunningTaskInfo? {
            return runCatching {
                taskInfo.javaClass.getMethod("getTaskInfo").invoke(taskInfo) as? ActivityManager.RunningTaskInfo
            }.getOrElse {
                runCatching {
                    taskInfo.javaClass.getField("mTaskInfo").get(taskInfo) as? ActivityManager.RunningTaskInfo
                }.getOrNull()
            }
        }

        /**
         * Installs all LSPosed hooks needed for Flyme lightweight freeform features.
         */
        fun installHooks(
            module: XposedModule,
            prefs: SharedPreferences,
            classLoader: ClassLoader,
        ) {
            currentModule = module
            hookControllerCapture(module, classLoader)
            hookFreeformLaunchConfig(module, prefs, classLoader)
            hookFreeformScale(module, prefs, classLoader)
            hookMiniFreeformScale(module, prefs, classLoader)
            hookBoundsCentering(module, prefs, classLoader)
            hookRestoreAvoid(module, prefs, classLoader)
            hookFreeformAvoidOffset(module, prefs, classLoader)
            hookTaskRepository(module, classLoader)
            hookRawTaskAppeared(module, classLoader)
            hookVelocityMonitor(module, classLoader)
            hookMiniBottomUpLimit(module, classLoader)
            hookTopCaptionMove(module, classLoader)
            hookBottomCaptionGestures(module, prefs, classLoader)
            hookMiniTouchForMenu(module, prefs, classLoader)
            hookResizeFocus(module, classLoader)
            hookTaskExit(module, classLoader)
            hookTaskToFront(module, classLoader)
            hookImeState(module, classLoader)
            hookGestureAnimation(module, classLoader)
            hookHomeKeepFreeform(module, prefs, classLoader)
        }

        /**
         * Installs the framework-level freeform sizing, centering and avoid-offset hooks inside
         * `system_server` (`MiuiMultiWindowUtils` / `MiuiFreeformLaunchConfig`).
         *
         * Needed because freeform launches resolved inside `system_server` — `LightOpenGate`
         * (`MiuiFreeformServiceImpl.checkStartActivityByFreeForm`) and chained activity launches
         * (`MiuiFreeformServiceImpl.addLaunchFreeformActivityOptionIfNeed`) — call
         * `MiuiMultiWindowUtils.getActivityOptions` in `system_server`, which would otherwise miss
         * hooks installed only in `com.android.systemui`.
         */
        fun installSystemServerHooks(
            module: XposedModule,
            prefs: SharedPreferences,
            classLoader: ClassLoader,
        ) {
            if (currentModule == null) currentModule = module
            hookFreeformLaunchConfig(module, prefs, classLoader)
            hookFreeformScale(module, prefs, classLoader)
            hookMiniFreeformScale(module, prefs, classLoader)
            hookBoundsCentering(module, prefs, classLoader)
            hookFreeformAvoidOffset(module, prefs, classLoader)
        }

        /**
         * "Which 小窗 is focused now" — `MultiTaskingTaskRepository.updateFreeformTaskToTop(int,
         * String)` is called for every freeform focus gain (`onTaskInfoChanged` :509-512 when
         * `runningTaskInfo.isFocused`) and from `MiuiFreeformModeController` `moveToFront` (:2800).
         *
         * Two consumers: the contact bar (it must know both that another window took focus — hide — and
         * that its own got it back, which a plain tap never reports), and the 黑色遮罩, which has to
         * follow the focused window. The repository itself comes along because it is the only place
         * that can turn a bare task id back into a window object (`getMiuiFreeformTaskInfo` /
         * `getMultiTaskingTaskInfo`) — the mask needs that leash for a 小窗 whose appearance the
         * freeform pipeline never announced.
         */
        private fun hookTaskToFront(
            module: XposedModule,
            classLoader: ClassLoader,
        ) {
            val cls = runCatching {
                classLoader.loadClass("com.android.wm.shell.multitasking.common.taskmanager.MultiTaskingTaskRepository")
            }.getOrNull() ?: return
            cls.declaredMethods
                .filter { it.name == "updateFreeformTaskToTop" && it.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType }
                .forEach { m ->
                    runCatching {
                        module.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("bubbledrawer.freeform.to_front")
                            .intercept { chain ->
                                val taskId = (chain.args.firstOrNull() as? Number)?.toInt() ?: -1
                                if (taskId > 0) {
                                    val repository = chain.thisObject
                                    if (repository != null && savedRepository == null) savedRepository = repository
                                    instance?.onTaskToFront(taskId, repository)
                                    freeformObserver?.onFreeformTaskToFront(taskId)
                                }
                                chain.proceed()
                            }
                    }
                }
            log("HOOK_INSTALLED_MultiTaskingTaskRepository.updateFreeformTaskToTop")
        }

        /**
         * Every 小窗 the shell ever sees: `MultiTaskingTaskRepository.onTaskAppeared(RunningTaskInfo,
         * SurfaceControl)` (called from `MultiTaskingTaskListener`, i.e. straight from
         * `ShellTaskOrganizer`).
         *
         * This is the safety net behind [hookTaskRepository]: that one only announces a window once the
         * freeform pipeline has built a `MiuiFreeformModeTaskInfo` for it, which happens on an "open
         * transition ready" — so opening a *second* 小窗 while one is already up can leave the mask
         * still pointing at the first window. Here nothing but `windowingMode == 5` is required, and
         * the leash comes with the call.
         */
        private fun hookRawTaskAppeared(
            module: XposedModule,
            classLoader: ClassLoader,
        ) {
            val cls = runCatching {
                classLoader.loadClass("com.android.wm.shell.multitasking.common.taskmanager.MultiTaskingTaskRepository")
            }.getOrNull() ?: return

            var installed = 0
            cls.declaredMethods
                .filter { it.name == "onTaskAppeared" && it.parameterCount == 2 }
                .forEach { m ->
                    runCatching {
                        module.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("bubbledrawer.freeform.raw_appeared")
                            .intercept { chain ->
                                val result = chain.proceed()
                                val taskInfo = chain.args.getOrNull(0)
                                val taskId = taskInfo?.let { runCatching {
                                    it.javaClass.getField("taskId").getInt(it)
                                }.getOrNull() } ?: -1
                                if (taskId > 0 && FreeformTask.windowingMode(taskInfo) == FreeformTask.WINDOWING_MODE_FREEFORM) {
                                    instance?.onRawTaskAppeared(
                                        taskId,
                                        chain.args.getOrNull(1) as? SurfaceControl,
                                        chain.thisObject,
                                    )
                                }
                                result
                            }
                        installed++
                    }
                }
            log("HOOK_INSTALLED_MultiTaskingTaskRepository.onTaskAppeared count=$installed")
        }

        /**
         * IME visibility as the ROM itself sees it: `MiuiFreeformModeDisplayInfo.setImeVisibility(
         * showing, height)` is what MIUI's own freeform code reads to keep windows out of the
         * keyboard's way (`isImeShowing()` / `getImeHeight()` are used all over
         * `MiuiFreeformModeResizeHandler`).
         *
         * Flyme is no help here — its contact bar records the IME flag and never reads it
         * (`C2831I.f10206B` is only ever assigned), so its bar just rides whatever the window does.
         * On HyperOS the window does not always move out of the way, so the bar has to avoid the
         * keyboard itself; this is the authoritative signal for that.
         */
        private fun hookImeState(
            module: XposedModule,
            classLoader: ClassLoader,
        ) {
            val cls = runCatching {
                classLoader.loadClass("com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeDisplayInfo")
            }.getOrNull() ?: return

            cls.declaredConstructors.forEach { ctor ->
                runCatching {
                    module.hook(ctor)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("bubbledrawer.freeform.ime.ctor")
                        .intercept { chain ->
                            val result = chain.proceed()
                            chain.thisObject?.let { displayInfo ->
                                imeDisplayInfo = displayInfo
                                refreshImeState(displayInfo)
                            }
                            result
                        }
                }
            }

            cls.declaredMethods.filter { it.name == "setImeVisibility" && it.parameterCount == 2 }.forEach { m ->
                runCatching {
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("bubbledrawer.freeform.ime.set")
                        .intercept { chain ->
                            val result = chain.proceed()
                            imeDisplayInfo = chain.thisObject
                            imeShowing = (chain.args[0] as? Boolean) ?: false
                            imeHeight = (chain.args[1] as? Number)?.toInt() ?: 0
                            log("IME_VISIBILITY showing=$imeShowing height=$imeHeight")
                            result
                        }
                }
            }
            log("HOOK_INSTALLED_MiuiFreeformModeDisplayInfo.setImeVisibility")
        }

        /** Re-read the state from the captured instance (covers "IME was already up at load time"). */
        private fun refreshImeState(displayInfo: Any) {
            runCatching {
                imeShowing = displayInfo.javaClass.getMethod("isImeShowing").invoke(displayInfo) as? Boolean ?: false
                imeHeight = (displayInfo.javaClass.getMethod("getImeHeight").invoke(displayInfo) as? Number)?.toInt() ?: 0
            }
        }

        /** Is the keyboard up right now (px height from [imeHeightPx])? */
        fun isImeShowing(): Boolean {
            imeDisplayInfo?.let {
                refreshImeState(it)
                return imeShowing
            }
            // The WMShell hook is the primary signal; if it never fired (class renamed by a future
            // ROM) fall back to asking the input-method service directly. Rate-limited: this is IPC.
            return pollImeFallback()
        }

        fun imeHeightPx(): Int = imeHeight

        @Volatile private var imePollAt = 0L
        @Volatile private var imePollHeight = 0

        /** `InputMethodManager.getInputMethodWindowVisibleHeight()` (hidden), cached [IME_POLL_MS]. */
        private fun pollImeFallback(): Boolean {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - imePollAt < IME_POLL_MS) return imePollHeight > 0
            imePollAt = now
            imePollHeight = runCatching {
                val imm = savedContext?.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager ?: return@runCatching 0
                android.view.inputmethod.InputMethodManager::class.java
                    .getMethod("getInputMethodWindowVisibleHeight")
                    .invoke(imm) as? Int ?: 0
            }.getOrDefault(0)
            if (imePollHeight > 0) imeHeight = imePollHeight
            return imePollHeight > 0
        }

        private const val IME_POLL_MS = 300L

        /**
         * "This window is going away" — `MiuiFreeformModeController.exitFreeformTask(int, …)` is the
         * single decision point for every close path (顶部 ✕、小白条上滑关闭、迷你小窗上滑退出、
         * 上滑全屏化也会走到这里). Consumers that draw around the window (the contact bar) must stop
         * following the closing animation and remove themselves immediately.
         */
        private fun hookTaskExit(
            module: XposedModule,
            classLoader: ClassLoader,
        ) {
            val candidates = listOf(
                "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeController",
                "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeController\$MiuiFreeformModeControlImpl",
            )
            var installed = 0
            for (name in candidates) {
                val cls = runCatching { classLoader.loadClass(name) }.getOrNull() ?: continue
                cls.declaredMethods
                    .filter { it.name == "exitFreeformTask" && it.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType }
                    .forEach { m ->
                        runCatching {
                            module.hook(m)
                                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                                .setId("bubbledrawer.freeform.exit.${name.substringAfterLast('.')}.${m.parameterCount}")
                                .intercept { chain ->
                                    val taskId = (chain.args.firstOrNull() as? Number)?.toInt() ?: -1
                                    if (taskId > 0) {
                                        instance?.onTaskClosing(taskId)
                                        freeformObserver?.onFreeformTaskClosing(taskId)
                                    }
                                    chain.proceed()
                                }
                            installed++
                        }
                    }
            }
            log("HOOK_INSTALLED_exitFreeformTask count=$installed")
        }

        /**
         * "This is the window the user is working on" — `MiuiFreeformModeResizeHandler.handleResize`
         * carries the `MiuiFreeformModeTaskInfo` on every resize event (actionMode 0 = down).
         *
         * Three consumers: the contact bar re-targets to that window (with two 小窗 open, the bar must
         * sit under the one being resized) and arms its per-frame tracking for the drag; the 黑色遮罩
         * re-anchors to it for the same reason.
         */
        private fun hookResizeFocus(
            module: XposedModule,
            classLoader: ClassLoader,
        ) {
            val handlerClass = runCatching {
                classLoader.loadClass("com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeResizeHandler")
            }.getOrNull() ?: return

            handlerClass.declaredMethods.filter { it.name == "handleResize" && it.parameterCount == 5 }.forEach { m ->
                runCatching {
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("bubbledrawer.freeform.resize_focus")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val taskInfo = chain.args[2]
                            val action = (chain.args[4] as? Number)?.toInt() ?: -1
                            if (taskInfo != null && (action == 0 || action == 2)) {
                                instance?.onTaskFocused(taskInfo)
                                freeformObserver?.onFreeformTaskFocused(taskInfo)
                            }
                            result
                        }
                }
            }
            log("HOOK_INSTALLED_MiuiFreeformModeResizeHandler.handleResize")
        }

        private fun hookControllerCapture(
            module: XposedModule,
            classLoader: ClassLoader,
        ) {
            val ctrlClass = runCatching {
                classLoader.loadClass("com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeController")
            }.getOrNull()

            ctrlClass?.declaredConstructors?.forEach { constructor ->
                runCatching {
                    module.hook(constructor)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("bubbledrawer.freeform.ctrl.construct.${constructor.parameterCount}")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val instance = chain.thisObject
                            if (instance != null) {
                                savedController = instance
                                val exec = runCatching {
                                    instance.javaClass.getDeclaredField("mMainExecutor").apply { isAccessible = true }.get(instance) as? Executor
                                }.getOrNull()
                                if (exec != null) savedExecutor = exec
                                val org = runCatching {
                                    instance.javaClass.getDeclaredField("mRootTaskDisplayAreaOrganizer").apply { isAccessible = true }.get(instance)
                                }.getOrNull()
                                if (org != null) savedOrganizer = org
                                // task id → window object, the fallback resolver for the mask.
                                val repo = runCatching {
                                    instance.javaClass.getDeclaredField("mMultiTaskingTaskRepository")
                                        .apply { isAccessible = true }.get(instance)
                                }.getOrNull()
                                if (repo != null) savedRepository = repo
                                log("CAPTURED_MiuiFreeformModeController_FROM_CONSTRUCTOR org=${org != null}")
                            }
                            result
                        }
                }
            }

            val implClass = runCatching {
                classLoader.loadClass("com.android.wm.shell.dagger.MultiTaskingControllerImpl")
            }.getOrNull()

            implClass?.declaredMethods?.filter { it.name == "init" }?.forEach { m ->
                runCatching {
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("bubbledrawer.freeform.impl.init")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val impl = chain.thisObject
                            if (impl != null) {
                                val ctrl = runCatching {
                                    impl.javaClass.getMethod("getMiuiFreeformModeController").invoke(impl)
                                }.getOrNull()
                                if (ctrl != null) {
                                    savedController = ctrl
                                    val exec = runCatching {
                                        ctrl.javaClass.getDeclaredField("mMainExecutor").apply { isAccessible = true }.get(ctrl) as? Executor
                                    }.getOrNull()
                                    if (exec != null) savedExecutor = exec
                                    val org = runCatching {
                                        ctrl.javaClass.getDeclaredField("mRootTaskDisplayAreaOrganizer").apply { isAccessible = true }.get(ctrl)
                                    }.getOrNull()
                                    if (org != null) savedOrganizer = org
                                    val repo = runCatching {
                                        ctrl.javaClass.getDeclaredField("mMultiTaskingTaskRepository")
                                            .apply { isAccessible = true }.get(ctrl)
                                    }.getOrNull()
                                    if (repo != null) savedRepository = repo
                                    log("CAPTURED_MiuiFreeformModeController_FROM_IMPL_INIT org=${org != null}")
                                }
                            }
                            result
                        }
                }
            }
        }

        private fun hookFreeformLaunchConfig(
            module: XposedModule,
            prefs: SharedPreferences,
            classLoader: ClassLoader,
        ) {
            val configClass = runCatching {
                classLoader.loadClass("android.util.MiuiFreeformLaunchConfig")
            }.getOrNull()
            if (configClass == null) {
                // Silent until now, and it is the difference between "the 缩放 setting decides the
                // 小窗's size" and "the ROM's own resolution table does": without this hook the ROM
                // sizes the window itself, so the centering arithmetic must take its scale from the
                // ROM instead of from the setting (see [centerRectOnScreen]).
                log("HOOK_SKIPPED_FreeformLaunchConfig cls=false")
                return
            }

            val method = runCatching {
                configClass.getDeclaredMethod(
                    "getFreeformArg",
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            }.getOrNull()
            if (method == null) {
                log(
                    "HOOK_SKIPPED_FreeformLaunchConfig cls=true method=false " +
                        "methods=${configClass.declaredMethods.joinToString { "${it.name}/${it.parameterCount}" }}",
                )
                return
            }

            // The argType values are public constants on that very class and they are what the ROM's
            // own callers pass, so read them rather than trusting the numbers this module was written
            // against: a ROM that renumbers them would otherwise get the 缩放 setting handed back as,
            // say, its left margin.
            val originalScale = intConstant(configClass, "ARGS_ORIGINAL_SCALE", 2)
            val leftMargin = intConstant(configClass, "ARGS_LEFT_MARGIN", 4)

            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("bubbledrawer.freeform.launch_config")
                .intercept { chain ->
                    val snap = RemotePrefs.read(prefs)
                    if (!snap.flymeFreeformEnabled) {
                        return@intercept chain.proceed()
                    }
                    val argType = chain.args[3] as? Int ?: return@intercept chain.proceed()
                    val targetScale = (snap.flymeFreeformScale.coerceIn(50, 95)) / 100f

                    when (argType) {
                        originalScale -> targetScale
                        leftMargin -> {
                            if (snap.flymeFreeformCenter) {
                                -1.0f // Sentinel for horizontal centering in HyperOS: (displayShortSide - freeFormWidth) / 2
                            } else {
                                chain.proceed()
                            }
                        }
                        else -> chain.proceed()
                    }
                }
            log("HOOK_INSTALLED_MiuiFreeformLaunchConfig.getFreeformArg scale=$originalScale leftMargin=$leftMargin")
        }

        private fun intConstant(cls: Class<*>, name: String, fallback: Int): Int =
            runCatching { cls.getField(name).getInt(null) }.getOrDefault(fallback)

        /**
         * The 小窗's size is one scale factor that the ROM multiplies with its own unscaled task
         * bounds (`MiuiMultiWindowUtils.getPossibleBounds`). Those bounds are **orientation
         * dependent**: on the same short side a portrait window is `shortSide * ratio` wide, a
         * landscape one is `shortSide * ratio * aspect` wide — ~2.2x wider on this phone. Returning
         * the setting verbatim is therefore only safe in portrait: 横屏 inside the 小窗 made the
         * window wider than the display, and `startFreeformOrientationChangeShellTransition`
         * (MiuiFreeformModeAnimation:5320) only *offsets* such a rect into place, never shrinks it —
         * so the content ran off the screen.
         *
         * The ROM's own value is that same preferred scale *after* `reviewFreeFormBounds`
         * (MiuiMultiWindowUtils:2267), which shrinks it until the visual rect fits the freeform
         * accessible area — i.e. exactly the "fits on screen" rule, for either orientation and for
         * every app aspect the ROM knows about (game apps, `ADDITIONAL_FREEFORM_RESOLUTIONS`).
         * Taking whichever of the two is smaller therefore keeps the Flyme-style size wherever it
         * is legal, and lets the ROM clamp only where the setting would not fit.
         */
        private fun hookFreeformScale(
            module: XposedModule,
            prefs: SharedPreferences,
            classLoader: ClassLoader,
        ) {
            val utilsClass = runCatching {
                classLoader.loadClass("android.util.MiuiMultiWindowUtils")
            }.getOrNull()
            if (utilsClass == null) {
                log("HOOK_SKIPPED_FreeformScale cls=false")
                return
            }

            var hooked = 0
            for (m in utilsClass.declaredMethods) {
                if (m.name == "getFreeFormScale" || m.name == "getOriFreeformScale") {
                    runCatching {
                        module.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("bubbledrawer.freeform.scale.${m.name}.${m.parameterCount}")
                            .intercept { chain ->
                                val snap = RemotePrefs.read(prefs)
                                if (!snap.flymeFreeformEnabled) return@intercept chain.proceed()
                                val target = (snap.flymeFreeformScale.coerceIn(50, 95)) / 100f
                                val rom = (chain.proceed() as? Number)?.toFloat()
                                val used = fitFreeformScale(target, rom)
                                // Publish it: this answer *is* the size the 小窗 being placed will
                                // have, and the centering hooks need exactly that number (see
                                // [centerRectOnScreen] / [appliedScaleFor]) instead of re-deriving it
                                // from the setting — which is only the same value while this hook is
                                // the one deciding the size.
                                noteAppliedScale(chain.args, used)
                                logScale(m.name, chain.args, target, rom, used)
                                used
                            }
                    }
                    hooked++
                }
            }
            log("HOOK_INSTALLED_MiuiMultiWindowUtils.getFreeFormScale hooks=$hooked")
        }

        /**
         * 保存的「大迷你」尺寸：ROM 每次进迷你都会用
         * `MiuiMultiWindowUtils.getMiniFreeformScale(context, landscape, bounds, pkg)` 重新算一遍缩放
         * （`MiuiFreeformModeAnimation:3941/4083/4613/5863`）。这里按**包名**把属于用户的放大倍率乘进去，
         * 于是拖动、回桌面、从最近任务直接开迷你窗等所有路径都会一开始就按大尺寸生成，不用事后再补动画。
         *
         * 只在 `flymeMiniMenu` 开启时生效：倍率滑块本来就跟着这个开关灰掉，关掉菜单后不应该还留着
         * 一个没有入口可改的"大迷你"状态。
         *
         * [normalMiniScaleOf] 自己也会反射调用这个方法，所以用 `miniScaleHookBypass` 保证它拿到 ROM 的
         * 原始值，否则「放大 / 还原」会把已经放大的尺寸当成正常值，越点越大。
         */
        private fun hookMiniFreeformScale(
            module: XposedModule,
            prefs: SharedPreferences,
            classLoader: ClassLoader,
        ) {
            val utilsClass = runCatching {
                classLoader.loadClass("android.util.MiuiMultiWindowUtils")
            }.getOrNull()
            if (utilsClass == null) {
                log("HOOK_SKIPPED_MiniFreeformScale cls=false")
                return
            }
            val methods = utilsClass.declaredMethods.filter { it.name == "getMiniFreeformScale" }
            if (methods.isEmpty()) {
                log("HOOK_SKIPPED_MiniFreeformScale hooks=0")
                return
            }

            var hooked = 0
            for (method in methods) {
                runCatching {
                    module.hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("bubbledrawer.mini.scale.${method.name}.${method.parameterCount}")
                        .intercept { chain ->
                            if (miniScaleHookBypass.get() == true) return@intercept chain.proceed()
                            val snap = RemotePrefs.read(prefs)
                            if (!snap.flymeMiniMenu) return@intercept chain.proceed()
                            val packageName = chain.args.lastOrNull() as? String
                                ?: return@intercept chain.proceed()
                            if (packageName !in miniEnlargedPackages()) {
                                return@intercept chain.proceed()
                            }
                            val original = chain.proceed()
                            val normal = (original as? Number)?.toFloat()
                            if (normal == null || !normal.isFinite() || normal <= 0f) {
                                return@intercept original
                            }
                            val ratio = miniEnlargeRatioFromPct(snap.flymeMiniEnlargePct)
                            val used = enlargedMiniScale(normal, ratio)
                            logMiniEnlargeScale(packageName, normal, snap.flymeMiniEnlargePct, used)
                            used
                        }
                }.onFailure {
                    log("HOOK_FAILED_MiniFreeformScale params=${method.parameterCount}", it)
                }.onSuccess {
                    hooked++
                }
            }
            log("HOOK_INSTALLED_MiuiMultiWindowUtils.getMiniFreeformScale hooks=$hooked")
        }

        /** One line per distinct (package, normal scale, ratio): this runs on every mini entry. */
        @Volatile private var miniScaleLogged = false
        @Volatile private var lastMiniScaleKey = ""

        private fun logMiniEnlargeScale(packageName: String, normal: Float, pct: Int, used: Float) {
            val key = "$packageName:$normal:$pct:$used"
            if (miniScaleLogged && key == lastMiniScaleKey) return
            miniScaleLogged = true
            lastMiniScaleKey = key
            log("MINI_ENLARGE_SCALE pkg=$packageName normal=$normal pct=$pct used=$used")
        }

        /**
         * 禁止小窗偏移 — 已经有小窗时，新开的小窗不再为了避开它而挪位。
         *
         * HyperOS 的「错开」就是在 `avoidIfNeeded`（MiuiMultiWindowUtils:1807，手机走这一支）里做的：
         * 新窗口和已有窗口重叠时，把新窗口挪到 `fixed.left + FREEFORM_RECT_OFFSET_X_ZIZHAN` /
         * `fixed.top + FREEFORM_RECT_OFFSET_Y_ZIZHAN` —— 两个常量是 **78dp / 44dp**
         * （:319-320），也就是用户看到的「往右下偏一点」。
         *
         * `avoidAsPossible(Rect mobile, …)` 是 void 并且**原地**改第一个参数，是所有「避让另一个小窗」
         * 逻辑的唯一咽喉：
         *  - 启动：`getFreeformRect:1461` / `getCustomFreeformRect:1541` → `getAvoidFreeformBounds:1547`
         *  - 全屏 → 小窗切换：`MulWinSwitchInteractUtil:104` → `getAvoidFreeformBounds`
         *  - 迷你 → 普通恢复：`MiuiFreeformModeMiniStateHandler:276` / `MiuiFreeformModePinHandler:953`
         *    → `MiuiFreeformModeAvoidAlgorithm.avoidOtherFreeformTaskIfNeed:909`
         * 所以在这里不 proceed，就等于"每个小窗都开在/回到同一个默认位置"，
         * 而且它只挪位、不动尺寸：缩放、居中、屏幕内夹取都是这一步之前算好的。
         *
         * 迷你小窗自己的排布（`autoOrderingAvoidMiniTask` / `avoidForActiveMiniTask`）不走这个函数，
         * 贴边/侧边栏避让（`adjustBoundsForSidebarIfNeed`）也不走，都不受影响。
         *
         * 开关只受「禁止小窗偏移」控制，**不看** Flyme 样式小窗总开关：这是对 HyperOS 自身行为的
         * 开关，原生小窗模式下同样应该生效（用户实测反馈）。
         *
         * 这是开关的**开**那一半。关的那一半在 [applyRomAvoidOffset]：ROM 那次避让会被模块的居中
         * 覆盖掉（它在 `getCustomFreeformRect` 内部，居中在它返回之后），所以关掉开关时得由我们
         * 在居中之后再补一次同样的避让，第二个小窗才会像原版那样往右下错开（只在 Flyme 样式 +
         * 居中接管了位置时才需要；总开关关着时 ROM 那次避让本来就是生效的）。
         */
        private fun hookFreeformAvoidOffset(
            module: XposedModule,
            prefs: SharedPreferences,
            classLoader: ClassLoader,
        ) {
            val utilsClass = runCatching {
                classLoader.loadClass("android.util.MiuiMultiWindowUtils")
            }.getOrNull() ?: return

            val method = runCatching {
                utilsClass.getDeclaredMethod("avoidAsPossible", Rect::class.java, Rect::class.java, Rect::class.java)
            }.getOrNull() ?: return

            runCatching {
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("bubbledrawer.freeform.no_offset")
                    .intercept { chain ->
                        // Deliberately independent of the Flyme-style master switch: 「禁止小窗偏移」
                        // is about HyperOS's own 错开 behaviour, so it must also work while the module
                        // is not taking the 小窗 over at all (user report: 不开 Flyme 小窗时开关不生效).
                        val snap = RemotePrefs.read(prefs)
                        if (!snap.flymeFreeformNoOffset && !avoidOffsetSuppressionActive()) return@intercept chain.proceed()
                        logOffsetSkipped(chain.args)
                        null
                    }
            }
            log("HOOK_INSTALLED_MiuiMultiWindowUtils.avoidAsPossible")
        }

        /** One line per (new window, existing window) pair — this runs on launches, not per frame. */
        @Volatile private var offsetLogged = false
        @Volatile private var lastOffsetKey = 0

        private fun logOffsetSkipped(args: List<Any?>) {
            val mobile = args.getOrNull(0) as? Rect
            val fixed = args.getOrNull(1) as? Rect
            val key = (mobile?.hashCode() ?: 0) * 31 + (fixed?.hashCode() ?: 0)
            if (offsetLogged && key == lastOffsetKey) return
            offsetLogged = true
            lastOffsetKey = key
            log("FREEFORM_OFFSET_SKIPPED mobile=$mobile other=$fixed")
        }

        /**
         * Depth counter for `MiuiMultiWindowUtils.getFreeformRect` / `getCustomFreeformRect`
         * overloads: the 5-arg and 6-arg overloads delegate to the 7-arg overload (and the 6..12-arg
         * `getFreeformRect` overloads delegate to the 13-arg one), so without a re-entrancy guard
         * [centerRectOnScreen] and [applyRomAvoidOffset] would run twice on the exact same `Rect`.
         */
        private val rectHookDepth = object : ThreadLocal<Int>() {
            override fun initialValue(): Int = 0
        }

        private fun hookBoundsCentering(
            module: XposedModule,
            prefs: SharedPreferences,
            classLoader: ClassLoader,
        ) {
            val utilsClass = runCatching {
                classLoader.loadClass("android.util.MiuiMultiWindowUtils")
            }.getOrNull()

            var rectHooks = 0
            var rectFailed = 0
            utilsClass?.declaredMethods?.filter {
                it.name == "getFreeformRect" || it.name == "getCustomFreeformRect"
            }?.forEach { m ->
                runCatching {
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("bubbledrawer.freeform.bounds.${m.name}.${m.parameterCount}")
                        .intercept { chain ->
                            val depth = (rectHookDepth.get() ?: 0) + 1
                            rectHookDepth.set(depth)
                            try {
                                val result = chain.proceed()
                                if (depth > 1) return@intercept result
                                val snap = RemotePrefs.read(prefs)
                                // 总开关只管"模块要不要接管小窗"；**居中的开关现在只管横向**
                                // （用户 2026-10-11：「居中仅为横方向居中，竖方向默认居中但可以上下位置调整」），
                                // 所以这里不再要求 `flymeFreeformCenter` —— 否则关掉居中，上下滑杆也跟着失效。
                                if (snap.flymeFreeformEnabled && result is Rect && !result.isEmpty) {
                                    // The package this rect is being computed for is a plain String argument in
                                    // every overload (`getCustomFreeformRect(ctx, …, pkg, …)`), and it is what
                                    // tells the avoid pass whether it is looking at the very window it is placing.
                                    //
                                    // 落点被明确指定时（扇形从哪个角落划出来，见 `MiuiFreeform.triggerSideRect`）
                                    // 只补垂直方向：水平那一半是用户划出来的意图，居中不该把它抹掉。
                                    val callCtx = chain.args.firstOrNull { it is Context } as? Context
                                    centerRectOnScreen(
                                        result,
                                        snap,
                                        selfPackage = packageOf(chain.args),
                                        keepLeft = m.name == "getCustomFreeformRect" && requestedLeft(chain.args) != null,
                                        callContext = callCtx,
                                    )
                                } else {
                                    logCenterSkip("${m.name}/${m.parameterCount}", snap)
                                }
                                result
                            } finally {
                                rectHookDepth.set(depth - 1)
                            }
                        }
                    rectHooks++
                }.onFailure {
                    rectFailed++
                    log("BOUNDS_CENTER_HOOK_FAILED src=${m.name}/${m.parameterCount}", it)
                }
            }

            val utilClass = runCatching {
                classLoader.loadClass("com.android.wm.shell.multitasking.miuimultiwinswitch.MulWinSwitchInteractUtil")
            }.getOrNull()

            var interactHooks = 0
            var interactFailed = 0
            utilClass?.declaredMethods?.filter {
                it.name == "getFreeformDefaultLaunchBounds" ||
                it.name == "getSwitchedFreeformTargetBounds" ||
                it.name == "getDefaultFreeformRect"
            }?.forEach { m ->
                runCatching {
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("bubbledrawer.freeform.interact.${m.name}")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val snap = RemotePrefs.read(prefs)
                            if (snap.flymeFreeformEnabled && snap.flymeFreeformCenter && result is Rect && !result.isEmpty) {
                                // The window being placed, so that (a) the avoid step never dodges it
                                // and (b) the scale the module's own hook last gave the ROM is
                                // recognised as *this* window's size (see [appliedScaleFor]) — the
                                // same two things the framework hooks pass as `selfPackage`.
                                centerRectOnScreen(result, snap, selfPackage = packageOf(chain.args))
                            } else {
                                logCenterSkip("MulWinSwitch.${m.name}", snap)
                            }
                            result
                        }
                    interactHooks++
                }.onFailure {
                    interactFailed++
                    log("BOUNDS_CENTER_HOOK_FAILED src=${m.name}/${m.parameterCount}", it)
                }
            }

            val miniHandlerClass = runCatching {
                classLoader.loadClass("com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeMiniStateHandler")
            }.getOrNull()

            var miniHooks = 0
            var miniFailed = 0
            miniHandlerClass?.declaredMethods?.filter {
                it.name == "getRestoredBounds" && it.parameterCount == 2
            }?.forEach { m ->
                runCatching {
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("bubbledrawer.freeform.mini.getRestoredBounds")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val snap = RemotePrefs.read(prefs)
                            if (snap.flymeFreeformEnabled && snap.flymeFreeformCenter) {
                                val rect = chain.args[1] as? Rect
                                if (rect != null && !rect.isEmpty) {
                                    // 迷你 → 普通小窗 (点击还原): the window being placed is the one the
                                    // first argument describes, so the avoid pass must not run against it.
                                    //
                                    // `result` is not a guess: `singleTap` hands exactly this float to
                                    // `setAnimParam(rect, restoredBounds, restoredBounds, 0)`, i.e. it IS
                                    // the scale the restored window is drawn at (the task's own scale
                                    // before it went mini, times the ROM's fit correction). Centring with
                                    // the setting instead — as this did — leaves the window off the
                                    // centre by half the difference whenever they differ, and the ROM's
                                    // avoid step then compares that same wrong rect.
                                    centerRectOnScreen(
                                        rect,
                                        snap,
                                        selfTaskId = chain.args.getOrNull(0)?.let { getTaskId(it) } ?: 0,
                                        scaleOverride = (result as? Number)?.toFloat(),
                                        armRestoreGuard = true,
                                    )
                                } else {
                                    logCenterSkipArgs("mini.getRestoredBounds", chain.args)
                                }
                            } else {
                                logCenterSkip("mini.getRestoredBounds", snap)
                            }
                            result
                        }
                    miniHooks++
                }.onFailure {
                    miniFailed++
                    log("BOUNDS_CENTER_HOOK_FAILED src=${m.name}/${m.parameterCount}", it)
                }
            }
            log("HOOK_INSTALLED_BoundsCentering rect=$rectHooks rectFailed=$rectFailed interact=$interactHooks interactFailed=$interactFailed mini=$miniHooks miniFailed=$miniFailed")
            // A zero is the interesting case: it means the ROM's own class/method shape moved and the
            // centering switch can no longer do anything at all. Name what IS there instead of leaving
            // "no CENTERED_BOUNDS in the log" to be guessed at.
            if (rectHooks == 0) {
                log("BOUNDS_CENTER_NO_MATCH MiuiMultiWindowUtils cls=${utilsClass != null} methods=${utilsClass?.declaredMethods?.filter { it.name.contains("Rect", true) || it.name.contains("reeform", true) }?.joinToString { "${it.name}/${it.parameterCount}" }}")
            }
            if (miniHooks == 0) {
                log("BOUNDS_CENTER_NO_MATCH MiniStateHandler cls=${miniHandlerClass != null} methods=${miniHandlerClass?.declaredMethods?.joinToString { "${it.name}/${it.parameterCount}" }}")
            }
        }

        /**
         * 迷你 → 普通 恢复：ROM 的避让跑在**模块居中之后**。
         *
         * `MiuiFreeformModeMiniStateHandler.singleTap` 的顺序是死的：先 `getRestoredBounds(taskInfo,
         * rect)`（模块就在这一步居中），再 `avoidOtherFreeformTaskIfNeed(taskInfo, rect, scale)` ——
         * 后者**原地**改的是同一个 `Rect` 对象，只要还有第二个普通小窗在，它就把刚算好的居中再推走
         * 78dp/44dp。这是唯一一个「模块写完之后 ROM 还会改写」的摆放点（启动路径与切换路径的避让都在
         * ROM 方法内部、也就是模块居中之前），所以它也是「设置了居中却看起来没居中」的最后一道门。
         *
         * 模块在上一步已经把该不该偏移决定过了（[applyRomAvoidOffset]，用的就是 ROM 自己那套
         * `avoidAsPossible`），于是这里只做一件事：让模块的决定成为最终决定 —— 跳过这一次避让。
         *
         * 触发条件是「模块刚居中过**这个** `Rect`」（对象同一性 + [RESTORE_GUARD_MS] 窗口）。ROM 的
         * 其它调用点（贴边恢复等）拿的是别的对象，行为完全不变；而模块没有替 ROM 做决定的场合
         * （禁止偏移开着，或者模块自己算出的避让没有真的挪动窗口）也不跳，ROM 照旧说话。
         */
        private fun hookRestoreAvoid(
            module: XposedModule,
            prefs: SharedPreferences,
            classLoader: ClassLoader,
        ) {
            val algorithmClass = runCatching {
                classLoader.loadClass("com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeAvoidAlgorithm")
            }.getOrNull()

            val method = algorithmClass?.declaredMethods?.firstOrNull {
                it.name == "avoidOtherFreeformTaskIfNeed" && it.parameterCount == 3 &&
                    it.parameterTypes.getOrNull(1) == Rect::class.java
            }
            if (method == null) {
                log("HOOK_SKIPPED_RestoreAvoid cls=${algorithmClass != null}")
                return
            }

            runCatching {
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("bubbledrawer.freeform.restore_avoid")
                    .intercept { chain ->
                        val rect = chain.args.getOrNull(1) as? Rect
                        val snap = RemotePrefs.read(prefs)
                        if (rect != null && snap.flymeFreeformEnabled && snap.flymeFreeformCenter &&
                            centeredRectModuleDecided && isFreshlyCentered(rect)
                        ) {
                            logRestoreAvoidSkipped(rect)
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                log("HOOK_INSTALLED_RestoreAvoid")
            }.onFailure { log("HOOK_FAILED_RestoreAvoid", it) }
        }

        /** Identity + age of the rect [centerRectOnScreen] just placed, for [hookRestoreAvoid]. */
        @Volatile private var centeredRectId = 0
        @Volatile private var centeredRectAt = 0L
        @Volatile private var centeredRectModuleDecided = false

        private fun armRestoreCentered(rect: Rect, moduleDecided: Boolean) {
            centeredRectId = System.identityHashCode(rect)
            centeredRectAt = SystemClock.uptimeMillis()
            centeredRectModuleDecided = moduleDecided
        }

        private fun isFreshlyCentered(rect: Rect?): Boolean =
            rect != null && centeredRectId != 0 &&
                System.identityHashCode(rect) == centeredRectId &&
                SystemClock.uptimeMillis() - centeredRectAt <= RESTORE_GUARD_MS

        @Volatile private var restoreSkipLogged = false
        @Volatile private var lastRestoreSkipKey = 0

        private fun logRestoreAvoidSkipped(rect: Rect) {
            val key = rect.hashCode()
            if (restoreSkipLogged && key == lastRestoreSkipKey) return
            restoreSkipLogged = true
            lastRestoreSkipKey = key
            log("RESTORE_AVOID_SKIPPED rect=$rect — the module's own placement stands")
        }

        /**
         * Why a centering hook decided not to centre.
         *
         * The gate is two settings, and when it is closed the whole feature is invisible — no
         * `CENTERED_BOUNDS`, nothing to look at, which is indistinguishable from "the hook never ran".
         * This is that distinction, logged once per source and answer (the same gate is asked on every
         * launch, so the line must not repeat).
         */
        private fun logCenterSkip(source: String, snap: RemotePrefs.Snapshot) {
            val message = "CENTER_SKIPPED src=$source enabled=${snap.flymeFreeformEnabled} " +
                "center=${snap.flymeFreeformCenter} scale=${snap.flymeFreeformScale} " +
                "noOffset=${snap.flymeFreeformNoOffset}"
            if (message == lastCenterSkip) return
            lastCenterSkip = message
            log(message)
        }

        /**
         * Same idea for the other way a centering hook can end up doing nothing: the rect it is supposed
         * to rewrite is not where the signature said it would be (a ROM that moved the out-parameter).
         * Silent in the old code, and indistinguishable from "the hook never ran".
         */
        private fun logCenterSkipArgs(source: String, args: List<Any?>) {
            val message = "CENTER_SKIP_ARGS src=$source args=${args.joinToString { argText(it) }}"
            if (message == lastCenterSkip) return
            lastCenterSkip = message
            log(message)
        }

        @Volatile private var lastCenterSkip: String? = null

        /**
         * 横屏不居中 —— 一行证据（去重）。用户 2026-10-18 的规则「居中小窗仅对竖屏生效」之后，
         * 横屏看不到 `CENTERED_BOUNDS` 是**正确**的，这行就是用来区分"按规则没居中"和"钩子没跑"。
         */
        private fun logCenterLandscape(rect: Rect, screenW: Int, screenH: Int) {
            val message = "CENTERED_BOUNDS_SKIPPED_LANDSCAPE rect=$rect screen=($screenW x $screenH)"
            if (message == lastCenterSkip) return
            lastCenterSkip = message
            log(message)
        }

        /**
         * Center the launch rect on the screen (设置项「小窗居中」) —— **只在竖屏**。
         *
         * 用户 2026-10-18：「在横屏时打开其他 app 的小窗，上滑使其转变为迷你小窗，随后点击迷你小窗
         * 恢复为小窗时，位置会出现在横屏的最中间，希望能做到从哪里来到哪里去。即居中小窗修改为
         * 仅对竖屏生效」。所以横屏直接返回、一个像素都不改：rect 保持 ROM 算出来的那一份，
         * 迷你 → 普通小窗的还原就待在它原来的位置。
         *
         * 其余历史原因仍然成立：rect 是**未缩放**的任务空间坐标，可见尺寸是 `rect * scale`，
         * 而这个 scale 不是设置值 —— ROM 会把它夹到能放下为止；按设置值居中会在两条边都算出负数、
         * 把小窗挂到屏幕外。竖屏下这个换算仍然是 [centerScale] 的事。
         *
         * [keepLeft] is for the one caller that already knows where the window belongs horizontally:
         * a扇形 launch states the side it was triggered from ([requestedLeft] sees it in the ROM's own
         * arguments, `MiuiFreeform.triggerSideRect` computed it), and centering would silently undo it.
         *
         * 两个轴现在各管各的（用户 2026-10-11）：
         *  - **横向**：`小窗居中` 开着才居中，关掉就不动它；扇形触发侧永远优先（[keepLeft]）；
         *  - **纵向**：默认正中（`小窗居中` 开着时等于今天的正中，关着时保持 ROM 给的位置），
         *    再叠加 `小窗上下位置` 滑杆（[verticalOffsetPx]）—— 滑杆只在竖屏生效。
         * 滑杆默认 0，所以这条路径在默认设置下与以前逐像素一致。
         */
        private fun centerRectOnScreen(
            rect: Rect,
            snap: RemotePrefs.Snapshot,
            selfPackage: String? = null,
            selfTaskId: Int = 0,
            scaleOverride: Float? = null,
            armRestoreGuard: Boolean = false,
            keepLeft: Boolean = false,
            callContext: Context? = null,
        ) {
            // system_server 里没有 SystemUI 的 savedContext：用 ROM 调用方自己传进来的 Context。
            val ctx = callContext ?: savedContext
            val targetScale = (snap.flymeFreeformScale.coerceIn(50, 95)) / 100f
            // **Which scale will the ROM multiply this rect by?** That is the whole question: the
            // window is positioned by its *unscaled* origin and drawn at `rect * scale`, so a wrong
            // scale here does not merely mis-size the assumed window, it also moves the real one off
            // the centre by half the difference — and, with 禁止小窗偏移 off, it makes the avoid step
            // below compare a rect that is bigger/smaller than the window it stands for, i.e. dodge a
            // window that does not even overlap. Both symptoms the OS3 report describes ("新开的" and
            // "还原的都不居中") come out of exactly this one number.
            //
            // `launchScale` answers it with the *setting* reviewed by the ROM, which is only the
            // truth as long as the module's own scale hook ([hookFreeformScale]) is what decided the
            // window's size. Where the ROM keeps another preferred-scale source the module cannot
            // reach (`MiuiFreeformLaunchConfig` has a different shape on that build), the ROM sizes
            // the window by its own value and every placement below is off. So prefer what the ROM
            // itself said, in this order: the scale it just handed back to its own caller
            // ([scaleOverride] — the `getRestoredBounds` return value *is* the anim target's scale),
            // then the value the module's scale hook last gave the ROM ([appliedScaleFor]).
            val applied = scaleOverride?.takeIf { it.isUsableScale() } ?: appliedScaleFor(selfPackage)
            val assumed = launchScale(rect, targetScale, ctx)
            if (applied == null && assumed == null && rect.width() >= rect.height()) {
                // A landscape window's visible size is ~1/aspect *smaller* than the setting, so
                // centering it with the setting would be wrong in the harmful direction (off both
                // edges). Without the ROM's reviewed scale, leave its own placement — top-aligned,
                // but at least fully on screen — alone.
                log("CENTERED_BOUNDS_SKIPPED_WIDE rect=$rect targetScale=$targetScale")
                return
            }
            val scale = centerScale(applied, assumed, targetScale)
            val (screenW, screenH) = screenSizeForCentering(ctx)
            // 用户 2026-10-18：**居中只在竖屏生效**。横屏时一个像素都不改 —— 尤其是迷你小窗点回
            // 普通小窗那一下（[hookBoundsCentering] 里的 `getRestoredBounds`）：ROM 给的 rect 就是
            // 它"原来待的地方"，居中会让它跳到屏幕正中（"从哪里来到哪里去"）。
            if (isWideScreen(screenW, screenH)) {
                logCenterLandscape(rect, screenW, screenH)
                return
            }
            val visualW = rect.width() * scale
            val visualH = rect.height() * scale
            if (visualW > screenW + 0.5f || visualH > screenH + 0.5f) {
                // The window is bigger than the display at this scale, so "centred" would mean "half
                // off every edge". Only reachable when the scale came from a ROM answer we could not
                // review (the setting path is reviewed by [launchScale]) — leave the ROM's own
                // placement alone rather than hanging the window off the screen.
                log("CENTERED_BOUNDS_SKIPPED_TOO_BIG rect=$rect scale=$scale visual=(${visualW.toInt()}x${visualH.toInt()}) screen=($screenW x $screenH)")
                return
            }
            val (targetLeft, targetTop) = centeredOrigin(visualW, visualH, screenW, screenH)
            // 横向：居中开关关掉时**不动**它（那正是"不居中"的意思），[keepLeft] 时留给启动方指定的
            // 触发侧 —— 否则居中会在 ROM 按触发侧算完之后再把它搬回屏幕中间。
            val left = if (keepLeft || !snap.flymeFreeformCenter) rect.left else targetLeft
            // 纵向：居中开关管到哪一步就停在哪一步（开着 = 正中，关着 = ROM 给的位置），然后叠加
            // 「小窗上下位置」的滑杆。走到这里的只可能是竖屏（横屏在上面已经返回）。
            val centered = snap.flymeFreeformCenter
            val verticalBase = if (centered) targetTop else rect.top
            val offset = verticalOffsetPx(snap.flymeFreeformVerticalPct, screenH, visualH)
            rect.offsetTo(left, verticalBase + offset)
            // 禁止小窗偏移 = 关: 把已经居中好的窗口再交给 ROM 的避让逻辑一次，第二个小窗因此落在
            // 「第一个的位置 + 右 78dp / 下 44dp」（方向选择与屏幕内夹取都由 ROM 自己决定）。
            // 落点被明确指定时不参与（移开就不是用户划出来的那一边了）；居中关掉时也不参与 ——
            // 那是"模块不要重摆窗口"的意思，滑杆为 0 时这条路径必须与以前完全一样。
            val avoided = if (centered && !snap.flymeFreeformNoOffset && !keepLeft) {
                applyRomAvoidOffset(rect, visualW, visualH, selfPackage, selfTaskId, ctx)
            } else {
                false
            }
            if (armRestoreGuard) {
                // Armed only for the 迷你 → 普通 restore, whose caller runs the ROM's *own* avoid on
                // this very rect right after this hook returns ([hookRestoreAvoid]). The module has
                // already made that decision here — the toggle says "no offset at all", or the avoid
                // above just placed the window — so that later pass must not undo it.
                armRestoreCentered(rect, moduleDecided = snap.flymeFreeformNoOffset || avoided)
            }
            log(
                "CENTERED_BOUNDS rect=$rect scale=$scale src=${if (applied != null) "rom" else if (assumed != null) "assumed" else "setting"} " +
                    "applied=$applied assumed=$assumed targetScale=$targetScale avoided=$avoided " +
                    "left=${if (keepLeft) "triggered" else if (centered) "centered" else "rom"} " +
                    "vpos=${snap.flymeFreeformVerticalPct}%(${offset}px) centered=$centered " +
                    "visual=(${visualW.toInt()}x${visualH.toInt()}) screen=($screenW x $screenH)",
            )
        }

        /**
         * The applied scale the module's own scale hook last gave the ROM ([hookFreeformScale]), i.e.
         * the size a 小窗 opened right now will actually have. Kept as a one-slot memo because the
         * ROM asks for the scale a few instructions before it asks for the rect — same thread, same
         * call chain — and answers the same value for the same package/window every time.
         */
        @Volatile private var appliedScaleAt = 0L
        @Volatile private var appliedScalePackage: String? = null
        @Volatile private var appliedScaleValue = 0f

        private fun noteAppliedScale(args: List<Any?>, used: Float) {
            if (!used.isUsableScale()) return
            appliedScaleValue = used
            appliedScalePackage = args.filterIsInstance<String>().lastOrNull()
            appliedScaleAt = SystemClock.uptimeMillis()
        }

        private fun appliedScaleFor(packageName: String?): Float? {
            val value = appliedScaleValue
            if (!value.isUsableScale()) return null
            if (SystemClock.uptimeMillis() - appliedScaleAt > APPLIED_SCALE_TTL_MS) return null
            val forPackage = appliedScalePackage
            if (packageName != null && forPackage != null && packageName != forPackage) return null
            return value
        }

        /**
         * 居中的参考系：ROM 自己的显示器尺寸 — `MiuiMultiWindowUtils.getDisplaySize`，也就是
         * `Resources.getSystem().getConfiguration().windowConfiguration.getMaxBounds()`，ROM 自己
         * 摆放小窗时用的就是它（`getCustomFreeformRect` / `getSwitchedFreeformTargetBounds`）。
         *
         * `Resources.getSystem().displayMetrics` 是同一个屏幕的另一种说法，两者一般相等 —— 但只要
         * 不等（状态栏/导航栏、显示尺寸覆盖、折叠态…），用哪一个就会整体差半个差值，而且是**两条
         * 居中路径一起差**。既然 ROM 有权威答案，就用它的；拿不到时才退回原来的 metrics。
         */
        private fun screenSizeForCentering(context: Context? = savedContext): Pair<Int, Int> {
            val rom = runCatching {
                Class.forName("android.util.MiuiMultiWindowUtils")
                    .getMethod("getDisplaySize", Context::class.java)
                    .invoke(null, context ?: savedContext) as? Rect
            }.getOrNull()
            val metrics = android.content.res.Resources.getSystem().displayMetrics
            if (rom != null && rom.width() > 0 && rom.height() > 0) {
                if (rom.width() != metrics.widthPixels || rom.height() != metrics.heightPixels) {
                    val message = "CENTER_SCREEN_MISMATCH rom=(${rom.width()}x${rom.height()}) " +
                        "metrics=(${metrics.widthPixels}x${metrics.heightPixels}) — using the ROM's"
                    if (message != lastScreenMismatch) {
                        lastScreenMismatch = message
                        log(message)
                    }
                }
                return rom.width() to rom.height()
            }
            return metrics.widthPixels to metrics.heightPixels
        }

        @Volatile private var lastScreenMismatch: String? = null

        /**
         * 「禁止小窗偏移」关闭时，让 ROM 的避让在**居中之后**再生效一次。
         *
         * 为什么必须由我们补这一步：ROM 的避让发生在 `getCustomFreeformRect` **内部**
         * （`getAvoidFreeformBounds:1541` → `avoidAsPossible` → `avoidIfNeeded`），而模块的居中是
         * 在那个方法**返回之后**重新摆放窗口 —— 所以只要「小窗居中」开着，ROM 那次避让就被覆盖，
         * 开关关掉也看不到偏移（用户实测：两个小窗的 task 配置完全相同）。
         *
         * 这里直接调 ROM 自己的 `avoidAsPossible(mobile, fixed, restriction)`：左右/上下的方向选择、
         * 78dp/44dp 的常量、以及"挪出去会出屏"时的夹取，全部沿用它的 if-else，不复刻一套。
         *
         * **"另一个小窗"必须是真的、还活着、且不是正在摆放的这一个**（[findOtherWindow]）：
         * 早期版本直接拿模块跟踪的那个窗口（遮罩当前跟谁），而它并不保证还活着 —— 窗口关掉后
         * 只有 `onTaskVanished` 会清 `windows`，`activeTaskInfo` 会留到下一次 focus 事件；
         * 拿一个已经关掉的窗口去避让，新窗口（默认位置和它一样）必然被判为"重叠"，于是被推离
         * 刚算好的居中位置 78dp/44dp。用户看到的就是「居中不生效」，而这只在
         * 「禁止小窗偏移」默认改成**关**（87b46c8）之后才可能出现 —— 那次之前这个补偿根本不会跑。
         */
        private fun applyRomAvoidOffset(
            rect: Rect,
            visualW: Float,
            visualH: Float,
            selfPackage: String?,
            selfTaskId: Int,
            context: Context? = savedContext,
        ): Boolean {
            val candidate = findOtherWindow(selfTaskId, selfPackage, context)
            val other = candidate.bounds
            if (other == null) {
                val reason = when {
                    candidate.selfMatches > 0 -> "SELF_OR_SAME_PACKAGE"
                    else -> "NO_OTHER_WINDOW"
                }
                log(
                    "CENTERED_BOUNDS_AVOID_SKIPPED reason=$reason src=${candidate.source} " +
                        "selfMatches=${candidate.selfMatches} selfTaskId=$selfTaskId selfPkg=$selfPackage",
                )
                return false
            }
            val area = freeformAccessibleArea(context) ?: return false
            val mobile = Rect(rect.left, rect.top, rect.left + visualW.toInt(), rect.top + visualH.toInt())
            val applied = runCatching {
                Class.forName("android.util.MiuiMultiWindowUtils")
                    .getMethod("avoidAsPossible", Rect::class.java, Rect::class.java, Rect::class.java)
                    .invoke(null, mobile, other, area)
                true
            }.getOrDefault(false)
            if (!applied) return false
            val dx = mobile.left - rect.left
            val dy = mobile.top - rect.top
            rect.offset(dx, dy)
            log("CENTERED_BOUNDS_AVOIDED other=$other visual=$mobile delta=($dx,$dy) src=${candidate.source}")
            return dx != 0 || dy != 0
        }

        /** Answer of [findOtherWindow]: the rect to dodge (null = there is no other 小窗). */
        private class OtherWindow(val bounds: Rect?, val selfMatches: Int, val source: String)

        /**
         * The *other* live 小窗 a window being placed has to dodge.
         *
         * The list comes from the shell's own repository (`MultiTaskingTaskRepository
         * .getFreeformTasksInZOrder` → `getMiuiFreeformTaskInfo`, both used by the ROM's own avoid
         * logic) instead of from the window the 黑色遮罩 happens to follow. Those are not the same
         * question: the mask's window outlives the window itself, and a 迷你/贴边 one is not a valid
         * dodge target either — while the repository knows exactly which tasks are still open. The
         * mask's window stays as the fallback for a ROM whose repository cannot be reached at all.
         *
         * The window being placed ([selfTaskId]/[selfPackage]) is never "another" one: the module
         * reuses an app's existing 小窗 task on purpose (A4b), and a 迷你小窗 restore is the same task
         * coming back — dodging it moved the window off the centre by 78dp/44dp.
         *
         * Only windows that are *positively* recognised as 普通小窗 are candidates ([FreeformTask
         * .isDodgeable], i.e. the ROM's own `isNormalState()` rule). Everything the module cannot
         * identify that way — a mode it could not read, a 迷你/贴边 one, a window that is on its way
         * out, one whose leash is gone — is simply not worth pushing the new window off the centre for.
         */
        private fun findOtherWindow(selfTaskId: Int, selfPackage: String?, context: Context? = savedContext): OtherWindow {
            val inst = instance
            val repo = inst?.getMultiTaskingTaskRepository()
            val ids = repo?.let { repository ->
                runCatching {
                    (repository.javaClass.getMethod("getFreeformTasksInZOrder").invoke(repository) as? List<*>)
                        ?.mapNotNull { (it as? Number)?.toInt() }
                }.getOrNull()
            }
            if (!ids.isNullOrEmpty() && repo != null) {
                var selfMatches = 0
                for (id in ids) {
                    if (id == selfTaskId) {
                        selfMatches++
                        continue
                    }
                    // 正在关闭（✕ / 上滑关闭 / 窗外点击关闭）的窗口：退出动画期间它在仓库里仍是
                    // 「可见的普通小窗」，拿它当避让目标就是「一个看不见的小窗把新窗推到右下」。
                    if (inst?.isWindowSuspended(id) == true) continue
                    val info = inst?.callWithTaskId(repo, "getMiuiFreeformTaskInfo", id) ?: continue
                    if (selfPackage != null && selfPackage == FreeformTask.packageName(info)) {
                        selfMatches++
                        continue
                    }
                    if (!FreeformTask.isDodgeable(info)) continue
                    val leash = FreeformTask.leash(info)
                    if (leash != null && !leash.isValid) continue
                    val bounds = FreeformTask.visualBounds(info)
                    if (bounds != null && !bounds.isEmpty) return OtherWindow(bounds, selfMatches, "shell")
                }
                return OtherWindow(null, selfMatches, "shell")
            }
            // Fallback: no repository answer (a ROM that reshaped the class, or a moment where it is
            // not built yet). Only a live, plain, non-self window counts here either.
            if (inst == null) {
                // system_server：没有 WMShell 仓库，问框架自己（getFreeFormStackToAvoid 已被
                // LightOpenGate 过滤掉退出中/桌面后/同包的栈）。
                return frameworkOtherWindow(selfPackage, context)
            }
            val tracked = inst.activeTaskInfo?.takeIf {
                FreeformTask.isDodgeable(it) && !inst.isWindowSuspended(FreeformTask.taskId(it))
            }?.also { info ->
                if (selfTaskId > 0 && selfTaskId == FreeformTask.taskId(info)) return OtherWindow(null, 1, "tracked")
                if (selfPackage != null && selfPackage == FreeformTask.packageName(info)) {
                    return OtherWindow(null, 1, "tracked")
                }
                val leash = FreeformTask.leash(info)
                if (leash != null && !leash.isValid) return OtherWindow(null, 0, "tracked")
            }
            val bounds = tracked?.let { FreeformTask.visualBounds(it) }?.takeIf { !it.isEmpty }
            return OtherWindow(bounds, 0, if (tracked != null) "tracked" else "none")
        }

        /**
         * The scale the ROM will apply to a task with these unscaled bounds: its own
         * `reviewFreeFormBounds` of our preferred scale against the freeform accessible area — the very
         * computation `getFreeFormScale` performs (see [hookFreeformScale]), so the centering above
         * agrees with the size the window actually ends up having. Null when the ROM cannot be asked.
         *
         * Reviewing the *returned rect* (rather than re-deriving `getPossibleBounds` from
         * vertical/landscape/package) is what makes this correct for every launch path: the rect
         * already carries whatever bounds the ROM chose, including the fixed
         * `ADDITIONAL_FREEFORM_RESOLUTIONS` aspect ratios.
         */
        private fun launchScale(rect: Rect, targetScale: Float, context: Context? = savedContext): Float? {
            val area = freeformAccessibleArea(context) ?: return null
            val reviewed = runCatching {
                val bounds = Rect(0, 0, rect.width(), rect.height())
                (Class.forName("android.util.MiuiMultiWindowUtils").getMethod(
                    "reviewFreeFormBounds",
                    Rect::class.java,
                    Rect::class.java,
                    java.lang.Float.TYPE,
                    Rect::class.java,
                ).invoke(null, bounds, Rect(), targetScale, area) as? Number)?.toFloat()
            }.getOrNull()
            return reviewed?.takeIf { it.isFinite() && it > 0f }
        }

        /**
         * The other 小窗 as `system_server` sees it: `MiuiFreeFormManager.getFreeFormStackToAvoid`
         * (the ROM's own avoid source; [com.repl.bubbledrawer.xposed.LightOpenGate] filters out the
         * launching package, exiting/behind-home stacks). Visual bounds = bounds × freeFormScale.
         */
        private fun frameworkOtherWindow(selfPackage: String?, context: Context?): OtherWindow {
            val info = runCatching {
                val displayId = runCatching { context?.display?.displayId }.getOrNull() ?: 0
                Class.forName("miui.app.MiuiFreeFormManager")
                    .getMethod("getFreeFormStackToAvoid", Int::class.javaPrimitiveType, String::class.java)
                    .invoke(null, displayId, selfPackage)
            }.getOrNull() ?: return OtherWindow(null, 0, "framework")
            fun f(name: String): Any? = runCatching { info.javaClass.getField(name).get(info) }.getOrNull()
            val pkg = f("packageName") as? String
            if (selfPackage != null && pkg == selfPackage) return OtherWindow(null, 1, "framework")
            if (f("visible") == false || f("inPinMode") == true) return OtherWindow(null, 0, "framework")
            if ((f("windowState") as? Int ?: 0) != 0) return OtherWindow(null, 0, "framework")
            val bounds = f("bounds") as? Rect ?: return OtherWindow(null, 0, "framework")
            if (bounds.isEmpty) return OtherWindow(null, 0, "framework")
            val scale = (f("freeFormScale") as? Number)?.toFloat() ?: 1f
            val visual = MiuiFreeform.visualBounds(bounds, scale) ?: Rect(
                bounds.left, bounds.top,
                bounds.left + (bounds.width() * scale).toInt(), bounds.top + (bounds.height() * scale).toInt(),
            )
            return OtherWindow(visual, 0, "framework")
        }

        /**
         * `MiuiMultiWindowUtils.getFreeFormAccessibleArea(context, true, isDesktopMode(context))` —
         * the ROM's own "where a 小窗 may live" rect, shared by the scale review and the avoid step.
         */
        private fun freeformAccessibleArea(callContext: Context? = savedContext): Rect? {
            val context = callContext ?: savedContext ?: return null
            return runCatching {
                val utils = Class.forName("android.util.MiuiMultiWindowUtils")
                val desktopMode = runCatching {
                    utils.getDeclaredMethod("isDesktopMode", Context::class.java)
                        .apply { isAccessible = true }.invoke(null, context) as? Boolean ?: false
                }.getOrDefault(false)
                utils.getMethod(
                    "getFreeFormAccessibleArea",
                    Context::class.java,
                    java.lang.Boolean.TYPE,
                    java.lang.Boolean.TYPE,
                ).invoke(null, context, true, desktopMode) as? Rect
            }.getOrNull()?.takeIf { !it.isEmpty }
        }

        private fun hookTaskRepository(
            module: XposedModule,
            classLoader: ClassLoader,
        ) {
            val repoClass = runCatching {
                classLoader.loadClass("com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeTaskRepository")
            }.getOrNull() ?: return

            // Hook onTaskAppeared(MiuiFreeformModeTaskInfo)
            repoClass.declaredMethods.filter { it.name == "onTaskAppeared" && it.parameterCount == 1 }.forEach { m ->
                runCatching {
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("bubbledrawer.freeform.task_appeared.${m.parameterTypes[0].simpleName}")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val taskInfo = chain.args[0]
                            if (taskInfo != null && taskInfo !is Number) {
                                instance?.onTaskAppeared(taskInfo)
                                // The contact bar (contactbar/ContactBarController) listens here too:
                                // it must work with HyperOS's own 小窗, i.e. even while the Flyme-style
                                // takeover above is switched off (flymeFreeformEnabled defaults false).
                                freeformObserver?.onFreeformTaskAppeared(taskInfo)
                            }
                            result
                        }
                }
            }

            // Hook onTaskVanished(int)
            repoClass.declaredMethods.filter { it.name == "onTaskVanished" && it.parameterCount == 1 }.forEach { m ->
                runCatching {
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("bubbledrawer.freeform.task_vanished")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val taskId = (chain.args[0] as? Number)?.toInt() ?: -1
                            if (taskId > 0) {
                                instance?.onTaskVanished(taskId)
                                freeformObserver?.onFreeformTaskVanished(taskId)
                            }
                            result
                        }
                }
            }

            // Hook onTaskModeChanged(MiuiFreeformModeTaskInfo, int, int, boolean)
            repoClass.declaredMethods.filter { it.name == "onTaskModeChanged" && it.parameterCount >= 3 }.forEach { m ->
                runCatching {
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("bubbledrawer.freeform.task_mode_changed")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val taskInfo = chain.args[0]
                            val oldMode = (chain.args[1] as? Number)?.toInt() ?: -1
                            val newMode = (chain.args[2] as? Number)?.toInt() ?: -1
                            if (taskInfo != null) {
                                // Synchronous (WMShell thread) record of a 迷你小窗 conversion: the ROM
                                // performs it inside the gesture's own `proceed()`, so the 上滑小白条 hook
                                // has to be able to see it the moment that call returns — the main-thread
                                // dispatch below is too late for that decision.
                                noteMiniSwipeConversion(taskInfo, oldMode, newMode)
                                instance?.onTaskModeChanged(taskInfo, oldMode, newMode)
                                freeformObserver?.onFreeformTaskModeChanged(taskInfo, oldMode, newMode)
                            }
                            result
                        }
                }
            }
            log("HOOK_INSTALLED_MiuiFreeformModeTaskRepository")
        }

        private fun hookVelocityMonitor(
            module: XposedModule,
            classLoader: ClassLoader,
        ) {
            val velClass = runCatching {
                classLoader.loadClass("com.android.wm.shell.multitasking.common.MultiTaskingVelocityMonitor")
            }.getOrNull() ?: return

            velClass.declaredMethods.filter { it.name == "getVelocity" && it.parameterCount == 1 }.forEach { m ->
                runCatching {
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("bubbledrawer.freeform.velocity")
                        .intercept { chain ->
                            if (sForceMiniTransition || sForceRebound) {
                                0.0f
                            } else if (sForceFullscreenTransition) {
                                2000.0f
                            } else {
                                chain.proceed()
                            }
                        }
                }
            }
            log("HOOK_INSTALLED_MultiTaskingVelocityMonitor")
        }

        private fun hookMiniBottomUpLimit(
            module: XposedModule,
            classLoader: ClassLoader,
        ) {
            val taskInfoClass = runCatching {
                classLoader.loadClass("com.android.wm.shell.multitasking.common.taskmanager.MiuiFreeformModeTaskInfo")
            }.getOrNull() ?: return

            var installed = 0
            taskInfoClass.declaredMethods.filter { it.name == "getMiniBottomUpLimit" && it.parameterCount == 0 }.forEach { m ->
                runCatching {
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("bubbledrawer.freeform.mini_limit")
                        .intercept { chain ->
                            if (sForceMiniTransition) {
                                999.0f
                            } else if (sForceRebound) {
                                -999.0f
                            } else {
                                chain.proceed()
                            }
                        }
                    installed++
                }
            }
            if (installed == 0) {
                // HyperOS 3: the window object has no such limit because its 小白条 release path has no
                // 迷你 branch at all. Nothing to steer there — [FlymeFreeformController
                // .scheduleMiniFallback] drives the ROM's own conversion instead.
                log("HOOK_SKIPPED_MiuiFreeformModeTaskInfo.getMiniBottomUpLimit")
            } else {
                log("HOOK_INSTALLED_MiuiFreeformModeTaskInfo.getMiniBottomUpLimit count=$installed")
            }
        }

        private fun hookTopCaptionMove(
            module: XposedModule,
            classLoader: ClassLoader,
        ) {
            val moveHandlerClass = runCatching {
                classLoader.loadClass("com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeMoveHandler")
            }.getOrNull() ?: return

            moveHandlerClass.declaredMethods.filter {
                it.name == "handleMotionEvents" && it.parameterCount == 8
            }.forEach { m ->
                runCatching {
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("bubbledrawer.freeform.top_caption_move")
                        .intercept { chain ->
                            chain.proceed()
                        }
                }
            }
            log("HOOK_INSTALLED_MiuiFreeformModeMoveHandler.handleMotionEvents")
        }

        private fun hookBottomCaptionGestures(
            module: XposedModule,
            prefs: SharedPreferences,
            classLoader: ClassLoader,
        ) {
            val moveHandlerClass = runCatching {
                classLoader.loadClass("com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeMoveHandler")
            }.getOrNull() ?: return

            val method = moveHandlerClass.declaredMethods.firstOrNull {
                it.name == "onBottomCaptionHandleMotionEvents" && it.parameterCount == 5
            } ?: return

            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("bubbledrawer.freeform.bottom_caption_gesture")
                .intercept { chain ->
                    val snap = RemotePrefs.read(prefs)
                    if (!snap.flymeFreeformEnabled) {
                        return@intercept chain.proceed()
                    }

                    val moveHandler = chain.thisObject
                    if (savedOrganizer == null && moveHandler != null) {
                        val org = runCatching {
                            moveHandler.javaClass.getDeclaredField("mRootTaskDisplayAreaOrganizer").apply { isAccessible = true }.get(moveHandler)
                        }.getOrNull()
                        if (org != null) savedOrganizer = org
                    }

                    val taskInfo = chain.args[3]
                    val inst = instance
                    if (inst != null && taskInfo != null) {
                        // Touching a window's bottom caption makes it the one the mask follows (the
                        // controller itself decides whether that window may carry a mask).
                        inst.onWindowInteracting(taskInfo)
                    }

                    val y = (chain.args[1] as? Number)?.toFloat() ?: 0f
                    val downPoint = chain.args[2] as? PointF
                    val action = (chain.args[4] as? Number)?.toInt() ?: -1

                    val downY = downPoint?.y ?: (inst?.gestureDownY ?: y)
                    val dy = y - downY

                    when (action) {
                        MotionEvent.ACTION_DOWN -> {
                            inst?.onBottomCaptionDown(y, if (taskInfo != null) getTaskId(taskInfo) else 0)
                            chain.proceed()
                        }
                        MotionEvent.ACTION_MOVE -> {
                            inst?.onBottomCaptionMove(dy, snap)
                            chain.proceed()
                        }
                        MotionEvent.ACTION_UP -> {
                            if (inst != null) {
                                inst.onBottomCaptionUp(dy, snap) { chain.proceed() }
                            } else {
                                chain.proceed()
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            inst?.onBottomCaptionCancel()
                            chain.proceed()
                        }
                        else -> chain.proceed()
                    }
                }
            log("HOOK_INSTALLED_onBottomCaptionHandleMotionEvents")
        }

        private fun isTouchInsideMiniMenu(ev: MotionEvent): Boolean {
            val overlay = instance?.miniMenuOverlay ?: return false
            if (!overlay.isShowing()) return false
            val bounds = overlay.screenBounds ?: return true
            val hit = Rect(bounds).apply { inset(-24, -24) }
            val x = ev.rawX.toInt()
            val y = ev.rawY.toInt()
            return hit.contains(x, y)
        }

        /**
         * Long press on a mini window (迷你/悬浮小窗长按) triggers Flyme-style floating action menu
         * (静音 / 放大).
         */
        private fun hookMiniTouchForMenu(
            module: XposedModule,
            prefs: SharedPreferences,
            classLoader: ClassLoader,
        ) {
            val touchStateClass = runCatching {
                classLoader.loadClass("com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeTouchState")
            }.getOrNull() ?: return

            var longPressTriggered = false
            var downX = 0f
            var downY = 0f
            var currentMiniTaskId = -1
            var pendingLongPressRunnable: Runnable? = null
            val touchSlop = 24f

            touchStateClass.declaredMethods
                .filter { it.name == "handleMotionEvents" && it.parameterCount == 3 }
                .forEach { method ->
                    runCatching {
                        module.hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("bubbledrawer.freeform.mini_touch_events")
                            .intercept { chain ->
                                val snap = RemotePrefs.read(prefs)
                                val ev = chain.args[0] as? MotionEvent
                                val taskInfo = chain.args[2]

                                if (!snap.flymeMiniMenu || ev == null || taskInfo == null) {
                                    return@intercept chain.proceed()
                                }

                                val isMini = runCatching {
                                    taskInfo.javaClass.getMethod("isMiniState").invoke(taskInfo) as? Boolean
                                }.getOrNull() == true

                                val taskId = runCatching {
                                    (taskInfo.javaClass.getMethod("getTaskId").invoke(taskInfo) as? Number)?.toInt()
                                }.getOrNull() ?: -1

                                if (!isMini || taskId <= 0) {
                                    pendingLongPressRunnable?.let { instance?.handler?.removeCallbacks(it) }
                                    pendingLongPressRunnable = null
                                    return@intercept chain.proceed()
                                }
                                instance?.miniTaskInfoMap?.put(taskId, taskInfo)

                                val overlayShowing = instance?.miniMenuOverlay?.isShowingForTask(taskId) == true
                                if (overlayShowing) {
                                    when (ev.actionMasked) {
                                        MotionEvent.ACTION_DOWN -> {
                                            downX = ev.rawX
                                            downY = ev.rawY
                                            log("MINI_MENU_TOUCH_INTERCEPTED_DOWN taskId=$taskId")
                                            return@intercept null
                                        }
                                        MotionEvent.ACTION_MOVE -> {
                                            val dx = abs(ev.rawX - downX)
                                            val dy = abs(ev.rawY - downY)
                                            if (dx > touchSlop || dy > touchSlop) {
                                                log("MINI_MENU_DISMISSED_BY_DRAG taskId=$taskId dx=$dx dy=$dy")
                                                instance?.miniMenuOverlay?.dismiss(animate = true)
                                                setDisableTouch(false)
                                                return@intercept chain.proceed()
                                            }
                                            return@intercept null
                                        }
                                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                            log("MINI_MENU_TOUCH_INTERCEPTED_UP taskId=$taskId")
                                            return@intercept null
                                        }
                                    }
                                    log("MINI_MENU_TOUCH_INTERCEPTED action=${ev.actionMasked} taskId=$taskId")
                                    return@intercept null
                                }

                                when (ev.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        downX = ev.rawX
                                        downY = ev.rawY
                                        currentMiniTaskId = taskId
                                        longPressTriggered = false

                                        if (instance?.miniMenuOverlay?.isShowing() == true &&
                                            !instance!!.miniMenuOverlay.isShowingForTask(taskId)) {
                                            instance?.miniMenuOverlay?.dismiss(animate = false)
                                        }

                                        pendingLongPressRunnable?.let { instance?.handler?.removeCallbacks(it) }
                                        val r = Runnable {
                                            if (currentMiniTaskId == taskId) {
                                                longPressTriggered = true
                                                suppressSingleTapTaskId = taskId
                                                instance?.triggerHapticFeedback()
                                                log("MINI_LONG_PRESS_TRIGGERED taskId=$taskId")

                                                val bounds = runCatching {
                                                    taskInfo.javaClass.getMethod("getBounds").invoke(taskInfo) as? Rect
                                                }.getOrNull() ?: Rect(0, 0, 300, 450)

                                                val leash = runCatching {
                                                    taskInfo.javaClass.getMethod("getLeash").invoke(taskInfo) as? SurfaceControl
                                                }.getOrNull()

                                                val pkgName = runCatching {
                                                    taskInfo.javaClass.getMethod("getPackageName").invoke(taskInfo) as? String
                                                }.getOrNull().orEmpty()

                                                val runningTaskInfo = runCatching {
                                                    taskInfo.javaClass.getMethod("getTaskInfo").invoke(taskInfo)
                                                }.getOrNull()
                                                val uid = runCatching {
                                                    runningTaskInfo?.javaClass?.getField("effectiveUid")?.getInt(runningTaskInfo)
                                                }.getOrNull() ?: -1
                                                val cfg = runCatching {
                                                    runningTaskInfo?.javaClass?.getField("configuration")?.get(runningTaskInfo) as? android.content.res.Configuration
                                                }.getOrNull()
                                                val displayId = runCatching {
                                                    runningTaskInfo?.javaClass?.getField("displayId")?.getInt(runningTaskInfo)
                                                }.getOrNull() ?: 0

                                                // 当前缩放，不是「目标」缩放：长按菜单出现时窗口是静止的，
                                                // 而 destination 在动画被打断时会停在上一轮的值上。
                                                val miniScale = runCatching {
                                                    (taskInfo.javaClass.getMethod("getFreeformScale").invoke(taskInfo) as? Number)?.toFloat()
                                                }.getOrNull()?.takeIf { it.isFinite() && it > 0f } ?: 0.28f

                                                // 菜单的命中区域跟着窗口的**视觉**矩形走：`getScaledBounds()` 就是它
                                                // （未缩放 bounds 原地放大，见 `MiniScaleToggle.kt` 的坐标约定）。
                                                val scaledBounds = runCatching {
                                                    taskInfo.javaClass.getMethod("getScaledBounds").invoke(taskInfo) as? Rect
                                                }.getOrNull()

                                                // 「放大」状态问 ROM 的 scale，不记在我们自己的表里 —— 见 [toggleMiniWindowScale]。
                                                val isEnlarged = instance?.isMiniEnlarged(taskInfo) == true
                                                if (leash != null) {
                                                    instance?.miniMenuOverlay?.show(
                                                        taskId = taskId,
                                                        packageName = pkgName,
                                                        uid = uid,
                                                        bounds = bounds,
                                                        leash = leash,
                                                        config = cfg,
                                                        displayId = displayId,
                                                        miniScale = if (miniScale in 0.05f..0.95f) miniScale else 0.28f,
                                                        screenBounds = scaledBounds,
                                                        isEnlarged = isEnlarged,
                                                    )
                                                }
                                            }
                                        }
                                        pendingLongPressRunnable = r
                                        instance?.handler?.postDelayed(r, ViewConfiguration.getLongPressTimeout().toLong())
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        val dx = abs(ev.rawX - downX)
                                        val dy = abs(ev.rawY - downY)
                                        if (dx > touchSlop || dy > touchSlop) {
                                            pendingLongPressRunnable?.let { instance?.handler?.removeCallbacks(it) }
                                            pendingLongPressRunnable = null
                                            if (instance?.miniMenuOverlay?.isShowingForTask(taskId) == true) {
                                                instance?.miniMenuOverlay?.dismiss(animate = true)
                                            }
                                        }
                                    }
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                        pendingLongPressRunnable?.let { instance?.handler?.removeCallbacks(it) }
                                        pendingLongPressRunnable = null
                                        if (longPressTriggered) {
                                            longPressTriggered = false
                                            return@intercept null
                                        }
                                    }
                                }
                                chain.proceed()
                            }
                    }
                }

            touchStateClass.declaredMethods
                .filter { it.name == "singleTap" }
                .forEach { method ->
                    runCatching {
                        module.hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("bubbledrawer.freeform.mini_single_tap_suppress")
                            .intercept { chain ->
                                val taskInfo = chain.args.firstOrNull()
                                val taskId = runCatching {
                                    taskInfo?.let { (it.javaClass.getMethod("getTaskId").invoke(it) as? Number)?.toInt() }
                                }.getOrNull() ?: -1

                                val isOverlayShowing = taskId > 0 && instance?.miniMenuOverlay?.isShowingForTask(taskId) == true
                                if (taskId > 0 && (suppressSingleTapTaskId == taskId || isOverlayShowing)) {
                                    suppressSingleTapTaskId = -1
                                    log("SUPPRESS_SINGLE_TAP taskId=$taskId isOverlayShowing=$isOverlayShowing")
                                    return@intercept null
                                }
                                chain.proceed()
                            }
                    }
                }

            // Hook MulWinSwitchEventHandler: prevent gesture monitor from dispatching or stealing pointer on overlay tap
            val mwClass = runCatching {
                classLoader.loadClass("com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.MulWinSwitchEventHandler")
            }.getOrNull()
            mwClass?.declaredMethods
                ?.filter { it.name == "isInterceptDownEvent" && it.parameterCount == 1 }
                ?.forEach { m ->
                    runCatching {
                        module.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("bubbledrawer.freeform.mulwin_intercept_down")
                            .intercept { chain ->
                                val overlay = instance?.miniMenuOverlay
                                val ev = chain.args[0] as? MotionEvent
                                if (overlay?.isShowing() == true && ev != null) {
                                    if (isTouchInsideMiniMenu(ev)) {
                                        log("MULWIN_INTERCEPT_DOWN_DURING_MINI_MENU (inside bounds) x=${ev.rawX} y=${ev.rawY}")
                                        return@intercept true
                                    } else {
                                        log("MULWIN_OUTSIDE_MINI_MENU_TAP x=${ev.rawX} y=${ev.rawY} -> DISMISS")
                                        overlay.dismiss(animate = true)
                                        return@intercept chain.proceed()
                                    }
                                }
                                chain.proceed()
                            }
                    }
                }

            // Hook MiuiFreeformModeGestureHandler.handleMotionEvents: block WMShell handling while menu overlay is active
            val ghClass = runCatching {
                classLoader.loadClass("com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeGestureHandler")
            }.getOrNull()
            ghClass?.declaredMethods
                ?.filter { it.name == "handleMotionEvents" && it.parameterCount == 2 }
                ?.forEach { m ->
                    runCatching {
                        module.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("bubbledrawer.freeform.gesture_handler_handle_motion")
                            .intercept { chain ->
                                val overlay = instance?.miniMenuOverlay
                                val ev = chain.args[0] as? MotionEvent
                                if (overlay?.isShowing() == true && ev != null) {
                                    if (isTouchInsideMiniMenu(ev)) {
                                        log("GESTURE_HANDLER_BLOCKED_DURING_MINI_MENU x=${ev.rawX} y=${ev.rawY}")
                                        val resultClass = m.returnType
                                        val undefineResult = runCatching {
                                            val ctor = resultClass.constructors.firstOrNull { it.parameterCount == 3 }
                                            ctor?.newInstance(-1, -1, null)
                                        }.getOrNull()
                                        return@intercept undefineResult
                                    }
                                }
                                chain.proceed()
                            }
                    }
                }

            // Hook MiuiFreeformModeGestureHandler.setDisableTouch: prevent WMShell from resetting mDisableTouch = false while menu is showing
            ghClass?.declaredMethods
                ?.filter { it.name == "setDisableTouch" && it.parameterCount == 1 }
                ?.forEach { m ->
                    runCatching {
                        module.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("bubbledrawer.freeform.gesture_handler_set_disable_touch")
                            .intercept { chain ->
                                val disabled = chain.args[0] as? Boolean ?: false
                                if (instance?.miniMenuOverlay?.isShowing() == true && !disabled) {
                                    log("PREVENTED_RESET_DISABLE_TOUCH_DURING_MINI_MENU")
                                    return@intercept null
                                }
                                chain.proceed()
                            }
                    }
                }

            // Hook InputMonitor.pilferPointers and InputManager.pilferPointers: prevent stealing pointer while menu is showing
            val inputMonitorClass = runCatching { classLoader.loadClass("android.view.InputMonitor") }.getOrNull()
            inputMonitorClass?.declaredMethods
                ?.filter { it.name == "pilferPointers" && it.parameterCount == 0 }
                ?.forEach { m ->
                    runCatching {
                        module.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("bubbledrawer.freeform.input_monitor_pilfer")
                            .intercept { chain ->
                                if (instance?.miniMenuOverlay?.isShowing() == true) {
                                    log("BLOCKED_INPUT_MONITOR_PILFER_DURING_MINI_MENU")
                                    return@intercept null
                                }
                                chain.proceed()
                            }
                    }
                }

            val inputManagerClass = runCatching { classLoader.loadClass("android.hardware.input.InputManager") }.getOrNull()
            inputManagerClass?.declaredMethods
                ?.filter { it.name == "pilferPointers" && it.parameterCount == 1 }
                ?.forEach { m ->
                    runCatching {
                        module.hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("bubbledrawer.freeform.input_manager_pilfer")
                            .intercept { chain ->
                                if (instance?.miniMenuOverlay?.isShowing() == true) {
                                    log("BLOCKED_INPUT_MANAGER_PILFER_DURING_MINI_MENU")
                                    return@intercept null
                                }
                                chain.proceed()
                            }
                    }
                }

            log("HOOK_INSTALLED_hookMiniTouchForMenu_complete")
        }

        private fun hookGestureAnimation(
            module: XposedModule,
            classLoader: ClassLoader,
        ) {
            val animClass = runCatching {
                classLoader.loadClass("com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeAnimation")
            }.getOrNull() ?: return

            animClass.declaredMethods.filter {
                it.name == "startGestureAnimation" && it.parameterCount == 3
            }.forEach { m ->
                runCatching {
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("bubbledrawer.freeform.gesture_anim")
                        .intercept { chain ->
                            // 窗口正在动 —— 联系人条的跟随由此切回逐帧（空闲时它是定时器采样）。
                            // 一次 volatile 读 + 一次 post，跑在 `wmshell.main` 上，可以忽略不计。
                            com.repl.bubbledrawer.contactbar.onFreeformWindowMotion?.invoke()
                            // 迷你小窗 as the ROM's own 迷你动画 (type 11). This only makes sense once the
                            // ROM has *already* turned the window into 迷你小窗 — its animation target is
                            // then the mini rect and its scale the mini scale, both of which this keeps.
                            // [miniSwipeConvertedAt] is the proof of that: the ROM's own 迷你 branch
                            // announces the mode change inside `onBottomCaptionHandleMotionEvents`, i.e.
                            // before it builds this animation. On a ROM whose gesture has no such branch
                            // (HyperOS 3) the target is still the plain 小窗's rect, and rewriting it with
                            // the line below would fly the window to the top of the screen at full size —
                            // there the conversion is driven after the release instead, see
                            // [scheduleMiniFallback].
                            if (sForceMiniTransition && miniSwipeConvertedAt != 0L) {
                                val taskInfo = chain.args[1]
                                val animTarget = chain.args[2]
                                log("INTERCEPT_GESTURE_ANIM_TO_MINI taskInfo=$taskInfo animTarget=$animTarget")
                                if (animTarget != null && taskInfo != null) {
                                    runCatching {
                                        val targetBounds = animTarget.javaClass.getMethod("getTaskBounds").invoke(animTarget) as? Rect
                                        val scaleX = (animTarget.javaClass.getMethod("getScaleX").invoke(animTarget) as? Number)?.toFloat() ?: 0.45f

                                        val stroke = runCatching {
                                            val utilsClass = classLoader.loadClass("android.util.MiuiMultiWindowUtils")
                                            utilsClass.getField("MINI_FREEFORM_PADDING_STROKE").getInt(null)
                                        }.getOrDefault(15)

                                        val ctx = instance?.context
                                        val targetTop = if (ctx != null) {
                                            runCatching {
                                                val displayInfoClass = classLoader.loadClass("com.android.wm.shell.multitasking.common.MultiTaskingDisplayInfo")
                                                val getMovableBoundsMethod = displayInfoClass.getMethod(
                                                    "getMovableBounds",
                                                    Context::class.java,
                                                    Int::class.javaPrimitiveType,
                                                    Int::class.javaPrimitiveType,
                                                    Int::class.javaPrimitiveType,
                                                )
                                                val mb = getMovableBoundsMethod.invoke(null, ctx, stroke, stroke, 0) as Rect
                                                mb.top
                                            }.getOrDefault(130)
                                        } else 130

                                        if (targetBounds != null) {
                                            targetBounds.offsetTo(targetBounds.left, targetTop)
                                            val setAnimParamMethod = animTarget.javaClass.getMethod(
                                                "setAnimParam",
                                                Rect::class.java,
                                                Float::class.javaPrimitiveType,
                                                Float::class.javaPrimitiveType,
                                                Float::class.javaPrimitiveType,
                                            )
                                            setAnimParamMethod.invoke(animTarget, targetBounds, scaleX, scaleX, 0.0f)
                                            log("UPDATED_ANIM_TARGET_FOR_MINI bounds=$targetBounds scale=$scaleX targetTop=$targetTop")
                                        }
                                    }.onFailure {
                                        log("FAILED_TO_UPDATE_ANIM_TARGET", it)
                                    }
                                }
                                // Change animation type from 2 to 11 (the official mini window spring animation!)
                                chain.args[0] = 11
                            }
                            chain.proceed()
                        }
                }
            }
            log("HOOK_INSTALLED_MiuiFreeformModeAnimation.startGestureAnimation")
        }

        /**
         * 「回桌面不收迷你」— 拦掉 ROM 的**批量**小窗 → 迷你小窗。
         *
         * 触发链（源码 `out/WMShell_src/…/multitasking/miuifreeform/`）：framework 发
         * `com.miui.fullscreen_state_change`（`state=toHome` / `toRecents`）→
         * `MiuiFreeformModeSettingsObserver.FreeFormBroadcastReceiver:90-103` →
         * `MiuiFreeformModeController.allFreeformFromFreeformToMini()` →（可能先等 100ms 给旋转让路）
         * `allFreeformFromFreeformToMiniInternal()`：遍历 Z 序上**每一个**小窗，跳过正在拖拽缩放/
         * 连续动画/转场中/正在退出或进入/elude 动画的那些，其余逐个 `fromFreeformToMini(taskId)`
         * （mode 1 + 迷你缩放 + 贴到上方左/右角 + `startGestureAnimation(11, …)`）。所以"上滑回桌面
         * 时所有小窗一起变迷你"只可能来自这里；用户 2026-10-17 要的就是把这个关掉。
         *
         * 为什么钩这两个方法而不是那个广播接收器：`Internal()` 还会被"旋转让路"的延迟 runnable 和
         * shell 命令直接调用，只拦外层在那两条路上会漏；而这两个方法都无参、只做这一件事，拦掉没有
         * 副作用 —— `mPendingFreeformToMiniForRecents` / `mPendingMiniTaskIds` 这些记账只由
         * `Internal()` 写，跳过就不会留下"半个批量迷你化"的残留。
         *
         * **只拦"批量"**：`fromFreeformToMini(int)` 本身不动 —— 模块自己的「小白条上滑 → 迷你小窗」
         * 调的正是它（[fromFreeformToMini]），ROM 从最近任务以小窗打开走的也是另一条
         * `startEnterMiniShellTransition(..., "fromRecent")`，两者都照旧。
         *
         * 开关只受 `RemotePrefs.KEY_HOME_KEEP_FREEFORM` 控制，**不看** Flyme 样式小窗总开关
         * （同 [hookFreeformAvoidOffset]：这是对 ROM 自身行为的开关，原生小窗模式下同样要生效）。
         */
        private fun hookHomeKeepFreeform(
            module: XposedModule,
            prefs: SharedPreferences,
            classLoader: ClassLoader,
        ) {
            val cls = runCatching {
                classLoader.loadClass("com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeController")
            }.getOrNull()
            if (cls == null) {
                log("HOME_KEEP_HOOK_UNAVAILABLE reason=class")
                return
            }

            var installed = 0
            cls.declaredMethods
                .filter { it.parameterCount == 0 && it.returnType == Void.TYPE }
                .filter {
                    it.name == "allFreeformFromFreeformToMini" || it.name == "allFreeformFromFreeformToMiniInternal"
                }
                .forEach { method ->
                    runCatching {
                        module.hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("bubbledrawer.freeform.home_keep.${method.name}")
                            .intercept { chain ->
                                if (!RemotePrefs.read(prefs).homeKeepFreeform) return@intercept chain.proceed()
                                log("FREEFORM_HOME_KEEP_SKIPPED via=${method.name}")
                                null
                            }
                        installed++
                    }.onFailure {
                        module.log(Log.WARN, TAG, "HOME_KEEP_HOOK_FAILED_${method.name}", it)
                    }
                }
            if (installed > 0) {
                log("HOOK_INSTALLED_MiuiFreeformModeController.allFreeformFromFreeformToMini hooks=$installed")
            } else {
                // 类在、方法不在：这台 ROM 的批量迷你化没走那两个入口（老 ROM 常见），开关无事可做 ——
                // 体检页据此报 INFO 而不是 WARN，免得把"这台本来就不收迷你"说成故障。
                log("HOME_KEEP_HOOK_UNAVAILABLE reason=method")
            }
        }
    }
}

/**
 * 小窗上下位置的滑杆（设置项「小窗上下位置」）：竖屏时相对**正中的上下余量**的百分比。
 *
 * `0` = 正中、`-100` = 贴顶、`+100` = 贴底 —— 因为余量 = 屏幕高 − 可见高，所以任何取值都落在屏幕内，
 * 换分辨率/换缩放都不用重算（用户 2026-10-11：「竖方向默认居中但可以上下位置调整」）。
 *
 * 横屏不调用它：那里的窗口几乎占满高度，挪一下就出屏（见 `centerRectOnScreen`）。
 */
fun verticalOffsetPx(pct: Int, screenH: Int, visualH: Float): Int {
    val slack = (screenH - visualH).coerceAtLeast(0f)
    return Math.round(slack * pct.coerceIn(-100, 100) / 200f)
}

/**
 * Whether the caller of the ROM's rect function asked for a specific position: `getCustomFreeformRect`
 * takes `(Context, int left, int top, …)` and answers with the ROM's own rect *pinned* there, while
 * the sentinel [MiuiFreeform.POS_AUTO] means "you decide" (see `MiuiFreeform.customFreeformRect`).
 *
 * The module's 小窗居中 must not override a position that was asked for: the扇形 launch states the
 * side the user swiped from, and a ROM launch that states one (安全中心's sidebar does) means it too.
 * Returns the requested `left`, or null when the position is the ROM's business.
 */
fun requestedLeft(args: List<Any?>): Int? {
    val left = args.getOrNull(1) as? Int ?: return null
    val top = args.getOrNull(2) as? Int ?: return null
    if (left == MiuiFreeform.POS_AUTO || top == MiuiFreeform.POS_AUTO) return null
    // `MiuiFreeformServiceImpl.addLaunchFreeformActivityOptionIfNeed` passes -1/-1 when it has no
    // source rect — "no position", not a request to pin the window at the left edge.
    if (left == -1 && top == -1) return null
    return left
}

/**
 * The 小窗 scale that is actually handed to the ROM: the user's setting ([target]), capped by what
 * the ROM itself computed for the very same window ([romScale], i.e. our preferred scale after its
 * own `reviewFreeFormBounds` — see `FlymeFreeformController.hookFreeformScale`).
 *
 * The cap matters because the ROM's unscaled task bounds are orientation dependent: a landscape
 * window is `aspect` times wider than a portrait one on the same short side, so the setting that
 * looks right in portrait makes a 横屏 window wider than the display — and the ROM's rotation path
 * only *offsets* such a rect into place, never shrinks it, so the content ends up off the screen.
 *
 * A ROM answer that is unusable (null when the method is missing, 0 when its resolution config
 * cannot be read, NaN) falls back to the setting, which is what the hook did before the cap existed.
 */
fun fitFreeformScale(target: Float, romScale: Float?): Float =
    if (romScale == null || !romScale.isFinite() || romScale <= 0f) target else minOf(target, romScale)

/**
 * Whether a number a ROM handed back can be the scale a 小窗 is drawn at: positive, finite and within
 * the range the ROM itself uses (a scale above 1 would mean a window bigger than its own unscaled
 * bounds, and a zero/NaN answer means the ROM could not compute one).
 */
fun Float?.isUsableScale(): Boolean =
    this != null && this.isFinite() && this > 0.05f && this <= 1.5f

/**
 * Which scale the centring arithmetic may use, most trustworthy first:
 *
 *  1. [applied] — what the ROM itself said it will apply (the `getRestoredBounds` return value, or the
 *     last answer of the module's own `getFreeFormScale`/`getOriFreeformScale` hook). The window is
 *     drawn at `unscaled bounds x this`, so centring with it puts the *real* window in the middle.
 *  2. [assumed] — the ROM's review of the user's setting (`reviewFreeFormBounds`), i.e. what the module
 *     used to centre with. Correct exactly while the module's scale hook is what decides the size.
 *  3. [setting] — the raw 缩放 setting, the last resort when the ROM cannot be asked at all.
 *
 * Why the order matters: centring with a scale that is too large puts the real (smaller) window up and
 * to the left of the middle by half the size difference, and vice versa — and the same wrong size also
 * makes the 避让 step compare a rect that does not match the window, so it can push a window that does
 * not even overlap. Both are visible as "设置了居中，可是并不居中".
 */
fun centerScale(applied: Float?, assumed: Float?, setting: Float): Float {
    if (applied.isUsableScale()) return applied as Float
    if (assumed != null && assumed.isFinite() && assumed > 0f) return assumed
    return setting
}

/**
 * Top-left corner that centers a `visualW x visualH` window on a `screenW x screenH` screen — the
 * arithmetic behind 小窗居中, for either orientation.
 *
 * The caller passes the window's *visible* size (`unscaled bounds x the scale the ROM will apply`,
 * see `fitFreeformScale`): using the raw setting instead is what used to push a 横屏窗口 off both
 * screen edges, because its unscaled bounds are ~2.2x wider on the same short side.
 */
fun centeredOrigin(visualW: Float, visualH: Float, screenW: Int, screenH: Int): Pair<Int, Int> =
    Math.round((screenW - visualW) / 2f) to Math.round((screenH - visualH) / 2f)
