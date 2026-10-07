package com.repl.bubbledrawer.pin

import com.repl.bubbledrawer.data.PinnedRef
import com.repl.bubbledrawer.pinyin.AppSortKey
import com.repl.bubbledrawer.pinyin.BubbleApp

/**
 * Flat cell model for the pin-manager grid.
 *
 * The original renders BOTH areas as 4-column grids:
 *  - ★ area: AppDragLayout → GridLayoutManager(context, 4) (AppDragLayout.java:185),
 *    one item per app, drag-sortable;
 *  - letter area: rows grouped 4-per-line via C2875i subList chunks (:561/:615) with
 *    section labels — identical visual result to one grid with span=1 cells and
 *    full-span label rows (LightWeightOpenSettings' SpanSizeLookup idiom :193-198).
 */
sealed interface Cell {
    data object PinHead : Cell                 // 已添加 + 长按拖动图标以排序 (app_settings_item_head)
    data object EmptyHint : Cell               // 暂无已添加应用 (app_settings_item_selected click_add_tip,
                                               // visibility VISIBLE(0) only while pins empty — :1034)
    data class Label(val text: String) : Cell  // ★/推荐/A..Z/# group header
    data class Pin(val app: BubbleApp) : Cell  // pinned slot, drag-reorderable
    data class App(val app: BubbleApp) : Cell  // regular grid cell
}

object Sections {

    const val RECOMMEND_TOP_N = 8

    fun build(
        all: List<BubbleApp>,
        pins: List<PinnedRef>,
        recommend: Boolean,
    ): List<Cell> {
        val pinnedPkgs = pins.map { it.packageName }.toSet()
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

        if (recommend) {
            val rec = all.filter { it.usageCount > 0 && it.packageName !in pinnedPkgs }
                .sortedByDescending { it.usageCount }
                .take(RECOMMEND_TOP_N)
            if (rec.isNotEmpty()) {
                out.add(Cell.Label("推荐"))
                rec.forEach { out.add(Cell.App(it)) }
            }
        }

        val groups = LinkedHashMap<String, MutableList<BubbleApp>>()
        for (app in all) {
            if (app.packageName in pinnedPkgs) continue
            groups.getOrPut(AppSortKey.groupOf(app.sortKey)) { ArrayList() }.add(app)
        }
        for (k in groups.keys.sortedWith(AppSortKey.SECTION_LETTER)) {
            out.add(Cell.Label(k))
            groups.getValue(k).sortedWith(AppSortKey.DEFAULT).forEach { out.add(Cell.App(it)) }
        }
        return out
    }

    /** Group labels present (for the index bar), in display order. */
    fun labelsOf(cells: List<Cell>): List<String> = cells.filterIsInstance<Cell.Label>().map { it.text }
}
