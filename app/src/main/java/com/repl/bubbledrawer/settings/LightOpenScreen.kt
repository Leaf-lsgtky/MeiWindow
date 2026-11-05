package com.repl.bubbledrawer.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.data.AppRepository
import com.repl.bubbledrawer.pinyin.AppSearch
import com.repl.bubbledrawer.pinyin.BubbleApp
import com.repl.bubbledrawer.lightopen.LightOpen
import com.repl.bubbledrawer.ui.component.AppIcon
import com.repl.bubbledrawer.ui.component.CardItem
import com.repl.bubbledrawer.ui.component.MiuixSearchBar
import com.repl.bubbledrawer.ui.component.SearchCloseButton
import com.repl.bubbledrawer.ui.component.groupedCardItems
import com.repl.bubbledrawer.ui.util.BlurredBar
import com.repl.bubbledrawer.ui.util.LeadSpacer
import com.repl.bubbledrawer.ui.util.PageBottomSpacer
import com.repl.bubbledrawer.ui.util.barColor
import com.repl.bubbledrawer.ui.util.horizontalCutoutPadding
import com.repl.bubbledrawer.ui.util.pageBackdrop
import com.repl.bubbledrawer.ui.util.pageContentPadding
import com.repl.bubbledrawer.ui.util.pageScroll
import com.repl.bubbledrawer.ui.util.rememberBlurBackdrop
import com.repl.bubbledrawer.xposed.SettingsUiState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「轻量打开」设置页：六个开关 + 应用间跳转的名单。
 *
 * 每个开关背后是一处系统钩子，判定逻辑全在 [LightOpen]（纯函数、有单测）；这页只负责写值。
 * 两项在 SystemUI 进程里生效（通知 / 控制中心），三项在 system_server 里生效（分享 / 文本操作 /
 * 应用间跳转）—— 后者要重启手机，页尾的说明里写清楚了。
 */
@Composable
fun LightOpenSettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    onPickApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snap = state.snapshot
    val enabled = snap.lightOpenEnabled
    val targets = remember(snap.lightOpenJumpList) { LightOpen.decodeTargets(snap.lightOpenJumpList) }
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                TopAppBar(
                    title = stringResource(R.string.light_open_title),
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
                .horizontalCutoutPadding()
                .pageBackdrop(backdrop)
                .pageScroll(scrollBehavior),
            contentPadding = pageContentPadding(innerPadding),
        ) {
            item(key = "lead") { LeadSpacer() }

            groupedCardItems(
                keyPrefix = "master",
                items = listOf(
                    CardItem("enable") {
                        SwitchRow(
                            title = stringResource(R.string.light_open_enable),
                            summary = stringResource(R.string.light_open_enable_summary),
                            checked = enabled,
                            onCheckedChange = actions::setLightOpenEnabled,
                        )
                    },
                ),
            )

            item(key = "notification-title") {
                SmallTitle(text = stringResource(R.string.light_open_section_notification))
            }
            groupedCardItems(
                keyPrefix = "notification",
                items = listOf(
                    CardItem("portrait") {
                        SwitchRow(
                            title = stringResource(R.string.light_open_notif_portrait),
                            summary = stringResource(R.string.light_open_notif_portrait_summary),
                            checked = snap.lightOpenNotifPortrait,
                            enabled = enabled,
                            onCheckedChange = actions::setLightOpenNotifPortrait,
                        )
                    },
                    CardItem("landscape") {
                        SwitchRow(
                            title = stringResource(R.string.light_open_notif_landscape),
                            summary = stringResource(R.string.light_open_notif_landscape_summary),
                            checked = snap.lightOpenNotifLandscape,
                            enabled = enabled,
                            onCheckedChange = actions::setLightOpenNotifLandscape,
                        )
                    },
                ),
            )

            item(key = "control-center-title") {
                SmallTitle(text = stringResource(R.string.light_open_section_control_center))
            }
            groupedCardItems(
                keyPrefix = "control-center",
                items = listOf(
                    CardItem("tile") {
                        SwitchRow(
                            title = stringResource(R.string.light_open_control_center),
                            summary = stringResource(R.string.light_open_control_center_summary),
                            checked = snap.lightOpenControlCenter,
                            enabled = enabled,
                            onCheckedChange = actions::setLightOpenControlCenter,
                        )
                    },
                ),
            )

            item(key = "jump-title") {
                SmallTitle(text = stringResource(R.string.light_open_section_jump))
            }
            groupedCardItems(
                keyPrefix = "jump",
                items = buildList {
                    add(CardItem("share") {
                        SwitchRow(
                            title = stringResource(R.string.light_open_share),
                            summary = stringResource(R.string.light_open_share_summary),
                            checked = snap.lightOpenShare,
                            enabled = enabled,
                            onCheckedChange = actions::setLightOpenShare,
                        )
                    })
                    add(CardItem("text") {
                        SwitchRow(
                            title = stringResource(R.string.light_open_text),
                            summary = stringResource(R.string.light_open_text_summary),
                            checked = snap.lightOpenText,
                            enabled = enabled,
                            onCheckedChange = actions::setLightOpenText,
                        )
                    })
                    add(CardItem("app-jump") {
                        SwitchRow(
                            title = stringResource(R.string.light_open_jump),
                            summary = stringResource(R.string.light_open_jump_summary),
                            checked = snap.lightOpenJump,
                            enabled = enabled,
                            onCheckedChange = actions::setLightOpenJump,
                        )
                    })
                    add(CardItem("mode") {
                        DropdownRow(
                            title = stringResource(R.string.light_open_jump_mode),
                            items = listOf(
                                stringResource(R.string.light_open_jump_mode_white),
                                stringResource(R.string.light_open_jump_mode_black),
                            ),
                            selectedIndex = snap.lightOpenJumpMode,
                            enabled = enabled && snap.lightOpenJump,
                            onSelect = actions::setLightOpenJumpMode,
                        )
                    })
                    add(CardItem("pick") {
                        ArrowRow(
                            title = stringResource(R.string.light_open_jump_pick),
                            summary = if (targets.isEmpty()) {
                                stringResource(R.string.light_open_jump_pick_empty)
                            } else {
                                stringResource(R.string.light_open_jump_pick_count, targets.size)
                            },
                            enabled = enabled && snap.lightOpenJump,
                            onClick = onPickApps,
                        )
                    })
                },
            )

            item(key = "hint") { HintRow(stringResource(R.string.light_open_hint)) }
            item(key = "defaults") {
                HintRow(
                    "黑名单模式：名单外的一切跨应用跳转都会进小窗；名单内的应用强制全屏（用来压掉系统" +
                        "原生的轻量打开）。白名单模式：只有名单内的目标应用会进小窗。",
                )
            }

            item(key = "bottom-spacer") { PageBottomSpacer() }
        }
    }
}

/**
 * 应用选择页：按「被打开的应用」勾选。列表来自 [AppRepository]（与扇形/收藏同一份枚举），
 * 勾选直接写回整份名单 —— 编码与清洗都在 [LightOpen.encodeTargets] 里，UI 不做半套。
 *
 * 搜索沿用本应用已有的那一套：顶栏在搜索态整个换成搜索框（同 `PinManageActivity`），返回键
 * 先退搜索再退页面。匹配与排序走 [AppSearch]（拼音 / 首字母 / 包名，与面板里的应用搜索同一份
 * 实现），所以「微信」既能被 `weixin` 搜到也能被 `wx` 搜到，顺序也和面板一致。
 *
 * 每行前面是该应用的图标（[AppIcon]）：名称与包名共用同一行，图标在它们之前。
 */
@Composable
fun LightOpenAppsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snap = state.snapshot
    val selected = remember(snap.lightOpenJumpList) { LightOpen.decodeTargets(snap.lightOpenJumpList) }
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val apps by produceState(initialValue = emptyList<BubbleApp>()) {
        value = runCatching { AppRepository(context).all().toList() }
            .getOrDefault(emptyList())
    }
    // 只在词或列表变时重算：一次输入 200 个应用各跑一遍拼音匹配，别放进每次重组。
    val shown = remember(apps, query) { AppSearch.filter(apps, query) }

    fun exitSearch() {
        searching = false
        query = ""
    }

    // 返回键先退搜索，再退页面（与 PinManageActivity 的搜索态一致由页面自己接住）。
    BackHandler(enabled = searching) { exitSearch() }

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                if (searching) {
                    MiuixSearchBar(
                        // MiuixSearchBar 是普通 Row，不吃窗口 inset（TopAppBar 自己吃），
                        // 这里不补状态栏高度的话搜索框会顶到状态栏下面；高度取 TopAppBar 的
                        // 折叠高度，进出搜索态时列表不会跳。
                        modifier = Modifier
                            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                            .fillMaxWidth()
                            .height(TopAppBarDefaults.CollapsedHeight),
                        insideMargin = DpSize(12.dp, 0.dp),
                        inputField = {
                            InputField(
                                query = query,
                                onQueryChange = { query = it },
                                onSearch = { },
                                expanded = true,
                                onExpandedChange = { expanded -> if (!expanded) exitSearch() },
                                label = stringResource(R.string.search_hint),
                            )
                        },
                        expanded = true,
                        outsideEndAction = { SearchCloseButton(onClick = ::exitSearch) },
                    )
                } else {
                    TopAppBar(
                        title = stringResource(R.string.light_open_apps_title),
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
                        actions = {
                            IconButton(onClick = { searching = true }) {
                                Icon(
                                    imageVector = MiuixIcons.Search,
                                    contentDescription = stringResource(R.string.search),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        },
                    )
                }
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .pageBackdrop(backdrop)
                .pageScroll(scrollBehavior),
            contentPadding = pageContentPadding(innerPadding),
        ) {
            // 首项是 SmallTitle（自带上下边距），按 ui-guidelines 不再补 LeadSpacer。
            item(key = "count") {
                SmallTitle(
                    text = if (searching) {
                        stringResource(R.string.light_open_apps_found, shown.size)
                    } else {
                        stringResource(R.string.light_open_apps_selected, selected.size)
                    },
                )
            }
            if (apps.isEmpty()) {
                item(key = "empty") { HintRow(stringResource(R.string.light_open_apps_loading)) }
            } else if (shown.isEmpty()) {
                item(key = "no-result") { HintRow(stringResource(R.string.search_no_result)) }
            }
            groupedCardItems(
                keyPrefix = "app",
                items = shown.map { app ->
                    // 同一个包可能既有主应用又有分身（userId 不同），键必须带上 userId，
                    // 否则分身会在 lazy 列表里撞键。
                    CardItem("${app.packageName}#${app.userId}") {
                        SwitchRow(
                            title = app.label,
                            summary = app.packageName,
                            checked = app.packageName in selected,
                            leading = { AppIcon(app = app, size = AppRowIconSize, cloneBadge = snap.cloneBadge) },
                            onCheckedChange = { on ->
                                val next = if (on) {
                                    selected + app.packageName
                                } else {
                                    selected - app.packageName
                                }
                                actions.setLightOpenJumpList(next)
                            },
                        )
                    }
                },
            )
            item(key = "bottom-spacer") { PageBottomSpacer() }
        }
    }
}

/**
 * 行内图标尺寸。36dp 是与两行文字（名称 + 包名）齐平的那个大小：再大就会把 preference 行撑高，
 * 一屏能看的应用数量变少。
 */
private val AppRowIconSize = 36.dp
