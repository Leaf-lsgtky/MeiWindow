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

    /**
     * 更多面板尺寸/字号（方案 B overlay）。面板始终居中，所以位置没有 key：
     * 长/宽是"占屏幕的百分比"（0 = [PANEL_PCT_DEFAULT]，即 62 % 屏幕）。
     */
    const val KEY_PANEL_W_PCT = "panel_width_pct"
    const val KEY_PANEL_H_PCT = "panel_height_pct"
    const val KEY_PANEL_ICON_DP = "panel_icon_dp"
    const val KEY_PANEL_TEXT_SP = "panel_text_sp"

    /** 0 = 用面板的默认占地（62 % 屏幕，见 FanHost.DEFAULT_PANEL_PCT）。 */
    const val PANEL_PCT_DEFAULT = 0
    const val PANEL_PCT_MIN = 40
    const val PANEL_PCT_MAX = 100
    const val PANEL_ICON_DEFAULT = 0
    const val PANEL_ICON_MIN = 28
    const val PANEL_ICON_MAX = 64
    const val PANEL_TEXT_DEFAULT = 0
    const val PANEL_TEXT_MIN = 10
    const val PANEL_TEXT_MAX = 20

    data class Snapshot(
        val enabled: Boolean,
        val left: Boolean,
        val right: Boolean,
        val rangeDp: Int,
        val bottomDp: Int,
        val edgeDp: Int,
        val freeform: Boolean,
        val pins: String,
        /** 更多面板：宽/高百分比（0 = 默认 62 % 屏幕，始终居中），图标 dp / 文字 sp（0 = 布局默认） */
        val panelWidthPct: Int = PANEL_PCT_DEFAULT,
        val panelHeightPct: Int = PANEL_PCT_DEFAULT,
        val panelIconDp: Int = PANEL_ICON_DEFAULT,
        val panelTextSp: Int = PANEL_TEXT_DEFAULT,
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
            panelWidthPct = pct(sp, KEY_PANEL_W_PCT),
            panelHeightPct = pct(sp, KEY_PANEL_H_PCT),
            panelIconDp = sizeOrZero(sp, KEY_PANEL_ICON_DP, PANEL_ICON_MIN, PANEL_ICON_MAX),
            panelTextSp = sizeOrZero(sp, KEY_PANEL_TEXT_SP, PANEL_TEXT_MIN, PANEL_TEXT_MAX),
            pins = sp.getString(com.repl.bubbledrawer.data.PrefsPinBackend.KEY, "").orEmpty(),
        )
    }

    /** 0 = 面板默认占地 / 布局默认；否则夹进滑块区间。 */
    private fun pct(sp: SharedPreferences, key: String): Int =
        sp.getInt(key, PANEL_PCT_DEFAULT).let { if (it <= 0) 0 else it.coerceIn(PANEL_PCT_MIN, PANEL_PCT_MAX) }

    private fun sizeOrZero(sp: SharedPreferences, key: String, min: Int, max: Int): Int =
        sp.getInt(key, 0).let { if (it <= 0) 0 else it.coerceIn(min, max) }

    /** Slider bounds for every axis (kept in one place so UI and reader cannot drift). */
    const val MIN_DP = 24
    const val MAX_DP = 160
}
