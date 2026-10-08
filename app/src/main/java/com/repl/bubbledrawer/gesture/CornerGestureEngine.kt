package com.repl.bubbledrawer.gesture

import kotlin.math.hypot
import kotlin.math.max

/**
 * Port of the FlymeFreeform reference corner engine (gesture/CornerGestureEngine.kt
 * :35-179 + CornerTriggerRegion.kt:6-32), which the user explicitly pointed at as
 * the model for "真正的左右下角内滑触发" — a quarter-circle at the PHYSICAL bottom
 * corner plus inward+upward thresholds, running on the trusted SPY window.
 *
 * Bottom corners only (the reference and the decompiled flyme original are bottom
 * corner triggers; the fan geometry pivots at the bottom edge).
 *
 * Pure Kotlin (no android.* types) → JVM-testable, like the reference tests.
 */
enum class SpySide { LEFT, RIGHT }

enum class SpyPhase { IDLE, ARMED, REVEALING, SELECTING, COMMITTING, CANCELLING }

/** CornerTriggerRegion.detectSide (:13-32) — quarter-disc at the true corner. */
object CornerTriggerRegion {
    fun radiusPx(rangeDp: Int, density: Float): Float {
        val safe = density.takeIf { it > 0f } ?: 1f
        return rangeDp.coerceIn(24, 160) * safe
    }

    fun detectSide(
        x: Float, y: Float, w: Float, h: Float, radius: Float,
        leftEnabled: Boolean, rightEnabled: Boolean,
    ): SpySide? {
        val fromBottom = h - y
        if (fromBottom < 0f || fromBottom > radius) return null
        if (leftEnabled && x >= 0f && hypot(x.toDouble(), fromBottom.toDouble()) <= radius) return SpySide.LEFT
        val fromRight = w - x
        if (rightEnabled && fromRight >= 0f && hypot(fromRight.toDouble(), fromBottom.toDouble()) <= radius) return SpySide.RIGHT
        return null
    }
}

/** CornerGestureConfig + AdaptiveCornerGestureConfig.create (:35-75). */
data class SpyGestureConfig(
    val displayWidth: Float,
    val displayHeight: Float,
    val triggerRadius: Float,
    val inwardThreshold: Float,
    val upwardThreshold: Float,
    val reverseTolerance: Float,
    val leftEnabled: Boolean,
    val rightEnabled: Boolean,
)

object AdaptiveSpyGestureConfig {
    // reference constants (:70-74)
    private const val INWARD_SLOP_MULTIPLIER = 1.75f
    private const val UPWARD_SLOP_MULTIPLIER = 0.50f

    /**
     * How far the finger must travel (radially, any direction — the corner is a complete
     * sector) before the stroke is ours.
     *
     * 21 dp = the LOWER edge of MiuiHome's measured 21–24 dp take-over band for the bottom
     * strip. Two constraints pull in opposite directions and this is where they meet:
     *  - higher = safer for taps: a stroke that never travels this far is served by the app
     *    itself, which is what removed the double input on DOWN-driven targets (the
     *    bottom-left keyboard key);
     *  - lower  = we must pilfer BEFORE MiuiHome commits HOME
     *    (`down_y - current_y > record_area_height_px`), otherwise the panel and HOME both
     *    happen. Claiming at the band's lower edge keeps us ahead of it.
     */
    private const val INWARD_DP = 21f
    private const val UPWARD_DP = 4f
    private const val REVERSE_TOLERANCE_DP = 8f

    fun create(
        displayWidth: Float, displayHeight: Float, touchSlop: Float, density: Float,
        triggerRangeDp: Int, leftEnabled: Boolean, rightEnabled: Boolean,
    ): SpyGestureConfig {
        val safeDensity = if (density > 0f) density else 1f
        val r = CornerTriggerRegion.radiusPx(triggerRangeDp, safeDensity)
        return SpyGestureConfig(
            displayWidth, displayHeight, r,
            inwardThreshold = max(touchSlop * INWARD_SLOP_MULTIPLIER, safeDensity * INWARD_DP),
            upwardThreshold = max(touchSlop * UPWARD_SLOP_MULTIPLIER, safeDensity * UPWARD_DP),
            reverseTolerance = max(touchSlop, safeDensity * REVERSE_TOLERANCE_DP),
            leftEnabled = leftEnabled,
            rightEnabled = rightEnabled,
        )
    }
}

sealed interface SpyAction {
    data object Ignore : SpyAction
    data object PassThrough : SpyAction
    data class Activate(val side: SpySide, val originX: Float, val originY: Float, val x: Float, val y: Float) : SpyAction
    data class Update(val side: SpySide, val x: Float, val y: Float) : SpyAction
    data object Commit : SpyAction
    data object Cancel : SpyAction
}

/**
 * CornerGestureEngine (:78-179). down() arms only (PassThrough — the stream is
 * not stolen yet); move() past the inward+upward thresholds CLAIMS the stream
 * (Activate → the spy view pilfers the real DOWN target's channel); after that
 * every move is Update; up() Commit / cancel() Cancel.
 */
class CornerGestureEngine {
    var phase: SpyPhase = SpyPhase.IDLE
        private set
    var isClaimed: Boolean = false
        private set

    private var pointerId = -1
    private var originX = 0f
    private var originY = 0f
    private var side: SpySide? = null

    /**
     * Arm only — the DOWN has already been gated to the corner BOX by the view.
     * Reference :92-100 gates on the quarter-disc; on large rounded-corner panels
     * the digitizer cannot deliver a touch at the mathematical corner, so we use
     * the FLYME ORIGINAL's own trigger shape instead: the slide zone is a RECT
     * (BubbleConfig.zones ported from the decompiled 96dp×160dp zone, edge mode
     * inset=0). False triggers stay guarded by the inward+upward claim thresholds
     * (:101-108) which the reference keeps unchanged.
     */
    fun down(pointerId: Int, x: Float, y: Float, side: SpySide): SpyAction {
        reset()
        this.pointerId = pointerId
        originX = x
        originY = y
        this.side = side
        phase = SpyPhase.ARMED
        return SpyAction.PassThrough
    }

    fun move(pointerId: Int, pointerCount: Int, x: Float, y: Float, config: SpyGestureConfig): SpyAction {
        val activeSide = side ?: return SpyAction.PassThrough
        if (pointerId != this.pointerId || pointerCount != 1) return cancel()
        val inward = if (activeSide == SpySide.LEFT) x - originX else originX - x
        val upward = originY - y
        // Only a RETREAT (the finger leaving the screen interior, back over the edge) cancels
        // before the claim. Everything else inside the corner sector counts, in ANY direction:
        // the previous vertical-dominance rule ("upward >= 2x inward = the system's BACK/HOME,
        // leave it alone") is deliberately gone — a swipe straight up out of the corner now
        // opens the panel (user request), and HOME stays reachable from the rest of the bottom
        // edge because the corner gate only admits the sector.
        if (inward < -config.reverseTolerance) return cancel()
        if (!isClaimed) {
            // Radial travel, not an inward+upward pair: the sector is direction-agnostic.
            val travel = hypot(inward.toDouble(), upward.toDouble()).toFloat()
            if (travel < config.inwardThreshold) return SpyAction.PassThrough
            isClaimed = true
            phase = SpyPhase.REVEALING
            return SpyAction.Activate(activeSide, originX, originY, x, y)
        }
        phase = SpyPhase.SELECTING
        return SpyAction.Update(activeSide, x, y)
    }

    fun up(pointerId: Int): SpyAction {
        if (pointerId != this.pointerId) return cancel()
        if (!isClaimed) {
            reset()
            return SpyAction.PassThrough
        }
        clearTracking(SpyPhase.COMMITTING)
        return SpyAction.Commit
    }

    fun cancel(): SpyAction {
        val wasClaimed = isClaimed
        clearTracking(if (wasClaimed) SpyPhase.CANCELLING else SpyPhase.IDLE)
        return if (wasClaimed) SpyAction.Cancel else SpyAction.PassThrough
    }

    /** spy DOWN detection helper mirroring CornerGestureView :271-285 usage. */
    fun detectSide(x: Float, y: Float, config: SpyGestureConfig): SpySide? =
        CornerTriggerRegion.detectSide(
            x, y, config.displayWidth, config.displayHeight, config.triggerRadius,
            config.leftEnabled, config.rightEnabled,
        )

    private fun reset() = clearTracking(SpyPhase.IDLE)

    private fun clearTracking(next: SpyPhase) {
        phase = next
        pointerId = -1
        originX = 0f
        originY = 0f
        side = null
        isClaimed = false
    }
}
