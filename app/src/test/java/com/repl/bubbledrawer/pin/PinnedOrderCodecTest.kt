package com.repl.bubbledrawer.pin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedOrderCodecTest {

    private val wechat = PinnedKey.App("com.tencent.mm", 0)
    private val qq = PinnedKey.App("com.tencent.mobileqq", 10)
    private val pay = PinnedKey.Function("com.tencent.mm#wechat_pay")
    private val scan = PinnedKey.Function("com.eg.android.AlipayGphone#alipay_scan")

    @Test
    fun roundTripsAMixedOrder() {
        val order = listOf(pay, wechat, scan, qq)

        val encoded = PinnedOrderCodec.encode(order)

        assertEquals(
            // 编码按传入顺序写，不按类型分组 —— 这正是"混排"要保存的信息。
            "fn:com.tencent.mm#wechat_pay;app:com.tencent.mm#0;" +
                "fn:com.eg.android.AlipayGphone#alipay_scan;app:com.tencent.mobileqq#10",
            encoded,
        )
        assertEquals(order, PinnedOrderCodec.decode(encoded))
    }

    @Test
    fun decodeDropsMalformedAndDuplicateEntriesButKeepsValidOnes() {
        val raw = " app:com.tencent.mm#0 ; bad ; fn:com.tencent.mm#wechat_pay ; " +
            "app:com.tencent.mm#0 ;; fn: ; app:#3"

        assertEquals(
            listOf(wechat, pay),
            PinnedOrderCodec.decode(raw),
        )
    }

    @Test
    fun encodeSkipsEntriesThatCannotRoundTrip() {
        val invalid = listOf(
            PinnedKey.App("com.bad;name", 0),
            PinnedKey.App("", 0),
            PinnedKey.Function(" "),
            PinnedKey.Function("a;b"),
        )

        assertEquals("", PinnedOrderCodec.encode(invalid))
        assertTrue(PinnedOrderCodec.isPlausible(""))
    }

    @Test
    fun plausibilityRejectsUnknownTokensAndOverlongPayloads() {
        assertTrue(PinnedOrderCodec.isPlausible(PinnedOrderCodec.encode(listOf(wechat, pay))))
        assertFalse(PinnedOrderCodec.isPlausible("app:com.tencent.mm#0;unknown:x"))
        assertFalse(PinnedOrderCodec.isPlausible("app:com.tencent.mm#0;" + "x".repeat(33_000)))
        assertFalse(PinnedOrderCodec.isPlausible("fn:"))
    }

    @Test
    fun encodedOutputAlwaysPassesPlausibility() {
        val order = (0 until 600).map { i ->
            if (i % 2 == 0) PinnedKey.App("com.example.app$i", i) else PinnedKey.Function("com.example.app$i#id")
        }
        val encoded = PinnedOrderCodec.encode(order)

        assertTrue("encode 的输出必须能通过接收端校验", PinnedOrderCodec.isPlausible(encoded))
        assertTrue(PinnedOrderCodec.decode(encoded).size <= 512)
    }
}
