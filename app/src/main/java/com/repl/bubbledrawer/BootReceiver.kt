package com.repl.bubbledrawer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Re-arm the overlay after reboot when the user left the switch on
 * (the original survives via `persistent=true` in a systemui-shared process;
 * ours restarts the foreground service on BOOT_COMPLETED only if the stored
 * user preference and overlay permission both allow).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val wanted = context.getSharedPreferences("runtime", Context.MODE_PRIVATE)
            .getBoolean("user_wants", false)
        if (!wanted) return
        if (!Settings.canDrawOverlays(context)) return
        LauncherService.start(context)
    }
}
