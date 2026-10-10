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
import com.repl.bubbledrawer.launch.FlymeFreeformController
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

    /**
     * Every window/view operation happens on the SystemUI **main** thread, whatever thread the event
     * arrived on. The freeform hooks fire on the WMShell thread (`wmshell.main`) — a view created there
     * belongs to that thread's ViewRootImpl, so a later refresh from the main thread (the notification
     * listener posts there) dies with `CalledFromWrongThreadException: Expected: wmshell.main Calling:
     * main`. Doing everything on main also means one Choreographer, one input thread, no interleaving.
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** The main thread's Choreographer — never `Choreographer.getInstance()` off-thread. */
    @Volatile
    private var mainChoreographer: Choreographer? = null

    /** Diagnostics go to LSPosed's module log and logcat (prefix `CONTACT_BAR_`). */
    private fun log(priority: Int, message: String) = logger(priority, message, null)

    private var view: ContactBarView? = null
    private var added = false

    @Volatile
    private var frameScheduled = false
    private var frames = 0
    private var items: List<Conversation> = emptyList()
    private var currentPkg: String? = null
    private var lastRect: Rect? = null
    private var lastLogAt = 0L
    private var hideReason: String? = null
    private var fastUntil = 0L
    private var capacityShown = 0
    private var cornerShown = Float.NaN

    /** While set, the bar stays hidden: the window is closing, so it must not ride the animation. */
    private var closingUntil = 0L

    /**
     * Another 小窗 holds the focus, so this window's bar must not show. Cleared the moment this window
     * is brought to the front again (`onFreeformTaskToFront`).
     */
    private var unfocused = false

    /** taskId → the `MiuiFreeformModeTaskInfo` we have seen for it (bounded, main thread only). */
    private val taskById = HashMap<Int, Any>()

    @Volatile
    private var task: Any? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == RemotePrefs.KEY_CONTACT_BAR) {
            onMain {
                log(Log.INFO, "CONTACT_BAR_PREF_CHANGED enabled=${enabled()} thread=${Thread.currentThread().name}")
                if (!enabled()) detach("PREF_OFF")
                scheduleFrame()
            }
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        // Bind the frame loop to the main thread's Choreographer up front (the constructor itself may
        // run on the module's own thread after a hot reload).
        onMain { mainChoreographer = Choreographer.getInstance() }
        RecentConversations.onChanged = {
            onMain {
                // Capture first, then render: HyperOS cancels an app's notifications the moment the
                // app is opened, so anything not remembered now would never reach the bar.
                recent.observe()
                refreshData(force = true)
            }
        }
        // Whatever is already posted when the module loads (install, SystemUI restart) counts as
        // recent conversations too.
        onMain { recent.observe() }
    }

    // ---------------------------------------------------------------- FreeformObserver

    override fun onFreeformTaskAppeared(taskInfo: Any) = onMain {
        rememberTask(taskInfo)
        if (task !== taskInfo) {
            task = taskInfo
            currentPkg = null
            hideReason = null
            closingUntil = 0L
            unfocused = false
            log(
                Log.INFO,
                "CONTACT_BAR_TASK_APPEARED pkg=${FreeformTask.packageName(taskInfo)} " +
                    "mode=${FreeformTask.mode(taskInfo)} taskId=${FreeformTask.taskId(taskInfo)}",
            )
        }
        scheduleFrame()
    }

    /**
     * The window is going away (swipe-up dismiss / ✕ / maximize): remove the bar now and stay hidden
     * until either the task is gone or [CLOSE_LATCH_MS] passes — the latter so an aborted transition
     * cannot leave the bar permanently hidden.
     */
    override fun onFreeformTaskClosing(taskId: Int) = onMain {
        val current = task ?: return@onMain
        if (FreeformTask.taskId(current) != taskId) return@onMain
        log(Log.INFO, "CONTACT_BAR_TASK_CLOSING taskId=$taskId")
        closingUntil = SystemClock.uptimeMillis() + CLOSE_LATCH_MS
        detach("CLOSING")
    }

    /**
     * Focus moved to a freeform window: **the bar belongs to the focused one**, so switch to it if we
     * know it, otherwise hide.
     *
     * This is the fix for using two 小窗 at once: tapping the other window took the bar away (correct),
     * and nothing else ever reported the tap that comes back — a plain tap is neither a move nor a
     * resize gesture, so only this event announces it.
     */
    override fun onFreeformTaskToFront(taskId: Int) = onMain {
        val known = taskById[taskId]
        val current = task
        if (known != null && known !== current) {
            log(
                Log.INFO,
                "CONTACT_BAR_FOCUS_SWITCH taskId=$taskId pkg=${FreeformTask.packageName(known)} " +
                    "from=${current?.let { FreeformTask.taskId(it) }}",
            )
            task = known
            currentPkg = null
            hideReason = null
            closingUntil = 0L
            unfocused = false
            fastUntil = SystemClock.uptimeMillis() + FAST_WINDOW_MS
            scheduleFrame()
            return@onMain
        }
        if (current != null && FreeformTask.taskId(current) == taskId) {
            if (unfocused) {
                log(Log.INFO, "CONTACT_BAR_REGAINED_FOCUS taskId=$taskId")
                unfocused = false
                hideReason = null
                fastUntil = SystemClock.uptimeMillis() + FAST_WINDOW_MS
            }
            scheduleFrame()
            return@onMain
        }
        // Another window we never saw an event for holds the focus: hide until it tells us more.
        if (!unfocused) {
            log(Log.INFO, "CONTACT_BAR_LOST_FOCUS taskId=$taskId")
            unfocused = true
        }
        detach("UNFOCUSED")
    }

    /**
     * A resize/move gesture on a window: re-target if it is a different task, and track every frame
     * for the duration of the drag (the gesture is what `宽度实时一致` hinges on).
     */
    override fun onFreeformTaskFocused(taskInfo: Any) = onMain {
        rememberTask(taskInfo)
        if (task !== taskInfo) {
            task = taskInfo
            currentPkg = null
            unfocused = false
            log(
                Log.INFO,
                "CONTACT_BAR_TASK_FOCUSED pkg=${FreeformTask.packageName(taskInfo)} " +
                    "mode=${FreeformTask.mode(taskInfo)} taskId=${FreeformTask.taskId(taskInfo)}",
            )
        }
        fastUntil = SystemClock.uptimeMillis() + FAST_WINDOW_MS
        scheduleFrame()
    }

    override fun onFreeformTaskVanished(taskId: Int) = onMain {
        taskById.remove(taskId)
        val current = task ?: return@onMain
        if (FreeformTask.taskId(current) == taskId) {
            log(Log.INFO, "CONTACT_BAR_TASK_VANISHED taskId=$taskId")
            task = null
            unfocused = false
            detach("TASK_VANISHED")
        }
    }

    /** Remember the objects behind task ids, so a bare "this task came to front" can be resolved. */
    private fun rememberTask(taskInfo: Any) {
        val id = FreeformTask.taskId(taskInfo)
        if (id <= 0) return
        if (taskById.size > MAX_TRACKED_TASKS) taskById.clear()
        taskById[id] = taskInfo
    }

    override fun onFreeformTaskModeChanged(taskInfo: Any, oldMode: Int, newMode: Int) = onMain {
        rememberTask(taskInfo)
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
        onMain {
            val choreographer = mainChoreographer ?: Choreographer.getInstance().also { mainChoreographer = it }
            choreographer.postFrameCallback(frameCallback)
        }
    }

    private val frameCallback = Choreographer.FrameCallback {
        frameScheduled = false
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // Defensive: a callback must never touch views off the main thread.
            scheduleFrame()
            return@FrameCallback
        }
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

        // Closing: hide immediately and stay hidden while the window animates away (the user's
        // "关闭小窗动画期间不要跟随" rule). Two sources: the exit hook and the task's own EXITING state.
        if (now < closingUntil) {
            detach("CLOSING")
            return
        }
        if (FreeformTask.isExiting(info)) {
            closingUntil = now + CLOSE_LATCH_MS
            detach("EXITING")
            return
        }

        // Another 小窗 owns the focus: this window's bar stays away until it is brought back.
        if (unfocused) {
            detach("UNFOCUSED")
            return
        }

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

        // Size gate: a window dragged down to a thumbnail has no room for a name row (user setting,
        // 0 disables it). Expressed against the screen width, which is what the slider shows.
        val minPct = minWidthPct()
        if (minPct > 0) {
            val screenWidth = wm.currentWindowMetrics.bounds.width()
            if (bounds.width() * 100 < minPct * screenWidth) {
                // Stable reason so a drag through the threshold logs once, not per percentage point.
                detach("TOO_SMALL")
                return
            }
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
        if (rect == null) {
            // No room that avoids the keyboard — the window already fills what is left above it.
            detach("IME_NO_ROOM")
            return
        }
        if (!added) {
            attach(info, rect)
        } else if (rect != lastRect) {
            update(info, rect)
        }
        lastRect = rect
        logGeometry(info, bounds, rect)
    }

    /**
     * Window rect for the bar: same width as the small window, hugging its bottom edge — and never
     * over the keyboard.
     *
     * The IME is why this returns null: Flyme's bar records the keyboard state and ignores it
     * (`C2831I.f10206B` is write-only), because there the ROM moves the window out of the way. On
     * HyperOS the window can stay put, so when the space under the window is inside the keyboard the
     * bar moves above the *window* instead; if even that would sit behind the keyboard, it stays
     * hidden rather than covering the keys. Returns null in that case.
     */
    private fun layoutFor(bounds: Rect): Rect? {
        val metrics = wm.currentWindowMetrics
        val screenH = metrics.bounds.height()
        val screenW = metrics.bounds.width()
        val systemBars = runCatching {
            metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        }.getOrDefault(android.graphics.Insets.NONE)
        val navBottom = systemBars.bottom
        val statusTop = systemBars.top

        // Keyboard top edge: everything below it must stay clear.
        val imeTop = if (FlymeFreeformController.isImeShowing()) {
            (screenH - FlymeFreeformController.imeHeightPx()).coerceAtLeast(statusTop)
        } else {
            0
        }
        val limitBottom = if (imeTop > 0) minOf(screenH - navBottom, imeTop) else screenH - navBottom

        val width = bounds.width().coerceAtLeast(dp(90))
        var x = bounds.left
        if (x + width > screenW) x = screenW - width
        if (x < 0) x = 0

        val height = barHeight()
        val gap = dp(GAP_DP)
        val below = bounds.bottom + gap
        val above = bounds.top - height - gap
        val y = when {
            below + height <= limitBottom -> below
            above >= statusTop && above + height <= limitBottom -> above
            else -> return null
        }
        return Rect(x, y, x + width, y + height)
    }

    private fun barHeight(): Int = ensureView().barHeightPx()

    private fun ensureView(): ContactBarView = view ?: ContactBarView(host).also {
        // 6dp vertical padding keeps the 36dp avatar + name row inside the 63dp bar.
        it.setPaddingDp(10, 6)
        view = it
    }

    // ---------------------------------------------------------------- window plumbing

    private fun attach(info: Any, rect: Rect) {
        val v = ensureView()
        capacityShown = v.capacityFor(rect.width())
        applyCorner(v, info)
        v.bind(items, capacityShown, fallbackIcon(), ::openConversation, ::onRemoveConversation)
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

    private fun update(info: Any, rect: Rect) {
        val v = view ?: return
        runCatching { wm.updateViewLayout(v, params(rect)) }
            .onFailure { logger(Log.WARN, "CONTACT_BAR_UPDATE_FAILED", it) }
        applyCorner(v, info)
        // A wider window fits more avatars — re-bind so a resize changes the visible set too.
        val capacity = v.capacityFor(rect.width())
        if (capacity != capacityShown) {
            capacityShown = capacity
            v.bind(items, capacity, fallbackIcon(), ::openConversation, ::onRemoveConversation)
        }
    }

    /**
     * Corner radius of the window itself (`MiuiFreeformModeTaskInfo.getCornerRadius()`, screen px), so
     * the bar's corners match the 小窗 instead of looking like a pill. 13dp when the ROM stays silent.
     */
    private fun applyCorner(view: ContactBarView, info: Any) {
        val radius = FreeformTask.cornerRadius(info) ?: return
        if (radius == cornerShown) return
        cornerShown = radius
        view.setCornerRadius(radius)
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

    /** Rebuilds the avatar row for the app that currently owns the small window (main thread only). */
    fun refreshData(force: Boolean) = onMain {
        val info = task ?: return@onMain
        val pkg = FreeformTask.packageName(info)
        if (pkg == null || pkg !in RecentConversations.IM_WHITELIST) {
            items = emptyList()
            detach("NOT_IM_APP")
            return@onMain
        }
        val list = recent.conversations(pkg, force)
        if (list == items) return@onMain
        items = list
        val v = view ?: return@onMain
        val rect = lastRect ?: return@onMain
        v.bind(items, v.capacityFor(rect.width()), fallbackIcon(), ::openConversation, ::onRemoveConversation)
        log(
            Log.INFO,
            "CONTACT_BAR_DATA pkg=$pkg items=${items.size} live=${recent.lastLiveCount} " +
                "remembered=${(items.size - recent.lastLiveCount).coerceAtLeast(0)}",
        )
    }

    /**
     * Swipe-to-remove (Flyme: `ContactListView.C2929d.onSwiped` → `C2831I.m9363I`). The conversation is
     * dropped from the memory and suppressed until something newer arrives for that chat — and the bar
     * disappears when that was the last one.
     */
    private fun onRemoveConversation(conversation: Conversation) {
        recent.forget(conversation)
        val info = task
        val pkg = info?.let { FreeformTask.packageName(it) }
        items = if (pkg != null) recent.conversations(pkg, force = true) else emptyList()
        if (items.isEmpty()) {
            detach("EMPTY_AFTER_REMOVE")
            return
        }
        val v = view ?: return
        val rect = lastRect ?: return
        capacityShown = v.capacityFor(rect.width())
        v.bind(items, capacityShown, fallbackIcon(), ::openConversation, ::onRemoveConversation)
    }

    /** Flyme falls back to the app icon when a notification carries no avatar. */
    private fun fallbackIcon() = runCatching {
        host.packageManager.getApplicationIcon(currentPkg ?: return@runCatching null)
    }.getOrNull()

    // ---------------------------------------------------------------- tap → conversation

    /**
     * Open the tapped conversation **inside the existing small window** — Flyme's
     * `C2831I.mo9405g` verbatim:
     *
     * ```java
     * Intent intent = c0653b.m3435c();          // the Intent behind the click, kept in the model
     * Bundle bundle = m9407t();                 // ActivityOptions.makeBasic().toBundle()
     * bundle.putBoolean("start_windowmode", true);
     * if (!m9364J(pi, intent, bundle))          // pi.send(ctx, 0, intent, null, null, null, bundle)
     *     AbstractC7062g.m24760j(ctx, intent, user);  // startActivityAsUser(intent, bundle, user)
     * ```
     *
     * Two details matter on HyperOS, and both were missing before:
     *  - the **Intent** is sent along (fill-in) and, more importantly, is kept as the fallback: opening
     *    an app cancels its notifications and WeChat cancels their PendingIntents with them, so
     *    `send()` starts throwing `CanceledException` for chats whose notification is gone — the Intent
     *    still starts fine. Without it the tap degraded to "open WeChat's main UI", which is the
     *    "sometimes it works, sometimes it doesn't" report.
     *  - the fallback keeps the **freeform options** (Flyme's bundle carries `start_windowmode`); a plain
     *    `send()` would open the chat fullscreen instead of in the window.
     *
     * MIUI spelling of the same intent: freeform `ActivityOptions` + `setLaunchTaskId` of the window we
     * are attached to (`MiuiCaptionClickListener.handleNewWindowClicked:239-243`).
     */
    private fun openConversation(conversation: Conversation) {
        val info = task
        val options = freeformOptions(conversation.pkg, info)
        val bundle = options?.toBundle()
        val pi = conversation.pendingIntent
        val intent = conversation.launchIntent?.let { android.content.Intent(it) }
        val taskId = FreeformTask.taskId(info)

        // 1. Flyme's primary path: the PendingIntent, with the stored Intent as fill-in + window mode.
        if (pi != null && bundle != null) {
            val sent = runCatching {
                pi.send(host, 0, intent, null, null, null, bundle)
                true
            }.getOrDefault(false)
            if (sent) {
                log(Log.INFO, "CONTACT_BAR_OPEN_PI pkg=${conversation.pkg} reuseTask=$taskId title=${conversation.title}")
                return
            }
        }

        // 2. Flyme's fallback: start the Intent itself. Survives a cancelled PendingIntent and still
        //    lands in the small window because the options/task id are kept.
        if (intent != null) {
            val started = startIntentAsUser(intent, bundle, conversation.userId)
            if (started) {
                log(
                    Log.INFO,
                    "CONTACT_BAR_OPEN_INTENT pkg=${conversation.pkg} reuseTask=$taskId " +
                        "cancelled=${pi != null} title=${conversation.title}",
                )
                return
            }
        }

        // 3. No stored Intent (older memory entry): plain send, at least it opens the chat.
        val plain = runCatching { pi?.send(); true }.getOrDefault(false)
        if (plain) {
            log(Log.INFO, "CONTACT_BAR_OPEN_PLAIN pkg=${conversation.pkg} title=${conversation.title}")
            return
        }

        val launched = openAppInCurrentWindow(conversation.pkg, options)
        logger(
            Log.WARN,
            "CONTACT_BAR_OPEN_FALLBACK pkg=${conversation.pkg} title=${conversation.title} " +
                "intent=${intent != null} pi=${pi != null} launched=$launched",
            null,
        )
    }

    /** `Context.startActivityAsUser(intent, bundle, user)` — Flyme's `AbstractC7062g.m24760j`. */
    private fun startIntentAsUser(intent: android.content.Intent, bundle: android.os.Bundle?, userId: Int): Boolean {
        val launchIntent = android.content.Intent(intent)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val user = userHandle(userId)
        if (user != null) {
            val asUser = runCatching {
                Context::class.java.getMethod(
                    "startActivityAsUser",
                    android.content.Intent::class.java,
                    android.os.Bundle::class.java,
                    android.os.UserHandle::class.java,
                ).invoke(host, launchIntent, bundle, user)
                true
            }.getOrDefault(false)
            if (asUser) return true
        }
        return runCatching {
            if (bundle != null) host.startActivity(launchIntent, bundle) else host.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }

    /** `UserHandle.of(id)` is hidden; user 0 / unknown falls back to this process's own handle. */
    private fun userHandle(userId: Int): android.os.UserHandle? {
        if (userId <= 0) return android.os.Process.myUserHandle()
        return runCatching {
            android.os.UserHandle::class.java
                .getMethod("of", Int::class.javaPrimitiveType)
                .invoke(null, userId) as android.os.UserHandle
        }.getOrNull() ?: android.os.Process.myUserHandle()
    }

    /** MIUI freeform launch options pinned to the window we are attached to. */
    private fun freeformOptions(pkg: String, info: Any?): ActivityOptions? {        val options = runCatching { MiuiFreeform.activityOptions(host, pkg, noCheck = true) }.getOrNull()
            ?: runCatching { MiuiFreeform.makeActivityOptions(host, pkg) }.getOrNull()
            ?: return null
        val taskId = FreeformTask.taskId(info)
        if (taskId > 0) {
            runCatching {
                ActivityOptions::class.java
                    .getMethod("setLaunchTaskId", Int::class.javaPrimitiveType)
                    .invoke(options, taskId)
            }
        }
        return runCatching { MiuiFreeform.withoutFreeformAnimation(options) }.getOrDefault(options)
    }

    private fun openAppInCurrentWindow(pkg: String, options: ActivityOptions?): Boolean = runCatching {
        val intent = host.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        if (options != null) host.startActivity(intent, options.toBundle()) else host.startActivity(intent)
        true
    }.getOrDefault(false)

    // ---------------------------------------------------------------- lifecycle

    private fun enabled(): Boolean = runCatching { RemotePrefs.read(prefs).contactBar }.getOrDefault(false)

    /** Hide-threshold in percent of the screen width; 0 disables the size gate entirely. */
    private fun minWidthPct(): Int = runCatching { RemotePrefs.read(prefs).contactBarMinWidthPct }.getOrDefault(0)

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

    fun dispose() = onMain {
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefsListener) }
        if (RecentConversations.onChanged != null) RecentConversations.onChanged = null
        detach("DISPOSE")
        task = null
        // Also unregister our notification listener: a hot reload replaces our classes but not the
        // objects the previous generation registered inside SystemUI.
        runCatching { RecentConversations.detachListener() }
        log(Log.INFO, "CONTACT_BAR_DISPOSED")
    }

    private fun dp(value: Int): Int =
        (value * host.resources.displayMetrics.density).toInt()

    private companion object {
        /** Gap between the window's bottom edge and the bar (Flyme: 10dp margin, 63dp bar). */
        const val GAP_DP = 6

        /**
         * How long the bar stays hidden after the close gesture commits. The task only vanishes when
         * the closing animation finishes; the latch covers the gap, and expiring it means an aborted
         * transition cannot leave the bar hidden forever.
         */
        const val CLOSE_LATCH_MS = 1200L

        /** Notification data is re-read at most this often; callbacks refresh immediately. */
        const val DATA_EVERY_FRAMES = 40

        /** Geometry diagnostics are rate-limited so a resize drag cannot flood logcat. */
        const val GEOMETRY_LOG_MS = 700L

        /** How long after an animation ends the bar keeps tracking every single frame. */
        const val FAST_WINDOW_MS = 400L

        /** Idle polling stride: geometry is unchanged, so every 4th frame is plenty. */
        const val IDLE_FRAME_STRIDE = 4

        /** At most this many freeform tasks are remembered for focus lookups (two is the ROM's cap). */
        const val MAX_TRACKED_TASKS = 4
    }
}
