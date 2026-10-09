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

    // Active lightweight freeform task state
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

    val hoverRunnable = Runnable {
        hoverScheduled = false
        gestureHoverArmed = true
        // Cancel module takeover -> window becomes standard native freeform window!
        isTakeover = false
        hideDim()
        triggerHapticFeedback()
        log("SWIPE_UP_HOLD_TRIGGERED_NATIVE_FREEFORM taskId=$activeTaskId")
    }

    var pilferCallback: (() -> Boolean)? = null
    fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    init {
        instance = this
        currentModule = module
        savedContext = context
        backgroundDecoration = FreeformBackgroundDecoration(context, classLoader, prefs) {
            val snap = RemotePrefs.read(prefs)
            if (snap.flymeFreeformOutsideDismiss && isTakeover) {
                val taskId = activeTaskId
                val taskInfo = activeTaskInfo
                if (taskId != null && taskInfo != null) {
                    log("OUTSIDE_SURFACE_TAP_DISMISS taskId=$taskId")
                    closeTask(taskInfo, taskId)
                    hideDim()
                    isTakeover = false
                }
            }
        }
    }

    fun start() {
        log("FLYME_FREEFORM_CONTROLLER_STARTED")
    }

    fun dispose() {
        if (hoverScheduled) {
            handler.removeCallbacks(hoverRunnable)
            hoverScheduled = false
        }
        hideDim()
        activeTaskId = null
        activeTaskInfo = null
        isTakeover = false
        if (instance === this) {
            instance = null
        }
        log("FLYME_FREEFORM_CONTROLLER_DISPOSED")
    }

    // -------------------------------------------------------------------------
    // Outside-tap dismiss & Dimming mask
    // -------------------------------------------------------------------------

    fun onTaskAppeared(taskInfo: Any) {
        val snap = RemotePrefs.read(prefs)
        if (!snap.flymeFreeformEnabled) return
        val taskId = getTaskId(taskInfo)
        if (taskId <= 0) return

        activeTaskId = taskId
        activeTaskInfo = taskInfo
        isTakeover = true
        gestureHoverArmed = false
        if (hoverScheduled) {
            handler.removeCallbacks(hoverRunnable)
            hoverScheduled = false
        }
        log("TASK_APPEARED taskId=$taskId bounds=${getVisualBounds(taskInfo)}")

        handler.post {
            if (snap.flymeFreeformDimBg || snap.flymeFreeformOutsideDismiss) {
                showDim(taskInfo)
            }
        }
        handler.postDelayed({
            if (isTakeover && activeTaskId == taskId) {
                if (snap.flymeFreeformDimBg || snap.flymeFreeformOutsideDismiss) {
                    showDim(taskInfo)
                }
            }
        }, 200L)
    }

    fun onTaskVanished(taskId: Int) {
        if (activeTaskId == taskId) {
            log("TASK_VANISHED taskId=$taskId")
            activeTaskId = null
            activeTaskInfo = null
            isTakeover = false
            hideDim()
            backgroundDecoration?.release()
        }
    }

    fun onTaskModeChanged(taskInfo: Any, oldMode: Int, newMode: Int) {
        val taskId = getTaskId(taskInfo)
        if (taskId <= 0) return

        if (newMode == 0) {
            val snap = RemotePrefs.read(prefs)
            if (snap.flymeFreeformEnabled) {
                log("TASK_RESTORED_TO_FREEFORM taskId=$taskId oldMode=$oldMode -> RETAKE TAKEOVER")
                activeTaskId = taskId
                activeTaskInfo = taskInfo
                isTakeover = true
                gestureHoverArmed = false
                if (hoverScheduled) {
                    handler.removeCallbacks(hoverRunnable)
                    hoverScheduled = false
                }
                handler.post {
                    if (snap.flymeFreeformDimBg || snap.flymeFreeformOutsideDismiss) {
                        showDim(taskInfo)
                    }
                }
                handler.postDelayed({
                    if (isTakeover && activeTaskId == taskId) {
                        if (snap.flymeFreeformDimBg || snap.flymeFreeformOutsideDismiss) {
                            showDim(taskInfo)
                        }
                    }
                }, 350L)
            }
        } else if (activeTaskId == taskId) {
            log("TASK_MODE_CHANGED taskId=$taskId oldMode=$oldMode newMode=$newMode -> EXIT TAKEOVER")
            isTakeover = false
            hideDim()
        }
    }

    // -------------------------------------------------------------------------
    // Bottom Caption Gestures (Action delegates)
    // -------------------------------------------------------------------------

    fun onBottomCaptionDown(y: Float) {
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
                isTakeover = false
                hideDim()
            }
        }

        if (dy < -50f && snap.flymeFreeformSwipeUpMini) {
            log("ACTION_UP_SWIPE_MINI taskId=$activeTaskId dy=$dy")
            return try {
                sForceMiniTransition = true
                proceed()
            } finally {
                sForceMiniTransition = false
                isTakeover = false
                hideDim()
            }
        }

        if (dy > 80f && snap.flymeFreeformSwipeDownFull) {
            log("ACTION_UP_FULLSCREEN taskId=$activeTaskId dy=$dy")
            return try {
                sForceFullscreenTransition = true
                proceed()
            } finally {
                sForceFullscreenTransition = false
                isTakeover = false
                hideDim()
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

    fun showDim(taskInfo: Any) {
        val snap = RemotePrefs.read(prefs)
        if (!snap.flymeFreeformDimBg && !snap.flymeFreeformOutsideDismiss) {
            hideDim()
            return
        }

        val organizer = getRootTaskDisplayAreaOrganizer()
        val leash = getTaskLeash(taskInfo)
        if (organizer == null || leash == null) {
            log("SHOW_DIM_DEFERRED organizer=${organizer != null} leash=${leash != null}")
            return
        }

        val blurRadius = 0
        val dimAlpha = if (snap.flymeFreeformDimBg) 0.35f else 0.0f
        val allowOutsideDismiss = snap.flymeFreeformOutsideDismiss

        log("SHOW_DIM_SURFACE blur=0 alpha=$dimAlpha outsideDismiss=$allowOutsideDismiss")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            backgroundDecoration?.show(organizer, leash, blurRadius, dimAlpha, allowOutsideDismiss)
        } else {
            handler.post {
                backgroundDecoration?.show(organizer, leash, blurRadius, dimAlpha, allowOutsideDismiss)
            }
        }
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
                log("BACKGROUND_SURFACE_CREATED")
            }

            val sc = surfaceControl ?: return
            runCatching {
                runCatching {
                    transaction.javaClass.getMethod("setTrustedOverlay", SurfaceControl::class.java, Boolean::class.javaPrimitiveType)
                        .invoke(transaction, sc, true)
                }
                runCatching {
                    transaction.javaClass.getMethod("setRelativeLayer", SurfaceControl::class.java, SurfaceControl::class.java, Int::class.javaPrimitiveType)
                        .invoke(transaction, sc, leash, -1)
                }
                runCatching {
                    transaction.javaClass.getMethod("setBackgroundBlurRadius", SurfaceControl::class.java, Int::class.javaPrimitiveType)
                        .invoke(transaction, sc, 0)
                }
                runCatching {
                    transaction.javaClass.getMethod("setColor", SurfaceControl::class.java, FloatArray::class.java)
                        .invoke(transaction, sc, floatArrayOf(0f, 0f, 0f))
                }
                runCatching {
                    transaction.javaClass.getMethod("setAlpha", SurfaceControl::class.java, Float::class.javaPrimitiveType)
                        .invoke(transaction, sc, dimAlpha)
                }
                runCatching {
                    transaction.javaClass.getMethod("show", SurfaceControl::class.java).invoke(transaction, sc)
                }
                transaction.apply()
                log("BACKGROUND_SURFACE_SHOWN dimAlpha=$dimAlpha")
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
            runCatching {
                runCatching {
                    transaction.javaClass.getMethod("hide", SurfaceControl::class.java).invoke(transaction, sc)
                }
                transaction.apply()
                log("BACKGROUND_SURFACE_HIDDEN")
            }
        }

        fun release() {
            lastTapUpTime = 0L
            val sc = surfaceControl ?: return
            unregisterInputReceiver(sc)
            runCatching {
                runCatching {
                    transaction.javaClass.getMethod("remove", SurfaceControl::class.java).invoke(transaction, sc)
                }
                transaction.apply()
                surfaceControl = null
                log("BACKGROUND_SURFACE_RELEASED")
            }
        }

        private fun registerInputReceiver(sc: SurfaceControl) {
            if (isReceiverRegistered) return
            runCatching {
                val tokenCls = classLoader.loadClass("android.window.InputTransferToken")
                val token = tokenCls.getConstructor().newInstance()
                val receiverInterface = classLoader.loadClass("android.view.SurfaceControlInputReceiver")

                val proxy = Proxy.newProxyInstance(classLoader, arrayOf(receiverInterface)) { _, method, args ->
                    if (method.name == "onInputEvent" && args != null && args.isNotEmpty()) {
                        val event = args[0] as? InputEvent
                        handleInputEvent(event)
                    } else if (method.name == "toString") {
                        "SurfaceControlInputReceiverProxy"
                    } else {
                        null
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
                isReceiverRegistered = false
                log("INPUT_RECEIVER_UNREGISTERED")
            }.onFailure {
                log("INPUT_RECEIVER_UNREGISTER_FAILED", it)
            }
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

        /**
         * Freeform state fan-out for consumers that are independent of the Flyme-style takeover
         * (today: the floating contact bar). Set by `SystemUiHookInstaller` right after this
         * controller is created, cleared on dispose/hot reload.
         */
        @Volatile var freeformObserver: com.repl.bubbledrawer.contactbar.FreeformObserver? = null
        @Volatile var currentModule: XposedModule? = null
        @Volatile var savedController: Any? = null
        @Volatile var savedExecutor: Executor? = null
        @Volatile var savedOrganizer: Any? = null
        @Volatile var savedContext: Context? = null
        @Volatile var sForceMiniTransition = false
        @Volatile var sForceFullscreenTransition = false
        @Volatile var sForceRebound = false

        fun log(msg: String, error: Throwable? = null) {
            if (error != null) {
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
            hookVelocityMonitor(module, classLoader)
            hookMiniBottomUpLimit(module, classLoader)
            hookTopCaptionMove(module, classLoader)
            hookBottomCaptionGestures(module, prefs, classLoader)
            hookResizeFocus(module, classLoader)
            hookGestureAnimation(module, classLoader)
        }

        /**
         * "This is the window the user is working on" — `MiuiFreeformModeResizeHandler.handleResize`
         * carries the `MiuiFreeformModeTaskInfo` on every resize event (actionMode 0 = down).
         *
         * Two consumers: the contact bar re-targets to that window (with two 小窗 open, the bar must
         * sit under the one being resized), and its per-frame tracking is armed for the drag.
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
                                if (snap.flymeFreeformEnabled) {
                                    (snap.flymeFreeformScale.coerceIn(50, 95)) / 100f
                                } else {
                                    chain.proceed()
                                }
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
                        val taskId = getTaskId(taskInfo)
                        if (taskId > 0 && (inst.activeTaskId != taskId || !inst.isTakeover)) {
                            inst.activeTaskId = taskId
                            inst.activeTaskInfo = taskInfo
                            inst.isTakeover = true
                        }
                    }

                    val y = (chain.args[1] as? Number)?.toFloat() ?: 0f
                    val downPoint = chain.args[2] as? PointF
                    val action = (chain.args[4] as? Number)?.toInt() ?: -1

                    val downY = downPoint?.y ?: (inst?.gestureDownY ?: y)
                    val dy = y - downY

                    when (action) {
                        MotionEvent.ACTION_DOWN -> {
                            inst?.onBottomCaptionDown(y)
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
