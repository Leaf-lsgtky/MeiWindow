package com.repl.bubbledrawer.xposed

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Binder
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.SurfaceControl
import com.repl.bubbledrawer.gesture.CornerTriggerRegion
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * `system_server` 侧对桌面 `[Gesture Monitor] swipe-up` 的输入表面裁剪仲裁。
 *
 * ## 为什么不碰 `com.miui.home`，也不改 `touchableRegion`
 *
 * 1. **不能 hook 系统桌面**：Android 17 上 `com.miui.home` 由小米私有的
 *    `/system_ext/bin/hyos_spawner` fork，不经过 ART zygote，标准 LSPosed Java 注入不会进入该进程，
 *    强行注入（原生 `.so` / 改进程池）还有崩溃与 ANR 风险（见
 *    `ANDROID_17_MIUI_HOME_NATIVE_LOADER_REPORT.md`）。
 * 2. **不能清 `replaceTouchableRegionWithCrop` 改 `touchableRegion`**：`GestureMonitorSpyWindow`
 *    构造时调了 `mWindowHandle.replaceTouchableRegionWithCrop(null)` + `t.setCrop(mInputSurface, null)`。
 *    `dumpsys SurfaceFlinger --layers` 证实：这个监视器在 SurfaceFlinger 里靠
 *    `replaceTouchableRegionWithCrop` 从父层 `Input Overlays` 继承触摸区域。一旦把
 *    `replaceTouchableRegionWithCrop` 设成 `false`，MIUI 的输入分发器不再認显式的 `touchableRegion`，
 *    导致屏幕正中间上滑回桌面全部失效（见 commit `9541170`）。
 *
 * ## 本方案怎么做（保留 `replaceTouchableRegionWithCrop = true`，只设 `SurfaceControl` 矩形裁剪）
 *
 * 既然 `replaceTouchableRegionWithCrop` 保持为 `true` 时，SurfaceFlinger 会用 `mInputSurface`
 * 自己的 `crop` 作为该 spy 窗口的 `touchableRegion`，我们直接在 `mInputSurface` 上执行：
 *
 * ```
 * SurfaceControl.Transaction().setCrop(mInputSurface, Rect(leftCut, 0, width - rightCut, height)).apply()
 * ```
 *
 * - `Rect` 是矩形：把左右两侧各裁掉 `bottomExtentPx`（即用户设置的「沿底边范围」），保留中间完整的
 *   `[leftCut, width - rightCut] × [0, height]` 区域；
 * - **角落按下（`x < leftCut` 或 `x >= width - rightCut`）**：落在 `swipe-up` 的 crop 之外，
 *   InputDispatcher 根本不会把 `ACTION_DOWN` 发给桌面的 `swipe-up` 监视器 —— 从根源消除 ~8ms 的跨进程竞速；
 * - **中间底边上滑（`leftCut <= x < width - rightCut`）**：正常落在 crop 内，回桌面 / 悬停进最近任务完全不受影响；
 *   且 InputDispatcher 只在 `ACTION_DOWN` 时按 `touchableRegion` 选派触摸目标，一旦中间起手，后续手指滑进角落列也不会断流；
 * - **左右侧边返回**：归桌面的 `GestureStubLeft` / `GestureStubRight` 独立窗口管，不走 `swipe-up`，完全不受影响；
 * - **可逆恢复**：模块总开关关闭、或对应边角关闭时，自动把那一侧的裁剪恢复为 `0`（两边都关时 `setCrop(sc, null)`
 *   完全还原成系统默认状态）。
 */
class LauncherMonitorRegion(
    private val module: XposedModule,
    private val prefs: SharedPreferences,
    private val logger: (Int, String, Throwable?) -> Unit,
) {
    private var loader: ClassLoader? = null

    /** 最近一次捕获到的 `InputManagerService` 实例（从 `monitorGestureInput` 或 `pilferPointers` 拿到）。 */
    @Volatile
    private var imsInstance: Any? = null

    /** 上一次成功应用到某 token 的裁剪签名（`tokenHash|crop`），避免 `displayChanged`（如刷新率切换）刷屏。 */
    private var lastAppliedSignature: String? = null

    @Volatile
    private var disposed = false

    private var watchdogStarted = false

    private data class InitialCrop(val surface: SurfaceControl, val crop: Rect)
    private val initialCrop = ThreadLocal<InitialCrop?>()

    // Synchronous SurfaceFlinger transactions and Settings writes must not block system_server's main looper.
    private val worker = HandlerThread("bubbledrawer-launcher-crop", android.os.Process.THREAD_PRIORITY_BACKGROUND)
        .apply { start() }
    private val handler = Handler(worker.looper)

    // Reapply instead of treating a cached signature as proof of SurfaceFlinger's live state.
    // Also bounds the lifetime of an acknowledgement if hooks disappear during a reload.
    private val cropWatchdog = object : Runnable {
        override fun run() {
            if (disposed) return
            val interactive = runCatching {
                systemContext()?.getSystemService(PowerManager::class.java)?.isInteractive != false
            }.getOrDefault(true)
            if (interactive) runCatching { applyCrop("watchdog") }
                .onFailure { logger(Log.WARN, "ARBITER_REFRESH_FAILED", it) }
            if (!disposed) handler.postDelayed(this, if (interactive) CROP_REFRESH_MS else SCREEN_OFF_REFRESH_MS)
        }
    }

    /** 必须强引用持有：SharedPreferences 对 listener 只持弱引用。 */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in RELEVANT_KEYS) {
            scheduleApply("pref:$key")
        }
    }

    private var displayListenerRegistered = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                scheduleApply("displayChanged")
            }
        }
    }

    fun dispose() {
        disposed = true
        handler.removeCallbacksAndMessages(null)
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefsListener) }
        val ctx = imsInstance?.let { readField(it, "mContext") as? Context } ?: systemContext()
        // 先把 SurfaceControl 上的裁剪还回去，再写状态。只写状态不还原的话，模块被禁用/热重载后
        // 桌面 swipe-up 的角落仍然是裁掉的，而模块已经不跑了 —— 那两列里的上滑回桌面会一直失效。
        runCatching { applyCrop("dispose", forceClear = true) }
        publishCropState(ctx, false)
        if (displayListenerRegistered) {
            runCatching { ctx?.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener) }
            displayListenerRegistered = false
        }
        worker.quitSafely()
    }

    fun install(classLoader: ClassLoader) {
        loader = classLoader
        publishCropState(systemContext(), false)
        runCatching { prefs.registerOnSharedPreferenceChangeListener(prefsListener) }
        val clazz = loadClass(IMS_CLASS)
        if (clazz == null) {
            logger(Log.WARN, "ARBITER_NO_IMS", null)
            return
        }
        hookMonitorCreation(clazz)
        runCatching { hookInitialCrop(clazz) }
            .onFailure { logger(Log.WARN, "ARBITER_INITIAL_CROP_HOOK_FAILED", it) }
        hookMonitorRemoval(clazz)
        hookInstanceCapture(clazz)
    }

    /** Replace the constructor's setCrop(null), in the SAME transaction that first shows it. */
    private fun hookInitialCrop(imsClass: Class<*>) {
        val spyClass = loadClass("com.android.server.input.GestureMonitorSpyWindow") ?: return
        val constructors = spyClass.declaredConstructors.filter { constructor ->
            constructor.parameterTypes.any { it == String::class.java } &&
                constructor.parameterTypes.any { it == SurfaceControl::class.java }
        }
        if (constructors.isEmpty()) return
        val cropMethod = SurfaceControl.Transaction::class.java.getMethod(
            "setCrop", SurfaceControl::class.java, Rect::class.java,
        )
        module.hook(cropMethod)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("bubbledrawer.system.initial_launcher_crop")
            .intercept { chain ->
                val initial = initialCrop.get()
                if (!disposed && initial != null && chain.args[0] === initial.surface) {
                    val result = chain.proceed(arrayOf(initial.surface, initial.crop))
                    logger(Log.INFO, "ARBITER_INITIAL_CROP crop=${initial.crop.toShortString()}", null)
                    return@intercept result
                }
                chain.proceed()
            }
        constructors.forEachIndexed { index, constructor ->
            module.hook(constructor)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("bubbledrawer.system.launcher_spy_constructor.$index")
                .intercept { chain ->
                    val name = chain.args.firstOrNull { it is String } as? String
                    val displayId = chain.args.firstOrNull { it is Int } as? Int
                    val surface = chain.args.firstOrNull { it is SurfaceControl } as? SurfaceControl
                    if (disposed || displayId != Display.DEFAULT_DISPLAY || surface == null ||
                        name != MONITOR_PREFIX + LAUNCHER_MONITOR_NAME && name != LAUNCHER_MONITOR_NAME) {
                        return@intercept chain.proceed()
                    }
                    val crop = runCatching {
                        val ctx = imsInstance?.let { readField(it, "mContext") as? Context } ?: systemContext()
                        computeSwipeUpCrop(displayMetrics(ctx), RemotePrefs.read(prefs))
                    }.getOrNull() ?: return@intercept chain.proceed()
                    val previous = initialCrop.get()
                    initialCrop.set(InitialCrop(surface, crop))
                    try {
                        chain.proceed()
                    } finally {
                        if (previous == null) initialCrop.remove() else initialCrop.set(previous)
                    }
                }
            // setCrop may already have been inlined into this constructor by the boot image.
            module.deoptimize(constructor)
        }
        // Likewise, prevent the constructor itself from being bypassed by an inlined caller.
        imsClass.declaredMethods.filter { it.name == "createSpyWindowGestureMonitor" }
            .forEach { module.deoptimize(it) }
        logger(Log.INFO, "ARBITER_INITIAL_CROP_HOOKED count=${constructors.size}", null)
    }

    /**
     * 1) 拦截 `InputManagerService.monitorGestureInput`：每当 `com.miui.home` 重建 `swipe-up` 监视器时，
     *    立刻对新生成的 `GestureMonitorSpyWindow.mInputSurface` 应用裁剪。
     */
    private fun hookMonitorCreation(imsClass: Class<*>) {
        val methods = imsClass.declaredMethods.filter { it.name == "monitorGestureInput" }
        if (methods.isEmpty()) {
            logger(Log.WARN, "ARBITER_NO_MONITOR_METHOD", null)
            return
        }
        for ((index, method) in methods.withIndex()) {
            runCatching { method.isAccessible = true }
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("bubbledrawer.system.launcher_monitor_crop.$index")
                .intercept { chain ->
                    val ims = chain.thisObject
                    if (ims != null) onImsCaptured(ims)
                    val result = chain.proceed()
                    val name = chain.args.firstOrNull { it is String } as? String
                    if (!disposed && (name == LAUNCHER_MONITOR_NAME || name != null && name.startsWith(OUR_MONITOR_PREFIX))) {
                        // The ROM creates a FULL-screen spy surface before returning its channel.
                        // Posting this to the main queue lets the launcher consume corner DOWNs
                        // in between. Apply here, before it can attach its InputEventReceiver.
                        val identity = Binder.clearCallingIdentity()
                        try {
                            val ok = runCatching { applyCrop("monitorCreated:$name") }
                                .onFailure { logger(Log.WARN, "ARBITER_CREATE_CROP_FAILED", it) }
                                .getOrDefault(false)
                            if (!ok) scheduleApply("monitorCreated:$name")
                        } finally {
                            Binder.restoreCallingIdentity(identity)
                        }
                    }
                    result
                }
        }
        logger(Log.INFO, "ARBITER_HOOKED ${imsClass.name}.monitorGestureInput count=${methods.size}", null)
    }

    private fun hookMonitorRemoval(imsClass: Class<*>) {
        val method = imsClass.declaredMethods.firstOrNull {
            it.name == "removeSpyWindowGestureMonitor" && it.parameterCount == 1
        } ?: return
        method.isAccessible = true
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("bubbledrawer.system.launcher_monitor_removed")
            .intercept { chain ->
                val ims = chain.thisObject
                val monitors = ims?.let { readField(it, "mInputMonitors") as? Map<*, *> }
                val removed = monitors?.let { synchronized(it) { it[chain.args[0]] } }
                val handle = removed?.let { readField(it, "mWindowHandle") }
                val isLauncher = handle != null && isLauncherHandle(handle)
                val result = chain.proceed()
                if (!disposed && isLauncher) scheduleApply("monitorRemoved")
                result
            }
    }

    /**
     * 2) 顺手在高频/必经入口捕获已存活的 `InputManagerService` 实例：
     *    - 系统刚开机时，`swipe-up` 的创建直接触发上面的 `monitorGestureInput`；
     *    - `adb install -r` 热重载时，`swipe-up` 早就建好了，不会再调 `monitorGestureInput`，
     *      但模块在 SystemUI 侧重建自己的 `BubbleDrawer-corner-MultiTaskSwitch` 时一定会调一次
     *      `monitorGestureInput`，或者任何一次 `pilferPointersInternal` 都会把 `thisObject` 送过来。
     */
    private fun hookInstanceCapture(imsClass: Class<*>) {
        val method = imsClass.declaredMethods.firstOrNull {
            it.name == "pilferPointersInternal" && it.parameterCount == 1
        } ?: return
        runCatching { method.isAccessible = true }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("bubbledrawer.system.ims_capture")
            .intercept { chain ->
                val ims = chain.thisObject
                if (ims != null && imsInstance == null) {
                    onImsCaptured(ims)
                    scheduleApply("imsCapturedViaPilfer")
                }
                chain.proceed()
            }
    }

    private fun onImsCaptured(ims: Any) {
        if (disposed) return
        val first = imsInstance == null
        imsInstance = ims
        if (first) {
            handler.post {
                if (disposed) return@post
                ensureDisplayListener(ims)
                if (!watchdogStarted) {
                    watchdogStarted = true
                    handler.post(cropWatchdog)
                }
            }
        }
    }

    private fun ensureDisplayListener(ims: Any) {
        if (displayListenerRegistered) return
        val ctx = (readField(ims, "mContext") as? Context) ?: systemContext() ?: return
        val dm = ctx.getSystemService(DisplayManager::class.java) ?: return
        runCatching {
            dm.registerDisplayListener(displayListener, handler)
            displayListenerRegistered = true
        }.onFailure {
            logger(Log.WARN, "ARBITER_DISPLAY_LISTENER_FAILED", it)
        }
    }

    private fun scheduleApply(reason: String, attempt: Int = 0) {
        if (disposed) return
        handler.post {
            if (disposed) return@post
            val ok = runCatching { applyCrop(reason) }
                .onFailure { logger(Log.WARN, "ARBITER_APPLY_FAILED reason=$reason", it) }
                .getOrDefault(false)
            if (!ok && attempt < RETRY_COUNT) {
                handler.postDelayed(
                    { scheduleApply(reason, attempt + 1) },
                    RETRY_DELAY_MS,
                )
            }
        }
    }

    /**
     * 在 `InputManagerService.mInputMonitors` 里找到 `[Gesture Monitor] swipe-up`，
     * 只改它的 `mInputSurface` crop，不动 `replaceTouchableRegionWithCrop`。
     *
     * @param forceClear true = 不管设置如何都恢复全屏裁剪（模块卸载/禁用时用）。
     */
    @Synchronized
    private fun applyCrop(reason: String, forceClear: Boolean = false): Boolean {
        if (disposed && !forceClear) return false
        val ims = imsInstance ?: return false
        val monitors = readField(ims, "mInputMonitors") as? Map<*, *> ?: run {
            logger(Log.WARN, "ARBITER_NO_MONITOR_MAP", null)
            return false
        }
        val ctx = (readField(ims, "mContext") as? Context) ?: systemContext()
        val geometry = displayMetrics(ctx)
        val crop = if (forceClear) null else computeSwipeUpCrop(geometry, RemotePrefs.read(prefs))
        var applied = true
        var found = false
        val signatures = mutableListOf<String>()

        val entries = synchronized(monitors) { monitors.entries.map { it.key to it.value } }
        for ((token, monitor) in entries) {
            if (monitor == null) continue
            val handle = readField(monitor, "mWindowHandle") ?: continue
            if (!isLauncherHandle(handle)) continue
            found = true
            val surface = readField(monitor, "mInputSurface") as? SurfaceControl
            if (surface == null || !surface.isValid) { applied = false; continue }

            val cropDesc = crop?.toShortString() ?: "FULL(null)"
            val signature = "${System.identityHashCode(token)}|$cropDesc"
            signatures += signature

            // 确保 replaceTouchableRegionWithCrop 为 true（如果之前实验残留了 false，这里顺手修回来）。
            runCatching {
                handle.javaClass.getField("replaceTouchableRegionWithCrop").setBoolean(handle, true)
            }
            val ok = setSurfaceCrop(surface, handle, crop)
            applied = applied && ok
            if (!ok || reason != "watchdog" && signature != lastAppliedSignature) logger(
                if (ok) Log.INFO else Log.WARN,
                "ARBITER_CROP reason=$reason token=$token name=swipe-up crop=$cropDesc ok=$ok",
                null,
            )
        }
        // 状态必须反映「此刻 SurfaceControl 上到底有没有裁剪」：找不到目标监视器时桌面根本没裁，
        // 这里要写回 0 —— 否则 SystemUI 以为「已裁剪」不再在 DOWN 预抢，竞速会静默回来。
        if (!found && reason != "watchdog") {
            logger(Log.WARN, "ARBITER_NO_MONITOR_TARGET reason=$reason", null)
        }
        lastAppliedSignature = if (found && applied) signatures.joinToString(";") else null
        val active = found && applied && crop != null && geometry != null
        val state = if (active) {
            val boot = runCatching {
                android.provider.Settings.Global.getInt(ctx!!.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
            }.getOrDefault(-1)
            ArbiterCropState(boot, SystemClock.uptimeMillis(), geometry.width, geometry.height,
                geometry.rotation, crop.left, crop.right)
        } else null
        publishCropState(ctx, active, state)
        return found && applied
    }

    private fun isLauncherHandle(handle: Any): Boolean {
        val name = readField(handle, "name") as? String
        return (name == MONITOR_PREFIX + LAUNCHER_MONITOR_NAME || name == LAUNCHER_MONITOR_NAME) &&
            readField(handle, "displayId") == Display.DEFAULT_DISPLAY
    }

    /**
     * 计算 `swipe-up` 监视器的矩形裁剪区：
     * - 返回 `null` 表示不裁剪（恢复全屏默认：`setCrop(sc, null)`）；
     * - 开启左/右角时，从屏幕左边裁掉 `leftCut` px、从右边裁掉 `rightCut` px，高度保持 `[0, height]`。
     */
    private fun computeSwipeUpCrop(geometry: DisplayGeometry?, snap: RemotePrefs.Snapshot): Rect? {
        if (!snap.enabled || (!snap.left && !snap.right)) return null
        val (width, height, density) = geometry ?: return null
        if (width <= 0 || height <= 0) return null
        val extentPx = (CornerTriggerRegion.radiusPx(snap.bottomDp, density) + 0.5f).toInt()
        val (left, right) = launcherCropEdges(width, extentPx, snap.left, snap.right)
        if (left == 0 && right == width) return null
        return Rect(left, 0, right, height)
    }

    private data class DisplayGeometry(val width: Int, val height: Int, val density: Float, val rotation: Int)

    /**
     * 读取当前默认显示屏的**旋转后**真实宽高与 density。
     *
     * 不能用 `maximumWindowMetrics`：它返回的是各方向里的最大值（自然竖屏的 1220×2656），
     * 横屏时 `max(width)`/`max(height)` 会拼出 `2656×2656` —— 高度越界对 crop 无害，
     * 但如果 crop 的坐标空间跟着旋转，左右裁剪就会落到错误的边上。`Display.getRealSize`
     * 给的是当前方向下的物理尺寸（横屏 2656×1220），和桌面监视器的输入表面一致。
     */
    @Suppress("DEPRECATION")
    private fun displayMetrics(context: Context?): DisplayGeometry? {
        val density = context?.resources?.displayMetrics?.density?.takeIf { it > 0f }
            ?: runCatching { android.content.res.Resources.getSystem().displayMetrics.density }.getOrNull()
            ?: return null
        if (context != null) {
            runCatching {
                val display = context.getSystemService(DisplayManager::class.java)
                    ?.getDisplay(Display.DEFAULT_DISPLAY)
                if (display != null) {
                    val point = android.graphics.Point()
                    display.getRealSize(point)
                    if (point.x > 0 && point.y > 0) return DisplayGeometry(point.x, point.y, density, display.rotation)
                }
            }
        }
        // A fallback with unknown rotation must never be published as a valid acknowledgement.
        return null
    }

    private fun setSurfaceCrop(surface: SurfaceControl, handle: Any, crop: Rect?): Boolean = runCatching {
        val handleClass = Class.forName("android.view.InputWindowHandle")
        val setInfo = SurfaceControl.Transaction::class.java.getMethod(
            "setInputWindowInfo",
            SurfaceControl::class.java,
            handleClass,
        )
        val setCrop: Method = SurfaceControl.Transaction::class.java.getMethod(
            "setCrop",
            SurfaceControl::class.java,
            Rect::class.java,
        )
        SurfaceControl.Transaction().use { transaction ->
            setInfo.invoke(transaction, surface, handle)
            setCrop.invoke(transaction, surface, crop)
            // apply() merely queues the transaction. Acknowledge only after its synchronous
            // form returns; verified in this ROM's framework.jar Transaction.apply(boolean).
            SurfaceControl.Transaction::class.java.getMethod("apply", Boolean::class.javaPrimitiveType)
                .invoke(transaction, true)
        }
        true
    }.getOrElse {
        logger(Log.WARN, "ARBITER_SET_CROP_FAILED", it)
        false
    }

    /**
     * 把「`system_server` 已成功裁剪桌面 `swipe-up` 监视器」的状态写到 `Settings.Global`，
     * 供 `com.android.systemui` 侧的 [CornerInputMonitor] 同步读取（跨进程，零 IPC 竞速）：
     * 只要裁剪处于生效状态，SystemUI 在角落 `ACTION_DOWN` 时就保持纯观察（`mode=cropped`），
     * 等手指真正滑过触发阈值（`SpyAction.Activate`）才调用一次 `pilferPointers()`。
     */
    private fun publishCropState(context: Context?, active: Boolean, state: ArbiterCropState? = null) {
        val ctx = context ?: systemContext() ?: return
        runCatching {
            android.provider.Settings.Global.putString(
                ctx.contentResolver,
                SETTING_ARBITER_CROP_STATE,
                if (active) state?.encode().orEmpty() else "",
            )
            android.provider.Settings.Global.putInt(
                ctx.contentResolver,
                SETTING_ARBITER_CROP_ACTIVE,
                if (active) 1 else 0,
            )
        }
    }

    private fun systemContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getMethod("currentActivityThread").invoke(null)
        activityThread.getMethod("getSystemContext").invoke(current) as? Context
    }.getOrNull()

    private fun loadClass(name: String): Class<*>? {
        for (candidate in listOf(
            loader,
            javaClass.classLoader,
            runCatching { systemContext()?.classLoader }.getOrNull(),
            ClassLoader.getSystemClassLoader(),
            null,
        )) {
            try {
                return Class.forName(name, false, candidate)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun readField(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val field: Field? = runCatching { type.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                runCatching { field.isAccessible = true }
                return runCatching { field.get(target) }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    companion object {
        /** `Settings.Global` 键名：`1` 表示 `system_server` 已把桌面 `swipe-up` 裁出左右下角。 */
        const val SETTING_ARBITER_CROP_ACTIVE = "bubbledrawer_arbiter_crop_active"
        const val SETTING_ARBITER_CROP_STATE = "bubbledrawer_arbiter_crop_state"

        private const val IMS_CLASS = "com.android.server.input.InputManagerService"
        private const val LAUNCHER_MONITOR_NAME = "swipe-up"
        private const val MONITOR_PREFIX = "[Gesture Monitor] "
        private const val OUR_MONITOR_PREFIX = "BubbleDrawer-corner"

        private const val RETRY_COUNT = 6
        private const val RETRY_DELAY_MS = 300L
        private const val CROP_REFRESH_MS = 2_000L
        private const val SCREEN_OFF_REFRESH_MS = 10_000L

        private val RELEVANT_KEYS = setOf(
            RemotePrefs.KEY_ENABLED,
            RemotePrefs.KEY_LEFT,
            RemotePrefs.KEY_RIGHT,
            RemotePrefs.KEY_RANGE_DP,
            RemotePrefs.KEY_BOTTOM_DP,
            RemotePrefs.KEY_EDGE_DP,
        )
    }
}
