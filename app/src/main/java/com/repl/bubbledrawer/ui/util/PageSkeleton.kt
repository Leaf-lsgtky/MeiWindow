package com.repl.bubbledrawer.ui.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * The page-skeleton rules from docs/ui-guidelines.md, written once so every page scrolls,
 * pads and terminates identically:
 *  - the scrolling container carries edge haptics + vertical overscroll + the bar's
 *    nested-scroll connection;
 *  - `contentPadding` sets TOP (and the wide-screen side inset) only — the bottom is left
 *    to the mandatory trailing [PageBottomSpacer];
 *  - secondary-page signatures therefore never take a `bottomPadding: Dp` parameter.
 */

/** Apply to the scrolling container (LazyColumn / LazyVerticalGrid) of a page. */
fun Modifier.pageScroll(scrollBehavior: ScrollBehavior?): Modifier = this
    .scrollEndHaptic()
    .overScrollVertical()
    .then(
        if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
        else Modifier,
    )

/**
 * Top-only content padding derived from the Scaffold inner padding, plus the wide-screen
 * side inset from [contentSidePadding] — the list itself stays full width so there are no
 * dead zones beside it (guidelines: 内容居中只加 contentPadding).
 */
@Composable
fun pageContentPadding(
    innerPadding: PaddingValues,
    extraTop: Dp = 8.dp,
): PaddingValues {
    val direction = LocalLayoutDirection.current
    val side = contentSidePadding()
    return PaddingValues(
        start = innerPadding.calculateStartPadding(direction) + side,
        top = innerPadding.calculateTopPadding() + extraTop,
        end = innerPadding.calculateEndPadding(direction) + side,
    )
}

/**
 * Mandatory trailing item content — `Spacer(Modifier.height(24.dp).navigationBarsPadding())`.
 * Call it as `item { PageBottomSpacer() }` at the end of every page's lazy list.
 */
@Composable
fun PageBottomSpacer() {
    Spacer(Modifier.height(24.dp).navigationBarsPadding())
}

/**
 * The 12.dp lead-in for a page whose FIRST item is a Card or a form. Pages opening with
 * SmallTitle or RestartRequiredHint must NOT use it — both bring their own vertical margin.
 */
@Composable
fun LeadSpacer() {
    Spacer(Modifier.height(12.dp))
}
