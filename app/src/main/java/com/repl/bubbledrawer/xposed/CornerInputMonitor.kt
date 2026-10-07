package com.repl.bubbledrawer.xposed

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
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
 * Trusted SPY corner capture INSIDE the SystemUI process — the exact mechanism of
 * the FlymeFreeform reference the user pointed at (hook/SystemUiCornerInputMonitor.kt:
 *   :200-223  window params  (type 2024, edge-hugging corner box, SPY bit, trusted overlay)
 *   :236-359  gesture view   (arm on DOWN, claim+pilfer on threshold MOVE, stream to UP)
 *   :170-184  pilfer          (ViewRootImpl.getInputToken → InputManager.pilferPointers)
 *   SystemUiHookInstaller.kt :18-40 install via SystemUIApplication.onCreate):
 *
 *  • a size=radius window flush with the PHYSICAL bottom corner (gravity
 *    BOTTOM|LEFT / BOTTOM|RIGHT, cutout ALWAYS, fitInsets 0) — zero inset distance,
 *    which is what "而不是一个离边角有一定距离的悬浮窗" demands;
 *  • SPY (inputFeatures |= 1 shl 2) + setTrustedOverlay(): SystemUI windows are
 *    trusted overlays, so a SPY window receives the WHOLE stroke without ever
 *    eating the app's DOWN — a plain edge back-swipe is untouched until we claim;
 *  • on claim (inward ≥ max(1.75·slop, 14dp) && upward ≥ max(0.5·slop, 4dp)) we
 *    pilferPointers() — the stream becomes OURS until the finger is up, so the
 *    fan receives DOWN→MOVE…→UP as ONE continuous sequence. The old retract bug
 *    came from two disconnected capture windows; with pilfer there is nothing to
 *    lose the UP to.
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

    private val inputFeaturesField: Field =
        WindowManager.LayoutParams::class.java.getField("inputFeatures").apply { isAccessible = true }
    private val setTrustedOverlayMethod: Method =
        WindowManager.LayoutParams::class.java.getMethod("setTrustedOverlay").apply { isAccessible = true }
    private val getViewRootImplMethod: Method =
        View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }
    private val getInputTokenMethod: Method? = runCatching {
        Class.forName("android.view.ViewRootImpl").getMethod("getInputToken")
    }.getOrNull()
    private val pilferPointersMethod: Method =
        InputManager::class.java.getDeclaredMethod("pilferPointers", IBinder::class.java)

    private class Binding(val view: SpyCornerView, var size: Int)

    private val bindings = LinkedHashMap<SpySide, Binding>()
    private var settings = RemotePrefs.read(prefs)
    private var fan: FanHost? = null

    fun start() {
        if (Looper.myLooper() == mainHandler.looper) applySettings() else mainHandler.post(::applySettings)
        prefs.registerOnSharedPreferenceChangeListener { _, _ ->
            if (Looper.myLooper() == mainHandler.looper) applySettings() else mainHandler.post(::applySettings)
        }
    }

    private fun applySettings() {
        settings = RemotePrefs.read(prefs)
        updateSide(SpySide.LEFT, settings.enabled && settings.left)
        updateSide(SpySide.RIGHT, settings.enabled && settings.right)
        // change-only trace (fires on remote preference notifications)
        logger(
            Log.INFO,
            "PREFS_APPLIED enabled=" + settings.enabled + " left=" + settings.left +
                " right=" + settings.right + " range=" + settings.rangeDp,
            null,
        )
    }

    private fun updateSide(side: SpySide, shouldExist: Boolean) {
        val existing = bindings[side]
        if (!shouldExist) {
            if (existing != null) removeBinding(side, existing)
            return
        }
        val size = (CornerTriggerRegion.radiusPx(settings.rangeDp, density) + 0.5f).toInt().coerceAtLeast(1)
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
            canClaim = { canClaim(side) },
            pilfer = ::pilfer,
            onActivate = { screenX, screenY ->
                mainHandler.post {
                    ensureFan().show(side, screenX, screenY, screenW(), screenH())
                }
            },
            onStream = { screenEv ->
                mainHandler.post { ensureFan().forward(screenEv) }
            },
            onStreamEnd = { cancelled ->
                mainHandler.post { if (cancelled) fan?.cancelExternal() }
            },
            onStrokeEnd = { claimed -> strokeEndSink?.invoke(claimed) },
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

    private fun canClaim(side: SpySide): Boolean =
        settings.enabled && when (side) {
            SpySide.LEFT -> settings.left
            SpySide.RIGHT -> settings.right
        }

    /**
     * Every corner stroke that had ARMED the drawer ends through this sink with
     * whether the drawer CLAIMED it. Wired by SystemUiHookInstaller to
     * PilferGuard: claimed → drop the pending native hand-off (the drawer owns
     * the stream; the commit guard suppresses the late native triggerBack);
     * unclaimed (plain vertical back swipe, straight-inward drag, 2nd finger) →
     * hand the stream to the back monitor whose pilfer we swallowed, so native
     * BACK keeps working everywhere the drawer did not take over.
     */
    var strokeEndSink: ((claimed: Boolean) -> Unit)? = null

    /** Screen-space membership for the back-gesture corner gate: a DOWN starting
     *  inside an ENABLED, laid-out spy box must not be registered by the native
     *  back plugin (it belongs to the drawer — flyme semantics). Fail open on
     *  any uncertainty (no binding, zero box, pre-layout location). */
    fun isInsideCornerBox(x: Float, y: Float): Boolean {
        for ((side, binding) in bindings) {
            if (!canClaim(side)) continue
            val view = binding.view
            if (view.width <= 0 || view.height <= 0) continue
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            if (loc[0] == 0 && loc[1] == 0) continue // never laid out
            if (x >= loc[0] && x <= loc[0] + view.width &&
                y >= loc[1] && y <= loc[1] + view.height
            ) {
                return true
            }
        }
        return false
    }

    /** True while ANY corner view is mid-stroke (armed or claimed). Used by
     *  PilferGuard to keep HyperOS's native edge-back monitor from stealing the
     *  stream we are already evaluating. Both our DOWN handler and the native
     *  pilfer run on the SystemUI main looper, and the DOWN trace precedes the
     *  CANCEL trace by 6–22 ms on device, so this flag is set before the steal. */
    fun isCornerStreamActive(): Boolean = bindings.values.any { it.view.streamActive }

    /** True only for strokes WE claimed for the fan (or ended <grace ago): the
     *  native monitor still sees the full stream through its own channel even
     *  when our guard blocked its pilfer, so it may try to commit BACK for a
     *  gesture the drawer already owns. An UNCLAIMED corner stroke — a plain
     *  upward swipe — never marks this and commits BACK exactly as before. */
    fun isCornerCommitWindow(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        return bindings.values.any {
            (it.view.streamActive && it.view.claimActive) ||
                (it.view.lastClaimedEndUptime != 0L &&
                    now - it.view.lastClaimedEndUptime <= COMMIT_GRACE_MS)
        }
    }

    /** SystemUiCornerInputMonitor.pilferPointers (:170-184) verbatim flow. */
    private fun pilfer(view: View): Boolean = try {
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

    /** createLayoutParams (:200-223) — edge-hugging corner box, trusted SPY. */
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
     * Port of CornerGestureView (:236-359). The engine runs in the VIEW's own
     * coordinate space — reference :280-281/289-290 pass `width`/`height` (the
     * box == the radius square at the corner), so x from the LEFT corner /
     * fromBottom measure the disc directly. Events forwarded to the fan are
     * remapped to screen coords with getLocationOnScreen (our fan canvas is the
     * full display; the reference kept its overlay in the same space instead).
     */
    @SuppressLint("ClickableViewAccessibility")
    class SpyCornerView(
        ctx: Context,
        private val side: SpySide,
        var rangePx: Float,
        private val touchSlopPx: Float,
        private val canClaim: () -> Boolean,
        private val pilfer: (View) -> Boolean,
        private val onActivate: (screenX: Float, screenY: Float) -> Unit,
        private val onStream: (MotionEvent) -> Unit,
        private val onStreamEnd: (cancelled: Boolean) -> Unit,
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

        /** uptime ms of the moment a CLAIMED stroke ended (0 = never). The native
         *  edge handler's UP for the same stroke may be dispatched slightly AFTER
         *  ours on the same looper; the commit guard keeps a short grace window so
         *  that late triggerBack() is suppressed too. UNCLAIMED strokes never mark
         *  this — a plain upward back swipe must still commit BACK normally (flyme
         *  arbitration: not diagonal = not the drawer's). */
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
                    if (wasClaimed) {
                        markClaimedEnd()
                        onStreamEnd(true)
                    }
                    onStrokeEnd(wasClaimed)
                }
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resetTracking()
                    // Gate shape: the FULL corner box — flyme original semantics
                    // (its slide trigger zone is a RECT, the 96×160dp zone ported
                    // in the old BubbleConfig.zones), NOT the reference's quarter
                    // disc: on this panel's large rounded corners the digitizer
                    // never delivers the mathematical corner, so a disc centered
                    // exactly at it rejects every reachable finger touch.
                    // False fires are still prevented by the diagonal claim
                    // thresholds in engine.move (reference :101-108 unchanged).
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
                            if (wasClaimed) onStreamEnd(true)
                            onStrokeEnd(wasClaimed)
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
                                    val ok = pilfer(this) // reference :327-330
                                    trace("PILFER_OK=$ok")
                                }
                                claimed = true
                                val s = toScreen(event.getX(index), event.getY(index))
                                trace("ACTIVATE screen=(${s[0]},${s[1]})")
                                onActivate(s[0], s[1])
                            }
                            is SpyAction.Update -> if (claimed) forwardToFan(event)
                            SpyAction.Cancel -> {
                                val wasClaimed = claimed
                                trace("ENGINE_CANCEL claimed=$wasClaimed")
                                resetTracking()
                                if (wasClaimed) {
                                    markClaimedEnd()
                                    onStreamEnd(true)
                                }
                                // unclaimed here = vertical-dominant / pulled back /
                                // ambiguous: PilferGuard hands the stream to native.
                                onStrokeEnd(wasClaimed)
                            }
                            else -> Unit // PassThrough — still observing, app keeps its stream
                        }
                    }
                }

                MotionEvent.ACTION_POINTER_DOWN -> if (tracking) {
                    val wasClaimed = claimed
                    engine.cancel()
                    if (wasClaimed) {
                        resetTracking()
                        markClaimedEnd()
                        onStreamEnd(true)
                    }
                    // second finger = never the drawer: hand the stream back NOW
                    // (native decides on its own UP/CANCEL afterwards)
                    onStrokeEnd(wasClaimed)
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
                        onStreamEnd(false)
                    }
                    if (wasTracking) onStrokeEnd(wasClaimed)
                }

                MotionEvent.ACTION_CANCEL -> {
                    trace("SYSTEM_CANCEL tracking=$tracking claimed=$claimed")
                    if (tracking) {
                        val wasClaimed = claimed
                        engine.cancel()
                        resetTracking()
                        if (wasClaimed) {
                            markClaimedEnd()
                            onStreamEnd(true)
                        }
                        onStrokeEnd(wasClaimed)
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
            lastClaimedEndUptime = android.os.SystemClock.uptimeMillis()
        }
    }

    companion object {
        const val INPUT_FEATURE_SPY = 1 shl 2          // reference :363
        const val CORNER_WINDOW_TYPE = 2024            // reference :364 (TYPE_ACCESSIBILITY_OVERLAY)
        const val CORNER_INPUT_CHANNEL_TITLE = "BubbleDrawer-corner-spy" // reference :365

        /** grace after a corner stroke ends during which the native back handler's
         *  late commit attempt for the SAME stroke is still suppressed (main-looper
         *  dispatch ordering; measured steal was 6–22 ms). */
        const val COMMIT_GRACE_MS = 300L
    }
}
