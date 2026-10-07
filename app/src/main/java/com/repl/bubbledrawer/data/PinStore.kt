package com.repl.bubbledrawer.data

import com.repl.bubbledrawer.pinyin.BubbleApp

/**
 * Reference to a pinned slot (original `LauncherAppPersistent`, C2762j.java:407-432,
 * minus recentStartTime which we keep on the resolved app).
 */
data class PinnedRef(val packageName: String, val userId: Int = 0)

/**
 * Storage abstraction. Original keeps the ordered config string in
 * `Settings.Global "long_press_app"` (C2762j.m8871K :—) which a normal app cannot
 * write; per spec §7 we default to app-private prefs and keep the Global backend
 * for phase 2 (root/shizuku).
 *
 * Encoding follows the original: `"pkg#user;pkg#user;..."` order = bubble order
 * (the `C2762j.h` comparator sorts by indexOf of this string).
 */
interface PinBackend {
    fun read(): String
    fun write(value: String)
}

class PrefsPinBackend(private val prefs: android.content.SharedPreferences) : PinBackend {
    override fun read(): String = prefs.getString(KEY, "").orEmpty()
    override fun write(value: String) = prefs.edit().putString(KEY, value).apply()
    companion object { const val KEY = "long_press_app" } // same key name as the original Global setting
}

object PinCodec {
    const val MAX_PINS = 6 // m9522W min(6, …) — bubble bar capacity

    fun decode(raw: String): List<PinnedRef> =
        raw.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull {
                val parts = it.split('#')
                val pkg = parts[0]
                val user = parts.getOrNull(1)?.toIntOrNull() ?: 0
                if (pkg.isEmpty()) null else PinnedRef(pkg, user)
            }

    fun encode(list: List<PinnedRef>): String =
        list.joinToString(";") { "${it.packageName}#${it.userId}" }
}

class PinStore(private val backend: PinBackend) {
    var onPinsChanged: ((List<PinnedRef>) -> Unit)? = null

    fun pins(): List<PinnedRef> = PinCodec.decode(backend.read())

    fun setPins(list: List<PinnedRef>) {
        backend.write(PinCodec.encode(list.take(PinCodec.MAX_PINS)))
        onPinsChanged?.invoke(pins())
    }

    fun toggle(app: BubbleApp): Boolean {
        val cur = pins().toMutableList()
        val ref = PinnedRef(app.packageName, app.userId)
        return if (cur.remove(ref)) {
            setPins(cur); false
        } else {
            if (cur.size >= PinCodec.MAX_PINS) return false // caller toasts 最多固定6个
            cur.add(ref); setPins(cur); true
        }
    }

    fun move(from: Int, to: Int) {
        val cur = pins().toMutableList()
        if (from !in cur.indices || to !in cur.indices) return
        cur.add(to, cur.removeAt(from))
        setPins(cur)
    }

    /** Original "recent used floats up" — on launch, pinned items bump in the bar
     *  (C2795h DynamicListener writes recentStartTime; ordering keeps config string). */
    fun touchOnLaunch(app: BubbleApp) { /* order = config string (original semantics); no-op */ }
}
