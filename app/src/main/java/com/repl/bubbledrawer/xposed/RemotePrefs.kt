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
            pins = sp.getString(com.repl.bubbledrawer.data.PrefsPinBackend.KEY, "").orEmpty(),
        )
    }
    /** Slider bounds for every axis (kept in one place so UI and reader cannot drift). */
    const val MIN_DP = 24
    const val MAX_DP = 160
}
