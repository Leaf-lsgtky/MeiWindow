package com.repl.bubbledrawer.bubble

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.data.AppRepository
import com.repl.bubbledrawer.data.LaunchCountStore
import com.repl.bubbledrawer.data.PinStore
import com.repl.bubbledrawer.gesture.Corner
import com.repl.bubbledrawer.launch.ILaunchStrategy

/**
 * The role Flyme's `AppLauncherWindow` (WindowModeSlidGesture) plays around the
 * launcher view: bind data (pins → first 6 + "更多" tile, m9235C :447-463),
 * set radius/safeDegrees per navbar (m9237F :482-505), forward events
 * (SlideGestureForwarding), launch on selection, retract after.
 */
class BubbleDockController(
    private val context: Context,
    private val repo: AppRepository,
    private val pinStore: PinStore,
    private val launchStrategy: ILaunchStrategy,
    /** Optional external tile factory (FanHost builds tiles in code inside SystemUI);
     *  falls back to inflating slide_gesture_list_item in our own process. */
    private val viewFactory: GestureAppLauncher.ViewFactory? = null,
) {
    val launcher = GestureAppLauncher(context)
    var isBusy = false
        private set

    /** Fan shown/hidden signal → host dims/undims the scrim window. */
    var onShownChanged: ((Boolean) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoRetract = Runnable { forceRetract() }

    /** Preview mode (no live finger): auto-collapse like a released gesture. */
    var previewMode = false

    /**
     * Fan "更多" tile → host hook. [com.repl.bubbledrawer.xposed.FanHost] sets this
     * inside SystemUI and raises the overlay manage-page panel (方案 B: the same
     * `PinManageView` the Activity hosts, pinned to the current freeform 小窗, so the
     * page IS the window content — Flyme likewise opens SlideLaunchAppSettings with
     * `start_windowmode` instead of a new full-screen page). Left null (e.g. a plain
     * app-process host) the full-screen manage Activity still runs.
     */
    var onMoreRequested: (() -> Unit)? = null

    init {
        launcher.setCallback(object : GestureAppLauncher.Callback {
            override fun onItemSelected(item: GestureAppLauncher.AdapterItem, view: View, index: Int) {
                when (item) {
                    is GestureAppLauncher.AdapterItem.AppItem -> {
                        launchStrategy.launch(context, item.app)
                        LaunchCountStore.increment(context, item.app.packageName, item.app.userId)
                        com.repl.bubbledrawer.data.predict.AppPredictor.recordLaunch(context, item.app.packageName, item.app.userId)
                    }
                    GestureAppLauncher.AdapterItem.More -> {
                        // VERIFIED: original fan's trailing "更多" tile (C2821f.mo3456G
                        // :294-317) launches SlideLaunchAppSettings (选择/管理合一页,
                        // 带 A–Z 索引条) — NOT MoreAppWindow. Per user ruling the two
                        // pages merge: the manage page IS the "更多" destination.
                        // 方案 B: inside SystemUI the host answers with the overlay
                        // 更多应用 panel (Flyme puts MoreAppWindow INTO the window);
                        // only an Activity-less host falls back to the full-screen page.
                        val host = onMoreRequested
                        if (host != null) {
                            host()
                        } else {
                            // Explicit ComponentName by PACKAGE STRING: the caller may be
                            // the SystemUI-hosted fan whose Context reports a different
                            // package, and Intent(context, cls) would misattribute it.
                            context.startActivity(
                                Intent().setComponent(
                                    android.content.ComponentName(
                                        com.repl.bubbledrawer.xposed.ModuleResources.MODULE_PACKAGE,
                                        "com.repl.bubbledrawer.pin.PinManageActivity",
                                    ),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
                retractAfterAction()
            }

            override fun onGestureCanceled(reason: Int) = retractAfterAction()

            override fun onExpandStarted() {
                isBusy = true
                onShownChanged?.invoke(true)
                schedulePreviewTimeout()
            }
        })
    }

    private fun schedulePreviewTimeout() {
        mainHandler.removeCallbacks(autoRetract)
        if (previewMode) {
            // a real finger ends with UP/CANCEL; a preview has none — end it after 4s
            mainHandler.postDelayed(autoRetract, 4000)
        }
    }

    var currentPage = 0
        private set
    var totalPages = 1
        private set
    private var pages: List<List<GestureAppLauncher.AdapterItem>> = emptyList()

    fun resetPage() {
        currentPage = 0
    }

    fun nextPage(): Boolean {
        if (pages.size <= 1) return false
        currentPage = (currentPage + 1) % pages.size
        val items = pages[currentPage]
        launcher.switchToAdapter(
            GestureAppLauncher.SlideAdapter(items) { item, index ->
                viewFactory?.create(item, index) ?: itemView(item)
            },
            animated = true,
        )
        return true
    }

    /** @param screenH full-screen height in px (the launcher window is MATCH_PARENT). */
    fun show(corner: Corner, screenW: Int, screenH: Int, maxApps: Int = 6, radiusDp: Int = 0, autoFillRecommend: Boolean = false) {
        currentPage = 0
        pages = buildPages(maxApps, autoFillRecommend)
        totalPages = pages.size
        val items = pages.firstOrNull() ?: listOf(GestureAppLauncher.AdapterItem.More)
        val side = if (corner == Corner.BOTTOM_RIGHT || corner == Corner.SIDE_RIGHT) 1 else 0
        launcher.setAdapter(
            GestureAppLauncher.SlideAdapter(items) { item, index ->
                viewFactory?.create(item, index) ?: itemView(item)
            },
        )
        launcher.setLayoutSide(side)
        val navbar = hasNavBar()
        val defaultRadius = context.resources.getDimension(
            if (navbar) R.dimen.slide_gesture_launcher_item_radius
            else R.dimen.slide_gesture_launcher_item_radius_no_nav_bar,
        )
        val radiusPx = if (radiusDp > 0) {
            radiusDp * context.resources.displayMetrics.density
        } else {
            defaultRadius
        }
        launcher.setRadiusPx(radiusPx)
        launcher.setSafeDegrees(if (navbar) 0 else 3)
        launcher.setCenterPosition(screenH.toFloat())
    }

    /** Finger events end (UP/CANCEL forwarded) → stop the preview timer. */
    fun onGestureFinished() {
        previewMode = false
        mainHandler.removeCallbacks(autoRetract)
    }

    private fun hasNavBar(): Boolean {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return id > 0 && context.resources.getDimensionPixelSize(id) > 0
    }

    /**
     * Builds pages of candidate apps.
     * Sequences:
     * 1. Pinned apps
     * 2. Recommended apps
     * Wraps each batch of [maxApps] items with a trailing "更多" tile.
     */
    fun buildPages(maxApps: Int, autoFillRecommend: Boolean): List<List<GestureAppLauncher.AdapterItem>> {
        val all = repo.cachedAll()
        val pinnedApps = ArrayList<com.repl.bubbledrawer.pinyin.BubbleApp>()
        val pinnedKeys = HashSet<String>()
        for (ref in pinStore.pins()) {
            val app = all.firstOrNull { it.packageName == ref.packageName && it.userId == ref.userId }
                ?: repo.findApp(ref.packageName, ref.userId)
            if (app != null) {
                pinnedApps.add(app)
                pinnedKeys.add("${app.packageName}#${app.userId}")
            }
        }

        val recCount = maxOf(15, maxApps * 3)
        val recommendations = com.repl.bubbledrawer.data.predict.AppPredictor.getRecommendations(
            context, all, pinnedKeys, recCount,
        )

        val resultPages = ArrayList<List<GestureAppLauncher.AdapterItem>>()

        if (pinnedApps.isEmpty() && recommendations.isEmpty()) {
            resultPages.add(listOf(GestureAppLauncher.AdapterItem.More))
            return resultPages
        }

        if (pinnedApps.isEmpty()) {
            var idx = 0
            while (idx < recommendations.size) {
                val chunk = recommendations.subList(idx, minOf(idx + maxApps, recommendations.size))
                resultPages.add(chunk.map { GestureAppLauncher.AdapterItem.AppItem(it) } + GestureAppLauncher.AdapterItem.More)
                idx += maxApps
            }
            return resultPages
        }

        if (!autoFillRecommend && pinnedApps.size <= maxApps) {
            // First page contains only the pinned apps without filling
            resultPages.add(pinnedApps.map { GestureAppLauncher.AdapterItem.AppItem(it) } + GestureAppLauncher.AdapterItem.More)
            // Subsequent pages contain recommendations
            var rIdx = 0
            while (rIdx < recommendations.size) {
                val chunk = recommendations.subList(rIdx, minOf(rIdx + maxApps, recommendations.size))
                resultPages.add(chunk.map { GestureAppLauncher.AdapterItem.AppItem(it) } + GestureAppLauncher.AdapterItem.More)
                rIdx += maxApps
            }
            return resultPages
        }

        // Pinned apps + recommendations combined pool
        val pool = ArrayList<com.repl.bubbledrawer.pinyin.BubbleApp>()
        pool.addAll(pinnedApps)
        pool.addAll(recommendations)

        var idx = 0
        while (idx < pool.size) {
            val chunk = pool.subList(idx, minOf(idx + maxApps, pool.size))
            resultPages.add(chunk.map { GestureAppLauncher.AdapterItem.AppItem(it) } + GestureAppLauncher.AdapterItem.More)
            idx += maxApps
        }
        return resultPages
    }

    /** Inflates the verbatim port of slide_gesture_list_item.xml. */
    private fun itemView(item: GestureAppLauncher.AdapterItem): View {
        val v = LayoutInflater.from(context)
            .inflate(R.layout.slide_gesture_list_item, launcher, false)
        v.layoutParams = GestureAppLauncher.LayoutParams()
        val icon = v.findViewById<de.hdodenhof.circleimageview.CircleImageView>(R.id.slide_icon)
        when (item) {
            is GestureAppLauncher.AdapterItem.AppItem -> icon.setImageDrawable(repo.icon(item.app))
            GestureAppLauncher.AdapterItem.More -> icon.setImageResource(R.drawable.icon_gesture_more_app)
        }
        // ORIGINAL: the adapter sets a click listener on every tile
        // (C2937F.m9712g :142 → C2937F$a.onClick :56-69 → mo9283j(item, view, reason=1)),
        // so with the panel open a plain TAP on an icon launches it without any drag.
        // (The corner-drag path launches via m9729p→m9736y with reason=0 instead.)
        v.setOnClickListener { launcher.clickSelect(item, v) }
        return v
    }

    /**
     * Fresh DOWN on void while the fan is open — verbatim C2822g.onTouchEvent
     * :381-393: DOWN → m9254Z() (close: m9245P → m9738l retract) + onGestureCanceled(1)
     * (stats-only in the original, :1096-1098; our onGestureCanceled maps to the same
     * retract, so one call is equivalent). ACTION_POINTER_DOWN :383-385 is ignored.
     */
    fun onVoidTouchDown() = retractAfterAction()

    fun forward(ev: MotionEvent, pointerId: Int): Boolean {
        val r = launcher.forwardEvent(ev, pointerId)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            onGestureFinished()
        }
        return r
    }

    private fun retractAfterAction() {
        launcher.retract(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) { shown(false) }
            override fun onAnimationEnd(animation: Animator) { shown(false) }
        })
        onGestureFinished()
    }

    private fun shown(v: Boolean) {
        isBusy = launcher.state != 0
        if (!v) onShownChanged?.invoke(false)
    }

    fun forceRetract() = retractAfterAction()

    fun destroy() {
        mainHandler.removeCallbacks(autoRetract)
        launcher.removeAllViews()
    }
}
