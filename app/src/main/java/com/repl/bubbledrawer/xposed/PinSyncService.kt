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
        if (callerUid != Process.SYSTEM_UID) {
            Log.w(TAG, "PIN_SYNC_REJECTED uid=$callerUid (only android allowed)")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val raw = intent?.getStringExtra(EXTRA_PINS)
        if (raw == null || !isPlausiblePinsValue(raw)) {
            Log.w(TAG, "PIN_SYNC_REJECTED bad payload len=${raw?.length ?: -1}")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val backend = SettingsStore.pinBackend(applicationContext)
        backend.write(raw)
        Log.i(
            TAG,
            "PIN_SYNC_APPLIED pins=${PinCodec.decode(raw).size} len=${raw.length}",
        )
        stopSelf(startId)
        return START_NOT_STICKY
    }

    companion object {
        const val TAG = "BubbleDrawer"
        const val ACTION = "com.repl.bubbledrawer.action.SYNC_PINS"
        const val EXTRA_PINS = "pins"

        /** "pkg#user;…" for ~100 pins is < 5 KB; 10 KB is a generous cap. */
        private const val MAX_VALUE_LEN = 10_000

        /**
         * Grammar check for the encoded pin string: `pkg#user;pkg#user;…` (PinCodec
         * format). Empty = "no pins", which is legal (manage mode removes everything).
         */
        private fun isPlausiblePinsValue(raw: String): Boolean {
            if (raw.isEmpty()) return true
            if (raw.length > MAX_VALUE_LEN) return false
            return raw.split(';').all { entry ->
                entry.isNotEmpty() && entry.length <= 512 &&
                    entry.substringBefore('#').let { it.isNotEmpty() && it.none { c -> c == '#' || c == ';' } }
            }
        }
    }
}
