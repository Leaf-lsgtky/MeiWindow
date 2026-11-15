package com.repl.bubbledrawer.launch

import kotlin.math.abs

/** A void vendor setter returning normally does not prove that Sound Assist accepted the write. */
internal data class MiniVolumeResult(val vendorApplied: Boolean, val proxyCount: Int, val failedCount: Int) {
    val applied: Boolean get() = failedCount == 0 && (vendorApplied || proxyCount > 0)
    val usedProxy: Boolean get() = proxyCount > 0
}

internal fun <T> applyMiniPlayerVolume(
    players: List<T>,
    volume: Float,
    readVolume: () -> Float?,
    writeVendor: (T, Float) -> Boolean,
    writeProxy: (T, Float) -> Boolean,
    forceProxy: Boolean = false,
): MiniVolumeResult {
    if (players.isEmpty()) return MiniVolumeResult(false, 0, 0)
    // Do not use any(): an app may have several players and older implementations need each one.
    val writes = players.map { runCatching { writeVendor(it, volume) }.getOrDefault(false) }
    val observed = runCatching { readVolume() }.getOrNull()
    val confirmed = observed != null && observed.isFinite() && abs(observed - volume) <= 0.001f
    var proxies = 0
    var failed = 0
    players.forEachIndexed { index, player ->
        if (forceProxy || !confirmed || !writes[index]) {
            if (runCatching { writeProxy(player, volume) }.getOrDefault(false)) proxies++ else failed++
        }
    }
    return MiniVolumeResult(confirmed && writes.any { it }, proxies, failed)
}

/** Same selection as the supplied OS3 Sound Assist: STARTED and media/music usage. */
internal fun isMiniMediaPlayer(state: Int, usage: Int, stream: Int, requireStarted: Boolean = true): Boolean =
    (!requireStarted || state == 2) && (usage == 1 || stream == 3)
