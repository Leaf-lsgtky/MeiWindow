package com.repl.bubbledrawer.app

import android.app.UiModeManager
import android.content.Context
import androidx.core.content.edit
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * Makes the system resolve this app's resources by the theme picked inside the app.
 */
object AppNightMode {

    private const val PREFS_NAME = "launch_background"
    private const val KEY_APPLIED = "night_mode"

    private fun nightModeFor(themeMode: String): Int = when (themeMode) {
        ColorSchemeMode.Light.name, ColorSchemeMode.MonetLight.name -> UiModeManager.MODE_NIGHT_NO
        ColorSchemeMode.Dark.name, ColorSchemeMode.MonetDark.name -> UiModeManager.MODE_NIGHT_YES
        else -> UiModeManager.MODE_NIGHT_AUTO
    }

    fun apply(context: Context, themeMode: String) {
        val wanted = nightModeFor(themeMode)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_APPLIED, Int.MIN_VALUE) == wanted) return
        try {
            context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(wanted)
            prefs.edit { putInt(KEY_APPLIED, wanted) }
        } catch (_: Throwable) {
            // OEM might refuse, safe to ignore
        }
    }
}
