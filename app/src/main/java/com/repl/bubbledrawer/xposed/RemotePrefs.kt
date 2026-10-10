package com.repl.bubbledrawer.xposed

import android.content.SharedPreferences

/**
 * Cross-process config protocol (same mechanism as the FlymeFreeform reference:
 * ModuleMain.kt:46 getRemotePreferences(GROUP) + ProcessConfiguration.kt:25-30
 * registering a listener on the remote SharedPreferences proxy — LSPosed mirrors
 * this single file into every hooked process, so the settings UI (app process)
 * and the live monitor (SystemUI) share ONE source of truth).
 *
 * Corners: BOTTOM-LEFT / BOTTOM-RIGHT only — the physical bottom corners (user
 * request "真正的左右下角内滑触发"; the decompiled flyme original and the reference
 * are both bottom-corner triggers, and the fan geometry pivots at the bottom edge).
 */
object RemotePrefs {
    const val GROUP = "bubble_runtime"

    const val KEY_ENABLED = "enabled"
    const val KEY_LEFT = "corner_left_enabled"
    const val KEY_RIGHT = "corner_right_enabled"
    const val KEY_RANGE_DP = "corner_trigger_range_dp"
    const val KEY_FREEFORM = "freeform"

    /**
     * Trigger REGION, not just its size: the corner is a quarter-ELLIPSE whose two
     * semi-axes are set independently — how far the zone reaches ALONG the bottom edge
     * ([KEY_BOTTOM_DP]) and how far it reaches UP the side edge ([KEY_EDGE_DP]). Equal
     * values give the plain quarter-disc; a large edge axis with a small bottom axis is
     * "swipe up along the side", the opposite is "swipe in along the bottom". Both fall
     * back to [KEY_RANGE_DP] so an existing install keeps its single slider behaviour.
     */
    const val KEY_BOTTOM_DP = "corner_trigger_bottom_dp"
    const val KEY_EDGE_DP = "corner_trigger_edge_dp"

    /**
     * Written by the system_server arbiter hook ([LauncherMonitorRegion]) once MiuiHome's
     * "[Gesture Monitor] swipe-up" carries a touchable region with the corner boxes cut
     * out — i.e. the launcher's own recogniser no longer receives corner DOWNs. While that
     * proof is present SystemUI can stay a pure observer and take nothing from the app;
     * without it the bottom strip is owned at DOWN, because otherwise one corner swipe
     * also commits MiuiHome's HOME animation (its recogniser decides from the first
     * upward sample). LSPosed remote preferences are mirrored into every hooked process,
     * which is exactly the cross-process channel this needs.
     */
    const val KEY_ARBITER_REGION = "arbiter_launcher_region_epoch_ms"

    const val DEFAULT_ENABLED = true
    const val DEFAULT_LEFT = true
    const val DEFAULT_RIGHT = true
    const val DEFAULT_RANGE_DP = 96
    const val DEFAULT_FREEFORM = false

    /** 更多面板长宽/图标/文字 默认值与范围 */
    const val KEY_PANEL_W_PCT = "panel_width_pct"
    const val KEY_PANEL_H_PCT = "panel_height_pct"
    const val KEY_PANEL_ICON_DP = "panel_icon_dp"
    const val KEY_PANEL_TEXT_SP = "panel_text_sp"

    const val PANEL_PCT_MIN = 40
    const val PANEL_PCT_MAX = 100

    const val DEFAULT_PANEL_W_PCT = 80
    const val PANEL_W_PCT_MIN = PANEL_PCT_MIN
    const val PANEL_W_PCT_MAX = PANEL_PCT_MAX

    const val DEFAULT_PANEL_H_PCT = 70
    const val PANEL_H_PCT_MIN = PANEL_PCT_MIN
    const val PANEL_H_PCT_MAX = PANEL_PCT_MAX

    const val DEFAULT_PANEL_ICON_DP = 45
    const val PANEL_ICON_MIN = 28
    const val PANEL_ICON_MAX = 64

    const val DEFAULT_PANEL_TEXT_SP = 10
    const val PANEL_TEXT_MIN = 8
    const val PANEL_TEXT_MAX = 18

    /** 更多面板点击外部收起方式：0 = 单击，1 = 双击 */
    const val KEY_PANEL_DISMISS_OUTSIDE = "panel_dismiss_outside"
    const val DISMISS_OUTSIDE_SINGLE = 0
    const val DISMISS_OUTSIDE_DOUBLE = 1
    const val DEFAULT_PANEL_DISMISS_OUTSIDE = DISMISS_OUTSIDE_SINGLE

    /** 扇形面板应用图标数量：5 或 6 个 */
    const val KEY_FAN_ICON_COUNT = "fan_icon_count"
    const val DEFAULT_FAN_ICON_COUNT = 6

    /** 展开扇形半径（dp） */
    const val KEY_FAN_RADIUS_DP = "fan_radius_dp"
    const val DEFAULT_FAN_RADIUS_DP = 260
    const val FAN_RADIUS_MIN = 180
    const val FAN_RADIUS_MAX = 360

    /** 扇形滑出时振动一下（默认开启，与 Flyme 的手感一致）。 */
    const val KEY_FAN_SHOW_VIBRATE = "fan_show_vibrate"
    const val DEFAULT_FAN_SHOW_VIBRATE = true

    /**
     * 扇形滑出后不点选就自动收回的时间（秒）。0 = 一直停留，直到点选应用、点按外部或熄屏。
     *
     * 参考实现固定 5s（CornerRadialOverlayView 的 GESTURE_TIMEOUT_MS）；这里做成可调，默认取
     * 10s —— 5s 那份曾被用户否掉（"扇形面板弹出后不要过几秒就消失"），10s 既满足"不点就退回"，
     * 又不至于让人还没看清就没了。想要老手感就拖到 5，想永远停留就拖到最左。
     */
    const val KEY_FAN_AUTO_RETRACT_SEC = "fan_auto_retract_sec"
    const val DEFAULT_FAN_AUTO_RETRACT_SEC = 10
    const val FAN_AUTO_RETRACT_MIN = 0
    const val FAN_AUTO_RETRACT_MAX = 30

    /** 扇形图标大小（dp，默认 = launcher_app_item_icon_width 44dp）。 */
    const val KEY_FAN_ICON_DP = "fan_icon_dp"
    const val DEFAULT_FAN_ICON_DP = 44
    const val FAN_ICON_MIN = 32
    const val FAN_ICON_MAX = 64

    /** 扇形图标形状：0 = 圆形（Flyme 原样），1 = 系统样式圆角矩形。 */
    const val KEY_FAN_ICON_SHAPE = "fan_icon_shape"
    const val FAN_ICON_SHAPE_CIRCLE = 0
    const val FAN_ICON_SHAPE_ROUNDED = 1
    const val DEFAULT_FAN_ICON_SHAPE = FAN_ICON_SHAPE_CIRCLE

    /** 扇形应用未摆满时用推荐应用填满（默认关闭） */
    const val KEY_FAN_AUTO_FILL_RECOMMEND = "fan_auto_fill_recommend"
    const val DEFAULT_FAN_AUTO_FILL_RECOMMEND = false

    /** 扇形应用重按翻页（默认开启） */
    const val KEY_FAN_PRESSURE_PAGE_TURN = "fan_pressure_page_turn"
    const val DEFAULT_FAN_PRESSURE_PAGE_TURN = true

    /** 扇形重按灵敏度（0: 标准 1.2hPa, 1: 灵敏 0.8hPa, 2: 较重 1.8hPa） */
    const val KEY_FAN_PRESSURE_SENSITIVITY = "fan_pressure_sensitivity"
    const val DEFAULT_FAN_PRESSURE_SENSITIVITY = 0

    fun sensitivityToThreshold(sensitivity: Int): Float = when (sensitivity) {
        1 -> 0.8f
        2 -> 1.8f
        else -> 1.2f
    }

    /** 更多面板推荐应用开关与显示数量（4个为一组，最多20个） */
    const val KEY_RECOMMEND_ENABLED = "recommend_enabled"
    const val KEY_RECOMMEND_COUNT = "recommend_count"
    const val DEFAULT_RECOMMEND_ENABLED = true
    const val DEFAULT_RECOMMEND_COUNT = 8
    const val RECOMMEND_COUNT_MIN = 4
    const val RECOMMEND_COUNT_MAX = 20
    const val RECOMMEND_COUNT_STEP = 4
    /** 实验性：Flyme 样式轻量小窗设置 */
    const val KEY_FLYME_FREEFORM_ENABLED = "flyme_freeform_enabled"
    const val KEY_FLYME_FREEFORM_CENTER = "flyme_freeform_center"
    const val KEY_FLYME_FREEFORM_NO_OFFSET = "flyme_freeform_no_offset"
    const val KEY_FLYME_FREEFORM_SCALE = "flyme_freeform_scale"
    const val KEY_FLYME_FREEFORM_OUTSIDE_DISMISS = "flyme_freeform_outside_dismiss"
    const val KEY_FLYME_FREEFORM_OUTSIDE_DISMISS_ACTION = "flyme_freeform_outside_dismiss_action"
    const val KEY_FLYME_FREEFORM_SWIPE_UP_MINI = "flyme_freeform_swipe_up_mini"
    const val KEY_FLYME_FREEFORM_SWIPE_UP_HOLD_FREE = "flyme_freeform_swipe_up_hold_free"
    const val KEY_FLYME_FREEFORM_SWIPE_DOWN_FULL = "flyme_freeform_swipe_down_full"
    const val KEY_FLYME_FREEFORM_DIM_BG = "flyme_freeform_dim_bg"

    const val DEFAULT_FLYME_FREEFORM_ENABLED = false
    const val DEFAULT_FLYME_FREEFORM_CENTER = true

    /**
     * 禁止小窗偏移：已经有小窗时，新开的小窗不再为了避开它而挪位。
     *
     * HyperOS 会给第二个小窗加一个「错开」偏移（`MiuiMultiWindowUtils.avoidIfNeeded`：X +78dp、
     * Y +44dp，正好是往右下偏一点），Flyme 的小窗每次都开在同一个默认位置。
     * 默认关闭：不动系统原有摆放，想要 Flyme 那样"每次都开在同一处"再手动打开。
     *
     * 与 `flymeFreeformEnabled`（Flyme 样式小窗总开关）**无关**：这是对 HyperOS 自身避让行为的
     * 开关，原生小窗模式下同样生效。
     */
    const val DEFAULT_FLYME_FREEFORM_NO_OFFSET = false
    const val DEFAULT_FLYME_FREEFORM_SCALE = 80
    const val FLYME_FREEFORM_SCALE_MIN = 60
    const val FLYME_FREEFORM_SCALE_MAX = 95
    const val DEFAULT_FLYME_FREEFORM_OUTSIDE_DISMISS = true
    const val DEFAULT_FLYME_FREEFORM_OUTSIDE_DISMISS_ACTION = 0
    const val DEFAULT_FLYME_FREEFORM_SWIPE_UP_MINI = true
    const val DEFAULT_FLYME_FREEFORM_SWIPE_UP_HOLD_FREE = true
    const val DEFAULT_FLYME_FREEFORM_SWIPE_DOWN_FULL = true
    const val DEFAULT_FLYME_FREEFORM_DIM_BG = true

    /**
     * 小窗底部联系人条 — the port of Flyme's 「联系人头像」 (`com.flyme.systemuitools`
     * `notification_show_contact_list` System setting, default on). Independent of the Flyme-style
     * freeform takeover above: it rides HyperOS's own 小窗.
     */
    const val KEY_CONTACT_BAR = "contact_bar_enabled"
    const val DEFAULT_CONTACT_BAR = true

    /**
     * 联系人条的尺寸阈值：小窗可视宽度小于「屏幕宽度 × N%」时整条隐藏（0 = 不按尺寸隐藏）。
     * 默认 60%（用户 2026-10-09 指定）：小窗宽度约屏幕 70%，所以只要明显缩窄就隐藏。
     */
    const val KEY_CONTACT_BAR_MIN_WIDTH_PCT = "contact_bar_min_width_pct"
    const val DEFAULT_CONTACT_BAR_MIN_WIDTH_PCT = 60
    const val CONTACT_BAR_MIN_WIDTH_MIN = 0
    const val CONTACT_BAR_MIN_WIDTH_MAX = 90

    @androidx.compose.runtime.Immutable
    data class Snapshot(
        val enabled: Boolean,
        val left: Boolean,
        val right: Boolean,
        val rangeDp: Int,
        val bottomDp: Int,
        val edgeDp: Int,
        val freeform: Boolean,
        val pins: String,
        /** 更多面板：宽/高百分比，图标 dp / 文字 sp */
        val panelWidthPct: Int = DEFAULT_PANEL_W_PCT,
        val panelHeightPct: Int = DEFAULT_PANEL_H_PCT,
        val panelIconDp: Int = DEFAULT_PANEL_ICON_DP,
        val panelTextSp: Int = DEFAULT_PANEL_TEXT_SP,
        /** 更多面板点击外部收起方式：0 = 单击，1 = 双击 */
        val panelDismissOutside: Int = DEFAULT_PANEL_DISMISS_OUTSIDE,
        /** 扇形面板应用图标数量（5 或 6） */
        val fanIconCount: Int = DEFAULT_FAN_ICON_COUNT,
        /** 展开扇形半径（dp） */
        val fanRadiusDp: Int = DEFAULT_FAN_RADIUS_DP,
        /** 扇形滑出时振动 */
        val fanShowVibrate: Boolean = DEFAULT_FAN_SHOW_VIBRATE,
        /** 扇形滑出后不点选自动收回的秒数（0 = 不自动收回） */
        val fanAutoRetractSec: Int = DEFAULT_FAN_AUTO_RETRACT_SEC,
        /** 扇形图标大小（dp） */
        val fanIconDp: Int = DEFAULT_FAN_ICON_DP,
        /** 扇形图标形状：0 = 圆形，1 = 系统样式圆角矩形 */
        val fanIconShape: Int = DEFAULT_FAN_ICON_SHAPE,
        /** 扇形未摆满时用推荐应用填满 */
        val fanAutoFillRecommend: Boolean = DEFAULT_FAN_AUTO_FILL_RECOMMEND,
        /** 扇形重按翻页开关与灵敏度 */
        val fanPressurePageTurn: Boolean = DEFAULT_FAN_PRESSURE_PAGE_TURN,
        val fanPressureSensitivity: Int = DEFAULT_FAN_PRESSURE_SENSITIVITY,
        /** 推荐应用开关与显示数量（4, 8, 12, 16, 20） */
        val recommendEnabled: Boolean = DEFAULT_RECOMMEND_ENABLED,
        val recommendCount: Int = DEFAULT_RECOMMEND_COUNT,
        /** 实验性：Flyme 样式轻量小窗设置 */
        val flymeFreeformEnabled: Boolean = DEFAULT_FLYME_FREEFORM_ENABLED,
        val flymeFreeformCenter: Boolean = DEFAULT_FLYME_FREEFORM_CENTER,
        /** 已经有小窗时，新小窗不再为避让而偏移（HyperOS 默认 X+78dp / Y+44dp） */
        val flymeFreeformNoOffset: Boolean = DEFAULT_FLYME_FREEFORM_NO_OFFSET,
        val flymeFreeformScale: Int = DEFAULT_FLYME_FREEFORM_SCALE,
        val flymeFreeformOutsideDismiss: Boolean = DEFAULT_FLYME_FREEFORM_OUTSIDE_DISMISS,
        val flymeFreeformOutsideDismissAction: Int = DEFAULT_FLYME_FREEFORM_OUTSIDE_DISMISS_ACTION,
        val flymeFreeformSwipeUpMini: Boolean = DEFAULT_FLYME_FREEFORM_SWIPE_UP_MINI,
        val flymeFreeformSwipeUpHoldFree: Boolean = DEFAULT_FLYME_FREEFORM_SWIPE_UP_HOLD_FREE,
        val flymeFreeformSwipeDownFull: Boolean = DEFAULT_FLYME_FREEFORM_SWIPE_DOWN_FULL,
        val flymeFreeformDimBg: Boolean = DEFAULT_FLYME_FREEFORM_DIM_BG,
        /** 小窗底部联系人条（Flyme「联系人头像」） */
        val contactBar: Boolean = DEFAULT_CONTACT_BAR,
        /** 联系人条尺寸阈值：小窗宽度低于屏幕宽度的 N% 时隐藏（0 = 关闭该限制） */
        val contactBarMinWidthPct: Int = DEFAULT_CONTACT_BAR_MIN_WIDTH_PCT,
    )

    fun enabledFor(side: com.repl.bubbledrawer.gesture.SpySide): (Snapshot) -> Boolean = { snap ->
        snap.enabled && when (side) {
            com.repl.bubbledrawer.gesture.SpySide.LEFT -> snap.left
            com.repl.bubbledrawer.gesture.SpySide.RIGHT -> snap.right
        }
    }

    /** Pins share PrefsPinBackend.KEY so hooked and app processes use one key. */
    fun read(sp: SharedPreferences?): Snapshot {
        if (sp == null) {
            return Snapshot(false, false, false, DEFAULT_RANGE_DP, DEFAULT_RANGE_DP, DEFAULT_RANGE_DP, false, "")
        }
        val range = sp.getInt(KEY_RANGE_DP, DEFAULT_RANGE_DP).coerceIn(MIN_DP, MAX_DP)
        val rawRecCount = sp.getInt(KEY_RECOMMEND_COUNT, DEFAULT_RECOMMEND_COUNT).coerceIn(RECOMMEND_COUNT_MIN, RECOMMEND_COUNT_MAX)
        val snappedRecCount = (((rawRecCount - RECOMMEND_COUNT_MIN + (RECOMMEND_COUNT_STEP / 2)) / RECOMMEND_COUNT_STEP) * RECOMMEND_COUNT_STEP + RECOMMEND_COUNT_MIN)
            .coerceIn(RECOMMEND_COUNT_MIN, RECOMMEND_COUNT_MAX)
        return Snapshot(
            enabled = sp.getBoolean(KEY_ENABLED, DEFAULT_ENABLED),
            left = sp.getBoolean(KEY_LEFT, DEFAULT_LEFT),
            right = sp.getBoolean(KEY_RIGHT, DEFAULT_RIGHT),
            rangeDp = range,
            // The two axes default to the single "size" value, so an existing install keeps
            // its behaviour until the new sliders are touched.
            bottomDp = sp.getInt(KEY_BOTTOM_DP, range).coerceIn(MIN_DP, MAX_DP),
            edgeDp = sp.getInt(KEY_EDGE_DP, range).coerceIn(MIN_DP, MAX_DP),
            freeform = sp.getBoolean(KEY_FREEFORM, DEFAULT_FREEFORM),
            panelWidthPct = sp.getInt(KEY_PANEL_W_PCT, DEFAULT_PANEL_W_PCT).let { if (it <= 0) DEFAULT_PANEL_W_PCT else it.coerceIn(PANEL_W_PCT_MIN, PANEL_W_PCT_MAX) },
            panelHeightPct = sp.getInt(KEY_PANEL_H_PCT, DEFAULT_PANEL_H_PCT).let { if (it <= 0) DEFAULT_PANEL_H_PCT else it.coerceIn(PANEL_H_PCT_MIN, PANEL_H_PCT_MAX) },
            panelIconDp = sp.getInt(KEY_PANEL_ICON_DP, DEFAULT_PANEL_ICON_DP).let { if (it <= 0) DEFAULT_PANEL_ICON_DP else it.coerceIn(PANEL_ICON_MIN, PANEL_ICON_MAX) },
            panelTextSp = sp.getInt(KEY_PANEL_TEXT_SP, DEFAULT_PANEL_TEXT_SP).let { if (it <= 0) DEFAULT_PANEL_TEXT_SP else it.coerceIn(PANEL_TEXT_MIN, PANEL_TEXT_MAX) },
            panelDismissOutside = sp.getInt(KEY_PANEL_DISMISS_OUTSIDE, DEFAULT_PANEL_DISMISS_OUTSIDE).coerceIn(0, 1),
            fanIconCount = sp.getInt(KEY_FAN_ICON_COUNT, DEFAULT_FAN_ICON_COUNT).coerceIn(5, 6),
            fanRadiusDp = sp.getInt(KEY_FAN_RADIUS_DP, DEFAULT_FAN_RADIUS_DP).let { if (it <= 0) DEFAULT_FAN_RADIUS_DP else it.coerceIn(FAN_RADIUS_MIN, FAN_RADIUS_MAX) },
            fanShowVibrate = sp.getBoolean(KEY_FAN_SHOW_VIBRATE, DEFAULT_FAN_SHOW_VIBRATE),
            fanAutoRetractSec = sp.getInt(KEY_FAN_AUTO_RETRACT_SEC, DEFAULT_FAN_AUTO_RETRACT_SEC)
                .coerceIn(FAN_AUTO_RETRACT_MIN, FAN_AUTO_RETRACT_MAX),
            fanIconDp = sp.getInt(KEY_FAN_ICON_DP, DEFAULT_FAN_ICON_DP).let { if (it <= 0) DEFAULT_FAN_ICON_DP else it.coerceIn(FAN_ICON_MIN, FAN_ICON_MAX) },
            fanIconShape = sp.getInt(KEY_FAN_ICON_SHAPE, DEFAULT_FAN_ICON_SHAPE).coerceIn(0, 1),
            fanAutoFillRecommend = sp.getBoolean(KEY_FAN_AUTO_FILL_RECOMMEND, DEFAULT_FAN_AUTO_FILL_RECOMMEND),
            fanPressurePageTurn = sp.getBoolean(KEY_FAN_PRESSURE_PAGE_TURN, DEFAULT_FAN_PRESSURE_PAGE_TURN),
            fanPressureSensitivity = sp.getInt(KEY_FAN_PRESSURE_SENSITIVITY, DEFAULT_FAN_PRESSURE_SENSITIVITY).coerceIn(0, 2),
            recommendEnabled = sp.getBoolean(KEY_RECOMMEND_ENABLED, DEFAULT_RECOMMEND_ENABLED),
            recommendCount = snappedRecCount,
            flymeFreeformEnabled = sp.getBoolean(KEY_FLYME_FREEFORM_ENABLED, DEFAULT_FLYME_FREEFORM_ENABLED),
            flymeFreeformCenter = sp.getBoolean(KEY_FLYME_FREEFORM_CENTER, DEFAULT_FLYME_FREEFORM_CENTER),
            flymeFreeformNoOffset = sp.getBoolean(KEY_FLYME_FREEFORM_NO_OFFSET, DEFAULT_FLYME_FREEFORM_NO_OFFSET),
            flymeFreeformScale = sp.getInt(KEY_FLYME_FREEFORM_SCALE, DEFAULT_FLYME_FREEFORM_SCALE).coerceIn(FLYME_FREEFORM_SCALE_MIN, FLYME_FREEFORM_SCALE_MAX),
            flymeFreeformOutsideDismiss = sp.getBoolean(KEY_FLYME_FREEFORM_OUTSIDE_DISMISS, DEFAULT_FLYME_FREEFORM_OUTSIDE_DISMISS),
            flymeFreeformOutsideDismissAction = sp.getInt(KEY_FLYME_FREEFORM_OUTSIDE_DISMISS_ACTION, DEFAULT_FLYME_FREEFORM_OUTSIDE_DISMISS_ACTION).coerceIn(0, 1),
            flymeFreeformSwipeUpMini = sp.getBoolean(KEY_FLYME_FREEFORM_SWIPE_UP_MINI, DEFAULT_FLYME_FREEFORM_SWIPE_UP_MINI),
            flymeFreeformSwipeUpHoldFree = sp.getBoolean(KEY_FLYME_FREEFORM_SWIPE_UP_HOLD_FREE, DEFAULT_FLYME_FREEFORM_SWIPE_UP_HOLD_FREE),
            flymeFreeformSwipeDownFull = sp.getBoolean(KEY_FLYME_FREEFORM_SWIPE_DOWN_FULL, DEFAULT_FLYME_FREEFORM_SWIPE_DOWN_FULL),
            flymeFreeformDimBg = sp.getBoolean(KEY_FLYME_FREEFORM_DIM_BG, DEFAULT_FLYME_FREEFORM_DIM_BG),
            contactBar = sp.getBoolean(KEY_CONTACT_BAR, DEFAULT_CONTACT_BAR),
            contactBarMinWidthPct = sp.getInt(KEY_CONTACT_BAR_MIN_WIDTH_PCT, DEFAULT_CONTACT_BAR_MIN_WIDTH_PCT)
                .coerceIn(CONTACT_BAR_MIN_WIDTH_MIN, CONTACT_BAR_MIN_WIDTH_MAX),
            pins = sp.getString(com.repl.bubbledrawer.data.PrefsPinBackend.KEY, "").orEmpty(),
        )
    }
    /** Slider bounds for every axis (kept in one place so UI and reader cannot drift). */
    const val MIN_DP = 24
    const val MAX_DP = 160
}
