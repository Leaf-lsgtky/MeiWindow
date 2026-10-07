package com.repl.bubbledrawer.launch

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.DisplayMetrics
import com.repl.bubbledrawer.pinyin.BubbleApp

/**
 * "Stay on the current page" launch: asks the system to open the app in a
 * FREEFORM desktop window ABOVE the calling page — the portable analogue of the
 * Flyme flow (SlideLaunchAppSettings/MoreAppWindow launch via
 * `AppLauncherWindow.m9253Y` with bundle `start_windowmode=true` :792-810, and
 * the framework floats the app over the still-visible selection page).
 *
 * How it works on stock Android 15/16 ("desktop windowing"):
 *  - public: `ActivityOptions.setLaunchBounds(rect)` — honoured as "open this
 *    activity in a freeform window of these bounds" when the device's desktop
 *    windowing is enabled, ignored (fullscreen) otherwise;
 *  - @hide via reflection: `setLaunchWindowingMode(WINDOWING_MODE_FREEFORM = 1)`
 *    — works where the ODM/dev-option gate "Enable freeform windows" is on.
 * Both are best-effort: ANY SecurityException/Rejection falls back to a normal
 * fullscreen launch, so behaviour never regresses below FullscreenLaunchStrategy.
 *
 * Real in-window embedding (app drawn inside our own page, no separate window)
 * is impossible for third-party apps — Android forbids hosting other apps'
 * Activities in-process; the closest the OS allows IS the freeform float above.
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

        val opts = ActivityOptions.makeBasic()
        applyFreeformHints(context, opts)
        return try {
            context.startActivity(intent, opts.toBundle())
            true
        } catch (_: Exception) {
            // device rejected freeform — plain fullscreen launch
            try {
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun applyFreeformHints(context: Context, opts: ActivityOptions) {
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
    }
}

/** Reads the user's 小窗启动 toggle at launch time (flyme parity: every bubble
 *  launch carries `start_windowmode`; our fallback is honest fullscreen). */
class ConfigurableLaunchStrategy(private val context: Context) : ILaunchStrategy {
    private val freeform = FreeformLaunchStrategy()
    private val fullscreen = FullscreenLaunchStrategy()

    override fun launch(ctx: Context, app: com.repl.bubbledrawer.pinyin.BubbleApp): Boolean {
        val useFreeform = com.repl.bubbledrawer.bubble.BubbleConfig(context).freeform
        return (if (useFreeform) freeform else fullscreen).launch(ctx, app)
    }
}
