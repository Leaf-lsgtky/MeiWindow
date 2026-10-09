package com.repl.bubbledrawer.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.ui.component.CardItem
import com.repl.bubbledrawer.ui.component.StatusCard
import com.repl.bubbledrawer.ui.component.groupedCardItems
import com.repl.bubbledrawer.ui.util.BlurredBar
import com.repl.bubbledrawer.ui.util.LeadSpacer
import com.repl.bubbledrawer.ui.util.PageBottomSpacer
import com.repl.bubbledrawer.ui.util.barColor
import com.repl.bubbledrawer.ui.util.pageBackdrop
import com.repl.bubbledrawer.ui.util.pageContentPadding
import com.repl.bubbledrawer.ui.util.pageScroll
import com.repl.bubbledrawer.ui.util.rememberBlurBackdrop
import com.repl.bubbledrawer.xposed.RemotePrefs
import com.repl.bubbledrawer.xposed.SettingsUiState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * Main settings screen with categorized feature navigation entries and redesigned status card.
 */
@Composable
fun MainSettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onNavigateToTrigger: () -> Unit,
    onNavigateToFan: () -> Unit,
    onNavigateToMorePanel: () -> Unit,
    onNavigateToExperimental: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snap = state.snapshot
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    title = stringResource(R.string.settings_title),
                    color = barColor(backdrop != null),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pageBackdrop(backdrop)
                .pageScroll(scrollBehavior),
            contentPadding = pageContentPadding(innerPadding),
        ) {
            item(key = "lead") { LeadSpacer() }

            // --- Redesigned Status Card (KernelSU / HyperMusicCover / ghostlock-app style) ---
            item(key = "status-card") {
                StatusCard(
                    connected = state.connected,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                )
            }

            // --- Global Toggles ---
            item(key = "global-title") {
                SmallTitle(text = stringResource(R.string.global_section_title))
            }
            groupedCardItems(
                keyPrefix = "global",
                items = buildList {
                    add(CardItem("enable") {
                        SwitchRow(
                            title = stringResource(R.string.enable_service),
                            checked = snap.enabled,
                            onCheckedChange = actions::setEnabled,
                        )
                    })
                    add(CardItem("freeform") {
                        SwitchRow(
                            title = stringResource(R.string.freeform_toggle),
                            checked = snap.freeform,
                            onCheckedChange = actions::setFreeform,
                        )
                    })
                    add(CardItem("freeform-hint") {
                        SliderHintRow(stringResource(R.string.freeform_hint))
                    })
                },
            )

            // --- Categorized Feature Entries ---
            item(key = "categories-title") {
                SmallTitle(text = stringResource(R.string.category_section_title))
            }
            groupedCardItems(
                keyPrefix = "categories",
                items = listOf(
                    CardItem("trigger") {
                        ArrowRow(
                            title = stringResource(R.string.category_trigger),
                            summary = stringResource(R.string.category_trigger_summary),
                            onClick = onNavigateToTrigger,
                        )
                    },
                    CardItem("fan") {
                        ArrowRow(
                            title = stringResource(R.string.category_fan),
                            summary = stringResource(R.string.category_fan_summary),
                            onClick = onNavigateToFan,
                        )
                    },
                    CardItem("panel") {
                        ArrowRow(
                            title = stringResource(R.string.category_panel),
                            summary = stringResource(R.string.category_panel_summary),
                            onClick = onNavigateToMorePanel,
                        )
                    },
                    CardItem("manage") {
                        ArrowRow(
                            title = stringResource(R.string.category_manage),
                            summary = stringResource(R.string.category_manage_summary),
                            onClick = { actions.openManage(context) },
                        )
                    },
                    CardItem("experimental") {
                        ArrowRow(
                            title = stringResource(R.string.category_experimental),
                            summary = stringResource(R.string.category_experimental_summary),
                            onClick = onNavigateToExperimental,
                        )
                    },
                ),
            )

            item(key = "bottom-spacer") { PageBottomSpacer() }
        }
    }
}

/**
 * Trigger gesture and zone settings subpage.
 * Note: 触发区默认半径滑条已去除，仅保留底边/侧边范围设置与预览。
 */
@Composable
fun TriggerSettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    onPreviewTriggerZone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snap = state.snapshot
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    title = stringResource(R.string.category_trigger),
                    color = barColor(backdrop != null),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pageBackdrop(backdrop)
                .pageScroll(scrollBehavior),
            contentPadding = pageContentPadding(innerPadding),
        ) {
            item(key = "lead") { LeadSpacer() }

            groupedCardItems(
                keyPrefix = "trigger_pos",
                items = listOf(
                    CardItem("pos") {
                        DropdownRow(
                            title = stringResource(R.string.trigger_pos),
                            items = listOf(
                                stringResource(R.string.pos_both),
                                stringResource(R.string.pos_left),
                                stringResource(R.string.pos_right),
                            ),
                            selectedIndex = state.triggerPos,
                            onSelect = actions::setTriggerPos,
                        )
                    },
                ),
            )

            groupedCardItems(
                keyPrefix = "zone",
                items = buildList {
                    add(CardItem("bottom") {
                        IntSliderRow(
                            title = stringResource(R.string.range_bottom),
                            value = snap.bottomDp,
                            min = RemotePrefsMinDp, max = RemotePrefsMaxDp,
                            valueText = { stringResource(R.string.dp_value, it) },
                            onCommit = { actions.setBottomDp(it) },
                        )
                    })
                    add(CardItem("edge") {
                        IntSliderRow(
                            title = stringResource(R.string.range_edge),
                            value = snap.edgeDp,
                            min = RemotePrefsMinDp, max = RemotePrefsMaxDp,
                            valueText = { stringResource(R.string.dp_value, it) },
                            onCommit = { actions.setEdgeDp(it) },
                        )
                    })
                    add(CardItem("preview") {
                        ArrowRow(
                            title = stringResource(R.string.preview_trigger_zone),
                            onClick = onPreviewTriggerZone,
                        )
                    })
                    add(CardItem("hint") { SliderHintRow(stringResource(R.string.range_hint)) })
                },
            )

            item(key = "bottom-spacer") { PageBottomSpacer() }
        }
    }
}

/**
 * Fan dock appearance settings subpage (icon count and radius).
 */
@Composable
fun FanSettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snap = state.snapshot
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    title = stringResource(R.string.category_fan),
                    color = barColor(backdrop != null),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pageBackdrop(backdrop)
                .pageScroll(scrollBehavior),
            contentPadding = pageContentPadding(innerPadding),
        ) {
            item(key = "lead") { LeadSpacer() }

            groupedCardItems(
                keyPrefix = "fan",
                items = buildList {
                    add(CardItem("icon_count") {
                        DropdownRow(
                            title = stringResource(R.string.fan_icon_count),
                            items = listOf(
                                stringResource(R.string.fan_icon_count_5),
                                stringResource(R.string.fan_icon_count_6),
                            ),
                            selectedIndex = if (snap.fanIconCount == 5) 0 else 1,
                            onSelect = { actions.setFanIconCount(if (it == 0) 5 else 6) },
                        )
                    })
                    add(CardItem("radius") {
                        DefaultableIntSliderRow(
                            title = stringResource(R.string.fan_radius),
                            value = snap.fanRadiusDp,
                            defaultValue = RemotePrefs.DEFAULT_FAN_RADIUS_DP,
                            min = RemotePrefsFanRadiusMin,
                            max = RemotePrefsFanRadiusMax,
                            valueText = { stringResource(R.string.dp_value, it) },
                            onCommit = actions::setFanRadiusDp,
                        )
                    })
                    add(CardItem("auto_fill") {
                        SwitchRow(
                            title = stringResource(R.string.fan_auto_fill_recommend),
                            checked = snap.fanAutoFillRecommend,
                            onCheckedChange = actions::setFanAutoFillRecommend,
                        )
                    })
                    add(CardItem("hint") { SliderHintRow(stringResource(R.string.fan_auto_fill_recommend_summary)) })
                    add(CardItem("pressure_page_turn") {
                        SwitchRow(
                            title = stringResource(R.string.fan_pressure_page_turn),
                            checked = snap.fanPressurePageTurn,
                            onCheckedChange = actions::setFanPressurePageTurn,
                        )
                    })
                    if (snap.fanPressurePageTurn) {
                        add(CardItem("pressure_sensitivity") {
                            DropdownRow(
                                title = stringResource(R.string.fan_pressure_sensitivity),
                                items = listOf(
                                    stringResource(R.string.fan_pressure_sensitivity_standard),
                                    stringResource(R.string.fan_pressure_sensitivity_sensitive),
                                    stringResource(R.string.fan_pressure_sensitivity_heavy),
                                ),
                                selectedIndex = snap.fanPressureSensitivity.coerceIn(0, 2),
                                onSelect = actions::setFanPressureSensitivity,
                            )
                        })
                    }
                    add(CardItem("pressure_hint") { SliderHintRow(stringResource(R.string.fan_pressure_page_turn_summary)) })
                },
            )

            item(key = "bottom-spacer") { PageBottomSpacer() }
        }
    }
}

/**
 * More panel appearance settings subpage (dimensions, icon/text size, dismiss outside).
 */
@Composable
fun MorePanelSettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snap = state.snapshot
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    title = stringResource(R.string.category_panel),
                    color = barColor(backdrop != null),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pageBackdrop(backdrop)
                .pageScroll(scrollBehavior),
            contentPadding = pageContentPadding(innerPadding),
        ) {
            item(key = "lead") { LeadSpacer() }

            groupedCardItems(
                keyPrefix = "panel",
                items = buildList {
                    add(CardItem("w") {
                        DefaultableIntSliderRow(
                            title = stringResource(R.string.panel_width),
                            value = snap.panelWidthPct,
                            defaultValue = RemotePrefs.DEFAULT_PANEL_W_PCT,
                            min = RemotePrefsPctMin,
                            max = RemotePrefsPctMax,
                            valueText = { stringResource(R.string.pct_value, it) },
                            onCommit = actions::setPanelWidthPct,
                        )
                    })
                    add(CardItem("h") {
                        DefaultableIntSliderRow(
                            title = stringResource(R.string.panel_height),
                            value = snap.panelHeightPct,
                            defaultValue = RemotePrefs.DEFAULT_PANEL_H_PCT,
                            min = RemotePrefsPctMin,
                            max = RemotePrefsPctMax,
                            valueText = { stringResource(R.string.pct_value, it) },
                            onCommit = actions::setPanelHeightPct,
                        )
                    })
                    add(CardItem("icon") {
                        DefaultableIntSliderRow(
                            title = stringResource(R.string.panel_icon),
                            value = snap.panelIconDp,
                            defaultValue = RemotePrefs.DEFAULT_PANEL_ICON_DP,
                            min = RemotePrefsIconMin,
                            max = RemotePrefsIconMax,
                            valueText = { stringResource(R.string.dp_value, it) },
                            onCommit = actions::setPanelIconDp,
                        )
                    })
                    add(CardItem("text") {
                        DefaultableIntSliderRow(
                            title = stringResource(R.string.panel_text),
                            value = snap.panelTextSp,
                            defaultValue = RemotePrefs.DEFAULT_PANEL_TEXT_SP,
                            min = RemotePrefsTextMin,
                            max = RemotePrefsTextMax,
                            valueText = { stringResource(R.string.sp_value, it) },
                            onCommit = actions::setPanelTextSp,
                        )
                    })
                    add(CardItem("dismiss_outside") {
                        DropdownRow(
                            title = stringResource(R.string.panel_dismiss_outside),
                            items = listOf(
                                stringResource(R.string.panel_dismiss_outside_single),
                                stringResource(R.string.panel_dismiss_outside_double),
                            ),
                            selectedIndex = snap.panelDismissOutside,
                            onSelect = actions::setPanelDismissOutside,
                        )
                    })
                    add(CardItem("hint") { SliderHintRow(stringResource(R.string.more_panel_hint)) })
                },
            )

            groupedCardItems(
                keyPrefix = "recommend",
                items = buildList {
                    add(CardItem("enable") {
                        SwitchRow(
                            title = stringResource(R.string.recommend_apps_enable),
                            checked = snap.recommendEnabled,
                            onCheckedChange = actions::setRecommendEnabled,
                        )
                    })
                    if (snap.recommendEnabled) {
                        add(CardItem("count") {
                            StepIntSliderRow(
                                title = stringResource(R.string.recommend_apps_count),
                                value = snap.recommendCount,
                                min = RemotePrefs.RECOMMEND_COUNT_MIN,
                                max = RemotePrefs.RECOMMEND_COUNT_MAX,
                                step = RemotePrefs.RECOMMEND_COUNT_STEP,
                                valueText = { stringResource(R.string.recommend_apps_count_format, it) },
                                onCommit = actions::setRecommendCount,
                            )
                        })
                    }
                    add(CardItem("hint") { SliderHintRow(stringResource(R.string.recommend_apps_hint)) })
                },
            )

            item(key = "bottom-spacer") { PageBottomSpacer() }
        }
    }
}

/**
 * Experimental features subpage: Flyme-style lightweight freeform window,
 * gesture transitions, and window centering/scaling.
 */
@Composable
fun ExperimentalSettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snap = state.snapshot
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    title = stringResource(R.string.category_experimental),
                    color = barColor(backdrop != null),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pageBackdrop(backdrop)
                .pageScroll(scrollBehavior),
            contentPadding = pageContentPadding(innerPadding),
        ) {
            item(key = "exp-lead") { LeadSpacer() }

            // --- 核心总开关 ---
            item(key = "exp-master-title") {
                SmallTitle(text = stringResource(R.string.exp_flyme_freeform_title))
            }
            groupedCardItems(
                keyPrefix = "exp-master",
                items = listOf(
                    CardItem("enable-flyme-freeform") {
                        SwitchRow(
                            title = stringResource(R.string.exp_flyme_freeform_title),
                            checked = snap.flymeFreeformEnabled,
                            onCheckedChange = actions::setFlymeFreeformEnabled,
                        )
                    },
                    CardItem("enable-flyme-freeform-hint") {
                        SliderHintRow(stringResource(R.string.exp_flyme_freeform_summary))
                    },
                ),
            )

            // --- 窗口尺寸与位置 ---
            item(key = "exp-window-title") {
                SmallTitle(text = stringResource(R.string.exp_section_window_tuning))
            }
            groupedCardItems(
                keyPrefix = "exp-window",
                items = buildList {
                    add(CardItem("center-window") {
                        SwitchRow(
                            title = stringResource(R.string.exp_center_window),
                            checked = snap.flymeFreeformCenter,
                            onCheckedChange = actions::setFlymeFreeformCenter,
                        )
                    })
                    add(CardItem("center-window-hint") {
                        SliderHintRow(stringResource(R.string.exp_center_window_summary))
                    })
                    add(CardItem("scale-window") {
                        IntSliderRow(
                            title = stringResource(R.string.exp_window_scale),
                            value = snap.flymeFreeformScale,
                            min = RemotePrefs.FLYME_FREEFORM_SCALE_MIN,
                            max = RemotePrefs.FLYME_FREEFORM_SCALE_MAX,
                            valueText = { stringResource(R.string.pct_value, it) },
                            onCommit = actions::setFlymeFreeformScale,
                        )
                    })
                    add(CardItem("scale-window-hint") {
                        SliderHintRow(stringResource(R.string.exp_window_scale_hint))
                    })
                    add(CardItem("dim-bg") {
                        SwitchRow(
                            title = stringResource(R.string.exp_dim_background),
                            checked = snap.flymeFreeformDimBg,
                            onCheckedChange = actions::setFlymeFreeformDimBg,
                        )
                    })
                    add(CardItem("dim-bg-hint") {
                        SliderHintRow(stringResource(R.string.exp_dim_background_summary))
                    })
                },
            )

            // --- 小白条手势交互 ---
            item(key = "exp-gestures-title") {
                SmallTitle(text = stringResource(R.string.exp_section_gestures))
            }
            groupedCardItems(
                keyPrefix = "exp-gestures",
                items = buildList {
                    add(CardItem("outside-dismiss") {
                        SwitchRow(
                            title = stringResource(R.string.exp_outside_dismiss),
                            checked = snap.flymeFreeformOutsideDismiss,
                            onCheckedChange = actions::setFlymeFreeformOutsideDismiss,
                        )
                    })
                    if (snap.flymeFreeformOutsideDismiss) {
                        add(CardItem("outside-dismiss-action") {
                            DropdownRow(
                                title = stringResource(R.string.exp_outside_dismiss_action),
                                items = listOf(
                                    stringResource(R.string.exp_outside_dismiss_action_single),
                                    stringResource(R.string.exp_outside_dismiss_action_double),
                                ),
                                selectedIndex = snap.flymeFreeformOutsideDismissAction,
                                onSelect = actions::setFlymeFreeformOutsideDismissAction,
                            )
                        })
                    }
                    add(CardItem("outside-dismiss-hint") {
                        SliderHintRow(stringResource(R.string.exp_outside_dismiss_summary))
                    })
                    add(CardItem("swipe-up-mini") {
                        SwitchRow(
                            title = stringResource(R.string.exp_swipe_up_mini),
                            checked = snap.flymeFreeformSwipeUpMini,
                            onCheckedChange = actions::setFlymeFreeformSwipeUpMini,
                        )
                    })
                    add(CardItem("swipe-up-mini-hint") {
                        SliderHintRow(stringResource(R.string.exp_swipe_up_mini_summary))
                    })
                    add(CardItem("swipe-up-hold-free") {
                        SwitchRow(
                            title = stringResource(R.string.exp_swipe_up_hold_free),
                            checked = snap.flymeFreeformSwipeUpHoldFree,
                            onCheckedChange = actions::setFlymeFreeformSwipeUpHoldFree,
                        )
                    })
                    add(CardItem("swipe-up-hold-free-hint") {
                        SliderHintRow(stringResource(R.string.exp_swipe_up_hold_free_summary))
                    })
                    add(CardItem("swipe-down-full") {
                        SwitchRow(
                            title = stringResource(R.string.exp_swipe_down_fullscreen),
                            checked = snap.flymeFreeformSwipeDownFull,
                            onCheckedChange = actions::setFlymeFreeformSwipeDownFull,
                        )
                    })
                    add(CardItem("swipe-down-full-hint") {
                        SliderHintRow(stringResource(R.string.exp_swipe_down_fullscreen_summary))
                    })
                },
            )

            // --- 说明 ---
            item(key = "exp-info-title") {
                SmallTitle(text = stringResource(R.string.exp_section_info))
            }
            groupedCardItems(
                keyPrefix = "exp-info",
                items = listOf(
                    CardItem("info-text") {
                        SliderHintRow(stringResource(R.string.exp_freeform_info))
                    },
                ),
            )

            item(key = "exp-bottom-spacer") { PageBottomSpacer() }
        }
    }
}
