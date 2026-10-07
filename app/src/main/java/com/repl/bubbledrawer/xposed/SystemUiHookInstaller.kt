package com.repl.bubbledrawer.xposed

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Port of the reference hook/SystemUiHookInstaller.kt :11-61: hook
 * `com.android.systemui.SystemUIApplication.onCreate`; after the real onCreate
 * proceeds, `chain.thisObject` IS the SystemUI Application (a Context, :28) —
 * start the corner SPY monitor with it, exactly once.
 */
class SystemUiHookInstaller(
    private val module: XposedModule,
    private val prefs: SharedPreferences,
) {
    private val bound = AtomicBoolean(false)
    private var inputMonitor: CornerInputMonitor? = null
    private var backFilter: BackGestureFilter? = null
    private var appClassLoader: ClassLoader? = null

    fun install(classLoader: ClassLoader) {
        // Keep the resolved classloader for the back-gesture corner gate below.
        appClassLoader = classLoader
        // AOSP SystemUI names its Application com.android.systemui.SystemUIApplication
        // (reference SystemUiHookInstaller.kt:59). HyperOS 17.03 does NOT have that
        // class at all — its manifest declares the application as
        // com.android.systemui.application.impl.SystemUIApplicationImpl
        // (decompiled out/SystemUI1703_src/resources/AndroidManifest.xml:356, class
        // extends Application, public final void onCreate() :65/:155). Try candidates.
        var installed = false
        for ((index, candidate) in APPLICATION_CLASS_CANDIDATES.withIndex()) {
            try {
                val applicationClass = classLoader.loadClass(candidate)
                val onCreate = applicationClass.getDeclaredMethod("onCreate")
                module
                    .hook(onCreate)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("bubbledrawer.systemui.corner_spy_owner.$index")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val context = chain.thisObject as? Context
                        if (context != null && bound.compareAndSet(false, true)) {
                            startInputMonitor(context)
                        }
                        result
                    }
                module.log(Log.INFO, TAG, "SYSTEMUI_CORNER_SPY_HOOK_INSTALLED_$candidate")
                installed = true
                break
            } catch (exception: ReflectiveOperationException) {
                module.log(Log.WARN, TAG, "SYSTEMUI_APPLICATION_TARGET_UNAVAILABLE_$candidate", exception)
            } catch (exception: LinkageError) {
                module.log(Log.WARN, TAG, "SYSTEMUI_APPLICATION_TARGET_LINKAGE_FAILED_$candidate", exception)
            }
        }
        if (!installed) module.log(Log.ERROR, TAG, "SYSTEMUI_NO_APPLICATION_HOOKABLE")
    }

    private fun startInputMonitor(context: Context) {
        try {
            val monitor = CornerInputMonitor(context, prefs) { priority, message, error ->
                module.log(priority, TAG, message, error)
            }
            inputMonitor = monitor
            monitor.start()
            module.log(Log.INFO, TAG, "SYSTEMUI_CORNER_SPY_READY")
            // back-gesture corner gate — SystemUI's own ClassLoader (install param)
            val cl = appClassLoader
            if (cl != null) {
                val filter = BackGestureFilter(module, cl, { x, y ->
                    monitor.isInsideCornerBox(x, y)
                }) { priority, message, error ->
                    module.log(priority, TAG, message, error)
                }
                filter.install()
                backFilter = filter
            }
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_API_UNAVAILABLE", exception)
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_START_FAILED", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_LINKAGE_FAILED", exception)
        }
    }

    private companion object {
        const val TAG = "BubbleDrawer"

        /** AOSP first (vanilla ROMs, reference target), HyperOS/MIUI 17 impl second. */
        val APPLICATION_CLASS_CANDIDATES = listOf(
            "com.android.systemui.SystemUIApplication",
            "com.android.systemui.application.impl.SystemUIApplicationImpl",
        )
    }
}
