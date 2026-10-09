package com.repl.bubbledrawer.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.repl.bubbledrawer.data.PinCodec

/**
 * Broadcast-based persistence handoff for pins edited inside SystemUI.
 *
 * Replaces or supplements PinSyncService: on modern Android (API 26+, and especially 34+ / HyperOS),
 * calling startService() across apps when the target app is in the background is strictly forbidden
 * (BackgroundServiceStartNotAllowedException). Explicit broadcasts to a registered BroadcastReceiver
 * succeed even when the target app process is idle or stopped.
 */
class PinSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val raw = intent.getStringExtra(EXTRA_PINS) ?: return
        if (!isPlausiblePinsValue(raw)) {
            Log.w(TAG, "PIN_SYNC_BROADCAST_REJECTED bad payload len=${raw.length}")
            return
        }

        val appContext = context.applicationContext
        val backend = SettingsStore.pinBackend(appContext)
        backend.write(raw)
        Log.i(
            TAG,
            "PIN_SYNC_BROADCAST_APPLIED pins=${PinCodec.decode(raw).size} len=${raw.length}",
        )
    }

    companion object {
        const val TAG = "BubbleDrawer"
        const val ACTION = "com.repl.bubbledrawer.action.SYNC_PINS"
        const val ACTION_TO_SYSTEMUI = "com.repl.bubbledrawer.action.SYNC_PINS_TO_SYSTEMUI"
        const val EXTRA_PINS = "pins"

        /** "pkg#user;…" for ~100 pins is < 5 KB; 10 KB is a generous cap. */
        private const val MAX_VALUE_LEN = 10_000

        /**
         * Grammar check for the encoded pin string: `pkg#user;pkg#user;…` (PinCodec
         * format). Empty = "no pins", which is legal (manage mode removes everything).
         */
        fun isPlausiblePinsValue(raw: String): Boolean {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return true
            if (trimmed.length > MAX_VALUE_LEN) return false
            return trimmed.split(';').filter { it.isNotBlank() }.all { entry ->
                entry.length <= 512 &&
                    entry.substringBefore('#').let { it.isNotEmpty() && it.none { c -> c == '#' || c == ';' } }
            }
        }
    }
}
