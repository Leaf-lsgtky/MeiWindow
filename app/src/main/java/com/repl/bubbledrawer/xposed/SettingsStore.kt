package com.repl.bubbledrawer.xposed

import android.content.Context
import com.repl.bubbledrawer.data.PinBackend
import com.repl.bubbledrawer.data.PinCodec
import com.repl.bubbledrawer.data.PinnedRef
import com.repl.bubbledrawer.data.PrefsPinBackend

/**
 * Single facade over the settings values for the APP process. The local "cfg"
 * file is the always-available mirror (the page works before LSPosed connects);
 * every mutation is written through to the REMOTE group immediately when bound,
 * so the live SystemUI monitor reacts in real time (RemoteBridge.syncLocal
 * re-pushes everything on first bind).
 */
object SettingsStore {

    /** trigger_pos semantics (kept from the old page): 0 both, 1 left, 2 right. */
    const val POS_BOTH = 0
    const val POS_LEFT = 1
    const val POS_RIGHT = 2

    fun snapshot(context: Context): RemotePrefs.Snapshot =
        RemotePrefs.read(RemoteBridge.remote ?: fallbackMirror(context))

    /** Offline mirror file (used when the framework is not connected). */
    private fun fallbackMirror(context: Context) = RemoteBridge.local(context)

    fun triggerPos(context: Context): Int =
        RemoteBridge.local(context).getInt("trigger_pos", POS_BOTH)

    fun setEnabled(context: Context, on: Boolean) {
        RemoteBridge.local(context).edit().putBoolean(RemotePrefs.KEY_ENABLED, on).apply()
        RemoteBridge.remote?.edit()?.putBoolean(RemotePrefs.KEY_ENABLED, on)?.apply()
    }

    fun setTriggerPos(context: Context, pos: Int) {
        val p = pos.coerceIn(POS_BOTH, POS_RIGHT)
        RemoteBridge.local(context).edit()
            .putInt("trigger_pos", p)
            .putBoolean(RemotePrefs.KEY_LEFT, p != POS_RIGHT)
            .putBoolean(RemotePrefs.KEY_RIGHT, p != POS_LEFT)
            .apply()
        RemoteBridge.remote?.edit()
            ?.putBoolean(RemotePrefs.KEY_LEFT, p != POS_RIGHT)
            ?.putBoolean(RemotePrefs.KEY_RIGHT, p != POS_LEFT)
            ?.apply()
    }

    fun setRangeDp(context: Context, dp: Int) {
        val v = dp.coerceIn(RemotePrefs.MIN_DP, RemotePrefs.MAX_DP)
        RemoteBridge.local(context).edit().putInt(RemotePrefs.KEY_RANGE_DP, v).apply()
        RemoteBridge.remote?.edit()?.putInt(RemotePrefs.KEY_RANGE_DP, v)?.apply()
    }

    /** Trigger region axis along the bottom edge (dp). */
    fun setBottomDp(context: Context, dp: Int) {
        val v = dp.coerceIn(RemotePrefs.MIN_DP, RemotePrefs.MAX_DP)
        RemoteBridge.local(context).edit().putInt(RemotePrefs.KEY_BOTTOM_DP, v).apply()
        RemoteBridge.remote?.edit()?.putInt(RemotePrefs.KEY_BOTTOM_DP, v)?.apply()
    }

    /** Trigger region axis up the side edge (dp). */
    fun setEdgeDp(context: Context, dp: Int) {
        val v = dp.coerceIn(RemotePrefs.MIN_DP, RemotePrefs.MAX_DP)
        RemoteBridge.local(context).edit().putInt(RemotePrefs.KEY_EDGE_DP, v).apply()
        RemoteBridge.remote?.edit()?.putInt(RemotePrefs.KEY_EDGE_DP, v)?.apply()
    }

    /** 更多面板宽/高（占屏幕百分比；0 = 默认 62 %，面板始终居中）。 */
    fun setPanelWidthPct(context: Context, pct: Int) = putInt(context, RemotePrefs.KEY_PANEL_W_PCT, pct)
    fun setPanelHeightPct(context: Context, pct: Int) = putInt(context, RemotePrefs.KEY_PANEL_H_PCT, pct)
    /** 更多面板图标 dp / 文字 sp（0 = 布局默认）。 */
    fun setPanelIconDp(context: Context, dp: Int) = putInt(context, RemotePrefs.KEY_PANEL_ICON_DP, dp)
    fun setPanelTextSp(context: Context, sp: Int) = putInt(context, RemotePrefs.KEY_PANEL_TEXT_SP, sp)

    private fun putInt(context: Context, key: String, value: Int) {
        RemoteBridge.local(context).edit().putInt(key, value).apply()
        RemoteBridge.remote?.edit()?.putInt(key, value)?.apply()
    }

    fun setFreeform(context: Context, on: Boolean) {
        RemoteBridge.local(context).edit().putBoolean(RemotePrefs.KEY_FREEFORM, on).apply()
        RemoteBridge.remote?.edit()?.putBoolean(RemotePrefs.KEY_FREEFORM, on)?.apply()
    }

    /** Pin backend: remote file when connected, local mirror otherwise; writes
     *  go to BOTH so the offline cache never diverges (syncLocal re-pushes). */
    fun pinBackend(context: Context): PinBackend = object : PinBackend {
        override fun read(): String {
            val r = RemoteBridge.remote
            if (r != null) return PrefsPinBackend(r).read()
            return PrefsPinBackend(fallbackMirror(context)).read()
        }

        override fun write(value: String) {
            PrefsPinBackend(fallbackMirror(context)).write(value)
            RemoteBridge.remote?.let { PrefsPinBackend(it).write(value) }
        }
    }

    /** Encode/decode passthroughs keep the call sites honest about the format. */
    fun encodePins(list: List<PinnedRef>): String = PinCodec.encode(list)
}
