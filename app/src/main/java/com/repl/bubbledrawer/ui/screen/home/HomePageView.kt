package com.repl.bubbledrawer.ui.screen.home

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.settings.SettingsActions
import com.repl.bubbledrawer.settings.SwitchRow
import com.repl.bubbledrawer.ui.component.CardItem
import com.repl.bubbledrawer.ui.component.StatusCard
import com.repl.bubbledrawer.ui.component.groupedCardItems
import com.repl.bubbledrawer.ui.util.LeadSpacer
import com.repl.bubbledrawer.ui.util.PageBottomSpacer
import com.repl.bubbledrawer.ui.util.PageScaffold
import com.repl.bubbledrawer.xposed.SettingsUiState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh

@Composable
fun HomePageView(
    state: SettingsUiState,
    actions: SettingsActions,
    extraBottomPadding: Dp = 0.dp,
) {
    val snap = state.snapshot

    PageScaffold(
        title = stringResource(R.string.tab_home),
        extraBottomPadding = extraBottomPadding,
        actions = {
            IconButton(onClick = actions::restartSystemUi) {
                Icon(
                    imageVector = MiuixIcons.Refresh,
                    contentDescription = stringResource(R.string.restart_systemui),
                )
            }
        },
    ) {
        item(key = "lead") { LeadSpacer() }

        item(key = "status-card") {
            StatusCard(
                connected = state.connected,
                // 12dp 水平内缩，和下面 groupedCardItems 的卡片对齐（ui-guidelines.md「Card 间距」）；
                // 卡片本身不留内边距，否则状态色的边缘会比别的卡窄一圈。
                modifier = Modifier.padding(horizontal = 12.dp),
                frameworkLabel = state.frameworkLabel,
                onClick = actions::restartSystemUi,
            )
        }

        // Global master switches
        item(key = "global-title") {
            SmallTitle(text = stringResource(R.string.global_section_title))
        }
        groupedCardItems(
            keyPrefix = "global",
            items = buildList {
                add(CardItem("enable") {
                    SwitchRow(
                        title = stringResource(R.string.enable_service),
                        summary = stringResource(R.string.enable_service_summary),
                        checked = snap.enabled,
                        onCheckedChange = actions::setEnabled,
                    )
                })
                add(CardItem("freeform") {
                    SwitchRow(
                        title = stringResource(R.string.freeform_toggle),
                        summary = stringResource(R.string.freeform_hint),
                        checked = snap.freeform,
                        enabled = snap.enabled,
                        onCheckedChange = actions::setFreeform,
                    )
                })
                add(CardItem("landscape_two_swipes") {
                    SwitchRow(
                        title = stringResource(R.string.fan_landscape_two_swipes),
                        summary = stringResource(R.string.fan_landscape_two_swipes_summary),
                        checked = snap.fanLandscapeTwoSwipes,
                        enabled = snap.enabled,
                        onCheckedChange = actions::setFanLandscapeTwoSwipes,
                    )
                })
            },
        )

        item(key = "bottom-spacer") { PageBottomSpacer() }
    }
}
