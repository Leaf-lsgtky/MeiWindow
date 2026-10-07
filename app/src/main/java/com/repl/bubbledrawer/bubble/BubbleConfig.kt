package com.repl.bubbledrawer.bubble

import android.content.Context
import android.content.SharedPreferences
import com.repl.bubbledrawer.gesture.Corner
import com.repl.bubbledrawer.gesture.CornerZone

/**
 * Trigger-zone configuration, mirrored after the original's two-zone model:
 * flyme ships a 20x64dp visible indicator (slide_gesture_indicator_width/height)
 * at the edge midpoint; our replica uses invisible bottom-corner zones
 * (spec §4.1: inset 32dp, width 96dp, height 160dp, all adjustable).
 */
class BubbleConfig(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    var insetDp: Float
        get() = if (edgeMode) 0f else prefs.getFloat("inset", 32f)
        set(v) = prefs.edit().putFloat("inset", v).apply()

    var widthDp: Float
        get() = prefs.getFloat("zone_w", 96f)
        set(v) = prefs.edit().putFloat("zone_w", v).apply()

    var heightDp: Float
        get() = prefs.getFloat("zone_h", 160f)
        set(v) = prefs.edit().putFloat("zone_h", v).apply()

    /** 0 = both bottom corners, 1 = left only, 2 = right only, 3 = side midpoints. */
    var triggerPos: Int
        get() = prefs.getInt("trigger_pos", 0)
        set(v) = prefs.edit().putInt("trigger_pos", v).apply()

    /** Edge-hugging mode requires system gesture insets disabled (root helper). */
    var edgeMode: Boolean
        get() = prefs.getBoolean("edge_mode", false)
        set(v) = prefs.edit().putBoolean("edge_mode", v).apply()

    fun zones(screenW: Int, screenH: Int, density: Float): List<CornerZone> {
        val ins = insetDp * density
        val w = widthDp * density
        val h = heightDp * density
        return when (triggerPos) {
            1 -> listOf(leftBottom(ins, w, h, screenW, screenH))
            2 -> listOf(rightBottom(ins, w, h, screenW, screenH))
            3 -> listOf(
                CornerZone(Corner.SIDE_LEFT, 0f, screenH * 0.3f, 24f * density, screenH * 0.7f),
                CornerZone(Corner.SIDE_RIGHT, screenW - 24f * density, screenH * 0.3f, screenW.toFloat(), screenH * 0.7f),
            )
            else -> listOf(leftBottom(ins, w, h, screenW, screenH), rightBottom(ins, w, h, screenW, screenH))
        }
    }

    private fun leftBottom(ins: Float, w: Float, h: Float, sw: Int, sh: Int) =
        CornerZone(Corner.BOTTOM_LEFT, ins, sh - h, ins + w, sh.toFloat())

    private fun rightBottom(ins: Float, w: Float, h: Float, sw: Int, sh: Int) =
        CornerZone(Corner.BOTTOM_RIGHT, sw - ins - w, sh - h, sw - ins, sh.toFloat())
}
