package com.repl.bubbledrawer.pin

import androidx.compose.runtime.MutableState
import com.repl.bubbledrawer.data.PinnedRef
import com.repl.bubbledrawer.pinyin.BubbleApp
import com.repl.bubbledrawer.shortcut.ShortcutItem
import com.repl.bubbledrawer.shortcut.ShortcutType
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用收藏与功能收藏在同一个收藏区里混排：功能不能被强制排在所有应用之后。
 */
class PinManageModelMixedOrderTest {

    private val wechat = BubbleApp("com.tencent.mm", "微信", sortKey = "WEIXIN", initials = "WX")
    private val qq = BubbleApp("com.tencent.mobileqq", "QQ", sortKey = "QQ", initials = "QQ")
    private val pay = ShortcutItem(
        id = "wechat_pay",
        packageName = "com.tencent.mm",
        label = "收付款",
        subLabel = "微信",
        type = ShortcutType.APP_SHORTCUT,
    )

    private val wechatKey = PinnedKey.App(wechat.packageName, wechat.userId)
    private val qqKey = PinnedKey.App(qq.packageName, qq.userId)
    private val payKey = PinnedKey.Function(pay.pinKey)

    private fun injectApps(model: PinManageModel, apps: List<BubbleApp>) {
        val field = PinManageModel::class.java.declaredFields.first { it.name.startsWith("apps") }
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = field.get(model) as MutableState<kotlinx.collections.immutable.ImmutableList<BubbleApp>>
        state.value = persistentListOf(*apps.toTypedArray())
    }

    @Test
    fun functionCanBeDraggedAboveAnAppAndTheMixedOrderIsPersisted() {
        var storedPins = listOf(PinnedRef(wechat.packageName, wechat.userId), PinnedRef(qq.packageName, qq.userId))
        var storedFunctions = listOf(pay.pinKey)
        var storedOrder = listOf(wechatKey, qqKey, payKey)
        val model = PinManageModel(
            loadApps = { persistentListOf() },
            pinsOf = { storedPins },
            onOrderChange = { storedPins = it },
            recommendConfig = { false to 0 },
            functionPinsOf = { storedFunctions },
            pinnedFunctionItems = { listOf(pay) },
            onFunctionOrderChange = { storedFunctions = it },
            pinnedOrderOf = { storedOrder },
            onPinnedOrderChange = { storedOrder = it },
        )
        injectApps(model, listOf(wechat, qq))
        model.refreshPins()

        assertEquals(listOf(wechatKey, qqKey, payKey), model.visiblePinnedKeys())

        model.movePinnedItem(payKey, wechatKey)

        assertEquals(
            "功能可以排到应用前面，不再被固定追加在末尾",
            listOf(payKey, wechatKey, qqKey),
            model.visiblePinnedKeys(),
        )
        assertEquals("拖动过程中不写 prefs", listOf(wechatKey, qqKey, payKey), storedOrder)

        model.persistPinnedOrder()

        assertEquals(listOf(payKey, wechatKey, qqKey), storedOrder)
        assertEquals(
            "应用列表内部仍按混排后的相对顺序保存",
            listOf(
                PinnedRef(wechat.packageName, wechat.userId),
                PinnedRef(qq.packageName, qq.userId),
            ),
            storedPins,
        )
        assertEquals(listOf(pay.pinKey), storedFunctions)
    }

    @Test
    fun legacyLayoutKeepsNewAppsBeforePinnedFunctions() {
        var storedPins = listOf(PinnedRef(qq.packageName, qq.userId))
        var storedFunctions = listOf(pay.pinKey)
        var storedOrder = listOf(qqKey, payKey)
        val model = PinManageModel(
            loadApps = { persistentListOf() },
            pinsOf = { storedPins },
            onOrderChange = { storedPins = it },
            recommendConfig = { false to 0 },
            functionPinsOf = { storedFunctions },
            pinnedFunctionItems = { listOf(pay) },
            onFunctionOrderChange = { storedFunctions = it },
            pinnedOrderOf = { storedOrder },
            onPinnedOrderChange = { storedOrder = it },
        )
        injectApps(model, listOf(wechat, qq))
        model.refreshPins()

        model.togglePin(wechat)

        assertEquals(listOf(qqKey, wechatKey, payKey), storedOrder)
        assertEquals(
            listOf(PinnedRef(qq.packageName, qq.userId), PinnedRef(wechat.packageName, wechat.userId)),
            storedPins,
        )
    }

    @Test
    fun afterAMixedOrderNewAppsAppendAtTheEnd() {
        var storedPins = listOf(PinnedRef(qq.packageName, qq.userId))
        var storedFunctions = listOf(pay.pinKey)
        var storedOrder = listOf(payKey, qqKey)
        val model = PinManageModel(
            loadApps = { persistentListOf() },
            pinsOf = { storedPins },
            onOrderChange = { storedPins = it },
            recommendConfig = { false to 0 },
            functionPinsOf = { storedFunctions },
            pinnedFunctionItems = { listOf(pay) },
            onFunctionOrderChange = { storedFunctions = it },
            pinnedOrderOf = { storedOrder },
            onPinnedOrderChange = { storedOrder = it },
        )
        injectApps(model, listOf(wechat, qq))
        model.refreshPins()

        model.togglePin(wechat)

        assertEquals(listOf(payKey, qqKey, wechatKey), storedOrder)
    }

    @Test
    fun draggingAnAppAcrossAFunctionUpdatesTheAppSubsequenceToo() {
        var storedPins = listOf(PinnedRef(wechat.packageName, wechat.userId), PinnedRef(qq.packageName, qq.userId))
        var storedFunctions = listOf(pay.pinKey)
        var storedOrder = listOf(wechatKey, payKey, qqKey)
        val model = PinManageModel(
            loadApps = { persistentListOf() },
            pinsOf = { storedPins },
            onOrderChange = { storedPins = it },
            recommendConfig = { false to 0 },
            functionPinsOf = { storedFunctions },
            pinnedFunctionItems = { listOf(pay) },
            onFunctionOrderChange = { storedFunctions = it },
            pinnedOrderOf = { storedOrder },
            onPinnedOrderChange = { storedOrder = it },
        )
        injectApps(model, listOf(wechat, qq))
        model.refreshPins()

        model.movePinnedItem(qqKey, wechatKey)
        model.persistPinnedOrder()

        assertEquals(
            "QQ 越过微信后，应用收藏链的顺序也要跟着变",
            listOf(qqKey, wechatKey),
            storedPins.map { PinnedKey.App(it.packageName, it.userId) },
        )
        assertEquals(listOf(qqKey, wechatKey, payKey), storedOrder)
        assertTrue("功能收藏没有被应用重排挤掉", storedFunctions.contains(pay.pinKey))
    }
}
