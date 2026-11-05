package com.repl.bubbledrawer.xposed

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-process side of the config bridge — same service-102 plumbing as the
 * reference FrameworkConnectionRepository (:30-44 register listener, :202-225
 * probe apiVersion/PROP_CAP_REMOTE, :215 getRemotePreferences(GROUP)), reduced
 * to what a settings UI needs.
 *
 * Design: the LOCAL file "cfg" is the settings page's always-available storage
 * (works even before LSPosed connects). On first bind we MIRROR it into the
 * remote group exactly once — a plain `putString(KEY_LOCAL_MIRROR, ts)` snapshot
 * (no content-hash trap; a same-value rewrite is harmless and cheap). After that
 * every local toggle pushes straight through to the remote group, so the live
 * SystemUI monitor sees it in real time.
 */
object RemoteBridge {

    /** Our own local settings store (the settings UI reads/writes this). */
    fun local(context: Context): SharedPreferences =
        context.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    @Volatile
    var remote: SharedPreferences? = null
        private set

    @Volatile
    var frameworkLabel: String? = null
        private set

    /** true once the remote group is connected (settings page shows the state). */
    val connected: Boolean get() = remote != null

    /**
     * Connection listeners, notified on the worker thread at every bind/die transition.
     * The Compose settings page feeds its snapshot flow from these (guidelines: screens
     * consume Flows, never poll `connected` in recomposition).
     */
    private val connectionListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addConnectionListener(listener: (Boolean) -> Unit) {
        connectionListeners += listener
        // Deliver the CURRENT state immediately: a page collecting the flow must not wait
        // for the next transition to learn whether the bridge is already up.
        listener(connected)
    }

    fun removeConnectionListener(listener: (Boolean) -> Unit) {
        connectionListeners -= listener
    }

    private fun notifyConnection(connectedNow: Boolean) {
        connectionListeners.forEach { listener ->
            runCatching { listener(connectedNow) }
        }
    }

    private val started = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "bubble-bridge") }

    private const val KEY_LOCAL_MIRROR = "cfg_mirror_ts"

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                worker.execute { handleBind(service, app) }
            }

            override fun onServiceDied(service: XposedService) {
                worker.execute {
                    if (boundService === service) {
                        remote = null; boundService = null
                        notifyConnection(false)
                    }
                }
            }
        })
    }

    @Volatile
    private var boundService: XposedService? = null

    private fun handleBind(service: XposedService, context: Context) {
        try {
            if (service.apiVersion < XposedService.API_102) return
            if (service.frameworkProperties and XposedService.PROP_CAP_REMOTE == 0L) return
            val prefs = service.getRemotePreferences(RemotePrefs.GROUP)
            boundService = service
            remote = prefs
            frameworkLabel = runCatching { "${service.frameworkName} ${service.frameworkVersion}" }.getOrNull()
            migrateLegacyPins(context, prefs)
            syncLocal(context) // reconcile pins/functions by rev and push local settings truth
            // 绑上之后立刻把「已选功能」的详情重算一次：上一次可能是在缓存还空的时候算的占位项。
            runCatching { SettingsStore.publishFunctionDetails(context) }
            notifyConnection(true)
        } catch (exception: RuntimeException) {
            Log.w("BubbleDrawer", "BRIDGE_BIND_FAILED", exception)
        }
    }

    /** One-time move of the old app-private "pins" or local "cfg" into the shared group. */
    private fun migrateLegacyPins(context: Context, prefs: SharedPreferences) {
        val key = com.repl.bubbledrawer.data.PrefsPinBackend.KEY
        val localPrefs = local(context)
        val localCfg = localPrefs.getString(key, null)
        if (!localCfg.isNullOrEmpty() || localPrefs.contains(com.repl.bubbledrawer.data.PrefsPinBackend.KEY_REV)) {
            if (!prefs.contains(key) && !localCfg.isNullOrEmpty()) {
                prefs.edit().putString(key, localCfg).commit()
            }
            return
        }
        val legacy = context.getSharedPreferences("pins", Context.MODE_PRIVATE)
            .getString(key, null)
        if (!legacy.isNullOrEmpty()) {
            localPrefs.edit().putString(key, legacy).commit()
            prefs.edit().putString(key, legacy).commit()
        }
    }

    /** Push ALL local settings keys into the remote group (local file uses the
     *  same key names as RemotePrefs — see SettingsStore). */
    fun syncLocal(context: Context) {
        val target = remote ?: return
        val localPrefs = local(context)
        val snap = RemotePrefs.read(localPrefs)
        // 收藏应用与已选功能在绑定时按戳和解（PinsSync.decide）：
        //  - 若远程组里的戳更新，先把赢家写回本地 cfg，避免把远程新值用本地旧值盖掉；
        //  - 写回远程组时必须同时写值与戳（之前漏了 KEY_REV，导致远程组只有值没有戳）。
        val localPinBackend = com.repl.bubbledrawer.data.PrefsPinBackend(localPrefs)
        val remotePinBackend = com.repl.bubbledrawer.data.PrefsPinBackend(target)
        val pinDecision = com.repl.bubbledrawer.data.PinsSync.decide(
            localValue = localPinBackend.read(),
            localRev = localPinBackend.readRev(),
            remoteValue = remotePinBackend.read(),
            remoteRev = remotePinBackend.readRev(),
        )
        if (pinDecision.source == com.repl.bubbledrawer.data.PinsSync.Source.REMOTE) {
            localPinBackend.writeWithRev(pinDecision.value, pinDecision.rev)
        }

        val localFnBackend = com.repl.bubbledrawer.shortcut.FunctionPinStore.backend(localPrefs)
        val remoteFnBackend = com.repl.bubbledrawer.shortcut.FunctionPinStore.backend(target)
        val fnDecision = com.repl.bubbledrawer.data.PinsSync.decide(
            localValue = localFnBackend.read(),
            localRev = localFnBackend.readRev(),
            remoteValue = remoteFnBackend.read(),
            remoteRev = remoteFnBackend.readRev(),
        )
        if (fnDecision.source == com.repl.bubbledrawer.data.PinsSync.Source.REMOTE) {
            localFnBackend.writeWithRev(fnDecision.value, fnDecision.rev)
        }

        val localFunctionDetails = localPrefs.getString(com.repl.bubbledrawer.shortcut.FunctionPinDetails.KEY, null)
        val editor = target.edit()
            .putBoolean(RemotePrefs.KEY_ENABLED, snap.enabled)
            .putBoolean(RemotePrefs.KEY_LEFT, snap.left)
            .putBoolean(RemotePrefs.KEY_RIGHT, snap.right)
            .putInt(RemotePrefs.KEY_RANGE_DP, snap.rangeDp)
            .putBoolean(RemotePrefs.KEY_FREEFORM, snap.freeform)
            .putInt(RemotePrefs.KEY_BOTTOM_DP, snap.bottomDp)
            .putInt(RemotePrefs.KEY_EDGE_DP, snap.edgeDp)
            .putInt(RemotePrefs.KEY_PANEL_W_PCT, snap.panelWidthPct)
            .putInt(RemotePrefs.KEY_PANEL_H_PCT, snap.panelHeightPct)
            .putInt(RemotePrefs.KEY_PANEL_ICON_DP, snap.panelIconDp)
            .putInt(RemotePrefs.KEY_PANEL_TEXT_SP, snap.panelTextSp)
            .putInt(RemotePrefs.KEY_FAN_ICON_COUNT, snap.fanIconCount)
            .putInt(RemotePrefs.KEY_FAN_LAYERS, snap.fanLayers)
            .putInt(RemotePrefs.KEY_FAN_RADIUS_DP, snap.fanRadiusDp)
            .putBoolean(RemotePrefs.KEY_FAN_OUTER_RADIUS_ENABLED, snap.fanOuterRadiusEnabled)
            .putInt(RemotePrefs.KEY_FAN_OUTER_RADIUS_DP, snap.fanOuterRadiusDp)
            .putBoolean(RemotePrefs.KEY_FAN_SHOW_VIBRATE, snap.fanShowVibrate)
            .putInt(RemotePrefs.KEY_FAN_AUTO_RETRACT_SEC, snap.fanAutoRetractSec)
            .putBoolean(RemotePrefs.KEY_FAN_DOUBLE_TAP_DISMISS, snap.fanDoubleTapDismiss)
            .putString(RemotePrefs.KEY_FAN_BLACKLIST, encodeFanBlacklist(snap.fanBlacklist))
            .putBoolean(RemotePrefs.KEY_FAN_LANDSCAPE_TWO_SWIPES, snap.fanLandscapeTwoSwipes)
            .putInt(RemotePrefs.KEY_FAN_ICON_DP, snap.fanIconDp)
            .putInt(RemotePrefs.KEY_FAN_ICON_SHAPE, snap.fanIconShape)
            .putBoolean(RemotePrefs.KEY_CLONE_BADGE, snap.cloneBadge)
            .putBoolean(RemotePrefs.KEY_FAN_AUTO_FILL_RECOMMEND, snap.fanAutoFillRecommend)
            .putBoolean(RemotePrefs.KEY_FAN_PRESSURE_PAGE_TURN, snap.fanPressurePageTurn)
            .putInt(RemotePrefs.KEY_FAN_PRESSURE_SENSITIVITY, snap.fanPressureSensitivity)
            // 校准状态一起镜像：这些值平时由 SystemUI 广播回 App 落盘，但 App 的本地文件才是
            // 「重启/重连后的真相」（syncLocal 会把本地整份推回去），漏掉它们就会把校准结果抹掉。
            .putBoolean(RemotePrefs.KEY_FAN_PRESSURE_CALIBRATED, snap.fanPressureCalibrated)
            .putFloat(RemotePrefs.KEY_FAN_PRESSURE_CALIBRATED_HPA, snap.fanPressureCalibratedHpa)
            .putBoolean(RemotePrefs.KEY_FAN_PRESSURE_CALIBRATION_ACTIVE, snap.fanPressureCalibrationActive)
            .putInt(RemotePrefs.KEY_FAN_PRESSURE_CALIBRATION_ATTEMPTS, snap.fanPressureCalibrationAttempts)
            .putInt(RemotePrefs.KEY_FAN_PRESSURE_CALIBRATION_VALID_COUNT, snap.fanPressureCalibrationValidCount)
            .putFloat(RemotePrefs.KEY_FAN_PRESSURE_CALIBRATION_LAST_DELTA, snap.fanPressureCalibrationLastDelta)
            .putBoolean(RemotePrefs.KEY_FAN_PRESSURE_CALIBRATION_LAST_VALID, snap.fanPressureCalibrationLastValid)
            .putInt(RemotePrefs.KEY_FAN_PRESSURE_CALIBRATION_OUTCOME, snap.fanPressureCalibrationOutcome)
            .putInt(RemotePrefs.KEY_PANEL_DISMISS_OUTSIDE, snap.panelDismissOutside)
            .putBoolean(RemotePrefs.KEY_RECOMMEND_ENABLED, snap.recommendEnabled)
            .putInt(RemotePrefs.KEY_RECOMMEND_COUNT, snap.recommendCount)
            .putBoolean(RemotePrefs.KEY_FLYME_FREEFORM_ENABLED, snap.flymeFreeformEnabled)
            .putBoolean(RemotePrefs.KEY_FLYME_FREEFORM_CENTER, snap.flymeFreeformCenter)
            .putInt(RemotePrefs.KEY_FLYME_FREEFORM_VERTICAL_PCT, snap.flymeFreeformVerticalPct)
            .putBoolean(RemotePrefs.KEY_FLYME_FREEFORM_NO_OFFSET, snap.flymeFreeformNoOffset)
            .putInt(RemotePrefs.KEY_FLYME_FREEFORM_SCALE, snap.flymeFreeformScale)
            .putBoolean(RemotePrefs.KEY_FLYME_FREEFORM_OUTSIDE_DISMISS, snap.flymeFreeformOutsideDismiss)
            .putInt(RemotePrefs.KEY_FLYME_FREEFORM_OUTSIDE_DISMISS_ACTION, snap.flymeFreeformOutsideDismissAction)
            .putBoolean(RemotePrefs.KEY_FLYME_FREEFORM_SWIPE_UP_MINI, snap.flymeFreeformSwipeUpMini)
            .putBoolean(RemotePrefs.KEY_FLYME_FREEFORM_SWIPE_UP_HOLD_FREE, snap.flymeFreeformSwipeUpHoldFree)
            .putBoolean(RemotePrefs.KEY_FLYME_FREEFORM_DIM_BG, snap.flymeFreeformDimBg)
            // 迷你小窗长按菜单（2026-10-18）：和上面这些一样要在共享组里留一份，否则重启 App 后
            // `syncLocal` 的镜像会把它漏掉，模块那边读到的还是默认值。
            .putBoolean(RemotePrefs.KEY_FLYME_MINI_MENU, snap.flymeMiniMenu)
            .putInt(RemotePrefs.KEY_FLYME_MINI_ENLARGE_PCT, snap.flymeMiniEnlargePct)
            .putBoolean(RemotePrefs.KEY_HOME_KEEP_FREEFORM, snap.homeKeepFreeform)
            .putBoolean(RemotePrefs.KEY_CONTACT_BAR, snap.contactBar)
            .putInt(RemotePrefs.KEY_CONTACT_BAR_MIN_WIDTH_PCT, snap.contactBarMinWidthPct)
            // 「轻量打开」全套配置必须一起镜像：此前漏掉这组键会导致用户在本地关了轻量打开后，
            // 远程组仍保留旧的 true 值，表现为「没开轻量打开也会拦截进小窗」。
            .putBoolean(RemotePrefs.KEY_LIGHT_OPEN_ENABLED, snap.lightOpenEnabled)
            .putBoolean(RemotePrefs.KEY_LIGHT_OPEN_NOTIF_PORTRAIT, snap.lightOpenNotifPortrait)
            .putBoolean(RemotePrefs.KEY_LIGHT_OPEN_NOTIF_LANDSCAPE, snap.lightOpenNotifLandscape)
            .putBoolean(RemotePrefs.KEY_LIGHT_OPEN_CONTROL_CENTER, snap.lightOpenControlCenter)
            .putBoolean(RemotePrefs.KEY_LIGHT_OPEN_SHARE, snap.lightOpenShare)
            .putBoolean(RemotePrefs.KEY_LIGHT_OPEN_TEXT, snap.lightOpenText)
            .putBoolean(RemotePrefs.KEY_LIGHT_OPEN_JUMP, snap.lightOpenJump)
            .putInt(RemotePrefs.KEY_LIGHT_OPEN_JUMP_MODE, snap.lightOpenJumpMode)
            .putString(RemotePrefs.KEY_LIGHT_OPEN_JUMP_LIST, snap.lightOpenJumpList)
            // 顺手清掉跨重启/跨会话残留的一次性小窗启动口令。
            .remove(RemotePrefs.KEY_LAUNCH_PRIME)
        localPrefs.edit().remove(RemotePrefs.KEY_LAUNCH_PRIME).apply()
        if (pinDecision.source != com.repl.bubbledrawer.data.PinsSync.Source.NONE) {
            editor.putString(com.repl.bubbledrawer.data.PrefsPinBackend.KEY, pinDecision.value)
            editor.putLong(com.repl.bubbledrawer.data.PrefsPinBackend.KEY_REV, pinDecision.rev)
        }
        if (fnDecision.source != com.repl.bubbledrawer.data.PinsSync.Source.NONE) {
            editor.putString(com.repl.bubbledrawer.shortcut.FunctionPinStore.KEY_PINNED_FUNCTIONS, fnDecision.value)
            editor.putLong(com.repl.bubbledrawer.shortcut.FunctionPinStore.PREFS_KEY_REV, fnDecision.rev)
        }
        if (localFunctionDetails != null) {
            editor.putString(com.repl.bubbledrawer.shortcut.FunctionPinDetails.KEY, localFunctionDetails)
        }
        editor.commit() // synchronous: LSPosed mirrors the group only after the write lands
        Log.i(
            "BubbleDrawer",
            "BRIDGE_PUSHED enabled=" + snap.enabled + " left=" + snap.left +
                " right=" + snap.right + " range=" + snap.rangeDp + " pinsLen=" + pinDecision.value.length,
        )
        if (!target.contains(com.repl.bubbledrawer.data.PrefsPinBackend.KEY)) {
            migrateLegacyPins(context, target)
        }
    }
}
