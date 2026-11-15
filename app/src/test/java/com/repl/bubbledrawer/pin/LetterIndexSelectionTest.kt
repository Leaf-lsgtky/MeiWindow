package com.repl.bubbledrawer.pin

import org.junit.Assert.*
import org.junit.Test

class LetterIndexSelectionTest {
    private val layout = LetterIndexLayout(count = 28, height = 560f, naturalPitch = 20f, centered = true)

    @Test fun slightMovesDoNotReselectTheSameLetter() {
        val selection = LetterIndexSelection()
        assertEquals(3, selection.begin(70f, layout))
        listOf(70f, 70.2f, 69.8f, 71f, 70f).forEach { assertNull(selection.move(it, layout, 3f)) }
        assertEquals(3, selection.index)
    }

    @Test fun boundaryJitterMustCrossDeadBandBeforeChangingLetters() {
        val selection = LetterIndexSelection()
        selection.begin(79f, layout)
        listOf(80.5f, 79.5f, 82f, 78.5f).forEach { assertNull(selection.move(it, layout, 3f)) }
        assertEquals(4, selection.move(84f, layout, 3f))
        assertNull(selection.move(79f, layout, 3f))
        assertEquals(3, selection.move(76f, layout, 3f))
    }

    @Test fun everyLetterRemainsReachableInAShortSampledColumn() {
        val short = LetterIndexLayout(28, 196f, 20f, true)
        assertEquals(0f, short.top, 0f)
        for (index in 0 until short.count) {
            assertEquals(index, short.indexAt(short.top + short.pitch * (index + 0.5f)))
        }
        assertEquals(0, short.indexAt(-100f))
        assertEquals(27, short.indexAt(1000f))
    }

    @Test fun centeredColumnAndFreshTouchUseTheCorrectRow() {
        val tall = LetterIndexLayout(4, 200f, 20f, true)
        assertEquals(60f, tall.top, 0f)
        val selection = LetterIndexSelection()
        assertEquals(2, selection.begin(110f, tall))
        selection.end()
        assertNull(selection.index)
        assertNull(selection.move(140f, tall, 3f))
        assertEquals(2, selection.begin(110f, tall))
        assertNull(selection.begin(0f, LetterIndexLayout(0, 100f, 20f, true)))
    }
}
