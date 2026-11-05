package com.repl.bubbledrawer.ui.screen.about

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.app.AppSettings
import com.repl.bubbledrawer.settings.ArrowRow
import com.repl.bubbledrawer.ui.component.CardItem
import com.repl.bubbledrawer.ui.component.groupedCardItems
import com.repl.bubbledrawer.ui.theme.BubbleDrawerTheme
import com.repl.bubbledrawer.ui.util.LeadSpacer
import com.repl.bubbledrawer.ui.util.PageBottomSpacer
import com.repl.bubbledrawer.ui.util.PageScaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

class CreditsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appSettings = AppSettings.load(this)
        val themeMode = runCatching { ColorSchemeMode.valueOf(appSettings.themeMode) }.getOrDefault(ColorSchemeMode.System)

        setContent {
            BubbleDrawerTheme(themeMode = themeMode) {
                CreditsPage(onBack = { finish() })
            }
        }
    }
}

@Composable
fun CreditsPage(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    val credits = listOf(
        OpenSourceLibrary(
            name = "HyperMusicCover",
            description = "Architecture design, UI guidelines, and Miuix Compose navigation reference",
            url = "https://github.com",
        ),
        OpenSourceLibrary(
            name = "Meizu Flyme",
            description = "Inspiration for gesture bubble drawer, lightweight freeform, and quick contact bar",
            url = "https://www.flyme.com",
        ),
        OpenSourceLibrary(
            name = "Mishka & KernelSU",
            description = "UI interaction paradigms, blurred bars, and system integration practices",
            url = "https://kernelsu.org",
        ),
        OpenSourceLibrary(
            name = "LSPosed Developers",
            description = "ART hooking framework providing robust runtime module support",
            url = "https://github.com/LSPosed/LSPosed",
        ),
    )

    PageScaffold(
        title = stringResource(R.string.about_credits),
        onBack = onBack,
    ) {
        item(key = "lead") { LeadSpacer() }

        groupedCardItems(
            keyPrefix = "credits_list",
            items = credits.mapIndexed { index, item ->
                CardItem("credit_$index") {
                    ArrowRow(
                        title = item.name,
                        summary = item.description,
                        onClick = { uriHandler.openUri(item.url) },
                    )
                }
            },
        )

        item(key = "bottom-spacer") { PageBottomSpacer() }
    }
}
