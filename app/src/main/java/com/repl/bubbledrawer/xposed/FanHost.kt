package com.repl.bubbledrawer.xposed

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.bubble.BubbleDockController
import com.repl.bubbledrawer.bubble.GestureAppLauncher
import com.repl.bubbledrawer.bubble.SlideGestureItemView
import com.repl.bubbledrawer.data.AppRepository
import com.repl.bubbledrawer.data.PinBackend
import com.repl.bubbledrawer.data.PinStore
import com.repl.bubbledrawer.data.PrefsPinBackend
import com.repl.bubbledrawer.gesture.Corner
import com.repl.bubbledrawer.gesture.SpySide
import com.repl.bubbledrawer.launch.FreeformLaunchStrategy
import com.repl.bubbledrawer.launch.FullscreenLaunchStrategy
import com.repl.bubbledrawer.launch.ILaunchStrategy
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
    private val pinStore = PinStore(object : PinBackend {
        override fun read(): String = prefs.getString(PrefsPinBackend.KEY, "").orEmpty()
        override fun write(value: String) { prefs.edit().putString(PrefsPinBackend.KEY, value).apply() }
    })
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

    private var scrim: View? = null
    private var canvas: FrameLayout? = null
    private var fanWindowsUp = false

    private val timeout = Runnable { if (dock.isBusy) retract("TIMEOUT") }
    private var screenOffReceiver: android.content.BroadcastReceiver? = null
    private var pinsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob(),
    )

    init {
        if (moduleContext == null) logger(Log.ERROR, "FAN_MODULE_CONTEXT_FAILED", null)
        dock.onShownChanged = { shown -> if (!shown) fadeOutFanWindows() }
        scope.launch { runCatching { repo.loadAll() } } // warm the list for the first swipe
        // pins edits while the fan is open → live rebind (reference :85-96 watch pattern)
        pinsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PrefsPinBackend.KEY) mainHandler.post { if (dock.isBusy) dock.show(currentCorner(), screenW(), screenH()) }
        }
        runCatching { prefs.registerOnSharedPreferenceChangeListener(pinsListener) }
        // SCREEN_OFF → retract (original mo864f :1049-1056 → m9254Z)
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { mainHandler.post { retract("SCREEN_OFF") } }
        }
        runCatching {
            host.registerReceiver(r, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        }.onSuccess { screenOffReceiver = r }
    }

    private var activeCorner = Corner.BOTTOM_LEFT
    private fun currentCorner(): Corner = activeCorner

    private fun screenW(): Int = wm.currentWindowMetrics.bounds.width()
    private fun screenH(): Int = wm.currentWindowMetrics.bounds.height()

    /** Corner claim: raise windows, bind fresh pins, seed the launcher with the
     *  synthetic DOWN at the press point (the real DOWN stayed with the app under
     *  the corner — the spy was only observing until pilfer), then arm the timeout. */
    fun show(side: SpySide, downX: Float, downY: Float, screenW: Int, screenH: Int) {
        if (moduleContext == null) return // no module resources → no fan (fail closed)
        activeCorner = if (side == SpySide.LEFT) Corner.BOTTOM_LEFT else Corner.BOTTOM_RIGHT
        raiseFanWindows()
        dock.previewMode = false
        dock.show(activeCorner, screenW, screenH)
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
        armTimeout() // reference :194-195 (updateGesture re-arms the timeout every event)
    }

    /** Spy stream died mid-gesture (system stole the gesture, multi-finger,
     *  ACTION_CANCEL) → collapse, mirroring the engine's Cancel path. */
    fun cancelExternal() = retract("STREAM_END")

    private fun retract(source: String) {
        mainHandler.removeCallbacks(timeout)
        logger(Log.INFO, "FAN_RETRACT_$source", null)
        dock.forceRetract()
    }

    private fun armTimeout() {
        mainHandler.removeCallbacks(timeout)
        mainHandler.postDelayed(timeout, GESTURE_TIMEOUT_MS)
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

    /** Same window class as the SPY: type 2024 (NAVIGATION_BAR_PANEL), trusted
     *  overlay, fitInsets 0. The previous TYPE_APPLICATION_OVERLAY variant got
     *  mPolicyVisibility=false on HyperOS inside apps (isVisibleByPolicy=false —
     *  MIUI hides foreign APPLICATION_OVERLAY layers) even though WMS accepted
     *  the add; the 2024 spy windows proved visible in the same dump
     *  (frame=[0,2357][299,2656]). MATCH_PARENT + LAYOUT_IN_SCREEN + NO_LIMITS +
     *  cutout ALWAYS keeps origin == physical (0,0), so forwarded spy coordinates
     *  line up 1:1 (OverlayHost comment: getRealSize parity). */
    private fun fullScreenParams(title: String, touchable: Boolean) =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
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
        pinsListener?.let { runCatching { prefs.unregisterOnSharedPreferenceChangeListener(it) } }
        pinsListener = null
        mainHandler.removeCallbacks(timeout)
        tearFanWindowsNow()
        dock.destroy()
    }

    private companion object {
        val SCRIM_COLOR = Color.parseColor("#73000000") // fd_sys_color_scrim_default night
        const val FADE = 130L
        const val GESTURE_TIMEOUT_MS = 5_000L // reference CornerRadialOverlayView :1000
    }
}
