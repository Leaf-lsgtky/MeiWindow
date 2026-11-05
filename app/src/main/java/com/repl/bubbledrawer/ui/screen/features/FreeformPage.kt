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
import com.repl.bubbledrawer.settings.DropdownRow
import com.repl.bubbledrawer.settings.HintRow
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

class FreeformActivity : ComponentActivity() {
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
                FreeformPage(
                    state = state,
                    actions = actions,
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
fun FreeformPage(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
) {
    val snap = state.snapshot
    val freeformOn = snap.flymeFreeformEnabled

    PageScaffold(
        title = stringResource(R.string.features_freeform_title),
        onBack = onBack,
    ) {
        item(key = "exp-lead") { LeadSpacer() }

        // --- 核心总开关 ---
        item(key = "exp-master-title") {
            SmallTitle(text = stringResource(R.string.exp_flyme_freeform_title))
        }
        groupedCardItems(
            keyPrefix = "exp-master",
            items = listOf(
                CardItem("enable-flyme-freeform") {
                    SwitchRow(
                        title = stringResource(R.string.exp_flyme_freeform_title),
                        summary = stringResource(R.string.exp_flyme_freeform_summary),
                        checked = snap.flymeFreeformEnabled,
                        onCheckedChange = actions::setFlymeFreeformEnabled,
                    )
                },
            ),
        )

        // --- 窗口尺寸与位置 ---
        item(key = "exp-window-title") {
            SmallTitle(text = stringResource(R.string.exp_section_window_tuning))
        }
        groupedCardItems(
            keyPrefix = "exp-window",
            items = buildList {
                add(CardItem("center-window") {
                    SwitchRow(
                        title = stringResource(R.string.exp_center_window),
                        summary = stringResource(R.string.exp_center_window_summary),
                        checked = snap.flymeFreeformCenter,
                        enabled = freeformOn,
                        onCheckedChange = actions::setFlymeFreeformCenter,
                    )
                })
                add(CardItem("vertical-window") {
                    IntSliderRow(
                        title = stringResource(R.string.exp_window_vertical),
                        summary = stringResource(R.string.exp_window_vertical_hint),
                        value = snap.flymeFreeformVerticalPct,
                        min = RemotePrefs.FLYME_FREEFORM_VERTICAL_MIN,
                        max = RemotePrefs.FLYME_FREEFORM_VERTICAL_MAX,
                        enabled = freeformOn,
                        valueText = { stringResource(R.string.pct_value, it) },
                        onCommit = actions::setFlymeFreeformVerticalPct,
                    )
                })
                add(CardItem("scale-window") {
                    IntSliderRow(
                        title = stringResource(R.string.exp_window_scale),
                        summary = stringResource(R.string.exp_window_scale_hint),
                        value = snap.flymeFreeformScale,
                        min = RemotePrefs.FLYME_FREEFORM_SCALE_MIN,
                        max = RemotePrefs.FLYME_FREEFORM_SCALE_MAX,
                        enabled = freeformOn,
                        valueText = { stringResource(R.string.pct_value, it) },
                        onCommit = actions::setFlymeFreeformScale,
                    )
                })
                add(CardItem("dim-bg") {
                    SwitchRow(
                        title = stringResource(R.string.exp_dim_background),
                        summary = stringResource(R.string.exp_dim_background_summary),
                        checked = snap.flymeFreeformDimBg,
                        enabled = freeformOn,
                        onCheckedChange = actions::setFlymeFreeformDimBg,
                    )
                })
            },
        )

        // --- 小白条手势交互 ---
        item(key = "exp-gestures-title") {
            SmallTitle(text = stringResource(R.string.exp_section_gestures))
        }
        groupedCardItems(
            keyPrefix = "exp-gestures",
            items = buildList {
                add(CardItem("outside-dismiss") {
                    SwitchRow(
                        title = stringResource(R.string.exp_outside_dismiss),
                        summary = stringResource(R.string.exp_outside_dismiss_summary),
                        checked = snap.flymeFreeformOutsideDismiss,
                        enabled = freeformOn,
                        onCheckedChange = actions::setFlymeFreeformOutsideDismiss,
                    )
                })
                add(CardItem("outside-dismiss-action") {
                    DropdownRow(
                        title = stringResource(R.string.exp_outside_dismiss_action),
                        items = listOf(
                            stringResource(R.string.exp_outside_dismiss_action_single),
                            stringResource(R.string.exp_outside_dismiss_action_double),
                        ),
                        selectedIndex = snap.flymeFreeformOutsideDismissAction,
                        enabled = freeformOn && snap.flymeFreeformOutsideDismiss,
                        onSelect = actions::setFlymeFreeformOutsideDismissAction,
                    )
                })
                add(CardItem("swipe-up-mini") {
                    SwitchRow(
                        title = stringResource(R.string.exp_swipe_up_mini),
                        summary = stringResource(R.string.exp_swipe_up_mini_summary),
                        checked = snap.flymeFreeformSwipeUpMini,
                        enabled = freeformOn,
                        onCheckedChange = actions::setFlymeFreeformSwipeUpMini,
                    )
                })
                add(CardItem("swipe-up-hold-free") {
                    SwitchRow(
                        title = stringResource(R.string.exp_swipe_up_hold_free),
                        summary = stringResource(R.string.exp_swipe_up_hold_free_summary),
                        checked = snap.flymeFreeformSwipeUpHoldFree,
                        enabled = freeformOn,
                        onCheckedChange = actions::setFlymeFreeformSwipeUpHoldFree,
                    )
                })
            },
        )

        item(key = "exp-bottom-spacer") { PageBottomSpacer() }
    }
}
