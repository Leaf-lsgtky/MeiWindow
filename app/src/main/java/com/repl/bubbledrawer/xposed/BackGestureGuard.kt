package com.repl.bubbledrawer.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * Back-gesture arbitration for the corner drawer, trimmed to what this ROM actually
 * needs. Two earlier attempts through this class were removed after device evidence
 * showed they guarded nothing (see CornerInputMonitor's header for the full story):
 *
 *  • a DOWN gate inside the back plugin — `EdgeBackGestureHandler` has no motion
 *    handling on this build, so the gate never saw a stroke;
 *  • swallowing `android.view.InputMonitor.pilferPointers()` and handing the stream
 *    back later — no such call ever happens during a stroke on this ROM (zero
 *    `PILFER_ANY` lines in the module log), and there is no `[Gesture Monitor]
 *    edge-swipe` window in `dumpsys input` at all, so SystemUI's AOSP edge-back
 *    monitor is not the arbiter here. MiuiHome's touchable `GestureStubView` side
 *    window plus MIUI input redirection is.
 *
 * What remains is the one thing that still matters once the drawer DOES own a stroke:
 *
 *  COMMIT GUARD — `BackCallback.triggerBack()` / `setTriggerBack(boolean)`.
 *  The native handler decides BACK from its own state machine and never re-checks
 *  whether it still owns the pointers, so a corner stroke we claimed (and pilfered)
 *  would otherwise open the fan AND go back. Suppression is armed ONLY for a claimed
 *  stroke (`CornerInputMonitor.isCornerCommitWindow`), and cancellations
 *  (`setTriggerBack(false)`) always proceed — they merely clear native state.
 *
 *  On a ROM where that handler is not wired at all (this one) the hooks simply never
 *  fire; on one where it is, they keep BACK out of a claimed drawer gesture.
 */
class BackGestureGuard(
    private val module: XposedModule,
    private val isCommitWindow: () -> Boolean,
    private val logger: (Int, String, Throwable?) -> Unit,
) {
    private var installed = false

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        hookCommit(classLoader)
        hookMonitorCreationObserver()
        hookTokenPilferObserver()
    }

    /**
     * Commit-side suppression, only ever armed for strokes the drawer CLAIMED.
     * `EdgeBackGestureHandler`'s BackCallback is `EdgeBackGestureHandler$5` on this
     * HyperOS build; R8 strips the InnerClasses attribute, so `declaredClasses` can be
     * empty — hence the numbered candidates first, then `declaredClasses` as a fallback
     * for other ROMs, with a shape check on every candidate before hooking.
     */
    private fun hookCommit(classLoader: ClassLoader) {
        try {
            val handler = runCatching {
                classLoader.loadClass(
                    "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler",
                )
            }.getOrNull() ?: return // vanilla AOSP layout differs; the drawer needs no guard there
            val candidates = ArrayList<Class<*>>()
            for (suffix in 1..9) {
                runCatching { classLoader.loadClass(handler.name + "$" + suffix) }
                    .getOrNull()?.let { candidates.add(it) }
            }
            candidates.addAll(handler.declaredClasses)
            val callbacks = candidates.distinct().filter { c ->
                c.declaredMethods.any { it.name == "triggerBack" && it.parameterCount == 0 } &&
                    c.declaredMethods.any {
                        it.name == "setTriggerBack" && it.parameterCount == 1 &&
                            (it.parameterTypes[0] == Boolean::class.javaPrimitiveType ||
                                it.parameterTypes[0] == java.lang.Boolean::class.java)
                    }
            }
            if (callbacks.isEmpty()) {
                logger(Log.WARN, "BACK_COMMIT_GUARD_NO_TARGET", null)
                return
            }
            var index = 0
            callbacks.forEach { c ->
                c.declaredMethods.filter { it.name == "triggerBack" || it.name == "setTriggerBack" }
                    .forEach { m ->
                        val name = m.name
                        module.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("bubbledrawer.back_commit_guard." + index++)
                            .intercept { chain ->
                                val cancelling = name == "setTriggerBack" &&
                                    chain.getArg(0) == false
                                if (!cancelling && isCommitWindow()) {
                                    logger(Log.INFO, "BACK_COMMIT_BLOCKED_IN_CORNER", null)
                                    null
                                } else {
                                    chain.proceed()
                                }
                            }
                        logger(Log.INFO, "BACK_COMMIT_GUARD_HOOK_" + c.name + "." + name, null)
                    }
            }
        } catch (t: ReflectiveOperationException) {
            logger(Log.WARN, "BACK_COMMIT_GUARD_UNAVAILABLE", t)
        } catch (t: LinkageError) {
            logger(Log.WARN, "BACK_COMMIT_GUARD_LINKAGE", t)
        }
    }

    /**
     * READ-ONLY field diagnostics. Logs every gesture monitor this process creates, so
     * `dumpsys input` questions ("which channel owns the side band?") can be answered
     * from the module log alone on a device without adb. Our own corner monitor shows
     * up here too, which makes the transport actually in use visible.
     */
    private fun hookMonitorCreationObserver() {
        val clazz = runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal")
        }.getOrNull() ?: return
        clazz.declaredMethods.filter { it.name == "monitorGestureInput" }
            .forEachIndexed { i, m ->
                runCatching { m.isAccessible = true }
                module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("bubbledrawer.monitor_creator." + i)
                    .intercept { chain ->
                        val name = chain.getArgs().firstOrNull() as? String
                        logger(Log.INFO, "MONITOR_CREATED name=$name", null)
                        chain.proceed()
                    }
            }
    }

    /**
     * READ-ONLY diagnostics for the token-level pilfer path
     * (`InputManagerGlobal.pilferPointers(IBinder)`) — the same API the drawer uses for
     * its own claim. Logging it proves, per stroke, whether anything ELSE took the
     * stream over; `own=true` is our own corner claim.
     */
    private fun hookTokenPilferObserver() {
        val clazz = runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal")
        }.getOrNull() ?: return
        val m = clazz.declaredMethods.firstOrNull {
            it.name == "pilferPointers" && it.parameterCount == 1 &&
                it.parameterTypes[0] == android.os.IBinder::class.java
        } ?: return
        runCatching { m.isAccessible = true }
        module.hook(m)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("bubbledrawer.token_pilfer_observer")
            .intercept { chain ->
                val stack = Thread.currentThread().stackTrace.drop(2).take(4).joinToString("<-") {
                    it.className.substringAfterLast('.') + "." + it.methodName
                }
                logger(Log.INFO, "TOKEN_PILFER at=$stack", null)
                chain.proceed() // observe only — never change behavior here
            }
    }
}
