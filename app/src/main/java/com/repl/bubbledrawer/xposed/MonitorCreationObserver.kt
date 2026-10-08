package com.repl.bubbledrawer.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * Read-only observer of gesture-monitor creation INSIDE SystemUI.
 *
 * WHY: `dumpsys input` shows that in this very process two monitors created through the
 * same `InputManagerGlobal.monitorGestureInput` differ in their native input config —
 *
 *   [Gesture Monitor] MultiTaskSwitch      inputConfig=NOT_FOCUSABLE|TRUSTED_OVERLAY|SPY|DO_NOT_PILFER
 *   [Gesture Monitor] BubbleDrawer-corner  inputConfig=NOT_FOCUSABLE|TRUSTED_OVERLAY|SPY
 *
 * and that difference is exactly what decides who survives the launcher's bottom-area
 * take-over: during a held corner stroke the dispatcher shows
 * `[Gesture Monitor] swipe-up … pilferingPointerIds=0…01` while our monitor has been
 * dropped from the touch state entirely. DO_NOT_PILFER is therefore the thing worth
 * having, and finding out which call sets it means finding the class that creates
 * MultiTaskSwitch (not present in the decompiled SystemUI, so it ships in
 * system_ext/framework/Miui-WindowManager-Shell.jar).
 *
 * This observer logs the name and the call stack of every monitor creation in the
 * SystemUI process and then proceeds — no behaviour change, no state kept.
 */
class MonitorCreationObserver(
    private val module: XposedModule,
    private val logger: (Int, String, Throwable?) -> Unit,
) {
    private var installed = false

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        val clazz = runCatching { classLoader.loadClass(TARGET) }
            .recoverCatching { Class.forName(TARGET) }
            .getOrNull()
        if (clazz == null) {
            logger(Log.WARN, "MONITOR_OBSERVER_NO_TARGET", null)
            return
        }
        for (method in clazz.declaredMethods.filter { it.name == "monitorGestureInput" }) {
            hookCreate(method)
        }
    }

    private fun hookCreate(method: Method) {
        try {
            runCatching { method.isAccessible = true }
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("bubbledrawer.systemui.monitor_create")
                .intercept { chain ->
                    try {
                        logger(
                            Log.INFO,
                            "MONITOR_CREATE_OBSERVED args=${chain.args.joinToString(",")} " +
                                "at=${stack()}",
                            null,
                        )
                    } catch (t: Throwable) {
                        logger(Log.WARN, "MONITOR_CREATE_OBSERVE_FAILED", t)
                    }
                    chain.proceed()
                }
            logger(Log.INFO, "MONITOR_OBSERVER_HOOKED ${method.name}/${method.parameterCount}", null)
        } catch (t: Throwable) {
            logger(Log.WARN, "MONITOR_OBSERVER_HOOK_FAILED", t)
        }
    }

    private fun stack(): String = Thread.currentThread().stackTrace
        .drop(4)
        .take(10)
        .joinToString("<-") { it.className.substringAfterLast('.') + "." + it.methodName }

    private companion object {
        const val TARGET = "android.hardware.input.InputManagerGlobal"
    }
}
