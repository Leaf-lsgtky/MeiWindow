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
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

class FreeformOtherActivity : ComponentActivity() {
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
                FreeformOtherPage(
                    state = state,
                    actions = actions,
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
fun FreeformOtherPage(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
) {
    val snap = state.snapshot

    PageScaffold(
        title = stringResource(R.string.features_freeform_other_title),
        onBack = onBack,
    ) {
        item(key = "lead") { LeadSpacer() }

        groupedCardItems(
            keyPrefix = "other_group",
            items = listOf(
                CardItem("no_offset") {
                    SwitchRow(
                        title = stringResource(R.string.exp_no_offset),
                        summary = stringResource(R.string.exp_no_offset_summary),
                        checked = snap.flymeFreeformNoOffset,
                        onCheckedChange = actions::setFlymeFreeformNoOffset,
                    )
                },
                CardItem("home_keep_freeform") {
                    SwitchRow(
                        title = stringResource(R.string.exp_home_keep_freeform),
                        summary = stringResource(R.string.exp_home_keep_freeform_summary),
                        checked = snap.homeKeepFreeform,
                        onCheckedChange = actions::setHomeKeepFreeform,
                    )
                },
            ),
        )

        item(key = "bottom-spacer") { PageBottomSpacer() }
    }
}
