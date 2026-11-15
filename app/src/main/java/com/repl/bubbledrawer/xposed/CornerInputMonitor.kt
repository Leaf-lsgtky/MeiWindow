package com.repl.bubbledrawer.xposed

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.input.InputManager
import android.hardware.input.InputManagerGlobal
import android.os.Handler
import android.os.HandlerThread
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

    // ---------------------------------------------------- cached state for the input path

    /**
     * EVERYTHING the DOWN path needs is cached here, because that path is a RACE and the
     * whole budget is ~2 ms (see [beginMonitorStroke]).
     *
     * Measured on device (`MIUIInput: [MotionEvent] publisher`, the dispatcher publishing the
     * DOWN to the launcher's window at 21:29:47.974; our `InputManagerService: pilferPointers`
     * at .976): our DOWN-time pilfer lands ~2 ms after the touch and MiuiHome's window gets
     * the CANCEL 1 ms later. That 2 ms is what stands between a corner swipe and
     * HOME-plus-fan — MiuiHome commits HOME "from the first upward sample" (see
     * RemotePrefs.KEY_ARBITER_REGION), so a pilfer that arrives late is a pilfer that did
     * nothing. Anything on this path that can block — a window add, a binder round-trip to
     * PowerManager/KeyguardManager, a WindowManager metrics read, the main looper's queue —
     * can therefore cost the gesture, which is exactly what "first swipe after a SystemUI
     * restart goes home" was.
     *
     * Refreshed by [refreshState] (power/keyguard, any thread) and [refreshGeometry]
     * (WindowManager metrics, main thread only), on configuration/display changes, on
     * screen on/off, and by a watchdog on the input thread; never read from the DOWN path
     * as a call.
     */
    @Volatile
    private var deviceInteractive = true

    @Volatile
    private var keyguardLocked = false

    /** physical display bounds (union of current/maximum metrics) — see [displayBounds] */
    @Volatile
    private var displayBoundsCache = Rect(0, 0, 0, 0)

    @Volatile
    private var currentBoundsCache = Rect(0, 0, 0, 0)

    @Volatile
    private var maximumBoundsCache = Rect(0, 0, 0, 0)

    /**
     * Rotation the caches were built for (`Display.getRotation()`, -1 = never built).
     *
     * THE CACHE MUST NOT OUTLIVE A ROTATION. It used to: `displayBounds()` was a plain cache
     * read, and the only refreshers were the start-up path, a screen on/off, and a
     * `ComponentCallbacks` hook that HyperOS's `SystemUIApplicationImpl` does not have to
     * dispatch. Rotate to landscape afterwards and the corner gate keeps testing a PORTRAIT
     * rect: `CornerSector.contains(x, y, 1220, 2656, …)` rejects every landscape DOWN
     * (its vertical reach is measured from y=2656, so a real touch at y≤1220 is always "too
     * far up") — the fan simply cannot be opened any more. Reported 2026-10-11 as
     * "横屏下无法唤出扇形小窗".
     *
     * The DOWN path therefore checks this one INT before trusting the cache: `Display.getRotation`
     * is a field read of the framework's cached DisplayInfo (no binder, no WindowManager lock),
     * and the rotation itself is pushed to every process by the framework's own display listener.
     * A stale cache is healed on the main thread and, for the event at hand, rebuilt locally by
     * swapping the physical sides — a rotation cannot change the display's size, only its
     * orientation.
     */
    @Volatile
    private var geometryRotation = -1

    /**
     * The `Display` the caches were built for — held so the DOWN path never resolves one:
     * `Context.getDisplay()` caches its object per Context, but resolving it the first time
     * talks to DisplayManagerService, and this is the wrong path for a binder call. Its
     * DisplayInfo (and therefore `getRotation()`) is kept current by the framework's own
     * display listener.
     */
    @Volatile
    private var geometryDisplay: android.view.Display? = null

    /** IME height in px, maintained by the probe window's insets listener (main thread). */
    @Volatile
    private var imeHeightCache = 0

    /** The actual cropped rectangle and its boot/rotation/expiry, refreshed off the input thread. */
    @Volatile
    private var arbiterCropState: ArbiterCropState? = null

    @Volatile
    private var bootCount = -1

    /** Returns true only if the display is interactive and the keyguard is NOT locked. */
    private fun isUnlockedAndInteractive(): Boolean = deviceInteractive && !keyguardLocked

    /**
     * 电源/锁屏状态快照 —— 两次 binder 调用，所以它**只能**跑在看门狗线程上，永远不许出现在 DOWN 路径里。
     */
    private fun refreshState() {
        deviceInteractive = runCatching { powerManager?.isInteractive != false }.getOrDefault(true)
        keyguardLocked = runCatching { keyguardManager?.isKeyguardLocked == true }.getOrDefault(false)
        bootCount = runCatching {
            android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)
        arbiterCropState = runCatching {
            ArbiterCropState.decode(android.provider.Settings.Global.getString(
                context.contentResolver,
                LauncherMonitorRegion.SETTING_ARBITER_CROP_STATE,
            ))
        }.getOrNull()
    }

    /**
     * WindowManager metrics are the one part of the cache that must stay on the main thread;
     * everything else in the DOWN path only reads the fields it fills.
     */
    private fun refreshGeometry() {
        runCatching {
            val max = wm.maximumWindowMetrics.bounds
            val cur = wm.currentWindowMetrics.bounds
            maximumBoundsCache = Rect(max)
            currentBoundsCache = Rect(cur)
            displayBoundsCache = Rect(
                minOf(max.left, cur.left),
                minOf(max.top, cur.top),
                maxOf(max.right, cur.right),
                maxOf(max.bottom, cur.bottom),
            )
            geometryRotation = liveRotation()
        }.onFailure { logger(Log.WARN, "MON_GEOMETRY_REFRESH_FAILED", it) }
    }

    /**
     * `Display.getRotation()` — a field read of the DisplayInfo the framework caches and keeps
     * up to date for every process through its own display listener (no binder, no
     * WindowManager call, safe on the input thread). -1 when it cannot be read at all.
     */
    private fun liveRotation(): Int = runCatching {
        val display = geometryDisplay ?: (context.display ?: wm.defaultDisplay).also { geometryDisplay = it }
        display.rotation
    }.getOrDefault(-1)

    /** id of the display this monitor's geometry describes (defaults to the primary one). */
    private fun liveDisplayId(): Int = runCatching {
        val display = geometryDisplay ?: (context.display ?: wm.defaultDisplay).also { geometryDisplay = it }
        display.displayId
    }.getOrDefault(android.view.Display.DEFAULT_DISPLAY)

    /**
     * Live window bounds from this process's `Resources` — a LOCAL read (no WindowManager, no
     * binder), used only as the belt for [geometryIsStale].
     *
     * `Configuration.windowConfiguration` is @hide, so it goes through the same reflection the
     * freeform side uses (`MiuiFreeform.freeformTask`); `null` when unavailable, in which case
     * the rotation check alone decides.
     */
    private fun liveConfigBounds(): Rect? = runCatching {
        val config = context.resources.configuration
        val windowConfig = Configuration::class.java
            .getMethod("getWindowConfiguration").invoke(config) ?: return null
        windowConfig.javaClass.getMethod("getBounds").invoke(windowConfig) as? Rect
    }.getOrNull()

    /** True while the cached geometry still describes the display's current orientation. */
    private fun geometryIsStale(): Boolean {
        val cached = displayBoundsCache
        if (cached.width() <= 0 || cached.height() <= 0) return true
        val live = liveRotation()
        if (live >= 0 && geometryRotation >= 0 && live != geometryRotation) return true
        // Belt: a rotation between the refresh and the listener callback can also be caught by
        // the (local) configuration bounds, whose longer side must match the cache's.
        val config = liveConfigBounds() ?: return false
        if (config.width() <= 0 || config.height() <= 0) return false
        return (config.width() > config.height()) != (cached.width() > cached.height())
    }

    /**
     * The physical display rect for the CURRENT orientation, rebuilt locally when the cache
     * belongs to the previous one (see [geometryRotation]). Rotation swaps the two sides and
     * nothing else, so the stale cache is enough to answer correctly for this event instead of
     * rejecting the stroke; the real metrics are re-read on the main thread right after.
     */
    private fun displayBoundsForEvent(): Rect {
        val cached = displayBoundsCache
        if (!geometryIsStale()) return cached
        mainHandler.post(::refreshGeometry)
        val long = maxOf(cached.width(), cached.height())
        val short = minOf(cached.width(), cached.height())
        if (long <= 0 || short <= 0) return cached
        val landscape = when (liveRotation()) {
            android.view.Surface.ROTATION_90, android.view.Surface.ROTATION_270 -> true
            android.view.Surface.ROTATION_0, android.view.Surface.ROTATION_180 -> false
            else -> liveConfigBounds()?.let { it.width() > it.height() } ?: false
        }
        return if (landscape) Rect(0, 0, long, short) else Rect(0, 0, short, long)
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
     * [imeVisibleHeightPx]), removed in [dispose] so a hot reload cannot leave a stale window
     * behind.
     *
     * CREATED UP FRONT (start), NEVER FROM AN INPUT CALLBACK. It used to be built lazily on
     * the first corner DOWN, and `WindowManager.addView` is a window add: two binder
     * round-trips to system_server, a relayout and an insets dispatch, on the thread that was
     * supposed to pilfer the pointer stream a few milliseconds after the touch. Every
     * SystemUI start therefore had exactly one corner gesture that could not win that race —
     * the first one — and it went HOME with the fan, which is the "重启系统界面后第一次角落手势"
     * report. The insets listener installed here keeps [imeHeightCache] current afterwards, so
     * the DOWN path only reads a field.
     */
    private var insetProbe: View? = null

    /** true once [ensureInsetProbe] has been attempted; a second failure is not logged again */
    @Volatile
    private var insetProbeAttempted = false

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

    /** read on the input thread, written on the main thread by [applySettings] */
    @Volatile
    private var settings = RemotePrefs.read(prefs)
    private var fan: FanHost? = null

    // ------------------------------------------------------------ transport A state

    private var monitor: InputMonitor? = null
    private var receiver: InputEventReceiver? = null

    /**
     * The thread the gesture monitor's events are handled on — NOT the main looper.
     *
     * The DOWN path is a race against MiuiHome's native gesture code (see the cached-state
     * block above), and the main looper is the one thread guaranteed to be busy exactly when
     * it matters: SystemUI's startup right after a restart, the fan's window work, the
     * contact bar, a panel rebuild. Queuing a 2 ms-critical callback behind that work is what
     * "the shield fails after a while" looks like. Everything that touches windows still hops
     * to [mainHandler]; this thread only decides, pilfers and forwards.
     */
    private var inputThread: HandlerThread? = null
    private var inputHandler: Handler? = null

    /**
     * 看门狗的线程 —— **和输入线程分开**（见 [stateWatchdog]：曾经它在输入 Looper 上，每 250ms 两次
     * binder 调用，正好把偶尔一笔 DOWN 顶到 15ms，输给 launcher）。
     */
    private var stateThread: HandlerThread? = null
    private var stateHandlerRef: Handler? = null

    /** set once the gesture-monitor transport has failed to come up for good (see [startMonitorTransport]) */
    @Volatile
    private var monitorTransportDisabled = false

    private var receiverCreateFailures = 0

    @Volatile
    private var stroke: Stroke? = null

    /** 时延样本（`MON_LAT` / `MON_LAT_SLOW` 的来源，见 [LatencyStats]）。只在输入线程上读写。 */
    private val latency = LatencyStats()

    /** `onMonitorEvent` 的第一时刻：每个事件的投递时间。 */
    private var downRecvUptime = 0L

    /**
     * OBSERVE-FIRST, CLAIM-ON-TRAVEL — WITH ONE DELIBERATE EXCEPTION AT DOWN.
     *
     * The stroke itself is never taken for a tap's sake: this monitor carries the
     * DO_NOT_PILFER bit, so it keeps receiving the whole gesture even while another window
     * takes the pointers, and the drawer is claimed only in [Stroke.move] on
     * `SpyAction.Activate` — after the finger has travelled far enough to be a drawer
     * gesture. A tap therefore stays the app's own and we inject nothing. (Two earlier
     * designs that took the DOWN unconditionally were removed for exactly that: the app was
     * left holding DOWN…CANCEL, and rebuilding its click with a synthetic tap typed the
     * bottom-left keyboard key twice.)
     *
     * THE EXCEPTION is the bottom strip MiuiHome owns: there the DOWN IS pilfered, because a
     * corner swipe that lets the launcher keep its stream goes HOME *and* opens the fan —
     * MiuiHome's recogniser "decides from the first upward sample" (see
     * RemotePrefs.KEY_ARBITER_REGION), so nothing we do later in the stroke can take that
     * back. That pilfer is the ONLY reason the corner shield works, and it is a race:
     * measured 2 ms from the dispatcher publishing the DOWN to our `pilferPointers`, against
     * a launcher that answers with `MiuiInputManagerService: app requestRedirect` or its own
     * DOWN pilfer inside the same millisecond. [beginMonitorStroke] therefore keeps its work
     * to cached field reads plus that one binder call, and runs on [inputThread].
     *
     * Taps inside the strip are rebuilt (see [injectTap]) — the price of owning those DOWNs —
     * and are NOT rebuilt while the keyboard is up, where the app must serve its own taps.
     */

    /** True while the gesture-monitor transport owns corner capture. */
    var monitorActive: Boolean = false
        private set

    /**
     * Which transport corner capture is (or is about to be) running on — the honest answer to
     * "can this process shield a corner swipe from HOME", for the readiness log and for the
     * diagnostics page.
     *
     * `pending` is a real state and must not be reported as a fallback: [start] applies the
     * settings on the main thread, so a caller that runs earlier (the hot-reload entry point)
     * used to print `transport=spy-view-fallback` for a monitor whose gesture monitor came up
     * 8 ms later — a false alarm the diagnostics then repeated.
     */
    val transport: String
        get() = when {
            monitorActive -> "gesture-monitor"
            monitorTransportDisabled -> "spy-view-fallback"
            settings.enabled && (settings.left || settings.right) -> "pending"
            else -> "disabled"
        }

    /**
     * Freeform-side tap interception, offered every monitor event BEFORE the corner gate.
     * CALLED ON [inputThread] (any thread-safe implementation is fine; window work is not).
     */
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
        // The cached geometry has to be in place BEFORE the transport starts: the input
        // thread's warm-up and the very first DOWN both read it, and it may only be computed
        // here (WindowManager), never there.
        if (Looper.myLooper() == mainHandler.looper) {
            refreshGeometry()
            refreshState()
            applySettings()
        } else {
            mainHandler.post {
                refreshGeometry()
                refreshState()
                applySettings()
            }
        }
        // Kept as a field so dispose() can take it back off: a lambda registered inline is
        // unreachable afterwards, and every hot reload left another live listener behind.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> scheduleApplySettings() }
        prefsListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
        registerDebugReceiver()
        registerEnvironmentListeners()
        // Pre-warm FanHost and cached apps in background so the very first swipe has apps ready
        mainHandler.post { runCatching { ensureFan() } }
        // Enumeration ground truth for the fan's data source (this process's uid) — install
        // time is exactly the state a reboot leaves behind, which is what the panel bug needs
        // compared against a later DEBUG_PROBE run. See AppEnumProbe.
        Thread { runCatching { AppEnumProbe.run(context, logger, deep = false, prefs = prefs) } }.start()
        logger(Log.INFO, "MONITOR_INSTANCE_STARTED id=${System.identityHashCode(this)}", null)
    }

    /**
     * Keep the cached environment fresh without ever asking for it on the DOWN path:
     *  • screen on/off + user present — the two moments the gate's answer changes;
     *  • **rotation** — via `DisplayManager`, the only rotation signal that does not depend on
     *    the host Application forwarding configuration changes (HyperOS's
     *    `SystemUIApplicationImpl` need not, which is how the corner gate ended up testing a
     *    portrait rect in landscape — see [geometryRotation]);
     *  • configuration changes — same thing from the app side, kept as a second opinion;
     *  • keyguard state — event-driven, so "locked" cannot be a 250 ms stale guess.
     * All of them are registered in [start] and released in [dispose]; a hot reload that left
     * one behind would keep a dead instance's cache alive (same failure the prefs listener
     * and the debug receiver had, see [dispose]).
     */
    private fun registerEnvironmentListeners() {
        if (displayListener == null) {
            val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            val listener = object : android.hardware.display.DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit
                override fun onDisplayRemoved(displayId: Int) = Unit
                override fun onDisplayChanged(displayId: Int) {
                    if (disposed) return
                    if (displayId != liveDisplayId()) return
                    mainHandler.post {
                        refreshGeometry()
                        refreshState()
                    }
                }
            }
            runCatching { dm?.registerDisplayListener(listener, mainHandler) }
                .onSuccess { displayListener = listener }
                .onFailure { logger(Log.WARN, "MON_DISPLAY_LISTENER_FAILED", it) }
        }
        if (environmentReceiver == null) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    if (disposed) return
                    mainHandler.post {
                        refreshGeometry()
                        refreshState()
                    }
                }
            }
            runCatching {
                context.registerReceiver(
                    receiver,
                    android.content.IntentFilter().apply {
                        addAction(android.content.Intent.ACTION_SCREEN_ON)
                        addAction(android.content.Intent.ACTION_SCREEN_OFF)
                        addAction(android.content.Intent.ACTION_USER_PRESENT)
                    },
                    android.content.Context.RECEIVER_NOT_EXPORTED,
                )
            }.onSuccess { environmentReceiver = receiver }
                .onFailure { logger(Log.WARN, "MON_ENV_RECEIVER_FAILED", it) }
        }
        if (componentCallbacks == null) {
            val callbacks = object : android.content.ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                    refreshGeometry()
                }

                override fun onLowMemory() = Unit
            }
            runCatching { context.registerComponentCallbacks(callbacks) }
                .onSuccess { componentCallbacks = callbacks }
                .onFailure { logger(Log.WARN, "MON_CONFIG_CALLBACK_FAILED", it) }
        }
        if (keyguardListener == null) {
            // API 33+: the locked state arrives as an event instead of a binder call we would
            // have to make on the input path.
            val listener = KeyguardManager.KeyguardLockedStateListener { locked ->
                keyguardLocked = locked
            }
            runCatching {
                keyguardManager?.addKeyguardLockedStateListener(context.mainExecutor, listener)
            }.onSuccess { keyguardListener = listener }
                .onFailure { logger(Log.WARN, "MON_KEYGUARD_LISTENER_FAILED", it) }
        }
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
                // A broadcast already in flight when the hot reload disposed this instance
                // must not resurrect the fan (see dispose()).
                if (disposed) return
                when (i?.action) {
                    ACTION_DEBUG_MORE -> mainHandler.post { ensureFan().toggleMorePanelForDebug() }
                    ACTION_DEBUG_PRESSURE -> {
                        // 只有 adb（shell/root）能驱动这条：它会写校准状态（进而改重按阈值），
                        // 不能让它变成任何 App 都能按的按钮。`am broadcast` 的发送方是 shell。
                        val uid = runCatching { sentFromUid }.getOrNull()
                        if (uid == null || uid > android.os.Process.SHELL_UID) {
                            logger(Log.WARN, "DEBUG_PRESSURE_REJECTED uid=$uid", null)
                            return
                        }
                        val command = i.getStringExtra(EXTRA_DEBUG_PRESSURE_CMD).orEmpty()
                        val delta = i.getFloatExtra(EXTRA_DEBUG_PRESSURE_DELTA, 0f)
                        mainHandler.post { ensureFan().debugPressureCalibration(command, delta) }
                    }
                    AppEnumProbe.ACTION -> Thread { runCatching { AppEnumProbe.run(context, logger, prefs = prefs) } }.start()
                }
            }
        }
        runCatching {
            context.registerReceiver(
                r,
                android.content.IntentFilter().apply {
                    addAction(ACTION_DEBUG_MORE)
                    addAction(ACTION_DEBUG_PRESSURE)
                    addAction(AppEnumProbe.ACTION)
                },
                android.content.Context.RECEIVER_EXPORTED,
            )
        }.onSuccess {
            debugReceiver = r
            logger(
                Log.INFO,
                "DEBUG_MORE_READY action=$ACTION_DEBUG_MORE probe=${AppEnumProbe.ACTION} " +
                    "instance=${System.identityHashCode(this)}",
                null,
            )
        }.onFailure { logger(Log.WARN, "DEBUG_MORE_REGISTER_FAILED", it) }
    }

    private var debugReceiver: android.content.BroadcastReceiver? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** environment refreshers registered in [registerEnvironmentListeners], released in [dispose] */
    private var environmentReceiver: android.content.BroadcastReceiver? = null
    private var componentCallbacks: android.content.ComponentCallbacks? = null
    private var keyguardListener: KeyguardManager.KeyguardLockedStateListener? = null
    private var displayListener: android.hardware.display.DisplayManager.DisplayListener? = null

    /** set by [dispose]; every receiver entry point checks it before touching the runtime */
    @Volatile
    private var disposed = false

    /**
     * Tear everything down for an API-102 HOT RELOAD (no reboot, no SystemUI restart):
     * the monitor and its input channel, the SPY-view fallback windows and the fan all
     * belong to the OLD classloader and must not survive the swap — otherwise the reload
     * leaves a second subscriber on the same gestures and a stale full-screen window that
     * swallows touches. `onHotReloaded` re-installs a fresh instance afterwards.
     *
     * EVERY registration made in [start] has to be undone here. The debug receiver and the
     * preferences listener were missing until 2026-10-09, and the cost was exactly what a
     * hot reload makes easy to miss: `dumpsys activity broadcasts` showed TWO
     * `com.repl.bubbledrawer.action.DEBUG_MORE` receivers in one SystemUI process, both from
     * live (one current, one stale) CornerInputMonitors. A single broadcast was therefore
     * served twice, each stale instance lazily built its OWN FanHost with no gesture monitor
     * but full window rights, and the panel came up twice, stacked — dismissing the top one
     * left an identical copy that "could not be closed" (outside tap and app launch both
     * only hid the top). The gesture path was never affected, which is why the fan's own
     * 更多 tile behaved.
     */
    fun dispose() {
        disposed = true
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { stopMonitorTransport() }
        for (side in bindings.keys.toList()) {
            val binding = bindings[side] ?: continue
            runCatching { removeBinding(side, binding) }
        }
        runCatching { fan?.destroy() }
        fan = null
        debugReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        debugReceiver = null
        environmentReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        environmentReceiver = null
        componentCallbacks?.let { runCatching { context.unregisterComponentCallbacks(it) } }
        componentCallbacks = null
        displayListener?.let { listener ->
            runCatching {
                context.getSystemService(android.hardware.display.DisplayManager::class.java)
                    ?.unregisterDisplayListener(listener)
            }
        }
        displayListener = null
        keyguardListener?.let { runCatching { keyguardManager?.removeKeyguardLockedStateListener(it) } }
        keyguardListener = null
        prefsListener?.let { runCatching { prefs.unregisterOnSharedPreferenceChangeListener(it) } }
        prefsListener = null
        removeInsetProbe()
        stroke = null
        monitorActive = false
        logger(Log.INFO, "MON_DISPOSED_FOR_HOT_RELOAD id=${System.identityHashCode(this)}", null)
    }

    /**
     * 一次设置写入会**逐 key** 回调监听器：设置页保存一次要改二十来个 key，于是 `applySettings()`
     * 在一次点击里跑二十来遍（日志上就是同一毫秒里二十行一模一样的 `PREFS_APPLIED`，每行还都要
     * 走一遍 geometry/小窗快照/图标的读取）。
     *
     * 设备实测（2026-10-11）：LSPosed 把共享组镜像过来时是**一条一条**写的，所以"合并到主线程同一轮"
     * 不够 —— 同一毫秒里仍然出现 11 行。改成**按时间合并**：最后一次回调之后 [APPLY_SETTINGS_DEBOUNCE_MS]
     * 统一应用一次。合并是安全的，[applySettings] 每次都从 prefs 重新读，晚一点读到的只会更完整。
     */
    private fun scheduleApplySettings() {
        if (Looper.myLooper() != mainHandler.looper) {
            mainHandler.post(::scheduleApplySettings)
            return
        }
        mainHandler.removeCallbacks(applySettingsTask)
        mainHandler.postDelayed(applySettingsTask, APPLY_SETTINGS_DEBOUNCE_MS)
    }

    private val applySettingsTask = Runnable { applySettings() }

    private fun applySettings() {
        settings = RemotePrefs.read(prefs)
        val wanted = settings.enabled && (settings.left || settings.right)
        if (wanted) startMonitorTransport() else stopMonitorTransport()
        // The SPY-view fallback only runs while the monitor transport is unavailable.
        updateSide(SpySide.LEFT, !monitorActive && wanted && settings.left)
        updateSide(SpySide.RIGHT, !monitorActive && wanted && settings.right)
        // 气压校准的会话请求还在 → 保证 FanHost 存在。它是懒创建的，而「补上会话」只在 FanHost 的
        // 构造函数里跑一次（INIT_RESUME）：热重载/重启系统界面之后，用户早就点过的「开始校准」不该
        // 等到下一次划扇形才被受理 —— 否则设置页会一直停在「校准中」，看起来像功能坏了。
        if (settings.fanPressureCalibrationActive) mainHandler.post { runCatching { ensureFan() } }
        // change-only trace (fires on remote preference notifications)
        logger(
            Log.INFO,
            "PREFS_APPLIED enabled=" + settings.enabled + " left=" + settings.left +
                " right=" + settings.right + " range=" + settings.rangeDp +
                // `+` binds tighter than `if`, so the if-expression MUST be parenthesized here:
                // unparenthesized, `… + if (monitorActive) "monitor" else "spy-view" + " " + …`
                // parses as `… + (if (…) "monitor" else ("spy-view" + " " + …))` — i.e. with the
                // gesture monitor up (the normal case) the geometry, the freeform snapshot and
                // everything appended after it were silently dropped from this line.
                " transport=" + (if (monitorActive) "monitor" else "spy-view") +
                " " + boundsForLog() +
                // The freeform half of the same snapshot: this process acts on THESE values, so a
                // settings page that shows something else is a lost push, not a UI bug (see
                // RemoteBridge.syncLocal).
                " freeform[enabled=" + settings.flymeFreeformEnabled +
                " center=" + settings.flymeFreeformCenter +
                " vpos=" + settings.flymeFreeformVerticalPct +
                " scale=" + settings.flymeFreeformScale +
                " mini=" + settings.flymeFreeformSwipeUpMini +
                " noOffset=" + settings.flymeFreeformNoOffset +
                " dim=" + settings.flymeFreeformDimBg + "]" +
                // 图标那一半（见 AppRepository 的分身图标）：分身图标一律取主空间那张，这一项只管角标。
                " icon[cloneBadge=" + settings.cloneBadge + "]",
            null,
        )
    }

    // ------------------------------------------------------------ transport A

    private fun startMonitorTransport() {
        if (monitorActive || monitorTransportDisabled) return
        try {
            // Assign before anything else can throw: a monitor we created but failed to
            // wire must still be disposed, or its input channel leaks in system_server.
            val created = createMonitor()
            monitor = created
            val channel = created.inputChannel
            // The receiver belongs on its OWN thread (see [inputThread]).
            // THREAD_PRIORITY_URGENT_DISPLAY is the priority the input dispatcher itself uses
            // for gesture handling: this callback is a deadline, not housekeeping — it has
            // milliseconds to pilfer the stream before MiuiHome's native recogniser owns the
            // gesture.
            //
            // **这条 Looper 上只许有输入回调**：Looper 要等当前消息跑完才回到 epoll，任何排在输入
            // 通道 fd 前面的活都会变成 DOWN 的时延（看门狗就这样被挪走了，见 [stateWatchdog]）。
            // 需要"顺便跑一下"的东西一律另开线程或发去主线程。
            val thread = HandlerThread(
                "bubbledrawer-corner-input",
                android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY,
            )
            thread.start()
            val handler = Handler(thread.looper)
            inputThread = thread
            inputHandler = handler
            // The IME probe is a WINDOW (see [insetProbe]); it belongs to a moment with no
            // deadline — never to the first corner DOWN, where it used to cost the gesture — and
            // it belongs to the corner feature being ON, so it is created here rather than for
            // every process that merely loads the module.
            mainHandler.post { if (!disposed) ensureInsetProbe() }
            // Counts as up as soon as the channel exists: applySettings() must NOT fall back to
            // the SPY-view windows just because the receiver is created a beat later, and the
            // creation cannot fail in practice (a failure is logged and retried from scratch).
            monitorActive = true
            // channel.token is @hide too: never let a diagnostics call decide whether the
            // transport counts as up.
            val tokenDesc = runCatching { channel.token.toString() }.getOrElse { "?" }
            handler.post {
                if (disposed || monitor !== created) return@post
                try {
                    receiver = object : InputEventReceiver(channel, thread.looper) {
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
                    logger(
                        Log.INFO,
                        "MON_INPUT_READY name=$MONITOR_NAME display=$DISPLAY_ID channel=$tokenDesc " +
                            boundsForLog() + " thread=${thread.name}",
                        null,
                    )
                    warmUp()
                    // 看门狗走自己的线程（曾经在输入线程上，见 [stateWatchdog]）——
                    // 这一行是给用户日志留的证据：两个线程名必须是分开的。
                    val state = stateHandler()
                    logger(
                        Log.INFO,
                        "MON_STATE_THREAD name=${stateThread?.name} input=${thread.name} " +
                            "every=${STATE_REFRESH_MS}ms",
                        null,
                    )
                    state.post(stateWatchdog)
                } catch (t: Throwable) {
                    // Recovery is a full restart of the transport (the receiver cannot be built
                    // twice on the same channel anyway), but it must not become a loop: after a
                    // couple of attempts the monitor transport is declared unusable and the
                    // SPY-view fallback takes over — the same degradation a failed
                    // monitorGestureInput gets.
                    logger(Log.WARN, "MON_RECEIVER_CREATE_FAILED", t)
                    receiverCreateFailures++
                    mainHandler.post {
                        runCatching { stopMonitorTransport() }
                        if (receiverCreateFailures > MAX_RECEIVER_ATTEMPTS) {
                            monitorTransportDisabled = true
                            logger(
                                Log.ERROR,
                                "MON_RECEIVER_GIVING_UP attempts=$receiverCreateFailures " +
                                    "— 角落采集降级为 SPY 窗口",
                                null,
                            )
                        }
                        applySettings()
                    }
                }
            }
        } catch (t: Throwable) {
            monitorActive = false
            logger(Log.WARN, "MON_INPUT_UNAVAILABLE_FALLBACK_SPY_VIEW", t)
            disposeMonitor()
        }
    }

    /**
     * Power/keyguard refresher. It lives on the input thread (mostly idle) and never on the
     * DOWN path, so the gate's two binder calls cost the gesture nothing even in the worst
     * case — the first touch after a long idle, when both system_server and this process are
     * cold.
     *
     * THE PERIOD DEPENDS ON THE SCREEN, because this is the one piece of the corner feature
     * that runs without a finger on the glass. While the display is off no corner gesture can
     * arrive at all (and the gate must answer "not interactive" while it lasts), so the timer
     * slows from [STATE_REFRESH_MS] to [STATE_REFRESH_SCREEN_OFF_MS]: four wakeups per second
     * with two binder round-trips each is small next to a lit display, but during standby it is
     * pure waste — it keeps the SoC out of its deepest idle states and wakes system_server for
     * an answer nobody is waiting for. The screen on/off receiver and the keyguard listener do
     * the real work at the transitions; the slow tick is only a safety net, so a broadcast that
     * never arrives cannot leave the gate stuck.
     */
    /**
     * 看门狗：刷新电源/锁屏快照 + 发现几何失效就回主线程重取。
     *
     * **它必须跑在自己的线程上，绝不能和输入接收器共用 Looper。** 这里每 [STATE_REFRESH_MS]（250ms）跑一次，
     * 每次 `refreshState()` 都要两次 binder 往返（`PowerManager.isInteractive` / `isKeyguardLocked`）——
     * 而 Looper 只有在消息跑完之后才会回到 `epoll_wait`，输入通道的 fd 回调是**排在消息后面**的：
     * 手指正好落在这几毫秒里，DOWN 就要排队，`lat` 偶尔就到 15ms，输给 launcher 在 DOWN 后 ~8ms 认下的
     * HOME —— 这正是用户 2026-10-12 反馈的「边角屏蔽手势时灵时不灵」。曾经它就在输入线程上（见
     * `MON_DOWN` 的 `recv=`/`pil=` 拆分：那次是 `recv` 大，不是我们处理慢）。
     */
    private val stateWatchdog = object : Runnable {
        override fun run() {
            refreshState()
            // Self-heal, two ways: a geometry refresh that failed (or raced SystemUI's start-up)
            // must not leave the corner gate with an empty display, and a ROTATION that no
            // listener reported must not leave it with the previous orientation's — with either
            // one, every DOWN falls outside the sector and the fan cannot be opened at all.
            if (geometryIsStale()) {
                mainHandler.post(::refreshGeometry)
            }
            stateHandler().postDelayed(
                this,
                if (deviceInteractive) STATE_REFRESH_MS else STATE_REFRESH_SCREEN_OFF_MS,
            )
        }
    }

    /**
     * 看门狗自己的线程（懒建）。优先级 `THREAD_PRIORITY_BACKGROUND`：它是纯家务，两次 binder 调用
     * 慢一点没关系；关键是**别挡着输入**、也别占主线程（那样每秒四次 binder 卡的是 SystemUI 的 UI）。
     */
    private fun stateHandler(): Handler {
        stateHandlerRef?.let { return it }
        val thread = HandlerThread("bubbledrawer-corner-state", android.os.Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        stateThread = thread
        return Handler(thread.looper).also { stateHandlerRef = it }
    }

    /**
     * Touch the DOWN path once per SystemUI start so the first real corner gesture does not
     * pay for class loading, hidden-API linkage and JIT on the race's critical path. Pure
     * maths plus one throwaway [CornerGestureEngine] — no pilfer, no window, no log per call.
     */
    private fun warmUp() {
        val started = SystemClock.uptimeMillis()
        runCatching {
            repeat(WARMUP_ROUNDS) {
                val bounds = displayBoundsCache
                sideFor(bounds.right - 1f, bounds.bottom - 1f)
                sideFor(1f, bounds.bottom - 1f)
                AdaptiveSpyGestureConfig.create(
                    displayWidth = bounds.width().toFloat(),
                    displayHeight = bounds.height().toFloat(),
                    touchSlop = touchSlop,
                    density = density,
                    triggerRangeDp = settings.rangeDp,
                    leftEnabled = settings.left,
                    rightEnabled = settings.right,
                )
                val engine = CornerGestureEngine()
                engine.down(0, bounds.right - 1f, bounds.bottom - 1f, SpySide.RIGHT)
                engine.up(0)
                isUnlockedAndInteractive()
                cornerBoxPx()
            }
        }.onFailure { logger(Log.WARN, "MON_WARMUP_FAILED", it) }
        logger(
            Log.INFO,
            "MON_WARMUP rounds=$WARMUP_ROUNDS ms=${SystemClock.uptimeMillis() - started} " +
                boundsForLog(),
            null,
        )
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

    /**
     * Teardown happens ON the input thread: the receiver, the monitor's channel and the live
     * [Stroke] all belong to that looper, so disposing them from the main thread would race the
     * very callbacks that are still arriving. The thread quits straight afterwards, which also
     * drops the watchdog.
     */
    private fun stopMonitorTransport() {
        if (!monitorActive && monitor == null) return
        // The corner feature is not wanted (or is being rebuilt): the probe window goes with it,
        // so a disabled feature costs no window at all. It is re-created on the next start.
        removeInsetProbe()
        val thread = inputThread
        val toDispose = receiver
        val monitorToDispose = monitor
        receiver = null
        monitor = null
        inputThread = null
        inputHandler = null
        monitorActive = false
        val cleanup = Runnable {
            stroke?.cancel()
            stroke = null
            runCatching { toDispose?.dispose() }
            runCatching { monitorToDispose?.dispose() }
            thread?.quitSafely()
        }
        val handler = thread?.takeIf { it.isAlive }?.let { Handler(it.looper) }
        if (handler != null) handler.post(cleanup) else cleanup.run()
        logger(Log.INFO, "MON_INPUT_STOPPED", null)
    }

    private fun disposeMonitor() {
        stroke = null
        runCatching { receiver?.dispose() }
        runCatching { monitor?.dispose() }
        receiver = null
        monitor = null
        inputThread?.quitSafely()
        inputThread = null
        inputHandler = null
        stateThread?.quitSafely()
        stateThread = null
        stateHandlerRef = null
    }

    /** One dispatcher event from the gesture monitor; returns whether WE claimed the stream. */
    private fun onMonitorEvent(event: InputEvent): Boolean {
        // 这一行必须是**第一个**语句：它是"dispatcher 把事件投递到我们线程"的时刻，`MON_DOWN` 的
        // `recv=` 就是它减去 `ev.downTime` —— 判断"是别人挡了我们的线程"还是"我们自己处理慢"全靠它。
        downRecvUptime = SystemClock.uptimeMillis()
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
                    if (!s.tracking) {
                        // The stroke already ended (engine cancel, second finger, UP); `stroke`
                        // is cleared in Stroke.end(), so reaching here means a stroke that was
                        // never armed. Logging it against the stale origin used to print whole
                        // screens of MON_STROKE_END lines for touches that began elsewhere and
                        // buried the real gestures.
                        return@let
                    }
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
                // MiuiHome's swipe-up monitor / its `app requestRedirect` taking over. KEEP THE
                // STROKE ALIVE: our monitor carries the DO_NOT_PILFER bit (see MONITOR_NAME), so
                // the dispatcher keeps publishing the rest of the gesture to us and we can still
                // decide later. Robbing it back HERE (what this code used to do) made US the
                // owner of every tap in the strip, and the app — which had already received
                // the real DOWN — then needed a second, synthetic tap to get a click, which is
                // exactly the "tapping the bottom-left corner types twice" bug. Instead we stay
                // an observer (unless the DOWN already owned the strip, see [inPreOwnStrip]) and
                // otherwise only pilfer on Activate (Stroke.move), i.e. once the finger has
                // really travelled far enough to be a drawer gesture.
                val s = stroke
                if (s != null && s.tracking && !s.claimed) {
                    s.robbedByOtherAt = SystemClock.uptimeMillis()
                }
                logger(
                    Log.INFO,
                    "MON_SYSTEM_CANCEL tracking=${stroke?.tracking} claimed=${stroke?.claimed} " +
                        "kept=${s != null && s.tracking && !s.claimed} " +
                        "owned=${s?.owned} " +
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
        // PRE-OWN THE DOWN inside the bottom strip — that is what stops MiuiHome from
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
        //
        // NOTHING BELOW MAY BLOCK. Every value here is a cached field (see the cached-state
        // block) and the only call left is `pilferPointers()` itself — measured end to end at
        // ~2 ms on this device, which is the whole margin against the launcher's own
        // take-over. `lat` is that measurement, per stroke, in the log: it is the number to
        // read when a corner swipe goes HOME with the fan.
        val inPreOwnStrip = inPreOwnStrip(y)
        val imePx = imeVisibleHeightPx()
        val bounds = displayBounds()
        val crop = arbiterCropState
        val cropped = crop?.excludes(
            x, y, bounds.width(), bounds.height(), liveRotation(), bootCount, ev.downTime,
        ) == true
        // 当 system_server 已经把桌面的 `[Gesture Monitor] swipe-up` 裁出左右下角时（`cropped == true`），
        // 角落的 ACTION_DOWN 根本不会发给 com.miui.home，因此不再需要在按下瞬间调 `pilferPointers()` 抢跑：
        // 保持纯观察（`owned = false, mode=cropped`），普通点击和短距离滑动全部原封不动留给前景 App，
        // 只有手指真正滑满触发阈值（`Stroke.move -> SpyAction.Activate`）时才接管指针流打开扇面。
        // 仅当 `system_server` 裁剪未生效时，才回退到旧的 DOWN 预抢占（`mode=pre-own`）。
        val owned = !cropped && imePx <= 0 && inPreOwnStrip && pilferMonitor()
        val now = SystemClock.uptimeMillis()
        // 时延拆成两段：recv = dispatcher 投递到我们线程（大了说明我们的 Looper 被别的活挡住 / 系统忙），
        // pil = 我们的处理 + binder `pilferPointers`（大了说明是我们自己慢）。lat 是这两段之和，
        // 也是和 launcher 抢的那条线（它约 8ms 就认下 HOME）。
        val recv = (downRecvUptime - ev.downTime).coerceAtLeast(0)
        val pil = (now - downRecvUptime).coerceAtLeast(0)
        val lat = recv + pil
        if (!cropped) {
            latency.record(lat.toInt())
            if (lat > latency.slowMs) {
                logger(
                    Log.WARN,
                    "MON_LAT_SLOW side=$side lat=$lat recv=$recv pil=$pil owned=$owned band=$inPreOwnStrip " +
                        "ime=${imePx}px " + latency.summary(),
                    null,
                )
            } else if (latency.dueForReport(LAT_REPORT_EVERY)) {
                logger(Log.INFO, "MON_LAT " + latency.summary(), null)
            }
        }
        val mode = when {
            cropped -> "cropped"
            owned -> "pre-own"
            else -> "observe"
        }
        logger(
            Log.INFO,
            "MON_DOWN side=$side x=$x y=$y box=${cornerBoxPx()} band=$inPreOwnStrip " +
                "ime=${imePx}px mode=$mode " +
                "cropAge=${crop?.let { ev.downTime - it.confirmedAt } ?: -1} " +
                "lat=$lat recv=$recv pil=$pil " + boundsForLog(),
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
     * updates its insets whenever the keyboard shows or hides — the listener installed in
     * [ensureInsetProbe] stores them in [imeHeightCache] — so the DOWN path only reads a field.
     */
    private fun imeVisibleHeightPx(): Int = imeHeightCache

    /** Drops the IME probe window (main thread, it is a window) and forgets its cached height. */
    private fun removeInsetProbe() {
        insetProbe?.let { probe -> runCatching { wm.removeViewImmediate(probe) } }
        insetProbe = null
        insetProbeAttempted = false
        imeHeightCache = 0
    }

    /**
     * Adds the IME-inset probe window (see [imeVisibleHeightPx]) and keeps [imeHeightCache]
     * current from its insets listener. MAIN THREAD ONLY (it is a window), and called from
     * [startMonitorTransport] — never from an input callback, which is the whole point of the
     * cache.
     */
    private fun ensureInsetProbe(): View? {
        insetProbe?.let { return it }
        if (insetProbeAttempted && insetProbe == null) return null
        insetProbeAttempted = true
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
            // Every inset change lands here instead of in the DOWN path. Dispatch happens on
            // the thread that owns the view (this one).
            view.setOnApplyWindowInsetsListener { _, insets ->
                imeHeightCache = runCatching {
                    insets.getInsets(WindowInsets.Type.ime()).bottom
                }.getOrDefault(0)
                insets
            }
            wm.addView(view, lp)
            // Read once right away: the listener only fires on a change, and the keyboard may
            // already be up when this window appears.
            imeHeightCache = runCatching {
                view.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
            }.getOrDefault(0)
            insetProbe = view
            view
        }.onFailure {
            logger(Log.WARN, "MON_IME_PROBE_FAILED", it)
            // Without the probe the gate cannot see the keyboard, so it will pre-own over it
            // (corner taps over a keyboard then need the synthetic tap). Say so once.
            logger(Log.WARN, "MON_IME_PROBE_MISSING — the corner strip will be owned even over the keyboard", null)
        }.getOrNull()
    }

    private fun bottomExtentPx(): Float =
        (CornerTriggerRegion.radiusPx(settings.bottomDp, density) + 0.5f).toInt().toFloat()

    private fun edgeExtentPx(): Float =
        (CornerTriggerRegion.radiusPx(settings.edgeDp, density) + 0.5f).toInt().toFloat()

    /**
     * Is this DOWN inside the strip whose pointer stream we must take at DOWN?
     *
     * Always true in practice, and deliberately so: the caller has already accepted the touch
     * as a corner DOWN ([sideFor]), so this test only ever *subtracts* from our own trigger
     * area. It used to subtract a lot: the strip was a fixed 28 dp against MiuiHome's measured
     * 21–24 dp take-over band, while the trigger area's vertical axis is the user's
     * "沿侧边向上" setting and reaches up to 160 dp. Every DOWN in the upper part of the sector
     * therefore armed a stroke WITHOUT owning its DOWN — the fan opened and MiuiHome was still
     * free to commit HOME from the same gesture. The strip is now the larger of the two: the
     * launcher's observed band (with its margin) or the sector's own reach.
     */
    private fun inPreOwnStrip(y: Float): Boolean {
        val bottom = displayBounds().bottom
        val reach = maxOf(HOME_GESTURE_BAND_DP * density, edgeExtentPx())
        return y >= bottom - reach
    }

    /**
     * Physical display bounds for the corner gate — the CACHED copy (see [refreshGeometry]).
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
     *
     * READ-ONLY for callers: normally the cache itself (so the input thread never allocates and
     * never asks WindowManager for anything); after a rotation it is a freshly built rect for
     * the new orientation until the main thread has re-read the metrics — see
     * [displayBoundsForEvent].
     */
    private fun displayBounds(): Rect = displayBoundsForEvent()

    private fun boundsForLog(): String {
        val max = maximumBoundsCache
        val cur = currentBoundsCache
        // `rot=` is what turns "the corner does not trigger" into a readable log line: a
        // portrait `cur=` next to `rot=1` is a stale geometry cache, not a user missing the
        // corner (see geometryRotation).
        return "cur=${cur.width()}x${cur.height()} max=${max.width()}x${max.height()} " +
            "rot=$geometryRotation stale=${geometryIsStale()}"
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

        // Written on [inputThread], read there and by the back-gesture guard on the main
        // thread (isCornerCommitWindow) — hence @Volatile.
        @Volatile
        var tracking = false
            private set

        @Volatile
        var claimed = false
            private set

        /**
         * True while WE hold this stream: set by the DOWN-time pre-own ([beginMonitorStroke])
         * or by the mid-stroke pilfer on Activate. It is what decides whether a tap has to be
         * rebuilt for the app at release (see [up]).
         */
        @Volatile
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

        /** true when this stroke's DOWN was already taken by the pre-own in [beginMonitorStroke] */
        private var preOwnedAtDown = false

        /** uptime of the last CLAIMED stroke end (0 = never) — read by [isCornerCommitWindow]
         *  from the main thread, written here on the input thread. */
        @Volatile
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
            preOwnedAtDown = newOwned
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
            // opens the panel). The gesture is taken HERE and only here, so the app has had the
            // touch for the whole decision distance — which is what keeps its taps intact — and
            // without the DOWN pre-own below, MiuiHome would still be free to commit HOME from
            // the same stroke.
            when (val action = engine.move(pointerId, raw.pointerCount, x, y, cfg)) {
                is SpyAction.Activate -> {
                    if (!isUnlockedAndInteractive()) {
                        cancel()
                        return
                    }
                    takeStream()
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

        /**
         * Take the pointer stream for the drawer. Called once per stroke, on Activate.
         *
         * When the DOWN was already pre-owned in the bottom strip this is the stroke's SECOND
         * pilfer, and it is deliberate: `pilferPointers()` reports nothing, so a pre-own that
         * lost the race to MiuiHome's own take-over is indistinguishable from one that won.
         * Pilfering again a few millimetres into the gesture costs one binder call and is the
         * only way left to pull the stream back from a launcher that got there first (its HOME
         * commit needs more travel than our activation threshold, and it is that animation the
         * extra pilfer interrupts). The log distinguishes the two — `MON_PILFER_RETRY` for the
         * repeat — so a log reader can tell "no pilfer happened at all" from "the one at DOWN
         * was repeated".
         */
        private fun takeStream() {
            if (pilferAttempted) return
            pilferAttempted = true
            val ok = pilferMonitor()
            if (ok) owned = true
            if (preOwnedAtDown) {
                logger(
                    Log.INFO,
                    "MON_PILFER_RETRY ok=$ok lat=${SystemClock.uptimeMillis() - downUptime}",
                    null,
                )
            } else {
                pilfered(ok)
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
                // Observed stroke (IME showing, or a DOWN the gate did not accept): the app has
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
            // Drop the reference so the next ACTION_UP cannot be logged against this dead
            // stroke's origin (see onMonitorEvent's UP branch).
            if (stroke === this) stroke = null
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

        /**
         * adb 驱动气压校准的钩子（只收 shell/root，见 [registerDebugReceiver]）：
         *
         *     adb shell am broadcast -a com.repl.bubbledrawer.action.DEBUG_PRESSURE \
         *         --es cmd start
         *     adb shell am broadcast -a com.repl.bubbledrawer.action.DEBUG_PRESSURE \
         *         --es cmd sample --ef delta 0.42
         *     adb shell am broadcast -a com.repl.bubbledrawer.action.DEBUG_PRESSURE --es cmd finish
         *
         * 有它才能在没有真人按压的情况下把整条链路（会话 → 采样 → 算阈值 → 广播 → App 落盘）跑通。
         */
        const val ACTION_DEBUG_PRESSURE = "com.repl.bubbledrawer.action.DEBUG_PRESSURE"
        const val EXTRA_DEBUG_PRESSURE_CMD = "cmd"
        const val EXTRA_DEBUG_PRESSURE_DELTA = "delta"
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
         *
         * It is a FLOOR, not the strip itself: [inPreOwnStrip] takes the larger of this and
         * the trigger area's own vertical reach, so no part of our corner can be owned by
         * the launcher instead of us.
         */
        const val HOME_GESTURE_BAND_DP = 28f

        /**
         * How often the power/keyguard snapshot is refreshed (see [stateWatchdog]) while the
         * display is ON. It exists so the DOWN path never makes a binder call: 250 ms is far below
         * the time it takes a user to lock the phone and reach for the corner, and screen on/off
         * and keyguard changes also push an immediate refresh.
         *
         * **它跑在看门狗自己的线程上**，不在输入线程上：每次刷新是两次 binder 往返，排在输入通道的
         * fd 回调前面就是偶尔一笔 DOWN 的 15ms（2026-10-12 用户反馈的成因，见 [stateWatchdog]）。
         */
        const val STATE_REFRESH_MS = 250L

        /** 每多少笔角落手势打一行 `MON_LAT` 汇总（偏慢的每一笔都会单独打 `MON_LAT_SLOW`）。 */
        const val LAT_REPORT_EVERY = 20

        /**
         * …and while it is OFF, where nothing can be waiting for the answer: 20x fewer wakeups
         * for the one timer this feature owns, kept running only so a missed screen-on broadcast
         * cannot leave the gate stuck on "not interactive".
         */
        const val STATE_REFRESH_SCREEN_OFF_MS = 5000L

        /**
         * 设置变化合并窗口：共享组是逐 key 镜像过来的，同一毫秒里能到十几个回调；等最后一次之后
         * 这么久再统一应用（用户感知不到这 120ms，日志与读取次数从"每个 key 一次"变成"一次点击一次"）。
         */
        const val APPLY_SETTINGS_DEBOUNCE_MS = 120L

        /**
         * How many times [warmUp] walks the DOWN path at start. Enough for the JIT to compile
         * the gate (measured in the log as MON_WARMUP ms=...) without costing anything worth
         * mentioning on SystemUI's start-up thread.
         */
        const val WARMUP_ROUNDS = 24

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

        /** receiver-creation attempts before the monitor transport is written off (see [startMonitorTransport]) */
        const val MAX_RECEIVER_ATTEMPTS = 2


        /** Grace after a corner stroke ends during which the native back handler's late
         *  commit attempt for the SAME stroke is still suppressed (main-looper dispatch
         *  ordering; the observed steal was 6–22 ms). */
        const val COMMIT_GRACE_MS = 300L
    }
}

