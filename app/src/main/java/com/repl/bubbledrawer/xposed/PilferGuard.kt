package com.repl.bubbledrawer.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * Arbitration between the corner drawer and HyperOS's native edge-BACK gesture
 * inside apps.
 *
 * DEVICE EVIDENCE (real finger, inside an app): `SPY_*_DOWN inBox=true` followed
 * 6–22 ms later by `SPY_*_SYSTEM_CANCEL claimed=false` — SystemUI's edge-swipe
 * monitor steals the stream long BEFORE our diagonal inward+upward thresholds
 * can be reached, so the drawer can never claim it. On the desktop and on the
 * lock screen the same DOWN survives (there MiuiHome/Keyguard owns the band),
 * which is exactly why earlier adb-injected tests "passed" — injection
 * (deviceId=-1) is not arbitrated by the native handler at all.
 *
 * The steal is `android.view.InputMonitor.pilferPointers()` (zero args), invoked
 * by the back handler through its own monitor (decompiled
 * EdgeBackGestureHandler$$ExternalSyntheticLambda1.java:33 →
 * displayBackGestureHandlerImpl.inputMonitorCompat.mInputMonitor.pilferPointers(),
 * InputMonitorCompat.java:22-27 monitorGestureInput("edge-swipe", displayId),
 * DaggerReferenceGlobalRootComponent.java:32426-32433). Our OWN claim is a
 * different entry point — `InputManager.pilferPointers(IBinder token)`
 * (CornerInputMonitor :pilfer) — so neutralizing the former never breaks us.
 *
 * Behaviour, mirroring the reference modules the user pointed at
 * (FlymeFreeform owns its corner zone; MiuiBackGestureHook only pilfers a
 * horizontal-intent stream and leaves vertical ones alone):
 *
 *  • while a corner stroke is ARMED (finger down inside the box, single finger,
 *    diagonal still undecided) a back monitor's pilfer is BLOCKED and remembered;
 *  • if the stroke ends WITHOUT a claim (a plain vertical back swipe, a straight
 *    inward drag, a second finger) the remembered monitor is pilfered on the
 *    native handler's behalf — a few dozen ms late, but the native gesture keeps
 *    its stream takeover and commits BACK exactly as before, so nothing outside
 *    the drawer's own semantics changes;
 *  • if the stroke IS claimed, the hand-off never happens and the native
 *    handler's commit is additionally suppressed (hookCommit below), because
 *    HyperOS decides BACK from its own state machine and does not re-check
 *    whether the steal succeeded.
 *
 * Only monitors whose channel name says "back"/"edge-swipe" are ever touched —
 * Launcher's bottom swipe-up/home monitor shares the same method, and blocking
 * it would break "swipe up from the corner to go home". Unknown names fail
 * open (a PILFER_CALL log line reveals them).
 */
class PilferGuard(
    private val module: XposedModule,
    private val isStreamActive: () -> Boolean,
    private val isCommitWindow: () -> Boolean,
    private val logger: (Int, String, Throwable?) -> Unit,
) {
    private var installed = false

    /** the back monitor whose steal we swallowed for the current corner stroke */
    @Volatile
    private var pendingHandoff: Any? = null

    private val inputMonitorClass: Class<*>? = runCatching {
        Class.forName("android.view.InputMonitor")
    }.getOrNull()

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        hookPilfer()
        hookCommit(classLoader)
    }

    private fun hookPilfer() {
        val clazz = inputMonitorClass
        if (clazz == null) {
            logger(Log.WARN, "PILFER_GUARD_NO_CLASS", null)
            return
        }
        val methods = clazz.declaredMethods
            .filter { it.name == "pilferPointers" && it.returnType == Void.TYPE }
        if (methods.isEmpty()) {
            logger(Log.WARN, "PILFER_GUARD_NO_METHODS", null)
            return
        }
        methods.forEachIndexed { i, m ->
            module.hook(m)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("bubbledrawer.pilfer_guard.$i")
                .intercept { chain ->
                    val monitor = chain.thisObject
                    val name = monitorName(monitor)
                    // EVERY call is logged now (pilfer is rare): strokes died at
                    // 6–22 ms with NO call logged at all, so either the steal
                    // uses the token API (hooked below) or the back monitor lives
                    // in another process — the log decides.
                    val stack = Thread.currentThread().stackTrace
                        .drop(2).take(6).joinToString("<-") {
                            it.className.substringAfterLast('.') + "." + it.methodName
                        }
                    logger(Log.INFO, "PILFER_ANY name=$name active=" + isStreamActive() + " at=$stack", null)
                    if (isStreamActive() && isBackName(name)) {
                        logger(Log.INFO, "PILFER_BLOCKED name=$name", null)
                        pendingHandoff = monitor
                        null // swallow: the drawer gets to decide the stroke first
                    } else {
                        chain.proceed()
                    }
                }
            logger(Log.INFO, "PILFER_GUARD_HOOK_" + m.name + "_" + m.parameterCount, null)
        }
    }

    /**
     * Read-only instrumentation of the OTHER pilfer path:
     * InputManagerGlobal.pilferPointers(IBinder) — the same API our own claim
     * uses (CornerInputMonitor.pilfer). Strokes die with ACTION_CANCEL 6–22 ms
     * after DOWN while zero InputMonitor.pilferPointers calls are logged, so if
     * THIS hook fires during a dying stroke with a token that is not one of our
     * spy windows', that caller is the real steal we must gate.
     */
    fun hookTokenPilferObserver(ourTokens: () -> List<android.os.IBinder>) {
        val clazz = runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal")
        }.getOrNull() ?: return
        val m = clazz.declaredMethods.firstOrNull {
            it.name == "pilferPointers" && it.parameterCount == 1 &&
                it.parameterTypes[0] == android.os.IBinder::class.java
        } ?: return
        m.isAccessible = true
        module.hook(m)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("bubbledrawer.token_pilfer_observer")
            .intercept { chain ->
                val token = chain.getArg(0) as? android.os.IBinder
                val mine = ourTokens().any { it === token }
                logger(Log.INFO, "TOKEN_PILFER own=$mine active=" + isStreamActive(), null)
                chain.proceed() // observe only — never change behavior here yet
            }
        logger(Log.INFO, "TOKEN_PILFER_OBSERVER_HOOKED", null)
    }

    /** Log every gesture-monitor created IN THIS PROCESS with its name, so the
     *  back band's owning channel is identified (and any unexpected extra
     *  contender — e.g. an AntiMistake/SideGesture monitor — reveals itself). */
    fun hookMonitorCreator() {
        val clazz = runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal")
        }.getOrNull() ?: return
        clazz.declaredMethods.filter { it.name == "monitorGestureInput" }
            .forEachIndexed { i, m ->
                m.isAccessible = true
                module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("bubbledrawer.monitor_creator." + i)
                    .intercept { chain ->
                        val name = chain.getArgs().firstOrNull() as? String
                        val stack = Thread.currentThread().stackTrace
                            .drop(2).take(4).joinToString("<-") {
                                it.className.substringAfterLast('.') + "." + it.methodName
                            }
                        logger(Log.INFO, "MONITOR_CREATED name=$name args=" +
                            chain.getArgs().joinToString(",") {
                                if (it is String) it else it.toString()
                            } + " at=$stack", null)
                        chain.proceed()
                    }
            }
    }

    /**
     * Hand the stream to a native back monitor we blocked, called when the corner
     * stroke turned out NOT to be a drawer gesture. Idempotent; safe to call from
     * any end path (UP / CANCEL / second finger / engine cancel).
     */
    fun handOffToNative() {
        val monitor = pendingHandoff ?: return
        pendingHandoff = null
        try {
            val clazz = inputMonitorClass ?: return
            clazz.getMethod("pilferPointers").invoke(monitor)
            logger(Log.INFO, "PILFER_HANDED_BACK", null)
        } catch (t: Throwable) {
            logger(Log.WARN, "PILFER_HANDBACK_FAILED", t)
        }
    }

    /** a claimed stroke owns the stream outright — never hand it to native back. */
    fun discardHandoff() {
        if (pendingHandoff != null) {
            pendingHandoff = null
            logger(Log.INFO, "PILFER_HANDOFF_DISCARDED", null)
        }
    }

    private fun isBackName(name: String?): Boolean {
        if (name == null) return false
        val lower = name.lowercase()
        return lower.contains("edge-swipe") || lower.contains("edgeback") ||
            lower.contains("back")
    }

    private var cachedGetName: java.lang.reflect.Method? = null

    private fun monitorName(monitor: Any?): String? {
        val clazz = inputMonitorClass ?: return null
        if (monitor == null || !clazz.isInstance(monitor)) return null
        return try {
            var m = cachedGetName
            if (m == null) {
                m = clazz.getMethod("getName")
                cachedGetName = m
            }
            m.invoke(monitor) as? String
        } catch (t: Throwable) {
            logger(Log.WARN, "PILFER_GUARD_NAME_UNAVAILABLE", t)
            null
        }
    }

    /**
     * Commit-side suppression, only ever armed for strokes the drawer CLAIMED
     * (CornerInputMonitor.isCornerCommitWindow). HyperOS decides BACK from its
     * own state machine — EdgeBackGestureHandler.java:245-254 triggerBack,
     * :337-341 injects KEYCODE_BACK itself when mBackAnimation==null — and does
     * not re-check whether its pilfer succeeded, so a claimed stroke would
     * otherwise launch a fan app AND go back.
     * Cancellations (setTriggerBack(false)) always proceed: they only clear
     * native state, and never suppressing them keeps the handler consistent.
     */
    private fun hookCommit(classLoader: ClassLoader) {
        try {
            val handler = runCatching {
                classLoader.loadClass(
                    "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler",
                )
            }.getOrNull() ?: return // vanilla AOSP layout differs; hand-off alone is enough
            // R8 strips the InnerClasses attribute on this HyperOS build —
            // handler.declaredClasses came back EMPTY on device (log:
            // BACK_COMMIT_GUARD_NO_TARGET, 07:06:42.400). The runtime names stay:
            // the BackCallback is EdgeBackGestureHandler$5 (decompiled
            // EdgeBackGestureHandler.java:222-223 "renamed from …$5", implements
            // NavigationEdgeBackPlugin.BackCallback with triggerBack/setTriggerBack
            // at :237/:245). Try the numbered candidates, keep declaredClasses as
            // a fallback for other ROMs, and always shape-verify before hooking.
            val candidates = ArrayList<Class<*>>()
            for (suffix in 1..9) {
                runCatching {
                    classLoader.loadClass(handler.name + "$" + suffix)
                }.getOrNull()?.let { candidates.add(it) }
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
}
