package com.repl.bubbledrawer.gesture

/**
 * Pure state machine for the corner swipe. No Android types → JVM-testable.
 *
 * Flyme original: the window host is driven by the framework gesture listener;
 * the slide-out itself starts when the finger travels `slide_trigger_scroll_distance`
 * (50dp, GestureAppLauncher f10690c :944) upward along the edge. Our replica is a
 * standalone overlay, so we additionally require a diagonal component so the plain
 * system back-swipe stays untouched (design spec §4.2).
 */
class CornerGestureDetector(
    private val zones: List<CornerZone>,
    private val triggerDistancePx: Float = 100f,   // ~50dp — flyme slide trigger
    private val minSidePx: Float = 36f,            // diagonal component requirement
) {
    enum class Feed { IGNORE, PENDING, ACCEPT }

    var state = State.IDLE
        private set
    var triggered = false
        private set
    var corner: Corner? = null
        private set

    enum class State { IDLE, TRACKING, TRIGGERED }

    private var startX = 0f
    private var startY = 0f
    private var activeZone: CornerZone? = null

    fun onDown(x: Float, y: Float): Feed {
        val zone = zones.firstOrNull { it.contains(x, y) } ?: return Feed.IGNORE
        state = State.TRACKING
        activeZone = zone
        startX = x
        startY = y
        return Feed.PENDING
    }

    fun onMove(x: Float, y: Float): Feed {
        if (state != State.TRACKING) return Feed.IGNORE
        val zone = activeZone ?: return Feed.IGNORE
        val dx = x - startX
        val dy = y - startY
        val accepted = when (zone.corner) {
            Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT -> {
                val inward = when (zone.corner) {
                    Corner.BOTTOM_LEFT -> dx > minSidePx
                    else -> dx < -minSidePx
                }
                // up-dominant diagonal, flyme slide distance met
                -dy >= triggerDistancePx && inward && -dy >= kotlin.math.abs(dx) * 0.5f
            }
            Corner.SIDE_LEFT -> -dx >= triggerDistancePx && kotlin.math.abs(dy) < -dx * 0.5f
            Corner.SIDE_RIGHT -> dx >= triggerDistancePx && kotlin.math.abs(dy) < dx * 0.5f
        }
        if (accepted) {
            state = State.TRIGGERED
            triggered = true
            corner = zone.corner
            return Feed.ACCEPT
        }
        return Feed.PENDING
    }

    fun onCancel() = reset()

    fun onUp() = reset()

    private fun reset() {
        state = State.IDLE
        triggered = false
        corner = null
        activeZone = null
    }
}
