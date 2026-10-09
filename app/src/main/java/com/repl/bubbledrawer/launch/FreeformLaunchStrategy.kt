package com.repl.bubbledrawer.launch

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.UserHandle
import android.util.DisplayMetrics
import com.repl.bubbledrawer.data.MultiUserHelper
import com.repl.bubbledrawer.pinyin.BubbleApp

/**
 * "Stay on the current page" launch: asks the system to open the app in a
 * freeform desktop window ABOVE the calling page — the portable analogue of the
 * Flyme flow (SlideLaunchAppSettings/MoreAppWindow launch via
 * `AppLauncherWindow.m9253Y` with bundle `start_windowmode=true` :792-810, and
 * the framework floats the app over the still-visible selection page).
 *
 * Two vendor paths, then a generic path, then fullscreen fallback:
 *  1. HyperOS/MIUI: `MiuiMultiWindowUtils.getActivityOptions(ctx, pkg, true, false)`
 *     — VERIFIED in the decompiled target SystemUI 17.03:
 *     AppMiniWindowManagerImpl$launchMiniWindowActivity$1.java:133 is exactly
 *     this call (plus setFreeformAnimation(false), line 135) before
 *     PendingIntent.send(..., bundle). Static framework util → reflectable from
 *     any app; the framework may still gate the actual freeform switch for
 *     non-system callers (LSPosed phase lifts that fully).
 *     When [position] is given the 5-argument overload with explicit
 *     `launchBoundsLeft/Top` is used instead (MiuiMultiWindowUtils:711), which is
 *     how the 更多页 hands the window over at the very spot the panel sat on.
 *  2. AOSP 15/16 "desktop windowing": public `setLaunchBounds(rect)` honoured
 *     as freeform when desktop mode is enabled; @hide
 *     `setLaunchWindowingMode(1)` where the ODM gate is on.
 *  3. Anything rejected → plain fullscreen launch (never worse than v0.1).
 *
 * ## Task identity: `NEW_TASK` only — never `MULTIPLE_TASK`
 *
 * The launch intent carries `FLAG_ACTIVITY_NEW_TASK` and nothing else, matching every 小窗
 * launch this ROM performs itself:
 *  - sidebar / gamebox — `FreeformUtil` (`com.miui.gamebooster.utils.AbstractC6721e0.m21981o`,
 *    in the pulled `com.miui.securitycenter` APK) adds exactly `addFlags(268435456)`;
 *  - WMShell linkage — `MultiTaskingLinkageTransition.startSidebarLaunchFreeform:627-657`
 *    builds the options with `MiuiMultiWindowUtils.makeActivityOptions` and sends the app's
 *    own launcher intent, again with no per-launch task flag.
 *
 * New-task-ness is OPT-IN in this ROM: the caption 新建小窗 button is the only path that wants
 * a second window, so it says so explicitly (`ActivityOptions.setForceLaunchNewTask`,
 * `MiuiCaptionClickListener.handleNewWindowClicked:290-292`), reuses a background task by id
 * when one exists (:239-243) and refuses past two freeform windows (:58 `MAX_FREEFORM_COUNT`,
 * `showMaxFreeformToastIfNeeded:448-470`); the plain 小窗 button merely converts the current
 * window (:155-181).
 *
 * `FLAG_ACTIVITY_MULTIPLE_TASK` means "always start a NEW task, never bring an existing one
 * to the front". With an app that is already running (fullscreen or in another small window)
 * the ROM therefore starts a SECOND instance, and every further launch stacks another
 * identical 小窗 — which is exactly the "小窗是个新实例 / 能开出好几个一模一样的小窗" report.
 * MIUI's own ActivityStarter spells the policy out in `ActivityStarterImpl.resolveReusableTask`
 * (pulled `miui-services.jar`): a freeform launch whose options report
 * `getForceLaunchNewTask()` returns `(true, null)` = "do not reuse". Without the flag the
 * existing task is reused and MIUI moves that task into the small window.
 *
 * `FLAG_ACTIVITY_RESET_TASK_IF_NEEDED` is dropped with it: that is launcher semantics
 * ("restart the app at its root activity"), not 小窗 semantics — the sidebar resumes the app
 * where the user left it.
 */
class FreeformLaunchStrategy(private val position: Rect? = null) : ILaunchStrategy {

    override fun launch(context: Context, app: BubbleApp): Boolean {
        val userHandle = MultiUserHelper.getUserHandle(app.userId)
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val act = if (userHandle != null && launcherApps != null) {
            runCatching { launcherApps.getActivityList(app.packageName, userHandle).firstOrNull() }.getOrNull()
        } else null

        val comp = act?.componentName ?: MultiUserHelper.findComponentForPackage(context, app.packageName, app.userId)

        val intent = (if (comp != null) {
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = comp
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(app.packageName)
        })?.apply {
            // Task-identity parity with the ROM's own 小窗 launches (see class KDoc):
            // NEW_TASK only. MULTIPLE_TASK used to force a brand-new task on every launch,
            // so a running app got a second instance in a second small window instead of
            // being moved into the one window the user asked for.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return false

        val opts = miuiOptions(context, app.packageName) ?: aospOptions(context)
        val bundle = opts.toBundle()

        if (app.userId != 0 && userHandle != null) {
            // 1. Reflective Context.startActivityAsUser (works directly in SystemUI)
            val asUserOk = runCatching {
                val method = Context::class.java.getMethod(
                    "startActivityAsUser",
                    Intent::class.java,
                    android.os.Bundle::class.java,
                    UserHandle::class.java,
                )
                method.invoke(context, intent, bundle, userHandle)
                true
            }.getOrElse { false }
            if (asUserOk) return true

            // 2. Public LauncherApps.startMainActivity
            if (comp != null && launcherApps != null) {
                val launcherOk = runCatching {
                    launcherApps.startMainActivity(comp, userHandle, position, bundle)
                    true
                }.getOrElse { false }
                if (launcherOk) return true
            }
        }

        return try {
            context.startActivity(intent, bundle)
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

    /** HyperOS path — `MiuiMultiWindowUtils.getActivityOptions(...)`, positioned when asked. */
    private fun miuiOptions(context: Context, pkg: String): ActivityOptions? {
        val snap = com.repl.bubbledrawer.xposed.SettingsStore.snapshot(context)
        val pos = if (snap.flymeFreeformEnabled) null else position

        return (MiuiFreeform.activityOptions(context, pkg, noCheck = true, position = pos)
            // `getActivityOptions` is gated by checkAuthority() (third-party callers get
            // null); the ROM's own builder is not, and is what its sidebar linkage uses.
            ?: MiuiFreeform.makeActivityOptions(
                context,
                pkg,
                pos?.left ?: MiuiFreeform.POS_AUTO,
                pos?.top ?: MiuiFreeform.POS_AUTO,
            )
            ?: ActivityOptions.makeBasic().also {
                runCatching {
                    ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                        .invoke(it, 5) // 5 = WINDOWING_MODE_FREEFORM
                }
            })
            .let { MiuiFreeform.withoutFreeformAnimation(it) }
            .also { opts ->
                if (pos != null) {
                    runCatching { opts.setLaunchBounds(pos) }
                }
            }
    }

    private fun aospOptions(context: Context): ActivityOptions {
        val opts = ActivityOptions.makeBasic()
        val dm: DisplayMetrics = context.resources.displayMetrics
        val bounds = position ?: Rect().also {
            val w = (dm.widthPixels * 0.62f).toInt()
            val h = (dm.heightPixels * 0.62f).toInt()
            val cx = dm.widthPixels / 2
            val cy = (dm.heightPixels * 0.45f).toInt()
            it.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        }
        runCatching { opts.setLaunchBounds(bounds) }
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

    override fun launch(context: Context, app: BubbleApp): Boolean {
        val useFreeform = com.repl.bubbledrawer.xposed.SettingsStore.snapshot(this.context).freeform
        return (if (useFreeform) freeform else fullscreen).launch(context, app)
    }
}
