package com.repl.bubbledrawer.xposed

import android.util.Log
import android.view.MotionEvent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app back is owned by HyperOS SystemUI's edge-swipe plugin:
 * InputMonitorCompat("edge-swipe", displayId) → DisplayBackGestureHandlerImpl
 * (DaggerReferenceGlobalRootComponent.java:32426-32433) → BackPanelController
 * .onMotionEvent (out sources …gestural/BackPanelController.java:222, JADX could
 * not decompile the body but the signature survives; R8 inlined the AOSP
 * EdgeBackGestureHandler.onMotion* into the plugin — the handler class itself has
 * no MotionEvent methods left). It pilfers FIRST for any side-edge stroke
 * (EdgeBackGestureHandler$$ExternalSyntheticLambda1.java:33) at its own small
 * slop — before our diagonal claim thresholds — which surfaced as
 * SPY_*_SYSTEM_CANCEL claimed=false inside apps (desktop works because MiuiHome
 * owns the edge band there, MiuiBackGestureHook SystemUiInputRuntime.java:1310).
 *
 * Arbitration per the user's cited reference (MiuiBackGestureHook: drawn overlay
 * regions must make the back gesture NOT trigger at all): gate the plugin's own
 * DOWN. A stroke that BEGINS inside an enabled corner box is never registered by
 * the plugin — no pilfer, no predictive panel, no BACK on release. Strokes
 * anywhere else keep native back byte-for-byte, and flyme has the same semantic:
 * the corner box belongs to the drawer, not to back.
 */
class BackGestureFilter(
    private val module: XposedModule,
    private val classLoader: ClassLoader,
    private val insideCornerBox: (x: Float, y: Float) -> Boolean,
    private val logger: (Int, String, Throwable?) -> Unit,
) {
    private val installed = AtomicBoolean(false)

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        try {
            val cls = classLoader.loadClass(
                "com.android.systemui.navigationbar.gestural.BackPanelController",
            )
            // Exactly one shape survives R8: void onMotionEvent(MotionEvent)
            // (public static too? no — instance; filter keeps instance methods
            // with the verbatim signature).
            val candidates: List<java.lang.reflect.Method> = cls.declaredMethods.filter { m: java.lang.reflect.Method ->
                !java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.returnType == Void.TYPE &&
                    m.parameterCount == 1 &&
                    m.parameterTypes[0] == MotionEvent::class.java
            }
            if (candidates.isEmpty()) {
                logger(Log.ERROR, "BACK_FILTER_NO_TARGET", null)
                installed.set(false)
                return
            }
            candidates.forEachIndexed { i, method ->
                logger(Log.INFO, "BACK_FILTER_HOOK_" + method.name, null)
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("bubbledrawer.back_gesture_corner_gate.$i")
                    .intercept { chain ->
                        val ev = chain.getArg(0) as? MotionEvent
                        if (ev != null && ev.actionMasked == MotionEvent.ACTION_DOWN) {
                            val inside = insideCornerBox(ev.rawX, ev.rawY)
                            logger(
                                Log.INFO,
                                "BACK_DOWN_SEEN(" + ev.rawX + "," + ev.rawY + ")inside=" + inside,
                                null,
                            )
                            if (inside) {
                                logger(Log.INFO, "BACK_SUPPRESSED_IN_CORNER", null)
                                null // skip proceed: the plugin never registers this stroke
                            } else {
                                chain.proceed()
                            }
                        } else {
                            chain.proceed()
                        }
                    }
            }
            logger(Log.INFO, "BACK_FILTER_INSTALLED_" + candidates.size, null)
        } catch (t: ReflectiveOperationException) {
            logger(Log.WARN, "BACK_FILTER_UNAVAILABLE", t)
            installed.set(false)
        } catch (t: LinkageError) {
            logger(Log.WARN, "BACK_FILTER_LINKAGE", t)
            installed.set(false)
        }
    }
}
