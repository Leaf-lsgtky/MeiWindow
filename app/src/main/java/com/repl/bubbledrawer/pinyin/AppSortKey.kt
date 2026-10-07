package com.repl.bubbledrawer.pinyin

import java.text.Collator
import java.util.Locale

/**
 * Sort keys / comparators for the pinned-app list.
 *
 * Mirrors decompiled classes:
 *  - `p146Y0.AbstractC7096c.m25049m()`  — precomputed uppercase pinyin sort key (field h).
 *  - `p146Y0.C7094a`                    — usage count desc, then sort key compareTo.
 *  - `windowmode.views.SlideLaunchAppSettings.C2874h` — section letter comparator:
 *    names whose first char is not a letter sink to the end, otherwise compareTo.
 */

/** One selectable launcher entry (superset of original `LauncherAppPersistent`, C2762j.java:407-432). */
data class BubbleApp(
    val packageName: String,
    val label: String,
    val userId: Int = 0,
    val shortcutId: String? = null,
    val recentStartTime: Long = 0L,
    val usageCount: Int = 0,
    /** Precomputed at construction time, like `AbstractC7096c` does in its ctor (:323-324). */
    val sortKey: String = AppSortKey.of(label),
)

object AppSortKey {

    /** `AbstractC7096c.m25056u()` (:282-284): recompute key from label. */
    fun of(label: String): String = HanziToPinyin.sortKey(label)

    /** Section letter for a sort key: uppercase first char; digits bucket "0"; rest "#". */
    fun groupOf(sortKey: String): String {
        val c = sortKey.firstOrNull() ?: return "#"
        return when {
            c in 'A'..'Z' || c in 'a'..'z' -> c.uppercaseChar().toString()
            c in '0'..'9' -> "0"
            else -> "#"
        }
    }

    private val chineseCollator: Collator? =
        Collator.getAvailableLocales().firstOrNull { it.language == "zh" }?.let { Collator.getInstance(it) }

    /**
     * `C7094a.m25030b(true)` semantics (:15-24): order by usage count desc,
     * then by the pinyin sort key ascending (plain string compareTo).
     */
    val DEFAULT: Comparator<BubbleApp> = Comparator { a, b ->
        if (b.usageCount != a.usageCount) b.usageCount - a.usageCount
        else a.sortKey.compareTo(b.sortKey)
    }

    /** `C2874h` (:456-469): non-letter first char goes last, else compareTo. */
    val SECTION_LETTER: Comparator<String> = Comparator { x, y ->
        val xa = x.firstOrNull()?.isLetter() == true
        val ya = y.firstOrNull()?.isLetter() == true
        when {
            !xa && !ya -> 0
            !xa -> 1
            !ya -> -1
            else -> x.compareTo(y)
        }
    }

    /** Section display order: "#" sinks to the end (C2874h rule applied to group labels). */
    fun compareSections(a: String, b: String): Int =
        SECTION_LETTER.compare(a, b)
}
