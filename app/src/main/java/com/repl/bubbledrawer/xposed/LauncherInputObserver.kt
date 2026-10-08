package com.repl.bubbledrawer.xposed

import android.util.Log
import android.view.MotionEvent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * Launcher-process probe for the corner-drawer arbitration.
 *
 * WHY THIS LIVES IN `com.miui.home`
 * The bottom strip of this ROM belongs to the system launcher: `dumpsys input` shows the
 * full-screen spy `[Gesture Monitor] swipe-up` with ownerPid 32577 / ownerUid 10149 =
 * com.miui.home, and inside apps a corner ACTION_DOWN is followed 6–22 ms later by
 * ACTION_CANCEL for everyone else. Android 17 MiuiHome is a Rust/Flutter rewrite with NO
 * dex (its APK contains only libapp.so / libapp_launcher.so / libhyper_os_flutter.so /
 * libresources_frb.so), and its Rust strings name both take-over paths outright:
 *
 *   input_InputMonitor_pilferPointers        " Home pilfer_pointers"
 *   input_MiuiInputManager_request_redirect  "Redirect motion event on view("
 *
 * A JNI call INTO a framework method runs in the CALLER's process, so both paths enter
 * framework code right here. This observer therefore sees exactly which call takes the
 * gesture, on which thread, and whether the launcher's own tap passthrough
 * ("scheduled passthrough after 300ms", "passthrough timeout fired, injecting tap x=")
 * really fires — the evidence needed before changing any behaviour, and the reason the
 * in-SystemUI hooks never saw anything: the caller was never in our process.
 *
 * READ-ONLY BY CONSTRUCTION: every hook logs and then proceeds. Nothing here may change
 * launcher behaviour yet — a mistake inside MiuiHome's process can put the launcher into
 * a restart loop, which is far worse than the drawer being inelegant. Exception mode is
 * PROTECTIVE and installation is wrapped, so any failure degrades to "no logs".
 */
class LauncherInputObserver(
    private val module: XposedModule,
    private val logger: (Int, String, Throwable?) -> Unit,
) {
    private var installed = false

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        logger(Log.INFO, "LAUNCHER_OBSERVER_INSTALLING", null)
        hookInputMonitorPilfer(classLoader)
        hookInputManagerGlobalPilfer(classLoader)
        hookCancelCurrentTouch(classLoader)
        hookInjectInputEvent(classLoader)
        hookMonitorGestureInput(classLoader)
        hookMonitorRegionUpdates(classLoader)
        logger(Log.INFO, "LAUNCHER_OBSERVER_READY", null)
    }

    /** android.view.InputMonitor.pilferPointers() — what MiuiHome's Rust " Home pilfer_pointers" calls. */
    private fun hookInputMonitorPilfer(classLoader: ClassLoader) {
        val clazz = load(classLoader, "android.view.InputMonitor") ?: return
        clazz.declaredMethods
            .filter { it.name == "pilferPointers" && it.parameterCount == 0 }
            .forEachIndexed { i, m ->
                hook(m, "launcher.monitor_pilfer.$i") { chain ->
                    "LAUNCHER_MONITOR_PILFER monitor=${monitorName(clazz, chain.thisObject)}"
                }
            }
    }

    /** android.hardware.input.InputManagerGlobal.pilferPointers(IBinder) — the token-level path. */
    private fun hookInputManagerGlobalPilfer(classLoader: ClassLoader) {
        val clazz = load(classLoader, "android.hardware.input.InputManagerGlobal") ?: return
        clazz.declaredMethods
            .filter { it.name == "pilferPointers" && it.parameterCount == 1 }
            .forEachIndexed { i, m ->
                hook(m, "launcher.token_pilfer.$i") { "LAUNCHER_TOKEN_PILFER" }
            }
    }

    /** InputManagerGlobal.cancelCurrentTouch() — the hard "cancel everything" path. */
    private fun hookCancelCurrentTouch(classLoader: ClassLoader) {
        val clazz = load(classLoader, "android.hardware.input.InputManagerGlobal") ?: return
        clazz.declaredMethods
            .filter { it.name == "cancelCurrentTouch" && it.parameterCount == 0 }
            .forEachIndexed { i, m ->
                hook(m, "launcher.cancel_touch.$i") { "LAUNCHER_CANCEL_CURRENT_TOUCH" }
            }
    }

    /** InputManager.injectInputEvent(InputEvent, int) — the launcher's own tap passthrough. */
    private fun hookInjectInputEvent(classLoader: ClassLoader) {
        val clazz = load(classLoader, "android.hardware.input.InputManager") ?: return
        clazz.declaredMethods
            .filter { it.name == "injectInputEvent" && it.parameterCount >= 1 }
            .forEachIndexed { i, m ->
                hook(m, "launcher.inject_event.$i") { chain ->
                    val event = chain.args.firstOrNull() as? MotionEvent
                    if (event == null) {
                        "LAUNCHER_INJECT_INPUT_EVENT kind=key"
                    } else {
                        "LAUNCHER_INJECT_INPUT_EVENT action=${event.actionMasked} " +
                            "x=${event.rawX} y=${event.rawY} deviceId=${event.deviceId} " +
                            "downTime=${event.downTime}"
                    }
                }
            }
    }

    /** InputManagerGlobal.monitorGestureInput(String, int) — which monitor is the launcher's. */
    private fun hookMonitorGestureInput(classLoader: ClassLoader) {
        val clazz = load(classLoader, "android.hardware.input.InputManagerGlobal") ?: return
        clazz.declaredMethods
            .filter { it.name == "monitorGestureInput" }
            .forEachIndexed { i, m ->
                hook(m, "launcher.monitor_create.$i") { chain ->
                    "LAUNCHER_MONITOR_CREATE name=${chain.args.firstOrNull()}"
                }
            }
    }

    /** MIUI's InputManagerGlobal.updateInputMonitor*(…) — region/visibility changes. */
    private fun hookMonitorRegionUpdates(classLoader: ClassLoader) {
        val clazz = load(classLoader, "android.hardware.input.InputManagerGlobal") ?: return
        clazz.declaredMethods
            .filter { it.name.startsWith("updateInputMonitor") }
            .forEachIndexed { i, m ->
                val name = m.name
                hook(m, "launcher.monitor_update.$i") { chain ->
                    "LAUNCHER_MONITOR_UPDATE $name args=${chain.args.joinToString(",")}"
                }
            }
    }

    // ------------------------------------------------------------------ plumbing

    private fun load(classLoader: ClassLoader, name: String): Class<*>? =
        runCatching { classLoader.loadClass(name) }
            .recoverCatching { Class.forName(name) }
            .getOrNull()
            ?: run {
                logger(Log.WARN, "LAUNCHER_OBSERVER_NO_CLASS_$name", null)
                null
            }

    private fun hook(
        method: Method,
        id: String,
        describe: (chain: XposedInterface.Chain) -> String,
    ) {
        try {
            runCatching { method.isAccessible = true }
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("bubbledrawer.$id")
                .intercept { chain ->
                    try {
                        logger(
                            Log.INFO,
                            "LAUNCHER_HOOK ${describe(chain)} " +
                                "thread=${Thread.currentThread().name} at=${stack()}",
                            null,
                        )
                    } catch (t: Throwable) {
                        logger(Log.WARN, "LAUNCHER_HOOK_LOG_FAILED", t)
                    }
                    chain.proceed()
                }
            logger(Log.INFO, "LAUNCHER_OBSERVER_HOOKED $id", null)
        } catch (t: Throwable) {
            logger(Log.WARN, "LAUNCHER_OBSERVER_HOOK_FAILED_$id", t)
        }
    }

    private fun monitorName(clazz: Class<*>, monitor: Any?): String? {
        if (monitor == null || !clazz.isInstance(monitor)) return null
        return runCatching { clazz.getMethod("getName").invoke(monitor) as? String }.getOrNull()
    }

    private fun stack(): String = Thread.currentThread().stackTrace
        .drop(3)
        .take(6)
        .joinToString("<-") { it.className.substringAfterLast('.') + "." + it.methodName }
}
