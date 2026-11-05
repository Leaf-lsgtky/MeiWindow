package com.repl.bubbledrawer.app

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Settings that belong to the app itself — theme mode, navbar style, and blur effects.
 */
data class AppSettings(
    val themeMode: String = "System",
    val isFloatingNavbar: Boolean = false,
    val isBlurEnabled: Boolean = true,
) {
    fun toJson(): String = JSONObject().apply {
        put("themeMode", themeMode)
        put("isFloatingNavbar", isFloatingNavbar)
        put("isBlurEnabled", isBlurEnabled)
    }.toString(2)

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_FLOATING_NAVBAR = "floating_navbar"
        private const val KEY_BLUR_ENABLED = "blur_enabled"

        fun fromJson(json: String): AppSettings = try {
            val obj = JSONObject(json)
            AppSettings(
                themeMode = obj.optString("themeMode", "System"),
                isFloatingNavbar = obj.optBoolean("isFloatingNavbar", false),
                isBlurEnabled = obj.optBoolean("isBlurEnabled", true),
            )
        } catch (_: Exception) {
            AppSettings()
        }

        fun load(context: Context): AppSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return AppSettings(
                themeMode = prefs.getString(KEY_THEME_MODE, "System") ?: "System",
                isFloatingNavbar = prefs.getBoolean(KEY_FLOATING_NAVBAR, false),
                isBlurEnabled = prefs.getBoolean(KEY_BLUR_ENABLED, true),
            )
        }

        fun save(context: Context, settings: AppSettings) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_THEME_MODE, settings.themeMode)
                putBoolean(KEY_FLOATING_NAVBAR, settings.isFloatingNavbar)
                putBoolean(KEY_BLUR_ENABLED, settings.isBlurEnabled)
            }
        }
    }
}
