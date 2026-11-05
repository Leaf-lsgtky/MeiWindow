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

data class OpenSourceLibrary(
    val name: String,
    val description: String,
    val url: String,
)

class LicenseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appSettings = AppSettings.load(this)
        val themeMode = runCatching { ColorSchemeMode.valueOf(appSettings.themeMode) }.getOrDefault(ColorSchemeMode.System)

        setContent {
            BubbleDrawerTheme(themeMode = themeMode) {
                LicensePage(onBack = { finish() })
            }
        }
    }
}

@Composable
fun LicensePage(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    val libraries = listOf(
        OpenSourceLibrary(
            name = "Miuix (Compose)",
            description = "HyperOS style UI component library for Compose Multiplatform",
            url = "https://github.com/compose-miuix-ui/miuix",
        ),
        OpenSourceLibrary(
            name = "Jetpack Compose",
            description = "Android's modern toolkit for building native UI",
            url = "https://developer.android.com/jetpack/compose",
        ),
        OpenSourceLibrary(
            name = "KotlinX Coroutines & Collections",
            description = "Library support for Kotlin coroutines and immutable collections",
            url = "https://github.com/Kotlin/kotlinx.coroutines",
        ),
        OpenSourceLibrary(
            name = "LibXposed",
            description = "Next-generation Xposed API implementation",
            url = "https://github.com/libxposed/api",
        ),
        OpenSourceLibrary(
            name = "HiddenApiBypass",
            description = "Bypass Android hidden-API restrictions without root or adb",
            url = "https://github.com/LSPosed/AndroidHiddenApiBypass",
        ),
        OpenSourceLibrary(
            name = "CircleImageView",
            description = "A fast circular ImageView perfect for profile images",
            url = "https://github.com/hdodenhof/CircleImageView",
        ),
    )

    PageScaffold(
        title = stringResource(R.string.about_licenses),
        onBack = onBack,
    ) {
        item(key = "lead") { LeadSpacer() }

        groupedCardItems(
            keyPrefix = "licenses_list",
            items = libraries.mapIndexed { index, lib ->
                CardItem("lib_$index") {
                    ArrowRow(
                        title = lib.name,
                        summary = lib.description,
                        onClick = { uriHandler.openUri(lib.url) },
                    )
                }
            },
        )

        item(key = "bottom-spacer") { PageBottomSpacer() }
    }
}
