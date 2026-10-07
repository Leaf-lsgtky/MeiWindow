package com.repl.bubbledrawer.bubble

import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.data.AppRepository
import com.repl.bubbledrawer.data.LaunchCountStore
import com.repl.bubbledrawer.data.PinStore
import com.repl.bubbledrawer.gesture.Corner
import com.repl.bubbledrawer.launch.ILaunchStrategy
import com.repl.bubbledrawer.more.MoreAppsActivity

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
) {
    val launcher = GestureAppLauncher(context)
    var isBusy = false
        private set

    init {
        launcher.setCallback(object : GestureAppLauncher.Callback {
            override fun onItemSelected(item: GestureAppLauncher.AdapterItem, view: View, index: Int) {
                when (item) {
                    is GestureAppLauncher.AdapterItem.AppItem -> {
                        launchStrategy.launch(context, item.app)
                        LaunchCountStore.increment(context, item.app.packageName)
                    }
                    GestureAppLauncher.AdapterItem.More -> {
                        // original: MoreAppWindow launched as its own window; we start the Activity
                        context.startActivity(
                            Intent(context, MoreAppsActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                retractAfterAction()
            }

            override fun onGestureCanceled(reason: Int) = retractAfterAction()
            override fun onExpandStarted() { isBusy = true }
        })
    }

    /** @param screenH full-screen height in px (the launcher window is MATCH_PARENT). */
    fun show(corner: Corner, screenW: Int, screenH: Int) {
        val side = if (corner == Corner.BOTTOM_RIGHT || corner == Corner.SIDE_RIGHT) 1 else 0
        launcher.setAdapter(
            GestureAppLauncher.SlideAdapter(buildItems()) { item, _ -> itemView(item) },
        )
        launcher.setLayoutSide(side)
        val navbar = hasNavBar()
        launcher.setRadiusPx(
            context.resources.getDimension(
                if (navbar) R.dimen.slide_gesture_launcher_item_radius
                else R.dimen.slide_gesture_launcher_item_radius_no_nav_bar,
            ),
        )
        // m9237F :497-504 — navbar: radius277/deg0, no navbar (full-screen apps): 242/deg3
        launcher.setSafeDegrees(if (navbar) 0 else 3)
        launcher.setCenterPosition(screenH.toFloat()) // :863-866 → onLayout → auto-expand :793
    }

    private fun hasNavBar(): Boolean {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return id > 0 && context.resources.getDimensionPixelSize(id) > 0
    }

    /** m9235C (:447-463): first 6 pins, then the "更多" tile. */
    private fun buildItems(): List<GestureAppLauncher.AdapterItem> {
        val all = repo.cachedAll()
        val out = ArrayList<GestureAppLauncher.AdapterItem>()
        for (ref in pinStore.pins()) {
            if (out.size >= 6) break // :453 i6>=6 break
            all.firstOrNull { it.packageName == ref.packageName && it.userId == ref.userId }
                ?.let { out.add(GestureAppLauncher.AdapterItem.AppItem(it)) }
        }
        out.add(GestureAppLauncher.AdapterItem.More) // :460-461 more tile (icon_gesture_more_app)
        return out
    }

    /** Inflates the verbatim port of slide_gesture_list_item.xml. */
    private fun itemView(item: GestureAppLauncher.AdapterItem): View {
        val v = LayoutInflater.from(context)
            .inflate(R.layout.slide_gesture_list_item, launcher, false)
        v.layoutParams = GestureAppLauncher.LayoutParams() // circle bounds set in onMeasure
        val icon = v.findViewById<de.hdodenhof.circleimageview.CircleImageView>(R.id.slide_icon)
        when (item) {
            is GestureAppLauncher.AdapterItem.AppItem -> icon.setImageDrawable(repo.icon(item.app))
            GestureAppLauncher.AdapterItem.More -> icon.setImageResource(R.drawable.icon_gesture_more_app)
        }
        return v
    }

    fun forward(ev: MotionEvent, pointerId: Int) = launcher.forwardEvent(ev, pointerId)

    private fun retractAfterAction() {
        launcher.retract(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { isBusy = false }
            override fun onAnimationStart(animation: android.animation.Animator) { isBusy = false }
        })
    }

    fun forceRetract() = launcher.retract(null)

    fun destroy() = launcher.removeAllViews()
}
