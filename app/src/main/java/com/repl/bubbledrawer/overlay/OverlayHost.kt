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
 *  2. Scrim window (shown while the fan is open) — full-screen dim behind the fan.
 *     The original's dim comes from the FRAMEWORK small-window (not an APK constant;
 *     APK token fd_sys_color_scrim_default is #1a000000 day / #73000000 night — we
 *     use the night value as it matches the observed original visuals). It also
 *     forwards touches to the launcher and force-retracts on any UP, which keeps
 *     preview mode dismissible.
 *  3. Canvas window (added above the scrim while open) — FLAG_NOT_TOUCHABLE, hosts
 *     GestureAppLauncher; analog of AppLauncherWindow's own window (m9248S
 *     :482-499 MATCH_PARENT, flags NOT_FOCUSABLE|LAYOUT_IN_SCREEN, RGBA,
 *     title "window mode slide gesture").
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
        val dm = context.resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels

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
                    // window (touch capture binds at DOWN) and arrives via onMove —
                    // the scrim only receives *new* touches (tap-outside dismissal).
                }

                override fun onMove(ev: MotionEvent, screenX: Float, screenY: Float) {
                    dock.forward(ev, 0)
                }

                override fun onEnd(ev: MotionEvent?, cancelled: Boolean) {
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

        // --- scrim BELOW: dim + touch forward + dismiss-on-up (preview-safe) ---
        val s = object : View(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> dock.forward(event, 0)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dock.forward(event, 0)
                        dock.forceRetract()   // idempotent; covers tap-outside & preview
                    }
                }
                return true
            }
        }.apply {
            background = android.graphics.drawable.ColorDrawable(SCRIM_COLOR)
            alpha = 0f
        }
        wm.addView(s, fullScreenParams("window mode slide scrim", touchable = true))
        s.animate().alpha(1f).setDuration(FADE).start()
        scrim = s

        // --- canvas ABOVE the scrim, NOT touchable (so touches land on scrim) ---
        val c = android.widget.FrameLayout(context).apply {
            addView(
                dock.launcher,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        wm.addView(c, fullScreenParams("window mode slide gesture", touchable = false))
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                if (touchable) 0 else (
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    ),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.title = title
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
