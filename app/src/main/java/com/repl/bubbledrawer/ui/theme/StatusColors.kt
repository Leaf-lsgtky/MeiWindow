package com.repl.bubbledrawer.ui.theme

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Semantic color tokens (docs/ui-guidelines.md "语义色 token"): screens must not scatter
 * `Color(0xFF...)` literals. Everything here is DERIVED from `MiuixTheme.colorScheme`,
 * so the two legal color sources really are the only two, and Monet / dark mode keeps
 * working everywhere at once.
 *
 * Names describe STATES of this module, not hues: the module has runtime/connection state
 * (LSPosed bridge), a threshold state (trigger zone), and a destructive state (unpin),
 * and those are what a screen needs to ask about.
 */
object StatusColors {

    /** LSPosed remote group bound — the fan lives in SystemUI and reacts live. */
    @Composable
    fun healthy() = MiuixTheme.colorScheme.primary

    /** Bridge not bound: settings are written but nothing consumes them yet. */
    @Composable
    fun warning() = MiuixTheme.colorScheme.onTertiaryContainer

    /** Unrecoverable / wrong-state (e.g. module disabled while the fan is up). */
    @Composable
    fun danger() = MiuixTheme.colorScheme.error

    /** Neutral, informational chrome: hints, secondary labels, inactive markers. */
    @Composable
    fun neutral() = MiuixTheme.colorScheme.onSurfaceVariantSummary

    /** Container for a selected/active marker (letter index, current section). */
    @Composable
    fun selectedNodeContainer() = MiuixTheme.colorScheme.surfaceContainerHighest

    /** Add-to-fan (the ★ badge in manage mode, unselected). */
    @Composable
    fun actionButton() = MiuixTheme.colorScheme.primary

    /** Remove-from-fan (the ★ badge on an already pinned app). */
    @Composable
    fun removeAction() = MiuixTheme.colorScheme.error

    /** Card-like container for the overlay panel and other raised surfaces. */
    @Composable
    fun surfaceContainer() = MiuixTheme.colorScheme.surfaceContainer
}
