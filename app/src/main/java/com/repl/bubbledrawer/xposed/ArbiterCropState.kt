package com.repl.bubbledrawer.xposed

/** A short-lived, geometry-specific acknowledgement; a persisted boolean is not proof. */
internal data class ArbiterCropState(
    val bootCount: Int,
    val confirmedAt: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val left: Int,
    val right: Int,
) {
    fun excludes(
        x: Float,
        y: Float,
        displayWidth: Int,
        displayHeight: Int,
        displayRotation: Int,
        currentBoot: Int,
        downTime: Long,
    ): Boolean = currentBoot >= 0 && bootCount == currentBoot &&
        width == displayWidth && height == displayHeight && rotation == displayRotation &&
        downTime >= confirmedAt && downTime - confirmedAt <= MAX_AGE_MS &&
        x >= 0f && x < width && y >= 0f && y < height && (x < left || x >= right)

    fun encode(): String = "1|$bootCount|$confirmedAt|$width|$height|$rotation|$left|$right"

    companion object {
        const val MAX_AGE_MS = 6_000L

        fun decode(value: String?): ArbiterCropState? {
            val fields = value?.split('|') ?: return null
            if (fields.size != 8 || fields[0] != "1") return null
            val boot = fields[1].toIntOrNull() ?: return null
            val time = fields[2].toLongOrNull() ?: return null
            val width = fields[3].toIntOrNull() ?: return null
            val height = fields[4].toIntOrNull() ?: return null
            val rotation = fields[5].toIntOrNull() ?: return null
            val left = fields[6].toIntOrNull() ?: return null
            val right = fields[7].toIntOrNull() ?: return null
            if (boot < 0 || time < 0 || width <= 0 || height <= 0 || rotation !in 0..3 ||
                left < 0 || right > width || left >= right || left == 0 && right == width) return null
            return ArbiterCropState(boot, time, width, height, rotation, left, right)
        }
    }
}

/** Keep a central HOME lane, but do not silently cap each corner to one third of the display. */
internal fun launcherCropEdges(width: Int, extent: Int, leftEnabled: Boolean, rightEnabled: Boolean): Pair<Int, Int> {
    val sides = (if (leftEnabled) 1 else 0) + (if (rightEnabled) 1 else 0)
    if (width <= 1 || sides == 0) return 0 to width
    // +1 includes the integer boundary accepted by CornerSector.contains().
    val cut = (extent + 1).coerceIn(0, (width - 1) / sides)
    return (if (leftEnabled) cut else 0) to (width - if (rightEnabled) cut else 0)
}
