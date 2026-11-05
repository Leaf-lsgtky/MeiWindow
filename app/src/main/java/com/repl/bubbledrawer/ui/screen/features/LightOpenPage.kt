package com.repl.bubbledrawer.ui.screen.features

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.app.AppSettings
import com.repl.bubbledrawer.lightopen.LightOpen
import com.repl.bubbledrawer.settings.ArrowRow
import com.repl.bubbledrawer.settings.DropdownRow
import com.repl.bubbledrawer.settings.HintRow
import com.repl.bubbledrawer.settings.SettingsActions
import com.repl.bubbledrawer.settings.StoreSettingsActions
import com.repl.bubbledrawer.settings.SwitchRow
import com.repl.bubbledrawer.ui.component.CardItem
import com.repl.bubbledrawer.ui.component.groupedCardItems
import com.repl.bubbledrawer.ui.theme.BubbleDrawerTheme
import com.repl.bubbledrawer.ui.util.LeadSpacer
import com.repl.bubbledrawer.ui.util.PageBottomSpacer
import com.repl.bubbledrawer.ui.util.PageScaffold
import com.repl.bubbledrawer.xposed.SettingsStore
import com.repl.bubbledrawer.xposed.SettingsUiState
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

class LightOpenActivity : ComponentActivity() {
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
                LightOpenPage(
                    state = state,
                    actions = actions,
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
fun LightOpenPage(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snap = state.snapshot
    val master = snap.lightOpenEnabled
    val targets = remember(snap.lightOpenJumpList) { LightOpen.decodeTargets(snap.lightOpenJumpList) }

    PageScaffold(
        title = stringResource(R.string.features_light_open_title),
        onBack = onBack,
    ) {
        item(key = "lead") { LeadSpacer() }

        // --- 总开关 ---
        groupedCardItems(
            keyPrefix = "light_master",
            items = listOf(
                CardItem("master") {
                    SwitchRow(
                        title = stringResource(R.string.light_open_enable),
                        summary = stringResource(R.string.light_open_enable_summary),
                        checked = snap.lightOpenEnabled,
                        onCheckedChange = actions::setLightOpenEnabled,
                    )
                },
            ),
        )

        // --- 通知 ---
        item(key = "notif_title") {
            SmallTitle(text = stringResource(R.string.light_open_section_notification))
        }
        groupedCardItems(
            keyPrefix = "light_notif",
            items = listOf(
                CardItem("notif_portrait") {
                    SwitchRow(
                        title = stringResource(R.string.light_open_notif_portrait),
                        summary = stringResource(R.string.light_open_notif_portrait_summary),
                        checked = snap.lightOpenNotifPortrait,
                        enabled = master,
                        onCheckedChange = actions::setLightOpenNotifPortrait,
                    )
                },
                CardItem("notif_landscape") {
                    SwitchRow(
                        title = stringResource(R.string.light_open_notif_landscape),
                        summary = stringResource(R.string.light_open_notif_landscape_summary),
                        checked = snap.lightOpenNotifLandscape,
                        enabled = master,
                        onCheckedChange = actions::setLightOpenNotifLandscape,
                    )
                },
            ),
        )

        // --- 控制中心 ---
        item(key = "control_title") {
            SmallTitle(text = stringResource(R.string.light_open_section_control_center))
        }
        groupedCardItems(
            keyPrefix = "light_control",
            items = listOf(
                CardItem("control_center") {
                    SwitchRow(
                        title = stringResource(R.string.light_open_control_center),
                        summary = stringResource(R.string.light_open_control_center_summary),
                        checked = snap.lightOpenControlCenter,
                        enabled = master,
                        onCheckedChange = actions::setLightOpenControlCenter,
                    )
                },
            ),
        )

        // --- 跳转 ---
        item(key = "jump_title") {
            SmallTitle(text = stringResource(R.string.light_open_section_jump))
        }
        groupedCardItems(
            keyPrefix = "light_jump",
            items = buildList {
                add(CardItem("share") {
                    SwitchRow(
                        title = stringResource(R.string.light_open_share),
                        summary = stringResource(R.string.light_open_share_summary),
                        checked = snap.lightOpenShare,
                        enabled = master,
                        onCheckedChange = actions::setLightOpenShare,
                    )
                })
                add(CardItem("text") {
                    SwitchRow(
                        title = stringResource(R.string.light_open_text),
                        summary = stringResource(R.string.light_open_text_summary),
                        checked = snap.lightOpenText,
                        enabled = master,
                        onCheckedChange = actions::setLightOpenText,
                    )
                })
                add(CardItem("jump") {
                    SwitchRow(
                        title = stringResource(R.string.light_open_jump),
                        summary = stringResource(R.string.light_open_jump_summary),
                        checked = snap.lightOpenJump,
                        enabled = master,
                        onCheckedChange = actions::setLightOpenJump,
                    )
                })
                add(CardItem("jump_mode") {
                    DropdownRow(
                        title = stringResource(R.string.light_open_jump_mode),
                        items = listOf(
                            stringResource(R.string.light_open_jump_mode_white),
                            stringResource(R.string.light_open_jump_mode_black),
                        ),
                        selectedIndex = snap.lightOpenJumpMode.coerceIn(0, 1),
                        enabled = master && snap.lightOpenJump,
                        onSelect = actions::setLightOpenJumpMode,
                    )
                })
                add(CardItem("jump_pick") {
                    ArrowRow(
                        title = stringResource(R.string.light_open_jump_pick),
                        summary = if (targets.isEmpty()) {
                            stringResource(R.string.light_open_jump_pick_empty)
                        } else {
                            stringResource(R.string.light_open_jump_pick_count, targets.size)
                        },
                        enabled = master && snap.lightOpenJump,
                        onClick = {
                            context.startActivity(Intent(context, LightOpenAppsActivity::class.java))
                        },
                    )
                })
            },
        )

        // --- 说明 ---
        item(key = "hint_title") {
            SmallTitle(text = stringResource(R.string.exp_section_info))
        }
        groupedCardItems(
            keyPrefix = "light_hint_card",
            items = listOf(
                CardItem("hint") {
                    HintRow(stringResource(R.string.light_open_hint))
                },
            ),
        )

        item(key = "bottom-spacer") { PageBottomSpacer() }
    }
}
