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
@androidx.compose.runtime.Immutable
data class BubbleApp(
    val packageName: String,
    val label: String,
    val userId: Int = 0,
    val shortcutId: String? = null,
    val recentStartTime: Long = 0L,
    val usageCount: Int = 0,
    /** Precomputed at construction time, like `AbstractC7096c` does in its ctor (:323-324). */
    val sortKey: String = AppSortKey.of(label),
    val initials: String = AppSortKey.initialsOf(label),
)

object AppSortKey {

    /** `AbstractC7096c.m25056u()` (:282-284): recompute key from label. */
    fun of(label: String): String = HanziToPinyin.sortKey(label)

    /** Initials of each word/character in uppercase, e.g. "WX" for "微信". */
    fun initialsOf(label: String): String =
        HanziToPinyin.convert(label)
            .mapNotNull { it.target.firstOrNull()?.uppercaseChar() }
            .joinToString("")

    /** Section letter for a sort key: uppercase first char; digits AND symbols bucket "#" (the
     * index bar's tail glyph — the old "0" bucket is folded in per UI refactor). */
    fun groupOf(sortKey: String): String {
        val c = sortKey.firstOrNull() ?: return "#"
        return when {
            c in 'A'..'Z' || c in 'a'..'z' -> c.uppercaseChar().toString()
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
        else {
            val s = a.sortKey.compareTo(b.sortKey)
            if (s != 0) s else a.userId.compareTo(b.userId)
        }
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
