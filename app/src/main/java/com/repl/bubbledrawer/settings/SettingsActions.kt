package com.repl.bubbledrawer.settings

import android.content.Context
import com.repl.bubbledrawer.xposed.SettingsStore

/**
 * Write-path of the settings page, hoisted out of the composable tree so rows keep
 * stable parameters (docs/ui-guidelines.md: callbacks should not be recreated per
 * recomposition). Every method writes through [SettingsStore], which mirrors the local
 * file into the LSPosed remote group in the same call.
 */
interface SettingsActions {
    fun setEnabled(on: Boolean)
    fun setTriggerPos(pos: Int)
    fun setRangeDp(dp: Int)
    fun setBottomDp(dp: Int)
    fun setEdgeDp(dp: Int)
    fun setFreeform(on: Boolean)
    fun setPanelWidthPct(pct: Int)
    fun setPanelHeightPct(pct: Int)
    fun setPanelIconDp(dp: Int)
    fun setPanelTextSp(sp: Int)
    fun setPanelDismissOutside(mode: Int)
    fun setFanIconCount(count: Int)
    fun setFanRadiusDp(dp: Int)
    fun setFanAutoFillRecommend(enabled: Boolean)
    fun setRecommendEnabled(enabled: Boolean)
    fun setRecommendCount(count: Int)
    fun openManage(context: Context)
}

/** The one implementation; created once in [MainActivity] and remembered there. */
class StoreSettingsActions(private val context: Context) : SettingsActions {
    override fun setEnabled(on: Boolean) = SettingsStore.setEnabled(context, on)
    override fun setTriggerPos(pos: Int) = SettingsStore.setTriggerPos(context, pos)
    override fun setRangeDp(dp: Int) = SettingsStore.setRangeDp(context, dp)
    override fun setBottomDp(dp: Int) = SettingsStore.setBottomDp(context, dp)
    override fun setEdgeDp(dp: Int) = SettingsStore.setEdgeDp(context, dp)
    override fun setFreeform(on: Boolean) = SettingsStore.setFreeform(context, on)
    override fun setPanelWidthPct(pct: Int) = SettingsStore.setPanelWidthPct(context, pct)
    override fun setPanelHeightPct(pct: Int) = SettingsStore.setPanelHeightPct(context, pct)
    override fun setPanelIconDp(dp: Int) = SettingsStore.setPanelIconDp(context, dp)
    override fun setPanelTextSp(sp: Int) = SettingsStore.setPanelTextSp(context, sp)
    override fun setPanelDismissOutside(mode: Int) = SettingsStore.setPanelDismissOutside(context, mode)
    override fun setFanIconCount(count: Int) = SettingsStore.setFanIconCount(context, count)
    override fun setFanRadiusDp(dp: Int) = SettingsStore.setFanRadiusDp(context, dp)
    override fun setFanAutoFillRecommend(enabled: Boolean) = SettingsStore.setFanAutoFillRecommend(context, enabled)
    override fun setRecommendEnabled(enabled: Boolean) = SettingsStore.setRecommendEnabled(context, enabled)
    override fun setRecommendCount(count: Int) = SettingsStore.setRecommendCount(context, count)
    override fun openManage(context: Context) {
        context.startActivity(
            android.content.Intent(context, com.repl.bubbledrawer.pin.PinManageActivity::class.java),
        )
    }
}
