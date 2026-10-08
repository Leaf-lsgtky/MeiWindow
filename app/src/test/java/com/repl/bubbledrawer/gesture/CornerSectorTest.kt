package com.repl.bubbledrawer.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawer's trigger region is a quarter-ellipse hugging a bottom corner. These cases pin
 * the two properties that matter on device: the axes are independent (a tall thin zone up
 * the edge, or a wide shallow one along the bottom), and a point is either inside the
 * corner's sector or outside it — the direction the finger then moves is the engine's
 * business, not the region's.
 */
class CornerSectorTest {

    private val w = 1220f
    private val h = 2656f

    @Test fun cornerPointIsInside() {
        // right corner bottom-most pixel (what a real finger actually reaches)
        assertTrue(CornerSector.contains(1219f, 2655f, w, h, 300f, 300f, leftCorner = false))
        assertTrue(CornerSector.contains(1f, 2655f, w, h, 300f, 300f, leftCorner = true))
    }

    @Test fun circleShapeRejectsTheSquareCorner() {
        // equal axes: the diagonal of the square is OUTSIDE the disc (x=250,y=250 → r≈354>300)
        assertFalse(CornerSector.contains(970f, 2406f, w, h, 300f, 300f, leftCorner = false))
    }

    @Test fun tallThinZoneFollowsTheEdgeOnly() {
        // edge axis 200dp-ish, bottom axis small: a point high up the edge is in, a point far
        // along the bottom at the same height is out
        assertTrue(CornerSector.contains(1215f, 2656f - 150f, w, h, 40f, 200f, leftCorner = false))
        assertFalse(CornerSector.contains(1220f - 150f, 2656f - 150f, w, h, 40f, 200f, leftCorner = false))
    }

    @Test fun wideShallowZoneFollowsTheBottomOnly() {
        // bottom axis large, edge axis small: far along the bottom is in, high up the edge is out
        assertTrue(CornerSector.contains(1220f - 150f, 2650f, w, h, 200f, 40f, leftCorner = false))
        assertFalse(CornerSector.contains(1215f, 2656f - 150f, w, h, 200f, 40f, leftCorner = false))
    }

    @Test fun sidesDoNotMirror() {
        // a left-corner point is not a right-corner point
        assertFalse(CornerSector.contains(40f, 2650f, w, h, 300f, 300f, leftCorner = false))
        assertFalse(CornerSector.contains(1180f, 2650f, w, h, 300f, 300f, leftCorner = true))
    }

    @Test fun midBottomIsNeverInside() {
        // the middle of the bottom edge must stay with the system's HOME gesture
        assertFalse(CornerSector.contains(610f, 2650f, w, h, 300f, 300f, leftCorner = true))
        assertFalse(CornerSector.contains(610f, 2650f, w, h, 300f, 300f, leftCorner = false))
    }
}
