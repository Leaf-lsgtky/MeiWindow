package com.repl.bubbledrawer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "which 收藏 list wins" decision — the part of the app/SystemUI pin split that can be
 * proven without a device (see PinsSync and PinBackend.readRev).
 */
class PinsSyncTest {

    private val a = "com.a#0"
    private val b = "com.a#0;com.b#0"

    @Test
    fun bothEmptyIsNoPins() {
        val d = PinsSync.decide("", 0, "", 0)
        assertEquals(PinsSync.Source.NONE, d.source)
        assertEquals("", d.value)
        assertFalse(d.pushBack)
    }

    @Test
    fun newerRemoteReplacesStaleLocal() {
        val d = PinsSync.decide(a, 100, b, 200)
        assertEquals(PinsSync.Source.REMOTE, d.source)
        assertEquals(b, d.value)
        assertEquals(200, d.rev)
        assertTrue("loser must be rewritten so the two converge", d.pushBack)
    }

    @Test
    fun newerLocalIsKeptAndPushedBack() {
        // The panel edited pins, the handoff to the app never landed: local is newer.
        val d = PinsSync.decide(b, 300, a, 200)
        assertEquals(PinsSync.Source.LOCAL, d.source)
        assertEquals(b, d.value)
        assertTrue(d.pushBack)
    }

    @Test
    fun legacyUnstampedSidesFallBackToTheSharedBus() {
        // Pre-stamp data: both rev 0 and different → remote (the shared bus) wins, once.
        val d = PinsSync.decide(a, 0, b, 0)
        assertEquals(PinsSync.Source.REMOTE, d.source)
        assertEquals(b, d.value)
        assertTrue(d.pushBack)
    }

    @Test
    fun identicalValuesNeedNoWork() {
        val d = PinsSync.decide(b, 42, b, 42)
        assertEquals(b, d.value)
        assertFalse(d.pushBack)
    }

    @Test
    fun oneSidedValuesPropagate() {
        val remoteOnly = PinsSync.decide("", 0, b, 7)
        assertEquals(PinsSync.Source.REMOTE, remoteOnly.source)
        assertEquals(7, remoteOnly.rev)
        assertTrue(remoteOnly.pushBack)

        val localOnly = PinsSync.decide(b, 7, "", 0)
        assertEquals(PinsSync.Source.LOCAL, localOnly.source)
        assertEquals(7, localOnly.rev)
        assertTrue(localOnly.pushBack)
    }

    @Test
    fun clearingAllPinsIsAValueToo() {
        // "unpin everything" in the app must reach the panel. The stamp is what makes the
        // difference: an empty STAMPED list is an edit, an empty unstamped one is a cold store.
        val cleared = PinsSync.decide("", 500, b, 400)
        assertEquals(PinsSync.Source.LOCAL, cleared.source)
        assertEquals("", cleared.value)
        assertEquals(500, cleared.rev)
        assertTrue(cleared.pushBack)

        // …and the legacy (unstamped) empty side is only "never filled".
        val coldStore = PinsSync.decide("", 0, b, 0)
        assertEquals(PinsSync.Source.REMOTE, coldStore.source)
        assertEquals(b, coldStore.value)
    }
}
