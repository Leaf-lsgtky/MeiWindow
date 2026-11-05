package com.repl.bubbledrawer.ui.util

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.repl.bubbledrawer.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Standard page frame adhering strictly to docs/ui-guidelines.md:
 * - Scaffold + TopAppBar(scrollBehavior) + LazyColumn
 * - BlurredBar with backdrop & transparent bar color when blur is active
 * - pageScroll (scrollEndHaptic + overScrollVertical + nestedScroll)
 * - contentSidePadding for wide-screen centering
 * - horizontalCutoutPadding on secondary pages (onBack != null)
 * - extraBottomPadding support for the 4 Pager Tabs
 */
@Composable
fun PageScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    extraBottomPadding: Dp = 0.dp,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val isSecondary = onBack != null
    val barColor = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        floatingActionButton = floatingActionButton,
        topBar = {
            BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    title = title,
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        if (onBack != null) {
                            PageBackButton(onBack = onBack)
                        }
                    },
                    actions = actions,
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        val direction = LocalLayoutDirection.current
        val side = contentSidePadding()
        val contentPadding = PaddingValues(
            start = innerPadding.calculateStartPadding(direction) + side,
            top = innerPadding.calculateTopPadding() + 8.dp,
            end = innerPadding.calculateEndPadding(direction) + side,
            bottom = if (extraBottomPadding > 0.dp) innerPadding.calculateBottomPadding() + extraBottomPadding else 0.dp,
        )

        LazyColumn(
            overscrollEffect = null,
            modifier = Modifier
                .fillMaxSize()
                .then(if (isSecondary) Modifier.horizontalCutoutPadding() else Modifier)
                .pageBackdrop(backdrop)
                .pageScroll(scrollBehavior),
            contentPadding = contentPadding,
            content = content,
        )
    }
}

/** Standard back navigation button */
@Composable
fun PageBackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        val layoutDirection = LocalLayoutDirection.current
        Icon(
            modifier = Modifier.graphicsLayer {
                if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
            },
            imageVector = MiuixIcons.Back,
            contentDescription = stringResource(R.string.back),
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}
