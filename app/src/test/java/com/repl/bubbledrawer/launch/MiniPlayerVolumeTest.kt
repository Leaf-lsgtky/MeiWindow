package com.repl.bubbledrawer.launch

import org.junit.Assert.*
import org.junit.Test

class MiniPlayerVolumeTest {
    @Test fun silentVendorNoOpFallsBackForEveryPlayer() {
        val vendor = mutableListOf<Int>()
        val proxy = mutableListOf<Int>()
        val result = applyMiniPlayerVolume(listOf(10, 11), 0f, { 1f },
            { player, _ -> vendor.add(player); true }, { player, _ -> proxy.add(player); true })
        assertEquals(listOf(10, 11), vendor)
        assertEquals(listOf(10, 11), proxy)
        assertTrue(result.applied)
        assertTrue(result.usedProxy)
        assertFalse(result.vendorApplied)
    }

    @Test fun verifiedVendorWriteKeepsItsOwnVolumeAndTouchesEveryPlayer() {
        val writes = mutableListOf<Int>()
        val result = applyMiniPlayerVolume(listOf(10, 11), 0f, { 0f },
            { player, _ -> writes.add(player); true }, { _, _ -> error("unexpected fallback") })
        assertEquals(listOf(10, 11), writes)
        assertTrue(result.applied)
        assertTrue(result.vendorApplied)
        assertFalse(result.usedProxy)
    }

    @Test fun oneFailedPlayerDoesNotSkipLaterPlayersOrClaimFullSuccess() {
        val proxies = mutableListOf<Int>()
        val result = applyMiniPlayerVolume(listOf(10, 11, 12), 0f, { 1f },
            { player, _ -> if (player == 10) error("unsupported") else true },
            { player, _ -> proxies.add(player); player != 11 })
        assertEquals(listOf(10, 11, 12), proxies)
        assertFalse(result.applied)
        assertEquals(1, result.failedCount)
        assertEquals(2, result.proxyCount)
    }

    @Test fun restorationUsesSavedVolumeOnEveryPlayerEvenWithoutVendorReadback() {
        val restored = mutableListOf<Float>()
        val result = applyMiniPlayerVolume(listOf(10, 11), 0.65f, { null },
            { _, _ -> false }, { _, volume -> restored.add(volume); true })
        assertEquals(listOf(0.65f, 0.65f), restored)
        assertTrue(result.applied)
    }

    @Test fun absentPlayersAndUnreadableBackendsAreNotSuccess() {
        assertFalse(applyMiniPlayerVolume(emptyList<Int>(), 0f, { 0f }, { _, _ -> true }, { _, _ -> true }).applied)
        assertFalse(applyMiniPlayerVolume(listOf(10), 0f, { Float.NaN }, { _, _ -> true }, { _, _ -> false }).applied)
    }

    @Test fun proxyMuteMustBeRestoredEvenWhenVendorAlreadyReportsOne() {
        val restored = mutableListOf<Int>()
        val result = applyMiniPlayerVolume(listOf(10, 11), 1f, { 1f },
            { _, _ -> true }, { player, _ -> restored.add(player); true }, forceProxy = true)
        assertEquals(listOf(10, 11), restored)
        assertTrue(result.applied)
    }

    @Test fun onlyStartedMediaPlayersAreMutedButPausedPlayersCanBeRestored() {
        assertTrue(isMiniMediaPlayer(2, 1, -1))
        assertTrue(isMiniMediaPlayer(2, 14, 3))
        assertFalse(isMiniMediaPlayer(3, 1, 3))
        assertFalse(isMiniMediaPlayer(2, 2, 0))
        assertTrue(isMiniMediaPlayer(3, 1, 3, requireStarted = false))
        assertFalse(isMiniMediaPlayer(3, 2, 0, requireStarted = false))
    }
}
