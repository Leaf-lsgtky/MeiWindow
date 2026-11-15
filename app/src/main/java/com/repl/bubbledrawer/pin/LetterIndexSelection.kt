package com.repl.bubbledrawer.pin

/** Drawing and hit testing share the full alphabet, even when some glyphs are hidden. */
internal data class LetterIndexLayout(
    val count: Int,
    val height: Float,
    val naturalPitch: Float,
    val centered: Boolean,
) {
    val pitch: Float = if (count > 0) minOf(naturalPitch, height / count).coerceAtLeast(0f) else 0f
    val top: Float = if (centered) ((height - count * pitch) / 2f).coerceAtLeast(0f) else 0f

    fun indexAt(y: Float): Int? = if (count > 0 && pitch > 0f) {
        ((y - top) / pitch).toInt().coerceIn(0, count - 1)
    } else null
}

/** Touch selection is independent of the list's current section during animated scrolling. */
internal class LetterIndexSelection {
    var index: Int? = null
        private set

    fun begin(y: Float, layout: LetterIndexLayout): Int? {
        index = layout.indexAt(y)
        return index
    }

    /** A small dead band around the previous row prevents boundary jitter. */
    fun move(y: Float, layout: LetterIndexLayout, slop: Float): Int? {
        val previous = index ?: return null
        val margin = minOf(slop, layout.pitch * 0.2f)
        val start = layout.top + previous * layout.pitch - margin
        val end = layout.top + (previous + 1) * layout.pitch + margin
        if (y >= start && y < end) return null
        val next = layout.indexAt(y) ?: return null
        if (next == previous) return null
        index = next
        return next
    }

    fun end() { index = null }
}
