package com.repl.bubbledrawer.pin

import androidx.compose.runtime.Immutable
import com.repl.bubbledrawer.data.PinnedRef
import com.repl.bubbledrawer.pinyin.AppSortKey
import com.repl.bubbledrawer.pinyin.BubbleApp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * Flat cell model for the pin-manager grid.
 *
 * The original renders BOTH areas as 4-column grids:
 *  - ★ area: AppDragLayout → GridLayoutManager(context, 4) (AppDragLayout.java:185),
 *    one item per app, drag-sortable;
 *  - letter area: rows grouped 4-per-line via C2875i subList chunks (:561/:615) with
 *    section labels — identical visual result to one grid with span=1 cells and
 *    full-span label rows (LightWeightOpenSettings' SpanSizeLookup idiom :193-198).
 *
 * The list is an [ImmutableList] produced HERE (docs/ui-guidelines.md "强跳过友好的状态
 * 形状"): converting at the UI layer would leave every intermediate composable unskippable.
 */
@Immutable
sealed interface Cell {
    data object PinHead : Cell                 // 已添加 + 长按拖动图标以排序 (app_settings_item_head)
    data object EmptyHint : Cell               // 暂无已添加应用 (click_add_tip, only while pins empty)
    data class Label(val text: String) : Cell  // ★/推荐/A..Z/# group header
    data class Pin(val app: BubbleApp) : Cell  // pinned slot, drag-reorderable
    /** @param section owning group label — lazy-grid keys need it (recommend + letter
     *  groups overlap by design, so pkg alone is not unique). */
    data class App(val app: BubbleApp, val section: String) : Cell  // regular grid cell
}

private fun MutableList<Cell>.addApp(app: BubbleApp, section: String) = add(Cell.App(app, section))

object Sections {

    const val RECOMMEND_TOP_N = 8

    fun build(
        all: List<BubbleApp>,
        pins: List<PinnedRef>,
        recommend: Boolean,
        recommendCount: Int = RECOMMEND_TOP_N,
        recommendedApps: List<BubbleApp> = emptyList(),
    ): ImmutableList<Cell> {
        val pinnedKeys = pins.map { "${it.packageName}#${it.userId}" }.toSet()
        val out = ArrayList<Cell>()

        out.add(Cell.PinHead)
        if (pins.isEmpty()) {
            // SlideLaunchAppSettings:1034 — click_add_tip VISIBLE(0) only while empty
            out.add(Cell.EmptyHint)
        } else {
            // no "★" text row in the original: the ★ area is AppDragLayout right below
            // the 已添加 head; ★ exists only as the index-bar current marker (:326)
            for (ref in pins) {
                all.firstOrNull { it.packageName == ref.packageName && it.userId == ref.userId }
                    ?.let { out.add(Cell.Pin(it)) }
            }
        }

        if (recommend && recommendCount > 0) {
            val rec = if (recommendedApps.isNotEmpty()) {
                recommendedApps.filter { "${it.packageName}#${it.userId}" !in pinnedKeys }.take(recommendCount)
            } else {
                all.filter { it.usageCount > 0 && "${it.packageName}#${it.userId}" !in pinnedKeys }
                    .sortedByDescending { it.usageCount }
                    .take(recommendCount)
            }
            if (rec.isNotEmpty()) {
                out.add(Cell.Label("推荐"))
                rec.forEach { out.addApp(it, "推荐") }
            }
        }

        val groups = LinkedHashMap<String, MutableList<BubbleApp>>()
        for (app in all) {
            if ("${app.packageName}#${app.userId}" in pinnedKeys) continue
            groups.getOrPut(AppSortKey.groupOf(app.sortKey)) { ArrayList() }.add(app)
        }
        for (k in groups.keys.sortedWith(AppSortKey.SECTION_LETTER)) {
            out.add(Cell.Label(k))
            groups.getValue(k).sortedWith(AppSortKey.DEFAULT).forEach { out.addApp(it, k) }
        }
        return out.toImmutableList()
    }

    /** Group labels present (for the index bar), in display order. */
    fun labelsOf(cells: List<Cell>): List<String> = cells.filterIsInstance<Cell.Label>().map { it.text }
}
