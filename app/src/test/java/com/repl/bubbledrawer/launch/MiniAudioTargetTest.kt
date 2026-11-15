package com.repl.bubbledrawer.launch

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniAudioTargetTest {
    @Test fun missingEffectiveUidUsesPackageOrActivityIdentity() {
        assertEquals(10234, selectMiniAudioUid(0, 10234, null, -1))
        assertEquals(10234, selectMiniAudioUid(0, null, 10234, null))
    }

    @Test fun clonedAppNeverFallsBackToOwnerUser() {
        assertEquals(99_910234, selectMiniAudioUid(999, 10234, 99_910234, null))
        assertEquals(-1, selectMiniAudioUid(999, 10234, null, 10234))
    }

    @Test fun unknownUserIsNotGuessedFromAnUnscopedPackageLookup() {
        assertEquals(-1, selectMiniAudioUid(null, 10234, null, -1))
        assertEquals(1010234, selectMiniAudioUid(null, null, 1010234, null))
    }
}
