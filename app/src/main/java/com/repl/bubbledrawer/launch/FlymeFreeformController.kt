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
import java.lang.reflect.Proxy
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

    /** taskId → latch expiry: windows the mask must stay away from (closing, or mid-gesture). */
    private val suspended = HashMap<Int, Long>()

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

    // Gesture tracking for bottom bar
    var gestureDownY = 0f
    var gestureDownTime = 0L
    var gestureHoverArmed = false
    var hoverScheduled = false

    /** The window whose bottom caption is being dragged (set on ACTION_DOWN, WMShell thread). */
    @Volatile private var gestureTaskId = 0

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
        if (takeoverCancelledFor == taskId) takeoverCancelledFor = null
        if (maskTaskId == taskId) maskTaskId = null
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
        suspendWindow(taskId, "CLOSING")
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

    private fun isTargetable(taskId: Int, info: Any): Boolean {
        if (suspended.containsKey(taskId)) return false
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
        onMain {
            suspended[taskId] = SystemClock.uptimeMillis() + SUSPEND_MS
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
            log("ACTION_UP_SWIPE_MINI taskId=$activeTaskId dy=$dy")
            return try {
                sForceMiniTransition = true
                proceed()
            } finally {
                sForceMiniTransition = false
                // 迷你小窗 carries no mask: keep it away while the transition runs (the mode change
                // that follows settles it for good).
                suspendGesture("GESTURE_MINI")
            }
        }

        if (dy > 80f && snap.flymeFreeformSwipeDownFull) {
            log("ACTION_UP_FULLSCREEN taskId=$activeTaskId dy=$dy")
            return try {
                sForceFullscreenTransition = true
                proceed()
            } finally {
                sForceFullscreenTransition = false
                suspendGesture("GESTURE_FULLSCREEN")
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

    fun fromFreeformToMini(taskId: Int) {
        executeOnShell {
            val controller = getMiuiFreeformModeController()
            var transitioned = false
            if (controller != null) {
                transitioned = runCatching {
                    val m = (controller.javaClass.declaredMethods + controller.javaClass.methods).firstOrNull {
                        it.name == "fromFreeformToMini" && it.parameterCount == 1
                    }
                    if (m != null) {
                        m.isAccessible = true
                        m.invoke(controller, taskId)
                        log("MINI_VIA_CONTROLLER taskId=$taskId")
                        true
                    } else {
                        val implCls = classLoader.loadClass("com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeController\$MiuiFreeformModeControlImpl")
                        val ctor = implCls.getDeclaredConstructor(controller.javaClass, Int::class.javaPrimitiveType)
                        ctor.isAccessible = true
                        val controlInstance = ctor.newInstance(controller, 0)
                        val m2 = (controlInstance.javaClass.declaredMethods + controlInstance.javaClass.methods).firstOrNull {
                            it.name == "fromFreeformToMini" && it.parameterCount == 1
                        }
                        if (m2 != null) {
                            m2.isAccessible = true
                            m2.invoke(controlInstance, taskId)
                            log("MINI_VIA_CONTROL_IMPL taskId=$taskId")
                            true
                        } else false
                    }
                }.onFailure {
                    log("MINI_VIA_CONTROLLER_FAILED taskId=$taskId", it)
                }.getOrDefault(false)
            }

            if (!transitioned) {
                log("MINI_TRANSITION_FAILED controller=$controller taskId=$taskId")
            }
        }
    }

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

    private fun triggerHapticFeedback() {
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

        /** `MiuiFreeformModeController.mMultiTaskingTaskRepository` — task id → window object. */
        @Volatile var savedRepository: Any? = null
        @Volatile var sForceMiniTransition = false
        @Volatile var sForceFullscreenTransition = false
        @Volatile var sForceRebound = false

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
            hookBoundsCentering(module, prefs, classLoader)
            hookTaskRepository(module, classLoader)
            hookRawTaskAppeared(module, classLoader)
            hookVelocityMonitor(module, classLoader)
            hookMiniBottomUpLimit(module, classLoader)
            hookTopCaptionMove(module, classLoader)
            hookBottomCaptionGestures(module, prefs, classLoader)
            hookResizeFocus(module, classLoader)
            hookTaskExit(module, classLoader)
            hookTaskToFront(module, classLoader)
            hookImeState(module, classLoader)
            hookGestureAnimation(module, classLoader)
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
            }.getOrNull() ?: return

            val method = runCatching {
                configClass.getDeclaredMethod(
                    "getFreeformArg",
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            }.getOrNull() ?: return

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
                        2 -> targetScale // ARGS_ORIGINAL_SCALE
                        4 -> { // ARGS_LEFT_MARGIN
                            if (snap.flymeFreeformCenter) {
                                -1.0f // Sentinel for horizontal centering in HyperOS: (displayShortSide - freeFormWidth) / 2
                            } else {
                                chain.proceed()
                            }
                        }
                        else -> chain.proceed()
                    }
                }
            log("HOOK_INSTALLED_MiuiFreeformLaunchConfig.getFreeformArg")
        }

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
            }.getOrNull() ?: return

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
                                logScale(m.name, chain.args, target, rom, used)
                                used
                            }
                    }
                }
            }
            log("HOOK_INSTALLED_MiuiMultiWindowUtils.getFreeFormScale")
        }

        private fun hookBoundsCentering(
            module: XposedModule,
            prefs: SharedPreferences,
            classLoader: ClassLoader,
        ) {
            val utilsClass = runCatching {
                classLoader.loadClass("android.util.MiuiMultiWindowUtils")
            }.getOrNull()

            utilsClass?.declaredMethods?.filter {
                it.name == "getFreeformRect" || it.name == "getCustomFreeformRect"
            }?.forEach { m ->
                runCatching {
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("bubbledrawer.freeform.bounds.${m.name}.${m.parameterCount}")
                        .intercept { chain ->
                            val result = chain.proceed()
                            val snap = RemotePrefs.read(prefs)
                            if (snap.flymeFreeformEnabled && snap.flymeFreeformCenter && result is Rect && !result.isEmpty) {
                                centerRectOnScreen(result, snap)
                            }
                            result
                        }
                }
            }

            val utilClass = runCatching {
                classLoader.loadClass("com.android.wm.shell.multitasking.miuimultiwinswitch.MulWinSwitchInteractUtil")
            }.getOrNull()

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
                                centerRectOnScreen(result, snap)
                            }
                            result
                        }
                }
            }

            val miniHandlerClass = runCatching {
                classLoader.loadClass("com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeMiniStateHandler")
            }.getOrNull()

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
                                    centerRectOnScreen(rect, snap)
                                }
                            }
                            result
                        }
                }
            }
            log("HOOK_INSTALLED_BoundsCentering")
        }

        private fun centerRectOnScreen(rect: Rect, snap: RemotePrefs.Snapshot) {
            // Portrait only. A landscape window is ~2.2x wider on the same short side and its scale is
            // clamped to exactly fit the display (hookFreeformScale), so centering it with this
            // portrait math would compute a negative left and hang it off *both* screen edges. The
            // ROM's own `getLeftMargin` already centered it with the clamped scale — our
            // ARGS_LEFT_MARGIN override (-1) is precisely what sends it down that branch.
            if (rect.width() >= rect.height()) return
            val dm = android.content.res.Resources.getSystem().displayMetrics
            val targetScale = (snap.flymeFreeformScale.coerceIn(50, 95)) / 100f
            val visualW = rect.width() * targetScale
            val visualH = rect.height() * targetScale
            val targetLeft = Math.round((dm.widthPixels - visualW) / 2f)
            val targetTop = Math.round((dm.heightPixels - visualH) / 2f)
            rect.offsetTo(targetLeft, targetTop)
            log("CENTERED_BOUNDS rect=$rect targetScale=$targetScale visual=(${visualW.toInt()}x${visualH.toInt()}) screen=(${dm.widthPixels}x${dm.heightPixels})")
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
                }
            }
            log("HOOK_INSTALLED_MiuiFreeformModeTaskInfo.getMiniBottomUpLimit")
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
                            if (sForceMiniTransition) {
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
    }
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
