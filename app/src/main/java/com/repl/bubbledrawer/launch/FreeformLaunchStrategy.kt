package com.repl.bubbledrawer.launch

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.DisplayMetrics
import com.repl.bubbledrawer.pinyin.BubbleApp

/**
 * "Stay on the current page" launch: asks the system to open the app in a
 * freeform desktop window ABOVE the calling page — the portable analogue of the
 * Flyme flow (SlideLaunchAppSettings/MoreAppWindow launch via
 * `AppLauncherWindow.m9253Y` with bundle `start_windowmode=true` :792-810, and
 * the framework floats the app over the still-visible selection page).
 *
 * Two vendor paths, then a generic path, then fullscreen fallback:
 *  1. HyperOS/MIUI: `android.util.MiuiMultiWindowUtils.getActivityOptions(ctx,
 *     pkg, true, false)` — VERIFIED in the decompiled target SystemUI 17.03:
 *     AppMiniWindowManagerImpl$launchMiniWindowActivity$1.java:133 is exactly
 *     this call (plus setFreeformAnimation(false), line 135) before
 *     PendingIntent.send(..., bundle). Static framework util → reflectable from
 *     any app; the framework may still gate the actual freeform switch for
 *     non-system callers (LSPosed phase lifts that fully).
 *  2. AOSP 15/16 "desktop windowing": public `setLaunchBounds(rect)` honoured
 *     as freeform when desktop mode is enabled; @hide
 *     `setLaunchWindowingMode(1)` where the ODM gate is on.
 *  3. Anything rejected → plain fullscreen launch (never worse than v0.1).
 */
class FreeformLaunchStrategy : ILaunchStrategy {

    override fun launch(context: Context, app: BubbleApp): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                )
            } ?: return false

        val opts = miuiOptions(context, app.packageName) ?: aospOptions(context)
        return try {
            context.startActivity(intent, opts.toBundle())
            true
        } catch (_: Exception) {
            try {
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** HyperOS path — MiuiMultiWindowUtils.getActivityOptions(ctx, pkg, true, false). */
    private fun miuiOptions(context: Context, pkg: String): ActivityOptions? = runCatching {
        Class.forName("android.util.MiuiMultiWindowUtils")
            .getMethod(
                "getActivityOptions",
                Context::class.java, String::class.java,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            )
            .invoke(null, context, pkg, true, false) as? ActivityOptions
    }.getOrNull()

    private fun aospOptions(context: Context): ActivityOptions {
        val opts = ActivityOptions.makeBasic()
        val dm: DisplayMetrics = context.resources.displayMetrics
        val w = (dm.widthPixels * 0.62f).toInt()
        val h = (dm.heightPixels * 0.62f).toInt()
        val cx = dm.widthPixels / 2
        val cy = (dm.heightPixels * 0.45f).toInt()
        runCatching { opts.setLaunchBounds(Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)) }
        runCatching {
            // @hide ActivityOptions.setLaunchWindowingMode(int); 1 = WINDOWING_MODE_FREEFORM
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(opts, 1)
        }
        return opts
    }
}

/** Reads the user's 小窗启动 toggle at launch time (flyme parity: every bubble
 *  launch carries `start_windowmode`; our fallback is honest fullscreen).
 *  The toggle lives in the module's shared prefs (SettingsStore writes through
 *  to the LSPosed remote group; the local mirror keeps it usable offline). */
class ConfigurableLaunchStrategy(private val context: Context) : ILaunchStrategy {
    private val freeform = FreeformLaunchStrategy()
    private val fullscreen = FullscreenLaunchStrategy()

    override fun launch(ctx: Context, app: BubbleApp): Boolean {
        val useFreeform = com.repl.bubbledrawer.xposed.SettingsStore.snapshot(context).freeform
        return (if (useFreeform) freeform else fullscreen).launch(ctx, app)
    }
}
