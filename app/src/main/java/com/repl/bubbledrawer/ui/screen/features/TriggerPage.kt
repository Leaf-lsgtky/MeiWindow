package com.repl.bubbledrawer.ui.screen.features

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.app.AppSettings
import com.repl.bubbledrawer.settings.ArrowRow
import com.repl.bubbledrawer.settings.DropdownRow
import com.repl.bubbledrawer.settings.HintRow
import com.repl.bubbledrawer.settings.IntSliderRow
import com.repl.bubbledrawer.settings.RemotePrefsMaxDp
import com.repl.bubbledrawer.settings.RemotePrefsMinDp
import com.repl.bubbledrawer.settings.SettingsActions
import com.repl.bubbledrawer.settings.StoreSettingsActions
import com.repl.bubbledrawer.ui.component.CardItem
import com.repl.bubbledrawer.ui.component.TriggerZonePreview
import com.repl.bubbledrawer.ui.component.groupedCardItems
import com.repl.bubbledrawer.ui.theme.BubbleDrawerTheme
import com.repl.bubbledrawer.ui.util.LeadSpacer
import com.repl.bubbledrawer.ui.util.PageBottomSpacer
import com.repl.bubbledrawer.ui.util.PageScaffold
import com.repl.bubbledrawer.xposed.SettingsStore
import com.repl.bubbledrawer.xposed.SettingsUiState
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

class TriggerActivity : ComponentActivity() {
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
                TriggerPage(
                    state = state,
                    actions = actions,
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
fun TriggerPage(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snap = state.snapshot
    var previewTriggerZone by remember { mutableStateOf(false) }

    BackHandler(enabled = previewTriggerZone) {
        previewTriggerZone = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PageScaffold(
            title = stringResource(R.string.features_trigger_title),
            onBack = onBack,
        ) {
            item(key = "lead") { LeadSpacer() }

            groupedCardItems(
                keyPrefix = "trigger_pos",
                items = listOf(
                    CardItem("pos") {
                        DropdownRow(
                            title = stringResource(R.string.trigger_pos),
                            items = listOf(
                                stringResource(R.string.pos_both),
                                stringResource(R.string.pos_left),
                                stringResource(R.string.pos_right),
                            ),
                            selectedIndex = state.triggerPos,
                            enabled = snap.enabled,
                            onSelect = actions::setTriggerPos,
                        )
                    },
                ),
            )

            groupedCardItems(
                keyPrefix = "blacklist_entry",
                items = listOf(
                    CardItem("gesture_blacklist") {
                        ArrowRow(
                            title = stringResource(R.string.gesture_blacklist),
                            summary = if (snap.fanBlacklist.isEmpty()) {
                                stringResource(R.string.gesture_blacklist_empty)
                            } else {
                                stringResource(R.string.gesture_blacklist_count, snap.fanBlacklist.size)
                            },
                            enabled = snap.enabled,
                            onClick = {
                                context.startActivity(Intent(context, GestureBlacklistActivity::class.java))
                            },
                        )
                    },
                ),
            )

            groupedCardItems(
                keyPrefix = "zone",
                items = buildList {
                    add(CardItem("bottom") {
                        IntSliderRow(
                            title = stringResource(R.string.range_bottom),
                            summary = stringResource(R.string.range_bottom_summary),
                            enabled = snap.enabled,
                            value = snap.bottomDp,
                            min = RemotePrefsMinDp,
                            max = RemotePrefsMaxDp,
                            valueText = { stringResource(R.string.dp_value, it) },
                            onCommit = { actions.setBottomDp(it) },
                        )
                    })
                    add(CardItem("edge") {
                        IntSliderRow(
                            title = stringResource(R.string.range_edge),
                            summary = stringResource(R.string.range_edge_summary),
                            enabled = snap.enabled,
                            value = snap.edgeDp,
                            min = RemotePrefsMinDp,
                            max = RemotePrefsMaxDp,
                            valueText = { stringResource(R.string.dp_value, it) },
                            onCommit = { actions.setEdgeDp(it) },
                        )
                    })
                    add(CardItem("preview") {
                        ArrowRow(
                            title = stringResource(R.string.preview_trigger_zone),
                            enabled = snap.enabled,
                            onClick = { previewTriggerZone = true },
                        )
                    })
                },
            )

            groupedCardItems(
                keyPrefix = "range_hint",
                items = listOf(
                    CardItem("hint") {
                        HintRow(stringResource(R.string.range_hint))
                    },
                ),
            )

            item(key = "bottom-spacer") { PageBottomSpacer() }
        }

        AnimatedVisibility(
            visible = previewTriggerZone,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            TriggerZonePreview(
                snap = state.snapshot,
                onDismiss = { previewTriggerZone = false },
            )
        }
    }
}
