package com.repl.bubbledrawer.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.bubble.BubbleConfig
import com.repl.bubbledrawer.bubble.BubbleDockController
import com.repl.bubbledrawer.gesture.Corner
import com.repl.bubbledrawer.gesture.CornerGestureDetector
import com.repl.bubbledrawer.gesture.CornerZone

/**
 * Window structure replicating the original split (with our non-privileged substitutes):
 *
 *  1. Zone windows (resident) — tiny touchable corner windows, analog of flyme's
 *     SlideGestureForwarding capture window: the only always-on touch takers.
 *  2. Scrim window (while the fan is open) — pure dim BELOW the canvas. The
 *     original's dim comes from the FRAMEWORK small-window (not an APK constant;
 *     APK token fd_sys_color_scrim_default is #1a000000 day / #73000000 night — we
 *     use the night value as it matches the observed original visuals).
 *  3. Canvas window (above the scrim) — hosts GestureAppLauncher and IS TOUCHABLE,
 *     like the original's own window: AppLauncherWindow.m9248S :640-650 uses
 *     flags=16777984 (LAYOUT_IN_SCREEN|LAYOUT_NO_LIMITS|0x1000000) — bit 0x10
 *     NOT_TOUCHABLE is NOT set — so the fan window receives touches itself, and
 *     the icon children are clickable (C2937F$a.onClick :57-68 → mo9283j(item, view,
 *     1) = "tap an icon, launch it"). Non-child touches are forwarded into the
 *     launcher (aim + release-on-icon launch) and any UP retracts, which also
 *     keeps preview mode dismissible.
 *
 * Coordinate spaces must agree: events forwarded to the launcher have to be in
 * the SAME space the launcher laid itself out in, so BOTH overlay windows carry
 * LAYOUT_IN_SCREEN|LAYOUT_NO_LIMITS (origin = real display 0,0 — without it the
 * window origin sits below the status bar and every forwarded y was short by the
 * status-bar height → aiming band never matched → "swipe onto an icon and release"
 * silently failed, user-feedback ①), and screen metrics are the REAL ones, like
 * the original's getRealSize (SlideGestureForwarding.calcGesturePosition :66-75),
 * not displayMetrics.heightPixels (which excludes the navbar → centerY off).
 *
 * Order matters: scrim is added BEFORE the canvas so the fan is never dimmed.
 */
class OverlayHost(
    private val context: Context,
    private val config: BubbleConfig,
    private val dock: BubbleDockController,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density

    private var scrim: View? = null
    private var canvas: android.widget.FrameLayout? = null
    private val zoneViews = ArrayList<Pair<CornerZone, ZoneView>>()
    var screenW = 0; private set
    var screenH = 0; private set

    private var fanWindowsUp = false

    init {
        dock.onShownChanged = { shown -> if (!shown) fadeOutFanWindows() }
    }

    @SuppressLint("WrongConstant")
    fun attach() {
        detach()
        // REAL metrics (include navbar), like the original
        // SlideGestureForwarding.calcGesturePosition :66-75 (getRealSize → setCenterPosition(y)).
        val b = wm.currentWindowMetrics.bounds
        screenW = b.width()
        screenH = b.height()

        for (zone in config.zones(screenW, screenH, density)) {
            val z = ZoneView(context, zone)
            z.listener = object : ZoneView.Listener {
                override fun onTrigger(corner: Corner, ev: MotionEvent) {
                    raiseFanWindows()
                    dock.show(corner, screenW, screenH)
                    // the original window saw a DOWN first (m9740w :906-909) then MOVEs
                    val down = MotionEvent.obtain(ev)
                    down.action = MotionEvent.ACTION_DOWN
                    dock.forward(down, 0)
                    down.recycle()
                    dock.forward(ev, 0)
                    // NOTE: the rest of this gesture keeps being routed to the zone
                    // window (touch capture binds at DOWN) and arrives via onMove/onEnd.
                    // The scrim is NOT_TOUCHABLE; the canvas above receives only *new*
                    // touch sequences (icon taps, void-tap dismissal).
                }

                override fun onMove(ev: MotionEvent, screenX: Float, screenY: Float) {
                    dock.forward(ev, 0)
                }

                override fun onEnd(ev: MotionEvent?, cancelled: Boolean) {
                    // UP: the launcher decides — verbatim dispatchTouchEvent :185-189
                    // (mWasForwarding = onTouchForwarded(ev) || !onForwardingStopped():
                    //  the RIGHT side runs only when forwarding returns FALSE) +
                    //  m9729p :531-556:
                    //  • release OVER an icon band → selectHovered (:554) returns false
                    //    → onForwardingStopped → m9245P → m9738l COLLAPSE, app launches
                    //    (user-feedback ① "拖出后滑到 app 上松手直接打开").
                    //  • dragged BACK toward the corner: with hover -1, onScroll
                    //    :817-823 latched cancelFlag → UP hits m9729p :544-549
                    //    onGestureCanceled(0) → returns false → same COLLAPSE chain.
                    //    That is user-feedback #8 "拉出面板后往回拉还能收起面板";
                    //    our dock's onGestureCanceled runs retractAfterAction(), so the
                    //    collapse fires through the forwarded UP itself.
                    //  • release over plain void: m9729p returns TRUE (:551-553) →
                    //    short-circuit, forwarding CONTINUES, panel STAYS open
                    //    (original behaviour). It closes on the NEXT void DOWN (canvas
                    //    window, C2822g.onTouchEvent :381-393) or BACK key (:343-357).
                    // ACTION_CANCEL: m9740w :923-925 returns false, no callback fired
                    // (m9729p not reached) → host tears the windows down here.
                    ev?.let { dock.forward(it, 0) }
                    if (cancelled) dock.forceRetract()
                }
            }
            val lp = WindowManager.LayoutParams(
                (zone.right - zone.left).toInt().coerceAtLeast(1),
                (zone.bottom - zone.top).toInt().coerceAtLeast(1),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = zone.left.toInt()
                y = zone.top.toInt()
                title = "window mode slide zone"
            }
            wm.addView(z, lp)
            zoneViews.add(zone to z)
        }
    }

    fun detach() {
        zoneViews.forEach { runCatching { wm.removeViewImmediate(it.second) } }
        zoneViews.clear()
        tearFanWindowsNow()
    }

    // ---------------- fan (scrim + canvas) ----------------

    private fun raiseFanWindows() {
        if (fanWindowsUp) return
        // launcher must be reparented into a fresh canvas
        (dock.launcher.parent as? android.view.ViewGroup)?.removeView(dock.launcher)

        // --- scrim BELOW: pure dim (canvas above is the touch target now) ---
        val s = View(context).apply {
            background = android.graphics.drawable.ColorDrawable(SCRIM_COLOR)
            alpha = 0f
        }
        wm.addView(s, fullScreenParams("window mode slide scrim", touchable = false))
        s.animate().alpha(1f).setDuration(FADE).start()
        scrim = s

        // --- canvas ABOVE the scrim, TOUCHABLE like the original fan window ---
        // (AppLauncherWindow.m9248S :640-650: flags=16777984 — NOT_TOUCHABLE bit 0x10
        // absent — and the window is MATCH_PARENT, so the open panel owns the screen's
        // touches; exactly what the user sees in flyme: everything behind is dim/dead.)
        //  • icon children carry click listeners (added in BubbleDockController.itemView,
        //    C2937F$a.onClick :56-69 → mo9283j(item, view, 1)) → tap an icon = launch.
        //  • a FRESH DOWN on void closes the panel — verbatim C2822g.onTouchEvent
        //    :381-393 (DOWN → m9254Z → m9245P → retract; ACTION_POINTER_DOWN :383-385
        //    returns true without closing).
        //  • the corner-drag stroke does NOT come through here: its DOWN bound to the
        //    zone window and keeps arriving via onMove/onEnd.
        val c = object : android.widget.FrameLayout(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) dock.onVoidTouchDown()
                return true   // consume the rest of the stroke (original returns true at :392)
            }
        }.apply {
            addView(
                dock.launcher,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        wm.addView(c, fullScreenParams("window mode slide gesture", touchable = true))
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
        canvas?.let { runCatching { wm.removeViewImmediate(it) } }
        scrim?.let { runCatching { wm.removeViewImmediate(it) } }
        canvas = null
        scrim = null
        fanWindowsUp = false
    }

    private fun fullScreenParams(title: String, touchable: Boolean) =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // base mirrors AppLauncherWindow.m9248S :646 flags=16777984
            // (LAYOUT_NO_LIMITS|LAYOUT_INSET_DECOR|…, NOT_TOUCHABLE bit 0x10 absent);
            // ours swaps NOT_FOCUSABLE in since we must never steal app focus.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.title = title
            // original :661/667/670: LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES (=1)
            layoutInDisplayCutoutMode =
                if (android.os.Build.VERSION.SDK_INT >= 28)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else 0
        }

    /** Expand once immediately (settings-page preview), like a real corner swipe. */
    fun preview(corner: Corner) {
        dock.previewMode = true
        raiseFanWindows()
        dock.show(corner, screenW, screenH)
    }

    /** SCREEN_OFF → retract the fan (original mo864f :1049-1056 → m9254Z). */
    fun collapseAll() = dock.forceRetract()

    fun refresh() = attach()

    companion object {
        // framework small-window dim is NOT an APK constant; nearest APK token is
        // fd_sys_color_scrim_default night #73000000 (day #1a000000) — night value
        // matches the observed original screenshots.
        private val SCRIM_COLOR = android.graphics.Color.parseColor("#73000000")
        private const val FADE = 130L
    }
}

/**
 * Touch capture for one corner zone. Acceptance rules from [CornerGestureDetector];
 * events remapped to screen coords before forwarding (zone window is its own coord space).
 */
class ZoneView(context: Context, private val zone: CornerZone) : View(context) {

    interface Listener {
        fun onTrigger(corner: Corner, ev: MotionEvent)
        fun onMove(ev: MotionEvent, screenX: Float, screenY: Float)
        fun onEnd(ev: MotionEvent?, cancelled: Boolean)
    }

    var listener: Listener? = null
    // thresholds in px: flyme slide trigger = slide_trigger_scroll_distance 50dp (:944);
    // diagonal component 20dp per design spec §4.2
    private val d = context.resources.displayMetrics.density
    private val detector = CornerGestureDetector(
        zones = listOf(zone),
        triggerDistancePx = 50f * d,
        minSidePx = 20f * d,
    )
    private var tracking = false
    private var accepted = false
    private val loc = IntArray(2)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        getLocationOnScreen(loc)
        val sx = event.x + loc[0]
        val sy = event.y + loc[1]
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                detector.onDown(sx, sy)
                tracking = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val feed = detector.onMove(sx, sy)
                val cornerNow = detector.corner
                if (feed == CornerGestureDetector.Feed.ACCEPT && !accepted && cornerNow != null) {
                    accepted = true
                    val re = MotionEvent.obtain(event)
                    re.offsetLocation(loc[0].toFloat(), loc[1].toFloat())
                    listener?.onTrigger(cornerNow, re)
                    re.recycle()
                }
                if (accepted) {
                    val re = MotionEvent.obtain(event)
                    re.offsetLocation(loc[0].toFloat(), loc[1].toFloat())
                    listener?.onMove(re, sx, sy)
                    re.recycle()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val up = event.actionMasked == MotionEvent.ACTION_UP
                if (accepted) {
                    val re = MotionEvent.obtain(event)
                    re.offsetLocation(loc[0].toFloat(), loc[1].toFloat())
                    listener?.onEnd(re, cancelled = !up)
                    re.recycle()
                }
                detector.onUp(); detector.onCancel()
                tracking = false
                accepted = false
                return true
            }
        }
        return false
    }
}
