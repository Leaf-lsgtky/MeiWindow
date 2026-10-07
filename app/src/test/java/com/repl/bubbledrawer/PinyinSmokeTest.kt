package com.repl.bubbledrawer

import com.repl.bubbledrawer.pinyin.AppSortKey
import com.repl.bubbledrawer.pinyin.BubbleApp
import com.repl.bubbledrawer.pinyin.HanziToPinyin
import com.repl.bubbledrawer.pinyin.PinyinTables
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Kept small on purpose (user ruling: overall test rounds, not per-task TDD).
 *
 * The boundary table was built against ANDROID's ICU/CLDR pinyin collator (the
 * original C2282f runs there too), which the desktop JDK does not provide — so
 * tests install a fake pinyin-ordered [HanziToPinyin.Compare]. That validates
 * the table/binary-search machinery; production keeps the system collator,
 * exactly like the original.
 */
class PinyinSmokeTest {

    /** Maps known hanzi (incl. every table boundary) to pinyin for comparison. */
    private class FakePinyinCompare : HanziToPinyin.Compare {
        private val map: HashMap<Char, String> = HashMap<Char, String>().let { seed ->
            for (i in PinyinTables.BOUNDARIES.indices) {
                seed[PinyinTables.BOUNDARIES[i]] = PinyinTables.PINYINS[i].lowercase()
            }
            seed['微'] = "wei"; seed['信'] = "xin"; seed['支'] = "zhi"; seed['付'] = "fu"
            seed['宝'] = "bao"; seed['美'] = "mei"; seed['团'] = "tuan"; seed['外'] = "wai"
            seed['卖'] = "mai"
            seed
        }
        override fun compare(a: String, b: String): Int {
            val ka = a.singleOrNull()?.let { map[it] } ?: a
            val kb = b.singleOrNull()?.let { map[it] } ?: b
            return ka.compareTo(kb)
        }
    }

    @Before fun setUp() { HanziToPinyin.collatorOverride = FakePinyinCompare() }
    @After fun tearDown() { HanziToPinyin.collatorOverride = null }

    private fun key(label: String) = AppSortKey.of(label)

    @Test
    fun chineseInitials() {
        assertEquals("WEIXIN", key("微信"))
        assertEquals("Z", key("支付宝").take(1))
        assertEquals("M", key("美团外卖").take(1))
    }

    @Test
    fun asciiKeepsCase() {
        assertEquals("Q", key("QQ").take(1))
        assertEquals("B", key("bilibili").take(1)) // m7035e uppercases everything
    }

    @Test
    fun groups() {
        assertEquals("W", AppSortKey.groupOf(key("微信")))
        assertEquals("0", AppSortKey.groupOf("360清理"))
        assertEquals("#", AppSortKey.groupOf("★特殊"))
    }

    @Test
    fun comparatorUsageThenKey() {
        val a = BubbleApp("a", "阿里", usageCount = 5, sortKey = "ALI")
        val b = BubbleApp("b", "微信", usageCount = 5, sortKey = "WEIXIN")
        val c = BubbleApp("c", "Z应用", usageCount = 9, sortKey = "Z")
        assertEquals(listOf(c, a, b), listOf(b, a, c).sortedWith(AppSortKey.DEFAULT))
    }

    @Test
    fun sectionLetterComparatorSinksHash() {
        val list = listOf("M", "#", "A").sortedWith(AppSortKey.SECTION_LETTER)
        assertEquals(listOf("A", "M", "#"), list)
    }
}
