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
