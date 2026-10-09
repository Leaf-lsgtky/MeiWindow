package com.repl.bubbledrawer.xposed

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import android.util.Log
import com.repl.bubbledrawer.data.AppRepository
import com.repl.bubbledrawer.data.MultiUserHelper

/**
 * Diagnostic: how many launcher apps can THIS process enumerate, per route.
 *
 * The fan and its 更多 panel run inside SystemUI, and on this ROM `com.android.systemui`
 * is NOT uid 1000 — `packages.list` gives it an ordinary app uid (u0_a231, verified with
 * `ps -A -o USER,PID,NAME` on 2026-10-09). Every per-uid rule of the platform therefore
 * applies to the fan's data source, package visibility included, so "the panel shows two
 * apps while the module's own Activity shows 215" has to be read route by route instead of
 * guessed at: `MultiUserHelper.queryLauncherActivitiesForUser` tries hidden IPackageManager,
 * then LauncherApps, then `queryIntentActivitiesAsUser`, and returns the FIRST non-empty
 * answer — a route that succeeds with a filtered handful silently wins over one that would
 * have returned everything.
 *
 * Printed at module install (SystemUI start, possibly while the user is still locked) and on
 * demand:
 *   adb shell am broadcast -a com.repl.bubbledrawer.action.DEBUG_PROBE
 */
object AppEnumProbe {

    const val TAG = "MeiWindow_Probe"
    const val ACTION = "com.repl.bubbledrawer.action.DEBUG_PROBE"

    fun run(context: Context, log: (Int, String, Throwable?) -> Unit, deep: Boolean = true) {
        val pm = runCatching { context.packageManager }
        log(
            Log.INFO,
            "PROBE env uid=${Process.myUid()} pkg=${context.packageName} " +
                "opPkg=${runCatching { context.opPackageName }.getOrNull()} " +
                "process=${runCatching { Application_processName() }.getOrNull()} " +
                "pm=${pm.getOrNull()?.javaClass?.name}",
            null,
        )

        // The module's own multi-route helper — its own MeiWindow_MultiUser lines name the
        // route that won, this line only reports what the caller finally received.
        for (userId in MultiUserHelper.getAllUserIds(context)) {
            val r = runCatching { MultiUserHelper.queryLauncherActivitiesForUser(context, userId) }
            log(
                Log.INFO,
                "PROBE queryLauncherActivitiesForUser(user=$userId) -> ${r.getOrNull()?.size ?: -1}" +
                    (r.exceptionOrNull()?.let { " err=$it" } ?: ""),
                null,
            )
        }

        val publicPm = runCatching {
            context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
            )
        }
        log(
            Log.INFO,
            "PROBE pm.queryIntentActivities -> ${publicPm.getOrNull()?.size ?: -1}" +
                (publicPm.exceptionOrNull()?.let { " err=$it" } ?: ""),
            null,
        )

        val launcherApps = runCatching {
            (context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps)
                .getActivityList(null, MultiUserHelper.getUserHandle(0))
        }
        log(
            Log.INFO,
            "PROBE LauncherApps.getActivityList(0) -> ${launcherApps.getOrNull()?.size ?: -1}" +
                (launcherApps.exceptionOrNull()?.let { " err=$it" } ?: ""),
            null,
        )

        // The full repository load is the expensive part (one enumeration over every user);
        // at module install FanHost already loads the same list right now, so only the
        // on-demand broadcast asks for this second opinion.
        if (!deep) return

        val all = runCatching {
            kotlinx.coroutines.runBlocking { AppRepository(context).loadAll() }
        }
        val sample = all.getOrNull()?.take(6)?.joinToString { it.packageName }
        log(
            Log.INFO,
            "PROBE AppRepository.loadAll -> ${all.getOrNull()?.size ?: -1} sample=[$sample]" +
                (all.exceptionOrNull()?.let { " err=$it" } ?: ""),
            null,
        )
    }

    /** Reflective so the probe also compiles against the plain (non-hidden) SDK surface. */
    private fun Application_processName(): String? = runCatching {
        Class.forName("android.app.Application")
            .getMethod("getProcessName")
            .invoke(null) as? String
    }.getOrNull()
}
