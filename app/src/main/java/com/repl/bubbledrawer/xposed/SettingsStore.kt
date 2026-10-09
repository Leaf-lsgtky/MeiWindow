package com.repl.bubbledrawer.xposed

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import com.repl.bubbledrawer.data.PinBackend
import com.repl.bubbledrawer.data.PinCodec
import com.repl.bubbledrawer.data.PinnedRef
import com.repl.bubbledrawer.data.PrefsPinBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/**
 * Immutable UI state for the settings page: one snapshot of the cross-process config
 * (docs/ui-guidelines.md "强跳过友好的状态形状" — @Immutable, scalar fields only, no
 * List-typed fields that would defeat skipping).
 */
@Immutable
data class SettingsUiState(
    val snapshot: RemotePrefs.Snapshot,
    /** 0 both / 1 left / 2 right — the old page's trigger_pos, kept for parity. */
    val triggerPos: Int,
    val connected: Boolean,
    val frameworkLabel: String?,
)

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
    /** 更多面板点击外部收起方式（0 = 单击，1 = 双击）。 */
    fun setPanelDismissOutside(context: Context, mode: Int) = putInt(context, RemotePrefs.KEY_PANEL_DISMISS_OUTSIDE, mode.coerceIn(0, 1))

    /** 扇形面板应用图标数量（5 或 6）。 */
    fun setFanIconCount(context: Context, count: Int) = putInt(context, RemotePrefs.KEY_FAN_ICON_COUNT, count.coerceIn(5, 6))

    /** 展开扇形半径（dp）。 */
    fun setFanRadiusDp(context: Context, dp: Int) = putInt(context, RemotePrefs.KEY_FAN_RADIUS_DP, dp.coerceIn(RemotePrefs.FAN_RADIUS_MIN, RemotePrefs.FAN_RADIUS_MAX))

    /** 扇形未摆满时用推荐应用填满开关。 */
    fun setFanAutoFillRecommend(context: Context, enabled: Boolean) {
        RemoteBridge.local(context).edit().putBoolean(RemotePrefs.KEY_FAN_AUTO_FILL_RECOMMEND, enabled).apply()
        RemoteBridge.remote?.edit()?.putBoolean(RemotePrefs.KEY_FAN_AUTO_FILL_RECOMMEND, enabled)?.apply()
    }

    /** 扇形重按翻页开关与灵敏度。 */
    fun setFanPressurePageTurn(context: Context, enabled: Boolean) {
        RemoteBridge.local(context).edit().putBoolean(RemotePrefs.KEY_FAN_PRESSURE_PAGE_TURN, enabled).apply()
        RemoteBridge.remote?.edit()?.putBoolean(RemotePrefs.KEY_FAN_PRESSURE_PAGE_TURN, enabled)?.apply()
    }

    fun setFanPressureSensitivity(context: Context, sensitivity: Int) =
        putInt(context, RemotePrefs.KEY_FAN_PRESSURE_SENSITIVITY, sensitivity.coerceIn(0, 2))

    /** 更多面板推荐应用开关与数量（4, 8, 12, 16, 20）。 */
    fun setRecommendEnabled(context: Context, enabled: Boolean) {
        RemoteBridge.local(context).edit().putBoolean(RemotePrefs.KEY_RECOMMEND_ENABLED, enabled).apply()
        RemoteBridge.remote?.edit()?.putBoolean(RemotePrefs.KEY_RECOMMEND_ENABLED, enabled)?.apply()
    }

    fun setRecommendCount(context: Context, count: Int) {
        val raw = count.coerceIn(RemotePrefs.RECOMMEND_COUNT_MIN, RemotePrefs.RECOMMEND_COUNT_MAX)
        val snapped = (((raw - RemotePrefs.RECOMMEND_COUNT_MIN + (RemotePrefs.RECOMMEND_COUNT_STEP / 2)) / RemotePrefs.RECOMMEND_COUNT_STEP) * RemotePrefs.RECOMMEND_COUNT_STEP + RemotePrefs.RECOMMEND_COUNT_MIN)
            .coerceIn(RemotePrefs.RECOMMEND_COUNT_MIN, RemotePrefs.RECOMMEND_COUNT_MAX)
        putInt(context, RemotePrefs.KEY_RECOMMEND_COUNT, snapped)
    }

    /** 实验性：Flyme 样式轻量小窗设置 */
    fun setFlymeFreeformEnabled(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_FLYME_FREEFORM_ENABLED, enabled)

    fun setFlymeFreeformCenter(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_FLYME_FREEFORM_CENTER, enabled)

    fun setFlymeFreeformScale(context: Context, scale: Int) =
        putInt(context, RemotePrefs.KEY_FLYME_FREEFORM_SCALE, scale.coerceIn(RemotePrefs.FLYME_FREEFORM_SCALE_MIN, RemotePrefs.FLYME_FREEFORM_SCALE_MAX))

    fun setFlymeFreeformOutsideDismiss(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_FLYME_FREEFORM_OUTSIDE_DISMISS, enabled)

    fun setFlymeFreeformOutsideDismissAction(context: Context, action: Int) =
        putInt(context, RemotePrefs.KEY_FLYME_FREEFORM_OUTSIDE_DISMISS_ACTION, action.coerceIn(0, 1))

    fun setFlymeFreeformSwipeUpMini(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_FLYME_FREEFORM_SWIPE_UP_MINI, enabled)

    fun setFlymeFreeformSwipeUpHoldFree(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_FLYME_FREEFORM_SWIPE_UP_HOLD_FREE, enabled)

    fun setFlymeFreeformSwipeDownFull(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_FLYME_FREEFORM_SWIPE_DOWN_FULL, enabled)

    fun setFlymeFreeformDimBg(context: Context, enabled: Boolean) =
        putBoolean(context, RemotePrefs.KEY_FLYME_FREEFORM_DIM_BG, enabled)

    private fun putBoolean(context: Context, key: String, value: Boolean) {
        RemoteBridge.local(context).edit().putBoolean(key, value).apply()
        RemoteBridge.remote?.edit()?.putBoolean(key, value)?.apply()
    }

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
            val localPins = fallbackMirror(context).getString(PrefsPinBackend.KEY, null)
            if (!localPins.isNullOrEmpty()) return localPins
            val r = RemoteBridge.remote
            if (r != null) {
                val remotePins = PrefsPinBackend(r).read()
                if (remotePins.isNotEmpty()) {
                    fallbackMirror(context).edit().putString(PrefsPinBackend.KEY, remotePins).commit()
                    return remotePins
                }
            }
            return ""
        }

        override fun write(value: String) {
            fallbackMirror(context).edit().putString(PrefsPinBackend.KEY, value).commit()
            val r = RemoteBridge.remote
            if (r != null) {
                r.edit().putString(PrefsPinBackend.KEY, value).commit()
            } else {
                RemoteBridge.start(context)
            }
            runCatching {
                val intent = android.content.Intent(PinSyncReceiver.ACTION_TO_SYSTEMUI)
                    .putExtra(PinSyncReceiver.EXTRA_PINS, value)
                context.sendBroadcast(intent)
            }
        }
    }

    /** Encode/decode passthroughs keep the call sites honest about the format. */
    fun encodePins(list: List<PinnedRef>): String = PinCodec.encode(list)

    /**
     * Reactive snapshot the Compose settings page collects
     * (docs/ui-guidelines.md "Flow 收集": screens use `collectAsStateWithLifecycle`, never
     * poll in recomposition — and the old page's `recreate()`-to-rerender hack dies here).
     *
     * Emits on: any write through [SettingsStore] (local file listener sees them all),
     * remote-group changes surfaced by LSPosed, and bridge bind/die transitions (the
     * reader switches between the remote group and the offline mirror).
     */
    fun observe(context: Context): Flow<SettingsUiState> = callbackFlow {
        val app = context.applicationContext
        val local = RemoteBridge.local(app)

        fun push() { trySend(currentState(app)) }

        val localListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> push() }
        local.registerOnSharedPreferenceChangeListener(localListener)

        // The remote proxy may appear/disappear at any time; (re)register while present.
        var remoteListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
        fun attachRemote() {
            val r = RemoteBridge.remote ?: return
            if (remoteListener != null) return
            val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> push() }
            remoteListener = l
            runCatching { r.registerOnSharedPreferenceChangeListener(l) }
        }
        fun detachRemote() {
            val l = remoteListener ?: return
            remoteListener = null
            runCatching { RemoteBridge.remote?.unregisterOnSharedPreferenceChangeListener(l) }
        }

        val connectionListener: (Boolean) -> Unit = { connected ->
            if (connected) attachRemote() else detachRemote()
            push()
        }
        RemoteBridge.addConnectionListener(connectionListener)
        attachRemote()

        push() // initial value without waiting for a first event
        awaitClose {
            RemoteBridge.removeConnectionListener(connectionListener)
            detachRemote()
            local.unregisterOnSharedPreferenceChangeListener(localListener)
        }
    }
        .buffer(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        .distinctUntilChanged()
        .flowOn(Dispatchers.Main)

    /** Current UI state: snapshot + bridge status in one @Immutable value. */
    fun currentState(context: Context): SettingsUiState = SettingsUiState(
        snapshot = snapshot(context),
        triggerPos = triggerPos(context),
        connected = RemoteBridge.connected,
        frameworkLabel = RemoteBridge.frameworkLabel,
    )
}
