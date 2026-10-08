package com.repl.bubbledrawer.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks of the corner sector claim rule. The claim is RADIAL
 * (`hypot(inward, upward) >= max(1.75·slop, 14dp)`) and deliberately
 * direction-agnostic: a stroke that starts inside the corner sector belongs to the
 * drawer in every direction, straight up included (user request "完整扇形…即使上方也能"),
 * so the reference's old inward+upward pair and its vertical-dominance hand-off to the
 * system's HOME are both gone. Only a retreat back over the edge cancels.
 */
class CornerGestureEngineTest {

    private val density = 3f
    private val config = AdaptiveSpyGestureConfig.create(
        displayWidth = 1080f, displayHeight = 2400f,
        touchSlop = 20f, density = density,
        triggerRangeDp = 96, leftEnabled = true, rightEnabled = true,
    )
    // thresholds with slop=20, d=3: radial=42, reverse=24, radius=288

    private fun leftDown(e: CornerGestureEngine) =
        e.down(0, 20f, 2380f, SpySide.LEFT) // sector gate done by the monitor

    @Test fun downArmsLeft() {
        val e = CornerGestureEngine()
        assertEquals(SpyAction.PassThrough, leftDown(e))
        assertEquals(SpyPhase.ARMED, e.phase)
    }

    @Test fun smallMoveStaysUnclaimed() {
        val e = CornerGestureEngine()
        leftDown(e)
        assertEquals(SpyAction.PassThrough, e.move(0, 1, 25f, 2380f, config)) // travel 5 < 42
        assertFalse(e.isClaimed)
    }

    @Test fun inwardOnlyClaims() {
        val e = CornerGestureEngine()
        leftDown(e)
        // 44 px inward, no upward component: still the drawer (radial rule)
        assertTrue(e.move(0, 1, 20f + 44f, 2380f, config) is SpyAction.Activate)
        assertTrue(e.isClaimed)
    }

    @Test fun upwardOnlyClaims() {
        val e = CornerGestureEngine()
        leftDown(e)
        // straight UP out of the corner: the drawer, not the system's HOME
        val a = e.move(0, 1, 20f, 2380f - 44f, config)
        assertTrue(a is SpyAction.Activate)
        assertEquals(SpySide.LEFT, (a as SpyAction.Activate).side)
        assertTrue(e.isClaimed)
    }

    @Test fun diagonalClaims() {
        val e = CornerGestureEngine()
        leftDown(e)
        assertTrue(e.move(0, 1, 20f + 44f, 2380f - 13f, config) is SpyAction.Activate)
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
