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

    /** 扇形层数：1 = 单层，2 = 双层（内层 = 图标数量 + 更多，外层比内层多 2 个应用）。 */
    fun setFanLayers(layers: Int)
    fun setFanRadiusDp(dp: Int)
    fun setFanShowVibrate(enabled: Boolean)
    fun setFanAutoRetractSec(sec: Int)
    /** 扇形「双击外部关闭」（默认关 = 单击即收起；竖直滑永远是翻页，走不到这里）。 */
    fun setFanDoubleTapDismiss(enabled: Boolean)

    /** 角落手势黑名单：这些应用在前台时角落手势不响应（用户 2026-10-18）。 */
    fun setFanBlacklist(packages: Set<String>)

    /** 横屏时角落手势要滑两次才打开扇形（用户 2026-10-18）。 */
    fun setFanLandscapeTwoSwipes(enabled: Boolean)
    fun setFanIconDp(dp: Int)
    fun setFanIconShape(shape: Int)
    /** 外圈半径单独控制：开关 + 滑条（滑条值低于内圈时自动回到内圈半径）。 */
    fun setFanOuterRadiusEnabled(enabled: Boolean)
    fun setFanOuterRadiusDp(dp: Int)
    fun setCloneBadge(enabled: Boolean)
    fun setFanAutoFillRecommend(enabled: Boolean)
    fun setFanPressurePageTurn(enabled: Boolean)
    fun setFanPressureSensitivity(sensitivity: Int)

    /** 气压重按校准：开始 / 取消 / 清除（状态与采集见 SettingsStore 与 FanHost 的会话）。 */
    fun startPressureCalibration()
    fun cancelPressureCalibration()
    fun clearPressureCalibration()
    fun setRecommendEnabled(enabled: Boolean)
    fun setRecommendCount(count: Int)
    fun setFlymeFreeformEnabled(enabled: Boolean)
    fun setFlymeFreeformCenter(enabled: Boolean)
    fun setFlymeFreeformVerticalPct(pct: Int)
    fun setFlymeFreeformNoOffset(enabled: Boolean)
    fun setFlymeFreeformScale(scale: Int)
    fun setFlymeFreeformOutsideDismiss(enabled: Boolean)
    fun setFlymeFreeformOutsideDismissAction(action: Int)
    fun setFlymeFreeformSwipeUpMini(enabled: Boolean)
    fun setFlymeFreeformSwipeUpHoldFree(enabled: Boolean)
    fun setFlymeFreeformDimBg(enabled: Boolean)
    fun setFlymeMiniMenu(enabled: Boolean)
    fun setFlymeMiniEnlargePct(pct: Int)
    fun setHomeKeepFreeform(enabled: Boolean)
    fun setContactBar(enabled: Boolean)
    fun setContactBarMinWidthPct(pct: Int)

    /** 轻量打开：总开关 + 六个分项 + 应用间跳转的模式与名单。 */
    fun setLightOpenEnabled(enabled: Boolean)
    fun setLightOpenNotifPortrait(enabled: Boolean)
    fun setLightOpenNotifLandscape(enabled: Boolean)
    fun setLightOpenControlCenter(enabled: Boolean)
    fun setLightOpenShare(enabled: Boolean)
    fun setLightOpenText(enabled: Boolean)
    fun setLightOpenJump(enabled: Boolean)
    fun setLightOpenJumpMode(mode: Int)
    fun setLightOpenJumpList(packages: Set<String>)

    fun openManage(context: Context)

    /** 重启系统界面：模块的 hook 都在那个进程里，热重载只重建模块自己的运行时（见 [SystemUiRestart]）。 */
    fun restartSystemUi()
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
    override fun setFanLayers(layers: Int) = SettingsStore.setFanLayers(context, layers)
    override fun setFanRadiusDp(dp: Int) = SettingsStore.setFanRadiusDp(context, dp)
    override fun setFanShowVibrate(enabled: Boolean) = SettingsStore.setFanShowVibrate(context, enabled)
    override fun setFanAutoRetractSec(sec: Int) = SettingsStore.setFanAutoRetractSec(context, sec)
    override fun setFanDoubleTapDismiss(enabled: Boolean) = SettingsStore.setFanDoubleTapDismiss(context, enabled)
    override fun setFanBlacklist(packages: Set<String>) = SettingsStore.setFanBlacklist(context, packages)
    override fun setFanLandscapeTwoSwipes(enabled: Boolean) =
        SettingsStore.setFanLandscapeTwoSwipes(context, enabled)
    override fun setFanIconDp(dp: Int) = SettingsStore.setFanIconDp(context, dp)
    override fun setFanIconShape(shape: Int) = SettingsStore.setFanIconShape(context, shape)
    override fun setFanOuterRadiusEnabled(enabled: Boolean) = SettingsStore.setFanOuterRadiusEnabled(context, enabled)
    override fun setFanOuterRadiusDp(dp: Int) = SettingsStore.setFanOuterRadiusDp(context, dp)
    override fun setCloneBadge(enabled: Boolean) = SettingsStore.setCloneBadge(context, enabled)
    override fun setFanAutoFillRecommend(enabled: Boolean) = SettingsStore.setFanAutoFillRecommend(context, enabled)
    override fun setFanPressurePageTurn(enabled: Boolean) = SettingsStore.setFanPressurePageTurn(context, enabled)
    override fun setFanPressureSensitivity(sensitivity: Int) = SettingsStore.setFanPressureSensitivity(context, sensitivity)
    override fun startPressureCalibration() = SettingsStore.startPressureCalibration(context)
    override fun cancelPressureCalibration() = SettingsStore.cancelPressureCalibration(context)
    override fun clearPressureCalibration() = SettingsStore.clearPressureCalibration(context)
    override fun setRecommendEnabled(enabled: Boolean) = SettingsStore.setRecommendEnabled(context, enabled)
    override fun setRecommendCount(count: Int) = SettingsStore.setRecommendCount(context, count)
    override fun setFlymeFreeformEnabled(enabled: Boolean) = SettingsStore.setFlymeFreeformEnabled(context, enabled)
    override fun setFlymeFreeformCenter(enabled: Boolean) = SettingsStore.setFlymeFreeformCenter(context, enabled)
    override fun setFlymeFreeformVerticalPct(pct: Int) = SettingsStore.setFlymeFreeformVerticalPct(context, pct)
    override fun setFlymeFreeformNoOffset(enabled: Boolean) = SettingsStore.setFlymeFreeformNoOffset(context, enabled)
    override fun setFlymeFreeformScale(scale: Int) = SettingsStore.setFlymeFreeformScale(context, scale)
    override fun setFlymeFreeformOutsideDismiss(enabled: Boolean) = SettingsStore.setFlymeFreeformOutsideDismiss(context, enabled)
    override fun setFlymeFreeformOutsideDismissAction(action: Int) = SettingsStore.setFlymeFreeformOutsideDismissAction(context, action)
    override fun setFlymeFreeformSwipeUpMini(enabled: Boolean) = SettingsStore.setFlymeFreeformSwipeUpMini(context, enabled)
    override fun setFlymeFreeformSwipeUpHoldFree(enabled: Boolean) = SettingsStore.setFlymeFreeformSwipeUpHoldFree(context, enabled)
    override fun setFlymeFreeformDimBg(enabled: Boolean) = SettingsStore.setFlymeFreeformDimBg(context, enabled)
    override fun setFlymeMiniMenu(enabled: Boolean) = SettingsStore.setFlymeMiniMenu(context, enabled)
    override fun setFlymeMiniEnlargePct(pct: Int) = SettingsStore.setFlymeMiniEnlargePct(context, pct)
    override fun setHomeKeepFreeform(enabled: Boolean) = SettingsStore.setHomeKeepFreeform(context, enabled)
    override fun setContactBar(enabled: Boolean) = SettingsStore.setContactBar(context, enabled)
    override fun setContactBarMinWidthPct(pct: Int) = SettingsStore.setContactBarMinWidthPct(context, pct)
    override fun setLightOpenEnabled(enabled: Boolean) = SettingsStore.setLightOpenEnabled(context, enabled)
    override fun setLightOpenNotifPortrait(enabled: Boolean) = SettingsStore.setLightOpenNotifPortrait(context, enabled)
    override fun setLightOpenNotifLandscape(enabled: Boolean) = SettingsStore.setLightOpenNotifLandscape(context, enabled)
    override fun setLightOpenControlCenter(enabled: Boolean) = SettingsStore.setLightOpenControlCenter(context, enabled)
    override fun setLightOpenShare(enabled: Boolean) = SettingsStore.setLightOpenShare(context, enabled)
    override fun setLightOpenText(enabled: Boolean) = SettingsStore.setLightOpenText(context, enabled)
    override fun setLightOpenJump(enabled: Boolean) = SettingsStore.setLightOpenJump(context, enabled)
    override fun setLightOpenJumpMode(mode: Int) = SettingsStore.setLightOpenJumpMode(context, mode)
    override fun setLightOpenJumpList(packages: Set<String>) = SettingsStore.setLightOpenJumpList(context, packages)
    override fun openManage(context: Context) {
        context.startActivity(
            android.content.Intent(context, com.repl.bubbledrawer.pin.PinManageActivity::class.java),
        )
    }

    override fun restartSystemUi() = SystemUiRestart.restart(context)
}
