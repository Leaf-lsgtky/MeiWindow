package com.repl.bubbledrawer.xposed

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.input.InputManager
import android.hardware.input.InputManagerGlobal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.InputMonitor
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import com.repl.bubbledrawer.gesture.AdaptiveSpyGestureConfig
import com.repl.bubbledrawer.gesture.CornerGestureEngine
import com.repl.bubbledrawer.gesture.CornerSector
import com.repl.bubbledrawer.gesture.CornerTriggerRegion
import com.repl.bubbledrawer.gesture.SpyAction
import com.repl.bubbledrawer.gesture.SpyGestureConfig
import com.repl.bubbledrawer.gesture.SpyPhase
import com.repl.bubbledrawer.gesture.SpySide
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.hypot

/**
 * Corner-stroke capture INSIDE the SystemUI process, with TWO transports.
 *
 * ── A. PRIMARY: a real gesture monitor (`InputManagerGlobal.monitorGestureInput`
 *      + `InputEventReceiver` on the main looper) ────────────────────────────────
 *
 * This is the transport the MiuiBackGestureHook reference uses on this exact device.
 *
 * WHY IT REPLACES THE OLD SELF-MADE SPY VIEW (device log, 2026-10-08):
 * the module used to add its own window — type 2024 (NAVIGATION_BAR_PANEL),
 * `setTrustedOverlay()`, `inputFeatures |= INPUT_FEATURE_SPY` — and read the stroke
 * from `View.onTouchEvent`. Over the launcher that works, but with an APP in the
 * foreground the stroke died 6–22 ms after ACTION_DOWN:
 *
 *     SPY_RIGHT_DOWN side=RIGHT x=199.0 y=237.0 wh=299/299 inBox=true eligible=true
 *     SPY_RIGHT_SYSTEM_CANCEL tracking=true claimed=false                 (+9 ms)
 *
 * i.e. the input dispatcher dropped the spy window from the touch state long before
 * the diagonal claim thresholds (`inward ≥ max(1.75·slop, 14dp)`, `upward ≥
 * max(0.5·slop, 4dp)`) could be reached — which is exactly why the drawer only ever
 * opened over the launcher.
 *
 * Two hooks were previously built to chase that drop; BOTH were chasing nothing on
 * this ROM and are gone with this change:
 *   • an ACTION_DOWN gate inside the native back plugin — `EdgeBackGestureHandler`
 *     has no motion handling left on this build (R8 moved it into the plugin), and
 *   • a guard on `android.view.InputMonitor.pilferPointers()` — never called during a
 *     stroke (zero PILFER_ANY lines in the module log). There is not even a
 *     `[Gesture Monitor] edge-swipe` window in `dumpsys input`, so SystemUI's AOSP
 *     edge-back monitor is simply not the arbiter here; MiuiHome's touchable
 *     `GestureStubView` side window plus MIUI input redirection is.
 *
 * HOW THE MONITOR FIXES IT: `InputManagerService.monitorGestureInput` on this build
 * ALWAYS builds a `GestureMonitorSpyWindow` whose `InputWindowHandle.inputConfig` is
 * `SPY | DO_NOT_PILFER` (device `services.jar` →
 * com.android.server.input.InputManagerService:646-672 +
 * GestureMonitorSpyWindow.java:35; see hiddenapi/.../InputManagerGlobal.java). A
 * gesture monitor therefore keeps receiving the whole stream even while another
 * window takes the pointers — precisely what "observe first, claim later" needs.
 * (`dumpsys input` confirms the pattern: SystemUI's own `[Gesture Monitor]
 * MultiTaskSwitch` carries `DO_NOT_PILFER`, while our old plain SPY window did not.)
 *
 * The monitor's touchable region is the whole display, so the DOWN gate is a
 * screen-space corner-box test ([sideFor]) instead of a window hit test. The engine
 * is unchanged: the stream is observed, and only claimed — with `pilferPointers()` —
 * once the diagonal thresholds are met, after which the fan receives the rest of the
 * stroke as ONE continuous sequence.
 *
 * ── B. FALLBACK: the original SPY view windows ────────────────────────────────
 *
 * Kept verbatim (FlymeFreeform reference hook/SystemUiCornerInputMonitor.kt :200-223
 * window params, :236-359 gesture view, :170-184 pilfer) for the case where the
 * monitor API is unavailable — no MONITOR_INPUT permission, a foreign ROM, or a
 * future MIUI change to `monitorGestureInput`. On the launcher this transport
 * already worked, so a failed monitor creation degrades instead of disabling the
 * feature. If the monitor is active the fallback windows are never added, so the two
 * can never fight over the same stroke.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class CornerInputMonitor(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val logger: (Int, String, Throwable?) -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)!!
    private val inputManager = context.getSystemService(InputManager::class.java)!!
    private val keyguardManager = context.getSystemService(KeyguardManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    /** Returns true only if the display is interactive and the keyguard is NOT locked. */
    private fun isUnlockedAndInteractive(): Boolean {
        val interactive = runCatching { powerManager?.isInteractive != false }.getOrDefault(true)
        if (!interactive) return false
        val locked = runCatching { keyguardManager?.isKeyguardLocked == true }.getOrDefault(false)
        return !locked
    }

    /** a stroke that moved no further than this counts as a tap at release */
    private val tapSlopPx = touchSlop * 1.5f


    /** uptime of the last INJECTED event that was not ours (deviceId < 0) */
    @Volatile
    private var lastForeignInjectedUptime = 0L

    /**
     * Last REAL (non-injected) DOWN this monitor saw, whatever the corner gate decided about it.
     * Only used to make the log honest: an injected tap can then be read against the touch that
     * preceded it — "MiuiHome handing back a stream the app never got" versus "a duplicate of a
     * tap that already landed".
     */
    private var lastRealDownUptime = 0L
    private var lastRealDownX = 0f
    private var lastRealDownY = 0f

    /** how many foreign injected events arrived during the stroke currently being tracked */
    private var foreignInjectionsInStroke = 0

    /**
     * Invisible, non-touchable full-screen window used ONLY to read the IME inset (see
     * [imeVisibleHeightPx]). Created lazily on the first corner DOWN and removed in [dispose]
     * so a hot reload cannot leave a stale window behind.
     */
    private var insetProbe: View? = null

    /** uptime of our own injected passthrough tap, so we do not read it back as foreign */
    @Volatile
    private var lastOwnInjectUptime = 0L

    private val getViewRootImplMethod: Method =
        View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }
    private val getInputTokenMethod: Method? = runCatching {
        Class.forName("android.view.ViewRootImpl").getMethod("getInputToken")
    }.getOrNull()
    private val pilferPointersMethod: Method =
        InputManager::class.java.getDeclaredMethod("pilferPointers", IBinder::class.java)

    /** InputManager.injectInputEvent(InputEvent, int) — @hide, INJECT_EVENTS (SystemUI holds it). */
    private val injectInputEventMethod: Method? = runCatching {
        InputManager::class.java.getMethod(
            "injectInputEvent",
            android.view.InputEvent::class.java,
            Int::class.javaPrimitiveType,
        )
    }.getOrNull()

    private var settings = RemotePrefs.read(prefs)
    private var fan: FanHost? = null

    // ------------------------------------------------------------ transport A state

    private var monitor: InputMonitor? = null
    private var receiver: InputEventReceiver? = null
    private var stroke: Stroke? = null

    /**
     * OBSERVE-FIRST, CLAIM-ON-TRAVEL — the design the device forced on us.
     *
     * A stroke inside the corner sector is NEVER taken at DOWN any more. Reasons, in order of
     * how much they cost us:
     *
     *  1. A spy monitor cannot stop the dispatcher from publishing the real DOWN to the app, so
     *     a DOWN-time pilfer leaves the app holding an unfinished gesture (DOWN … CANCEL). The
     *     only way to give that app a click afterwards is a SECOND, synthetic tap — and for a
     *     target that acts on DOWN (a keyboard: tapping the bottom-left key typed the character
     *     twice, confirmed on device) that is a double input, not a repair.
     *  2. MiuiHome answers a DOWN-time pilfer with its own delayed passthrough, so two
     *     independent injectors were racing over the same tap.
     *
     * Observing instead costs nothing we cannot pay: this monitor carries the DO_NOT_PILFER
     * bit, so even after MiuiHome pilfers the stream (~9 ms after DOWN, inside its bottom
     * band) the dispatcher keeps publishing the rest of the gesture to us. The stroke therefore
     * stays alive across that CANCEL, and we pilfer once — in [Stroke.move], on
     * `SpyAction.Activate`, i.e. only after the finger has travelled far enough to be a drawer
     * gesture. Taps are then never ours: the app serves them itself, and we inject nothing.
     *
     * The launcher's HOME commit needs MORE upward travel than our activation threshold
     * (`down_y - current_y > record_area_height_px`, measured take-over range 21-24 dp versus
     * our ~14 dp), so claiming on travel still beats it to the punch.
     */

    /** True while the gesture-monitor transport owns corner capture. */
    var monitorActive: Boolean = false
        private set

    var outsideTapHandler: ((MotionEvent) -> Boolean)? = null

    fun pilfer(): Boolean = monitor?.let {
        runCatching { it.pilferPointers(); true }.getOrDefault(false)
    } ?: false


    // ------------------------------------------------------------ transport B state

    private val inputFeaturesField: Field =
        WindowManager.LayoutParams::class.java.getField("inputFeatures").apply { isAccessible = true }
    private val setTrustedOverlayMethod: Method =
        WindowManager.LayoutParams::class.java.getMethod("setTrustedOverlay").apply { isAccessible = true }

    private class Binding(val view: SpyCornerView, var size: Int)

    private val bindings = LinkedHashMap<SpySide, Binding>()

    // ------------------------------------------------------------ lifecycle

    fun start() {
        if (Looper.myLooper() == mainHandler.looper) applySettings() else mainHandler.post(::applySettings)
        prefs.registerOnSharedPreferenceChangeListener { _, _ ->
            if (Looper.myLooper() == mainHandler.looper) applySettings() else mainHandler.post(::applySettings)
        }
        registerDebugReceiver()
        // Pre-warm FanHost and cached apps in background so the very first swipe has apps ready
        mainHandler.post { runCatching { ensureFan() } }
        // Enumeration ground truth for the fan's data source (this process's uid) — install
        // time is exactly the state a reboot leaves behind, which is what the panel bug needs
        // compared against a later DEBUG_PROBE run. See AppEnumProbe.
        Thread { runCatching { AppEnumProbe.run(context, logger, deep = false) } }.start()
    }

    /**
     * Verification aid (docs/test-plan-device.md A5): toggle the 更多 overlay without
     * walking the corner gesture —
     *   adb shell am broadcast -a com.repl.bubbledrawer.action.DEBUG_MORE
     * plus the fan-data probe (AppEnumProbe) —
     *   adb shell am broadcast -a com.repl.bubbledrawer.action.DEBUG_PROBE
     * Registered here (not in FanHost) on purpose: FanHost is built lazily on the first
     * claimed stroke, so a receiver living there would be dead until a successful gesture.
     */
    private fun registerDebugReceiver() {
        if (debugReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                when (i?.action) {
                    ACTION_DEBUG_MORE -> mainHandler.post { ensureFan().toggleMorePanelForDebug() }
                    AppEnumProbe.ACTION -> Thread { runCatching { AppEnumProbe.run(context, logger) } }.start()
                }
            }
        }
        runCatching {
            context.registerReceiver(
                r,
                android.content.IntentFilter().apply {
                    addAction(ACTION_DEBUG_MORE)
                    addAction(AppEnumProbe.ACTION)
                },
                android.content.Context.RECEIVER_EXPORTED,
            )
        }.onSuccess {
            debugReceiver = r
            logger(Log.INFO, "DEBUG_MORE_READY action=$ACTION_DEBUG_MORE probe=${AppEnumProbe.ACTION}", null)
        }.onFailure { logger(Log.WARN, "DEBUG_MORE_REGISTER_FAILED", it) }
    }

    private var debugReceiver: android.content.BroadcastReceiver? = null

    /**
     * Tear everything down for an API-102 HOT RELOAD (no reboot, no SystemUI restart):
     * the monitor and its input channel, the SPY-view fallback windows and the fan all
     * belong to the OLD classloader and must not survive the swap — otherwise the reload
     * leaves a second subscriber on the same gestures and a stale full-screen window that
     * swallows touches. `onHotReloaded` re-installs a fresh instance afterwards.
     */
    fun dispose() {
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { stopMonitorTransport() }
        for (side in bindings.keys.toList()) {
            val binding = bindings[side] ?: continue
            runCatching { removeBinding(side, binding) }
        }
        runCatching { fan?.destroy() }
        fan = null
        insetProbe?.let { probe -> runCatching { wm.removeViewImmediate(probe) } }
        insetProbe = null
        stroke = null
        monitorActive = false
        logger(Log.INFO, "MON_DISPOSED_FOR_HOT_RELOAD", null)
    }

    private fun applySettings() {
        settings = RemotePrefs.read(prefs)
        val wanted = settings.enabled && (settings.left || settings.right)
        if (wanted) startMonitorTransport() else stopMonitorTransport()
        // The SPY-view fallback only runs while the monitor transport is unavailable.
        updateSide(SpySide.LEFT, !monitorActive && wanted && settings.left)
        updateSide(SpySide.RIGHT, !monitorActive && wanted && settings.right)
        // change-only trace (fires on remote preference notifications)
        logger(
            Log.INFO,
            "PREFS_APPLIED enabled=" + settings.enabled + " left=" + settings.left +
                " right=" + settings.right + " range=" + settings.rangeDp +
                " transport=" + if (monitorActive) "monitor" else "spy-view" +
                " " + boundsForLog(),
            null,
        )
    }

    // ------------------------------------------------------------ transport A

    private fun startMonitorTransport() {
        if (monitorActive) return
        try {
            // Assign before anything else can throw: a monitor we created but failed to
            // wire must still be disposed, or its input channel leaks in system_server.
            val created = createMonitor()
            monitor = created
            val channel = created.inputChannel
            receiver = object : InputEventReceiver(channel, Looper.getMainLooper()) {
                override fun onInputEvent(event: InputEvent) {
                    var handled = false
                    try {
                        handled = onMonitorEvent(event)
                    } catch (t: Throwable) {
                        logger(Log.WARN, "MON_EVENT_FAILED", t)
                        stroke?.cancel()
                    } finally {
                        // A monitor observes: it must never swallow what it did not claim.
                        finishInputEvent(event, handled)
                    }
                }
            }
            monitorActive = true
            // channel.token is @hide too: never let a diagnostics call decide whether the
            // transport counts as up.
            val tokenDesc = runCatching { channel.token.toString() }.getOrElse { "?" }
            logger(
                Log.INFO,
                "MON_INPUT_READY name=$MONITOR_NAME display=$DISPLAY_ID channel=$tokenDesc " +
                    boundsForLog(),
                null,
            )
        } catch (t: Throwable) {
            monitorActive = false
            logger(Log.WARN, "MON_INPUT_UNAVAILABLE_FALLBACK_SPY_VIEW", t)
            disposeMonitor()
        }
    }

    /**
     * Three creation routes, tried in order, so one linkage failure cannot disable the
     * primary transport on its own:
     *  1. the direct call — exactly what SystemUI's own InputMonitorCompat does;
     *  2. the same class through reflection (dodges a direct-link hidden-API denial);
     *  3. the public `InputManager` facade's @hide `monitorGestureInput`.
     * Every failure is accumulated into the thrown message so the module log names the
     * exact route that broke.
     */
    private fun createMonitor(): InputMonitor {
        val failures = StringBuilder()
        runCatching {
            InputManagerGlobal.getInstance().monitorGestureInput(MONITOR_NAME, DISPLAY_ID)
        }.onSuccess { logger(Log.INFO, "MON_CREATE_ROUTE=direct", null); return it }
            .onFailure { failures.append("direct{").append(it).append("} ") }

        runCatching {
            val cls = Class.forName("android.hardware.input.InputManagerGlobal")
            val instance = cls.getMethod("getInstance").invoke(null)
            cls.getMethod(
                "monitorGestureInput",
                String::class.java,
                Int::class.javaPrimitiveType,
            ).invoke(instance, MONITOR_NAME, DISPLAY_ID) as InputMonitor
        }.onSuccess { logger(Log.INFO, "MON_CREATE_ROUTE=reflect-global", null); return it }
            .onFailure { failures.append("reflect-global{").append(it).append("} ") }

        runCatching {
            InputManager::class.java
                .getMethod(
                    "monitorGestureInput",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                )
                .invoke(inputManager, MONITOR_NAME, DISPLAY_ID) as InputMonitor
        }.onSuccess { logger(Log.INFO, "MON_CREATE_ROUTE=reflect-inputmanager", null); return it }
            .onFailure { failures.append("reflect-inputmanager{").append(it).append("} ") }

        throw IllegalStateException("no gesture-monitor route: $failures")
    }

    private fun stopMonitorTransport() {
        if (!monitorActive && monitor == null) return
        stroke?.cancel()
        disposeMonitor()
        monitorActive = false
        logger(Log.INFO, "MON_INPUT_STOPPED", null)
    }

    private fun disposeMonitor() {
        stroke = null
        runCatching { receiver?.dispose() }
        runCatching { monitor?.dispose() }
        receiver = null
        monitor = null
    }

    /** One dispatcher event from the gesture monitor; returns whether WE claimed the stream. */
    private fun onMonitorEvent(event: InputEvent): Boolean {
        val ev = event as? MotionEvent ?: return false
        if (outsideTapHandler?.invoke(ev) == true) {
            return true
        }
        if (ev.deviceId < 0) {
            // Injected by someone else (MiuiHome's own passthrough tap, a test harness, …).
            // deviceId -1 is the platform's injected marker.
            if (SystemClock.uptimeMillis() - lastOwnInjectUptime > OWN_INJECT_GUARD_MS) {
                lastForeignInjectedUptime = SystemClock.uptimeMillis()
                foreignInjectionsInStroke++
                // Context matters more than the event itself: WHERE it is relative to our
                // sector, and what the real stroke before it looked like, decide whether this
                // is MiuiHome handing a stolen tap back (legitimate, we must not duplicate it)
                // or a duplicate of a tap the app already got.
                logger(
                    Log.INFO,
                    "MON_FOREIGN_INJECT action=${ev.actionMasked} x=${ev.rawX} y=${ev.rawY} " +
                        "inSector=${sideFor(ev.rawX, ev.rawY) != null} " +
                        "sinceRealDown=${if (lastRealDownUptime == 0L) -1 else SystemClock.uptimeMillis() - lastRealDownUptime} " +
                        "lastDown=(${lastRealDownX.toInt()},${lastRealDownY.toInt()}) " +
                        "strokeOwned=${stroke?.owned} strokeClaimed=${stroke?.claimed}",
                    null,
                )
            }
        }
        if (!ev.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            logger(Log.INFO, "MON_NON_TOUCHSOURCE src=${ev.source}", null)
            stroke?.cancel()
            return false
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Record the real DOWN before the gate: rejected DOWNs matter just as much for
                // reading a later injected tap (MiuiHome's hand-back is not limited to corners).
                lastRealDownUptime = SystemClock.uptimeMillis()
                lastRealDownX = ev.rawX
                lastRealDownY = ev.rawY
                foreignInjectionsInStroke = 0
                stroke?.cancel() // defensive: never carry a stale stroke into a new DOWN
                beginMonitorStroke(ev)
            }

            MotionEvent.ACTION_MOVE -> {
                val s = stroke ?: return false
                if (!settings.enabled || !isUnlockedAndInteractive()) {
                    s.cancel()
                    return false
                }
                val index = ev.findPointerIndex(s.pointerId)
                if (index < 0) {
                    s.cancel()
                    return false
                }
                // No direction pre-filtering any more: the corner is a complete sector, so the
                // engine claims the stroke on radial travel and every direction — straight up
                // included — becomes the drawer (the vertical-dominant "leave it to the
                // system's HOME" rule was removed with it).
                s.move(ev, ev.getRawX(index), ev.getRawY(index))
            }

            MotionEvent.ACTION_POINTER_DOWN -> stroke?.cancel() // second finger = never the drawer

            MotionEvent.ACTION_UP -> {
                stroke?.let { s ->
                    val index = ev.findPointerIndex(s.pointerId)
                    val ux = if (index >= 0) ev.getRawX(index) else ev.rawX
                    val uy = if (index >= 0) ev.getRawY(index) else ev.rawY
                    val moved = hypot(ux - s.originX, uy - s.originY)
                    val duration = SystemClock.uptimeMillis() - s.downUptime
                    // One line per gesture, so a user report ("I tapped and something else
                    // happened") can be matched to what the module actually did with that
                    // stroke. Logged BEFORE up(): up() clears claimed/owned, and reading them
                    // afterwards once printed "owned=false" for strokes that had been owned.
                    logger(
                        Log.INFO,
                        "MON_STROKE_END side=${s.side.name} claimed=${s.claimed} owned=${s.owned} " +
                            "moved=${moved.toInt()} dur=$duration " +
                            "robbedAt=${if (s.robbedByOtherAt == 0L) 0 else s.robbedByOtherAt - s.downUptime} " +
                            "from=(${s.originX.toInt()},${s.originY.toInt()}) " +
                            "to=(${ux.toInt()},${uy.toInt()}) inSector=${sideFor(s.originX, s.originY) != null} " +
                            "foreignInjections=$foreignInjectionsInStroke",
                        null,
                    )
                    s.up(ev, ux, uy)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                // A CANCEL means SOMEONE ELSE took this stream — in the bottom strip that is
                // MiuiHome's swipe-up monitor pilfering ~9 ms after DOWN. KEEP THE STROKE
                // ALIVE: our monitor carries the DO_NOT_PILFER bit (see MONITOR_NAME), so the
                // dispatcher keeps publishing the rest of the gesture to us and we can still
                // decide later. Robbing it back HERE (what this code used to do) made US the
                // owner of every tap in the strip, and the app — which had already received
                // the real DOWN — then needed a second, synthetic tap to get a click, which is
                // exactly the "tapping the bottom-left corner types twice" bug. Instead we stay
                // an observer and only pilfer on Activate (Stroke.move), i.e. once the finger
                // has really travelled far enough to be a drawer gesture.
                val s = stroke
                if (s != null && s.tracking && !s.claimed) {
                    s.robbedByOtherAt = SystemClock.uptimeMillis()
                }
                logger(
                    Log.INFO,
                    "MON_SYSTEM_CANCEL tracking=${stroke?.tracking} claimed=${stroke?.claimed} " +
                        "kept=$((s != null && s.tracking && !s.claimed)) " +
                        "x=${ev.rawX} y=${ev.rawY} inSector=${sideFor(ev.rawX, ev.rawY) != null} " +
                        "foreignInjections=$foreignInjectionsInStroke",
                    null,
                )
            }
        }
        return stroke?.claimed == true
    }

    private fun beginMonitorStroke(ev: MotionEvent) {
        if (ev.pointerCount != 1) return
        if (ev.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return
        if (!settings.enabled || !isUnlockedAndInteractive()) return
        val x = ev.rawX
        val y = ev.rawY
        val side = sideFor(x, y)
        if (side == null) {
            if (nearCorner(x, y)) {
                logger(
                    Log.INFO,
                    "MON_DOWN_REJECTED x=$x y=$y box=${cornerBoxPx()} ${boundsForLog()}" +
                        " left=${settings.left} right=${settings.right}",
                    null,
                )
            }
            return
        }
        // PRE-OWN THE DOWN inside the bottom band — that is what stops MiuiHome from
        // recognising HOME out of the corner (its Rust: "on_pilfered_at_down:
        // passthrough_eligible = true" / "skip DOWN pilfer"; whoever pilfered at DOWN makes it
        // stand down). Observing instead and claiming on travel is NOT enough: a device test
        // with the pure-observe build went home AND opened the panel for an up-swipe out of the
        // corner, because MiuiHome commits HOME from the gesture's early upward velocity and a
        // DO_NOT_PILFER monitor never even sees the CANCEL (robbedAt=0 in the log while HOME
        // fired).
        //
        // THE ONE EXCEPTION: while the IME (keyboard) is showing, we stay an observer. The
        // pre-own cannot prevent the dispatcher from delivering the real DOWN to the app, so a
        // keyboard — which acts on DOWN — typed the character once from that DOWN and once from
        // the synthetic tap we needed to hand the touch back. With the keyboard up, taps are
        // therefore served by the app itself (a corner drawer gesture started over the keyboard
        // is the trade-off, taking the same HOME risk as any observed stroke).
        val bottom = displayBounds().bottom
        val inHomeBand = y >= bottom - HOME_GESTURE_BAND_DP * density
        val imePx = imeVisibleHeightPx()
        val owned = imePx <= 0 && inHomeBand && pilferMonitor()
        logger(
            Log.INFO,
            "MON_DOWN side=$side x=$x y=$y box=${cornerBoxPx()} band=$inHomeBand " +
                "ime=${imePx}px mode=${if (owned) "pre-own" else "observe"} " + boundsForLog(),
            null,
        )
        val config = AdaptiveSpyGestureConfig.create(
            displayWidth = screenW().toFloat(),
            displayHeight = screenH().toFloat(),
            touchSlop = touchSlop,
            density = density,
            triggerRangeDp = settings.rangeDp,
            leftEnabled = settings.left,
            rightEnabled = settings.right,
        )
        val s = Stroke({ claimed -> logger(Log.INFO, "MON_PILFER_OK=$claimed", null) })
        s.arm(ev.getPointerId(0), x, y, side, config, owned)
        stroke = s
    }

    /**
     * Screen-space corner gate — the same RECT the SPY view used (`inBox`), not the
     * reference's quarter disc: on this panel the digitizer never delivers the
     * mathematical corner, so a disc centred exactly on it rejects every reachable
     * finger touch (FlymeFreeform commentary, CornerGestureEngine :104-112).
     */
    private fun sideFor(x: Float, y: Float): SpySide? {
        // COMPLETE QUARTER-ELLIPSE (sector) at each bottom corner — not a square, not
        // direction-gated, and no longer a circle: the two axes are configured separately
        // (how far the zone reaches ALONG the bottom edge and how far UP the side edge), so
        // "swipe in along the bottom" and "swipe up along the side" can be tuned
        // independently. Equal axes give the plain quarter-disc again. Once the DOWN is
        // inside, every direction belongs to the drawer, straight up included.
        val b = displayBounds()
        val bottom = bottomExtentPx()
        val edge = edgeExtentPx()
        if (settings.left && CornerSector.contains(x, y, b.width().toFloat(), b.bottom.toFloat(), bottom, edge, leftCorner = true)) {
            return SpySide.LEFT
        }
        if (settings.right && CornerSector.contains(x, y, b.width().toFloat(), b.bottom.toFloat(), bottom, edge, leftCorner = false)) {
            return SpySide.RIGHT
        }
        return null
    }

    /**
     * Height of the on-screen keyboard in pixels, 0 when it is not showing.
     *
     * The framework's own `InputMethodManager#getInputMethodWindowVisibleHeight()` exists in
     * this ROM's framework.jar but answers 0 even while `dumpsys input_method` reports
     * `mInputShown=true` (verified on device), so it is useless here. The dependable signal is
     * the IME inset of a window we own: an invisible, non-touchable, full-screen overlay that
     * requests only `WindowInsets.Type.ime()`, kept for the life of the monitor. The system
     * updates its insets whenever the keyboard shows or hides, so one field read per corner
     * DOWN answers the question that decides whether we may take the DOWN at all.
     */
    private fun imeVisibleHeightPx(): Int {
        val probe = ensureInsetProbe() ?: return 0
        return runCatching {
            probe.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
        }.getOrDefault(0)
    }

    /** Lazily adds the IME-inset probe window (see [imeVisibleHeightPx]); null if it cannot. */
    private fun ensureInsetProbe(): View? {
        insetProbe?.let { return it }
        return runCatching {
            val view = View(context)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                CORNER_WINDOW_TYPE, // TYPE_ACCESSIBILITY_OVERLAY, same trusted overlay type
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSPARENT,
            )
            // Only the IME inset is interesting; asking for the rest would make this window a
            // consumer of system-bar insets for no reason.
            lp.setFitInsetsTypes(WindowInsets.Type.ime())
            lp.gravity = Gravity.BOTTOM or Gravity.START
            lp.title = "bubble slide ime probe"
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            wm.addView(view, lp)
            insetProbe = view
            view
        }.onFailure { logger(Log.WARN, "MON_IME_PROBE_FAILED", it) }.getOrNull()
    }

    private fun bottomExtentPx(): Float =
        (CornerTriggerRegion.radiusPx(settings.bottomDp, density) + 0.5f).toInt().toFloat()

    private fun edgeExtentPx(): Float =
        (CornerTriggerRegion.radiusPx(settings.edgeDp, density) + 0.5f).toInt().toFloat()

    /**
     * Physical display bounds for the corner gate.
     *
     * `currentWindowMetrics` describes the area a MATCH_PARENT window would occupy and
     * may EXCLUDE system decorations: on this ROM the navigation-bar strip measures
     * 65 px in PORTRAIT (dumpsys: NavigationBar0 frame=[0,2591][1220,2656]), while in
     * landscape that inset is taken off the WIDTH and the height stays 1220. Anchoring
     * the band to `currentWindowMetrics.bottom` therefore floats it ~65 px above the
     * physical bottom edge in portrait and rejects exactly the touches users make at
     * the very corner — which is what "works in landscape, not in portrait" looks like.
     * `maximumWindowMetrics` is the full display; take the union so the band can never
     * end up above the physical edge on either metric.
     */
    private fun displayBounds(): Rect {
        val max = wm.maximumWindowMetrics.bounds
        val cur = wm.currentWindowMetrics.bounds
        return Rect(
            minOf(max.left, cur.left),
            minOf(max.top, cur.top),
            maxOf(max.right, cur.right),
            maxOf(max.bottom, cur.bottom),
        )
    }

    private fun boundsForLog(): String {
        val max = wm.maximumWindowMetrics.bounds
        val cur = wm.currentWindowMetrics.bounds
        return "cur=${cur.width()}x${cur.height()} max=${max.width()}x${max.height()}"
    }

    /** True inside a box twice the trigger size — used to log near misses (gate diagnosis). */
    private fun nearCorner(x: Float, y: Float): Boolean {
        val outer = cornerBoxPx() * 2f
        val b = displayBounds()
        if (y < b.bottom - outer) return false
        return (settings.left && x <= b.left + outer) || (settings.right && x >= b.right - outer)
    }

    /** Largest axis of the corner region — used for logs and the fallback view's size. */
    private fun cornerBoxPx(): Float = maxOf(bottomExtentPx(), edgeExtentPx())

    /** Only the monitor's channel is made the touch target; the app keeps everything else. */
    private fun pilferMonitor(): Boolean {
        val m = monitor ?: return false
        return try {
            m.pilferPointers()
            true
        } catch (t: Throwable) {
            logger(Log.WARN, "MON_PILFER_FAILED", t)
            false
        }
    }


    // ------------------------------------------------------------ transport B (fallback)

    private fun updateSide(side: SpySide, shouldExist: Boolean) {
        val existing = bindings[side]
        if (!shouldExist) {
            if (existing != null) removeBinding(side, existing)
            return
        }
        val size = cornerBoxPx().toInt().coerceAtLeast(1)
        if (existing == null) {
            addBinding(side, size)
        } else if (existing.size != size) {
            existing.size = size
            existing.view.rangePx = size.toFloat()
            runCatching { wm.updateViewLayout(existing.view, createLayoutParams(side, size)) }
                .onFailure { removeBinding(side, existing) }
        }
    }

    private fun addBinding(side: SpySide, size: Int) {
        if (bindings.containsKey(side)) return
        val v = SpyCornerView(
            ctx = context,
            side = side,
            rangePx = size.toFloat(),
            touchSlopPx = touchSlop,
            canClaim = { settings.enabled && isUnlockedAndInteractive() && if (side == SpySide.LEFT) settings.left else settings.right },
            pilfer = ::pilferView,
            onActivate = { activated, screenX, screenY -> openFan(activated, screenX, screenY) },
            onStream = { screenEv -> forwardToFan(screenEv) },
            onStrokeEnd = { claimed -> if (claimed) retractFan() },
            trace = { message -> logger(Log.INFO, "SPY_${side.name}_$message", null) },
        )
        try {
            wm.addView(v, createLayoutParams(side, size))
            bindings[side] = Binding(v, size)
            logger(Log.INFO, "SPY_WINDOW_ADDED_${side.name}", null)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SPY_WINDOW_ADD_FAILED_${side.name}", exception)
        }
    }

    private fun removeBinding(side: SpySide, binding: Binding) {
        bindings.remove(side)
        binding.view.resetTracking()
        runCatching { wm.removeViewImmediate(binding.view) }
    }

    // ------------------------------------------------------------ shared stroke logic

    /**
     * One stroke. Normally the engine arms as a PURE OBSERVER and `Activate` on a MOVE is
     * the only moment the stream is pilfered; if the launcher takes the gesture away first,
     * the CANCEL is answered with a re-pilfer ("shadow", see onMonitorEvent) and the same
     * engine continues from the original DOWN origin. The owned band is only used as a
     * fallback once that proved not to work on this device. A claimed stroke ends through
     * [retractFan], and one we hold but never claim replays the system's bottom up-swipe,
     * so the panel is never left hanging and "swipe up from the bottom to go HOME" keeps
     * working (reference CornerGestureEngine :113-147; the reference's two-window
     * hand-off lost exactly that release).
     */
    private inner class Stroke(private val pilfered: (Boolean) -> Unit) {
        private val engine = CornerGestureEngine()
        private var config: SpyGestureConfig? = null
        private var pilferAttempted = false

        var side: SpySide = SpySide.RIGHT
            private set

        var pointerId: Int = -1
            private set
        var tracking = false
            private set
        var claimed = false
            private set

        /**
         * True once we pilfered this stream ourselves — always mid-gesture, on Activate
         * (`Stroke.move`); nothing is taken at DOWN any more.
         */
        var owned = false
            private set

        /** set when somebody else (MiuiHome) pilfered it first; the stroke survives that. */
        var robbedByOtherAt = 0L

        /** DOWN position, in raw display pixels (read by the early-claim test) */
        var originX = 0f
            private set
        var originY = 0f
            private set

        /** ACTION_DOWN uptime — the tap/short-gesture test at release */
        var downUptime = 0L
            private set

        /** uptime of the last CLAIMED stroke end (0 = never) — read by [isCornerCommitWindow]. */
        var lastClaimedEndUptime: Long = 0L
            private set

        fun arm(
            newPointerId: Int,
            x: Float,
            y: Float,
            newSide: SpySide,
            newConfig: SpyGestureConfig,
            newOwned: Boolean,
        ) {
            pointerId = newPointerId
            side = newSide
            config = newConfig
            claimed = false
            owned = newOwned
            pilferAttempted = false
            downUptime = SystemClock.uptimeMillis()
            originX = x
            originY = y
            engine.down(newPointerId, x, y, newSide)
            tracking = engine.phase == SpyPhase.ARMED
        }

        fun move(raw: MotionEvent, x: Float, y: Float) {
            if (!tracking) return
            val cfg = config ?: return
            // The corner is a COMPLETE SECTOR: there is no upward hand-off to the system's
            // bottom up-swipe any more (user request — a swipe straight up out of the corner
            // opens the panel). The gesture is taken HERE and only here: nothing was pilfered
            // at DOWN, so by the time the engine says Activate the app has had the touch for
            // the whole decision distance — which is what keeps its taps intact — and MiuiHome
            // has not committed HOME yet (its `record_area_height_px` is larger than our
            // activation distance).
            when (val action = engine.move(pointerId, raw.pointerCount, x, y, cfg)) {
                is SpyAction.Activate -> {
                    if (!isUnlockedAndInteractive()) {
                        cancel()
                        return
                    }
                    if (!pilferAttempted && !owned) {
                        pilferAttempted = true
                        val ok = pilferMonitor()
                        if (ok) owned = true
                        pilfered(ok)
                    }
                    claimed = true
                    logger(Log.INFO, "MON_ACTIVATE side=${action.side.name} x=$x y=$y", null)
                    openFan(action.side, x, y)
                }

                is SpyAction.Update -> if (claimed) forwardToFan(raw)

                SpyAction.Cancel -> {
                    logger(Log.INFO, "MON_ENGINE_CANCEL claimed=$claimed", null)
                    end(retract = claimed)
                }

                else -> Unit // PassThrough — still observing, the app keeps its stream
            }
        }

        fun up(raw: MotionEvent, x: Float, y: Float) {
            if (!tracking) return
            if (claimed) {
                // The release goes to the fan so GestureAppLauncher decides launch /
                // stay-open / collapse (m9729p :531-556), exactly like the original
                // forwarding window. NEVER force-retract here: the fan owns this decision.
                forwardToFan(raw)
            } else if (owned) {
                // Not a drawer, but we DID take this stroke's DOWN (pre-own), so the app's own
                // gesture is sitting in DOWN…CANCEL and a tap must be rebuilt for it. This is
                // the narrow case the synthetic tap exists for; it no longer fires over the
                // keyboard, because there the DOWN was never taken (see beginMonitorStroke) and
                // the app serves its own taps — the double input the user reported came from
                // both the app's real DOWN and this injected tap landing on the same key.
                val moved = hypot(x - originX, y - originY)
                val duration = SystemClock.uptimeMillis() - downUptime
                if (moved <= tapSlopPx && duration <= TAP_MAX_MS) {
                    injectTap(x, y)
                } else {
                    logger(
                        Log.INFO,
                        "MON_SWALLOWED moved=${moved.toInt()} duration=$duration",
                        null,
                    )
                }
            } else {
                // Observed stroke (IME showing, or outside the launcher's band): the app has
                // been serving this touch itself the whole time, so a tap needs NOTHING from us.
                val moved = hypot(x - originX, y - originY)
                logger(
                    Log.INFO,
                    "MON_STROKE_UNCLAIMED moved=${moved.toInt()} " +
                        "duration=${SystemClock.uptimeMillis() - downUptime} " +
                        "robbedAt=${if (robbedByOtherAt == 0L) 0 else robbedByOtherAt - downUptime}",
                    null,
                )
            }
            engine.up(pointerId)
            end(retract = false)
        }

        fun cancel() {
            if (!tracking) return
            val wasClaimed = claimed
            engine.cancel()
            end(retract = wasClaimed)
        }

        private fun end(retract: Boolean) {
            val wasClaimed = claimed
            val wasOwned = owned
            tracking = false
            claimed = false
            owned = false
            pilferAttempted = false
            pointerId = -1
            config = null
            if (wasClaimed || wasOwned) lastClaimedEndUptime = SystemClock.uptimeMillis()
            if (retract) retractFan()
        }
    }

    /**
     * Hand a TAP inside the pre-owned corner strip back to the app: we took its DOWN, so
     * without this the tap would vanish (the user's "一些点击操作也被吞了").
     *
     * Injected immediately at release, NOT after the old 700 ms wait — that wait existed to let
     * MiuiHome's own 300 ms passthrough win; in this strip it never fires (device logs: dozens of
     * corner taps, zero injections from com.miui.home), while the delay made every corner tap
     * feel laggy. The wait is also what used to let a late launcher tap land on top of ours.
     *
     * This path is skipped entirely while the keyboard is showing (see beginMonitorStroke) —
     * there the pre-own does not happen and the app keeps its own tap.
     */
    private fun injectTap(x: Float, y: Float) {
        val method = injectInputEventMethod
        if (method == null) {
            logger(Log.WARN, "MON_TAP_PASSTHROUGH_UNAVAILABLE", null)
            return
        }
        try {
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(now, now + 32, MotionEvent.ACTION_UP, x, y, 0)
            lastOwnInjectUptime = now
            method.invoke(inputManager, down, 0)
            method.invoke(inputManager, up, 0)
            down.recycle()
            up.recycle()
            logger(Log.INFO, "MON_TAP_PASSTHROUGH_INJECTED x=$x y=$y", null)
        } catch (t: Throwable) {
            logger(Log.WARN, "MON_TAP_PASSTHROUGH_FAILED", t)
        }
    }

    private fun openFan(side: SpySide, x: Float, y: Float) {
        if (!isUnlockedAndInteractive()) return
        mainHandler.post {
            if (!isUnlockedAndInteractive()) return@post
            ensureFan().show(side, x, y, screenW(), screenH())
        }
    }

    private fun retractFan() {
        mainHandler.post { fan?.cancelExternal() }
    }

    /**
     * Hand one MOVE/UP to the fan. The event is copied and recycled around the fan call:
     * posting the receiver's own event, or a copy recycled before the posted lambda runs
     * (what the view path used to do), hands the launcher a recycled MotionEvent.
     */
    private fun forwardToFan(event: MotionEvent) {
        val copy = MotionEvent.obtain(event)
        mainHandler.post {
            try {
                fan?.forward(copy)
            } catch (t: Throwable) {
                logger(Log.WARN, "FAN_FORWARD_FAILED", t)
            } finally {
                copy.recycle()
            }
        }
    }

    /**
     * True only for strokes WE claimed (or ended < [COMMIT_GRACE_MS] ago): the native
     * handler decides BACK from its own state machine and does not re-check whether it
     * still owns the pointers, so a claimed corner stroke must suppress its late
     * commit. An UNCLAIMED corner stroke — a plain upward back swipe — never marks
     * this and commits BACK exactly as before.
     */
    fun isCornerCommitWindow(): Boolean {
        val s = stroke
        if (s != null &&
            ((s.tracking && (s.claimed || s.owned)) || withinGrace(s.lastClaimedEndUptime))
        ) {
            return true
        }
        return bindings.values.any { b ->
            val v = b.view
            (v.streamActive && v.claimActive) || withinGrace(v.lastClaimedEndUptime)
        }
    }

    private fun withinGrace(uptime: Long): Boolean =
        uptime != 0L && SystemClock.uptimeMillis() - uptime <= COMMIT_GRACE_MS

    /** SystemUiCornerInputMonitor.pilferPointers (:170-184) verbatim flow (fallback transport). */    private fun pilferView(view: View): Boolean = try {
        val viewRoot = getViewRootImplMethod.invoke(view) ?: return false
        val getToken = getInputTokenMethod ?: return false
        val inputToken = getToken.invoke(viewRoot) as? IBinder ?: return false
        pilferPointersMethod.invoke(inputManager, inputToken)
        true
    } catch (exception: Throwable) {
        logger(Log.WARN, "SPY_PILFER_FAILED", exception)
        false
    }

    private fun screenW(): Int = displayBounds().width()
    private fun screenH(): Int = displayBounds().height()

    private fun ensureFan(): FanHost {
        var f = fan
        if (f == null) {
            f = FanHost(context, prefs, logger)
            fan = f
        }
        return f
    }

    /** createLayoutParams (:200-223) — edge-hugging corner box, trusted SPY (fallback transport). */
    private fun createLayoutParams(side: SpySide, size: Int) =
        WindowManager.LayoutParams(
            size, size,
            CORNER_WINDOW_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSPARENT,
        ).apply {
            gravity = Gravity.BOTTOM or if (side == SpySide.LEFT) Gravity.LEFT else Gravity.RIGHT
            title = "$CORNER_INPUT_CHANNEL_TITLE-${side.name}"
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            setTrustedOverlayMethod.invoke(this)
            // SPY keeps this window a pure observer (it never becomes the touch target),
            // DO_NOT_PILFER keeps it in the dispatcher's touch state while another window
            // takes the pointers. Both bits are what the platform's own monitors carry:
            // InputConfigAdapter maps LayoutParams.inputFeatures (AOSP
            // INPUT_FEATURE_SPY = 1<<2, INPUT_FEATURE_DO_NOT_PILFER = 1<<3) onto
            // InputConfig, and InputManagerService's GestureMonitorSpyWindow sets exactly
            // `inputConfig = SPY | DO_NOT_PILFER` (16388, GestureMonitorSpyWindow.java:35).
            // Without DO_NOT_PILFER this fallback window is dropped ~6-22 ms after
            // ACTION_DOWN inside apps, which is the bug this whole change fixes.
            inputFeaturesField.setInt(
                this,
                inputFeaturesField.getInt(this) or INPUT_FEATURE_SPY or INPUT_FEATURE_DO_NOT_PILFER,
            )
        }

    /**
     * Port of CornerGestureView (:236-359). The fallback transport: the engine runs in
     * the VIEW's own coordinate space (reference :280-281/289-290 pass `width`/`height`
     * — the box == the radius square at the corner), so events forwarded to the fan are
     * remapped to screen coords with getLocationOnScreen.
     */
    @SuppressLint("ClickableViewAccessibility")
    class SpyCornerView(
        ctx: Context,
        private val side: SpySide,
        var rangePx: Float,
        private val touchSlopPx: Float,
        private val canClaim: () -> Boolean,
        private val pilfer: (View) -> Boolean,
        private val onActivate: (side: SpySide, screenX: Float, screenY: Float) -> Unit,
        private val onStream: (MotionEvent) -> Unit,
        private val onStrokeEnd: (claimed: Boolean) -> Unit,
        private val trace: (String) -> Unit,
    ) : View(ctx) {
        private val engine = CornerGestureEngine()
        private var activeConfig: SpyGestureConfig? = null
        private var activePointerId = -1
        private var tracking = false
        private var claimed = false
        private var pilferAttempted = false
        private val loc = IntArray(2)

        val streamActive: Boolean get() = tracking

        /** current claim state for the commit guard (true while a claimed stroke is mid-flight) */
        val claimActive: Boolean get() = claimed

        /** uptime ms of the moment a CLAIMED stroke ended (0 = never); see [COMMIT_GRACE_MS]. */
        var lastClaimedEndUptime: Long = 0L
            private set

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setWillNotDraw(true)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                trace("NON_TOUCHSOURCE src=${event.source}")
                val wasActive = tracking
                val wasClaimed = claimed
                resetTracking()
                if (wasActive) {
                    if (wasClaimed) markClaimedEnd()
                    onStrokeEnd(wasClaimed)
                }
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resetTracking()
                    // Gate shape: the FULL corner box — flyme original semantics, NOT the
                    // reference's quarter disc (same decision and rationale as the monitor
                    // transport's sideFor).
                    val inBox = event.x >= 0f && event.x <= width &&
                        event.y >= 0f && event.y <= height
                    val eligible = event.pointerCount == 1 &&
                        event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER &&
                        canClaim() && inBox
                    trace("DOWN side=$side x=${event.x} y=${event.y} wh=$width/$height r=$rangePx inBox=$inBox eligible=$eligible")
                    if (eligible) {
                        val config = AdaptiveSpyGestureConfig.create(
                            displayWidth = width.toFloat(),
                            displayHeight = height.toFloat(),
                            touchSlop = touchSlopPx,
                            density = resources.displayMetrics.density,
                            triggerRangeDp = (rangePx / resources.displayMetrics.density)
                                .toInt().coerceIn(24, 160),
                            leftEnabled = side == SpySide.LEFT,
                            rightEnabled = side == SpySide.RIGHT,
                        )
                        activePointerId = event.getPointerId(0)
                        engine.down(activePointerId, event.x, event.y, side)
                        if (engine.phase == SpyPhase.ARMED) {
                            activeConfig = config
                            tracking = true
                        } else {
                            trace("DOWN_NOT_ARMED phase=${engine.phase}")
                        }
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (tracking) {
                        if (!canClaim()) {
                            val wasClaimed = claimed
                            resetTracking()
                            if (wasClaimed) onStrokeEnd(true)
                            return true
                        }
                        val config = activeConfig
                        val index = event.findPointerIndex(activePointerId)
                        val action = if (config != null && index >= 0) {
                            engine.move(
                                activePointerId, event.pointerCount,
                                event.getX(index), event.getY(index), config,
                            )
                        } else {
                            engine.cancel()
                        }
                        when (action) {
                            is SpyAction.Activate -> {
                                if (!pilferAttempted) {
                                    pilferAttempted = true
                                    trace("PILFER_OK=${pilfer(this)}") // reference :327-330
                                }
                                claimed = true
                                val s = toScreen(event.getX(index), event.getY(index))
                                trace("ACTIVATE screen=(${s[0]},${s[1]})")
                                onActivate(action.side, s[0], s[1])
                            }
                            is SpyAction.Update -> if (claimed) forwardToFan(event)
                            SpyAction.Cancel -> {
                                val wasClaimed = claimed
                                trace("ENGINE_CANCEL claimed=$wasClaimed")
                                resetTracking()
                                if (wasClaimed) {
                                    markClaimedEnd()
                                    onStrokeEnd(true)
                                }
                            }
                            else -> Unit // PassThrough — still observing, app keeps its stream
                        }
                    }
                }

                MotionEvent.ACTION_POINTER_DOWN -> if (tracking) {
                    val wasClaimed = claimed
                    engine.cancel()
                    resetTracking()
                    if (wasClaimed) {
                        markClaimedEnd()
                        onStrokeEnd(true)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    val wasTracking = tracking
                    val wasClaimed = claimed
                    if (wasTracking) {
                        if (wasClaimed) {
                            // Release goes to the fan FIRST so GestureAppLauncher
                            // decides launch / stay-open / collapse (m9729p :531-556)
                            // — exactly like the original forwarding window.
                            forwardToFan(event)
                        }
                        engine.up(activePointerId)
                    }
                    resetTracking()
                    // unclaimed UP = the app's own gesture ended — nothing to tell the fan
                    if (wasClaimed) {
                        markClaimedEnd()
                        onStrokeEnd(false)
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    trace("SYSTEM_CANCEL tracking=$tracking claimed=$claimed")
                    if (tracking) {
                        val wasClaimed = claimed
                        engine.cancel()
                        resetTracking()
                        if (wasClaimed) {
                            markClaimedEnd()
                            onStrokeEnd(true)
                        }
                    }
                }
            }
            // A SPY window is never a target — returning true only keeps our
            // monitor channel alive (reference :349 does the same).
            return true
        }

        private fun forwardToFan(event: MotionEvent) {
            val copy = MotionEvent.obtain(event)
            val l = toScreen(0f, 0f)
            copy.offsetLocation(l[0], l[1]) // box-local → screen coords
            onStream(copy)
            copy.recycle()
        }

        private fun toScreen(x: Float, y: Float): FloatArray {
            getLocationOnScreen(loc)
            return floatArrayOf(x + loc[0], y + loc[1])
        }

        fun resetTracking() {
            engine.cancel()
            activeConfig = null
            activePointerId = -1
            tracking = false
            claimed = false
            pilferAttempted = false
        }

        private fun markClaimedEnd() {
            lastClaimedEndUptime = SystemClock.uptimeMillis()
        }
    }

    companion object {
        const val INPUT_FEATURE_SPY = 1 shl 2          // reference :363
        /** LayoutParams.inputFeatures bit that InputConfigAdapter maps to
         *  InputConfig.DO_NOT_PILFER (see the flag table in createLayoutParams). */
        const val INPUT_FEATURE_DO_NOT_PILFER = 1 shl 3
        const val CORNER_WINDOW_TYPE = 2024            // reference :364 (TYPE_ACCESSIBILITY_OVERLAY)
        /** adb hook for manual verification only (see [registerDebugReceiver]). */
        const val ACTION_DEBUG_MORE = "com.repl.bubbledrawer.action.DEBUG_MORE"
        const val CORNER_INPUT_CHANNEL_TITLE = "BubbleDrawer-corner-spy" // reference :365

        /**
         * Gesture-monitor channel name — appears in `dumpsys input` as
         * "[Gesture Monitor] BubbleDrawer-corner-MultiTaskSwitch".
         *
         * THE NAME IS LOAD-BEARING. Decompiled from this device's own
         * /system/framework/services.jar (classes2, com.android.server.input
         * .GestureMonitorSpyWindow:30-33):
         *
         *     this.mWindowHandle.inputConfig = 16388;              // SPY | TRUSTED_OVERLAY
         *     if (name != null && (name.endsWith("MultiTaskSwitch") || name.endsWith("pip-resize"))) {
         *         this.mWindowHandle.inputConfig |= DisplayDeviceInfo.FLAG_ALLOWS_CONTENT_MODE_SWITCH;
         *     }
         *
         * `DisplayDeviceInfo.FLAG_ALLOWS_CONTENT_MODE_SWITCH` is 1048576 (0x100000), and that
         * same bit is what `dumpsys input` prints as DO_NOT_PILFER. Confirmed live: the
         * dispatcher showed
         *     [Gesture Monitor] MultiTaskSwitch     … SPY | DO_NOT_PILFER   (name matches)
         *     [Gesture Monitor] BubbleDrawer-corner … SPY                   (no match)
         *     [Gesture Monitor] swipe-up (com.miui.home) … SPY              (no match)
         * and during a held corner stroke the launcher's swipe-up monitor held
         * `pilferingPointerIds=0…01` while ours had been dropped from the touch state
         * entirely — i.e. DO_NOT_PILFER is exactly what decides who survives the launcher's
         * take-over of the bottom gesture area. MIUI grants it by name suffix only, so the
         * suffix is appended here (monitor bookkeeping is token-keyed, not name-keyed, so
         * this cannot collide with MIUI's own monitor).
         */
        const val MONITOR_NAME = "BubbleDrawer-corner-MultiTaskSwitch"
        const val DISPLAY_ID = 0

        /**
         * Height of the system's reserved bottom band inside which we OWN the DOWN.
         * Measured on this device: every timely-cancelled corner stroke started ≤70 px
         * (21 dp) above the bottom edge, every stroke that survived started ≥77 px
         * (24 dp) up (or pilfered first). The counterparty is **com.miui.home**: its
         * native (Rust) `GestureInputMonitor` owns exactly this bottom "record area"
         * (`record_area_height_px`, `formula=down_y-current_y>record_area_height_px`,
         * `Home pilfer_pointers`) and takes the gesture away from the app inside it.
         * 28 dp keeps the whole observed band owned with a small margin; everything
         * above it stays a pure observer.
         */
        const val HOME_GESTURE_BAND_DP = 28f

        /** a stroke shorter than this that barely moved counts as a tap */
        const val TAP_MAX_MS = 250L

        /** IME visibility is re-checked at most this often (it only gates the pre-own decision). */
        const val IME_CACHE_MS = 200L

        /**
         * Wait past MiuiHome's own 300 ms tap passthrough before considering our own.
         * 700 ms also absorbs its scheduling jitter, and the check is made against the
         * DOWN time (not the release), so ANY injected event during the whole stroke
         * counts as "the launcher is handling this tap" — the double-tap guard.
         */
        const val TAP_PASSTHROUGH_DELAY_MS = 700L

        /** window in which an injected event is assumed to be our own */
        const val OWN_INJECT_GUARD_MS = 200L

        /**
         * How soon after DOWN a CANCEL still looks like the launcher's take-over
         * (measured 6–22 ms) rather than a genuine system cancellation we must respect.
         */
        const val TAKEOVER_WINDOW_MS = 120L

        /** how long the stream must continue after a re-pilfer to count as working */
        const val SHADOW_CONFIRM_MS = 200L


        /** Grace after a corner stroke ends during which the native back handler's late
         *  commit attempt for the SAME stroke is still suppressed (main-looper dispatch
         *  ordering; the observed steal was 6–22 ms). */
        const val COMMIT_GRACE_MS = 300L
    }
}

