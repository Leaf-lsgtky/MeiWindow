package com.repl.bubbledrawer.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** State-machine smoke (pure JVM; part of the scheduled overall test rounds). */
class CornerGestureDetectorTest {

    // 1080x2400 screen, 3dp density=3 → zone inset 96px, w 288px, h 480px (px literals)
    private val bl = CornerZone(Corner.BOTTOM_LEFT, 96f, 1920f, 384f, 2400f)
    private val br = CornerZone(Corner.BOTTOM_RIGHT, 696f, 1920f, 984f, 2400f)

    private fun det() = CornerGestureDetector(listOf(bl, br), triggerDistancePx = 150f, minSidePx = 60f)

    @Test
    fun diagonalUpFromLeftCornerTriggers() {
        val d = det()
        assertEquals(CornerGestureDetector.Feed.PENDING, d.onDown(240f, 2300f))
        assertEquals(CornerGestureDetector.Feed.ACCEPT, d.onMove(360f, 2000f)) // dx=+120 inward, dy=-300
        assertEquals(Corner.BOTTOM_LEFT, d.corner)
    }

    @Test
    fun straightUpDoesNotTrigger() {
        val d = det()
        d.onDown(240f, 2300f)
        assertEquals(CornerGestureDetector.Feed.PENDING, d.onMove(250f, 1900f)) // dx=10 < minSide
        assertFalse(d.triggered)
    }

    @Test
    fun horizontalSwipeDoesNotTrigger() {
        val d = det()
        d.onDown(240f, 2300f)
        d.onMove(900f, 2280f) // dy=-20 < trigger
        assertFalse(d.triggered)
    }

    @Test
    fun downOutsideZonesNeverTakes() {
        val d = det()
        assertEquals(CornerGestureDetector.Feed.IGNORE, d.onDown(500f, 1000f))
    }

    @Test
    fun rightCornerNeedsLeftwardInward() {
        val d = det()
        d.onDown(900f, 2300f)
        assertEquals(CornerGestureDetector.Feed.ACCEPT, d.onMove(780f, 2000f))
        assertEquals(Corner.BOTTOM_RIGHT, d.corner)
        val d2 = det()
        d2.onDown(900f, 2300f)
        d2.onMove(1020f, 2000f) // outward → never accept
        assertFalse(d2.triggered)
    }

    @Test
    fun upAndCancelReset() {
        val d = det()
        d.onDown(240f, 2300f); d.onMove(360f, 2000f)
        assertTrue(d.triggered)
        d.onUp()
        assertEquals(CornerGestureDetector.State.IDLE, d.state)
        assertFalse(d.triggered)
    }

    @Test
    fun sideMidpointInwardHorizontalTriggers() {
        val sl = CornerZone(Corner.SIDE_LEFT, 0f, 720f, 72f, 1680f)
        val sr = CornerZone(Corner.SIDE_RIGHT, 1008f, 720f, 1080f, 1680f)
        val d = CornerGestureDetector(listOf(sl, sr), triggerDistancePx = 150f, minSidePx = 60f)
        assertEquals(CornerGestureDetector.Feed.PENDING, d.onDown(30f, 1200f))
        assertEquals(CornerGestureDetector.Feed.ACCEPT, d.onMove(30f + 300f, 1210f)) // rightward inward
        assertEquals(Corner.SIDE_LEFT, d.corner)
        val d2 = CornerGestureDetector(listOf(sl, sr), triggerDistancePx = 150f, minSidePx = 60f)
        d2.onDown(1050f, 1200f)
        assertEquals(CornerGestureDetector.Feed.ACCEPT, d2.onMove(750f, 1195f)) // leftward inward
        assertEquals(Corner.SIDE_RIGHT, d2.corner)
    }
}
