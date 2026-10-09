package com.repl.bubbledrawer.contactbar

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import de.hdodenhof.circleimageview.CircleImageView

/**
 * The floating bar itself: a translucent pill the width of the small window, carrying the
 * avatars of the app's recent conversations.
 *
 * Flyme's original is `notification_layout_main.xml` (63dp tall `notification_bg` + a
 * `ContactListView` of 4-avatar rows, `windowmode/views/C2831I.java:424`). This is the same idea
 * built in code — the module's other surfaces (fan tiles, 更多 panel) are also code-built, and a
 * hand-built row keeps the bar independent of the module's resource ids in the SystemUI process.
 */
class ContactBarView(context: Context) : FrameLayout(context) {

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }

    private val avatarSize = dp(46)
    private val avatarGap = dp(7)

    private val backgroundDrawable = GradientDrawable().apply {
        cornerRadius = dp(31).toFloat()
        setColor(barColor())
        setStroke(dp(1), strokeColor())
    }

    init {
        background = backgroundDrawable
        clipToPadding = false
        addView(
            row,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
    }

    private fun barColor(): Int {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.parseColor("#E61C1C1C") else Color.parseColor("#F2FFFFFF")
    }

    private fun strokeColor(): Int {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (night) Color.parseColor("#33FFFFFF") else Color.parseColor("#1F000000")
    }

    /** Re-themed by the controller when the system switches light/dark. */
    fun refreshTheme() {
        backgroundDrawable.setColor(barColor())
        backgroundDrawable.setStroke(dp(1), strokeColor())
    }

    /** [maxCount] is what fits inside the window width; the rest stays for the next resize. */
    fun bind(items: List<Conversation>, maxCount: Int, fallback: Drawable?, onClick: (Conversation) -> Unit) {
        val shown = items.take(maxCount.coerceAtLeast(1))
        while (row.childCount > shown.size) row.removeViewAt(row.childCount - 1)
        shown.forEachIndexed { index, conversation ->
            val avatar = (row.getChildAt(index) as? CircleImageView) ?: newAvatar().also { row.addView(it) }
            avatar.setImageDrawable(conversation.icon ?: fallback)
            avatar.contentDescription = conversation.title
            avatar.setOnClickListener { v ->
                v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(70).withEndAction {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                }.start()
                onClick(conversation)
            }
        }
    }

    private fun newAvatar() = CircleImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
            marginStart = avatarGap / 2
            marginEnd = avatarGap / 2
        }
        borderWidth = dp(1)
        borderColor = Color.parseColor("#33000000")
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    /** How many of [widthPx] worth of avatars fit, so a resize drag never clips a half avatar. */
    fun capacityFor(widthPx: Int): Int {
        val usable = widthPx - paddingLeft - paddingRight - avatarGap
        if (usable <= avatarSize) return 1
        return ((usable + avatarGap) / (avatarSize + avatarGap)).coerceIn(1, MAX_AVATARS)
    }

    fun setPaddingDp(horizontal: Int, vertical: Int) {
        setPadding(dp(horizontal), dp(vertical), dp(horizontal), dp(vertical))
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

    /** Unused views are dropped, never shown empty. */
    fun isEmpty(): Boolean = row.childCount == 0

    fun fadeIn() {
        if (visibility != View.VISIBLE) {
            alpha = 0f
            visibility = View.VISIBLE
        }
        animate().alpha(1f).setDuration(140).start()
    }

    private companion object {
        /** Flyme caps the list at 20 but only ~4 avatars fit a portrait window. */
        const val MAX_AVATARS = 6
    }
}
