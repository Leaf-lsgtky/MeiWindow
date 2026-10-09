package com.repl.bubbledrawer.pin

import android.content.res.Configuration
import android.graphics.drawable.Drawable
import com.repl.bubbledrawer.data.AppIconCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.data.AppRepository
import com.repl.bubbledrawer.pinyin.BubbleApp
import com.repl.bubbledrawer.ui.theme.StatusColors
import com.repl.bubbledrawer.ui.util.BlurredBar
import com.repl.bubbledrawer.ui.util.PageBottomSpacer
import com.repl.bubbledrawer.ui.util.horizontalCutoutPadding
import com.repl.bubbledrawer.ui.util.pageBackdrop
import com.repl.bubbledrawer.ui.util.pageScroll
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The 管理页 (`SlideLaunchAppSettings` 应用 tab slice) in Compose. ONE page serves both
 * hosts (方案 B): [Chrome.ACTIVITY] lets the Activity draw the miuix bar; [Chrome.PANEL]
 * draws the 56dp look-alike bar itself because the overlay window has no action bar.
 *
 * Grid: 4 columns (f10411g0 = 4, SlideLaunchAppSettings:87); head/label/empty rows take a
 * full span (LightWeightOpenSettings' SpanSizeLookup idiom :193-198). View-mode tap
 * launches (original AbstractC2806s.m9105e with start_windowmode=true); manage-mode tap
 * toggles the pin; long-press on a ★ cell starts the drag (ItemTouchHelper semantics).
 */
enum class Chrome { ACTIVITY, PANEL }

private const val GRID_SPAN = 4

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
    topPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop? = null,
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior? = null,
) {
    val gridState = rememberLazyGridState()
    val drag = remember(model) { PinDragState(model) }
    val title = stringResource(R.string.slide_launch_app_settings_title)
    val tabText = stringResource(R.string.slide_launcher_tab_all_app)
    val headText = stringResource(R.string.slide_launch_app_has_selected)
    val emptyText = stringResource(R.string.settings_tips_click_to_add_app)

    // The page ALWAYS opens at the very top (收藏 head). Without this, a reused
    // composition (panel reopened, or the first `reload()` landing after layout) can
    // restore/keep a mid-list position — the original Activity always started at 0.
    LaunchedEffect(Unit) { gridState.scrollToItem(0) }
    LaunchedEffect(model.cells.isNotEmpty()) {
        if (model.cells.isNotEmpty()) gridState.scrollToItem(0)
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val gridSpan = if (isLandscape) 6 else 4

    Box(Modifier.fillMaxSize()) {
        val topContentPadding = if (chrome == Chrome.PANEL) 56.dp else topPadding.calculateTopPadding()
        LazyVerticalGrid(
            columns = GridCells.Fixed(gridSpan),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .pageBackdrop(backdrop)
                .pageScroll(scrollBehavior),
            contentPadding = PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = topContentPadding,
                bottom = 16.dp,
            ),
        ) {
            itemsIndexed(
                items = model.cells,
                key = { _, cell -> cellKey(cell) },
                contentType = { _, cell ->
                    when (cell) {
                        is Cell.PinHead -> 0
                        is Cell.EmptyHint -> 1
                        is Cell.Label -> 2
                        is Cell.Pin -> 3
                        is Cell.App -> 4
                    }
                },
                span = { _, cell ->
                    if (cell is Cell.Pin || cell is Cell.App) GridItemSpan(1) else GridItemSpan(gridSpan)
                },
            ) { index, cell ->
                val cellModifier = if (model.manageMode) Modifier.animateItem() else Modifier
                when (cell) {
                    is Cell.PinHead -> HeadRow(headText)
                    is Cell.EmptyHint -> EmptyHintRow(emptyText)
                    is Cell.Label -> LabelRow(cell.text)
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
                        iconDp = iconDp,
                        textSp = textSp,
                        onTap = { if (model.manageMode) model.togglePin(cell.app) else onLaunch(cell.app) },
                        modifier = cellModifier,
                    )
                }
            }
            item(key = "bottom", span = { GridItemSpan(gridSpan) }) { PageBottomSpacer() }
        }

        if (chrome == Chrome.PANEL) {
            BlurredBar(
                backdrop = backdrop,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
            ) {
                PanelBar(
                    title = title,
                    manageLabel = manageLabel,
                    manageMode = model.manageMode,
                    onToggleManage = onToggleManage,
                )
            }
        }

        LetterBarOverlay(
            letters = model.letters,
            gridState = gridState,
            cells = model.cells,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(top = if (chrome == Chrome.PANEL) 56.dp else 20.dp),
        )
    }
}

private fun cellKey(cell: Cell): String = when (cell) {
    is Cell.PinHead -> "head"
    is Cell.EmptyHint -> "empty"
    is Cell.Label -> "label:${cell.text}"
    is Cell.Pin -> "pin:${cell.app.packageName}#${cell.app.userId}"
    // Same app legitimately appears in BOTH 推荐 and its A–Z group; the owning section
    // disambiguates. (Lazy keys must be unique; the old RecyclerView never needed keys.)
    is Cell.App -> "app:${cell.app.packageName}#${cell.app.userId}@${cell.section}"
}

/**
 * The overlay panel's 56dp look-alike bar: title left, listview icon button right.
 */
@Composable
private fun PanelBar(
    title: String,
    manageLabel: String,
    manageMode: Boolean,
    onToggleManage: () -> Unit,
) {
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
            onClick = onToggleManage,
        ) {
            Icon(
                imageVector = MiuixIcons.ListView,
                contentDescription = manageLabel,
                tint = if (manageMode) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 收藏 header (原 已添加 + 长按拖动图标以排序，长按拖动提示已删掉). */
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

/** 暂无已添加应用 — keeps the original empty-area height (minHeight 78dp). */
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

/** A..Z / 推荐 section label — app_settings_item_all_header verbatim paddings. */
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
 * ★ pinned cell: same tile as [AppCell] plus the long-press drag handle. While dragging,
 * the tile lifts (scale + shadow via graphicsLayer, read at draw time) and follows the
 * finger horizontally within its row; crossing a neighbour's half-width reorders live.
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
) {
    val key = "pin:${app.packageName}#${app.userId}"
    val isDragging = drag.draggingKey == key
    var centerInParent by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = (if (isDragging) Modifier else modifier)
            .zIndex(if (isDragging) 1f else 0f)
            .onGloballyPositioned { coords ->
                if (!drag.isDragging) {
                    centerInParent = coords.positionInParent() + Offset(coords.size.width / 2f, coords.size.height / 2f)
                    drag.register(key, centerInParent)
                }
            }
            .graphicsLayer {
                translationX = if (isDragging) drag.translationX else 0f
                translationY = if (isDragging) drag.translationY else 0f
                scaleX = if (isDragging) 1.12f else 1f
                scaleY = if (isDragging) 1.12f else 1f
                alpha = if (isDragging) 0.9f else 1f
            }
            .pointerInput(model.manageMode, key) {
                if (!model.manageMode) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { drag.onDragStart(key) },
                    onDrag = { change, amount ->
                        change.consume()
                        drag.onDrag(amount)
                    },
                    onDragEnd = { drag.onDragEnd() },
                    onDragCancel = { drag.onDragEnd() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AppTile(
            app = app,
            manageMode = manageMode,
            pinned = true,
            iconDp = iconDp,
            textSp = textSp,
            onTap = onTap,
        )
    }
}

/** Regular (unpinned) cell: identical tile, no drag handle. */
@Composable
private fun AppCell(
    app: BubbleApp,
    manageMode: Boolean,
    iconDp: Int,
    textSp: Int,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AppTile(
            app = app,
            manageMode = manageMode,
            pinned = false,
            iconDp = iconDp,
            textSp = textSp,
            onTap = onTap,
        )
    }
}

/**
 * launcher_app_item.xml as a composable: 50dp circular icon (panel override via
 * [iconDp]), 12sp medium title (panel override via [textSp]), 24dp add/remove badge at
 * the top-end in manage mode. The icon clip is a plain circle (the original is a
 * CircleImageView); squircle modifiers are reserved for hand-drawn containers.
 */
@Composable
private fun AppTile(
    app: BubbleApp,
    manageMode: Boolean,
    pinned: Boolean,
    iconDp: Int,
    textSp: Int,
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
            AppIcon(app = app, size = iconSize)
            if (manageMode) {
                // ORIGINAL badge assets (app_launcher_item_add/remove_icon.png, decompiled
                // res) — the miuix vector detour read wrong at 24dp; the tile is a replica,
                // so the badge is a replica too. 24dp, pinned into the icon's top-end
                // corner with a small break-out (the decompiled RelativeLayout anchored it
                // to the icon box's outer corner, which sits a bit further out/up than a
                // plain TopEnd inside the padded tile).
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

/**
 * Drawable → ImageBitmap for a launcher icon. `remember(app.packageName)` keeps the
 * decode per cell; a recomposition re-uses it, a rebind decodes once.
 */
@Composable
private fun AppIcon(app: BubbleApp, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val px = with(density) { size.roundToPx() }

    // Instant memory-cache hit (0 latency, 0 main-thread I/O)
    var imageBitmap by remember(app.packageName, app.userId, px) {
        mutableStateOf(AppIconCache.get(app.packageName, app.userId, px))
    }

    if (imageBitmap == null) {
        LaunchedEffect(app.packageName, app.userId, px) {
            val loaded = AppIconCache.loadOrGet(context, app, px)
            if (loaded != null) {
                imageBitmap = loaded
            }
        }
    }

    val currentBmp = imageBitmap
    if (currentBmp != null) {
        Image(
            bitmap = currentBmp,
            contentDescription = app.label,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
        )
    }
}

/**
 * ★ drag coordinator. Cell centers land in a PLAIN map — gesture callbacks read it, no
 * composition ever does. Continuous 2D slot-based tracking keeps translation stable and
 * eliminates jumping.
 */
private class PinDragState(private val model: PinManageModel) {

    var draggingKey: String? by mutableStateOf(null)
        private set

    val isDragging: Boolean get() = draggingKey != null

    var translationX by mutableStateOf(0f)
        private set

    var translationY by mutableStateOf(0f)
        private set

    private val centers = HashMap<String, Offset>()
    private var initialSlots = emptyList<Offset>()
    private var currentSlot = 0
    private var initialCenter = Offset.Zero
    private var totalDrag = Offset.Zero

    fun register(key: String, center: Offset) {
        if (draggingKey == null) {
            centers[key] = center
        }
    }

    fun onDragStart(key: String) {
        draggingKey = key
        val pins = model.cells.filterIsInstance<Cell.Pin>()
        initialSlots = pins.mapNotNull { centers[cellKey(it)] }
        val myPos = pins.indexOfFirst { cellKey(it) == key }
        if (myPos >= 0 && initialSlots.size == pins.size) {
            currentSlot = myPos
            initialCenter = initialSlots[myPos]
        } else {
            currentSlot = 0
            initialCenter = centers[key] ?: Offset.Zero
        }
        totalDrag = Offset.Zero
        translationX = 0f
        translationY = 0f
    }

    fun onDrag(amount: Offset) {
        if (draggingKey == null || initialSlots.isEmpty()) return
        totalDrag += amount
        val fingerPos = initialCenter + totalDrag

        // Hysteresis prevents flickering between adjacent slots
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
            val key = draggingKey ?: return
            val fromIndexInCells = model.cells.indexOfFirst { it is Cell.Pin && cellKey(it) == key }
            val pinIndices = model.cells.indices.filter { model.cells[it] is Cell.Pin }
            if (fromIndexInCells >= 0 && bestSlot in pinIndices.indices) {
                val toIndexInCells = pinIndices[bestSlot]
                model.movePin(fromIndexInCells, toIndexInCells)
                currentSlot = bestSlot
            }
        }

        val currentSlotCenter = initialSlots.getOrNull(currentSlot) ?: initialCenter
        translationX = fingerPos.x - currentSlotCenter.x
        translationY = fingerPos.y - currentSlotCenter.y
    }

    fun onDragEnd() {
        if (draggingKey == null) return
        draggingKey = null
        translationX = 0f
        translationY = 0f
        initialSlots = emptyList()
        model.persistOrder()
    }
}

/**
 * Right-edge letter index (★ / ♥ / A–Z / #) as an AndroidView island: the View port is a
 * faithful port of `com.meizu.common.fastscrollletter` with constant-level fidelity, and
 * embedding it keeps that geometry byte-for-byte. Colors are injected from the theme so
 * the island follows Monet/dark with the rest of the page.
 *
 * Section-symbol mapping: the bar shows SINGLE GLYPHS. The 推荐 group header keeps its
 * word in the grid but maps to ♥ on the bar; `jumpTo`/scroll-sync translate both ways.
 * The bar is vertically CENTERED and, when the letters don't fit, draws every Nth glyph
 * (A C E …) while touch still maps 1:1 onto the full list — the bubble always shows the
 * real target letter (meizu original shrinks pitch instead; the sampled bar is the MIUI
 * launcher behaviour and reads better on a short panel).
 *
 * Selection → jump: `scrollToItem` on the LABEL row of that letter. Scroll → highlight:
 * derived from `firstVisibleItemIndex` (a DISCRETE read in composition — allowed; the
 * banned pattern is per-frame floats).
 */
@Composable
private fun LetterBarOverlay(
    letters: List<String>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    cells: List<Cell>,
    modifier: Modifier = Modifier,
) {
    if (letters.isEmpty()) return
    val density = LocalDensity.current
    val barLetters = remember(letters) { letters.map { barGlyph(it) } }

    // Discrete derived state: recomposes only when the top section actually changes.
    val visibleLetter by remember(cells) {
        derivedStateOf {
            val first = gridState.firstVisibleItemIndex
            var cur: String? = null
            for (i in first downTo 0) {
                val c = cells.getOrNull(i)
                if (c is Cell.Label) { cur = c.text; break }
            }
            cur ?: PINNED_GLYPH
        }
    }

    // Resolve theme colors in composition; the AndroidView update block is NOT a
    // composable context and must only receive plain values.
    val normalColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.toArgb()
    val currentTextColor = MiuixTheme.colorScheme.onPrimary.toArgb()
    val selectedBgColor = normalColor

    AndroidView(
        modifier = modifier,
        factory = { context ->
            LetterIndexBar(context).apply {
                onLetterSelected = { letter -> jumpTo(gridState, cells, letter) }
            }
        },
        update = { bar ->
            val w = with(density) { 28.dp.roundToPx() }
            bar.minimumWidth = w
            // Bar wants single glyphs; translate section labels (推荐 → ♥) and CENTER it.
            bar.letters = barLetters
            bar.currentLetter = barGlyph(visibleLetter)
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

/** The glyph that marks the ★ pinned area on the index bar. */
private const val PINNED_GLYPH = "★"

/** The glyph that marks the 推荐 section on the index bar. */
private const val RECOMMEND_GLYPH = "♥"

/** Section label → single glyph for the bar (identity for A–Z and #). */
private fun barGlyph(section: String): String = when (section) {
    "★" -> PINNED_GLYPH
    "推荐" -> RECOMMEND_GLYPH
    else -> section
}

private fun jumpTo(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    cells: List<Cell>,
    letter: String,
) {
    val index = if (letter == PINNED_GLYPH) 0
    else cells.indexOfFirst { it is Cell.Label && barGlyph(it.text) == letter }
    if (index >= 0) gridState.requestScrollToItem(index)
}

