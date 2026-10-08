package com.repl.bubbledrawer.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Card-like segments assembled from INDEPENDENT lazy items
 * (docs/ui-guidelines.md "多组件卡片拆为独立 lazy item"): a `LazyColumn` must not host
 * `item { Card { many rows } }`, because the whole card recomposes/measures as one node
 * and long lists judder. Each row becomes its own item; [CardSegment] paints the shared
 * container and the corner radii so the group still LOOKS like one card.
 *
 * Rules encoded here (guidelines verbatim):
 *  - first/last segments (any radius != 0) use `squircleSurface` — fill + clip, because
 *    a clickable row's ripple would otherwise bleed past the rounded corners;
 *  - middle segments use plain `background` — no offscreen layer, cheapest;
 *  - colors align with miuix Card (surfaceContainer + 16.dp radius); rows bring their own
 *    inside padding, so segments add none (CardDefaults.InsideMargin is 0.dp in 0.9.3 too).
 */

object CardSegmentDefaults {
    val CornerRadius = 16.dp
}

/**
 * A standalone one-item card (static text cards per the guidelines stay a single lazy
 * item instead of being split). Same container color and radius as a grouped card so the
 * two look identical side by side.
 */
@Composable
fun GroupedSection(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    CardSegment(
        topStart = CardSegmentDefaults.CornerRadius,
        topEnd = CardSegmentDefaults.CornerRadius,
        bottomEnd = CardSegmentDefaults.CornerRadius,
        bottomStart = CardSegmentDefaults.CornerRadius,
        modifier = modifier,
        content = content,
    )
}

/**
 * One horizontal slice of a grouped card.
 *
 * @param topStart/topEnd/bottomEnd/bottomStart corner radii of this slice; pass 0.dp on
 *   sides that continue into the next slice. Use [topCardSegment]/[middleCardSegment]/
 *   [bottomCardSegment]/[fullCardSegment] helpers instead of computing by hand.
 * @param outerBottomPadding the 12.dp between card groups (guidelines: 水平 12 + bottom 12);
 *   pass 0.dp inside the helpers' defaults when a group is the LAST thing on the page.
 */
@Composable
fun CardSegment(
    topStart: Dp,
    topEnd: Dp,
    bottomEnd: Dp,
    bottomStart: Dp,
    modifier: Modifier = Modifier,
    containerColor: Color = MiuixTheme.colorScheme.surfaceContainer,
    content: @Composable () -> Unit,
) {
    val anyRadius = topStart > 0.dp || topEnd > 0.dp || bottomEnd > 0.dp || bottomStart > 0.dp
    val shapeModifier = if (anyRadius) {
        Modifier.squircleSurface(
            color = containerColor,
            topStart = topStart,
            topEnd = topEnd,
            bottomEnd = bottomEnd,
            bottomStart = bottomStart,
        )
    } else {
        Modifier.background(containerColor)
    }
    Box(modifier = modifier.then(shapeModifier)) {
        content()
    }
}

/** One row of a grouped card: a stable lazy key plus the row composable. */
class CardItem(val key: String, val content: @Composable () -> Unit)

/**
 * Emit [items] as one visual card: independent lazy items, [CardSegment] corners welded
 * back together, horizontal 12.dp on every segment and [outerBottomPadding] only under
 * the last one. Deliberately adds NO item animation — the split is an invisible
 * performance change; rows that need motion attach `Modifier.animateItem()` themselves
 * (and must not set the placement spec to null: the groups below would snap).
 */
fun androidx.compose.foundation.lazy.LazyListScope.groupedCardItems(
    keyPrefix: String,
    items: List<CardItem>,
    outerBottomPadding: Dp = 12.dp,
) {
    items.forEachIndexed { index, item ->
        val first = index == 0
        val last = index == items.lastIndex
        item(key = "$keyPrefix:${item.key}", contentType = "card-segment") {
            CardSegment(
                topStart = if (first) CardSegmentDefaults.CornerRadius else 0.dp,
                topEnd = if (first) CardSegmentDefaults.CornerRadius else 0.dp,
                bottomEnd = if (last) CardSegmentDefaults.CornerRadius else 0.dp,
                bottomStart = if (last) CardSegmentDefaults.CornerRadius else 0.dp,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .then(if (last) Modifier.padding(bottom = outerBottomPadding) else Modifier),
            ) { item.content() }
        }
    }
}

