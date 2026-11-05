package com.repl.bubbledrawer.ui.screen.features

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.app.AppSettings
import com.repl.bubbledrawer.settings.IntSliderRow
import com.repl.bubbledrawer.settings.SettingsActions
import com.repl.bubbledrawer.settings.StoreSettingsActions
import com.repl.bubbledrawer.settings.SwitchRow
import com.repl.bubbledrawer.ui.component.CardItem
import com.repl.bubbledrawer.ui.component.groupedCardItems
import com.repl.bubbledrawer.ui.theme.BubbleDrawerTheme
import com.repl.bubbledrawer.ui.util.LeadSpacer
import com.repl.bubbledrawer.ui.util.PageBottomSpacer
import com.repl.bubbledrawer.ui.util.PageScaffold
import com.repl.bubbledrawer.xposed.RemotePrefs
import com.repl.bubbledrawer.xposed.SettingsStore
import com.repl.bubbledrawer.xposed.SettingsUiState
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

class FreeformExtendActivity : ComponentActivity() {
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
                FreeformExtendPage(
                    state = state,
                    actions = actions,
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
fun FreeformExtendPage(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
) {
    val snap = state.snapshot

    PageScaffold(
        title = stringResource(R.string.features_freeform_extend_title),
        onBack = onBack,
    ) {
        item(key = "lead") { LeadSpacer() }

        // --- 联系人头像条 ---
        item(key = "contact-bar-title") {
            SmallTitle(text = stringResource(R.string.exp_section_contact_bar))
        }
        groupedCardItems(
            keyPrefix = "contact-bar-group",
            items = buildList {
                add(CardItem("contact-bar") {
                    SwitchRow(
                        title = stringResource(R.string.exp_contact_bar),
                        summary = stringResource(R.string.exp_contact_bar_summary),
                        checked = snap.contactBar,
                        onCheckedChange = actions::setContactBar,
                    )
                })
                add(CardItem("contact-bar-min-width") {
                    IntSliderRow(
                        title = stringResource(R.string.exp_contact_bar_min_width),
                        value = snap.contactBarMinWidthPct,
                        min = RemotePrefs.CONTACT_BAR_MIN_WIDTH_MIN,
                        max = RemotePrefs.CONTACT_BAR_MIN_WIDTH_MAX,
                        enabled = snap.contactBar,
                        valueText = {
                            if (it == 0) stringResource(R.string.exp_contact_bar_min_width_off)
                            else stringResource(R.string.pct_value, it)
                        },
                        onCommit = actions::setContactBarMinWidthPct,
                    )
                })
            },
        )

        // --- 迷你小窗 ---
        item(key = "mini-window-title") {
            SmallTitle(text = stringResource(R.string.exp_section_mini_window))
        }
        groupedCardItems(
            keyPrefix = "mini-window-group",
            items = buildList {
                add(CardItem("flyme-mini-menu") {
                    SwitchRow(
                        title = stringResource(R.string.exp_flyme_mini_menu),
                        summary = stringResource(R.string.exp_flyme_mini_menu_summary),
                        checked = snap.flymeMiniMenu,
                        onCheckedChange = actions::setFlymeMiniMenu,
                    )
                })
                add(CardItem("flyme-mini-enlarge") {
                    IntSliderRow(
                        title = stringResource(R.string.exp_flyme_mini_enlarge),
                        summary = stringResource(R.string.exp_flyme_mini_enlarge_summary),
                        value = snap.flymeMiniEnlargePct,
                        min = RemotePrefs.FLYME_MINI_ENLARGE_MIN,
                        max = RemotePrefs.FLYME_MINI_ENLARGE_MAX,
                        enabled = snap.flymeMiniMenu,
                        valueText = { stringResource(R.string.pct_value, it) },
                        onCommit = actions::setFlymeMiniEnlargePct,
                    )
                })
            },
        )

        item(key = "bottom-spacer") { PageBottomSpacer() }
    }
}
