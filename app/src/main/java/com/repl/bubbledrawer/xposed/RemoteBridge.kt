package com.repl.bubbledrawer.xposed

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-process side of the config bridge — same service-102 plumbing as the
 * reference FrameworkConnectionRepository (:30-44 register listener, :202-225
 * probe apiVersion/PROP_CAP_REMOTE, :215 getRemotePreferences(GROUP)), reduced
 * to what a settings UI needs.
 *
 * Design: the LOCAL file "cfg" is the settings page's always-available storage
 * (works even before LSPosed connects). On first bind we MIRROR it into the
 * remote group exactly once — a plain `putString(KEY_LOCAL_MIRROR, ts)` snapshot
 * (no content-hash trap; a same-value rewrite is harmless and cheap). After that
 * every local toggle pushes straight through to the remote group, so the live
 * SystemUI monitor sees it in real time.
 */
object RemoteBridge {

    /** Our own local settings store (the settings UI reads/writes this). */
    fun local(context: Context): SharedPreferences =
        context.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    @Volatile
    var remote: SharedPreferences? = null
        private set

    @Volatile
    var frameworkLabel: String? = null
        private set

    /** true once the remote group is connected (settings page shows the state). */
    val connected: Boolean get() = remote != null

    private val started = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "bubble-bridge") }

    private const val KEY_LOCAL_MIRROR = "cfg_mirror_ts"

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                worker.execute { handleBind(service, app) }
            }

            override fun onServiceDied(service: XposedService) {
                worker.execute { if (boundService === service) { remote = null; boundService = null } }
            }
        })
    }

    @Volatile
    private var boundService: XposedService? = null

    private fun handleBind(service: XposedService, context: Context) {
        try {
            if (service.apiVersion < XposedService.API_102) return
            if (service.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L) return
            val prefs = service.getRemotePreferences(RemotePrefs.GROUP)
            boundService = service
            remote = prefs
            frameworkLabel = runCatching { "${service.frameworkName} ${service.frameworkVersion}" }.getOrNull()
            Log.i("BubbleDrawer", "BRIDGE_CONNECTED " + (frameworkLabel ?: ""))
            migrateLegacyPins(context, prefs)
            syncLocal(context) // always re-push local truth; keeps remote consistent after reboot
        } catch (exception: RuntimeException) {
            Log.w("BubbleDrawer", "BRIDGE_BIND_FAILED", exception)
        }
    }

    /** One-time move of the old app-private "pins" file into the shared group. */
    private fun migrateLegacyPins(context: Context, prefs: SharedPreferences) {
        val key = com.repl.bubbledrawer.data.PrefsPinBackend.KEY
        if (prefs.contains(key)) return
        val legacy = context.getSharedPreferences("pins", Context.MODE_PRIVATE)
            .getString(key, "").orEmpty()
        if (legacy.isNotEmpty()) prefs.edit().putString(key, legacy).apply()
    }

    /** Push ALL local settings keys into the remote group (local file uses the
     *  same key names as RemotePrefs — see SettingsStore). */
    fun syncLocal(context: Context) {
        val target = remote ?: return
        val snap = RemotePrefs.read(local(context))
        target.edit()
            .putBoolean(RemotePrefs.KEY_ENABLED, snap.enabled)
            .putBoolean(RemotePrefs.KEY_LEFT, snap.left)
            .putBoolean(RemotePrefs.KEY_RIGHT, snap.right)
            .putInt(RemotePrefs.KEY_RANGE_DP, snap.rangeDp)
            .putBoolean(RemotePrefs.KEY_FREEFORM, snap.freeform)
            .putInt(RemotePrefs.KEY_PANEL_W_PCT, snap.panelWidthPct)
            .putInt(RemotePrefs.KEY_PANEL_H_PCT, snap.panelHeightPct)
            .putInt(RemotePrefs.KEY_PANEL_ICON_DP, snap.panelIconDp)
            .putInt(RemotePrefs.KEY_PANEL_TEXT_SP, snap.panelTextSp)
            .commit() // synchronous: LSPosed mirrors the group only after the write lands
        Log.i(
            "BubbleDrawer",
            "BRIDGE_PUSHED enabled=" + snap.enabled + " left=" + snap.left +
                " right=" + snap.right + " range=" + snap.rangeDp,
        )
        if (!target.contains(com.repl.bubbledrawer.data.PrefsPinBackend.KEY)) {
            migrateLegacyPins(context, target)
        }
    }
}
