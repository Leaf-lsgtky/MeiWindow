package com.repl.bubbledrawer.xposed

import android.graphics.Rect
import android.graphics.Region
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.SurfaceControl
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * COOPERATION WITH THE LAUNCHER, WITHOUT TOUCHING THE LAUNCHER.
 *
 * Why this exists
 * ---------------
 * On this ROM a touch that starts in the bottom gesture band belongs to com.miui.home:
 * ~9 ms after DOWN its "[Gesture Monitor] swipe-up" has pilfered the stream (measured in
 * `dumpsys input`: `pilferingPointerIds=0…01` on swipe-up while our monitor was dropped
 * from the touch state), and its Rust recogniser then decides from the FIRST upward sample
 * (` Home gesture recognized, delay pilfer`,
 * `formula=down_y-current_y>record_area_height_px`). A later claim therefore interrupts an
 * animation that is already committed: the corner swipe opens the fan AND the phone goes
 * home. Owning the DOWN at once avoids that (the launcher skips its own take-over), but it
 * costs the app its touch in that strip.
 *
 * What the reference project does instead
 * ---------------------------------------
 * MiuiBackGestureHook keeps MiuiHome's native `GestureStubView` as the physical owner and
 * has the launcher publish an "accepted-DOWN token" that SystemUI must match before it may
 * pilfer (AGENTS.md:269-273), with the launcher's own processor neutralised at its
 * accepted-input boundary (AGENTS.md:256-262). On Android 17 that launcher-side half is a
 * NATIVE payload — MiuiHome is forked by Xiaomi's own `/system_ext/bin/hyos_spawner`, not
 * by the ART zygote, so no LSPosed Java hook can ever enter it (their own report,
 * ANDROID_17_MIUI_HOME_NATIVE_LOADER_REPORT.md:25-42,387-394, and confirmed here: the
 * module logs nothing at all from com.miui.home even with it in scope and after a restart).
 *
 * What we can do from system_server, in Java
 * -----------------------------------------
 * A gesture monitor receives a gesture only if the DOWN falls inside its `touchableRegion`
 * (`GestureMonitorSpyWindow` even sets `replaceTouchableRegionWithCrop(null)` so the region
 * comes from its surface crop). `InputManagerService.mInputMonitors` maps every monitor
 * token to its `GestureMonitorSpyWindow`, and that object exposes both the
 * `InputWindowHandle` and its `SurfaceControl`. So when MiuiHome creates its monitor we can
 * hand it a region with the corner boxes cut out: its recogniser then never sees a corner
 * DOWN, never recognises HOME there and never pilfers — cooperation at the region level,
 * with the app keeping its own DOWN and nothing injected.
 *
 * Safety: read-only for every other monitor; fail-open (any uncertainty leaves the original
 * untouched); exception mode PROTECTIVE; the only effect is a smaller region for one
 * MiuiHome monitor, and disabling the module restores the stock region on the next
 * launcher start.
 */
class LauncherMonitorRegion(
    private val module: XposedModule,
    private val logger: (Int, String, Throwable?) -> Unit,
) {
    private var installed = false

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        if (!ENABLED) {
            // DISABLED ON DEVICE EVIDENCE — do not re-enable without a new approach.
            //
            // The region write itself works (`ARBITER_REGION … ok=true`), but with it in
            // place MiuiHome loses its bottom up-swipe ENTIRELY: verified on device, an
            // up-swipe from the middle of the bottom edge no longer returns Home once the
            // patch is live, even though the patched region is the full screen minus two
            // small corner boxes and the monitor still prints its stock
            // `inputConfig=NOT_FOCUSABLE | TRUSTED_OVERLAY | SPY`. So MIUI's dispatcher is
            // not honouring an explicitly supplied `touchableRegion` on a spy window the way
            // AOSP does — most likely it only ever uses the surface crop, and clearing
            // `replaceTouchableRegionWithCrop` leaves the recogniser with nothing. The
            // corner drawer keeps working the deterministic way instead: CornerInputMonitor
            // owns the DOWN inside the strip, which makes MiuiHome skip its own take-over
            // ("on_pilfered_at_down: passthrough_eligible = true", "skip DOWN pilfer").
            //
            // If this is ever revisited, the shape to try is a CROP (MIUI's own
            // `updateInputMonitorTouchRegoinWithCrop(token, Rect)` keeps that flag true), but
            // a crop is a rectangle — it cannot carve the corner columns out of the bottom
            // band, which is exactly what this needs. A launcher-side native hook (see the
            // reference project's miui-home-hyos-native payload) is the only route that can.
            logger(Log.WARN, "ARBITER_DISABLED_BY_EVIDENCE", null)
            return
        }
        val clazz = runCatching { classLoader.loadClass(IMS) }
            .recoverCatching { Class.forName(IMS) }
            .getOrNull()
        if (clazz == null) {
            logger(Log.WARN, "ARBITER_NO_IMS", null)
            return
        }
        val method = runCatching {
            clazz.getDeclaredMethod(
                "monitorGestureInput",
                IBinder::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
            )
        }.getOrNull()
        if (method == null) {
            logger(Log.WARN, "ARBITER_NO_MONITOR_METHOD", null)
            return
        }
        hookMonitorCreation(clazz, method)
    }

    private fun hookMonitorCreation(imsClass: Class<*>, method: Method) {
        try {
            runCatching { method.isAccessible = true }
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("bubbledrawer.system.launcher_monitor_region")
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val name = chain.args.getOrNull(1) as? String
                        if (name == LAUNCHER_MONITOR_NAME) {
                            applyCornerCutRegion(chain.thisObject, name)
                        }
                    } catch (t: Throwable) {
                        logger(Log.WARN, "ARBITER_REGION_FAILED", t)
                    }
                    result
                }
            logger(Log.INFO, "ARBITER_HOOKED ${imsClass.name}.monitorGestureInput", null)
        } catch (t: Throwable) {
            logger(Log.WARN, "ARBITER_HOOK_FAILED", t)
        }
    }

    /** True only when the region was actually written back, so SystemUI can rely on it. */
    private fun applyCornerCutRegion(ims: Any?, name: String, attempt: Int = 0): Boolean {
        if (ims == null) return false
        val monitors = readField(ims, "mInputMonitors") as? Map<*, *> ?: run {
            logger(Log.WARN, "ARBITER_NO_MONITOR_MAP", null)
            return false
        }
        // InputManagerService registers the window as "[Gesture Monitor] " + name
        // (decompiled monitorGestureInput: `String str2 = "[Gesture Monitor] " + str;`),
        // so the handle never carries the bare name.
        val wanted = MONITOR_PREFIX + name
        var applied = false
        val present = ArrayList<String>(monitors.size)
        for ((token, monitor) in monitors) {
            if (monitor == null) continue
            val handle = readField(monitor, "mWindowHandle") ?: continue
            val surface = readField(monitor, "mInputSurface") as? SurfaceControl ?: continue
            val monitorName = runCatching { handle.javaClass.getField("name").get(handle) as? String }
                .getOrNull()
            present.add(monitorName ?: "?")
            if (monitorName != wanted && monitorName != name) continue

            val region = cornerCutRegion() ?: return false
            // `replaceTouchableRegionWithCrop` (set by the framework constructor) makes the
            // dispatcher ignore `touchableRegion` and use the surface crop instead, so it
            // has to be cleared for our region to be the one that counts.
            runCatching { handle.javaClass.getField("replaceTouchableRegionWithCrop").setBoolean(handle, false) }
            val touchable = runCatching { handle.javaClass.getField("touchableRegion").get(handle) }
                .getOrNull() as? Region ?: continue
            touchable.set(region)

            val ok = pushInputWindowInfo(surface, handle)
            logger(
                if (ok) Log.INFO else Log.WARN,
                "ARBITER_REGION token=$token name=$monitorName bounds=${region.bounds} ok=$ok",
                null,
            )
            if (ok) {
                applied = true
                // NOTE: the shared remote preferences cannot carry the proof — LSPosed hands
                // hooked processes a READ-ONLY proxy ("UnsupportedOperationException: Read
                // only implementation" from edit()), so system_server can never publish it.
                // Any future arbiter needs a channel that works in that direction (Binder
                // service, Settings.Global, …) before SystemUI can rely on it.
                logger(Log.INFO, "ARBITER_REGION_APPLIED_NO_PROOF_CHANNEL", null)
            }
        }
        if (!applied) {
            // The monitor may be registered a moment after this call returns, and MIUI can
            // re-create it later (display or activity changes), so retry a few times before
            // giving up — SystemUI keeps owning the DOWN in the strip until the proof lands.
            logger(Log.WARN, "ARBITER_MONITOR_NOT_FOUND name=$name attempt=$attempt present=$present", null)
            if (attempt < RETRY_COUNT) {
                runCatching {
                    Handler(Looper.getMainLooper()).postDelayed(
                        { applyCornerCutRegion(ims, name, attempt + 1) },
                        RETRY_DELAY_MS,
                    )
                }
            }
        }
        return applied
    }

    /** Full display minus the bottom strip inside the corner columns. */
    private fun cornerCutRegion(): Region? {
        val metrics = runCatching { android.content.res.Resources.getSystem().displayMetrics }.getOrNull()
            ?: return null
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.density
        if (width <= 0 || height <= 0) return null
        val cornerWidth = (CORNER_WIDTH_DP * density).toInt().coerceAtMost(width / 3)
        val stripHeight = (STRIP_HEIGHT_DP * density).toInt().coerceAtMost(height / 4)
        val region = Region(0, 0, width, height)
        val top = height - stripHeight
        region.op(Rect(0, top, cornerWidth, height), Region.Op.DIFFERENCE)
        region.op(Rect(width - cornerWidth, top, width, height), Region.Op.DIFFERENCE)
        return region
    }

    private fun pushInputWindowInfo(surface: SurfaceControl, handle: Any): Boolean = runCatching {
        val handleClass = Class.forName("android.view.InputWindowHandle")
        val transaction = SurfaceControl.Transaction()
        val setter = SurfaceControl.Transaction::class.java.getMethod(
            "setInputWindowInfo",
            SurfaceControl::class.java,
            handleClass,
        )
        setter.invoke(transaction, surface, handle)
        transaction.apply()
        true
    }.getOrElse {
        logger(Log.WARN, "ARBITER_SET_INFO_FAILED", it)
        false
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

    private companion object {
        /**
         * OFF by device evidence: with the patch live MiuiHome's bottom up-swipe stops
         * working everywhere (see install()). Kept as documentation of what was tried and
         * why the Java-reachable half of "cooperate with the launcher" is not viable.
         */
        const val ENABLED = false

        const val IMS = "com.android.server.input.InputManagerService"

        /** MiuiHome's home-gesture spy monitor (dumpsys input: "[Gesture Monitor] swipe-up"). */
        const val LAUNCHER_MONITOR_NAME = "swipe-up"

        /** InputManagerService names every monitor window with this prefix. */
        const val MONITOR_PREFIX = "[Gesture Monitor] "

        /** Corner box width, matching CornerInputMonitor's trigger box. */
        const val CORNER_WIDTH_DP = 48f

        /** Bottom strip owned by the system's home gesture. */
        const val STRIP_HEIGHT_DP = 28f

        /** the monitor can appear just after monitorGestureInput() returns / be re-created */
        const val RETRY_COUNT = 10
        const val RETRY_DELAY_MS = 300L
    }
}
