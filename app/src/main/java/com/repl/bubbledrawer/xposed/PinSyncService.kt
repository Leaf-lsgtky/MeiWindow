package com.repl.bubbledrawer.xposed

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.repl.bubbledrawer.data.PinCodec

/**
 * Persistence handoff for pins edited INSIDE the SystemUI overlay panel (方案 B).
 *
 * The hook process cannot write the module's config: its `getRemotePreferences` view is
 * read-only ("UnsupportedOperationException: Read only implementation", verified on
 * device) and `XposedServiceHelper` only delivers to the module's own app process. So
 * FanHandoff: FanHost.startService(this) → the pin order arrives here (system uid) and is
 * written through the ordinary app-side backend (`SettingsStore.pinBackend`: local mirror
 * + LSPosed remote group), the same path the manage Activity uses. LSPosed then mirrors
 * the group back into SystemUI, closing the loop.
 *
 * Security: exported (system uid must be able to reach it across apps) but the caller is
 * verified — only android (uid 1000, SystemUI) is accepted, and the payload is validated
 * by [PinCodec.decode] (max sane length, strict "pkg#user;" grammar) before it lands.
 */
class PinSyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callerUid = Binder.getCallingUid()
        if (!isSystemUiCaller(callerUid)) {
            Log.w(TAG, "PIN_SYNC_REJECTED uid=$callerUid (only SystemUI/android allowed)")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val raw = intent?.getStringExtra(EXTRA_PINS)
        if (raw == null || !isPlausiblePinsValue(raw)) {
            Log.w(TAG, "PIN_SYNC_REJECTED bad payload len=${raw?.length ?: -1}")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val rev = intent.getLongExtra(EXTRA_REV, 0L)
        if (rev > 0L) {
            SettingsStore.applyPushedPins(applicationContext, raw, rev)
        } else {
            SettingsStore.pinBackend(applicationContext).write(raw)
        }
        Log.i(
            TAG,
            "PIN_SYNC_APPLIED pins=${PinCodec.decode(raw).size} len=${raw.length} rev=$rev",
        )
        stopSelf(startId)
        return START_NOT_STICKY
    }

    /**
     * The old check was `callerUid == Process.SYSTEM_UID` (1000) — but on HyperOS 17 this
     * ROM's SystemUI runs as an ordinary app uid (u0_a231 / 10231, verified with
     * `ps -A -o USER,PID,NAME` and `packages.list`), so every handoff through this service was
     * rejected as "uid=10231 (only android allowed)". Resolve the caller's packages instead of
     * trusting a hard-coded uid, and keep accepting real uid 1000 for other ROMs.
     */
    private fun isSystemUiCaller(callerUid: Int): Boolean {
        if (callerUid == Process.SYSTEM_UID || callerUid == Process.myUid()) return true
        val packages = runCatching { packageManager.getPackagesForUid(callerUid) }.getOrNull()
        return packages?.contains(SYSTEMUI_PACKAGE) == true
    }

    companion object {
        const val TAG = "BubbleDrawer"
        const val ACTION = "com.repl.bubbledrawer.action.SYNC_PINS"
        const val EXTRA_PINS = "pins"

        /** Freshness stamp travelling with [EXTRA_PINS] (see PinsSync). 0 = older sender. */
        const val EXTRA_REV = "pins_rev"

        const val SYSTEMUI_PACKAGE = "com.android.systemui"

        /** "pkg#user;…" for ~100 pins is < 5 KB; 10 KB is a generous cap. */
        private const val MAX_VALUE_LEN = 10_000

        private fun isPlausiblePinsValue(raw: String): Boolean =
            PinSyncReceiver.isPlausiblePinsValue(raw)
    }
}
