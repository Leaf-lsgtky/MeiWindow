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

    data class Snapshot(
        val enabled: Boolean,
        val left: Boolean,
        val right: Boolean,
        val rangeDp: Int,
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
        if (sp == null) return Snapshot(false, false, false, DEFAULT_RANGE_DP, false, "")
        return Snapshot(
            enabled = sp.getBoolean(KEY_ENABLED, DEFAULT_ENABLED),
            left = sp.getBoolean(KEY_LEFT, DEFAULT_LEFT),
            right = sp.getBoolean(KEY_RIGHT, DEFAULT_RIGHT),
            rangeDp = sp.getInt(KEY_RANGE_DP, DEFAULT_RANGE_DP)
                .coerceIn(24, 160), // FlymeFreeform ModulePreferences MIN/MAX (ModulePreferences.kt:22-23)
            freeform = sp.getBoolean(KEY_FREEFORM, DEFAULT_FREEFORM),
            pins = sp.getString(com.repl.bubbledrawer.data.PrefsPinBackend.KEY, "").orEmpty(),
        )
    }
}
