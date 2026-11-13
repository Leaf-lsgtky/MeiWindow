package com.repl.bubbledrawer.pin

import androidx.compose.runtime.MutableState
import com.repl.bubbledrawer.pinyin.HanziToPinyin
import com.repl.bubbledrawer.pinyin.PinyinTables
import com.repl.bubbledrawer.shortcut.ShortcutItem
import com.repl.bubbledrawer.shortcut.ShortcutType
import kotlinx.collections.immutable.persistentListOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 功能页（快捷方式收藏）在 [PinManageModel] 里的那一半：点选必须当场生效、分组按应用名（拼音排序）、
 * 已选列表跟用户点选的顺序走。
 *
 * 第一条是真实踩到的坑：写入会**同步**触发同一个 prefs 文件上的监听者，而值与戳是两个 key，
 * 监听者在那中间读一次就可能拿到撕裂的一对（[com.repl.bubbledrawer.data.PinsSync.decide] 在平票时
 * 判给共享组）→ 刚点的那一下被旧值顶掉，用户看到的是「第二次开始点了没反应」。
 */
class PinManageModelFunctionTest {

    /**
     * 桌面 JDK 没有 Android 的 ICU 中文排序器，而拼音表要靠它做二分查找 —— 单测里装一份假表
     * （与 [com.repl.bubbledrawer.PinyinSmokeTest] 同一手法），否则「微信」会被当成不可转写的名字。
     */
    private class FakePinyinCompare : HanziToPinyin.Compare {
        private val map: HashMap<Char, String> = HashMap<Char, String>().let { seed ->
            for (i in PinyinTables.BOUNDARIES.indices) {
                seed[PinyinTables.BOUNDARIES[i]] = PinyinTables.PINYINS[i].lowercase()
            }
            seed['微'] = "wei"; seed['信'] = "xin"; seed['支'] = "zhi"; seed['付'] = "fu"
            seed['宝'] = "bao"
            seed
        }
        override fun compare(a: String, b: String): Int {
            val ka = a.singleOrNull()?.let { map[it] } ?: a
            val kb = b.singleOrNull()?.let { map[it] } ?: b
            return ka.compareTo(kb)
        }
    }

    @Before
    fun installPinyin() {
        HanziToPinyin.collatorOverride = FakePinyinCompare()
    }

    @After
    fun removePinyin() {
        HanziToPinyin.collatorOverride = null
    }

    private val wechatScan = ShortcutItem(
        id = "wechat_scan",
        packageName = "com.tencent.mm",
        label = "扫一扫",
        subLabel = "微信",
        type = ShortcutType.APP_SHORTCUT,
    )
    private val wechatPay = ShortcutItem(
        id = "wechat_pay",
        packageName = "com.tencent.mm",
        label = "收付款",
        subLabel = "微信",
        type = ShortcutType.APP_SHORTCUT,
    )
    private val alipayScan = ShortcutItem(
        id = "alipay_scan",
        packageName = "com.eg.android.AlipayGphone",
        label = "扫一扫",
        subLabel = "支付宝",
        type = ShortcutType.APP_SHORTCUT,
    )
    private val desktopCalc = ShortcutItem(
        id = "7",
        packageName = "com.miui.calculator",
        label = "计算器快捷",
        type = ShortcutType.DESKTOP_PINNED,
    )

    private fun model(
        functionPinsOf: () -> List<String> = { emptyList() },
        pinnedFunctionItems: () -> List<ShortcutItem> = { emptyList() },
        onFunctionOrderChange: (List<String>) -> Unit = {},
        pinnedOrderOf: () -> List<PinnedKey> = { emptyList() },
        onPinnedOrderChange: (List<PinnedKey>) -> Unit = {},
    ) = PinManageModel(
        loadApps = { persistentListOf() },
        pinsOf = { emptyList() },
        onOrderChange = {},
        recommendConfig = { false to 0 },
        functionPinsOf = functionPinsOf,
        pinnedFunctionItems = pinnedFunctionItems,
        onFunctionOrderChange = onFunctionOrderChange,
        pinnedOrderOf = pinnedOrderOf,
        onPinnedOrderChange = onPinnedOrderChange,
    )

    /** 直接往 Compose 状态字段里塞值（与 [PinManageModelPinTest] 同一手法，reload 是异步的）。 */
    private fun injectShortcuts(model: PinManageModel, apps: List<ShortcutItem>, desktop: List<ShortcutItem> = emptyList()) {
        setState(model, "appShortcuts", apps)
        setState(model, "desktopShortcuts", desktop)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> setState(model: PinManageModel, name: String, value: List<T>) {
        // `by mutableStateOf` 的属性编译成 `xxx$delegate`；精确匹配是为了不撞上构造参数
        // functionPinsOf（lambda）那种同前缀的字段。
        val field = PinManageModel::class.java.declaredFields.first {
            it.name == name || it.name == name + "\$delegate"
        }
        field.isAccessible = true
        (field.get(model) as MutableState<List<T>>).value = value
    }

    private fun injectPins(model: PinManageModel, pins: List<String>) {
        // 只改状态，不调 refreshFunctionPins()：后者会去读 functionPinsOf() 把我们刚塞的值盖掉。
        setState(model, "functionPins", pins)
    }

    @Test
    fun tappingAFunctionSticksEvenWhenTheReadRightAfterIsStale() {
        var stored: List<String> = emptyList()
        var readIsStale = false
        var model: PinManageModel? = null
        val built = model(
            // 写完之后的那次读拿到旧值：值与戳是两个 key，逐 key 镜像时会撕裂（真实设备就是这样）。
            functionPinsOf = { if (readIsStale) emptyList() else stored },
            onFunctionOrderChange = { order ->
                stored = order
                readIsStale = true
                // 真实链路里 prefs 监听是**同步**回调的（runOnUiThread 在主线程上等于直接调用），
                // 所以这里必须真的再读一次 —— 否则这个测试连"写回之后再读会被顶掉"都没碰到，
                // 去掉 re-assert 也照样绿。
                model?.refreshFunctionPins()
            },
        )
        model = built

        built.toggleFunctionPin(wechatScan)

        assertTrue("刚点选的功能必须当场进已选", built.isFunctionPinned(wechatScan))
        assertEquals(listOf(wechatScan.pinKey), stored)
    }

    @Test
    fun tappingAgainUnpinsAndPersistsTheEmptyList() {
        var stored: List<String> = listOf(wechatScan.pinKey)
        val model = model(functionPinsOf = { stored }, onFunctionOrderChange = { stored = it })
        model.refreshFunctionPins()

        model.toggleFunctionPin(wechatScan)

        assertFalse(model.isFunctionPinned(wechatScan))
        assertEquals(emptyList<String>(), stored)
    }

    @Test
    fun functionsAreGroupedByAppNameWithPinyinOrder() {
        val model = model()
        injectShortcuts(model, listOf(wechatScan, alipayScan, wechatPay, desktopCalc))

        val groups = model.groupedFunctions
        assertEquals(
            "每条功能归到宿主应用名（没有宿主就用它自己的标题）下，同一条不重复出现",
            setOf("微信", "支付宝", "计算器快捷"),
            groups.map { it.appName }.toSet(),
        )
        val wechat = groups.first { it.appName == "微信" }
        assertEquals(listOf("扫一扫", "收付款"), wechat.items.map { it.label })
        val calc = groups.first { it.appName == "计算器快捷" }
        assertEquals(listOf(desktopCalc.pinKey), calc.items.map { it.pinKey })
        // 拼音排序，不是中文码位排序：微(W) 在 支(Z) 前。
        assertTrue(
            "分组必须按拼音排序",
            groups.map { it.appName }.indexOf("微信") < groups.map { it.appName }.indexOf("支付宝"),
        )
    }

    @Test
    fun letterIndexAlwaysStartsWithPinnedAndMatchesTheGroupedOrder() {
        val model = model()
        injectShortcuts(model, listOf(alipayScan, wechatScan))

        val letters = model.functionLetters
        assertEquals("★", letters.first())
        // 微信(WEIXIN) 在 支付宝(ZHIFUBAO) 之前，与分组顺序一致（传入顺序恰好相反，所以这条测的是排序）。
        assertEquals(listOf("★", "W", "Z"), letters)
    }

    @Test
    fun pinnedFunctionsFollowTheTapOrderAndIgnoreUnknownKeys() {
        val model = model()
        injectShortcuts(model, listOf(wechatScan, alipayScan, wechatPay))
        injectPins(model, listOf(alipayScan.pinKey, "com.gone#x", wechatScan.pinKey))

        assertEquals(
            "已选区按用户点选的顺序排；枚举里没有的键也不能凭空消失（面板里认不出来就是这种情形）",
            listOf(alipayScan.pinKey, "com.gone#x", wechatScan.pinKey),
            model.pinnedFunctions.map { it.pinKey },
        )
    }

    /**
     * 面板（SystemUI）的枚举结果里未必有用户选的那条 —— 它靠宿主给的详情来画。
     * 用户 2026-10-13 报「功能不显示在扇形面板」，根因就在这里：认不出来的键被静默丢掉了。
     */
    @Test
    fun pinnedFunctionsFallBackToTheHostProvidedDetailsThenToAPlaceholder() {
        val fromDetails = ShortcutItem(
            id = "telegram_group_42",
            packageName = "org.telegram.messenger",
            label = "群聊",
            subLabel = "Telegram",
            type = ShortcutType.APP_SHORTCUT,
            intentUri = "intent:#Intent;action=VIEW;end",
        )
        val model = model(
            // 面板就是这么拿的：键来自共享组，详情（名称/宿主/Intent）来自 App 发布的那份。
            functionPinsOf = { listOf(wechatScan.pinKey, fromDetails.pinKey, "com.unknown#thing") },
            pinnedFunctionItems = { listOf(fromDetails) },
        )
        injectShortcuts(model, listOf(wechatScan))
        model.refreshFunctionPins()

        assertEquals(
            listOf(wechatScan.pinKey, fromDetails.pinKey, "com.unknown#thing"),
            model.pinnedFunctions.map { it.pinKey },
        )
        // 详情优先于占位：标题与启动 Intent 都来自详情。
        val resolved = model.pinnedFunctions.first { it.pinKey == fromDetails.pinKey }
        assertEquals("群聊", resolved.label)
        assertEquals("intent:#Intent;action=VIEW;end", resolved.intentUri)
        // 自己枚举到的条目优先于详情（它带着 ShortcutInfo，面板能用 LauncherApps 直接启动）。
        val own = model.pinnedFunctions.first { it.pinKey == wechatScan.pinKey }
        assertEquals(wechatScan.label, own.label)
        // 谁都没有的那条至少还看得见、能被取消（标题退回 id，包名可还原）。
        val placeholder = model.pinnedFunctions.first { it.pinKey == "com.unknown#thing" }
        assertEquals("thing", placeholder.label)
        assertEquals("com.unknown", placeholder.packageName)
    }

    @Test
    fun searchingFiltersBothTheCatalogueAndThePinnedList() {
        val model = model()
        injectShortcuts(model, listOf(wechatScan, wechatPay, alipayScan))
        injectPins(model, listOf(wechatPay.pinKey))

        model.startSearch()
        model.searchQuery = "扫一扫"

        assertEquals(
            listOf(wechatScan.pinKey, alipayScan.pinKey),
            model.groupedFunctions.flatMap { it.items }.map { it.pinKey },
        )
        assertEquals("已选里没有命中的项就不显示", emptyList<String>(), model.pinnedFunctions.map { it.pinKey })
    }

    @Test
    fun functionDragReordersAndPersistsOnlyOnDragEnd() {
        var stored = listOf(wechatScan.pinKey, wechatPay.pinKey, alipayScan.pinKey)
        var storedOrder = emptyList<PinnedKey>()
        val model = model(
            functionPinsOf = { stored },
            onFunctionOrderChange = { stored = it },
            pinnedOrderOf = { storedOrder },
            onPinnedOrderChange = { storedOrder = it },
        )
        model.refreshFunctionPins()

        model.movePinnedItem(
            PinnedKey.Function(wechatScan.pinKey),
            PinnedKey.Function(alipayScan.pinKey),
        )

        assertEquals(
            "功能格换位后，内存里的混排顺序先变",
            listOf(wechatPay.pinKey, alipayScan.pinKey, wechatScan.pinKey),
            model.pinnedOrder.filterIsInstance<PinnedKey.Function>().map { it.pinKey },
        )
        assertEquals(
            "拖动过程中不能每帧写 prefs，顺序要等松手才落盘",
            listOf(wechatScan.pinKey, wechatPay.pinKey, alipayScan.pinKey),
            stored,
        )
        assertEquals(emptyList<PinnedKey>(), storedOrder)

        model.persistPinnedOrder()
        assertEquals(listOf(wechatPay.pinKey, alipayScan.pinKey, wechatScan.pinKey), stored)
        assertEquals(
            listOf(wechatPay.pinKey, alipayScan.pinKey, wechatScan.pinKey),
            storedOrder.filterIsInstance<PinnedKey.Function>().map { it.pinKey },
        )
    }
}
