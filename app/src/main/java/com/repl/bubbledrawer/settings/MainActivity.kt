package com.repl.bubbledrawer.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repl.bubbledrawer.ui.component.TriggerZonePreview
import com.repl.bubbledrawer.ui.theme.BubbleDrawerTheme
import com.repl.bubbledrawer.xposed.SettingsStore
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

/**
 * Settings hub — hosts Miuix NavDisplay with native HyperOS slide transitions,
 * gesture swiping, and corner clipping.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val actions = StoreSettingsActions(applicationContext)
        val stateFlow = SettingsStore.observe(applicationContext)
        setContent {
            BubbleDrawerTheme {
                val state by stateFlow.collectAsStateWithLifecycle(
                    initialValue = SettingsStore.currentState(applicationContext),
                )
                val backStack = remember { navBackStackOf(BubbleScreen.Main) }
                val swipeBack = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
                    NavSwipeDirection.RightToLeft
                } else {
                    NavSwipeDirection.LeftToRight
                }
                var previewTriggerZone by remember { mutableStateOf(false) }

                BackHandler(enabled = previewTriggerZone) {
                    previewTriggerZone = false
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    NavDisplay(
                        backStack = backStack,
                        modifier = Modifier.fillMaxSize(),
                        effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
                        onBack = {
                            if (backStack.size > 1) {
                                backStack.removeAt(backStack.lastIndex)
                            }
                        },
                    ) {
                        entry<BubbleScreen.Main>(swipeDismiss = NavSwipeDirection.None) {
                            MainSettingsScreen(
                                state = state,
                                actions = actions,
                                onNavigateToTrigger = { backStack.add(BubbleScreen.Trigger) },
                                onNavigateToFan = { backStack.add(BubbleScreen.Fan) },
                                onNavigateToMorePanel = { backStack.add(BubbleScreen.MorePanel) },
                                onNavigateToExperimental = { backStack.add(BubbleScreen.Experimental) },
                            )
                        }
                        entry<BubbleScreen.Trigger>(swipeDismiss = swipeBack) {
                            TriggerSettingsScreen(
                                state = state,
                                actions = actions,
                                onBack = {
                                    if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                                },
                                onPreviewTriggerZone = { previewTriggerZone = true },
                            )
                        }
                        entry<BubbleScreen.Fan>(swipeDismiss = swipeBack) {
                            FanSettingsScreen(
                                state = state,
                                actions = actions,
                                onBack = {
                                    if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                        entry<BubbleScreen.MorePanel>(swipeDismiss = swipeBack) {
                            MorePanelSettingsScreen(
                                state = state,
                                actions = actions,
                                onBack = {
                                    if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                        entry<BubbleScreen.Experimental>(swipeDismiss = swipeBack) {
                            ExperimentalSettingsScreen(
                                state = state,
                                actions = actions,
                                onBack = {
                                    if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                                },
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = previewTriggerZone,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        TriggerZonePreview(
                            snap = state.snapshot,
                            onDismiss = { previewTriggerZone = false },
                        )
                    }
                }
            }
        }
    }
}
