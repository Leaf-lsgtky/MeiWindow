package com.repl.bubbledrawer.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import com.repl.bubbledrawer.bubble.BubbleConfig
import com.repl.bubbledrawer.bubble.BubbleDockController
import com.repl.bubbledrawer.gesture.Corner
import com.repl.bubbledrawer.gesture.CornerGestureDetector
import com.repl.bubbledrawer.gesture.CornerZone

/**
 * Dual-window replica of the original two-window structure:
 *
 *  1. Zone windows — tiny touchable windows at the bottom corners. Analog of
 *     flyme's SlideGestureForwarding capture window (and its visible
 *     window_slide_indicator 20x64dp pill). They are the ONLY windows taking
 *     touch; everything outside stays with the apps below.
 *  2. Canvas window — full-screen FLAG_NOT_TOUCHABLE, hosts the
 *     GestureAppLauncher. Analog of AppLauncherWindow's own window:
 *     m9248S (:482-499) MATCH_PARENT×MATCH_PARENT, flags 16777984
 *     (= FLAG_NOT_FOCUSABLE|FLAG_LAYOUT_IN_SCREEN|… — public equivalents below),
 *     RGBA format, gravity TOP|LEFT(51)/TOP|RIGHT(53) per corner.
 *
 * Events travel zone → detector → launcher.forwardEvent, mirroring the original
 * forwarding into GestureAppLauncher.m9740w (:893-927). Coordinates are remapped
 * from zone-window space to screen space before forwarding.
 */
class OverlayHost(
    private val context: Context,
    private val config: BubbleConfig,
    private val dock: BubbleDockController,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density

    private var canvas: android.widget.FrameLayout? = null
    private val zoneViews = ArrayList<Pair<CornerZone, ZoneView>>()
    var screenW = 0; private set
    var screenH = 0; private set

    /** Launch/tear-down entry point. */
    @SuppressLint("WrongConstant")
    fun attach() {
        detach()
        val dm = context.resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        // --- canvas (NOT_TOUCHABLE, transparent, full-screen) ---
        canvas = android.widget.FrameLayout(context).apply {
            addView(
                dock.launcher,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        wm.addView(
            canvas,
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "window mode slide gesture" // same title as original m9248S :495
            },
        )

        // --- zone windows ---
        for (zone in config.zones(screenW, screenH, density)) {
            val z = ZoneView(context, zone)
            z.listener = object : ZoneView.Listener {
                override fun onTrigger(corner: Corner, ev: MotionEvent) {
                    dock.show(corner, screenW, screenH)
                    // replay DOWN in screen coords, then this MOVE — like the original
                    // window's m9740w saw DOWN first (:906-909)
                    val down = MotionEvent.obtain(ev)
                    down.action = MotionEvent.ACTION_DOWN
                    dock.forward(down, 0)
                    down.recycle()
                }

                override fun onMove(ev: MotionEvent, screenX: Float, screenY: Float) {
                    // always forward once accepted — launcher guards internally
                    // (centerY==-1 → DOWN path), matching m9740w receiving every event
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
        canvas?.let { runCatching { wm.removeViewImmediate(it) } }
        canvas = null
    }

    /** Expand once immediately (settings-page preview), like a real corner swipe. */
    fun preview(corner: Corner) {
        dock.show(corner, screenW, screenH)
    }

    fun refresh() = attach()
}

/**
 * Touch capture for one corner zone. Pure event state machine — same acceptance
 * rules as [CornerGestureDetector]; remaps to screen coords for forwarding.
 */
class ZoneView(context: Context, private val zone: CornerZone) : android.view.View(context) {

    interface Listener {
        fun onTrigger(corner: Corner, ev: MotionEvent)
        fun onMove(ev: MotionEvent, screenX: Float, screenY: Float)
        fun onEnd(ev: MotionEvent?, cancelled: Boolean)
    }

    var listener: Listener? = null
    private val detector = CornerGestureDetector(listOf(zone))
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
                if (feed == CornerGestureDetector.Feed.ACCEPT && !accepted) {
                    accepted = true
                    listener?.onTrigger(detector.corner ?: return true, event)
                }
                if (accepted) {
                    // rebuild an event in screen coordinates for the launcher
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
