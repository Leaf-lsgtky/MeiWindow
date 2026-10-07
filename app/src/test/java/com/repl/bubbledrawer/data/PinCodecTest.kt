package com.repl.bubbledrawer.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** PinCodec round-trip + cap (scheduled overall round; pure JVM). */
class PinCodecTest {

    @Test
    fun roundTripKeepsOrderAndUsers() {
        val pins = listOf(PinnedRef("com.a", 0), PinnedRef("com.b", 10), PinnedRef("com.c"))
        val encoded = PinCodec.encode(pins)
        assertEquals("com.a#0;com.b#10;com.c#0", encoded)
        assertEquals(pins, PinCodec.decode(encoded))
    }

    @Test
    fun decodeSkipsGarbage() {
        val got = PinCodec.decode("com.a#0;;  ;#1;com.b")
        assertEquals(listOf(PinnedRef("com.a", 0), PinnedRef("com.b", 0)), got)
    }

    @Test
    fun capAtSix() {
        val many = (1..9).map { PinnedRef("p$it") }
        assertEquals(9, many.size)
        // store applies the cap; codec itself is lossless, cap via take()
        assertEquals(6, many.take(PinCodec.MAX_PINS).size)
    }
}
