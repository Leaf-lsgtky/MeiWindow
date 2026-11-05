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
 * 「手势黑名单」页：勾上的应用在前台时，角落手势**什么都不做**。
 *
 * 用户 2026-10-18：「在启用角落手势下面加一个手势黑名单和横屏时需滑动两次才打开的开关」。
 * 场景很实在：看视频、玩游戏时手指贴边，角落手势会误触发把扇形弹出来。
 *
 * 判定在 `CornerInputMonitor.openFan` 里（进扇形前查一次前台包名），这页只负责写值；
 * 名单的编解码是纯函数 `encodeFanBlacklist` / `decodeFanBlacklist`（有单测）。
 * 页面结构照搬「轻量打开」的选应用页，保持一致的手感（搜索、图标、开关行）。
 */
@Composable
fun GestureBlacklistScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snap = state.snapshot
    val selected = remember(snap.fanBlacklist) { snap.fanBlacklist }
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

    BackHandler(enabled = searching) { exitSearch() }

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, scrollBehavior = scrollBehavior) {
                if (searching) {
                    MiuixSearchBar(
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
                        title = stringResource(R.string.gesture_blacklist),
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
            item(key = "count") {
                SmallTitle(
                    text = if (searching) {
                        stringResource(R.string.light_open_apps_found, shown.size)
                    } else {
                        stringResource(R.string.gesture_blacklist_count_selected, selected.size)
                    },
                )
            }
            if (apps.isEmpty()) {
                item(key = "empty") { HintRow(stringResource(R.string.light_open_apps_loading)) }
            } else if (shown.isEmpty()) {
                item(key = "no-result") { HintRow(stringResource(R.string.search_no_result)) }
            }
            groupedCardItems(
                keyPrefix = "blacklist",
                items = shown.map { app ->
                    // 同一个包可能既有主应用又有分身（userId 不同），键要带上 userId 才不撞键。
                    CardItem("${app.packageName}#${app.userId}") {
                        SwitchRow(
                            title = app.label,
                            summary = app.packageName,
                            checked = app.packageName in selected,
                            leading = {
                                AppIcon(
                                    app = app,
                                    size = 36.dp,
                                    cloneBadge = snap.cloneBadge,
                                )
                            },
                            onCheckedChange = { on ->
                                actions.setFanBlacklist(
                                    if (on) selected + app.packageName else selected - app.packageName,
                                )
                            },
                        )
                    }
                },
            )
            item(key = "bottom-spacer") { PageBottomSpacer() }
        }
    }
}
