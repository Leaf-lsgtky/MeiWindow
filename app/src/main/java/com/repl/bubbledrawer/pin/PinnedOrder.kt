package com.repl.bubbledrawer.pin

import android.content.Context
import android.content.SharedPreferences
import com.repl.bubbledrawer.data.PinBackend
import com.repl.bubbledrawer.data.PrefsKeyBackend
import com.repl.bubbledrawer.data.PinnedRef
import com.repl.bubbledrawer.xposed.SettingsStore

/**
 * 收藏区里一个格子的身份。
 *
 * 应用收藏和功能收藏仍各存各的（`long_press_app` / `pinned_functions`），这里只额外记录
 * **两者混在一起时的先后顺序**。会员关系不在这里：布局里多出来的键会被忽略，少了的新收藏
 * 会补在末尾，所以升级/卸载/同步丢一笔都不会让格子消失。
 */
sealed interface PinnedKey {
    data class App(val packageName: String, val userId: Int = 0) : PinnedKey {
        val ref: PinnedRef get() = PinnedRef(packageName, userId)
    }

    data class Function(val pinKey: String) : PinnedKey
}

/**
 * 混合收藏顺序的编码（纯逻辑，单测直接跑）。
 *
 * 格式：`app:包名#用户id;fn:功能pinKey;…`。`;` 是分隔符，功能 id 里的 `;` 已由
 * [com.repl.bubbledrawer.shortcut.ShortcutItem.pinKey] 替换掉；包名本身不可能含 `;`。
 */
object PinnedOrderCodec {

    private const val APP_PREFIX = "app:"
    private const val FUNCTION_PREFIX = "fn:"
    private const val MAX_KEY_LEN = 512
    private const val MAX_RAW_LEN = 32_000
    private const val MAX_ENTRIES = 512

    fun encode(items: List<PinnedKey>): String {
        val out = StringBuilder()
        val seen = HashSet<String>()
        for (item in items.take(MAX_ENTRIES)) {
            val token = tokenOf(item) ?: continue
            if (token.length > MAX_KEY_LEN || !seen.add(token)) continue
            val extra = if (out.isEmpty()) token.length else token.length + 1
            if (out.length + extra > MAX_RAW_LEN) break
            if (out.isNotEmpty()) out.append(';')
            out.append(token)
        }
        return out.toString()
    }

    fun decode(raw: String?): List<PinnedKey> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<PinnedKey>()
        val seen = HashSet<String>()
        for (entry in text.split(';')) {
            val token = entry.trim()
            if (token.isEmpty() || token.length > MAX_KEY_LEN || !seen.add(token)) continue
            parse(token)?.let { out.add(it) }
            if (out.size >= MAX_ENTRIES) break
        }
        return out
    }

    /** 交接载荷的语法检查：每个非空项都必须是完整 token，长度/条数不超过上限。 */
    fun isPlausible(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return true
        if (text.length > MAX_RAW_LEN) return false
        val entries = text.split(';')
        if (entries.size > MAX_ENTRIES) return false
        return entries.all { entry ->
            val token = entry.trim()
            token.isNotEmpty() && token.length <= MAX_KEY_LEN && parse(token) != null
        }
    }

    private fun tokenOf(item: PinnedKey): String? = when (item) {
        is PinnedKey.App -> {
            if (item.packageName.isBlank() || ';' in item.packageName || '#' in item.packageName) {
                null
            } else {
                "$APP_PREFIX${item.packageName}#${item.userId}"
            }
        }
        is PinnedKey.Function -> {
            val key = item.pinKey.trim()
            if (key.isEmpty() || ';' in key) null else "$FUNCTION_PREFIX$key"
        }
    }

    private fun parse(token: String): PinnedKey? = when {
        token.startsWith(APP_PREFIX) -> {
            val parts = token.removePrefix(APP_PREFIX).split('#')
            if (parts.size != 2 || parts[0].isEmpty()) {
                null
            } else {
                parts[1].toIntOrNull()?.let { PinnedKey.App(parts[0], it) }
            }
        }
        token.startsWith(FUNCTION_PREFIX) -> {
            val key = token.removePrefix(FUNCTION_PREFIX)
            if (key.isEmpty()) null else PinnedKey.Function(key)
        }
        else -> null
    }
}

/**
 * 「应用 + 功能」混合收藏顺序的跨进程存储。
 *
 * 它只负责顺序；收藏成员仍由 [com.repl.bubbledrawer.data.PinStore] 和
 * [com.repl.bubbledrawer.shortcut.FunctionPinStore] 管。写入走 [SettingsStore] 的同一条
 * 交接路径（本地镜像 + 远程组 + SystemUI 广播），所以面板里拖出来的顺序设置页也能看到。
 */
object PinnedOrderStore {

    const val KEY_PINNED_ORDER = "pinned_order"
    const val PREFS_KEY_REV = "pinned_order_rev"

    fun backend(prefs: SharedPreferences): PinBackend =
        PrefsKeyBackend(prefs, KEY_PINNED_ORDER, PREFS_KEY_REV)

    fun get(context: Context): List<PinnedKey> =
        PinnedOrderCodec.decode(SettingsStore.pinnedOrderBackend(context).read())

    fun set(context: Context, order: List<PinnedKey>) {
        SettingsStore.pinnedOrderBackend(context).write(PinnedOrderCodec.encode(order))
    }
}
