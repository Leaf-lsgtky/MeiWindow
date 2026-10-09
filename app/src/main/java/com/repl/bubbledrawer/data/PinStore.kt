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

    /**
     * Freshness stamp of [read]'s value — epoch millis of the write that produced it,
     * 0 when the backend never stamped one (legacy data).
     *
     * WHY IT EXISTS: the pins live in TWO stores that both used to prefer their own copy —
     * the app's `cfg` mirror and SystemUI's `bubbledrawer_pins` — and were kept together by
     * one broadcast in each direction. A single lost handoff (module app force-stopped by
     * HyperOS, receiver not yet registered after a reload, …) left the two copies different
     * FOREVER: neither side ever looked at the other's value again, so the app's 收藏 and the
     * fan panel's 收藏 disagreed with no way back. With a stamp, the older copy is simply
     * replaced by the newer one (see [PinsSync.decide]).
     */
    fun readRev(): Long = 0L

    fun writeRev(rev: Long) {}
}

class PrefsPinBackend(private val prefs: android.content.SharedPreferences) : PinBackend {
    override fun read(): String = prefs.getString(KEY, "").orEmpty()
    override fun write(value: String) {
        prefs.edit().putString(KEY, value).commit()
    }

    override fun readRev(): Long = prefs.getLong(KEY_REV, 0L)
    override fun writeRev(rev: Long) {
        prefs.edit().putLong(KEY_REV, rev).commit()
    }

    companion object {
        const val KEY = "long_press_app" // same key name as the original Global setting
        const val KEY_REV = "long_press_app_rev"
    }
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
        // no storage cap — original writes the full ordered list; the fan truncates
        // to the first 6 at display time (AppLauncherWindow.m9235C :448-466)
        backend.write(PinCodec.encode(list))
        onPinsChanged?.invoke(pins())
    }

    /**
     * ORIGINAL: the settings page writes ANY number of pinned apps to
     * `long_press_app` (no cap at insert time); the cap is applied only when the
     * fan is built — `AppLauncherWindow.m9235C` takes the FIRST 6 + "更多" tile
     * (:448-466 `i6>=6 break`). So toggling here must NOT reject on count.
     */
    fun toggle(app: BubbleApp): Boolean {
        val cur = pins().toMutableList()
        val ref = PinnedRef(app.packageName, app.userId)
        return if (cur.remove(ref)) {
            setPins(cur); false
        } else {
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
