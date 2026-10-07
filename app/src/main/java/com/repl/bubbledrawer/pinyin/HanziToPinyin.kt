package com.repl.bubbledrawer.pinyin

import java.text.Collator
import java.util.Locale

/**
 * Verbatim port of decompiled `com.flyme.systemuitools.common.utils.C2282f`
 * (log tag "HanziToPinyin", [C2282f.java:78]).
 *
 * Table [PinyinTables] is a script transcription of the original `f7593b`
 * (407 collation boundary hanzi, ascending under [Collator] Locale.CHINA)
 * and `f7594c` (representative full pinyin per boundary).
 *
 * Behaviour kept identical to the original:
 *  - [convert] returns per-character segments; ASCII (< 256) is kept as-is with
 *    type 1, Chinese is mapped to full pinyin with type 2, anything below 阿 /
 *    above boundary max, or with an empty pinyin, is type 3.
 *  - [pinyin] (original `m7035e`) uppercases and concatenates every segment.
 *
 * Grouping into A–Z uses the first character of that uppercase string, which is
 * exactly how the original sort key is consumed by `AbstractC7096c.m25049m()`.
 */
internal object HanziToPinyin {

    private const val TYPE_ASCII = 1
    private const val TYPE_PINYIN = 2
    private const val TYPE_OTHER = 3

    /** Original boundary sentinel "蓙" (C2282f.java:112). */
    private const val MAX_BOUNDARY = '蓙'

    /** Original lower sentinel "阿" (C2282f.java:101). */
    private const val MIN_BOUNDARY = '阿'

    /**
     * Comparison strategy. Production path = java.text.Collator(Locale.CHINA),
     * identical to the original (Android's implementation is ICU/CLDR-backed →
     * pinyin order). Desktop JDK ships only code-point fallback rules for zh, so
     * unit tests inject an ICU-backed [Compare] via [collatorOverride].
     */
    fun interface Compare { fun compare(a: String, b: String): Int }

    @Volatile
    var collatorOverride: Compare? = null

    private val defaultCollator: Compare? by lazy {
        Collator.getAvailableLocales()
            .firstOrNull {
                it == Locale.CHINA || it == Locale.CHINESE || it == Locale.TAIWAN ||
                    it.toString() == "zh_HANS_CN"
            }
            ?.let { loc ->
                val c = Collator.getInstance(loc)
                Compare { a, b -> c.compare(a, b) }
            }
    }

    private val active: Compare? get() = collatorOverride ?: defaultCollator

    /** True when a Chinese collator is available (original field `f7597a`). */
    val enabled: Boolean get() = active != null

    data class Token(val type: Int, val source: String, val target: String)

    /** Original `m7033d(char)`. */
    private fun tokenFor(c: Char, col: Compare): Token {
        val s = c.toString()
        if (c.code < 256) return Token(TYPE_ASCII, s, s)
        val cmpMin = col.compare(s, MIN_BOUNDARY.toString())
        if (cmpMin < 0) return Token(TYPE_OTHER, s, s)

        var index: Int
        when {
            cmpMin == 0 -> index = 0
            else -> {
                val cmpMax = col.compare(s, MAX_BOUNDARY.toString())
                if (cmpMax > 0) return Token(TYPE_OTHER, s, s)
                index = if (cmpMax == 0) PinyinTables.BOUNDARIES.size - 1 else -1
            }
        }

        var cmp = 0
        if (index < 0) {
            // original binary search (C2282f.java:127-141)
            var lo = 0
            var hi = PinyinTables.BOUNDARIES.size - 1
            while (lo <= hi) {
                index = (lo + hi) / 2
                cmp = col.compare(s, PinyinTables.BOUNDARIES[index].toString())
                if (cmp == 0) break
                if (cmp > 0) lo = index + 1 else hi = index - 1
            }
        }
        if (cmp < 0) index--

        val pinyin = PinyinTables.PINYINS.getOrElse(index) { "" }
        return if (pinyin.isEmpty()) Token(TYPE_OTHER, s, s) else Token(TYPE_PINYIN, s, pinyin)
    }

    /** Original `m7034b(String)`. */
    fun convert(text: String?): List<Token> {
        val out = ArrayList<Token>()
        val col = active ?: return out
        if (text.isNullOrEmpty()) return out
        val str = text
        val sb = StringBuilder()
        var type = TYPE_ASCII
        for (i in 0 until str.length) {
            val c = str[i]
            if (c == ' ') {
                if (sb.isNotEmpty()) flush(sb, out, type)
            } else if (c.code < 256) {
                if (type != TYPE_ASCII && sb.isNotEmpty()) flush(sb, out, type)
                sb.append(c)
                type = TYPE_ASCII
            } else {
                val t = tokenFor(c, col)
                if (t.type == TYPE_PINYIN) {
                    if (sb.isNotEmpty()) flush(sb, out, type)
                    out.add(t)
                    type = TYPE_PINYIN
                } else {
                    if (type != t.type && sb.isNotEmpty()) flush(sb, out, type)
                    type = t.type
                    sb.append(c)
                }
            }
        }
        if (sb.isNotEmpty()) flush(sb, out, type)
        return out
    }

    private fun flush(sb: StringBuilder, out: ArrayList<Token>, type: Int) {
        val s = sb.toString()
        out.add(Token(type, s, s))
        sb.setLength(0)
    }

    /** Original `m7035e(String)`: uppercase concatenation of all targets. */
    fun pinyin(text: String?): String {
        val sb = StringBuilder()
        for (t in convert(text)) sb.append(t.target.uppercase())
        return sb.toString()
    }

    /**
     * Sort key for a launcher label. Falls back to the raw label when the
     * collator is unavailable, matching how [com.repl.bubbledrawer.pinyin.AppSortKey]
     * treats untranslatable names.
     */
    fun sortKey(label: String): String {
        val key = pinyin(label)
        return if (key.isEmpty()) label.trim().ifEmpty { label } else key
    }
}
