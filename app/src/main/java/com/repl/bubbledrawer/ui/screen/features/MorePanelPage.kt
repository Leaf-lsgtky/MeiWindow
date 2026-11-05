package com.repl.bubbledrawer.ui.screen.features

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.app.AppSettings
import com.repl.bubbledrawer.pin.PinManageActivity
import com.repl.bubbledrawer.settings.ArrowRow
import com.repl.bubbledrawer.settings.DefaultableIntSliderRow
import com.repl.bubbledrawer.settings.DropdownRow
import com.repl.bubbledrawer.settings.HintRow
import com.repl.bubbledrawer.settings.RemotePrefsIconMax
import com.repl.bubbledrawer.settings.RemotePrefsIconMin
import com.repl.bubbledrawer.settings.RemotePrefsPctMax
import com.repl.bubbledrawer.settings.RemotePrefsPctMin
import com.repl.bubbledrawer.settings.RemotePrefsTextMax
import com.repl.bubbledrawer.settings.RemotePrefsTextMin
import com.repl.bubbledrawer.settings.SettingsActions
import com.repl.bubbledrawer.settings.StoreSettingsActions
import com.repl.bubbledrawer.settings.SwitchRow
import com.repl.bubbledrawer.ui.component.CardItem
import com.repl.bubbledrawer.ui.component.IntInputRow
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

class MorePanelActivity : ComponentActivity() {
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
                MorePanelPage(
                    state = state,
                    actions = actions,
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
fun MorePanelPage(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snap = state.snapshot

    PageScaffold(
        title = stringResource(R.string.features_panel_title),
        onBack = onBack,
    ) {
        item(key = "lead") { LeadSpacer() }

        // --- 面板尺寸与排版 ---
        groupedCardItems(
            keyPrefix = "panel_dimensions",
            items = buildList {
                add(CardItem("w") {
                    DefaultableIntSliderRow(
                        title = stringResource(R.string.panel_width),
                        value = snap.panelWidthPct,
                        defaultValue = RemotePrefs.DEFAULT_PANEL_W_PCT,
                        min = RemotePrefsPctMin,
                        max = RemotePrefsPctMax,
                        valueText = { stringResource(R.string.pct_value, it) },
                        onCommit = actions::setPanelWidthPct,
                    )
                })
                add(CardItem("h") {
                    DefaultableIntSliderRow(
                        title = stringResource(R.string.panel_height),
                        value = snap.panelHeightPct,
                        defaultValue = RemotePrefs.DEFAULT_PANEL_H_PCT,
                        min = RemotePrefsPctMin,
                        max = RemotePrefsPctMax,
                        valueText = { stringResource(R.string.pct_value, it) },
                        onCommit = actions::setPanelHeightPct,
                    )
                })
                add(CardItem("icon") {
                    DefaultableIntSliderRow(
                        title = stringResource(R.string.panel_icon),
                        value = snap.panelIconDp,
                        defaultValue = RemotePrefs.DEFAULT_PANEL_ICON_DP,
                        min = RemotePrefsIconMin,
                        max = RemotePrefsIconMax,
                        valueText = { stringResource(R.string.dp_value, it) },
                        onCommit = actions::setPanelIconDp,
                    )
                })
                add(CardItem("text") {
                    DefaultableIntSliderRow(
                        title = stringResource(R.string.panel_text),
                        value = snap.panelTextSp,
                        defaultValue = RemotePrefs.DEFAULT_PANEL_TEXT_SP,
                        min = RemotePrefsTextMin,
                        max = RemotePrefsTextMax,
                        valueText = { stringResource(R.string.sp_value, it) },
                        onCommit = actions::setPanelTextSp,
                    )
                })
                add(CardItem("dismiss_outside") {
                    DropdownRow(
                        title = stringResource(R.string.panel_dismiss_outside),
                        items = listOf(
                            stringResource(R.string.panel_dismiss_outside_single),
                            stringResource(R.string.panel_dismiss_outside_double),
                        ),
                        selectedIndex = snap.panelDismissOutside,
                        onSelect = actions::setPanelDismissOutside,
                    )
                })
            },
        )
        groupedCardItems(
            keyPrefix = "more_panel_hint_group",
            items = listOf(
                CardItem("hint") {
                    HintRow(stringResource(R.string.more_panel_hint))
                },
            ),
        )

        // --- 推荐应用 ---
        item(key = "recommend_title") {
            SmallTitle(text = stringResource(R.string.recommend_section_title))
        }
        groupedCardItems(
            keyPrefix = "recommend_group",
            items = buildList {
                add(CardItem("enable") {
                    SwitchRow(
                        title = stringResource(R.string.recommend_apps_enable),
                        summary = stringResource(R.string.recommend_apps_hint),
                        checked = snap.recommendEnabled,
                        onCheckedChange = actions::setRecommendEnabled,
                    )
                })
                add(CardItem("count") {
                    IntInputRow(
                        title = stringResource(R.string.recommend_apps_count),
                        summary = stringResource(R.string.recommend_apps_count_summary),
                        value = snap.recommendCount,
                        min = RemotePrefs.RECOMMEND_COUNT_MIN,
                        max = RemotePrefs.RECOMMEND_COUNT_MAX,
                        enabled = snap.recommendEnabled,
                        valueText = { stringResource(R.string.recommend_apps_count_format, it) },
                        onCommit = actions::setRecommendCount,
                    )
                })
            },
        )

        item(key = "bottom-spacer") { PageBottomSpacer() }
    }
}
