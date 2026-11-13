package com.repl.bubbledrawer.xposed

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.Immutable
import com.repl.bubbledrawer.bubble.PressureCalibrationState
import com.repl.bubbledrawer.data.PinBackend
import com.repl.bubbledrawer.data.PinCodec
import com.repl.bubbledrawer.data.PinnedRef
import com.repl.bubbledrawer.data.PinsSync
import com.repl.bubbledrawer.data.PrefsPinBackend
import com.repl.bubbledrawer.shortcut.FunctionPinDetails
import com.repl.bubbledrawer.shortcut.FunctionPinStore
import com.repl.bubbledrawer.shortcut.ShortcutCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/**
 * Immutable UI state for the settings page: one snapshot of the cross-process config
 * (docs/ui-guidelines.md "强跳过友好的状态形状" — @Immutable, scalar fields only, no
 * List-typed fields that would defeat skipping).
 */
@Immutable
data class SettingsUiState(
    val snapshot: RemotePrefs.Snapshot,
    /** 0 both / 1 left / 2 right — the old page's trigger_pos, kept for parity. */
    val triggerPos: Int,
    val connected: Boolean,
    val frameworkLabel: String?,
)

/**
 * Single facade over the settings values for the APP process. The local "cfg"
 * file is the always-available mirror (the page works before LSPosed connects);
 * every mutation is written through to the REMOTE group immediately when bound,
 * so the live SystemUI monitor reacts in real time (RemoteBridge.syncLocal
 * re-pushes everything on first bind).
 */
object SettingsStore {

    private const val TAG = "BubbleDrawer"

    /** trigger_pos semantics (kept from the old page): 0 both, 1 left, 2 right. */
    const val POS_BOTH = 0
    const val POS_LEFT = 1
    const val POS_RIGHT = 2

    fun snapshot(context: Context): RemotePrefs.Snapshot =
        RemotePrefs.read(RemoteBridge.remote ?: fallbackMirror(context))

    /** Offline mirror file (used when the framework is not connected). */
    private fun fallbackMirror(context: Context) = RemoteBridge.local(context)

    fun triggerPos(context: Context): Int =
        RemoteBridge.local(context).getInt("trigger_pos", POS_BOTH)

    fun setEnabled(context: Context, on: Boolean) {
        RemoteBridge.local(context).edit().putBoolean(RemotePrefs.KEY_ENABLED, on).apply()
        RemoteBridge.remote?.edit()?.putBoolean(RemotePrefs.KEY_ENABLED, on)?.apply()
    }

    fun setTriggerPos(context: Context, pos: Int) {
        val p = pos.coerceIn(POS_BOTH, POS_RIGHT)
        RemoteBridge.local(context).edit()
            .putInt("trigger_pos", p)
            .putBoolean(RemotePrefs.KEY_LEFT, p != POS_RIGHT)
            .putBoolean(RemotePrefs.KEY_RIGHT, p != POS_LEFT)
            .apply()
        RemoteBridge.remote?.edit()
            ?.putBoolean(RemotePrefs.KEY_LEFT, p != POS_RIGHT)
            ?.putBoolean(RemotePrefs.KEY_RIGHT, p != POS_LEFT)
            ?.apply()
    }

    fun setRangeDp(context: Context, dp: Int) {
        val v = dp.coerceIn(RemotePrefs.MIN_DP, RemotePrefs.MAX_DP)
        RemoteBridge.local(context).edit().putInt(RemotePrefs.KEY_RANGE_DP, v).apply()
        RemoteBridge.remote?.edit()?.putInt(RemotePrefs.KEY_RANGE_DP, v)?.apply()
    }

    /** Trigger region axis along the bottom edge (dp). */
    fun setBottomDp(context: Context, dp: Int) {
        val v = dp.coerceIn(RemotePrefs.MIN_DP, RemotePrefs.MAX_DP)
        RemoteBridge.local(context).edit().putInt(RemotePrefs.KEY_BOTTOM_DP, v).apply()
        RemoteBridge.remote?.edit()?.putInt(RemotePrefs.KEY_BOTTOM_DP, v)?.apply()
    }

    /** Trigger region axis up the side edge (dp). */
    fun setEdgeDp(context: Context, dp: Int) {
        val v = dp.coerceIn(RemotePrefs.MIN_DP, RemotePrefs.MAX_DP)
        RemoteBridge.local(context).edit().putInt(RemotePrefs.KEY_EDGE_DP, v).apply()
        RemoteBridge.remote?.edit()?.putInt(RemotePrefs.KEY_EDGE_DP, v)?.apply()
    }

    /** 更多面板宽/高（占屏幕百分比；0 = 默认 62 %，面板始终居中）。 */
    fun setPanelWidthPct(context: Context, pct: Int) = putInt(context, RemotePrefs.KEY_PANEL_W_PCT, pct)
    fun setPanelHeightPct(context: Context, pct: Int) = putInt(context, RemotePrefs.KEY_PANEL_H_PCT, pct)
    /** 更多面板图标 dp / 文字 sp（0 = 布局默认）。 */
    fun setPanelIconDp(context: Context, dp: Int) = putInt(context, RemotePrefs.KEY_PANEL_ICON_DP, dp)
    fun setPanelTextSp(context: Context, sp: Int) = putInt(context, RemotePrefs.KEY_PANEL_TEXT_SP, sp)

    /** 更多面板点击外部收起方式（0 = 单击，1 = 双击）。 */
    fun setPanelDismissOutside(context: Context, mode: Int) = putInt(context, RemotePrefs.KEY_PANEL_DISMISS_OUTSIDE, mode.coerceIn(0, 1))

    /** 扇形面板应用图标数量（5 或 6）。 */
    fun setFanIconCount(context: Context, count: Int) = putInt(context, RemotePrefs.KEY_FAN_ICON_COUNT, count.coerceIn(5, 6))

    /** 扇形层数（1 = 单层，2 = 双层）。 */
    fun setFanLayers(context: Context, layers: Int) =
        putInt(context, RemotePrefs.KEY_FAN_LAYERS, layers.coerceIn(RemotePrefs.FAN_LAYERS_SINGLE, RemotePrefs.FAN_LAYERS_DOUBLE))

    /** 展开扇形半径（dp）—— 双层时它是**内圈**半径（外圈见 [setFanOuterRadiusDp]）。 */
    fun setFanRadiusDp(context: Context, dp: Int) {
        val inner = dp.coerceIn(RemotePrefs.FAN_RADIUS_MIN, RemotePrefs.FAN_RADIUS_MAX)
        putInt(context, RemotePrefs.KEY_FAN_RADIUS_DP, inner)
        // 内圈顶到外圈上面时把外圈一起抬起来：读侧也会夹（`RemotePrefs.read`），这里落盘是为了
        // 不留一个"比内圈还小"的旧值 —— 否则用户之后再调小内圈，外圈会突然缩回那个旧值。
        val storedOuter = RemoteBridge.local(context).getInt(
            RemotePrefs.KEY_FAN_OUTER_RADIUS_DP,
            RemotePrefs.DEFAULT_FAN_OUTER_RADIUS_DP,
        )
        if (storedOuter < inner) putInt(context, RemotePrefs.KEY_FAN_OUTER_RADIUS_DP, inner)
    }

    /** 外圈半径单独控制（见 `RemotePrefs.KEY_FAN_OUTER_RADIUS_ENABLED`）。 */
    fun setFanOuterRadiusEnabled(context: Context, enabled: Boolean) {
        RemoteBridge.local(context).edit().putBoolean(RemotePrefs.KEY_FAN_OUTER_RADIUS_ENABLED, enabled).apply()
        RemoteBridge.remote?.edit()?.putBoolean(RemotePrefs.KEY_FAN_OUTER_RADIUS_ENABLED, enabled)?.apply()
    }

    /**
     * 外圈半径（dp）。**落盘时就按内圈夹一次**：滑条滑到比内圈更小 → 存下来的就是内圈半径
     * （用户 2026-10-16：「滑到了比内圈半径更小的会自动弹回内圈半径」）。
     */
    fun setFanOuterRadiusDp(context: Context, dp: Int) {
        val inner = RemotePrefs.read(RemoteBridge.local(context)).fanRadiusDp
        val outer = dp.coerceIn(RemotePrefs.FAN_OUTER_RADIUS_MIN, RemotePrefs.FAN_OUTER_RADIUS_MAX)
            .coerceAtLeast(inner)
        putInt(context, RemotePrefs.KEY_FAN_OUTER_RADIUS_DP, outer)
    }

    /** 扇形滑出时振动一下。 */
    fun setFanShowVibrate(context: Context, enabled: Boolean) {
        RemoteBridge.local(context).edit().putBoolean(RemotePrefs.KEY_FAN_SHOW_VIBRATE, enabled).apply()
        RemoteBridge.remote?.edit()?.putBoolean(RemotePrefs.KEY_FAN_SHOW_VIBRATE, enabled)?.apply()
    }

    /** 扇形滑出后不点选自动收回的秒数（0 = 不自动收回）。 */
    fun setFanAutoRetractSec(context: Context, sec: Int) =
        putInt(context, RemotePrefs.KEY_FAN_AUTO_RETRACT_SEC, sec.coerceIn(RemotePrefs.FAN_AUTO_RETRACT_MIN, RemotePrefs.FAN_AUTO_RETRACT_MAX))

    /** 扇形「双击外部关闭」开关（默认关 = 单击即收起）。 */
    fun setFanDoubleTapDismiss(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_FAN_DOUBLE_TAP_DISMISS, enabled)

    /** 角落手势黑名单（用户 2026-10-18）。存换行分隔的包名，见 [encodeFanBlacklist]。 */
    fun setFanBlacklist(context: Context, packages: Set<String>) =
        putString(context, RemotePrefs.KEY_FAN_BLACKLIST, encodeFanBlacklist(packages))

    /** 横屏时需滑动两次才打开扇形（用户 2026-10-18）。 */
    fun setFanLandscapeTwoSwipes(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_FAN_LANDSCAPE_TWO_SWIPES, enabled)

    /**
     * 「下一次打开这个应用请给小窗」的一次性口令（见 [RemotePrefs.KEY_LAUNCH_PRIME]）。
     *
     * 由 App 进程在启动前写、system_server 的闸门读。它是 `GateLaunchStrategy` 的**第二通道**：
     * Intent 上的标记若被安全中心的拦截框重造时丢掉，用户点「允许」之后的那一次启动还能靠它对上。
     */
    fun primeFreeformLaunch(context: Context, packageName: String) {
        if (packageName.isEmpty()) return
        val value = RemotePrefs.encodeLaunchPrime(
            packageName,
            android.os.SystemClock.elapsedRealtime() + RemotePrefs.LAUNCH_PRIME_TTL_MS,
        )
        RemoteBridge.local(context).edit().putString(RemotePrefs.KEY_LAUNCH_PRIME, value).apply()
        RemoteBridge.remote?.edit()?.putString(RemotePrefs.KEY_LAUNCH_PRIME, value)?.apply()
    }

    /** 扇形图标大小（dp）。 */
    fun setFanIconDp(context: Context, dp: Int) =
        putInt(context, RemotePrefs.KEY_FAN_ICON_DP, dp.coerceIn(RemotePrefs.FAN_ICON_MIN, RemotePrefs.FAN_ICON_MAX))

    /** 扇形图标形状（0 = 圆形，1 = 系统样式圆角矩形）。 */
    fun setFanIconShape(context: Context, shape: Int) =
        putInt(context, RemotePrefs.KEY_FAN_ICON_SHAPE, shape.coerceIn(0, 1))

    /**
     * 分身应用图标上的角标。图标本身永远取主空间那张，这里只决定要不要叠角标 —— 所以这个开关
     * 不影响图标来源，只影响 `AppRepository` 的缓存键与绘制。
     */
    fun setCloneBadge(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_CLONE_BADGE, enabled)

    /** 扇形未摆满时用推荐应用填满开关。 */
    fun setFanAutoFillRecommend(context: Context, enabled: Boolean) {
        RemoteBridge.local(context).edit().putBoolean(RemotePrefs.KEY_FAN_AUTO_FILL_RECOMMEND, enabled).apply()
        RemoteBridge.remote?.edit()?.putBoolean(RemotePrefs.KEY_FAN_AUTO_FILL_RECOMMEND, enabled)?.apply()
    }

    /** 扇形重按翻页开关与灵敏度。 */
    fun setFanPressurePageTurn(context: Context, enabled: Boolean) {
        RemoteBridge.local(context).edit().putBoolean(RemotePrefs.KEY_FAN_PRESSURE_PAGE_TURN, enabled).apply()
        RemoteBridge.remote?.edit()?.putBoolean(RemotePrefs.KEY_FAN_PRESSURE_PAGE_TURN, enabled)?.apply()
    }

    fun setFanPressureSensitivity(context: Context, sensitivity: Int) =
        putInt(context, RemotePrefs.KEY_FAN_PRESSURE_SENSITIVITY, sensitivity.coerceIn(0, 2))

    // ---------------- 气压重按校准（参考实现 com.oxohang.fanfreeform 的「按压手势 → 气压校准」） ----------------

    /**
     * 请求开始一次校准会话。只写一个 `active = true`（外加把上次的进度清零），真正的采集在
     * SystemUI 的 [com.repl.bubbledrawer.xposed.FanHost] 里：它监听这个键，开一个会话，按满 5 次
     * 后把结果经 [PressureCalibrationBridge] 广播回来。
     *
     * 为什么用「一个布尔键当请求」而不是广播：App → SystemUI 这条方向 prefs 是通的（本地写 + 远程
     * 组镜像），而 hook 进程反过来写不了远程组（只读镜像）—— 两个方向各用自己能用的通道。
     */
    fun startPressureCalibration(context: Context) {
        val next = PressureCalibrationState.applyEvent(
            pressureCalibrationState(context),
            PressureCalibrationState.Stage.STARTED,
        )
        writePressureCalibrationState(context, next)
        Log.i(TAG, "PRESSURE_CALIBRATION_REQUESTED active=${next.active} calibrated=${next.calibrated}")
    }

    /** 取消会话（用户点「取消校准」/离开页面）：保留已经算出来的阈值，只是不再采集。 */
    fun cancelPressureCalibration(context: Context) {
        val current = pressureCalibrationState(context)
        if (!current.active) return
        writePressureCalibrationState(context, current.copy(active = false))
        Log.i(TAG, "PRESSURE_CALIBRATION_CANCELLED calibrated=${current.calibrated}")
    }

    /** 清除校准结果：回到灵敏度预设（页面上写明了这一点）。 */
    fun clearPressureCalibration(context: Context) {
        writePressureCalibrationState(context, PressureCalibrationState.cleared())
        Log.i(TAG, "PRESSURE_CALIBRATION_CLEARED")
    }

    /**
     * 应用 SystemUI 报回来的一个校准事件（[PressureCalibrationProvider] 与
     * [PressureCalibrationReceiver] 两个入口都走这里）。
     *
     * 状态迁移全在纯函数 [PressureCalibrationState.applyEvent] 里（那条路径有单测），这里只做
     * 「读当前 → 迁移 → 一次写完本地 + 远程」。**一次写完**这点是刻意的：八个字段分几次写，
     * 中间任何一次被读走都会看到自相矛盾的状态 —— 收藏那次拉锯（值与戳分两个键）就是这么来的。
     *
     * `@Synchronized`：provider 的 `call()` 跑在 Binder 线程上，广播接收者在主线程 —— 两个入口
     * 同时到达时，「读-改-写」必须串起来，否则后写的那次会基于旧快照把前一次盖掉。
     */
    @Synchronized
    fun applyPressureCalibrationEvent(
        context: Context,
        stage: String,
        attempts: Int = 0,
        delta: Float = 0f,
        valid: Boolean = false,
        threshold: Float = 0f,
        validCount: Int = 0,
        via: String = "broadcast",
    ) {
        val current = pressureCalibrationState(context)
        val next = PressureCalibrationState.applyEvent(
            state = current,
            stage = stage,
            attempts = attempts,
            delta = delta,
            valid = valid,
            threshold = threshold,
            validCount = validCount,
        )
        if (next == current) {
            // 会话已结束之后迟到的按压、未知阶段、重复投递 —— 不动 prefs（否则页面会回跳）。
            Log.i(TAG, "PRESSURE_CALIBRATION_EVENT_DROPPED via=$via stage=$stage active=${current.active}")
            return
        }
        writePressureCalibrationState(context, next)
        Log.i(
            TAG,
            "PRESSURE_CALIBRATION_EVENT via=$via stage=$stage attempts=${next.attempts}" +
                "/${com.repl.bubbledrawer.bubble.PressureCalibration.REQUIRED_ATTEMPTS}" +
                " validCount=${next.validCount} lastDelta=${next.lastDeltaHpa} lastValid=${next.lastValid}" +
                " active=${next.active} calibrated=${next.calibrated} hpa=${next.calibratedHpa}" +
                " outcome=${next.outcome}",
        )
    }

    /**
     * 打开设置页时的自愈：会话停在「已按满 5 次」却还标着进行中，只可能是**最后一次结果事件没到**
     * （App 只有在收到第 5 次 SAMPLE 之后才会丢掉紧随其后的 RESULT）。
     *
     * 这一条是「投递通道出问题」的最后一道保险：那种情况下 App 手上没有任何样本，算不出阈值，所以
     * 只能把会话收尾成「没成功」，让用户看到失败原因并重试 —— 总好过页面永远停在「取消校准」，
     * 让用户以为功能坏了。旧阈值一律不动（照 [PressureCalibrationState] 的规则）。
     */
    fun recoverStuckPressureCalibration(context: Context) {
        val current = pressureCalibrationState(context)
        if (!current.active) return
        if (current.attempts < com.repl.bubbledrawer.bubble.PressureCalibration.REQUIRED_ATTEMPTS) return
        writePressureCalibrationState(
            context,
            current.copy(active = false, outcome = com.repl.bubbledrawer.bubble.PressureCalibration.Outcome.TIMEOUT),
        )
        Log.w(
            TAG,
            "PRESSURE_CALIBRATION_RECOVERED_STUCK attempts=${current.attempts} " +
                "validCount=${current.validCount} calibrated=${current.calibrated}",
        )
    }

    /**
     * 当前校准状态。读**本地镜像**而不是远程组：App 是这些键的写入方（SystemUI 只读），所以本地
     * 文件永远是较新的那一份；读远程组反而可能在 LSPosed 掉线/镜像延迟时拿回旧值。
     */
    private fun pressureCalibrationState(context: Context): PressureCalibrationState {
        val snap = RemotePrefs.read(fallbackMirror(context))
        return PressureCalibrationState(
            active = snap.fanPressureCalibrationActive,
            calibrated = snap.fanPressureCalibrated,
            calibratedHpa = snap.fanPressureCalibratedHpa,
            attempts = snap.fanPressureCalibrationAttempts,
            validCount = snap.fanPressureCalibrationValidCount,
            lastDeltaHpa = snap.fanPressureCalibrationLastDelta,
            lastValid = snap.fanPressureCalibrationLastValid,
            outcome = snap.fanPressureCalibrationOutcome,
        )
    }

    /** 把校准状态整份写进「本地镜像 + 远程组」，两边看到的是同一份，绝不会只落一半。 */
    private fun writePressureCalibrationState(context: Context, state: PressureCalibrationState) {
        for (prefs in listOfNotNull(RemoteBridge.local(context), RemoteBridge.remote)) {
            prefs.edit()
                .putBoolean(RemotePrefs.KEY_FAN_PRESSURE_CALIBRATED, state.calibrated)
                .putFloat(RemotePrefs.KEY_FAN_PRESSURE_CALIBRATED_HPA, state.calibratedHpa)
                .putBoolean(RemotePrefs.KEY_FAN_PRESSURE_CALIBRATION_ACTIVE, state.active)
                .putInt(RemotePrefs.KEY_FAN_PRESSURE_CALIBRATION_ATTEMPTS, state.attempts)
                .putInt(RemotePrefs.KEY_FAN_PRESSURE_CALIBRATION_VALID_COUNT, state.validCount)
                .putFloat(RemotePrefs.KEY_FAN_PRESSURE_CALIBRATION_LAST_DELTA, state.lastDeltaHpa)
                .putBoolean(RemotePrefs.KEY_FAN_PRESSURE_CALIBRATION_LAST_VALID, state.lastValid)
                .putInt(RemotePrefs.KEY_FAN_PRESSURE_CALIBRATION_OUTCOME, state.outcome)
                .apply()
        }
    }

    /** 更多面板推荐应用开关与数量（4–50，默认 12；手输，不再吸附档位）。 */
    fun setRecommendEnabled(context: Context, enabled: Boolean) {
        RemoteBridge.local(context).edit().putBoolean(RemotePrefs.KEY_RECOMMEND_ENABLED, enabled).apply()
        RemoteBridge.remote?.edit()?.putBoolean(RemotePrefs.KEY_RECOMMEND_ENABLED, enabled)?.apply()
    }

    fun setRecommendCount(context: Context, count: Int) =
        putInt(context, RemotePrefs.KEY_RECOMMEND_COUNT, RemotePrefs.clampRecommendCount(count))

    /** 实验性：Flyme 样式轻量小窗设置 */
    fun setFlymeFreeformEnabled(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_FLYME_FREEFORM_ENABLED, enabled)

    fun setFlymeFreeformCenter(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_FLYME_FREEFORM_CENTER, enabled)

    /** 小窗上下位置：占「上下余量」的百分比，0 = 正中（横屏不生效）。 */
    fun setFlymeFreeformVerticalPct(context: Context, pct: Int) =
        putInt(
            context,
            RemotePrefs.KEY_FLYME_FREEFORM_VERTICAL_PCT,
            pct.coerceIn(RemotePrefs.FLYME_FREEFORM_VERTICAL_MIN, RemotePrefs.FLYME_FREEFORM_VERTICAL_MAX),
        )

    fun setFlymeFreeformNoOffset(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_FLYME_FREEFORM_NO_OFFSET, enabled)

    fun setFlymeFreeformScale(context: Context, scale: Int) =
        putInt(context, RemotePrefs.KEY_FLYME_FREEFORM_SCALE, scale.coerceIn(RemotePrefs.FLYME_FREEFORM_SCALE_MIN, RemotePrefs.FLYME_FREEFORM_SCALE_MAX))

    fun setFlymeFreeformOutsideDismiss(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_FLYME_FREEFORM_OUTSIDE_DISMISS, enabled)

    fun setFlymeFreeformOutsideDismissAction(context: Context, action: Int) =
        putInt(context, RemotePrefs.KEY_FLYME_FREEFORM_OUTSIDE_DISMISS_ACTION, action.coerceIn(0, 1))

    fun setFlymeFreeformSwipeUpMini(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_FLYME_FREEFORM_SWIPE_UP_MINI, enabled)

    fun setFlymeFreeformSwipeUpHoldFree(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_FLYME_FREEFORM_SWIPE_UP_HOLD_FREE, enabled)

    fun setFlymeFreeformDimBg(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_FLYME_FREEFORM_DIM_BG, enabled)

    fun setFlymeMiniMenu(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_FLYME_MINI_MENU, enabled)

    /** 长按菜单「放大」的倍率（百分数），见 [RemotePrefs.KEY_FLYME_MINI_ENLARGE_PCT]。 */
    fun setFlymeMiniEnlargePct(context: Context, pct: Int) =
        putInt(
            context,
            RemotePrefs.KEY_FLYME_MINI_ENLARGE_PCT,
            pct.coerceIn(RemotePrefs.FLYME_MINI_ENLARGE_MIN, RemotePrefs.FLYME_MINI_ENLARGE_MAX),
        )

    /**
     * 「回桌面不收迷你」：拦掉 ROM 在回桌面/进最近任务时把**所有**小窗收成迷你小窗
     * （见 [RemotePrefs.KEY_HOME_KEEP_FREEFORM]）。与 Flyme 样式总开关无关，所以不看它。
     */
    fun setHomeKeepFreeform(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_HOME_KEEP_FREEFORM, enabled)

    /** 小窗底部联系人条（Flyme「联系人头像」移植）。 */
    fun setContactBar(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_CONTACT_BAR, enabled)

    /** 联系人条尺寸阈值（屏幕宽度百分比，0 = 不按尺寸隐藏）。 */
    fun setContactBarMinWidthPct(context: Context, pct: Int) = putInt(
        context,
        RemotePrefs.KEY_CONTACT_BAR_MIN_WIDTH_PCT,
        pct.coerceIn(RemotePrefs.CONTACT_BAR_MIN_WIDTH_MIN, RemotePrefs.CONTACT_BAR_MIN_WIDTH_MAX),
    )

    // ------------------------------------------------------------------ 轻量打开

    /**
     * 「轻量打开」六开关的写入口。判定逻辑不在这里跑：这里只把值同步进 LSPosed 远程组，
     * SystemUI（通知 / 控制中心）与 system_server（分享 / 文本操作 / 应用间跳转）两侧的 hook
     * 都通过 `getRemotePreferences` 读同一份。
     */
    fun setLightOpenEnabled(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_LIGHT_OPEN_ENABLED, enabled)

    /** 竖屏 / 横屏点通知以小窗打开。 */
    fun setLightOpenNotifPortrait(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_LIGHT_OPEN_NOTIF_PORTRAIT, enabled)

    fun setLightOpenNotifLandscape(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_LIGHT_OPEN_NOTIF_LANDSCAPE, enabled)

    /** 控制中心长按磁贴以小窗打开。 */
    fun setLightOpenControlCenter(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_LIGHT_OPEN_CONTROL_CENTER, enabled)

    /** 分享至应用 / 文本操作。 */
    fun setLightOpenShare(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_LIGHT_OPEN_SHARE, enabled)

    fun setLightOpenText(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_LIGHT_OPEN_TEXT, enabled)

    /** 应用间跳转（带黑白名单）。 */
    fun setLightOpenJump(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_LIGHT_OPEN_JUMP, enabled)

    fun setLightOpenJumpMode(context: Context, mode: Int) = putInt(
        context,
        RemotePrefs.KEY_LIGHT_OPEN_JUMP_MODE,
        mode.coerceIn(
            com.repl.bubbledrawer.lightopen.LightOpen.MODE_WHITE,
            com.repl.bubbledrawer.lightopen.LightOpen.MODE_BLACK,
        ),
    )

    /** 写入完整名单（UI 改完再交进来；编码 / 清洗在 [com.repl.bubbledrawer.lightopen.LightOpen]）。 */
    fun setLightOpenJumpList(context: Context, packages: Collection<String>) = putString(
        context,
        RemotePrefs.KEY_LIGHT_OPEN_JUMP_LIST,
        com.repl.bubbledrawer.lightopen.LightOpen.encodeTargets(packages),
    )

    private fun putString(context: Context, key: String, value: String) {
        RemoteBridge.local(context).edit().putString(key, value).apply()
        RemoteBridge.remote?.edit()?.putString(key, value)?.apply()
    }

    private fun putBoolean(context: Context, key: String, value: Boolean) {
        RemoteBridge.local(context).edit().putBoolean(key, value).apply()
        RemoteBridge.remote?.edit()?.putBoolean(key, value)?.apply()
    }

    private fun putInt(context: Context, key: String, value: Int) {
        RemoteBridge.local(context).edit().putInt(key, value).apply()
        RemoteBridge.remote?.edit()?.putInt(key, value)?.apply()
    }

    fun setFreeform(context: Context, on: Boolean) {
        RemoteBridge.local(context).edit().putBoolean(RemotePrefs.KEY_FREEFORM, on).apply()
        RemoteBridge.remote?.edit()?.putBoolean(RemotePrefs.KEY_FREEFORM, on)?.apply()
    }

    /** Pin backend: remote file when connected, local mirror otherwise; writes
     *  go to BOTH so the offline cache never diverges (syncLocal re-pushes).
     *
     *  READS RECONCILE (see [PinsSync] / [PinBackend.readRev]): the two stores used to each
     *  prefer their own copy, so one lost handoff between this process and the SystemUI panel
     *  left the app's 收藏 and the panel's 收藏 disagreeing forever. Now the newer stamp wins
     *  and the loser is rewritten, so a single read on either side heals the split. */
    fun pinBackend(context: Context): PinBackend = object : PinBackend {
        private val local get() = PrefsPinBackend(fallbackMirror(context))

        override fun read(): String {
            val localBackend = local
            val remotePrefs = RemoteBridge.remote ?: return localBackend.read()
            val remoteBackend = PrefsPinBackend(remotePrefs)

            val decision = PinsSync.decide(
                localValue = localBackend.read(),
                localRev = localBackend.readRev(),
                remoteValue = remoteBackend.read(),
                remoteRev = remoteBackend.readRev(),
            )
            when (decision.source) {
                PinsSync.Source.REMOTE -> {
                    // 一次 commit：分两次写会让同一文件上的监听者读到「新值 + 旧戳」，
                    // 而平戳时 [PinsSync.decide] 判给远端 —— 刚点的那一下就被顶掉了。
                    localBackend.writeWithRev(decision.value, decision.rev)
                }
                PinsSync.Source.LOCAL -> if (decision.pushBack) {
                    // Our mirror is ahead of the shared group (a write landed while LSPosed was
                    // not connected): push it back so the hook process and the next app start
                    // see the same list.
                    runCatching {
                        remoteBackend.writeWithRev(decision.value, decision.rev)
                    }
                }
                PinsSync.Source.NONE -> Unit
            }
            return decision.value
        }

        override fun write(value: String) {
            val rev = System.currentTimeMillis()
            val localBackend = local
            localBackend.writeWithRev(value, rev)
            val r = RemoteBridge.remote
            if (r != null) {
                runCatching {
                    PrefsPinBackend(r).writeWithRev(value, rev)
                }
            } else {
                RemoteBridge.start(context)
            }
            broadcastPins(context, value, rev)
        }

        override fun readRev(): Long = local.readRev()

        override fun writeRev(rev: Long) {
            local.writeRev(rev)
            RemoteBridge.remote?.let { runCatching { PrefsPinBackend(it).writeRev(rev) } }
        }

        /** 见 [functionBackend] 里同名覆盖的说明：接口默认实现会重新盖戳，交接来的编辑不能被改戳。 */
        override fun writeWithRev(value: String, rev: Long) {
            local.writeWithRev(value, rev)
            RemoteBridge.remote?.let { r ->
                runCatching { PrefsPinBackend(r).writeWithRev(value, rev) }
            }
        }
    }

    /**
     * Apply pins pushed here by the SystemUI panel, KEEPING the sender's stamp: re-stamping
     * with "now" would make a late/duplicate delivery look newer than a local edit made after
     * it. No broadcast back — the sender is the one that just wrote them.
     *
     * And a delivery that is *older* than what this side already has is dropped, not written:
     * the same last-writer-wins the read path ([PinsSync.decide]) uses. Without this check a
     * duplicate handoff could clobber an edit the user just made here — which is the app-side
     * half of the "收藏自动反复添加/移除" oscillation (the panel side, `FanHost`, is guarded by
     * the same [PinsSync.shouldApplyPush]).
     */
    fun applyPushedPins(context: Context, value: String, rev: Long): Boolean {
        val local = PrefsPinBackend(fallbackMirror(context))
        val localRev = local.readRev()
        if (!PinsSync.shouldApplyPush(rev, localRev)) {
            android.util.Log.i(
                "BubbleDrawer",
                "PIN_PUSH_DROPPED rev=$rev localRev=$localRev len=${value.length}",
            )
            return false
        }
        local.writeWithRev(value, rev)
        val r = RemoteBridge.remote
        if (r != null) {
            runCatching { PrefsPinBackend(r).writeWithRev(value, rev) }
        } else {
            RemoteBridge.start(context)
        }
        return true
    }

    /** Hand the pin order to the SystemUI side (its FanHost receiver applies it). */
    fun broadcastPins(context: Context, value: String, rev: Long) {
        runCatching {
            val intent = android.content.Intent(PinSyncReceiver.ACTION_TO_SYSTEMUI)
                // 只有系统界面需要它。不加 setPackage 的话，任何注册了这个 action 的应用都能
                // **收到**用户的收藏列表（注入已由 FanHost 的发送方校验挡住，但没必要广而告之）。
                .setPackage(PinSyncReceiver.SYSTEMUI_PACKAGE)
                .putExtra(PinSyncReceiver.EXTRA_PINS, value)
                .putExtra(PinSyncReceiver.EXTRA_REV, rev)
            PinSyncReceiver.sendBroadcastWithIdentity(context, intent)
        }
    }

    /**
     * 「已选功能」（快捷方式收藏）的后端 —— 与 [pinBackend] **逐行同构**，只是换了 key。
     *
     * 为什么必须同构：这份列表和收藏应用一样，被两个进程读写（设置页在 App 进程，扇形面板在
     * SystemUI），而 hook 进程**写不了**远程组（只读镜像）。功能页第一版在 hook 侧直接写了
     * `RemoteBridge.local(SystemUI 的 context)`，也就是 SystemUI 自己的 cfg 文件 —— App 永远
     * 看不见，「在面板里选的功能设置页不显示，在设置页选的面板不显示」。所以：写的一方必须
     * 落到 App 进程（广播/服务交接），读的一方按戳和解，一次丢手能自愈。
     */
    fun functionBackend(context: Context): PinBackend = object : PinBackend {
        private val local get() = FunctionPinStore.backend(fallbackMirror(context))

        override fun read(): String {
            val localBackend = local
            val remotePrefs = RemoteBridge.remote ?: return localBackend.read()
            val remoteBackend = FunctionPinStore.backend(remotePrefs)

            val decision = PinsSync.decide(
                localValue = localBackend.read(),
                localRev = localBackend.readRev(),
                remoteValue = remoteBackend.read(),
                remoteRev = remoteBackend.readRev(),
            )
            when (decision.source) {
                PinsSync.Source.REMOTE -> {
                    // 一次 commit，理由同 pinBackend：这是个被管理页监听的同一个文件。
                    localBackend.writeWithRev(decision.value, decision.rev)
                }
                PinsSync.Source.LOCAL -> if (decision.pushBack) {
                    runCatching { remoteBackend.writeWithRev(decision.value, decision.rev) }
                }
                PinsSync.Source.NONE -> Unit
            }
            return decision.value
        }

        override fun write(value: String) {
            val rev = System.currentTimeMillis()
            local.writeWithRev(value, rev)
            val r = RemoteBridge.remote
            if (r != null) {
                runCatching { FunctionPinStore.backend(r).writeWithRev(value, rev) }
            } else {
                RemoteBridge.start(context)
            }
            broadcastFunctions(context, value, rev)
            // 面板要画的是「这一条是什么」，光有键不够 —— 详情跟着一起发（见 publishFunctionDetails）。
            publishFunctionDetails(context)
        }

        override fun readRev(): Long = local.readRev()

        override fun writeRev(rev: Long) {
            local.writeRev(rev)
            RemoteBridge.remote?.let { runCatching { FunctionPinStore.backend(it).writeRev(rev) } }
        }

        /**
         * 带上调用方的戳一次写完（本地 + 共享组）。
         *
         * 必须覆盖接口默认实现：默认是 `write(value)` + `writeRev(rev)`，而 `write` 会**重新盖戳**
         * （`System.currentTimeMillis()`）—— 交接来的编辑会被改成「现在」，比对面刚写的那一笔看起来
         * 更新，正是 [PinsSync.shouldApplyPush] 要防的事。
         */
        override fun writeWithRev(value: String, rev: Long) {
            local.writeWithRev(value, rev)
            RemoteBridge.remote?.let { r ->
                runCatching { FunctionPinStore.backend(r).writeWithRev(value, rev) }
            }
        }
    }

    /**
     * 「应用 + 功能」混合收藏顺序的后端 —— 与 [functionBackend] 同构，只换了 key。
     *
     * 顺序本身也必须跨进程：面板里把「聚合支付」拖到微信前面之后，设置页看到的必须是同一份。
     * 收藏成员仍由两条原有链分别保存，这里只同步它们的交错顺序。
     */
    fun pinnedOrderBackend(context: Context): PinBackend = object : PinBackend {
        private val local get() = com.repl.bubbledrawer.pin.PinnedOrderStore.backend(fallbackMirror(context))

        override fun read(): String {
            val localBackend = local
            val remotePrefs = RemoteBridge.remote ?: return localBackend.read()
            val remoteBackend = com.repl.bubbledrawer.pin.PinnedOrderStore.backend(remotePrefs)

            val decision = PinsSync.decide(
                localValue = localBackend.read(),
                localRev = localBackend.readRev(),
                remoteValue = remoteBackend.read(),
                remoteRev = remoteBackend.readRev(),
            )
            when (decision.source) {
                PinsSync.Source.REMOTE -> localBackend.writeWithRev(decision.value, decision.rev)
                PinsSync.Source.LOCAL -> if (decision.pushBack) {
                    runCatching { remoteBackend.writeWithRev(decision.value, decision.rev) }
                }
                PinsSync.Source.NONE -> Unit
            }
            return decision.value
        }

        override fun write(value: String) {
            val rev = System.currentTimeMillis()
            local.writeWithRev(value, rev)
            val r = RemoteBridge.remote
            if (r != null) {
                runCatching {
                    com.repl.bubbledrawer.pin.PinnedOrderStore.backend(r).writeWithRev(value, rev)
                }
            } else {
                RemoteBridge.start(context)
            }
            broadcastPinnedOrder(context, value, rev)
        }

        override fun readRev(): Long = local.readRev()

        override fun writeRev(rev: Long) {
            local.writeRev(rev)
            RemoteBridge.remote?.let { r ->
                runCatching { com.repl.bubbledrawer.pin.PinnedOrderStore.backend(r).writeRev(rev) }
            }
        }

        override fun writeWithRev(value: String, rev: Long) {
            local.writeWithRev(value, rev)
            RemoteBridge.remote?.let { r ->
                runCatching {
                    com.repl.bubbledrawer.pin.PinnedOrderStore.backend(r).writeWithRev(value, rev)
                }
            }
        }
    }

    /** 面板可能正开着：把混合收藏顺序直接推给 SystemUI（与 [broadcastFunctions] 同一通道）。 */
    fun broadcastPinnedOrder(context: Context, value: String, rev: Long) {
        runCatching {
            val intent = android.content.Intent(PinSyncReceiver.ACTION_TO_SYSTEMUI)
                .setPackage(PinSyncReceiver.SYSTEMUI_PACKAGE)
                .putExtra(PinSyncReceiver.EXTRA_ORDER, value)
                .putExtra(PinSyncReceiver.EXTRA_ORDER_REV, rev)
            PinSyncReceiver.sendBroadcastWithIdentity(context, intent)
        }
    }

    /**
     * 面板推过来的混合顺序落盘：保留发送方的戳、比本地旧就丢（规则同
     * [applyPushedFunctions]）。它不改变成员，只改变显示顺序，所以不需要重新发布详情。
     */
    fun applyPushedPinnedOrder(context: Context, value: String, rev: Long): Boolean {
        val local = com.repl.bubbledrawer.pin.PinnedOrderStore.backend(fallbackMirror(context))
        val localRev = local.readRev()
        if (!PinsSync.shouldApplyPush(rev, localRev)) {
            android.util.Log.i(
                TAG,
                "PINNED_ORDER_PUSH_DROPPED rev=$rev localRev=$localRev len=${value.length}",
            )
            return false
        }
        local.writeWithRev(value, rev)
        val r = RemoteBridge.remote
        if (r != null) {
            runCatching {
                com.repl.bubbledrawer.pin.PinnedOrderStore.backend(r).writeWithRev(value, rev)
            }
        } else {
            RemoteBridge.start(context)
        }
        return true
    }

    /** 面板可能正开着：把功能收藏直接推给 SystemUI（与 [broadcastPins] 同一通道、同一规则）。 */
    fun broadcastFunctions(context: Context, value: String, rev: Long) {
        runCatching {
            val intent = android.content.Intent(PinSyncReceiver.ACTION_TO_SYSTEMUI)
                .setPackage(PinSyncReceiver.SYSTEMUI_PACKAGE)
                .putExtra(PinSyncReceiver.EXTRA_FUNCTIONS, value)
                .putExtra(PinSyncReceiver.EXTRA_FUNCTIONS_REV, rev)
            PinSyncReceiver.sendBroadcastWithIdentity(context, intent)
        }
    }

    /**
     * 把「已选功能」的**详情**发布到共享组，供 SystemUI 侧的面板直接渲染。
     *
     * 只发键是不够的：面板要显示一条功能，就得在自己的枚举结果里认出这个键，而两个进程的枚举结果
     * 并不相同（面板有 LauncherApps 但读不到 App 的缓存，App 反过来）。少一条，用户就会看到
     * 「设置页选好了，扇形面板里没有」，且毫无提示。详情里带上名称、宿主与启动 Intent，面板就不必
     * 依赖自己的枚举了（图标缺失时 `ShortcutIcon` 会用宿主应用图标兜底）。
     *
     * 由 App 进程写（它的缓存最全），hook 进程只读 —— 与其它所有跨进程数据同一个方向。
     */
    fun publishFunctionDetails(context: Context) {
        runCatching {
            val pins = FunctionPinStore.decode(FunctionPinStore.backend(fallbackMirror(context)).read())
            val catalogue = ShortcutCacheStore.load(context).orEmpty().associateBy { it.pinKey }
            val details = pins.map { key ->
                when {
                    // 聚合支付不在扫描缓存里（内建功能）：用内建条目当详情，别落成 placeholder
                    // （placeholder 的 label 是宿主应用名，面板上会显示成「魅窗」）。
                    key == com.repl.bubbledrawer.shortcut.AggregatePayment.PIN_KEY ->
                        com.repl.bubbledrawer.shortcut.AggregatePayment.entryIn(context)
                    else -> catalogue[key] ?: FunctionPinDetails.placeholder(key, appLabelOf(context, key.substringBefore('#')))
                }
            }
            val encoded = FunctionPinDetails.encode(details)
            fallbackMirror(context).edit().putString(FunctionPinDetails.KEY, encoded).apply()
            RemoteBridge.remote?.edit()?.putString(FunctionPinDetails.KEY, encoded)?.apply()
            android.util.Log.i(
                TAG,
                "FUNCTION_DETAILS_PUBLISHED pins=${pins.size} resolved=${details.size} len=${encoded.length}",
            )
        }
    }

    private fun appLabelOf(context: Context, packageName: String): String? = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrNull()

    /**
     * 功能收藏由 SystemUI 面板推过来时落盘：保留发送方的戳、比本地旧就丢（不回推）—— 规则与
     * [applyPushedPins] 完全相同，理由见那里的注释。
     */
    fun applyPushedFunctions(context: Context, value: String, rev: Long): Boolean {
        val local = FunctionPinStore.backend(fallbackMirror(context))
        val localRev = local.readRev()
        if (!PinsSync.shouldApplyPush(rev, localRev)) {
            android.util.Log.i(
                TAG,
                "FUNCTION_PUSH_DROPPED rev=$rev localRev=$localRev len=${value.length}",
            )
            return false
        }
        local.writeWithRev(value, rev)
        val r = RemoteBridge.remote
        if (r != null) {
            runCatching { FunctionPinStore.backend(r).writeWithRev(value, rev) }
        } else {
            RemoteBridge.start(context)
        }
        // 面板推过来的这一笔也可能带着「面板并不认识」的键（它自己的枚举里没有），详情重新发布一次。
        publishFunctionDetails(context)
        return true
    }

    /** Encode/decode passthroughs keep the call sites honest about the format. */
    fun encodePins(list: List<PinnedRef>): String = PinCodec.encode(list)

    /**
     * Reactive snapshot the Compose settings page collects
     * (docs/ui-guidelines.md "Flow 收集": screens use `collectAsStateWithLifecycle`, never
     * poll in recomposition — and the old page's `recreate()`-to-rerender hack dies here).
     *
     * Emits on: any write through [SettingsStore] (local file listener sees them all),
     * remote-group changes surfaced by LSPosed, and bridge bind/die transitions (the
     * reader switches between the remote group and the offline mirror).
     */
    fun observe(context: Context): Flow<SettingsUiState> = callbackFlow {
        val app = context.applicationContext
        // 页面打开时先自愈一次「按满 5 次却还标着进行中」的卡死（结果事件没送到，见
        // [recoverStuckPressureCalibration]）——必须赶在第一次 push() 之前，这样页面拿到的
        // 第一帧就已经是收尾后的状态，不会先闪一下「校准中」。
        runCatching { recoverStuckPressureCalibration(app) }
        // 顺手把「已选功能」的详情重发一次：上一次可能是拿空缓存算的占位项，而现在缓存已经更全了。
        runCatching { publishFunctionDetails(app) }
        val local = RemoteBridge.local(app)

        fun push() { trySend(currentState(app)) }

        val localListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> push() }
        local.registerOnSharedPreferenceChangeListener(localListener)

        // The remote proxy may appear/disappear at any time; (re)register while present.
        var remoteListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
        fun attachRemote() {
            val r = RemoteBridge.remote ?: return
            if (remoteListener != null) return
            val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> push() }
            remoteListener = l
            runCatching { r.registerOnSharedPreferenceChangeListener(l) }
        }
        fun detachRemote() {
            val l = remoteListener ?: return
            remoteListener = null
            runCatching { RemoteBridge.remote?.unregisterOnSharedPreferenceChangeListener(l) }
        }

        val connectionListener: (Boolean) -> Unit = { connected ->
            if (connected) attachRemote() else detachRemote()
            push()
        }
        RemoteBridge.addConnectionListener(connectionListener)
        attachRemote()

        push() // initial value without waiting for a first event
        awaitClose {
            RemoteBridge.removeConnectionListener(connectionListener)
            detachRemote()
            local.unregisterOnSharedPreferenceChangeListener(localListener)
        }
    }
        .buffer(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        .distinctUntilChanged()
        .flowOn(Dispatchers.Main)

    /** Current UI state: snapshot + bridge status in one @Immutable value. */
    fun currentState(context: Context): SettingsUiState = SettingsUiState(
        snapshot = snapshot(context),
        triggerPos = triggerPos(context),
        connected = RemoteBridge.connected,
        frameworkLabel = RemoteBridge.frameworkLabel,
    )
}
