package com.repl.bubbledrawer.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
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
        if (!isFromSystemUi(this)) {
            // Was not verified at all before 2026-10-09 (any app could rewrite the pins);
            // the payload is harmless but the list is the user's, so check the sender.
            Log.w(TAG, "PIN_SYNC_BROADCAST_REJECTED sender=${sentFromUidOrNull()}")
            return
        }
        val raw = intent.getStringExtra(EXTRA_PINS) ?: return
        if (!isPlausiblePinsValue(raw)) {
            Log.w(TAG, "PIN_SYNC_BROADCAST_REJECTED bad payload len=${raw.length}")
            return
        }

        val appContext = context.applicationContext
        val rev = intent.getLongExtra(EXTRA_REV, 0L)
        if (rev > 0L) {
            // Keep the SENDER's stamp: this is the panel's edit, not a new one (see
            // SettingsStore.applyPushedPins).
            SettingsStore.applyPushedPins(appContext, raw, rev)
        } else {
            SettingsStore.pinBackend(appContext).write(raw)
        }
        Log.i(
            TAG,
            "PIN_SYNC_BROADCAST_APPLIED pins=${PinCodec.decode(raw).size} len=${raw.length} rev=$rev",
        )
    }

    private fun sentFromUidOrNull(): Int? = runCatching { sentFromUid }.getOrNull()

    /** True only for the SystemUI-side sender (uid 1000 or the com.android.systemui package). */
    private fun isFromSystemUi(receiver: BroadcastReceiver): Boolean = runCatching {
        val uid = receiver.sentFromUid
        if (uid == Process.SYSTEM_UID || uid == Process.myUid()) return@runCatching true
        receiver.sentFromPackage == SYSTEMUI_PACKAGE
    }.getOrDefault(false)

    companion object {
        const val TAG = "BubbleDrawer"
        const val ACTION = "com.repl.bubbledrawer.action.SYNC_PINS"
        const val ACTION_TO_SYSTEMUI = "com.repl.bubbledrawer.action.SYNC_PINS_TO_SYSTEMUI"
        const val EXTRA_PINS = "pins"

        /**
         * Freshness stamp travelling with [EXTRA_PINS] — see [com.repl.bubbledrawer.data.PinsSync].
         * Absent/0 means an older module build sent it.
         */
        const val EXTRA_REV = "pins_rev"

        /** On this ROM SystemUI is NOT uid 1000 (verified: u0_a231, `packages.list`). */
        const val SYSTEMUI_PACKAGE = "com.android.systemui"

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
