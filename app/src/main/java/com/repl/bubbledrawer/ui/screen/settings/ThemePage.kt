package com.repl.bubbledrawer.ui.screen.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.app.AppNightMode
import com.repl.bubbledrawer.app.AppSettings
import com.repl.bubbledrawer.settings.DropdownRow
import com.repl.bubbledrawer.settings.SwitchRow
import com.repl.bubbledrawer.ui.component.CardItem
import com.repl.bubbledrawer.ui.component.groupedCardItems
import com.repl.bubbledrawer.ui.theme.BubbleDrawerTheme
import com.repl.bubbledrawer.ui.util.LeadSpacer
import com.repl.bubbledrawer.ui.util.PageBottomSpacer
import com.repl.bubbledrawer.ui.util.PageScaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

class ThemeActivity : ComponentActivity() {

    private var currentThemeMode by mutableStateOf(ColorSchemeMode.System)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settings = AppSettings.load(this)
        currentThemeMode = runCatching { ColorSchemeMode.valueOf(settings.themeMode) }.getOrDefault(ColorSchemeMode.System)

        setContent {
            BubbleDrawerTheme(themeMode = currentThemeMode) {
                ThemePage(
                    initialThemeMode = currentThemeMode,
                    initialFloatingNavbar = settings.isFloatingNavbar,
                    initialBlurEnabled = settings.isBlurEnabled,
                    onThemeModeChange = {
                        currentThemeMode = it
                        AppNightMode.apply(this, it.name)
                    },
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
fun ThemePage(
    initialThemeMode: ColorSchemeMode,
    initialFloatingNavbar: Boolean,
    initialBlurEnabled: Boolean,
    onThemeModeChange: (ColorSchemeMode) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var themeMode by remember { mutableStateOf(initialThemeMode) }
    var isFloatingNavbar by remember { mutableStateOf(initialFloatingNavbar) }
    var isBlurEnabled by remember { mutableStateOf(initialBlurEnabled) }

    fun save() {
        AppSettings.save(
            context,
            AppSettings(
                themeMode = themeMode.name,
                isFloatingNavbar = isFloatingNavbar,
                isBlurEnabled = isBlurEnabled,
            ),
        )
    }

    val modeNames = listOf(
        stringResource(R.string.theme_system),
        stringResource(R.string.theme_light),
        stringResource(R.string.theme_dark),
        stringResource(R.string.theme_monet_system),
        stringResource(R.string.theme_monet_light),
        stringResource(R.string.theme_monet_dark),
    )
    val modeValues = listOf(
        ColorSchemeMode.System,
        ColorSchemeMode.Light,
        ColorSchemeMode.Dark,
        ColorSchemeMode.MonetSystem,
        ColorSchemeMode.MonetLight,
        ColorSchemeMode.MonetDark,
    )

    PageScaffold(
        title = stringResource(R.string.settings_theme),
        onBack = onBack,
    ) {
        item(key = "lead") { LeadSpacer() }

        groupedCardItems(
            keyPrefix = "theme_settings",
            items = listOf(
                CardItem("theme_mode") {
                    val currentIndex = modeValues.indexOf(themeMode).coerceAtLeast(0)
                    DropdownRow(
                        title = stringResource(R.string.theme_mode),
                        items = modeNames,
                        selectedIndex = currentIndex,
                        onSelect = {
                            val newMode = modeValues[it]
                            themeMode = newMode
                            onThemeModeChange(newMode)
                            save()
                        },
                    )
                },
                CardItem("floating_bar") {
                    SwitchRow(
                        title = stringResource(R.string.floating_navbar),
                        summary = stringResource(R.string.floating_navbar_summary),
                        checked = isFloatingNavbar,
                        onCheckedChange = {
                            isFloatingNavbar = it
                            save()
                        },
                    )
                },
                CardItem("blur") {
                    SwitchRow(
                        title = stringResource(R.string.blur_enabled),
                        summary = stringResource(R.string.blur_enabled_summary),
                        checked = isBlurEnabled,
                        onCheckedChange = {
                            isBlurEnabled = it
                            save()
                        },
                    )
                },
            ),
        )

        item(key = "bottom-spacer") { PageBottomSpacer() }
    }
}
