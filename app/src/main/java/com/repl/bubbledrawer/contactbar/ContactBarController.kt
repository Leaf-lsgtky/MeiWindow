package com.repl.bubbledrawer.contactbar

import android.app.ActivityOptions
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.repl.bubbledrawer.launch.MiuiFreeform
import com.repl.bubbledrawer.xposed.CornerInputMonitor
import com.repl.bubbledrawer.xposed.RemotePrefs

/**
 * The floating contact bar under the small window: Flyme's
 * `com.flyme.systemuitools.windowmode.views.C2831I` ("WMNotificationView"), ported to HyperOS.
 *
 * What Flyme does and what we do instead:
 *
 * | Flyme | here |
 * |---|---|
 * | overlay window type 2056 + `meizuFlags`, anchored from `WindowManagerExt.getWindowModeBound(0,0,windowingMode)` | overlay window type 2024 (the module's proven spy/panel identity), anchored from the live `MiuiFreeformModeTaskInfo` |
 * | geometry scaled by `dMin = live/origin bound` recomputed on every bound change | rect read verbatim from `getActiveTaskScaleBounds()` on every frame |
 * | shown for `windowingMode == 11 \|\| 1035`, hidden when no conversations | shown for `mMode == 0`, hidden for mini/pinned |
 *
 * Two user requirements drive the frame loop:
 *
 *  1. **宽度始终 = 小窗宽度.** HyperOS lets the user drag-resize the window, and WMShell exposes no
 *     per-frame callback at all (verified: `MiuiFreeformModeTaskRepository` only fires appeared /
 *     vanished / mode-changed, and `MiuiFreeformModeResizeHandler.handleResize` pushes geometry
 *     straight into the gesture animation). So the bar polls the task object once per frame —
 *     `getActiveTaskScaleBounds()` returns the destination rect while a gesture/animation is in
 *     flight, which keeps the bar's edge glued to the window's edge.
 *  2. **迷你小窗不显示.** `mMode` 1 (迷你) / 3 (迷你贴边) / 2 (普通贴边) all mean "no room for a bar"
 *     — the window is a thumbnail pinned at a screen edge — so the bar is removed from the window
 *     manager (not merely hidden: an added-but-invisible overlay would still eat touches).
 */
class ContactBarController(
    private val host: Context,
    private val prefs: SharedPreferences,
    private val logger: (Int, String, Throwable?) -> Unit,
) : FreeformObserver {

    private val wm = host.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val recent = RecentConversations(host, logger)

    /** Diagnostics go to LSPosed's module log and logcat (prefix `CONTACT_BAR_`). */
    private fun log(priority: Int, message: String) = logger(priority, message, null)

    private var view: ContactBarView? = null
    private var added = false
    private var frameScheduled = false
    private var frames = 0
    private var items: List<Conversation> = emptyList()
    private var currentPkg: String? = null
    private var lastRect: Rect? = null
    private var lastLogAt = 0L
    private var hideReason: String? = null
    private var fastUntil = 0L
    private var capacityShown = 0

    @Volatile
    private var task: Any? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == RemotePrefs.KEY_CONTACT_BAR) {
            main.post {
                log(Log.INFO, "CONTACT_BAR_PREF_CHANGED enabled=${enabled()}")
                if (!enabled()) detach("PREF_OFF")
                scheduleFrame()
            }
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        RecentConversations.onChanged = { main.post { refreshData(force = true) } }
    }

    // ---------------------------------------------------------------- FreeformObserver

    override fun onFreeformTaskAppeared(taskInfo: Any) {
        if (task !== taskInfo) {
            task = taskInfo
            currentPkg = null
            hideReason = null
            log(
                Log.INFO,
                "CONTACT_BAR_TASK_APPEARED pkg=${FreeformTask.packageName(taskInfo)} " +
                    "mode=${FreeformTask.mode(taskInfo)} taskId=${FreeformTask.taskId(taskInfo)}",
            )
        }
        scheduleFrame()
    }

    /**
     * A resize/move gesture on a window: re-target if it is a different task, and track every frame
     * for the duration of the drag (the gesture is what `超宽度实时一致` hinges on).
     */
    override fun onFreeformTaskFocused(taskInfo: Any) {
        if (task !== taskInfo) {
            task = taskInfo
            currentPkg = null
            log(
                Log.INFO,
                "CONTACT_BAR_TASK_FOCUSED pkg=${FreeformTask.packageName(taskInfo)} " +
                    "mode=${FreeformTask.mode(taskInfo)} taskId=${FreeformTask.taskId(taskInfo)}",
            )
        }
        fastUntil = SystemClock.uptimeMillis() + FAST_WINDOW_MS
        scheduleFrame()
    }

    override fun onFreeformTaskVanished(taskId: Int) {
        val current = task ?: return
        if (FreeformTask.taskId(current) == taskId) {
            log(Log.INFO, "CONTACT_BAR_TASK_VANISHED taskId=$taskId")
            task = null
            detach("TASK_VANISHED")
        }
    }

    override fun onFreeformTaskModeChanged(taskInfo: Any, oldMode: Int, newMode: Int) {
        if (task !== taskInfo) task = taskInfo
        log(Log.INFO, "CONTACT_BAR_MODE_CHANGED old=$oldMode new=$newMode")
        // 0 = 普通小窗 (bar visible), 1/2/3 = 迷你/贴边 (bar must go away).
        if (newMode != FreeformTask.MODE_NORMAL) detach("MODE_$newMode") else hideReason = null
        scheduleFrame()
    }

    // ---------------------------------------------------------------- frame loop

    private fun scheduleFrame() {
        if (frameScheduled) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private val frameCallback = Choreographer.FrameCallback {
        frameScheduled = false
        onFrame()
        if (task != null && enabled()) scheduleFrame()
    }

    private fun onFrame() {
        if (!enabled()) {
            if (added) detach("DISABLED")
            return
        }
        val info = task ?: return
        frames++

        // A resize/move drag registers an animation (MiuiFreeformModeResizeHandler pushes every
        // move into `startGestureAnimation`), which is exactly when the bar must track per frame.
        // Idle windows are polled on every IDLE_FRAME_STRIDE-th frame instead — same geometry, a
        // fraction of the work; the stride is also why a mode change can take one extra frame.
        val animating = FreeformTask.isAnimating(info)
        val now = SystemClock.uptimeMillis()
        if (animating) fastUntil = now + FAST_WINDOW_MS
        if (!animating && now > fastUntil && frames % IDLE_FRAME_STRIDE != 0) return

        // Mode gate: this is the user's "迷你小窗不应显示" rule and it also covers the pinned
        // states, where the window is half off-screen.
        if (!FreeformTask.isNormal(info)) {
            detach(if (FreeformTask.isMini(info)) "MINI" else "MODE_${FreeformTask.mode(info)}")
            return
        }
        if (!FreeformTask.isVisible(info)) {
            detach("NOT_VISIBLE")
            return
        }

        val bounds = FreeformTask.visualBounds(info)
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            detach("NO_BOUNDS")
            return
        }

        // The window's package can change inside the same task (小窗切换到另一个会话/应用).
        val pkg = FreeformTask.packageName(info)
        if (pkg != null && pkg != currentPkg) {
            currentPkg = pkg
            refreshData(force = true)
        } else if (frames % DATA_EVERY_FRAMES == 0) {
            refreshData(force = false)
        }

        if (items.isEmpty()) {
            detach("NO_CONTACTS")
            return
        }

        val rect = layoutFor(bounds)
        if (!added) {
            attach(rect)
        } else if (rect != lastRect) {
            update(rect)
        }
        lastRect = rect
        logGeometry(info, bounds, rect)
    }

    /** Window rect for the bar: same width as the small window, hugging its bottom edge. */
    private fun layoutFor(bounds: Rect): Rect {
        val metrics = wm.currentWindowMetrics
        val screenH = metrics.bounds.height()
        val screenW = metrics.bounds.width()
        val navBottom = runCatching {
            metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()).bottom
        }.getOrDefault(0)

        val width = bounds.width().coerceAtLeast(dp(90))
        var x = bounds.left
        if (x + width > screenW) x = screenW - width
        if (x < 0) x = 0

        var y = bounds.bottom + dp(GAP_DP)
        // Touching the bottom edge / gesture area: flip above the window instead of clipping.
        if (y + barHeight() > screenH - navBottom) {
            val above = bounds.top - barHeight() - dp(GAP_DP)
            if (above > navBottom) y = above
        }
        return Rect(x, y, x + width, y + barHeight())
    }

    private fun barHeight(): Int = dp(BAR_HEIGHT_DP)

    // ---------------------------------------------------------------- window plumbing

    private fun attach(rect: Rect) {
        val v = view ?: ContactBarView(host).also {
            it.setPaddingDp(10, 8)
            view = it
        }
        capacityShown = v.capacityFor(rect.width())
        v.bind(items, capacityShown, fallbackIcon(), ::openConversation)
        runCatching {
            wm.addView(v, params(rect))
            added = true
            hideReason = null
            v.fadeIn()
            log(Log.INFO, "CONTACT_BAR_SHOW rect=$rect items=${items.size} pkg=$currentPkg")
        }.onFailure {
            logger(Log.WARN, "CONTACT_BAR_ADD_FAILED rect=$rect", it)
            added = false
        }
    }

    private fun update(rect: Rect) {
        val v = view ?: return
        runCatching { wm.updateViewLayout(v, params(rect)) }
            .onFailure { logger(Log.WARN, "CONTACT_BAR_UPDATE_FAILED", it) }
        // A wider window fits more avatars — re-bind so a resize changes the visible set too.
        val capacity = v.capacityFor(rect.width())
        if (capacity != capacityShown) {
            capacityShown = capacity
            v.bind(items, capacity, fallbackIcon(), ::openConversation)
        }
    }

    /**
     * Remove the overlay from the window manager. Deliberately keeps [items] and [currentPkg]: a
     * mini window that is restored to a normal one, or a bar hidden by a transient state, must come
     * back instantly instead of waiting for the next notification refresh.
     */
    private fun detach(reason: String) {
        view?.let { v ->
            view = null
            runCatching { wm.removeViewImmediate(v) }
        }
        if (added || hideReason != reason) {
            log(Log.INFO, "CONTACT_BAR_HIDE reason=$reason")
        }
        added = false
        lastRect = null
        hideReason = reason
    }

    private fun params(rect: Rect) = WindowManager.LayoutParams(
        rect.width(),
        rect.height(),
        CornerInputMonitor.CORNER_WINDOW_TYPE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = rect.left
        y = rect.top
        title = "bubble contact bar"
        windowAnimations = 0
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        @Suppress("DEPRECATION")
        systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        runCatching { setFitInsetsTypes(0) }
        runCatching {
            WindowManager.LayoutParams::class.java.getMethod("setTrustedOverlay").invoke(this)
        }
    }

    // ---------------------------------------------------------------- data

    /** Rebuilds the avatar row for the app that currently owns the small window. */
    fun refreshData(force: Boolean) {
        val info = task ?: return
        val pkg = FreeformTask.packageName(info)
        if (pkg == null || pkg !in RecentConversations.IM_WHITELIST) {
            items = emptyList()
            detach("NOT_IM_APP")
            return
        }
        val list = recent.conversations(pkg, force)
        if (list == items) return
        items = list
        val v = view ?: return
        val rect = lastRect ?: return
        v.bind(items, v.capacityFor(rect.width()), fallbackIcon(), ::openConversation)
        log(Log.INFO, "CONTACT_BAR_DATA pkg=$pkg items=${items.size}")
    }

    /** Flyme falls back to the app icon when a notification carries no avatar. */
    private fun fallbackIcon() = runCatching {
        host.packageManager.getApplicationIcon(currentPkg ?: return@runCatching null)
    }.getOrNull()

    // ---------------------------------------------------------------- tap → conversation

    /**
     * Open the tapped conversation **inside the existing small window** — the port of Flyme's
     * `C2831I.mo9405g` (`contentIntent` + `start_windowmode=true` bundle, `m9407t()`).
     *
     * HyperOS spelling of the same thing: MIUI freeform `ActivityOptions` + `setLaunchTaskId` of the
     * window we are attached to, handed to `PendingIntent.send(…, options)`. That is the documented
     * reuse path (`MiuiCaptionClickListener.handleNewWindowClicked:239-243`); without the task id the
     * chat would open as a second window with the wrong shape (see [MiuiFreeform.reusableTaskId]).
     */
    private fun openConversation(conversation: Conversation) {
        val info = task
        val pi = conversation.pendingIntent
        if (pi == null) {
            logger(Log.WARN, "CONTACT_BAR_OPEN_NO_INTENT pkg=${conversation.pkg}", null)
            return
        }
        val options = runCatching { MiuiFreeform.activityOptions(host, conversation.pkg, noCheck = true) }.getOrNull()
            ?: runCatching { MiuiFreeform.makeActivityOptions(host, conversation.pkg) }.getOrNull()
        if (options != null) {
            val taskId = FreeformTask.taskId(info)
            if (taskId > 0) {
                runCatching {
                    ActivityOptions::class.java
                        .getMethod("setLaunchTaskId", Int::class.javaPrimitiveType)
                        .invoke(options, taskId)
                }
            }
            runCatching { MiuiFreeform.withoutFreeformAnimation(options) }
            val sent = runCatching {
                pi.send(host, 0, null, null, null, null, options.toBundle())
                true
            }.getOrDefault(false)
            if (sent) {
                log(
                    Log.INFO,
                    "CONTACT_BAR_OPEN_OPTIONS pkg=${conversation.pkg} reuseTask=$taskId " +
                        "title=${conversation.title}",
                )
                return
            }
        }
        val plain = runCatching { pi.send(); true }.getOrDefault(false)
        log(
            Log.INFO,
            "CONTACT_BAR_OPEN_PLAIN pkg=${conversation.pkg} sent=$plain options=${options != null} " +
                "title=${conversation.title}",
        )
    }

    // ---------------------------------------------------------------- lifecycle

    private fun enabled(): Boolean = runCatching { RemotePrefs.read(prefs).contactBar }.getOrDefault(false)

    private fun logGeometry(info: Any, bounds: Rect, rect: Rect) {
        val now = SystemClock.uptimeMillis()
        if (now - lastLogAt < GEOMETRY_LOG_MS) return
        lastLogAt = now
        log(
            Log.INFO,
            "CONTACT_BAR_GEOM mode=${FreeformTask.mode(info)} visual=$bounds bar=$rect " +
                "scale=${runCatching { info.javaClass.getMethod("getFreeformScale").invoke(info) }.getOrNull()} " +
                "animating=${FreeformTask.isAnimating(info)} items=${items.size}",
        )
    }

    fun dispose() {
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefsListener) }
        if (RecentConversations.onChanged != null) RecentConversations.onChanged = null
        detach("DISPOSE")
        task = null
        log(Log.INFO, "CONTACT_BAR_DISPOSED")
    }

    private fun dp(value: Int): Int =
        (value * host.resources.displayMetrics.density).toInt()

    private companion object {
        /** Gap between the window's bottom edge and the bar (Flyme: 10dp margin, 63dp bar). */
        const val GAP_DP = 6
        const val BAR_HEIGHT_DP = 62

        /** Notification data is re-read at most this often; callbacks refresh immediately. */
        const val DATA_EVERY_FRAMES = 40

        /** Geometry diagnostics are rate-limited so a resize drag cannot flood logcat. */
        const val GEOMETRY_LOG_MS = 700L

        /** How long after an animation ends the bar keeps tracking every single frame. */
        const val FAST_WINDOW_MS = 400L

        /** Idle polling stride: geometry is unchanged, so every 4th frame is plenty. */
        const val IDLE_FRAME_STRIDE = 4
    }
}
