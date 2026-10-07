package com.repl.bubbledrawer.xposed

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.hardware.input.InputManagerGlobal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.InputMonitor
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.repl.bubbledrawer.gesture.AdaptiveSpyGestureConfig
import com.repl.bubbledrawer.gesture.CornerGestureEngine
import com.repl.bubbledrawer.gesture.CornerTriggerRegion
import com.repl.bubbledrawer.gesture.SpyAction
import com.repl.bubbledrawer.gesture.SpyGestureConfig
import com.repl.bubbledrawer.gesture.SpyPhase
import com.repl.bubbledrawer.gesture.SpySide
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Corner-stroke capture INSIDE the SystemUI process, with TWO transports.
 *
 * ── A. PRIMARY: a real gesture monitor (`InputManagerGlobal.monitorGestureInput`
 *      + `InputEventReceiver` on the main looper) ────────────────────────────────
 *
 * This is the transport the MiuiBackGestureHook reference uses on this exact device.
 *
 * WHY IT REPLACES THE OLD SELF-MADE SPY VIEW (device log, 2026-10-08):
 * the module used to add its own window — type 2024 (NAVIGATION_BAR_PANEL),
 * `setTrustedOverlay()`, `inputFeatures |= INPUT_FEATURE_SPY` — and read the stroke
 * from `View.onTouchEvent`. Over the launcher that works, but with an APP in the
 * foreground the stroke died 6–22 ms after ACTION_DOWN:
 *
 *     SPY_RIGHT_DOWN side=RIGHT x=199.0 y=237.0 wh=299/299 inBox=true eligible=true
 *     SPY_RIGHT_SYSTEM_CANCEL tracking=true claimed=false                 (+9 ms)
 *
 * i.e. the input dispatcher dropped the spy window from the touch state long before
 * the diagonal claim thresholds (`inward ≥ max(1.75·slop, 14dp)`, `upward ≥
 * max(0.5·slop, 4dp)`) could be reached — which is exactly why the drawer only ever
 * opened over the launcher.
 *
 * Two hooks were previously built to chase that drop; BOTH were chasing nothing on
 * this ROM and are gone with this change:
 *   • an ACTION_DOWN gate inside the native back plugin — `EdgeBackGestureHandler`
 *     has no motion handling left on this build (R8 moved it into the plugin), and
 *   • a guard on `android.view.InputMonitor.pilferPointers()` — never called during a
 *     stroke (zero PILFER_ANY lines in the module log). There is not even a
 *     `[Gesture Monitor] edge-swipe` window in `dumpsys input`, so SystemUI's AOSP
 *     edge-back monitor is simply not the arbiter here; MiuiHome's touchable
 *     `GestureStubView` side window plus MIUI input redirection is.
 *
 * HOW THE MONITOR FIXES IT: `InputManagerService.monitorGestureInput` on this build
 * ALWAYS builds a `GestureMonitorSpyWindow` whose `InputWindowHandle.inputConfig` is
 * `SPY | DO_NOT_PILFER` (device `services.jar` →
 * com.android.server.input.InputManagerService:646-672 +
 * GestureMonitorSpyWindow.java:35; see hiddenapi/.../InputManagerGlobal.java). A
 * gesture monitor therefore keeps receiving the whole stream even while another
 * window takes the pointers — precisely what "observe first, claim later" needs.
 * (`dumpsys input` confirms the pattern: SystemUI's own `[Gesture Monitor]
 * MultiTaskSwitch` carries `DO_NOT_PILFER`, while our old plain SPY window did not.)
 *
 * The monitor's touchable region is the whole display, so the DOWN gate is a
 * screen-space corner-box test ([sideFor]) instead of a window hit test. The engine
 * is unchanged: the stream is observed, and only claimed — with `pilferPointers()` —
 * once the diagonal thresholds are met, after which the fan receives the rest of the
 * stroke as ONE continuous sequence.
 *
 * ── B. FALLBACK: the original SPY view windows ────────────────────────────────
 *
 * Kept verbatim (FlymeFreeform reference hook/SystemUiCornerInputMonitor.kt :200-223
 * window params, :236-359 gesture view, :170-184 pilfer) for the case where the
 * monitor API is unavailable — no MONITOR_INPUT permission, a foreign ROM, or a
 * future MIUI change to `monitorGestureInput`. On the launcher this transport
 * already worked, so a failed monitor creation degrades instead of disabling the
 * feature. If the monitor is active the fallback windows are never added, so the two
 * can never fight over the same stroke.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class CornerInputMonitor(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val logger: (Int, String, Throwable?) -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)!!
    private val inputManager = context.getSystemService(InputManager::class.java)!!
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val getViewRootImplMethod: Method =
        View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }
    private val getInputTokenMethod: Method? = runCatching {
        Class.forName("android.view.ViewRootImpl").getMethod("getInputToken")
    }.getOrNull()
    private val pilferPointersMethod: Method =
        InputManager::class.java.getDeclaredMethod("pilferPointers", IBinder::class.java)

    private var settings = RemotePrefs.read(prefs)
    private var fan: FanHost? = null

    // ------------------------------------------------------------ transport A state

    private var monitor: InputMonitor? = null
    private var receiver: InputEventReceiver? = null
    private var stroke: Stroke? = null

    /** True while the gesture-monitor transport owns corner capture. */
    var monitorActive: Boolean = false
        private set

    // ------------------------------------------------------------ transport B state

    private val inputFeaturesField: Field =
        WindowManager.LayoutParams::class.java.getField("inputFeatures").apply { isAccessible = true }
    private val setTrustedOverlayMethod: Method =
        WindowManager.LayoutParams::class.java.getMethod("setTrustedOverlay").apply { isAccessible = true }

    private class Binding(val view: SpyCornerView, var size: Int)

    private val bindings = LinkedHashMap<SpySide, Binding>()

    // ------------------------------------------------------------ lifecycle

    fun start() {
        if (Looper.myLooper() == mainHandler.looper) applySettings() else mainHandler.post(::applySettings)
        prefs.registerOnSharedPreferenceChangeListener { _, _ ->
            if (Looper.myLooper() == mainHandler.looper) applySettings() else mainHandler.post(::applySettings)
        }
    }

    private fun applySettings() {
        settings = RemotePrefs.read(prefs)
        val wanted = settings.enabled && (settings.left || settings.right)
        if (wanted) startMonitorTransport() else stopMonitorTransport()
        // The SPY-view fallback only runs while the monitor transport is unavailable.
        updateSide(SpySide.LEFT, !monitorActive && wanted && settings.left)
        updateSide(SpySide.RIGHT, !monitorActive && wanted && settings.right)
        // change-only trace (fires on remote preference notifications)
        logger(
            Log.INFO,
            "PREFS_APPLIED enabled=" + settings.enabled + " left=" + settings.left +
                " right=" + settings.right + " range=" + settings.rangeDp +
                " transport=" + if (monitorActive) "monitor" else "spy-view",
            null,
        )
    }

    // ------------------------------------------------------------ transport A

    private fun startMonitorTransport() {
        if (monitorActive) return
        try {
            // Assign before anything else can throw: a monitor we created but failed to
            // wire must still be disposed, or its input channel leaks in system_server.
            val created = InputManagerGlobal.getInstance()
                .monitorGestureInput(MONITOR_NAME, DISPLAY_ID)
            monitor = created
            val channel = created.inputChannel
            receiver = object : InputEventReceiver(channel, Looper.getMainLooper()) {
                override fun onInputEvent(event: InputEvent) {
                    var handled = false
                    try {
                        handled = onMonitorEvent(event)
                    } catch (t: Throwable) {
                        logger(Log.WARN, "MON_EVENT_FAILED", t)
                        stroke?.cancel()
                    } finally {
                        // A monitor observes: it must never swallow what it did not claim.
                        finishInputEvent(event, handled)
                    }
                }
            }
            monitorActive = true
            logger(
                Log.INFO,
                "MON_INPUT_READY name=$MONITOR_NAME display=$DISPLAY_ID channel=${channel.token}",
                null,
            )
        } catch (t: Throwable) {
            monitorActive = false
            logger(Log.WARN, "MON_INPUT_UNAVAILABLE_FALLBACK_SPY_VIEW", t)
            disposeMonitor()
        }
    }

    private fun stopMonitorTransport() {
        if (!monitorActive && monitor == null) return
        stroke?.cancel()
        disposeMonitor()
        monitorActive = false
        logger(Log.INFO, "MON_INPUT_STOPPED", null)
    }

    private fun disposeMonitor() {
        stroke = null
        runCatching { receiver?.dispose() }
        runCatching { monitor?.dispose() }
        receiver = null
        monitor = null
    }

    /** One dispatcher event from the gesture monitor; returns whether WE claimed the stream. */
    private fun onMonitorEvent(event: InputEvent): Boolean {
        val ev = event as? MotionEvent ?: return false
        if (!ev.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            logger(Log.INFO, "MON_NON_TOUCHSOURCE src=${ev.source}", null)
            stroke?.cancel()
            return false
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stroke?.cancel() // defensive: never carry a stale stroke into a new DOWN
                beginMonitorStroke(ev)
            }

            MotionEvent.ACTION_MOVE -> {
                val s = stroke ?: return false
                if (!settings.enabled) {
                    s.cancel()
                    return false
                }
                val index = ev.findPointerIndex(s.pointerId)
                if (index < 0) {
                    s.cancel()
                    return false
                }
                s.move(ev, ev.getRawX(index), ev.getRawY(index))
            }

            MotionEvent.ACTION_POINTER_DOWN -> stroke?.cancel() // second finger = never the drawer

            MotionEvent.ACTION_UP -> stroke?.up(ev)

            MotionEvent.ACTION_CANCEL -> {
                logger(
                    Log.INFO,
                    "MON_SYSTEM_CANCEL tracking=${stroke?.tracking} claimed=${stroke?.claimed}",
                    null,
                )
                stroke?.cancel()
            }
        }
        return stroke?.claimed == true
    }

    private fun beginMonitorStroke(ev: MotionEvent) {
        if (ev.pointerCount != 1) return
        if (ev.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return
        if (!settings.enabled) return
        val x = ev.rawX
        val y = ev.rawY
        val side = sideFor(x, y) ?: return
        val config = AdaptiveSpyGestureConfig.create(
            displayWidth = screenW().toFloat(),
            displayHeight = screenH().toFloat(),
            touchSlop = touchSlop,
            density = density,
            triggerRangeDp = settings.rangeDp,
            leftEnabled = settings.left,
            rightEnabled = settings.right,
        )
        val s = Stroke({ claimed -> logger(Log.INFO, "MON_PILFER_OK=$claimed", null) })
        s.arm(ev.getPointerId(0), x, y, side, config)
        stroke = s
        logger(
            Log.INFO,
            "MON_DOWN side=$side x=$x y=$y box=${cornerBoxPx()} eligible=true",
            null,
        )
    }

    /**
     * Screen-space corner gate — the same RECT the SPY view used (`inBox`), not the
     * reference's quarter disc: on this panel the digitizer never delivers the
     * mathematical corner, so a disc centred exactly on it rejects every reachable
     * finger touch (FlymeFreeform commentary, CornerGestureEngine :104-112).
     */
    private fun sideFor(x: Float, y: Float): SpySide? {
        val size = cornerBoxPx()
        if (y < screenH() - size) return null
        if (settings.left && x <= size) return SpySide.LEFT
        if (settings.right && x >= screenW() - size) return SpySide.RIGHT
        return null
    }

    private fun cornerBoxPx(): Float =
        (CornerTriggerRegion.radiusPx(settings.rangeDp, density) + 0.5f).toInt().toFloat()

    /** Only the monitor's channel is made the touch target; the app keeps everything else. */
    private fun pilferMonitor(): Boolean {
        val m = monitor ?: return false
        return try {
            m.pilferPointers()
            true
        } catch (t: Throwable) {
            logger(Log.WARN, "MON_PILFER_FAILED", t)
            false
        }
    }

    // ------------------------------------------------------------ transport B (fallback)

    private fun updateSide(side: SpySide, shouldExist: Boolean) {
        val existing = bindings[side]
        if (!shouldExist) {
            if (existing != null) removeBinding(side, existing)
            return
        }
        val size = cornerBoxPx().toInt().coerceAtLeast(1)
        if (existing == null) {
            addBinding(side, size)
        } else if (existing.size != size) {
            existing.size = size
            existing.view.rangePx = size.toFloat()
            runCatching { wm.updateViewLayout(existing.view, createLayoutParams(side, size)) }
                .onFailure { removeBinding(side, existing) }
        }
    }

    private fun addBinding(side: SpySide, size: Int) {
        if (bindings.containsKey(side)) return
        val v = SpyCornerView(
            ctx = context,
            side = side,
            rangePx = size.toFloat(),
            touchSlopPx = touchSlop,
            canClaim = { settings.enabled && if (side == SpySide.LEFT) settings.left else settings.right },
            pilfer = ::pilferView,
            onActivate = { activated, screenX, screenY -> openFan(activated, screenX, screenY) },
            onStream = { screenEv -> forwardToFan(screenEv) },
            onStrokeEnd = { claimed -> if (claimed) retractFan() },
            trace = { message -> logger(Log.INFO, "SPY_${side.name}_$message", null) },
        )
        try {
            wm.addView(v, createLayoutParams(side, size))
            bindings[side] = Binding(v, size)
            logger(Log.INFO, "SPY_WINDOW_ADDED_${side.name}", null)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SPY_WINDOW_ADD_FAILED_${side.name}", exception)
        }
    }

    private fun removeBinding(side: SpySide, binding: Binding) {
        bindings.remove(side)
        binding.view.resetTracking()
        runCatching { wm.removeViewImmediate(binding.view) }
    }

    // ------------------------------------------------------------ shared stroke logic

    /**
     * One observed stroke. `CornerGestureEngine` arms on DOWN (nothing is stolen), and
     * `Activate` on a MOVE is the ONLY moment the stream is pilfered — after that every
     * event is forwarded to the fan as one continuous sequence, so the release always
     * arrives (reference CornerGestureEngine :113-147).
     *
     * `pilfer` reports it back to the caller for tracing; a claimed stroke always ends
     * through [retractFan] so the panel can never be left hanging (the reference's
     * two-window hand-off lost exactly that).
     */
    private inner class Stroke(private val pilfered: (Boolean) -> Unit) {
        private val engine = CornerGestureEngine()
        private var config: SpyGestureConfig? = null
        private var pilferAttempted = false

        var side: SpySide = SpySide.RIGHT
            private set
        var pointerId: Int = -1
            private set
        var tracking = false
            private set
        var claimed = false
            private set

        /** uptime of the last CLAIMED stroke end (0 = never) — read by [isCornerCommitWindow]. */
        var lastClaimedEndUptime: Long = 0L
            private set

        fun arm(newPointerId: Int, x: Float, y: Float, newSide: SpySide, newConfig: SpyGestureConfig) {
            pointerId = newPointerId
            side = newSide
            config = newConfig
            claimed = false
            pilferAttempted = false
            engine.down(newPointerId, x, y, newSide)
            tracking = engine.phase == SpyPhase.ARMED
        }

        fun move(raw: MotionEvent, x: Float, y: Float) {
            if (!tracking) return
            val cfg = config ?: return
            when (val action = engine.move(pointerId, raw.pointerCount, x, y, cfg)) {
                is SpyAction.Activate -> {
                    if (!pilferAttempted) {
                        pilferAttempted = true
                        pilfered(pilferMonitor())
                    }
                    claimed = true
                    logger(Log.INFO, "MON_ACTIVATE side=${action.side.name} x=$x y=$y", null)
                    openFan(action.side, x, y)
                }

                is SpyAction.Update -> if (claimed) forwardToFan(raw)

                SpyAction.Cancel -> {
                    logger(Log.INFO, "MON_ENGINE_CANCEL claimed=$claimed", null)
                    end(retract = claimed)
                }

                else -> Unit // PassThrough — still observing, the app keeps its stream
            }
        }

        fun up(raw: MotionEvent) {
            if (!tracking) return
            if (claimed) {
                // The release goes to the fan so GestureAppLauncher decides launch /
                // stay-open / collapse (m9729p :531-556), exactly like the original
                // forwarding window. NEVER force-retract here: the fan owns this decision.
                forwardToFan(raw)
            }
            engine.up(pointerId)
            end(retract = false)
        }

        fun cancel() {
            if (!tracking) return
            val wasClaimed = claimed
            engine.cancel()
            end(retract = wasClaimed)
        }

        private fun end(retract: Boolean) {
            val wasClaimed = claimed
            tracking = false
            claimed = false
            pilferAttempted = false
            pointerId = -1
            config = null
            if (wasClaimed) lastClaimedEndUptime = SystemClock.uptimeMillis()
            if (retract) retractFan()
        }
    }

    private fun openFan(side: SpySide, x: Float, y: Float) {
        mainHandler.post { ensureFan().show(side, x, y, screenW(), screenH()) }
    }

    private fun retractFan() {
        mainHandler.post { fan?.cancelExternal() }
    }

    /**
     * Hand one MOVE/UP to the fan. The event is copied and recycled around the fan call:
     * posting the receiver's own event, or a copy recycled before the posted lambda runs
     * (what the view path used to do), hands the launcher a recycled MotionEvent.
     */
    private fun forwardToFan(event: MotionEvent) {
        val copy = MotionEvent.obtain(event)
        mainHandler.post {
            try {
                fan?.forward(copy)
            } catch (t: Throwable) {
                logger(Log.WARN, "FAN_FORWARD_FAILED", t)
            } finally {
                copy.recycle()
            }
        }
    }

    /**
     * True only for strokes WE claimed (or ended < [COMMIT_GRACE_MS] ago): the native
     * handler decides BACK from its own state machine and does not re-check whether it
     * still owns the pointers, so a claimed corner stroke must suppress its late
     * commit. An UNCLAIMED corner stroke — a plain upward back swipe — never marks
     * this and commits BACK exactly as before.
     */
    fun isCornerCommitWindow(): Boolean {
        val s = stroke
        if (s != null && ((s.tracking && s.claimed) || withinGrace(s.lastClaimedEndUptime))) return true
        return bindings.values.any { b ->
            val v = b.view
            (v.streamActive && v.claimActive) || withinGrace(v.lastClaimedEndUptime)
        }
    }

    private fun withinGrace(uptime: Long): Boolean =
        uptime != 0L && SystemClock.uptimeMillis() - uptime <= COMMIT_GRACE_MS

    /** SystemUiCornerInputMonitor.pilferPointers (:170-184) verbatim flow (fallback transport). */
    private fun pilferView(view: View): Boolean = try {
        val viewRoot = getViewRootImplMethod.invoke(view) ?: return false
        val getToken = getInputTokenMethod ?: return false
        val inputToken = getToken.invoke(viewRoot) as? IBinder ?: return false
        pilferPointersMethod.invoke(inputManager, inputToken)
        true
    } catch (exception: Throwable) {
        logger(Log.WARN, "SPY_PILFER_FAILED", exception)
        false
    }

    private fun screenW(): Int = wm.currentWindowMetrics.bounds.width()
    private fun screenH(): Int = wm.currentWindowMetrics.bounds.height()

    private fun ensureFan(): FanHost {
        var f = fan
        if (f == null) {
            f = FanHost(context, prefs, logger)
            fan = f
        }
        return f
    }

    /** createLayoutParams (:200-223) — edge-hugging corner box, trusted SPY (fallback transport). */
    private fun createLayoutParams(side: SpySide, size: Int) =
        WindowManager.LayoutParams(
            size, size,
            CORNER_WINDOW_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSPARENT,
        ).apply {
            gravity = Gravity.BOTTOM or if (side == SpySide.LEFT) Gravity.LEFT else Gravity.RIGHT
            title = "$CORNER_INPUT_CHANNEL_TITLE-${side.name}"
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            setTrustedOverlayMethod.invoke(this)
            inputFeaturesField.setInt(this, inputFeaturesField.getInt(this) or INPUT_FEATURE_SPY)
        }

    /**
     * Port of CornerGestureView (:236-359). The fallback transport: the engine runs in
     * the VIEW's own coordinate space (reference :280-281/289-290 pass `width`/`height`
     * — the box == the radius square at the corner), so events forwarded to the fan are
     * remapped to screen coords with getLocationOnScreen.
     */
    @SuppressLint("ClickableViewAccessibility")
    class SpyCornerView(
        ctx: Context,
        private val side: SpySide,
        var rangePx: Float,
        private val touchSlopPx: Float,
        private val canClaim: () -> Boolean,
        private val pilfer: (View) -> Boolean,
        private val onActivate: (side: SpySide, screenX: Float, screenY: Float) -> Unit,
        private val onStream: (MotionEvent) -> Unit,
        private val onStrokeEnd: (claimed: Boolean) -> Unit,
        private val trace: (String) -> Unit,
    ) : View(ctx) {
        private val engine = CornerGestureEngine()
        private var activeConfig: SpyGestureConfig? = null
        private var activePointerId = -1
        private var tracking = false
        private var claimed = false
        private var pilferAttempted = false
        private val loc = IntArray(2)

        val streamActive: Boolean get() = tracking

        /** current claim state for the commit guard (true while a claimed stroke is mid-flight) */
        val claimActive: Boolean get() = claimed

        /** uptime ms of the moment a CLAIMED stroke ended (0 = never); see [COMMIT_GRACE_MS]. */
        var lastClaimedEndUptime: Long = 0L
            private set

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setWillNotDraw(true)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                trace("NON_TOUCHSOURCE src=${event.source}")
                val wasActive = tracking
                val wasClaimed = claimed
                resetTracking()
                if (wasActive) {
                    if (wasClaimed) markClaimedEnd()
                    onStrokeEnd(wasClaimed)
                }
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resetTracking()
                    // Gate shape: the FULL corner box — flyme original semantics, NOT the
                    // reference's quarter disc (same decision and rationale as the monitor
                    // transport's sideFor).
                    val inBox = event.x >= 0f && event.x <= width &&
                        event.y >= 0f && event.y <= height
                    val eligible = event.pointerCount == 1 &&
                        event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER &&
                        canClaim() && inBox
                    trace("DOWN side=$side x=${event.x} y=${event.y} wh=$width/$height r=$rangePx inBox=$inBox eligible=$eligible")
                    if (eligible) {
                        val config = AdaptiveSpyGestureConfig.create(
                            displayWidth = width.toFloat(),
                            displayHeight = height.toFloat(),
                            touchSlop = touchSlopPx,
                            density = resources.displayMetrics.density,
                            triggerRangeDp = (rangePx / resources.displayMetrics.density)
                                .toInt().coerceIn(24, 160),
                            leftEnabled = side == SpySide.LEFT,
                            rightEnabled = side == SpySide.RIGHT,
                        )
                        activePointerId = event.getPointerId(0)
                        engine.down(activePointerId, event.x, event.y, side)
                        if (engine.phase == SpyPhase.ARMED) {
                            activeConfig = config
                            tracking = true
                        } else {
                            trace("DOWN_NOT_ARMED phase=${engine.phase}")
                        }
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (tracking) {
                        if (!canClaim()) {
                            val wasClaimed = claimed
                            resetTracking()
                            if (wasClaimed) onStrokeEnd(true)
                            return true
                        }
                        val config = activeConfig
                        val index = event.findPointerIndex(activePointerId)
                        val action = if (config != null && index >= 0) {
                            engine.move(
                                activePointerId, event.pointerCount,
                                event.getX(index), event.getY(index), config,
                            )
                        } else {
                            engine.cancel()
                        }
                        when (action) {
                            is SpyAction.Activate -> {
                                if (!pilferAttempted) {
                                    pilferAttempted = true
                                    trace("PILFER_OK=${pilfer(this)}") // reference :327-330
                                }
                                claimed = true
                                val s = toScreen(event.getX(index), event.getY(index))
                                trace("ACTIVATE screen=(${s[0]},${s[1]})")
                                onActivate(action.side, s[0], s[1])
                            }
                            is SpyAction.Update -> if (claimed) forwardToFan(event)
                            SpyAction.Cancel -> {
                                val wasClaimed = claimed
                                trace("ENGINE_CANCEL claimed=$wasClaimed")
                                resetTracking()
                                if (wasClaimed) {
                                    markClaimedEnd()
                                    onStrokeEnd(true)
                                }
                            }
                            else -> Unit // PassThrough — still observing, app keeps its stream
                        }
                    }
                }

                MotionEvent.ACTION_POINTER_DOWN -> if (tracking) {
                    val wasClaimed = claimed
                    engine.cancel()
                    resetTracking()
                    if (wasClaimed) {
                        markClaimedEnd()
                        onStrokeEnd(true)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    val wasTracking = tracking
                    val wasClaimed = claimed
                    if (wasTracking) {
                        if (wasClaimed) {
                            // Release goes to the fan FIRST so GestureAppLauncher
                            // decides launch / stay-open / collapse (m9729p :531-556)
                            // — exactly like the original forwarding window.
                            forwardToFan(event)
                        }
                        engine.up(activePointerId)
                    }
                    resetTracking()
                    // unclaimed UP = the app's own gesture ended — nothing to tell the fan
                    if (wasClaimed) {
                        markClaimedEnd()
                        onStrokeEnd(false)
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    trace("SYSTEM_CANCEL tracking=$tracking claimed=$claimed")
                    if (tracking) {
                        val wasClaimed = claimed
                        engine.cancel()
                        resetTracking()
                        if (wasClaimed) {
                            markClaimedEnd()
                            onStrokeEnd(true)
                        }
                    }
                }
            }
            // A SPY window is never a target — returning true only keeps our
            // monitor channel alive (reference :349 does the same).
            return true
        }

        private fun forwardToFan(event: MotionEvent) {
            val copy = MotionEvent.obtain(event)
            val l = toScreen(0f, 0f)
            copy.offsetLocation(l[0], l[1]) // box-local → screen coords
            onStream(copy)
            copy.recycle()
        }

        private fun toScreen(x: Float, y: Float): FloatArray {
            getLocationOnScreen(loc)
            return floatArrayOf(x + loc[0], y + loc[1])
        }

        fun resetTracking() {
            engine.cancel()
            activeConfig = null
            activePointerId = -1
            tracking = false
            claimed = false
            pilferAttempted = false
        }

        private fun markClaimedEnd() {
            lastClaimedEndUptime = SystemClock.uptimeMillis()
        }
    }

    companion object {
        const val INPUT_FEATURE_SPY = 1 shl 2          // reference :363
        const val CORNER_WINDOW_TYPE = 2024            // reference :364 (TYPE_ACCESSIBILITY_OVERLAY)
        const val CORNER_INPUT_CHANNEL_TITLE = "BubbleDrawer-corner-spy" // reference :365

        /** Gesture-monitor channel name — appears in `dumpsys input` as
         *  "[Gesture Monitor] BubbleDrawer-corner". */
        const val MONITOR_NAME = "BubbleDrawer-corner"
        const val DISPLAY_ID = 0

        /** Grace after a corner stroke ends during which the native back handler's late
         *  commit attempt for the SAME stroke is still suppressed (main-looper dispatch
         *  ordering; the observed steal was 6–22 ms). */
        const val COMMIT_GRACE_MS = 300L
    }
}
