package com.repl.bubbledrawer.xposed

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.PowerManager
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.bubble.BubbleDockController
import com.repl.bubbledrawer.bubble.FanPressureDetector
import com.repl.bubbledrawer.bubble.GestureAppLauncher
import com.repl.bubbledrawer.bubble.SlideGestureItemView
import com.repl.bubbledrawer.data.AppRepository
import com.repl.bubbledrawer.data.LaunchCountStore
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.repl.bubbledrawer.data.PinBackend
import com.repl.bubbledrawer.data.PinCodec
import com.repl.bubbledrawer.data.PinStore
import com.repl.bubbledrawer.data.PinsSync
import com.repl.bubbledrawer.data.PrefsPinBackend
import com.repl.bubbledrawer.gesture.Corner
import com.repl.bubbledrawer.gesture.SpySide
import com.repl.bubbledrawer.launch.FreeformLaunchStrategy
import com.repl.bubbledrawer.launch.FullscreenLaunchStrategy
import com.repl.bubbledrawer.launch.ILaunchStrategy
import com.repl.bubbledrawer.pin.PanelContentFactory
import com.repl.bubbledrawer.pin.PinManageModel
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.launch

/**
 * The fan UI (scrim + touchable canvas windows — the same window pair the old
 * OverlayHost used) living INSIDE the SystemUI process, driven by the single
 * pilfered stroke from the SPY view. This replaces the old zone-window routing.
 *
 * Retract paths, per the FlymeFreeform study (CornerRadialOverlayView.kt):
 *  • engine Cancel — pull-back beyond reverseTolerance (CornerGestureEngine :141-145)
 *    → cancelExternal
 *  • release decisions — forwarded UP runs GestureAppLauncher m9729p (launch over
 *    an icon / cancelFlag collapse after pull-back / stay open over plain void)
 *  • void DOWN on the canvas closes — verbatim C2822g.onTouchEvent :381-393
 *  • 5s inactivity timeout RE-ARMED ON EVERY EVENT — reference :186-187 (arm on
 *    beginGesture) and :194-195 (re-arm on updateGesture), GESTURE_TIMEOUT_MS
 *    = 5_000L (:1000). This is the self-heal that guarantees the panel ALWAYS
 *    retracts even if a UP is somehow lost — the user's "依旧无法收回" backstop.
 *  • SCREEN_OFF → retract (original mo864f :1049-1056)
 */
class FanHost(
    private val host: Context,
    private val prefs: SharedPreferences,
    private val logger: (Int, String, Throwable?) -> Unit,
) {
    private val wm = host.getSystemService(WindowManager::class.java)!!
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Fan context: SystemUI identity (package/services/storage — the window attribution rule in ModuleResources), module resources + classloader
     *  (our layouts, dimens, drawables). */
    private val moduleContext: Context? = ModuleResources.packageContext(host)
    private val context: Context = moduleContext?.let { ModuleResources.FanContext(host, it) } ?: host

    private val repo = AppRepository(context)

    /**
     * Pin persistence inside SystemUI (方案 B panel writes).
     *
     * `prefs` (the module's `XposedInterface.getRemotePreferences` view) is a READ-ONLY
     * mirror — its `edit()` throws `UnsupportedOperationException: Read only
     * implementation` (verified on device; libxposed 102 semantics: hook-process writes
     * do not exist on that object, and `XposedServiceHelper` does not deliver to
     * hook processes either — only to the module's own app process).
     *
     * So the write path is a round-trip to the module APP process:
     * [PinSyncService] (exported, guarded by a caller-uid check) applies the encoded
     * order through the ordinary app-side backend (local mirror + LSPosed remote group),
     * which is exactly what the manage Activity writes. SystemUI runs as system (uid
     * 1000), so `startService` from here is allowed to reach the module app even when it
     * is not running. Until the round-trip lands, the in-memory overlay below keeps the
     * FAN consistent within this SystemUI lifetime (the original ItemTouchHelper also
     * only re-read on next show).
     */
    @Volatile
    private var pinsOverlay: String? = null

    // SystemUI-local persistent SharedPreferences: guaranteed to survive SystemUI reboots
    private val storageContext: Context =
        if (host.isDeviceProtectedStorage) host
        else runCatching { host.createDeviceProtectedStorageContext() }.getOrDefault(host)

    private val sysUiPrefs: SharedPreferences =
        storageContext.getSharedPreferences("bubbledrawer_pins", Context.MODE_PRIVATE)

    private val pinStore = PinStore(object : PinBackend {
        private val local get() = PrefsPinBackend(sysUiPrefs)
        private val remote get() = PrefsPinBackend(prefs)

        override fun read(): String {
            // This session's own edit is authoritative until the app confirms it.
            pinsOverlay?.let { return it }

            val localBackend = local
            // `prefs` is the LSPosed remote group: readable here, never writable (see the
            // class doc above). A read failure must not hide the local copy.
            val remoteValue = runCatching { remote.read() }.getOrDefault("")
            val remoteRev = runCatching { remote.readRev() }.getOrDefault(0L)

            val decision = PinsSync.decide(
                localValue = localBackend.read(),
                localRev = localBackend.readRev(),
                remoteValue = remoteValue,
                remoteRev = remoteRev,
            )
            when (decision.source) {
                PinsSync.Source.REMOTE -> {
                    localBackend.write(decision.value)
                    localBackend.writeRev(decision.rev)
                }
                PinsSync.Source.LOCAL -> if (decision.pushBack) {
                    // Our copy is newer than the shared group — the app never applied the
                    // handoff (force-stopped by HyperOS, broadcast lost…). Hand it over again,
                    // otherwise the very next SystemUI start would read the stale value.
                    pushPinsToApp(decision.value, decision.rev)
                }
                PinsSync.Source.NONE -> Unit
            }
            logger(
                Log.INFO,
                "PIN_READ src=${decision.source} localRev=${localBackend.readRev()} " +
                    "remoteRev=$remoteRev pins=${PinCodec.decode(decision.value).size}",
                null,
            )
            return decision.value
        }

        override fun write(value: String) {
            val rev = System.currentTimeMillis()
            pinsOverlay = value
            val localBackend = local
            localBackend.write(value)
            localBackend.writeRev(rev)
            pushPinsToApp(value, rev)
        }

        override fun readRev(): Long = local.readRev()

        override fun writeRev(rev: Long) {
            local.writeRev(rev)
        }
    })

    /**
     * Hand the pin order to the module's app process: explicit broadcast first (works even
     * when the app is idle), service as the fallback (it is accepted now — the old uid check
     * only allowed uid 1000, while this ROM's SystemUI is an ordinary app uid).
     */
    private fun pushPinsToApp(value: String, rev: Long) {
        val deliveredBroadcast = runCatching {
            val intent = Intent(PinSyncReceiver.ACTION)
                .setClassName(ModuleResources.MODULE_PACKAGE, "com.repl.bubbledrawer.xposed.PinSyncReceiver")
                .putExtra(PinSyncReceiver.EXTRA_PINS, value)
                .putExtra(PinSyncReceiver.EXTRA_REV, rev)
            host.sendBroadcast(intent)
            true
        }.getOrDefault(false)

        val deliveredService = runCatching {
            val intent = Intent(PinSyncService.ACTION)
                .setClassName(ModuleResources.MODULE_PACKAGE, "com.repl.bubbledrawer.xposed.PinSyncService")
                .putExtra(PinSyncService.EXTRA_PINS, value)
                .putExtra(PinSyncService.EXTRA_REV, rev)
            context.startService(intent)
            true
        }.getOrDefault(false)

        logger(
            if (deliveredBroadcast || deliveredService) Log.INFO else Log.WARN,
            "PIN_WRITE_HANDOFF broadcast=$deliveredBroadcast service=$deliveredService " +
                "rev=$rev valueLen=${value.length}",
            null,
        )
    }

    private val strategy = ILaunchStrategy { ctx, app ->
        // freeform toggle from the same prefs the monitor watches (remote group)
        val snap = RemotePrefs.read(prefs)
        (if (snap.freeform) FreeformLaunchStrategy() else FullscreenLaunchStrategy()).launch(ctx, app)
    }

    /** Tile factory — the verbatim slide_gesture_list_item structure built in
     *  code so the fan works even if XML inflation fails in the foreign process
     *  (same dimens/paddings as the layout file, res/values/dimens.xml:10-13). */
    private val itemFactory = GestureAppLauncher.ViewFactory { item, _ -> buildTile(item) }

    private val dock = BubbleDockController(context, repo, pinStore, strategy, itemFactory)

    private val pressureDetector = FanPressureDetector(host) {
        onHeavyPressTriggered()
    }

    private var scrim: View? = null
    private var canvas: FrameLayout? = null
    private var fanWindowsUp = false

    private val timeout = Runnable { if (dock.isBusy) retract("TIMEOUT") }
    private var screenOffReceiver: android.content.BroadcastReceiver? = null
    private var pinsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var syncFromAppReceiver: android.content.BroadcastReceiver? = null

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob(),
    )

    init {
        if (moduleContext == null) logger(Log.ERROR, "FAN_MODULE_CONTEXT_FAILED", null)
        val initialSnap = RemotePrefs.read(prefs)
        pressureDetector.isEnabled = initialSnap.fanPressurePageTurn
        pressureDetector.thresholdHpa = RemotePrefs.sensitivityToThreshold(initialSnap.fanPressureSensitivity)
        dock.onShownChanged = { shown -> if (!shown) fadeOutFanWindows() }
        // 更多 tile → overlay panel instead of the full-screen Activity (方案 B)
        dock.onMoreRequested = { showMorePanel() }
        scope.launch {
            runCatching { repo.loadAll() }
            mainHandler.post {
                if (dock.isBusy) {
                    val snap = RemotePrefs.read(prefs)
                    dock.show(currentCorner(), screenW(), screenH(), snap.fanIconCount, snap.fanRadiusDp, snap.fanAutoFillRecommend)
                }
            }
        }
        registerUnlockReceiver()
        // pins edits while the fan is open → live rebind (reference :85-96 watch pattern)
        pinsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PrefsPinBackend.KEY || key == PrefsPinBackend.KEY_REV) {
                // The shared group moved: adopt it only when it is genuinely newer than our own
                // copy. Blindly copying it here is what used to make the panel show the app's
                // list and the app show the panel's, each depending on who wrote last.
                val remoteBackend = PrefsPinBackend(prefs)
                val remoteRev = runCatching { remoteBackend.readRev() }.getOrDefault(0L)
                val remoteValue = runCatching { remoteBackend.read() }.getOrDefault("")
                val localBackend = PrefsPinBackend(sysUiPrefs)
                val localRev = localBackend.readRev()
                if (remoteRev > localRev || (remoteRev == localRev && remoteValue == localBackend.read())) {
                    localBackend.write(remoteValue)
                    localBackend.writeRev(remoteRev)
                    pinsOverlay = null
                } else if (remoteValue != localBackend.read()) {
                    pushPinsToApp(localBackend.read(), localRev)
                }
                logger(
                    Log.INFO,
                    "PIN_REMOTE_CHANGED remoteRev=$remoteRev localRev=$localRev " +
                        "pins=${PinCodec.decode(localBackend.read()).size}",
                    null,
                )
                rebindFanIfBusy()
            } else if (key == RemotePrefs.KEY_FAN_ICON_COUNT || key == RemotePrefs.KEY_FAN_RADIUS_DP || key == RemotePrefs.KEY_FAN_AUTO_FILL_RECOMMEND) {
                rebindFanIfBusy()
            } else if (key == RemotePrefs.KEY_FAN_PRESSURE_PAGE_TURN || key == RemotePrefs.KEY_FAN_PRESSURE_SENSITIVITY) {
                val snap = RemotePrefs.read(prefs)
                pressureDetector.isEnabled = snap.fanPressurePageTurn
                pressureDetector.thresholdHpa = RemotePrefs.sensitivityToThreshold(snap.fanPressureSensitivity)
            }
        }
        runCatching { prefs.registerOnSharedPreferenceChangeListener(pinsListener) }

        // Direct broadcast receiver from App (e.g. PinManageActivity in settings app)
        val syncFromApp = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val raw = intent?.getStringExtra(PinSyncReceiver.EXTRA_PINS) ?: return
                if (!PinSyncReceiver.isPlausiblePinsValue(raw)) return
                val rev = intent.getLongExtra(PinSyncReceiver.EXTRA_REV, 0L)
                val localBackend = PrefsPinBackend(sysUiPrefs)
                val localRev = localBackend.readRev()
                val localValue = localBackend.read()
                if (rev <= 0L || rev >= localRev) {
                    // The app's copy is at least as new: adopt it and drop our session overlay,
                    // so reads stop pinning this session's earlier edit.
                    localBackend.write(raw)
                    localBackend.writeRev(if (rev > 0L) rev else System.currentTimeMillis())
                    pinsOverlay = null
                    rebindFanIfBusy()
                    logger(Log.INFO, "PIN_SYNC_FROM_APP_APPLIED len=${raw.length} rev=$rev", null)
                } else {
                    // A stale delivery (e.g. the app's pre-existing value arriving after a panel
                    // edit): keep ours and hand it over again, otherwise the two lists diverge.
                    logger(Log.WARN, "PIN_SYNC_FROM_APP_STALE rev=$rev localRev=$localRev", null)
                    if (localValue != raw) pushPinsToApp(localValue, localRev)
                }
            }
        }
        runCatching {
            host.registerReceiver(
                syncFromApp,
                IntentFilter(PinSyncReceiver.ACTION_TO_SYSTEMUI),
                Context.RECEIVER_EXPORTED,
            )
        }.onSuccess { syncFromAppReceiver = syncFromApp }

        // SCREEN_OFF → retract (original mo864f :1049-1056 → m9254Z)
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                mainHandler.post {
                    hideMorePanel(animated = false)
                    retract("SCREEN_OFF")
                }
            }
        }
        runCatching {
            host.registerReceiver(r, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        }.onSuccess { screenOffReceiver = r }

    }

    // ---------------- app list freshness (the boot/locked-enumeration trap) ----------------

    private var unlockReceiver: android.content.BroadcastReceiver? = null

    /**
     * The module is injected into SystemUI at BOOT, i.e. while the user is still locked — and
     * in that state the platform answers package queries with a tiny direct-boot-shaped
     * subset. Measured on device (2026-10-09, uid 10231 com.android.systemui): the fan's own
     * `AppRepository` enumerated **2** apps (设置 + 联系人) at boot versus **216** after
     * unlock, and because that answer sits in `AppRepository.cache` for the life of this
     * process, the 更多 panel showed those two apps until SystemUI was restarted by hand.
     *
     * Nothing else re-reads the list, so the unlock is the moment to do it: reload, then
     * rebind whatever is on screen. [Intent.ACTION_BOOT_COMPLETED] is the belt-and-braces
     * second chance (a device without a secure lock never gets USER_UNLOCKED, and there the
     * boot-time answer was already correct).
     */
    private fun registerUnlockReceiver() {
        if (unlockReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = reloadApps(i?.action ?: "UNLOCK")
        }
        runCatching {
            host.registerReceiver(
                r,
                IntentFilter().apply {
                    addAction(Intent.ACTION_USER_UNLOCKED)
                    addAction(Intent.ACTION_BOOT_COMPLETED)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )
        }.onSuccess {
            unlockReceiver = r
            logger(Log.INFO, "APP_LIST_RELOAD_ARMED", null)
        }.onFailure { logger(Log.WARN, "APP_LIST_RELOAD_ARM_FAILED", it) }
    }

    /** Re-enumerate when the cached list cannot be trusted; rebind the open fan/panel. */
    private fun reloadApps(reason: String) {
        if (!repo.cacheNeedsRefresh()) {
            logger(Log.INFO, "APP_LIST_RELOAD_SKIPPED reason=$reason total=${repo.cachedAll().size}", null)
            return
        }
        scope.launch {
            val total = runCatching { repo.loadAll() }.getOrNull()?.size ?: -1
            logger(Log.INFO, "APP_LIST_RELOADED reason=$reason total=$total", null)
            mainHandler.post {
                val snap = RemotePrefs.read(prefs)
                if (moreUp) {
                    // The panel is reading the old list: rebuild it in place.
                    moreContent?.reload()
                } else if (dock.isBusy) {
                    dock.show(currentCorner(), screenW(), screenH(), snap.fanIconCount, snap.fanRadiusDp, snap.fanAutoFillRecommend)
                }
            }
        }
    }

    /** adb verification hook — see `CornerInputMonitor.registerDebugReceiver`. */
    fun toggleMorePanelForDebug() {
        if (moreUp) hideMorePanel() else showMorePanel()
    }

    private var activeCorner = Corner.BOTTOM_LEFT
    private fun currentCorner(): Corner = activeCorner

    private fun screenW(): Int = wm.currentWindowMetrics.bounds.width()
    private fun screenH(): Int = wm.currentWindowMetrics.bounds.height()

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    /** Corner claim: raise windows, bind fresh pins, seed the launcher with the
     *  synthetic DOWN at the press point (the real DOWN stayed with the app under
     *  the corner — the spy was only observing until pilfer), then arm the timeout. */
    fun show(side: SpySide, downX: Float, downY: Float, screenW: Int, screenH: Int) {
        if (moduleContext == null) return // no module resources → no fan (fail closed)
        val km = host.getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) return
        val pm = host.getSystemService(PowerManager::class.java)
        if (pm?.isInteractive == false) return
        hideMorePanel(animated = false) // a fresh stroke always starts from the fan
        // The fan renders tiles from the cached list; if that list is the boot's locked-user
        // subset, refresh in the background so the NEXT stroke (and the 更多 panel) is whole.
        if (repo.cacheNeedsRefresh()) reloadApps("FAN_SHOW")
        activeCorner = if (side == SpySide.LEFT) Corner.BOTTOM_LEFT else Corner.BOTTOM_RIGHT
        raiseFanWindows()
        dock.previewMode = false
        val snap = RemotePrefs.read(prefs)
        dock.show(activeCorner, screenW, screenH, snap.fanIconCount, snap.fanRadiusDp, snap.fanAutoFillRecommend)
        lastTouchX = downX
        lastTouchY = downY
        pressureDetector.startListening(settleMs = 280L)
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, downX, downY, 0)
        dock.forward(down, 0)
        down.recycle()
        armTimeout() // reference :186-187 (beginGesture → postDelayed(timeout, GESTURE_TIMEOUT_MS))
    }

    /** A pilfered MOVE/UP in SCREEN coords — one continuous stream, so the
     *  release always arrives (this is what the old two-window handoff lost). */
    fun forward(ev: MotionEvent) {
        dock.forward(ev, 0)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - lastTouchX
                val dy = ev.y - lastTouchY
                if (kotlin.math.hypot(dx, dy) > 15f) {
                    pressureDetector.onMotionOccurred(180L)
                }
                lastTouchX = ev.x
                lastTouchY = ev.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressureDetector.stopListening()
            }
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = ev.x
                lastTouchY = ev.y
            }
        }
        armTimeout() // reference :194-195 (updateGesture re-arms the timeout every event)
    }

    /** Spy stream died mid-gesture (system stole the gesture, multi-finger,
     *  ACTION_CANCEL) → collapse, mirroring the engine's Cancel path. */
    fun cancelExternal() = retract("STREAM_END")

    /** Re-bind the fan's tiles from the current pins/apps without waiting for a new stroke. */
    private fun rebindFanIfBusy() {
        val snap = RemotePrefs.read(prefs)
        mainHandler.post {
            if (dock.isBusy) {
                dock.show(currentCorner(), screenW(), screenH(), snap.fanIconCount, snap.fanRadiusDp, snap.fanAutoFillRecommend)
            }
        }
    }

    private fun retract(source: String) {
        pressureDetector.stopListening()
        mainHandler.removeCallbacks(timeout)
        logger(Log.INFO, "FAN_RETRACT_$source", null)
        dock.forceRetract()
    }

    private fun onHeavyPressTriggered() {
        if (!fanWindowsUp || !dock.isBusy) return
        if (!pressureDetector.isTouchActive) return
        // 需注意手指停在app图标或更多上不能触发翻页
        if (dock.launcher.isHoveringItem) {
            logger(Log.DEBUG, "FAN_PRESSURE_IGNORED_HOVER index=${dock.launcher.currentHoveredIndex}", null)
            return
        }
        val turned = dock.nextPage()
        if (turned) {
            logger(Log.INFO, "FAN_PRESSURE_PAGE_TURN page=${dock.currentPage} total=${dock.totalPages}", null)
            performHeavyPressHaptic()
        }
    }

    private fun performHeavyPressHaptic() {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
            host.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            host.getSystemService(Vibrator::class.java)
        }
        if (vibrator != null && vibrator.hasVibrator()) {
            try {
                val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                vibrator.vibrate(effect)
            } catch (_: Throwable) {
                try {
                    val effect = VibrationEffect.createOneShot(35L, 180)
                    vibrator.vibrate(effect)
                } catch (_: Throwable) {
                    dock.launcher.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
            }
        } else {
            dock.launcher.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun armTimeout() {
        // Disabled: 用户要求“扇形面板弹出后不要过几秒就消失”，仅在点按外部、选中应用或熄屏时收起
        mainHandler.removeCallbacks(timeout)
    }

    // ---------------- tile building ----------------

    /** slide_gesture_list_item.xml (verbatim port) built in code —
     *  SlideGestureItemView padding=slide_gesture_item_padding (:5-7 of the layout),
     *  inner FrameLayout wrap (:9-17), CircleImageView launcher_app_item_icon_width.
     *  Direct construction: LSPosed loads our whole dex once, so this IS the same
     *  class identity GestureAppLauncher uses (ring drawing included). */
    private fun buildTile(item: GestureAppLauncher.AdapterItem): View {
        val res = context.resources
        val tile = SlideGestureItemView(context)
        val pad = res.getDimensionPixelSize(R.dimen.slide_gesture_item_padding)
        tile.setPadding(pad, pad, pad, pad)
        val inner = FrameLayout(context).apply {
            layoutParams = android.widget.RelativeLayout.LayoutParams(
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
                android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val icon = CircleImageView(context).apply {
            id = R.id.slide_icon // SlideGestureItemView:73 findViewById(R.id.slide_icon)
            layoutParams = FrameLayout.LayoutParams(
                res.getDimensionPixelSize(R.dimen.launcher_app_item_icon_width),
                res.getDimensionPixelSize(R.dimen.launcher_app_item_icon_width),
            )
        }
        when (item) {
            is GestureAppLauncher.AdapterItem.AppItem -> icon.setImageDrawable(repo.icon(item.app))
            GestureAppLauncher.AdapterItem.More ->
                runCatching { icon.setImageResource(R.drawable.icon_gesture_more_app) }
        }
        inner.addView(icon)
        tile.addView(inner)
        // ORIGINAL: the adapter sets a click listener on every tile (C2937F.m9712g :142 →
        // C2937F$a.onClick :56-69 → mo9283j(item, view, reason=1)) — same timing as the
        // factory itself, so taps work even while the fan is still expanding.
        tile.setOnClickListener { dock.launcher.clickSelect(item, tile) }
        return tile
    }

    // ---------------- windows (same pair as the legacy OverlayHost) ----------------

    private fun raiseFanWindows() {
        if (fanWindowsUp) return
        (dock.launcher.parent as? android.view.ViewGroup)?.removeView(dock.launcher)

        // scrim BELOW — pure dim, untouchable (framework small-window dim analogue)
        val s = View(context).apply {
            background = ColorDrawable(SCRIM_COLOR)
            alpha = 0f
        }
        wm.addView(s, fullScreenParams("bubble slide scrim", touchable = false))
        s.animate().alpha(1f).setDuration(FADE).start()
        scrim = s

        // canvas ABOVE, TOUCHABLE — AppLauncherWindow.m9248S :640-650 flags 16777984
        // has NO 0x10 NOT_TOUCHABLE; fresh void DOWN closes the panel verbatim
        // C2822g.onTouchEvent :381-393; icon children carry the click listener
        // (C2937F$a.onClick :56-69, wired in buildTile). The drag stroke itself
        // comes from the SPY, not from this window.
        val c = object : FrameLayout(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) dock.onVoidTouchDown()
                return true
            }
        }.apply {
            addView(
                dock.launcher,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        wm.addView(c, fullScreenParams("bubble slide gesture", touchable = true))
        canvas = c
        fanWindowsUp = true
    }

    private fun fadeOutFanWindows() {
        val s = scrim
        if (!fanWindowsUp) return
        if (s != null) {
            s.animate().alpha(0f).setDuration(FADE).withEndAction { tearFanWindowsNow() }.start()
        } else {
            tearFanWindowsNow()
        }
    }

    private fun tearFanWindowsNow() {
        mainHandler.removeCallbacks(timeout)
        canvas?.let { runCatching { wm.removeViewImmediate(it) } }
        scrim?.let { runCatching { wm.removeViewImmediate(it) } }
        canvas = null
        scrim = null
        fanWindowsUp = false
    }

    // ---------------- 更多应用 panel (方案 B: pinned on the current 小窗) ----------------

    private var moreCatcher: View? = null
    private var morePanel: FrameLayout? = null
    private var moreContent: PinManageModel? = null
    private var moreUp = false
    private var moreRect: Rect? = null

    /**
     * Raise the 固定管理 page (`PinManageView`) as an overlay ON TOP of whatever is on
     * screen — inside apps too — instead of opening the full-screen manage Activity.
     *
     * Rect: ALWAYS the screen-centred [defaultPanelRect] (62 % of the display, the same
     * footprint the launch default uses), never the bounds of the current小窗. Following
     * the window was tried first and dropped: this ROM's `MiuiFreeFormManager` stack list
     * comes back empty from SystemUI's uid (`stacks=0`) and its rect fallback returns
     * off-screen bounds, so the panel jumped around and the 长/宽 sliders looked
     * inconsistent. Centred + percentage sliders keeps those two controls predictable.
     *
     * Windows: the fan's existing pair pattern — a touchable full-screen catcher
     * (tap outside closes, light dim) plus the panel itself at [rect]. Both keep the
     * fan's type-2024 + trusted-overlay identity, so they stay visible inside apps.
     */
    private fun showMorePanel() {
        if (moreUp || moduleContext == null) return
        val km = host.getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) return
        val pm = host.getSystemService(PowerManager::class.java)
        if (pm?.isInteractive == false) return
        // Placement is screen-centred and independent of whatever小窗 is open; the only
        // inputs are the two size sliders (see applyPanelSize).
        val panelSnap = RemotePrefs.read(prefs)
        val rect = applyPanelSize(defaultPanelRect(), panelSnap)
        moreRect = Rect(rect)

        val dismissMode = panelSnap.panelDismissOutside
        val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
        var lastCatcherTapTime = 0L
        val catcher = object : FrameLayout(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    if (dismissMode == RemotePrefs.DISMISS_OUTSIDE_SINGLE) {
                        hideMorePanel()
                    } else {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastCatcherTapTime <= doubleTapTimeout) {
                            hideMorePanel()
                            lastCatcherTapTime = 0L
                        } else {
                            lastCatcherTapTime = now
                        }
                    }
                }
                return true
            }
        }.apply {
            background = ColorDrawable(MORE_SCRIM_COLOR)
            isClickable = true
        }
        val catcherAdded = runCatching {
            wm.addView(catcher, fullScreenParams("bubble more catcher", touchable = true))
        }
        if (catcherAdded.isFailure) {
            logger(Log.WARN, "MORE_CATCHER_FAILED", catcherAdded.exceptionOrNull())
            return
        }
        moreCatcher = catcher

        // The panel hosts the SAME page the fan's 更多 tile always opened: the manage
        // page (方案 B). With the miuix Compose refactor the page IS the Activity's page —
        // PinManageScreen(chrome = PANEL) draws the 56dp look-alike bar itself. The theme
        // wrapper is still needed: it pins the window's DayNight resource resolution for
        // the View islands (LetterIndexBar) inside the Compose tree.
        val themed = ContextThemeWrapper(context, R.style.Theme_BubbleDrawer)
        val content = PinManageModel(
            loadApps = { repo.all() },
            pinsOf = { pinStore.pins() },
            onOrderChange = { order -> pinStore.setPins(order) },
            recommendConfig = {
                val snap = RemotePrefs.read(prefs)
                snap.recommendEnabled to snap.recommendCount
            },
            loadRecommendations = { all, pinned, count ->
                com.repl.bubbledrawer.data.predict.AppPredictor.getRecommendations(
                    context, all, pinned, count,
                )
            },
        )
        // Panel window root FIRST, then the Compose content inside it: the view-tree
        // owners must be tagged on the window root too (see PanelContentFactory.create
        // doc) because compose resolves the lifecycle owner at the compose-view ROOT,
        // not on the ComposeView itself.
        val panel = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                    if (content.isSearching) {
                        content.cancelSearch()
                    } else {
                        hideMorePanel()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            background = GradientDrawable().apply {
                // MiuiMultiWindowUtils.FREEFORM_ROUND_CORNER = 25.8dp — match the ROM window
                cornerRadius = 26f * resources.displayMetrics.density
                setColor(themed.resources.getColor(R.color.fd_sys_color_surface_bright_default, null))
            }
            clipToOutline = true
            alpha = 0f
        }
        val composeView = PanelContentFactory.create(
            windowRoot = panel,
            context = themed,
            model = content,
            iconDp = panelSnap.panelIconDp,
            textSp = panelSnap.panelTextSp,
            onLaunch = { app -> launchFromPanel(app) },
            onClose = { hideMorePanel() },
            onFocusChange = { focusable ->
                val lp = panel.layoutParams as? WindowManager.LayoutParams ?: return@create
                if (focusable) {
                    lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                } else {
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                }
                runCatching { wm.updateViewLayout(panel, lp) }
            },
        )
        panel.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val panelAdded = runCatching { wm.addView(panel, panelParams("bubble more panel", rect)) }
        if (panelAdded.isFailure) {
            logger(Log.WARN, "MORE_PANEL_FAILED", panelAdded.exceptionOrNull())
            runCatching { wm.removeViewImmediate(catcher) }
            moreCatcher = null
            return
        }
        // Ground truth for "which page is in the panel?" — one line per rebuild. It has to come
        // from the model's own rebuild callback: the load is asynchronous (app list + the
        // recommendation pass), so logging right here and one frame later only ever printed
        // the empty initial state (items=0 letters=0) for a panel that was visibly full.
        content.onContentChanged = {
            val page = if (moreUp && moreContent === content) {
                content.describe(
                    themed.resources.getString(R.string.slide_launcher_tab_all_app),
                    themed.resources.getString(R.string.slide_launch_app_has_selected),
                )
            } else {
                "closed-before-rebuild"
            }
            panelLog("MORE_PANEL_TREE $page")
        }
        content.reload()
        panel.animate().alpha(1f).setDuration(FADE).start()
        morePanel = panel
        moreContent = content
        moreUp = true
        panelLog(
            "MORE_PANEL_SHOW " + rect.toShortString() + " screen=" + screenW() + "x" + screenH() +
                " pct=" + panelSnap.panelWidthPct + "x" + panelSnap.panelHeightPct +
                " icon=" + panelSnap.panelIconDp + " text=" + panelSnap.panelTextSp +
                " page=" + content.describe(
                    themed.resources.getString(R.string.slide_launcher_tab_all_app),
                    themed.resources.getString(R.string.slide_launch_app_has_selected),
                ),
        )
    }

    /** Panel diagnostics: LSPosed's module log (lagged) plus logcat for live reading. */
    private fun panelLog(message: String) {
        logger(Log.INFO, message, null)
        runCatching { Log.println(Log.INFO, "BubbleDrawer", message) }
    }

    private fun hideMorePanel(animated: Boolean = true) {
        if (!moreUp) return
        moreUp = false
        moreContent?.cancelSearch()
        moreContent?.release()
        moreContent = null
        moreCatcher?.let { runCatching { wm.removeViewImmediate(it) } }
        moreCatcher = null
        val panel = morePanel
        morePanel = null
        if (panel == null) return
        if (!animated) {
            runCatching { wm.removeViewImmediate(panel) }
            return
        }
        panel.animate().alpha(0f).setDuration(FADE)
            .withEndAction { runCatching { wm.removeViewImmediate(panel) } }
            .start()
        panelLog("MORE_PANEL_HIDE")
    }

    /** Panel app click → hand the very same spot to the app (Flyme: content swap). */
    private fun launchFromPanel(app: com.repl.bubbledrawer.pinyin.BubbleApp) {
        val rect = moreRect
        hideMorePanel(animated = false)
        val snap = RemotePrefs.read(prefs)
        val strategy: ILaunchStrategy =
            if (snap.freeform) {
                if (snap.flymeFreeformEnabled) FreeformLaunchStrategy() else FreeformLaunchStrategy(rect)
            } else FullscreenLaunchStrategy()
        val ok = runCatching { strategy.launch(context, app) }.getOrDefault(false)
        LaunchCountStore.increment(context, app.packageName, app.userId)
        com.repl.bubbledrawer.data.predict.AppPredictor.recordLaunch(context, app.packageName, app.userId)
        panelLog(
            "MORE_LAUNCH " + app.packageName + "#" + app.userId + " freeform=" + snap.freeform +
                " rect=" + (rect?.toShortString() ?: "-") + " ok=" + ok,
        )
    }

    /**
     * 面板长宽：屏幕百分比（0 = 默认 [DEFAULT_PANEL_PCT]），始终以屏幕正中为基准。
     * 两个轴互不影响：只拖宽度时高度保持基准值，反之亦然。
     */
    private fun applyPanelSize(base: Rect, snap: RemotePrefs.Snapshot): Rect {
        val sw = screenW()
        val sh = screenH()
        val w = if (snap.panelWidthPct == 0) base.width() else sw * snap.panelWidthPct / 100
        val h = if (snap.panelHeightPct == 0) base.height() else sh * snap.panelHeightPct / 100
        val l = ((sw - w) / 2).coerceAtLeast(0)
        val top = ((sh - h) / 2).coerceAtLeast(0)
        return Rect(l, top, l + w, top + h)
    }

    /**
     * 基准矩形：屏幕正中默认占地 —— 与启动小窗的默认尺寸一致，
     * 所以从面板里点应用时，应用正好落在面板原来的位置。
     */
    private fun defaultPanelRect(): Rect {
        val w = screenW()
        val h = screenH()
        val pw = (w * RemotePrefs.DEFAULT_PANEL_W_PCT / 100f).toInt()
        val ph = (h * RemotePrefs.DEFAULT_PANEL_H_PCT / 100f).toInt()
        return Rect((w - pw) / 2, (h - ph) / 2, (w + pw) / 2, (h + ph) / 2)
    }

    /** Same window class as the SPY: type 2024 (NAVIGATION_BAR_PANEL), trusted
     *  overlay, fitInsets 0. The previous TYPE_APPLICATION_OVERLAY variant got
     *  mPolicyVisibility=false on HyperOS inside apps (isVisibleByPolicy=false —
     *  MIUI hides foreign APPLICATION_OVERLAY layers) even though WMS accepted
     *  the add; the 2024 spy windows proved visible in the same dump
     *  (frame=[0,2357][299,2656]). MATCH_PARENT + LAYOUT_IN_SCREEN + NO_LIMITS +
     *  cutout ALWAYS keeps origin == physical (0,0), so forwarded spy coordinates
     *  line up 1:1 (OverlayHost comment: getRealSize parity). */
    private fun fullScreenParams(title: String, touchable: Boolean) =
        baseParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            title,
            touchable,
        )

    /**
     * 更多面板窗口：按 [rect] 定位（定位窗口，不是全屏）。
     * Same type-2024 trusted-overlay identity as the fan; `LAYOUT_NO_LIMITS` +
     * `setFitInsetsTypes(0)` keep `x/y` in physical screen coordinates, so the panel
     * lands exactly on the centred rect [applyPanelSize] computed.
     */
    private fun panelParams(title: String, rect: Rect) =
        baseParams(rect.width(), rect.height(), title, touchable = true).apply {
            x = rect.left
            y = rect.top
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

    private fun baseParams(width: Int, height: Int, title: String, touchable: Boolean) =
        WindowManager.LayoutParams(
            width,
            height,
            CornerInputMonitor.CORNER_WINDOW_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.title = title
            windowAnimations = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            @Suppress("DEPRECATION")
            systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            runCatching { setFitInsetsTypes(0) }
            runCatching {
                WindowManager.LayoutParams::class.java
                    .getMethod("setTrustedOverlay")
                    .invoke(this)
            }
        }

    fun destroy() {
        screenOffReceiver?.let { runCatching { host.unregisterReceiver(it) } }
        screenOffReceiver = null
        unlockReceiver?.let { runCatching { host.unregisterReceiver(it) } }
        unlockReceiver = null
        syncFromAppReceiver?.let { runCatching { host.unregisterReceiver(it) } }
        syncFromAppReceiver = null
        pinsListener?.let { runCatching { prefs.unregisterOnSharedPreferenceChangeListener(it) } }
        pinsListener = null
        mainHandler.removeCallbacks(timeout)
        pressureDetector.destroy()
        hideMorePanel(animated = false)
        tearFanWindowsNow()
        dock.destroy()
    }

    private companion object {
        val SCRIM_COLOR = Color.parseColor("#73000000") // fd_sys_color_scrim_default night

        /** 更多面板外圈轻遮罩：比扇子的 scrim 浅，面板仍是"小窗内容"而不是模态弹窗。 */
        val MORE_SCRIM_COLOR = Color.parseColor("#33000000")

        /** 默认面板占地（屏幕百分比），两个轴都用它 —— 与启动小窗的默认尺寸对齐。 */
        const val DEFAULT_PANEL_PCT = 62
        const val FADE = 130L
        const val GESTURE_TIMEOUT_MS = 5_000L // reference CornerRadialOverlayView :1000
    }
}
