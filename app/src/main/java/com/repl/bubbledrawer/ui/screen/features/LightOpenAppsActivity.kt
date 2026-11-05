package com.repl.bubbledrawer.ui.screen.features

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repl.bubbledrawer.app.AppSettings
import com.repl.bubbledrawer.settings.LightOpenAppsScreen
import com.repl.bubbledrawer.settings.StoreSettingsActions
import com.repl.bubbledrawer.ui.theme.BubbleDrawerTheme
import com.repl.bubbledrawer.xposed.SettingsStore
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

class LightOpenAppsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val actions = StoreSettingsActions(applicationContext)
        val stateFlow = SettingsStore.observe(applicationContext)

        val appSettings = AppSettings.load(this)
        val themeMode = runCatching { ColorSchemeMode.valueOf(appSettings.themeMode) }.getOrDefault(ColorSchemeMode.System)

        setContent {
            BubbleDrawerTheme(themeMode = themeMode) {
                val state by stateFlow.collectAsStateWithLifecycle(
                    initialValue = SettingsStore.currentState(applicationContext),
                )
                LightOpenAppsScreen(
                    state = state,
                    actions = actions,
                    onBack = { finish() },
                )
            }
        }
    }
}
