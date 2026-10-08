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

    const val DEFAULT_ENABLED = true
    const val DEFAULT_LEFT = true
    const val DEFAULT_RIGHT = true
    const val DEFAULT_RANGE_DP = 96
    const val DEFAULT_FREEFORM = false

    data class Snapshot(
        val enabled: Boolean,
        val left: Boolean,
        val right: Boolean,
        val rangeDp: Int,
        val bottomDp: Int,
        val edgeDp: Int,
        val freeform: Boolean,
        val pins: String,
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
            pins = sp.getString(com.repl.bubbledrawer.data.PrefsPinBackend.KEY, "").orEmpty(),
        )
    }

    /** Slider bounds for every axis (kept in one place so UI and reader cannot drift). */
    const val MIN_DP = 24
    const val MAX_DP = 160
}
