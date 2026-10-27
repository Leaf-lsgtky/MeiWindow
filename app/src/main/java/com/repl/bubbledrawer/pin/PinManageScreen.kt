package com.repl.bubbledrawer.pin

import android.content.res.Configuration
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.pinyin.BubbleApp
import com.repl.bubbledrawer.shortcut.AggregatePayment
import com.repl.bubbledrawer.shortcut.ShortcutBridge
import com.repl.bubbledrawer.shortcut.ShortcutItem
import com.repl.bubbledrawer.shortcut.ShortcutRepository
import com.repl.bubbledrawer.ui.component.AppIcon
import com.repl.bubbledrawer.ui.component.MiuixSearchBar
import com.repl.bubbledrawer.ui.component.SearchCloseButton
import com.repl.bubbledrawer.ui.theme.StatusColors
import com.repl.bubbledrawer.ui.util.BlurredBar
import com.repl.bubbledrawer.ui.util.PageBottomSpacer
import com.repl.bubbledrawer.ui.util.horizontalCutoutPadding
import com.repl.bubbledrawer.ui.util.pageBackdrop
import com.repl.bubbledrawer.ui.util.pageScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class Chrome { ACTIVITY, PANEL }

private const val GRID_SPAN = 4

private sealed interface PinnedEntry {
    data class App(val app: BubbleApp) : PinnedEntry
    data class Function(val item: ShortcutItem) : PinnedEntry
}

@Composable
fun PinManageScreen(
    model: PinManageModel,
    chrome: Chrome,
    iconDp: Int,
    textSp: Int,
    onLaunch: (BubbleApp) -> Unit,
    onClose: (() -> Unit)?,
    onToggleManage: () -> Unit,
    manageLabel: String,
    topPadding: PaddingValues = PaddingValues(0.dp),
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop? = null,
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior? = null,
    onLaunchShortcut: ((ShortcutItem) -> Unit)? = null,
) {
    val appsGridState = rememberLazyGridState()
    val fnsGridState = rememberLazyGridState()
    // 折叠后的 cell 列表（推荐 / 收藏各最多 Sections.VISIBLE_MAX 个）：网格、收藏区、字母条、
    // 拖拽落点**必须用同一份**，否则字母条按下标跳转、拖拽按下标换位都会落到别的地方去。
    val visibleCells = remember(model.cells, model.pinsExpanded, model.recommendExpanded) { model.visibleCells }
    // 拖拽落点要在**按下那一刻**现取，所以这里存的是"最新那一份"而不是值本身：`remember(model)`
    // 只跑一次，直接捕获 `visibleCells` 会把**首次组合**时的那一份冻在 lambda 里 —— 而模型的应用
    // 列表是异步装进来的（`cells` 初值 emptyList），首次组合时它往往还是空的，于是每次拖拽都是
    // `pins=0`：长按只放大、怎么拖都不动（用户 2026-10-16 的反馈）。
    val currentVisibleCells = rememberUpdatedState(visibleCells)
    val drag = remember(model) {
        PinDragState(
            model = model,
            visiblePins = { currentVisibleCells.value.filterIsInstance<Cell.Pin>() },
            visibleFunctions = { model.pinnedFunctions },
        )
    }
    val title = stringResource(R.string.slide_launch_app_settings_title)
    // 顶部共用收藏区在功能页上装的是「已选功能」，提示语跟着换 —— 否则功能页写着「暂无收藏应用」。
    val headText = if (model.selectedTab == 1) {
        stringResource(R.string.pinned_functions_title)
    } else {
        stringResource(R.string.slide_launch_app_has_selected)
    }
    val emptyText = if (model.selectedTab == 1) {
        stringResource(R.string.pinned_functions_empty)
    } else {
        stringResource(R.string.settings_tips_click_to_add_app)
    }

    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = model.selectedTab) { 2 }

    // 页面与 Tab 状态同步：Pager 滑动或动画结束后更新 model.selectedTab
    LaunchedEffect(pagerState.currentPage) {
        if (model.selectedTab != pagerState.currentPage) {
            model.selectTab(pagerState.currentPage)
        }
    }
    LaunchedEffect(model.selectedTab) {
        if (pagerState.currentPage != model.selectedTab && !pagerState.isScrollInProgress) {
            pagerState.scrollToPage(model.selectedTab)
        }
    }

    // 数据就绪时重置在最顶部，杜绝冷启动时锚定到底部的 Bug
    var appsHasScrolledToTop by remember { mutableStateOf(false) }
    LaunchedEffect(model.cells.isNotEmpty()) {
        if (!appsHasScrolledToTop && model.cells.isNotEmpty()) {
            appsHasScrolledToTop = true
            appsGridState.scrollToItem(0)
        }
    }
    var fnsHasScrolledToTop by remember { mutableStateOf(false) }
    LaunchedEffect(model.groupedFunctions.isNotEmpty()) {
        if (!fnsHasScrolledToTop && model.groupedFunctions.isNotEmpty()) {
            fnsHasScrolledToTop = true
            fnsGridState.scrollToItem(0)
        }
    }

    // 搜索：结果列表与整页共用同一个 LazyGridState，从列表深处进入搜索（或改关键词）时必须回顶，
    // 否则用户看到的是命中结果里靠后的那几条 —— 看起来就像"没搜到"。
    LaunchedEffect(model.isSearching, model.searchQuery, model.selectedTab) {
        if (model.isSearching) {
            runCatching { (if (model.selectedTab == 0) appsGridState else fnsGridState).scrollToItem(0) }
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val gridSpan = if (isLandscape) 6 else 4

    val density = LocalDensity.current
    val topContentPadding = if (chrome == Chrome.PANEL) 56.dp else topPadding.calculateTopPadding()
    val topContentPaddingPx = with(density) { topContentPadding.toPx() }

    // 估算收藏区域高度，确保首帧布局零抖动（用折叠后的列表：折叠时高度就是 20 个那么大）
    val pinnedAppsCount = remember(visibleCells) {
        visibleCells.takeWhile { it !is Cell.Label }.count { it is Cell.Pin }
    }
    val pinnedFnsCount = model.pinnedFunctions.size
    val totalPinned = pinnedAppsCount + pinnedFnsCount
    val estimatedHeightPx = with(density) {
        val rows = if (totalPinned == 0) 0 else (totalPinned + gridSpan - 1) / gridSpan
        (44.dp + if (totalPinned == 0) 110.dp else (rows * 88).dp).toPx()
    }
    var sharedPinsContentHeightPx by remember { mutableFloatStateOf(estimatedHeightPx) }
    var headerOffsetPx by remember { mutableFloatStateOf(0f) }

    // NestedScroll 实现：手势上滑时连续平滑折叠收藏区域，绝不提前突变吸顶；
    // 列表滑到顶端后再下拉时平滑展开收藏区域
    val nestedScrollConnection = remember(sharedPinsContentHeightPx) {
        object : NestedScrollConnection {
            /** 被"列表不在顶部"挡掉的展开，仅用于取证（一秒一行）。 */
            private var lastBlockedLogAt = 0L

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val minOffset = -sharedPinsContentHeightPx
                return if (delta < 0 && headerOffsetPx > minOffset && minOffset < 0f) {
                    val newOffset = (headerOffsetPx + delta).coerceIn(minOffset, 0f)
                    val consumed = newOffset - headerOffsetPx
                    headerOffsetPx = newOffset
                    Offset(0f, consumed)
                } else {
                    Offset.Zero
                }
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val minOffset = -sharedPinsContentHeightPx
                if (delta <= 0f || headerOffsetPx >= 0f) return Offset.Zero
                // 只有**列表真的回到顶**时才把收藏区拉回来。
                //
                // 用户 2026-10-18：「在收藏上滑，当应用/功能 tabrow 到顶后继续上滑，整个页面会下滑」。
                // 收藏区展开会同时把 Pager 往下推（`pagerY = 收藏高度 + headerOffset`），所以只要
                // 在列表还滚着的时候展开一次，整页就跟着往下一沉；那种残余位移属于上一笔手势的尾巴，
                // 不该改变页面结构。这也正是 A29 里写的顺序：先回到顶，再展开。
                val grid = if (model.selectedTab == 0) appsGridState else fnsGridState
                val atTop = grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset == 0
                if (!atTop) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastBlockedLogAt >= 1000L) {
                        lastBlockedLogAt = now
                        android.util.Log.i(
                            "BubbleDrawer",
                            "PINS_EXPAND_BLOCKED first=${grid.firstVisibleItemIndex}" +
                                " off=${grid.firstVisibleItemScrollOffset} delta=$delta",
                        )
                    }
                    return Offset.Zero
                }
                val newOffset = (headerOffsetPx + delta).coerceIn(minOffset, 0f)
                val consumed = newOffset - headerOffsetPx
                headerOffsetPx = newOffset
                return Offset(0f, consumed)
            }
        }
    }

    val tabRowHeightDp = 52.dp
    val tabRowHeightPx = with(density) { tabRowHeightDp.toPx() }
    val tabRowY = (topContentPaddingPx + sharedPinsContentHeightPx + headerOffsetPx)
        .coerceAtLeast(topContentPaddingPx)
    val pinsY = topContentPaddingPx + headerOffsetPx
    val pagerY = (sharedPinsContentHeightPx + headerOffsetPx).coerceAtLeast(0f)

    // 收藏区是**盖在 Pager 上面**的一层（视觉上是"可折叠的表头"），所以手指落在它上面时，下面那个
    // LazyVerticalGrid 根本收不到手势 —— 收藏一多、这块把可视区盖满，整页就滑不动了。
    // 用户 2026-10-17：「收藏的应用那一块不响应上下滑动手势」（面板里尤其明显：62% 的窗口高度，
    // 几行收藏就把 TabRow 与 A-Z 列表全挡住）。
    //
    // 这里把落在这块上的竖直拖动接回**同一条**滚动链：它自己就是一个 `scrollable`（状态 = 当前页
    // 那个网格），于是外层 nestedScroll 照常先折叠收藏区、剩下的再滚网格；长按排序进行中把它关掉，
    // 让拖动排序先拿到手势（收藏区里的 pointerInput 在更内层，Main 阶段本来就先收到事件，这里只是双保险）。
    //
    // 注意：`LazyVerticalGrid` 内部用 `ScrollableDefaults.reverseDirection(..., reverseLayout = false)`
    //（即 `reverseDirection = true`：手指上滑 delta<0 → 列表向后滚 +delta，手指下滑 delta>0 → 列表回顶 -delta）。
    // 这里若用默认的 `reverseDirection = false`，在收藏区下滑会把 +delta 传给网格导致下方列表反而往上滚，
    // 且永远到不了顶部的 `overScrollVertical()` 触顶回弹！
    val activeGridState: LazyGridState = if (model.selectedTab == 0) appsGridState else fnsGridState
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val reverseScrollDirection = ScrollableDefaults.reverseDirection(
        layoutDirection = layoutDirection,
        orientation = Orientation.Vertical,
        reverseScrolling = false,
    )
    val pinsScrollModifier = Modifier
        .scrollable(
            state = activeGridState,
            orientation = Orientation.Vertical,
            enabled = !drag.isDragging,
            reverseDirection = reverseScrollDirection,
            flingBehavior = ScrollableDefaults.flingBehavior(),
        )

    // 需求 10：切换页面时回顶，但回的是不含收藏的顶，应用/功能在平移的时候自己平移到上面（不含收藏的顶），收藏不跟着平移！
    val switchTab: (Int) -> Unit = { targetPage ->
        if (pagerState.currentPage != targetPage || model.selectedTab != targetPage) {
            model.selectTab(targetPage)
            coroutineScope.launch {
                // 1. 如果当前处于展开收藏状态，平滑折叠收藏区域至 TabRow 吸顶（回不含收藏的顶）
                if (sharedPinsContentHeightPx > 0f && headerOffsetPx > -sharedPinsContentHeightPx) {
                    launch {
                        Animatable(headerOffsetPx).animateTo(-sharedPinsContentHeightPx) {
                            headerOffsetPx = value
                        }
                    }
                }
                // 2. 目标网格回滚到第 0 项（无需等待，放子协程非阻塞执行）
                launch {
                    runCatching {
                        if (targetPage == 0) {
                            appsGridState.scrollToItem(0)
                        } else {
                            fnsGridState.scrollToItem(0)
                        }
                    }
                }
                // 3. 执行平移动画切换 Pager
                pagerState.animateScrollToPage(targetPage)
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        if (model.isSearching) {
            // 搜索模式：直接展示当前 Tab 的搜索结果，不显示 TabRow 与 Pager
            val searchGridState = if (model.selectedTab == 0) appsGridState else fnsGridState
            LazyVerticalGrid(
                columns = GridCells.Fixed(gridSpan),
                state = searchGridState,
                modifier = Modifier
                    .fillMaxSize()
                    .pageBackdrop(backdrop)
                    .horizontalCutoutPadding()
                    .pageScroll(scrollBehavior),
                contentPadding = PaddingValues(
                    start = 14.dp,
                    end = 14.dp,
                    top = topContentPadding + 8.dp,
                    bottom = 16.dp,
                ),
            ) {
                if (model.selectedTab == 0) {
                    itemsIndexed(
                        items = model.displayedCells,
                        key = { _, cell -> cellKey(cell) },
                        contentType = { _, cell ->
                            when (cell) {
                                is Cell.PinHead -> 0
                                is Cell.EmptyHint -> 1
                                is Cell.Label -> 2
                                is Cell.Pin -> 3
                                is Cell.App -> 4
                                is Cell.Expand -> 5
                            }
                        },
                        span = { _, cell ->
                            if (cell is Cell.Pin || cell is Cell.App) GridItemSpan(1) else GridItemSpan(gridSpan)
                        },
                    ) { _, cell ->
                        val cellModifier = if (model.manageMode) Modifier.animateItem() else Modifier
                        when (cell) {
                            is Cell.PinHead -> HeadRow(headText)
                            is Cell.EmptyHint -> EmptyHintRow(emptyText)
                            is Cell.Label -> LabelRow(cell.text)
                            is Cell.Expand -> SectionExpandRow(
                                onToggle = {
                                    if (cell.section == Sections.PIN_SECTION) {
                                        model.togglePinsExpanded()
                                    } else {
                                        model.toggleRecommendExpanded()
                                    }
                                },
                            )
                            is Cell.Pin -> PinCell(
                                app = cell.app,
                                model = model,
                                drag = drag,
                                manageMode = model.manageMode,
                                iconDp = iconDp,
                                textSp = textSp,
                                onTap = { if (model.manageMode) model.togglePin(cell.app) else onLaunch(cell.app) },
                                modifier = cellModifier,
                            )
                            is Cell.App -> AppCell(
                                app = cell.app,
                                manageMode = model.manageMode,
                                pinned = model.isPinned(cell.app),
                                iconDp = iconDp,
                                textSp = textSp,
                                cloneBadge = model.cloneBadge,
                                onTap = { if (model.manageMode) model.togglePin(cell.app) else onLaunch(cell.app) },
                                modifier = cellModifier,
                            )
                        }
                    }
                } else {
                    if (model.groupedFunctions.isEmpty()) {
                        item(key = "shortcut_search_empty", span = { GridItemSpan(gridSpan) }) {
                            LabelRow(stringResource(R.string.search_no_result))
                        }
                    } else {
                        model.groupedFunctions.forEach { group ->
                            item(key = "search_fn_group_${group.appName}", span = { GridItemSpan(gridSpan) }) {
                                LabelRow(group.appName)
                            }
                            items(
                                items = group.items,
                                key = { "search_fn_${group.appName}_${it.pinKey}" },
                                span = { GridItemSpan(1) },
                            ) { item ->
                                ShortcutCell(
                                    item = item,
                                    iconDp = iconDp,
                                    textSp = textSp,
                                    onTap = {
                                        if (model.manageMode) {
                                            model.toggleFunctionPin(item)
                                        } else {
                                            onLaunchShortcut?.invoke(item)
                                        }
                                    },
                                    showAppBadge = false,
                                    showBadge = model.manageMode,
                                    isPinned = model.isFunctionPinned(item),
                                )
                            }
                        }
                    }
                }
                item(key = "bottom_search", span = { GridItemSpan(gridSpan) }) { PageBottomSpacer() }
            }
        } else {
            // 统一滚动与触顶/触底回弹容器：
            // `pageScroll(scrollBehavior)`（内含 `overScrollVertical()` 与顶栏 `nestedScroll`）放在
            // `nestedScroll(nestedScrollConnection)` 外层，包裹 LAYER 1（Pager + 收藏区）与 LAYER 2（TabRow）。
            // 这样无论手势从收藏区、TabRow 还是下方网格发起：
            //  - 上滑先折叠收藏区，完全折叠后无缝驱动下方网格上滚，到底部触发触底回弹；
            //  - 下滑先将下方网格滚回顶部，再平滑展开收藏区，完全展开后剩余下拉量进入外层 `overScrollVertical()`，
            //    让收藏区、TabRow 与下方网格作为一个整体共同触发触顶回弹！
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pageScroll(scrollBehavior)
                    .nestedScroll(nestedScrollConnection),
            ) {
            // LAYER 1: Background content (PRODUCER)
            // HorizontalPager + SharedPinsSection 都在 pageBackdrop 内部，被顶栏和 TabRow 作为模糊源进行采样
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pageBackdrop(backdrop),
            ) {
                // 1. HorizontalPager 全屏展开，初始伴随收藏高度下移；上滑时平滑吸顶至 y = 0
                // 滚动项向上平滑穿透至 TabRow 背后与 TopBar 背后，完美激活局部磨砂模糊与顶部渐进模糊！
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(0, pagerY.toInt()) },
                ) { page ->
                    if (page == 0) {
                        // 应用页：推荐与 A-Z 应用列表
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(gridSpan),
                            state = appsGridState,
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalCutoutPadding(),
                            contentPadding = PaddingValues(
                                start = 14.dp,
                                end = 14.dp,
                                top = topContentPadding + tabRowHeightDp,
                                bottom = topContentPadding + 16.dp,
                            ),
                        ) {
                            val unpinnedSection = visibleCells.dropWhile { it !is Cell.Label }
                            itemsIndexed(
                                items = unpinnedSection,
                                key = { _, cell -> cellKey(cell) },
                                contentType = { _, cell ->
                                    when (cell) {
                                        is Cell.Label -> 2
                                        is Cell.App -> 4
                                        is Cell.Expand -> 5
                                        else -> 0
                                    }
                                },
                                span = { _, cell ->
                                    if (cell is Cell.App) GridItemSpan(1) else GridItemSpan(gridSpan)
                                },
                            ) { _, cell ->
                                val cellModifier = if (model.manageMode) Modifier.animateItem() else Modifier
                                when (cell) {
                                    is Cell.Label -> LabelRow(cell.text)
                                    is Cell.Expand -> SectionExpandRow(
                                        onToggle = {
                                            if (cell.section == Sections.PIN_SECTION) {
                                                model.togglePinsExpanded()
                                            } else {
                                                model.toggleRecommendExpanded()
                                            }
                                        },
                                    )
                                    is Cell.App -> AppCell(
                                        app = cell.app,
                                        manageMode = model.manageMode,
                                        pinned = model.isPinned(cell.app),
                                        iconDp = iconDp,
                                        textSp = textSp,
                                        cloneBadge = model.cloneBadge,
                                        onTap = {
                                            if (model.manageMode) model.togglePin(cell.app)
                                            else onLaunch(cell.app)
                                        },
                                        modifier = cellModifier,
                                    )
                                    else -> {}
                                }
                            }
                            if (unpinnedSection.isNotEmpty()) {
                                item(key = "bottom_apps", span = { GridItemSpan(gridSpan) }) { PageBottomSpacer() }
                            }
                        }
                    } else {
                        // 功能页：以应用名称为小标题作为分类，应用名称使用拼音排序 (小标题样式与 A-Z 一致)
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(gridSpan),
                            state = fnsGridState,
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalCutoutPadding(),
                            contentPadding = PaddingValues(
                                start = 14.dp,
                                end = 14.dp,
                                top = topContentPadding + tabRowHeightDp,
                                bottom = topContentPadding + 16.dp,
                            ),
                        ) {
                            // 聚合支付：内建功能，独立于扫描出的分组之外（用户 2026-10-24：
                            // 「单独拎出来不要和快捷方式摆在一起，放在收藏和字母排序中间」）。
                            // 收藏行为与其他功能一致：管理模式下点击勾选/取消，浏览模式点击执行。
                            // 它占网格第 0 位，字母条跳转的偏移在 [jumpToFunction] 里同步。
                            item(key = "aggregate_payment", span = { GridItemSpan(1) }) {
                                val aggregateContext = LocalContext.current
                                val aggregate = remember(aggregateContext) {
                                    AggregatePayment.entryIn(aggregateContext)
                                }
                                ShortcutCell(
                                    item = aggregate,
                                    iconDp = iconDp,
                                    textSp = textSp,
                                    onTap = {
                                        if (model.manageMode) {
                                            model.toggleFunctionPin(aggregate)
                                        } else {
                                            onLaunchShortcut?.invoke(aggregate)
                                        }
                                    },
                                    showAppBadge = false,
                                    showBadge = model.manageMode,
                                    isPinned = model.isFunctionPinned(aggregate),
                                )
                            }
                            // 功能页加载中：进度条 + 第 x 个 / 共 y 个。加载完才把空态显示出来，
                            // 否则会先闪一下「还没有读取到功能」再被列表顶掉。
                            if (model.functionLoading) {
                                item(key = "functions_loading", span = { GridItemSpan(gridSpan) }) {
                                    FunctionLoadingRow(
                                        loaded = model.functionLoaded,
                                        total = model.functionTotal,
                                    )
                                }
                            }
                            // 功能页原本没有任何空态：读不到快捷方式时整页是白的，连"在加载"都看不出来。
                            if (model.groupedFunctions.isEmpty() && !model.functionLoading) {
                                item(key = "functions_empty", span = { GridItemSpan(gridSpan) }) {
                                    EmptyHintRow(stringResource(R.string.functions_catalog_empty))
                                }
                            }
                            model.groupedFunctions.forEach { group ->
                                item(key = "group_header_${group.appName}", span = { GridItemSpan(gridSpan) }) {
                                    LabelRow(group.appName)
                                }
                                items(
                                    items = group.items,
                                    key = { "group_fn_${group.appName}_${it.pinKey}" },
                                    span = { GridItemSpan(1) },
                                ) { item ->
                                    val onTap: () -> Unit = {
                                        if (model.manageMode) {
                                            model.toggleFunctionPin(item)
                                        } else {
                                            onLaunchShortcut?.invoke(item)
                                        }
                                    }
                                    ShortcutCell(
                                        item = item,
                                        iconDp = iconDp,
                                        textSp = textSp,
                                        onTap = onTap,
                                        showAppBadge = false,
                                        showBadge = model.manageMode,
                                        isPinned = model.isFunctionPinned(item),
                                    )
                                }
                            }
                            if (model.groupedFunctions.isNotEmpty()) {
                                item(key = "bottom_fns", span = { GridItemSpan(gridSpan) }) { PageBottomSpacer() }
                            }
                        }
                    }
                }

                // 2. 顶部共用收藏区域 (应用 + 功能)：独立于 Pager 外部，平移动画时收藏绝不跟着平移！
                // 以前它还会随上滑淡出，现在只平移、全程不透明（用户 2026-10-17：「把上滑淡出删掉」）。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(0, pinsY.toInt()) }
                        .then(pinsScrollModifier)
                        // 完全折叠后它的下沿正好落在顶栏下沿：顶栏开模糊时是透明的，再画就只是
                        // "顶栏后面透出来的残影"。所以到那一刻直接不画 —— 硬切（不是渐变）；
                        // 读快照状态，不会因此每帧重组。
                        .graphicsLayer {
                            alpha = if (headerOffsetPx <= -sharedPinsContentHeightPx + 1f) 0f else 1f
                        },
                ) {
                    MeasuredHeader(
                        onMeasuredHeight = { h ->
                            if (h != sharedPinsContentHeightPx) {
                                sharedPinsContentHeightPx = h
                                // 收藏区高度会变（展开/收起「全部」、收藏/取消收藏）：把上滑累积的
                                // 偏移夹回新范围。不夹的话会留下"高度已经缩了、偏移还停在旧高度"的
                                // 悬空状态 —— 列表被顶到 y=0 而收藏区还在负偏移处，看着像两层叠一起。
                                headerOffsetPx = headerOffsetPx.coerceIn(-h, 0f)
                            }
                        },
                    ) {
                        SharedPinsSection(
                            model = model,
                            cells = visibleCells,
                            drag = drag,
                            gridSpan = gridSpan,
                            headText = headText,
                            emptyText = emptyText,
                            iconDp = iconDp,
                            textSp = textSp,
                            onLaunch = onLaunch,
                            onLaunchShortcut = onLaunchShortcut,
                        )
                    }
                }
            }

            // LAYER 2: 居中胶囊样式的 TabRow (CONSUMER 1)
            // 独立于 pageBackdrop 外部作为同级 Sibling，严防死锁！仅在胶囊本身范围应用局部 textureBlur 磨砂模糊！
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tabRowHeightDp)
                    .offset { IntOffset(0, tabRowY.toInt()) }
                    .then(pinsScrollModifier)
                    .zIndex(1.5f),
                contentAlignment = Alignment.Center,
            ) {
                CapsuleTabRow(
                    selectedTabIndex = model.selectedTab,
                    onTabSelected = switchTab,
                    backdrop = backdrop,
                )
            }
            }
        }

        // LAYER 3: Panel 顶栏 (CONSUMER 2，仅在 Chrome.PANEL 时绘制，独立于 pageBackdrop 外部)
        if (chrome == Chrome.PANEL) {
            BlurredBar(
                backdrop = backdrop,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .zIndex(2f),
            ) {
                PanelBar(
                    title = title,
                    manageLabel = manageLabel,
                    manageMode = model.manageMode,
                    onToggleManage = onToggleManage,
                    model = model,
                )
            }
        }

        // 5. 右侧快速字母条浮层：在应用页显示应用字母，在功能页显示功能分组首字母，两端完全一致
        if (!model.isSearching) {
            if (pagerState.currentPage == 0) {
                val unpinnedSection = remember(visibleCells) {
                    visibleCells.dropWhile { it !is Cell.Label }
                }
                val visibleLetter by remember(unpinnedSection) {
                    derivedStateOf {
                        val first = appsGridState.firstVisibleItemIndex
                        var cur: String? = null
                        for (i in first downTo 0) {
                            val c = unpinnedSection.getOrNull(i)
                            if (c is Cell.Label) { cur = c.text; break }
                        }
                        cur ?: PINNED_GLYPH
                    }
                }
                GenericLetterBarOverlay(
                    letters = model.letters,
                    currentLetter = visibleLetter,
                    onLetterSelected = { letter ->
                        if (letter == PINNED_GLYPH) {
                            coroutineScope.launch {
                                Animatable(headerOffsetPx).animateTo(0f) { headerOffsetPx = value }
                                appsGridState.scrollToItem(0)
                            }
                        } else {
                            coroutineScope.launch {
                                if (headerOffsetPx > -sharedPinsContentHeightPx) {
                                    Animatable(headerOffsetPx).animateTo(-sharedPinsContentHeightPx) {
                                        headerOffsetPx = value
                                    }
                                }
                                jumpTo(appsGridState, unpinnedSection, letter, -(topContentPaddingPx + tabRowHeightPx).toInt())
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(top = topContentPadding + 10.dp)
                        .zIndex(3f),
                )
            } else if (model.groupedFunctions.isNotEmpty()) {
                val fnsVisibleLetter by remember(model.groupedFunctions) {
                    derivedStateOf {
                        val first = fnsGridState.firstVisibleItemIndex
                        var curIndex = 0
                        var matched = PINNED_GLYPH
                        for (group in model.groupedFunctions) {
                            val groupCount = 1 + group.items.size
                            if (first in curIndex until (curIndex + groupCount)) {
                                val ch = group.sortKey.firstOrNull()?.uppercaseChar() ?: '#'
                                matched = if (ch in 'A'..'Z') ch.toString() else "#"
                                break
                            }
                            curIndex += groupCount
                        }
                        matched
                    }
                }
                GenericLetterBarOverlay(
                    letters = model.functionLetters,
                    currentLetter = fnsVisibleLetter,
                    onLetterSelected = { letter ->
                        if (letter == PINNED_GLYPH) {
                            coroutineScope.launch {
                                Animatable(headerOffsetPx).animateTo(0f) { headerOffsetPx = value }
                                fnsGridState.scrollToItem(0)
                            }
                        } else {
                            coroutineScope.launch {
                                if (headerOffsetPx > -sharedPinsContentHeightPx) {
                                    Animatable(headerOffsetPx).animateTo(-sharedPinsContentHeightPx) {
                                        headerOffsetPx = value
                                    }
                                }
                                jumpToFunction(fnsGridState, model, letter, -(topContentPaddingPx + tabRowHeightPx).toInt())
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(top = topContentPadding + 10.dp)
                        .zIndex(3f),
                )
            }
        }
    }
}

/**
 * 测量 Header 高度容器：
 * 采用 Layout 内部宽松高度约束（minHeight=0, maxHeight=Infinity）自然测量子组件完整高度，
 * 并立即通过 onMeasuredHeight 回调同步高度。
 */
@Composable
private fun MeasuredHeader(
    onMeasuredHeight: (Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.Layout(
        content = content,
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val looseConstraints = constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
        val placeable = measurables.firstOrNull()?.measure(looseConstraints)
        val measuredHeight = placeable?.height ?: 0
        if (measuredHeight > 0) {
            onMeasuredHeight(measuredHeight.toFloat())
        }
        layout(constraints.maxWidth, measuredHeight) {
            placeable?.placeRelative(0, 0)
        }
    }
}

/**
 * 顶部全局共用的收藏区域渲染 (应用 + 功能)
 * 采用 Row + Column 分行布局，完全独立于 HorizontalPager，滑动 Tab 时保持原地不动。
 */
@Composable
private fun SharedPinsSection(
    model: PinManageModel,
    /** 折叠后的 cell 列表（与网格、字母条同一份，见 [PinManageScreen] 里的 `visibleCells`）。 */
    cells: List<Cell>,
    drag: PinDragState,
    gridSpan: Int,
    headText: String,
    emptyText: String,
    iconDp: Int,
    textSp: Int,
    onLaunch: (BubbleApp) -> Unit,
    onLaunchShortcut: ((ShortcutItem) -> Unit)?,
) {
    var sectionCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val pinnedApps = remember(cells) {
        cells.takeWhile { it !is Cell.Label }.filterIsInstance<Cell.Pin>()
    }
    // 收藏超过 Sections.VISIBLE_MAX 个时那一枚「展开更多」，垫在第 20 个下面（用户 2026-10-14）。
    val expandCell = remember(cells) {
        cells.takeWhile { it !is Cell.Label }.filterIsInstance<Cell.Expand>()
            .firstOrNull { it.section == Sections.PIN_SECTION }
    }
    val pinnedFns = model.pinnedFunctions

    val density = LocalDensity.current
    // 拖拽中的那一格：数据给"最上层再画一遍"用（见下面的 OverlayAt）。
    val ghostKey = drag.draggingKey
    val ghostApp = if (ghostKey == null) null else pinnedApps.firstOrNull { cellKey(it) == ghostKey }?.app
    val ghostFunction = if (ghostKey == null || !ghostKey.startsWith(FUNCTION_DRAG_PREFIX)) {
        null
    } else {
        pinnedFns.firstOrNull { functionDragKey(it) == ghostKey }
    }
    val ghostSize = drag.draggingSize()

    // 外面这层 Box 只是给"拖拽图层"一个与收藏区重合的坐标原点（`centers` 也是这个坐标系），
    // 高度仍然由里面的 Column 决定（拖拽图层是零尺寸的，见 [OverlayAt]）。
    Box(modifier = Modifier.fillMaxWidth()) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { sectionCoordinates = it }
            // 长按拖动排序的手势挂在**整个收藏区**上，而不是每一格上。
            //
            // 挂在每一格上有个致命后果：换位之后"这一格"在组合里已经被别人占了（下面的格子按位置
            // 组合，没有 key），`pointerInput` 的键（应用标识）跟着变 → 手势协程被取消 →
            // `onDragCancel` → 拖拽在**第一次换位的那一瞬间**就被放下（用户 2026-10-16 的原话：
            // 「app 顺序一变就自己放下来了」）。收藏区这个节点从头到尾不动，手势就不会被打断；
            // 抓的是哪一格交给落点判定（[PinDragState.grabAt]）。
            .pointerInput(model.manageMode, drag) {
                if (!model.manageMode) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { drag.onDragStart(it) },
                    onDrag = { change, amount ->
                        // 只有真抓住了某一格才吃手势：长按在标题行 / 功能格 / 展开行上时，
                        // 不要替列表吞掉滚动。
                        if (drag.isDragging) {
                            change.consume()
                            drag.onDrag(amount)
                        }
                    },
                    onDragEnd = { drag.onDragEnd() },
                    onDragCancel = { drag.onDragEnd() },
                )
            }
            .padding(horizontal = 14.dp),
    ) {
        HeadRow(headText)

        if (pinnedApps.isEmpty() && pinnedFns.isEmpty()) {
            EmptyHintRow(emptyText)
        } else {
            val entries = remember(pinnedApps, pinnedFns) {
                pinnedApps.map { PinnedEntry.App(it.app) } +
                    pinnedFns.map { PinnedEntry.Function(it) }
            }
            val rows = entries.chunked(gridSpan)
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    for (i in 0 until gridSpan) {
                        val entry = row.getOrNull(i)
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (entry != null) {
                                when (entry) {
                                    is PinnedEntry.App -> {
                                        PinCell(
                                            app = entry.app,
                                            model = model,
                                            drag = drag,
                                            manageMode = model.manageMode,
                                            iconDp = iconDp,
                                            textSp = textSp,
                                            onTap = {
                                                if (model.manageMode) model.togglePin(entry.app)
                                                else onLaunch(entry.app)
                                            },
                                            sectionCoordinates = sectionCoordinates,
                                        )
                                    }
                                    is PinnedEntry.Function -> {
                                        val onTap: () -> Unit = {
                                            if (model.manageMode) {
                                                model.toggleFunctionPin(entry.item)
                                            } else {
                                                onLaunchShortcut?.invoke(entry.item)
                                            }
                                        }
                                        PinnedFunctionCell(
                                            item = entry.item,
                                            drag = drag,
                                            manageMode = model.manageMode,
                                            iconDp = iconDp,
                                            textSp = textSp,
                                            onTap = onTap,
                                            sectionCoordinates = sectionCoordinates,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 收藏超过一屏时的那一枚「展开更多」，垫在第 N 个（= 上面这些行）下面。
        if (expandCell != null) {
            SectionExpandRow(onToggle = model::togglePinsExpanded)
        }
    }

        // 拖拽中的那一格画在**最上层**：格子是按行嵌套的（`Row` 里的 `Box` 里才是 `PinCell`），
        // `zIndex` 只管同一层的兄弟，跨行根本管不到 —— 用户 2026-10-16：「手指拖动的图标图层在
        // 动画下面」。所以在这里再画一遍（网格里那一格是透明的占位），它必然盖住所有格子与让位动画。
        if ((ghostApp != null || ghostFunction != null) && ghostSize != null) {
            val widthDp = with(density) { ghostSize.width.toDp() }
            val heightDp = with(density) { ghostSize.height.toDp() }
            // 位置用 lambda 传：跟手位移是快照状态，在测量里读才不会每帧重组整个收藏区
            // （每帧只重放这一个节点，30 个格子的重组在拖动时是纯浪费）。
            OverlayAt(offset = { drag.draggingVisualOffset(ghostSize) }) {
                Box(
                    modifier = Modifier
                        .size(widthDp, heightDp)
                        .graphicsLayer {
                            scaleX = 1.12f
                            scaleY = 1.12f
                            alpha = 0.9f
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (ghostApp != null) {
                        AppTile(
                            app = ghostApp,
                            manageMode = model.manageMode,
                            pinned = true,
                            iconDp = iconDp,
                            textSp = textSp,
                            cloneBadge = model.cloneBadge,
                            onTap = {},
                        )
                    } else if (ghostFunction != null) {
                        ShortcutCell(
                            item = ghostFunction,
                            iconDp = iconDp,
                            textSp = textSp,
                            onTap = {},
                            showAppBadge = true,
                            showBadge = model.manageMode,
                            isPinned = true,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 零尺寸的绝对定位图层：把 [content] 画在父节点坐标系里的 `offset()` 处，自己不占布局。
 *
 * 用途是让"拖拽中的那一格"出现在收藏区最上层（见 [SharedPinsSection]）；零尺寸是为了不给收藏区
 * 加高度 —— `MeasuredHeader` 量到的高度直接决定上滑折叠的偏移，多出一格高度会让收起位置跳一下。
 * [offset] 传 lambda：跟手位移每帧都变，在测量里读它就只重放这一个节点，不必重组整个收藏区。
 */
@Composable
private fun OverlayAt(offset: () -> Offset, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = content) { measurables, constraints ->
        val placeable = measurables.firstOrNull()?.measure(constraints)
        layout(0, 0) {
            val at = offset()
            placeable?.place(at.x.roundToInt(), at.y.roundToInt())
        }
    }
}

private fun cellKey(cell: Cell): String = when (cell) {
    is Cell.PinHead -> "head"
    is Cell.EmptyHint -> "empty"
    is Cell.Label -> "label:${cell.text}"
    is Cell.Pin -> "pin:${cell.app.packageName}#${cell.app.userId}"
    is Cell.App -> "app:${cell.app.packageName}#${cell.app.userId}@${cell.section}"
    is Cell.Expand -> "expand:${cell.section}"
}

/** 功能格在拖拽状态里的 key（不能和应用的 `pin:` 撞）。 */
private const val FUNCTION_DRAG_PREFIX = "fn:"

private fun functionDragKey(item: ShortcutItem): String = "$FUNCTION_DRAG_PREFIX${item.pinKey}"

/**
 * The overlay panel's 56dp look-alike bar: title left, search + listview icon buttons right.
 */
@Composable
private fun PanelBar(
    title: String,
    manageLabel: String,
    manageMode: Boolean,
    onToggleManage: () -> Unit,
    model: PinManageModel,
) {
    if (model.isSearching) {
        MiuixSearchBar(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            insideMargin = DpSize(12.dp, 0.dp),
            inputField = {
                InputField(
                    query = model.searchQuery,
                    onQueryChange = { model.searchQuery = it },
                    onSearch = { },
                    expanded = true,
                    onExpandedChange = { expanded ->
                        if (!expanded) model.cancelSearch()
                    },
                    label = stringResource(R.string.search_hint),
                )
            },
            expanded = true,
            outsideEndAction = {
                SearchCloseButton(onClick = model::cancelSearch)
            },
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.Bold),
            )
            IconButton(
                onClick = model::startSearch,
            ) {
                Icon(
                    imageVector = MiuixIcons.Search,
                    contentDescription = stringResource(R.string.search),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = onToggleManage,
            ) {
                Icon(
                    imageVector = MiuixIcons.ListView,
                    contentDescription = manageLabel,
                    tint = if (manageMode) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                )
            }
            // 加号已经去掉（用户 2026-10-13：面板功能页右上角那个加号不要）。
            // 面板里直接点「管理」就能点选功能，加号只是多一条会跳走的出口。
        }
    }
}

/** 收藏 header */
@Composable
private fun HeadRow(headText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp),
    ) {
        Text(
            text = headText,
            modifier = Modifier
                .weight(1f)
                .padding(top = 18.dp, bottom = 8.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2,
        )
    }
}

/** 暂无已添加应用 */
/**
 * 功能页加载进度：Miuix 官方 [LinearProgressIndicator] + 「第 x 个 / 共 y 个」。
 *
 * total 是扫描前的估算值，可能出现 total 还没到、loaded 已经到的情况；调用方已经保证
 * `functionTotal >= functionLoaded`，这里再夹一次，进度条不会超过 100%。
 * total=0（还没估完）时传 null，走官方不确定态动画。
 */
@Composable
private fun FunctionLoadingRow(loaded: Int, total: Int) {
    val safeTotal = total.coerceAtLeast(loaded)
    val progress = if (safeTotal > 0) {
        (loaded.toFloat() / safeTotal).coerceIn(0f, 1f)
    } else {
        null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.functions_loading),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
            )
            Text(
                text = stringResource(
                    R.string.functions_loading_progress,
                    loaded,
                    safeTotal,
                ),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
            )
        }
        Spacer(Modifier.height(8.dp))
        top.yukonga.miuix.kmp.basic.LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            progress = progress,
        )
    }
}

@Composable
private fun EmptyHintRow(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 52.dp),
            color = StatusColors.selectedNodeContainer(),
            style = MiuixTheme.textStyles.body2,
        )
    }
}

/** A..Z / 推荐 section label */
@Composable
private fun LabelRow(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 18.dp, bottom = 8.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.body2,
    )
}

/**
 * 「展开更多 ▾」。
 *
 * 用户 2026-10-14：某一区超过 [Sections.VISIBLE_MAX] 个时默认不显示多出来的部分，在第 20 个下面放
 * 一枚按钮，点了才放出来。2026-10-15 按用户给的样子改过（他 P 了一版）：
 *
 *  - **不用 miuix `TextButton`**：整块灰底圆角按钮太抢眼，压过了下面的应用列表；
 *  - 只有文字 + 一个小三角，**居中**、**低透明度**，像"这一区还没完"的提示而不是按钮；
 *  - 文案不带数量（原来那对"（还有 N 个）"括号是用户点名去掉的）；
 *  - **没有「收起」**：展开后这一行整个消失。
 *
 * 三角自己画（`Canvas` + Path）而不是用字形或图标：▼ 这个字符在不同字体下胖瘦不一，图标又要挑一个
 * 语义勉强合适的；一个 Path 就三行，形状完全可控。
 */
@Composable
private fun SectionExpandRow(onToggle: () -> Unit) {
    val tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.panel_section_expand),
            color = tint,
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.alpha(ExpandRowAlpha),
        )
        Spacer(Modifier.width(6.dp))
        Canvas(
            modifier = Modifier
                .size(11.dp, 6.dp)
                .alpha(ExpandRowAlpha),
        ) {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width / 2f, size.height)
                close()
            }
            drawPath(path, tint)
        }
    }
}

/** 展开提示的透明度：比正文浅一档，不跟应用列表抢注意力（用户 P 的那版就是这个观感）。 */
private const val ExpandRowAlpha = 0.55f

/** 长按落点判定的余量：格子之间本来没有缝，给一点容差让边缘也能抓住。 */
private const val SLOT_SLOP_PX = 12f

/** 让位动画时长：短到不拖沓，长到看得清"谁给谁让了位"（用户 2026-10-16 要的就是这个）。 */
private const val SLOT_ANIM_MS = 180

/**
 * ★ pinned cell: same tile as [AppCell] plus the long-press drag handle.
 */
@Composable
private fun PinCell(
    app: BubbleApp,
    model: PinManageModel,
    drag: PinDragState,
    manageMode: Boolean,
    iconDp: Int,
    textSp: Int,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    sectionCoordinates: LayoutCoordinates? = null,
) {
    val key = "pin:${app.packageName}#${app.userId}"
    val isDragging = drag.draggingKey == key
    var centerInParent by remember { mutableStateOf(Offset.Zero) }

    // 让位动画（用户 2026-10-16：「app 顺序一变就自己放下来了，也没有移动让位的动画」）。
    //
    // 起始位移必须在**组合期**取，不能在 effect 里取：effect 要等这一帧组合完才跑，那时新槽位
    // 已经按"位移 0"画过一帧了 —— 用户看到的就是"在终点闪一下，再重新放一遍让位动画"。
    // `shiftOf` 是纯读（换位发生时就写好了），所以放在 `remember` 里算没有副作用。
    val slot = drag.slotOf(key)
    val startShift = remember(key, slot, isDragging) {
        if (isDragging) null else drag.shiftOf(key)
    }
    val progress = remember(key, slot, isDragging) { Animatable(if (startShift == null) 1f else 0f) }
    LaunchedEffect(key, slot, isDragging) {
        if (startShift != null) progress.animateTo(1f, animationSpec = tween(SLOT_ANIM_MS))
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                // 拖拽期间也照登记：换位之后每一格都被摆到新槽位上，落点表跟着走才是对的
                // （拖拽本身的参照系是按下那一刻的快照，见 [PinDragState.initialSlots]）。
                val rootPos = if (sectionCoordinates != null && sectionCoordinates.isAttached && coords.isAttached) {
                    sectionCoordinates.localPositionOf(coords, Offset.Zero)
                } else {
                    coords.positionInParent()
                }
                centerInParent = rootPos + Offset(coords.size.width / 2f, coords.size.height / 2f)
                drag.register(key, centerInParent, Size(coords.size.width.toFloat(), coords.size.height.toFloat()))
            }
            .graphicsLayer {
                // 拖拽中的那一格**不在这里画**：它由收藏区最上层的拖拽图层负责（见 SharedPinsSection
                // 的 ghost）。格子是按行嵌套的，zIndex 跨不了行 —— 用户 2026-10-16：
                // 「手指拖动的图标图层在动画下面」。这里留一个透明的占位，免得整行重排。
                val remaining = 1f - progress.value
                translationX = if (isDragging) 0f else (startShift?.x ?: 0f) * remaining
                translationY = if (isDragging) 0f else (startShift?.y ?: 0f) * remaining
                alpha = if (isDragging) 0f else 1f
            },
        contentAlignment = Alignment.Center,
    ) {
        AppTile(
            app = app,
            manageMode = manageMode,
            pinned = true,
            iconDp = iconDp,
            textSp = textSp,
            cloneBadge = model.cloneBadge,
            onTap = onTap,
        )
    }
}

/**
 * ★ pinned function cell: same tile as [ShortcutCell] plus the long-press drag transform.
 *
 * 功能收藏和应用收藏的槽位是分开的（功能段在应用段之后），但拖拽的注册/让位动画与 [PinCell]
 * 共用同一个 [PinDragState]；key 加 `fn:` 前缀，避免和应用的 `pin:` 撞车。
 */
@Composable
private fun PinnedFunctionCell(
    item: ShortcutItem,
    drag: PinDragState,
    manageMode: Boolean,
    iconDp: Int,
    textSp: Int,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    sectionCoordinates: LayoutCoordinates? = null,
) {
    val key = functionDragKey(item)
    val isDragging = drag.draggingKey == key
    var centerInParent by remember { mutableStateOf(Offset.Zero) }
    val slot = drag.slotOf(key)
    val startShift = remember(key, slot, isDragging) {
        if (isDragging) null else drag.shiftOf(key)
    }
    val progress = remember(key, slot, isDragging) { Animatable(if (startShift == null) 1f else 0f) }
    LaunchedEffect(key, slot, isDragging) {
        if (startShift != null) progress.animateTo(1f, animationSpec = tween(SLOT_ANIM_MS))
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                val rootPos = if (sectionCoordinates != null && sectionCoordinates.isAttached && coords.isAttached) {
                    sectionCoordinates.localPositionOf(coords, Offset.Zero)
                } else {
                    coords.positionInParent()
                }
                centerInParent = rootPos + Offset(coords.size.width / 2f, coords.size.height / 2f)
                drag.register(key, centerInParent, Size(coords.size.width.toFloat(), coords.size.height.toFloat()))
            }
            .graphicsLayer {
                val remaining = 1f - progress.value
                translationX = if (isDragging) 0f else (startShift?.x ?: 0f) * remaining
                translationY = if (isDragging) 0f else (startShift?.y ?: 0f) * remaining
                alpha = if (isDragging) 0f else 1f
            },
        contentAlignment = Alignment.Center,
    ) {
        ShortcutCell(
            item = item,
            iconDp = iconDp,
            textSp = textSp,
            onTap = onTap,
            showAppBadge = true,
            showBadge = manageMode,
            isPinned = true,
        )
    }
}

/** Regular (unpinned) cell */
@Composable
private fun AppCell(
    app: BubbleApp,
    manageMode: Boolean,
    pinned: Boolean = false,
    iconDp: Int,
    textSp: Int,
    cloneBadge: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AppTile(
            app = app,
            manageMode = manageMode,
            pinned = pinned,
            iconDp = iconDp,
            textSp = textSp,
            cloneBadge = cloneBadge,
            onTap = onTap,
        )
    }
}

@Composable
private fun AppTile(
    app: BubbleApp,
    manageMode: Boolean,
    pinned: Boolean,
    iconDp: Int,
    textSp: Int,
    cloneBadge: Boolean,
    onTap: () -> Unit,
) {
    val iconSize = (if (iconDp > 0) iconDp else 50).dp
    val titleSp = if (textSp > 0) textSp else 12
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            AppIcon(app = app, size = iconSize, cloneBadge = cloneBadge)
            if (manageMode) {
                Image(
                    painter = painterResource(
                        if (pinned) R.drawable.app_launcher_item_remove_icon
                        else R.drawable.app_launcher_item_add_icon,
                    ),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 3.dp, y = (-3).dp)
                        .size(24.dp),
                )
            }
        }
        Text(
            text = app.label,
            modifier = Modifier.padding(top = 10.dp),
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = titleSp.sp,
            maxLines = 1,
        )
    }
}

private class PinDragState(
    private val model: PinManageModel,
    /**
     * 当前**画出来的**收藏项（折叠时只有前 [Sections.VISIBLE_MAX] 个）。
     *
     * 拖拽的落点只能在"看得见的格子"之间换算：`onDragStart` 拿 [centers] 反查每个格子的中心点，
     * 而 [centers] 是渲染时登记的 —— 用全量 `model.cells` 去数，格子数对不上，`initialSlots.size ==
     * pins.size` 这个前提一破，拖拽就会退化成"跟着手指飘但不换位"，甚至落到看不见的位置上。
     *
     * 传 lambda 而不是列表：状态本身按 `remember(model)` 复用（`movePin` 会改 `cells`，把它当 key
     * 会让每次换位都重建状态、拖到一半断掉），所以列表要在**按下那一刻**现取。
     *
     * **这个 lambda 必须读得到当下的值**（调用方用 `rememberUpdatedState` 喂进来）：`remember(model)`
     * 只在首次组合跑一次，直接捕获一个局部变量会把那一份**永远冻住**。
     */
    private val visiblePins: () -> List<Cell.Pin>,
    /** 已选功能（显示顺序 = [PinManageModel.pinnedFunctions]）。 */
    private val visibleFunctions: () -> List<ShortcutItem>,
) {

    var draggingKey: String? by mutableStateOf(null)
        private set

    /** 当前拖的是功能格还是应用格；两者的落点列表和持久化链不同。 */
    private var draggingIsFunction = false
    private var draggingFunctionKey = ""

    val isDragging: Boolean get() = draggingKey != null

    var translationX by mutableStateOf(0f)
        private set

    var translationY by mutableStateOf(0f)
        private set

    /** key → 格子中心；[sizes] 是同一格的尺寸（长按落点判定用）。都是渲染时登记的。 */
    private val centers = HashMap<String, Offset>()
    private val sizes = HashMap<String, Size>()
    private var initialSlots = emptyList<Offset>()
    private var currentSlot = 0
    private var initialCenter = Offset.Zero
    private var totalDrag = Offset.Zero

    /**
     * key → 待播的让位位移（旧槽位中心 − 新槽位中心）。换位那一刻由 [onDrag] 算好，格子在
     * **组合期**取走（[shiftOf]）—— 在 effect 里算会先在新位置画一帧，就是"在终点闪一下"。
     */
    private val pendingShifts = HashMap<String, Offset>()

    fun register(key: String, center: Offset, size: Size) {
        centers[key] = center
        sizes[key] = size
    }

    /** 长按落点抓的是哪一格：取最近的、且落在它自己那格范围里的中心点；没命中就 null。 */
    private fun grabAt(position: Offset): String? {
        var best: String? = null
        var bestDist = Float.MAX_VALUE
        for ((key, center) in centers) {
            val size = sizes[key] ?: continue
            val dx = position.x - center.x
            val dy = position.y - center.y
            if (dx < -(size.width / 2f + SLOT_SLOP_PX) || dx > size.width / 2f + SLOT_SLOP_PX) continue
            if (dy < -(size.height / 2f + SLOT_SLOP_PX) || dy > size.height / 2f + SLOT_SLOP_PX) continue
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                best = key
            }
        }
        return best
    }

    /** 这一格现在在哪个槽位（不在拖拽中返回 -1）—— 让位动画的"换了位置没有"就用它判断。 */
    fun slotOf(key: String): Int {
        if (draggingKey == null) return -1
        val keys = if (key.startsWith(FUNCTION_DRAG_PREFIX)) {
            visibleFunctions().map { functionDragKey(it) }
        } else {
            visiblePins().map { cellKey(it) }
        }
        return keys.indexOfFirst { it == key }
    }

    /** 这一格的让位位移（旧 − 新）；没挪过 = null。纯读，组合期调用。 */
    fun shiftOf(key: String): Offset? = pendingShifts[key]

    /** 拖拽中那一格的**当前视觉中心**（槽位中心 + 跟手位移，收藏区坐标）；没在拖 = null。 */
    fun draggingVisualCenter(): Offset? {
        if (draggingKey == null) return null
        val slotCenter = initialSlots.getOrNull(currentSlot) ?: initialCenter
        return slotCenter + Offset(translationX, translationY)
    }

    /** 拖拽中那一格的尺寸（渲染时登记的）；没在拖 = null。 */
    fun draggingSize(): Size? = draggingKey?.let { sizes[it] }

    /** 拖拽中那一格的左上角（收藏区坐标，已含跟手位移）—— 在测量里读，别在组合期读。 */
    fun draggingVisualOffset(size: Size): Offset {
        val center = draggingVisualCenter() ?: return Offset.Zero
        return Offset(center.x - size.width / 2f, center.y - size.height / 2f)
    }

    fun onDragStart(position: Offset) {
        val pins = visiblePins()
        val functions = visibleFunctions()
        val pinKeys = pins.map { cellKey(it) }
        val functionKeys = functions.map { functionDragKey(it) }
        // 落点表只留"现在真的画着"的格子：取消收藏、折叠到 20 个之外的格子不会自己注销，
        // 留着就是抓得住的幽灵格子（先清再抓，免得抓到一个已经不在的）。
        val alive = (pinKeys + functionKeys).toHashSet()
        centers.keys.retainAll(alive)
        sizes.keys.retainAll(alive)
        val key = grabAt(position) ?: return
        draggingKey = key
        draggingIsFunction = key.startsWith(FUNCTION_DRAG_PREFIX)
        draggingFunctionKey = if (draggingIsFunction) key.removePrefix(FUNCTION_DRAG_PREFIX) else ""
        val dragKeys = if (draggingIsFunction) functionKeys else pinKeys
        initialSlots = dragKeys.mapNotNull { centers[it] }
        val myPos = dragKeys.indexOfFirst { it == key }
        if (myPos >= 0 && initialSlots.size == dragKeys.size) {
            currentSlot = myPos
            initialCenter = initialSlots[myPos]
        } else {
            currentSlot = 0
            initialCenter = centers[key] ?: Offset.Zero
        }
        pendingShifts.clear()
        totalDrag = Offset.Zero
        translationX = 0f
        translationY = 0f
        // 「长按只放大、怎么拖都不动」这类反馈唯一能事后核对的东西：`slots` 必须等于 `pins`
        // （`slots=0` 就是落点表是空的 —— 拖拽只能在原地放大，一个格子都换不了）。用户 2026-10-16。
        val line = "PIN_DRAG_START key=$key kind=${if (draggingIsFunction) "function" else "app"} " +
            "items=${dragKeys.size} slots=${initialSlots.size} centers=${centers.size} myPos=$myPos"
        if (myPos >= 0 && initialSlots.size == dragKeys.size) {
            android.util.Log.i("BubbleDrawer", line)
        } else {
            android.util.Log.w("BubbleDrawer", "$line SLOTS_MISMATCH")
        }
    }

    fun onDrag(amount: Offset) {
        if (draggingKey == null || initialSlots.isEmpty()) return
        totalDrag += amount
        val fingerPos = initialCenter + totalDrag

        val hysteresisSq = 400f
        var bestSlot = currentSlot
        var bestDistSq = (fingerPos - initialSlots[currentSlot]).getDistanceSquared() - hysteresisSq

        for (i in initialSlots.indices) {
            if (i == currentSlot) continue
            val distSq = (fingerPos - initialSlots[i]).getDistanceSquared()
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                bestSlot = i
            }
        }

        if (bestSlot != currentSlot) {
            if (draggingIsFunction) {
                val functions = visibleFunctions()
                val target = functions.getOrNull(bestSlot) ?: return
                val fromKey = draggingFunctionKey
                val before = functions.map { it.pinKey }
                model.moveFunctionPin(fromKey, target.pinKey)
                val after = model.pinnedFunctions.map { it.pinKey }
                for ((index, movedKey) in after.withIndex()) {
                    val previous = before.indexOf(movedKey)
                    if (previous < 0 || previous == index) continue
                    val from = initialSlots.getOrNull(previous) ?: continue
                    val to = initialSlots.getOrNull(index) ?: continue
                    pendingShifts[FUNCTION_DRAG_PREFIX + movedKey] = from - to
                }
                currentSlot = bestSlot
            } else {
                val key = draggingKey ?: return
                val fromIndexInCells = model.cells.indexOfFirst { it is Cell.Pin && cellKey(it) == key }
                val pinIndices = model.cells.indices.filter { model.cells[it] is Cell.Pin }
                if (fromIndexInCells >= 0 && bestSlot in pinIndices.indices) {
                    // 换位前后各取一份顺序，才能算清"谁给谁让了位"：只知道自己挪到哪不够 —— 被推着走的
                    // 那些格子也要各自滑一格（用户 2026-10-16 要的就是这个）。
                    // 这里读 `model.visibleCells` 而不是组合用的 `visibleCells`：后者要等下一次组合才更新。
                    val before = model.visibleCells.filterIsInstance<Cell.Pin>().map { cellKey(it) }
                    val toIndexInCells = pinIndices[bestSlot]
                    model.movePin(fromIndexInCells, toIndexInCells)
                    val after = model.visibleCells.filterIsInstance<Cell.Pin>().map { cellKey(it) }
                    for ((index, movedKey) in after.withIndex()) {
                        val previous = before.indexOf(movedKey)
                        if (previous < 0 || previous == index) continue
                        val from = initialSlots.getOrNull(previous) ?: continue
                        val to = initialSlots.getOrNull(index) ?: continue
                        pendingShifts[movedKey] = from - to
                    }
                    currentSlot = bestSlot
                }
            }
        }

        val currentSlotCenter = initialSlots.getOrNull(currentSlot) ?: initialCenter
        translationX = fingerPos.x - currentSlotCenter.x
        translationY = fingerPos.y - currentSlotCenter.y
    }

    fun onDragEnd() {
        val key = draggingKey ?: return
        val wasFunction = draggingIsFunction
        draggingKey = null
        draggingIsFunction = false
        draggingFunctionKey = ""
        translationX = 0f
        translationY = 0f
        initialSlots = emptyList()
        pendingShifts.clear()
        if (wasFunction) {
            model.persistFunctionOrder()
        } else {
            model.persistOrder()
        }
        android.util.Log.i(
            "BubbleDrawer",
            "PIN_DRAG_END key=$key kind=${if (wasFunction) "function" else "app"} " +
                "index=${if (wasFunction) model.functionPins.indexOf(key.removePrefix(FUNCTION_DRAG_PREFIX)) else model.cells.indexOfFirst { it is Cell.Pin && cellKey(it) == key }}",
        )
    }
}

/**
 * 通用字母快速索引条浮层，支持应用页与功能页复用。
 */
@Composable
private fun GenericLetterBarOverlay(
    letters: List<String>,
    currentLetter: String,
    onLetterSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (letters.isEmpty()) return
    val density = LocalDensity.current
    val barLetters = remember(letters) { letters.map { barGlyph(it) } }

    val normalColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.toArgb()
    val currentTextColor = MiuixTheme.colorScheme.onPrimary.toArgb()
    val selectedBgColor = normalColor

    AndroidView(
        modifier = modifier,
        factory = { context ->
            LetterIndexBar(context).apply {
                this.onLetterSelected = onLetterSelected
            }
        },
        update = { bar ->
            val w = with(density) { 28.dp.roundToPx() }
            bar.minimumWidth = w
            bar.letters = barLetters
            bar.currentLetter = barGlyph(currentLetter)
            bar.centerVertically = true
            bar.allowSampledDisplay = true
            bar.refreshColors(
                normal = normalColor,
                currentText = currentTextColor,
                selectedBg = selectedBgColor,
            )
        },
    )
}

private const val PINNED_GLYPH = "★"
private const val RECOMMEND_GLYPH = "♥"

private fun barGlyph(section: String): String = when (section) {
    "★" -> PINNED_GLYPH
    "推荐" -> RECOMMEND_GLYPH
    else -> section
}

private fun jumpTo(
    gridState: LazyGridState,
    cells: List<Cell>,
    letter: String,
    headerOffsetPx: Int = 0,
) {
    val index = cells.indexOfFirst { it is Cell.Label && barGlyph(it.text) == letter }
    if (index >= 0) gridState.requestScrollToItem(index, headerOffsetPx)
}

private fun jumpToFunction(
    gridState: LazyGridState,
    model: PinManageModel,
    letter: String,
    headerOffsetPx: Int = 0,
) {
    // 网格第 0 位是内建的「聚合支付」格（见 PinManageScreen 功能页），加载中还有一条进度行
    // —— 字母分组都排在这两者后面，跳转下标必须把它们算进去，否则永远差一两个格子。
    var targetIndex = 1 + if (model.functionLoading) 1 else 0
    for (group in model.groupedFunctions) {
        val ch = group.sortKey.firstOrNull()?.uppercaseChar() ?: '#'
        val groupLetter = if (ch in 'A'..'Z') ch.toString() else "#"
        if (groupLetter == letter) {
            gridState.requestScrollToItem(targetIndex, headerOffsetPx)
            return
        }
        targetIndex += (1 + group.items.size)
    }
}

/**
 * 需求 1 & 10：自定义样式的胶囊 TabRow，缩短居中，圆角改为胶囊，
 * 并支持局部 textureBlur（仅限 TabRow 胶囊那一小块）与微透磨砂玻璃效果。
 */
@Composable
fun CapsuleTabRow(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop? = null,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(
        stringResource(R.string.slide_launcher_tab_all_app),
        stringResource(R.string.slide_launcher_tab_all_shortcut),
    )
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .then(
                    if (backdrop != null) {
                        Modifier.textureBlur(
                            backdrop = backdrop,
                            shape = CircleShape,
                            blurRadius = 20f,
                        )
                    } else {
                        Modifier
                    }
                )
                .clip(CircleShape)
                .background(
                    if (backdrop != null) {
                        MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    } else {
                        MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                    }
                )
                .border(
                    width = 0.5.dp,
                    color = MiuixTheme.colorScheme.outline.copy(alpha = 0.2f),
                    shape = CircleShape,
                )
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, title ->
                val selected = selectedTabIndex == index
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .defaultMinSize(minWidth = 84.dp)
                        .clip(CircleShape)
                        .background(if (selected) MiuixTheme.colorScheme.primary else Color.Transparent)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onTabSelected(index) },
                        )
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title,
                        color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/**
 * 快捷方式/功能单元格：图标、单行文字、点击启动或点击勾选/取消。
 */
@Composable
private fun ShortcutCell(
    item: ShortcutItem,
    iconDp: Int,
    textSp: Int,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    showAppBadge: Boolean = false,
    showBadge: Boolean = false,
    isPinned: Boolean = false,
) {
    val iconSize = (if (iconDp > 0) iconDp else 50).dp
    val titleSp = if (textSp > 0) textSp else 12
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            ShortcutIcon(
                item = item,
                size = iconSize,
                showAppBadge = showAppBadge,
            )
            if (showBadge) {
                Image(
                    painter = painterResource(
                        if (isPinned) R.drawable.app_launcher_item_remove_icon
                        else R.drawable.app_launcher_item_add_icon,
                    ),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 3.dp, y = (-3).dp)
                        .size(24.dp),
                )
            }
        }
        Text(
            text = item.label,
            modifier = Modifier.padding(top = 10.dp),
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = titleSp.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ShortcutIcon(
    item: ShortcutItem,
    size: Dp,
    showAppBadge: Boolean = false,
) {
    val context = LocalContext.current
    var fallbackBmp by remember(item.packageName, item.id) { mutableStateOf<android.graphics.Bitmap?>(null) }

    val rawBmp = item.iconBitmap
    val rawDrawable = item.iconDrawable

    // 如果未拿到快捷方式自带图标，异步尝试从宿主应用加载应用图标作为兜底，彻底杜绝只显示首字。
    // 必须离开主线程：面板跑在 SystemUI 里，这里一次 getApplicationIcon 就是一次跨进程调用，
    // 每个缺图标的格子都来一发会把系统界面卡住（图标本身另有按包名缓存，见 applicationIcon）。
    if (rawBmp == null && rawDrawable == null && fallbackBmp == null && item.packageName.isNotEmpty()) {
        LaunchedEffect(item.packageName) {
            val loaded = withContext(Dispatchers.IO) {
                ShortcutRepository.applicationIcon(context, item.packageName)
            }
            if (loaded != null) {
                fallbackBmp = loaded
            }
        }
    }

    // remember 不能挂在 elvis 右边：那样它的调用位置会随 item 数据变化（这一帧走了 rawBmp、
    // 下一帧（图标缺失的上报）走 remember），同一 key 的组合槽位序列就变了。
    val drawableBmp = if (rawDrawable != null) {
        remember(rawDrawable) { ShortcutRepository.drawableToBitmap(rawDrawable, ShortcutBridge.ICON_MAX_PX) }
    } else null
    val activeBmp = rawBmp ?: fallbackBmp ?: drawableBmp

    Box(modifier = Modifier.size(size)) {
        if (activeBmp != null) {
            val isTransparent = remember(activeBmp) {
                ShortcutRepository.isTransparentBitmap(activeBmp)
            }
            if (isTransparent) {
                val isLight = remember(activeBmp) {
                    ShortcutRepository.isLightForeground(activeBmp)
                }
                // 透明底的 shortcut：经过视觉归一化（Foreground Normalization），
                // 剔除无效边缘空白，将前景图案尺寸与重心严格归一化居中，并提供高品质圆形底座。
                // 归一化结果按 pinKey 缓存（[ShortcutRepository.normalizedForeground]）：
                // 组合里逐像素重算 + 每次滚回来都新建一张位图是白花的。
                val normalizedBmp = remember(activeBmp) {
                    ShortcutRepository.normalizedForeground(item, activeBmp)
                }
                val plateBg = if (isLight) {
                    MiuixTheme.colorScheme.surfaceVariant
                } else {
                    Color.White
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(plateBg)
                        .border(0.5.dp, Color.Black.copy(alpha = 0.08f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = normalizedBmp.asImageBitmap(),
                        contentDescription = item.label,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            } else {
                // 实底图标：直接充满圆形显示，不加多余 padding
                Image(
                    bitmap = activeBmp.asImageBitmap(),
                    contentDescription = item.label,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            }
        } else {
            // 极限兜底：首字占位（仅在应用完全无法访问时）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.label.take(1),
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = (size.value * 0.45f).sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // 需求 6：添加的功能在左下角显示所属应用图标（参考 Flyme 原生样式）
        if (showAppBadge) {
            val badgeSize = (size.value * 0.42f).dp
            var appBmp by remember(item.packageName) {
                mutableStateOf(item.appIconBitmap)
            }
            if (appBmp == null && item.packageName.isNotEmpty()) {
                LaunchedEffect(item.packageName) {
                    val loaded = withContext(Dispatchers.IO) {
                        ShortcutRepository.applicationIcon(context, item.packageName, 96)
                    }
                    if (loaded != null) {
                        appBmp = loaded
                    }
                }
            }

            val currentAppBmp = appBmp
            if (currentAppBmp != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = (-2).dp, y = 2.dp)
                        .size(badgeSize)
                        .background(MiuixTheme.colorScheme.surface, CircleShape)
                        .padding(1.5.dp)
                        .clip(CircleShape),
                ) {
                    Image(
                        bitmap = currentAppBmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
}
