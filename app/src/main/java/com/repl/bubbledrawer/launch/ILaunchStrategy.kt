package com.repl.bubbledrawer.launch

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.UserHandle
import com.repl.bubbledrawer.data.MultiUserHelper
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
        val userHandle = MultiUserHelper.getUserHandle(app.userId)
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val act = if (userHandle != null && launcherApps != null) {
            runCatching { launcherApps.getActivityList(app.packageName, userHandle).firstOrNull() }.getOrNull()
        } else null

        val comp = act?.componentName ?: MultiUserHelper.findComponentForPackage(context, app.packageName, app.userId)

        val i = (if (comp != null) {
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = comp
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(app.packageName)
        })?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        } ?: return false

        if (app.userId != 0 && userHandle != null) {
            val asUserOk = runCatching {
                val method = Context::class.java.getMethod(
                    "startActivityAsUser",
                    Intent::class.java,
                    UserHandle::class.java,
                )
                method.invoke(context, i, userHandle)
                true
            }.getOrElse { false }
            if (asUserOk) return true

            if (comp != null && launcherApps != null) {
                val launcherOk = runCatching {
                    launcherApps.startMainActivity(comp, userHandle, null, null)
                    true
                }.getOrElse { false }
                if (launcherOk) return true
            }
        }

        context.startActivity(i)
        true
    } catch (_: Exception) {
        false
    }
}
