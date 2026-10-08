package com.repl.bubbledrawer.gesture

/** Screen-coordinate rectangle that captures corner gestures. Mirrors the original
 *  system gesture listener region + flyme slide trigger (`slide_trigger_scroll_distance` 50dp). */
enum class Corner { BOTTOM_LEFT, BOTTOM_RIGHT, SIDE_LEFT, SIDE_RIGHT }

data class CornerZone(
    val corner: Corner,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom
}

/**
 * The drawer's trigger REGION: a quarter-ellipse hugging each bottom corner, with the two
 * axes configured independently — [bottomExtent] along the bottom edge and [edgeExtent] up
 * the side edge. Equal axes are the plain quarter-disc of the Flyme reference
 * (`CornerTriggerRegion.detectSide`), and a DOWN inside it belongs to the drawer in every
 * direction, straight up included. Pure maths so it can be unit-tested off-device.
 */
object CornerSector {

    /** @return true when (x, y) is inside the quarter-ellipse of that corner. */
    fun contains(
        x: Float,
        y: Float,
        displayWidth: Float,
        displayHeight: Float,
        bottomExtent: Float,
        edgeExtent: Float,
        leftCorner: Boolean,
    ): Boolean {
        if (bottomExtent <= 0f || edgeExtent <= 0f) return false
        val fromBottom = displayHeight - y
        if (fromBottom < 0f || fromBottom > edgeExtent) return false
        val alongBottom = if (leftCorner) x else displayWidth - x
        if (alongBottom < 0f || alongBottom > bottomExtent) return false
        val u = alongBottom / bottomExtent
        val v = fromBottom / edgeExtent
        return u * u + v * v <= 1f
    }
}
