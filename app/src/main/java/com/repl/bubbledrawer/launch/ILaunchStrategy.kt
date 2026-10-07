package com.repl.bubbledrawer.launch

import android.content.Context
import android.content.Intent
import com.repl.bubbledrawer.pinyin.BubbleApp

/**
 * The ONLY seam that talks to the window system for "how to open".
 *
 * Flyme original (`AppLauncherWindow.java:792-814`): ActivityOptions bundle with
 * private keys `start_windowmode` (true / 512) and `virtual_mode` (1035) then
 * startActivityAsUser — requires the flyme framework, unportable.
 * This replica opens fullscreen; a future freeform strategy only replaces this file.
 */
fun interface ILaunchStrategy {
    fun launch(context: Context, app: BubbleApp): Boolean
}

class FullscreenLaunchStrategy : ILaunchStrategy {
    override fun launch(context: Context, app: BubbleApp): Boolean = try {
        val i = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            } ?: return false
        context.startActivity(i)
        true
    } catch (_: Exception) {
        false
    }
}
