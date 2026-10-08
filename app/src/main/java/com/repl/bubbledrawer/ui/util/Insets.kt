package com.repl.bubbledrawer.ui.util

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 横屏屏幕缺口补偿 (docs/ui-guidelines.md "横屏屏幕缺口"): a miuix Scaffold does not pad
 * content on the sides, and secondary pages only consume the TOP inset — so in landscape
 * the display cutout / landscape gesture bar would overlay the content. Adds ONLY the
 * horizontal `displayCutout ∪ navigationBars` inset (0 in portrait, where neither reaches
 * the sides).
 *
 * Apply right after `.fillMaxSize()` on a secondary page's root scroll container. Bars are
 * excluded — they handle their own insets. @Composable because the inset getters
 * (WindowInsets.displayCutout / navigationBars) are composable reads.
 */
@Composable
fun Modifier.horizontalCutoutPadding(): Modifier =
    this.windowInsetsPadding(
        WindowInsets.displayCutout.union(WindowInsets.navigationBars)
            .only(WindowInsetsSides.Horizontal),
    )
