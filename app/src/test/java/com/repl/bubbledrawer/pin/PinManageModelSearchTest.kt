package com.repl.bubbledrawer.pin

import com.repl.bubbledrawer.data.PinnedRef
import com.repl.bubbledrawer.pinyin.AppSortKey
import com.repl.bubbledrawer.pinyin.BubbleApp
import com.repl.bubbledrawer.pinyin.HanziToPinyin
import com.repl.bubbledrawer.pinyin.PinyinTables
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PinManageModelSearchTest {

    private class FakePinyinCompare : HanziToPinyin.Compare {
        private val map: HashMap<Char, String> = HashMap<Char, String>().let { seed ->
            for (i in PinyinTables.BOUNDARIES.indices) {
                seed[PinyinTables.BOUNDARIES[i]] = PinyinTables.PINYINS[i].lowercase()
            }
            seed['微'] = "wei"; seed['信'] = "xin"; seed['支'] = "zhi"; seed['付'] = "fu"
            seed['宝'] = "bao"; seed['美'] = "mei"; seed['团'] = "tuan"
            seed
        }
        override fun compare(a: String, b: String): Int {
            val ka = a.singleOrNull()?.let { map[it] } ?: a
            val kb = b.singleOrNull()?.let { map[it] } ?: b
            return ka.compareTo(kb)
        }
    }

    @Before
    fun setUp() {
        HanziToPinyin.collatorOverride = FakePinyinCompare()
    }

    @After
    fun tearDown() {
        HanziToPinyin.collatorOverride = null
    }

    @Test
    fun testSearchFiltering() {
        val app1 = BubbleApp("com.tencent.mm", "微信", sortKey = "WEIXIN", initials = "WX")
        val app2 = BubbleApp("com.eg.android.AlipayGphone", "支付宝", sortKey = "ZHIFUBAO", initials = "ZFB")
        val app3 = BubbleApp("com.sankuai.meituan", "美团", sortKey = "MEITUAN", initials = "MT")
        val apps = persistentListOf(app1, app2, app3)
        val pinned = mutableListOf(PinnedRef("com.tencent.mm", 0))

        val model = PinManageModel(
            loadApps = { apps },
            pinsOf = { pinned },
            onOrderChange = { },
            recommendConfig = { false to 0 },
        )

        val field = PinManageModel::class.java.declaredFields.first { it.name.startsWith("apps") }
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = field.get(model) as androidx.compose.runtime.MutableState<ImmutableList<BubbleApp>>
        state.value = apps

        // 1. Initial state
        assertFalse(model.isSearching)
        assertEquals("", model.searchQuery)

        // 2. Start search
        model.startSearch()
        assertTrue(model.isSearching)

        // 3. Search by Chinese label
        model.searchQuery = "微"
        var displayed = model.displayedCells
        assertEquals(2, displayed.size) // Label + 1 App
        assertTrue(displayed[0] is Cell.Label)
        assertEquals("com.tencent.mm", (displayed[1] as Cell.App).app.packageName)

        // 4. Search by Pinyin
        model.searchQuery = "zhifu"
        displayed = model.displayedCells
        assertEquals(2, displayed.size)
        assertEquals("com.eg.android.AlipayGphone", (displayed[1] as Cell.App).app.packageName)

        // 5. Search by Initials
        model.searchQuery = "mt"
        displayed = model.displayedCells
        assertEquals(2, displayed.size)
        assertEquals("com.sankuai.meituan", (displayed[1] as Cell.App).app.packageName)

        // 6. Search by PackageName
        model.searchQuery = "alipay"
        displayed = model.displayedCells
        assertEquals(2, displayed.size)
        assertEquals("com.eg.android.AlipayGphone", (displayed[1] as Cell.App).app.packageName)

        // 7. No match
        model.searchQuery = "nonexistent"
        displayed = model.displayedCells
        assertEquals(1, displayed.size)
        assertEquals("无搜索结果", (displayed[0] as Cell.Label).text)

        // 8. isPinned check
        assertTrue(model.isPinned(app1))
        assertFalse(model.isPinned(app2))

        // 9. Cancel search
        model.cancelSearch()
        assertFalse(model.isSearching)
        assertEquals("", model.searchQuery)
    }

    @Test
    fun testSearchRankPriority() {
        val model = PinManageModel(loadApps = { persistentListOf() }, pinsOf = { emptyList() }, onOrderChange = {})

        val weixin = BubbleApp("com.tencent.mm", "微信", sortKey = "WEIXIN", initials = "WX")
        val qyWeixin = BubbleApp("com.tencent.wework", "企业微信", sortKey = "QIYEWEIXIN", initials = "QYWX")
        val otherWithPkg = BubbleApp("com.tencent.weishi", "短视频", sortKey = "DUANSHIPIN", initials = "DSP")

        // 1. 首字匹配 vs 字符包含 vs 不匹配
        assertEquals(1, model.searchRank(weixin, "微")) // 首字匹配
        assertEquals(3, model.searchRank(qyWeixin, "微")) // 字符包含
        assertEquals(0, model.searchRank(otherWithPkg, "微")) // 不匹配

        // 2. 拼音首字匹配 vs 字符包含 vs 包名包含
        assertEquals(2, model.searchRank(weixin, "wei")) // 拼音首字匹配
        assertEquals(3, model.searchRank(qyWeixin, "wei")) // 字符包含
        assertEquals(4, model.searchRank(otherWithPkg, "wei")) // 包名包含 (weishi)

        // 3. 拼音首字母匹配 (initials)
        assertEquals(2, model.searchRank(weixin, "wx")) // 拼音首字匹配
        assertEquals(3, model.searchRank(qyWeixin, "wx")) // 字符包含
    }

    @Test
    fun testSearchSortingOrder() {
        val appTier1 = BubbleApp("com.a", "微信", sortKey = "WEIXIN", initials = "WX") // 首字匹配
        val appTier2 = BubbleApp("com.c", "维他命", sortKey = "WEITAMING", initials = "WTM") // 拼音首字匹配 for "wei"
        val appTier3 = BubbleApp("com.b", "企业微信", sortKey = "QIYEWEIXIN", initials = "QYWX") // 字符包含
        val appTier4 = BubbleApp("com.example.wei", "社交应用", sortKey = "SHEJIAOYINGYONG", initials = "SJYY") // 包名包含

        val apps = persistentListOf(appTier3, appTier4, appTier1)
        val model = PinManageModel(loadApps = { apps }, pinsOf = { emptyList() }, onOrderChange = {})
        val field = PinManageModel::class.java.declaredFields.first { it.name.startsWith("apps") }
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = field.get(model) as androidx.compose.runtime.MutableState<ImmutableList<BubbleApp>>
        state.value = apps

        model.startSearch()
        model.searchQuery = "微"
        val displayed = model.displayedCells
        // 首字匹配 (微信) > 字符包含 (企业微信)
        assertEquals(3, displayed.size) // Label + 2 Apps
        assertEquals("com.a", (displayed[1] as Cell.App).app.packageName)
        assertEquals("com.b", (displayed[2] as Cell.App).app.packageName)

        // 测试 拼音首字匹配 (维他命) > 字符包含 (企业微信) > 包名包含 (社交应用)
        val all4 = persistentListOf(appTier4, appTier3, appTier2)
        state.value = all4
        model.searchQuery = "wei"
        val displayedWei = model.displayedCells
        assertEquals(4, displayedWei.size)
        assertEquals("com.c", (displayedWei[1] as Cell.App).app.packageName)
        assertEquals("com.b", (displayedWei[2] as Cell.App).app.packageName)
        assertEquals("com.example.wei", (displayedWei[3] as Cell.App).app.packageName)
    }
}
