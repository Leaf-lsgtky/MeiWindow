package com.repl.bubbledrawer.ui.theme

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Single theme entry point for every Compose surface in this module — both Activities
 * AND the SystemUI overlay panel. The panel deliberately goes through the same wrapper
 * because it hosts the SAME page (方案 B): if it were themed separately it would stop
 * looking like the Activity it mirrors.
 *
 * [ColorSchemeMode.MonetSystem] makes miuix read the ROM's `system_accent1_*` palette
 * (MIUI/HyperOS publishes it) and fall back to the static [lightColorScheme] /
 * [darkColorScheme] when absent. Per docs/ui-guidelines.md the only legal color sources
 * are `MiuixTheme.colorScheme.*` and [StatusColors] — no hand-picked colors below.
 */
@Composable
fun BubbleDrawerTheme(
    content: @Composable () -> Unit,
) {
    val controller = remember {
        ThemeController(
            colorSchemeMode = ColorSchemeMode.System,
            lightColors = lightColorScheme(),
            darkColors = darkColorScheme(),
        )
    }
    MiuixTheme(controller = controller) {
        BarAppearance()
        content()
    }
}

/**
 * Both Activities go edge-to-edge, so they draw under the bars and the bar icon tint has
 * to follow the scheme miuix actually resolved. 0.9.3 exposes no `MiuixTheme.isDarkTheme`,
 * and with Monet the answer is decided inside the library — so read it off the applied
 * background color. Luminance rather than a named-color compare survives a Monet key
 * color too.
 *
 * SideEffect, not LaunchedEffect: this writes a window property, not state. In the
 * overlay panel `LocalView` resolves to a non-Activity context and this is a no-op —
 * the panel owns no window decorations to tint.
 */
@Composable
private fun BarAppearance() {
    val view = LocalView.current
    val dark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}
