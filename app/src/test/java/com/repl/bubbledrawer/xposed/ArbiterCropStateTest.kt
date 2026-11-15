package com.repl.bubbledrawer.xposed

import org.junit.Assert.*
import org.junit.Test

class ArbiterCropStateTest {
    private val crop = ArbiterCropState(42, 10_000L, 1220, 2656, 0, 115, 1105)

    @Test fun onlyPointsActuallyExcludedFromTheLauncherMaySkipPreOwn() {
        assertTrue(crop.excludes(100f, 2640f, 1220, 2656, 0, 42, 11_000L))
        assertTrue(crop.excludes(1150f, 2640f, 1220, 2656, 0, 42, 11_000L))
        // Enlarging the corner in SystemUI before system_server updates must not trust the old crop.
        assertFalse(crop.excludes(150f, 2640f, 1220, 2656, 0, 42, 11_000L))
        assertFalse(crop.excludes(115f, 2640f, 1220, 2656, 0, 42, 11_000L))
        val leftOnly = crop.copy(right = 1220)
        assertFalse(leftOnly.excludes(1150f, 2640f, 1220, 2656, 0, 42, 11_000L))
    }

    @Test fun rotationAndBootInvalidatePersistedAcknowledgements() {
        assertFalse(crop.excludes(100f, 1200f, 2656, 1220, 1, 42, 11_000L))
        // A 180-degree rotation keeps dimensions; checking only width/height is insufficient.
        assertFalse(crop.excludes(100f, 2640f, 1220, 2656, 2, 42, 11_000L))
        assertFalse(crop.excludes(100f, 2640f, 1220, 2656, 0, 43, 11_000L))
        assertFalse(crop.excludes(100f, 2640f, 1220, 2656, 0, -1, 11_000L))
    }

    @Test fun expiredOrNewerThanTheDownCannotProveThatDownWasProtected() {
        assertFalse(crop.excludes(100f, 2640f, 1220, 2656, 0, 42, 9_999L))
        assertFalse(crop.excludes(100f, 2640f, 1220, 2656, 0, 42, 16_001L))
    }

    @Test fun oldBooleanAndMalformedRecordsAreNotAccepted() {
        assertEquals(crop, ArbiterCropState.decode(crop.encode()))
        listOf(null, "1", "0", "1|42|10000|1220|2656|0|1200|100", "1|42|10000|1220|2656|0|0|1220")
            .forEach { assertNull(ArbiterCropState.decode(it)) }
    }

    @Test fun wideCornersAreNotSilentlyCappedAtOneThirdOfTheDisplay() {
        assertEquals(481 to 599, launcherCropEdges(1080, 480, true, true))
        assertEquals(481 to 1080, launcherCropEdges(1080, 480, true, false))
        assertEquals(0 to 599, launcherCropEdges(1080, 480, false, true))
        assertEquals(0 to 1080, launcherCropEdges(1080, 480, false, false))
        // An extreme display scale still leaves a non-empty middle lane for HOME.
        val (left, right) = launcherCropEdges(600, 480, true, true)
        assertTrue(left < right)
    }
}
