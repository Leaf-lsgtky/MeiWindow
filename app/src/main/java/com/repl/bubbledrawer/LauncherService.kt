package com.repl.bubbledrawer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.provider.Settings
import com.repl.bubbledrawer.bubble.BubbleConfig
import com.repl.bubbledrawer.bubble.BubbleDockController
import com.repl.bubbledrawer.data.AppRepository
import com.repl.bubbledrawer.data.PinStore
import com.repl.bubbledrawer.data.PrefsPinBackend
import com.repl.bubbledrawer.gesture.Corner
import com.repl.bubbledrawer.launch.ConfigurableLaunchStrategy
import com.repl.bubbledrawer.overlay.OverlayHost
import com.repl.bubbledrawer.settings.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hosts the overlay windows (original: AppLauncherWindowService — persistent
 * systemui-shared process; ours: a foreground service with specialUse type).
 */
class LauncherService : android.app.Service() {

    companion object {
        const val CHANNEL = "gesture"
        fun start(context: Context) =
            context.startForegroundService(Intent(context, LauncherService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, LauncherService::class.java))
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var host: OverlayHost? = null

    // original: AppLauncherWindow.mo864f (:1049-1056) collapses the fan on SCREEN_OFF
    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) host?.collapseAll()
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenOffReceiver, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))
        val prefs = getSharedPreferences("pins", Context.MODE_PRIVATE)
        val pinStore = AppGraph.pinStore ?: PinStore(PrefsPinBackend(prefs)).also { AppGraph.pinStore = it }
        val repo = AppGraph.repo ?: AppRepository(this).also { AppGraph.repo = it }
        val dock = BubbleDockController(this, repo, pinStore, ConfigurableLaunchStrategy(this))
        host = OverlayHost(this, BubbleConfig(this), dock)
        scope.launch { repo.loadAll() } // warm the list for the first swipe
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            AppGraph.serviceRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        host?.attach()
        AppGraph.serviceRunning = true
        // user intent flag lives in settings ("user_wants") — BootReceiver reads it
        if (AppGraph.previewRequested) {
            AppGraph.previewRequested = false
            host?.preview(Corner.BOTTOM_LEFT)
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        host?.refresh() // rebuild zones/canvas for rotation
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        host?.detach()
        host = null
        AppGraph.serviceRunning = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setSmallIcon(R.drawable.ic_stat_bubble)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
