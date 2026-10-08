package com.repl.bubbledrawer.ui.util

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Density that ignores BOTH user scalings (display size and font scale) — the
 * `LocalPlatformDensity` the guidelines assume. miuix 0.9.3 does not export such a
 * local (verified: no `LocalPlatformDensity` anywhere in the pinned v0.9.3 tree), and
 * Compose's [LocalDensity] / [LocalConfiguration] both fold the scaling in, which is
 * exactly what we must not do when deciding a layout SHELL.
 *
 * Why it matters: `Configuration.screenWidthDp` is computed with the font-scaled density,
 * so turning on a large font makes a narrow phone look "wide" and would flip the bottom
 * bar into a NavigationRail mid-conversation. Measuring raw pixels against the platform
 * density keeps the shell stable, which is the behavior docs/ui-guidelines.md asks for.
 */
@Composable
fun platformDensity(): Float {
    val density = LocalContext.current.resources.displayMetrics.density
    return if (density > 0f) density else LocalDensity.current.density
}

/** Window width in dp, measured pre-scaling. */
@Composable
fun windowWidthDp(): Dp {
    val widthPx = LocalWindowInfo.current.containerSize.width
    return with(androidx.compose.ui.unit.Density(platformDensity())) { widthPx.toDp() }
}

/** Below this width the shell is a bottom bar; above it, a rail (guidelines constant). */
val WideScreenMinWidth = 600.dp

/**
 * The single authority for "is this a wide layout". Every shell decision must consume
 * THIS value rather than re-comparing a threshold, because two independent comparisons
 * of an unscaled width vs. a scaled one disagree exactly when the user scales the UI.
 */
@Composable
fun rememberIsWideScreen(): Boolean = windowWidthDp() >= WideScreenMinWidth

/**
 * Content cap, independent of [WideScreenMinWidth] (guidelines: two separate constants).
 * Applied as EXTRA horizontal `contentPadding` on a still-full-width list, so the rows
 * centre without shrinking the scroll area (no dead zones at the sides).
 */
val MaxContentWidth = 800.dp

@Composable
fun contentSidePadding(): Dp {
    val window = windowWidthDp()
    if (window < WideScreenMinWidth) return 0.dp
    val slack = window - MaxContentWidth
    return if (slack > 0.dp) slack / 2 else 0.dp
}
