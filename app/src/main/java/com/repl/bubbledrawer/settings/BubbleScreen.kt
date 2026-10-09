package com.repl.bubbledrawer.settings

import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * Screen routes for Miuix NavDisplay.
 */
sealed interface BubbleScreen : NavKey {
    data object Main : BubbleScreen
    data object Trigger : BubbleScreen
    data object Fan : BubbleScreen
    data object MorePanel : BubbleScreen
    data object Experimental : BubbleScreen
}
