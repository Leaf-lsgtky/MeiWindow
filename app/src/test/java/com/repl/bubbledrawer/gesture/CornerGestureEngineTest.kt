package com.repl.bubbledrawer.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks of the corner claim thresholds — the reference CornerGestureEngine
 * :70-74 gates (inward max(1.75·slop, 14dp), upward max(0.5·slop, 4dp),
 * reverse max(slop, 8dp)). DOWN arming is unconditional at the engine level;
 * the SPY view gates the touch to the corner box (rounded-corner fix).
 */
class CornerGestureEngineTest {

    private val density = 3f
    private val config = AdaptiveSpyGestureConfig.create(
        displayWidth = 1080f, displayHeight = 2400f,
        touchSlop = 20f, density = density,
        triggerRangeDp = 96, leftEnabled = true, rightEnabled = true,
    )
    // thresholds with slop=20, d=3: inward=42, upward=12, reverse=24, radius=288

    private fun leftDown(e: CornerGestureEngine) =
        e.down(0, 20f, 2380f, SpySide.LEFT) // box gate done by the view

    @Test fun downArmsLeft() {
        val e = CornerGestureEngine()
        assertEquals(SpyAction.PassThrough, leftDown(e))
        assertEquals(SpyPhase.ARMED, e.phase)
    }

    @Test fun smallMoveStaysUnclaimed() {
        val e = CornerGestureEngine()
        leftDown(e)
        assertEquals(SpyAction.PassThrough, e.move(0, 1, 25f, 2380f, config)) // inward 5 < 42
        assertFalse(e.isClaimed)
    }

    @Test fun inwardWithoutUpwardDoesNotClaim() {
        val e = CornerGestureEngine()
        leftDown(e)
        assertEquals(SpyAction.PassThrough, e.move(0, 1, 20f + 44f, 2380f - 4f, config)) // upward 4 < 12
        assertFalse(e.isClaimed)
    }

    @Test fun inwardAndUpwardClaim() {
        val e = CornerGestureEngine()
        leftDown(e)
        val a = e.move(0, 1, 20f + 44f, 2380f - 13f, config)
        assertTrue(a is SpyAction.Activate)
        assertEquals(SpySide.LEFT, (a as SpyAction.Activate).side)
        assertTrue(e.isClaimed)
    }

    @Test fun pullBackBeyondToleranceCancelsClaimedGesture() {
        val e = CornerGestureEngine()
        leftDown(e)
        e.move(0, 1, 80f, 2360f, config) // claim (inward 60, upward 20)
        assertTrue(e.isClaimed)
        // back toward the corner beyond reverseTolerance=24:
        assertEquals(SpyAction.Cancel, e.move(0, 1, -10f, 2360f, config)) // inward -30
        assertFalse(e.isClaimed)
    }

    @Test fun unclaimedPullBackPassesThrough() {
        val e = CornerGestureEngine()
        leftDown(e)
        // never claimed → a reverse just cancels the ARMED watch quietly
        assertEquals(SpyAction.PassThrough, e.move(0, 1, 0f, 2380f, config))
        assertFalse(e.isClaimed)
    }

    @Test fun releaseOfClaimedCommits() {
        val e = CornerGestureEngine()
        leftDown(e)
        e.move(0, 1, 100f, 2300f, config)
        assertEquals(SpyAction.Commit, e.up(0))
    }

    @Test fun releaseOfUnclaimedPassesThrough() {
        val e = CornerGestureEngine()
        leftDown(e)
        assertEquals(SpyAction.PassThrough, e.up(0)) // plain tap → system keeps the gesture
    }

    @Test fun rightCornerMirrorsInward() {
        val e = CornerGestureEngine()
        e.down(0, 1060f, 2380f, SpySide.RIGHT)
        assertEquals(SpyPhase.ARMED, e.phase)
        // inward for RIGHT = originX - x → move LEFT by 44
        val a = e.move(0, 1, 1060f - 44f, 2380f - 13f, config)
        assertTrue(a is SpyAction.Activate)
        assertEquals(SpySide.RIGHT, (a as SpyAction.Activate).side)
    }

    @Test fun secondFingerCancels() {
        val e = CornerGestureEngine()
        leftDown(e)
        e.move(0, 1, 80f, 2360f, config) // claim
        assertEquals(SpyAction.Cancel, e.move(0, 2, 80f, 2360f, config)) // pointerCount 2
        assertFalse(e.isClaimed)
    }
}
