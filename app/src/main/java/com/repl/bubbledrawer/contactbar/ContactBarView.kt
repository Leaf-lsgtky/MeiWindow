package com.repl.bubbledrawer.contactbar

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.hdodenhof.circleimageview.CircleImageView
import kotlin.math.abs

/**
 * The floating bar itself: a translucent strip the width of the small window, carrying every recent
 * conversation as **avatar + name**.
 *
 * Flyme's original item is `notification_list_item.xml`: a 36dp `CircleImageView`, a 9dp
 * `sans-serif-medium` `TextView` (`app_contact_name`, 45dp wide, one line, 3dp below the avatar) and
 * a `NewMessageView` red dot pinned to the avatar's top-end (as long as the notification is still
 * there — `C3011v.java:77-83`). The whole item lives inside a 63dp-tall pill
 * (`notification_window_bg_width`) with 8dp horizontal padding. All of that is mirrored here; the
 * corner radius comes from the window itself ([setCornerRadius]) so the bar does not look like a pill.
 *
 * Gestures mirror `ContactListView`'s `ItemTouchHelper` (`C2929d`): a vertical swipe on an avatar
 * removes that conversation from the bar. Flyme removes the row and calls the store's remove; here the
 * controller forgets the conversation (see [ContactBarController.onRemoveConversation]).
 */
class ContactBarView(context: Context) : FrameLayout(context) {

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }

    /** 36dp avatar, 45dp-wide name, 8dp padding per item — Flyme's `notification_list_item.xml`. */
    private val avatarSize = dp(36)
    private val nameWidth = dp(45)
    private val itemPadding = dp(8)

    private val backgroundDrawable = GradientDrawable().apply {
        cornerRadius = dp(DEFAULT_CORNER_DP).toFloat()
        setColor(barColor())
        setStroke(dp(1), strokeColor())
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val removeThreshold = dp(22)

    init {
        background = backgroundDrawable
        clipToPadding = false
        addView(
            row,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
    }

    private fun night(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun barColor(): Int = if (night()) Color.parseColor("#E61C1C1C") else Color.parseColor("#F2FFFFFF")

    private fun strokeColor(): Int = if (night()) Color.parseColor("#33FFFFFF") else Color.parseColor("#1F000000")

    private fun nameColor(): Int = if (night()) Color.parseColor("#F2FFFFFF") else Color.parseColor("#E6000000")

    /** Re-themed by the controller when the system switches light/dark. */
    fun refreshTheme() {
        backgroundDrawable.setColor(barColor())
        backgroundDrawable.setStroke(dp(1), strokeColor())
        for (index in 0 until row.childCount) {
            itemName(row.getChildAt(index))?.setTextColor(nameColor())
        }
    }

    /**
     * Corner radius in pixels — the ROM's own window radius
     * (`MiuiFreeformModeTaskInfo.getCornerRadius()`, already in screen pixels), so the bar matches the
     * window it belongs to. 13dp is the fallback when the ROM does not answer.
     */
    fun setCornerRadius(radiusPx: Float?) {
        backgroundDrawable.cornerRadius = radiusPx ?: dp(DEFAULT_CORNER_DP).toFloat()
    }

    /** [maxCount] is what fits inside the window width; the rest stays for the next resize. */
    fun bind(
        items: List<Conversation>,
        maxCount: Int,
        fallback: Drawable?,
        onClick: (Conversation) -> Unit,
        onRemove: (Conversation) -> Unit,
    ) {
        val shown = items.take(maxCount.coerceAtLeast(1))
        while (row.childCount > shown.size) row.removeViewAt(row.childCount - 1)
        shown.forEachIndexed { index, conversation ->
            val item = row.getChildAt(index) ?: newItem().also { row.addView(it) }
            bindItem(item, conversation, fallback, onClick, onRemove)
        }
    }

    private fun bindItem(
        item: View,
        conversation: Conversation,
        fallback: Drawable?,
        onClick: (Conversation) -> Unit,
        onRemove: (Conversation) -> Unit,
    ) {
        val avatar = item.findViewById<CircleImageView>(AVATAR_ID)
        val name = item.findViewById<TextView>(NAME_ID)
        val dot = item.findViewById<View>(DOT_ID)

        avatar.setImageDrawable(conversation.icon?.takeIf { it.usable() } ?: fallback)
        name.text = conversation.title
        name.setTextColor(nameColor())
        // Flyme hides the red dot once the notification behind the row is gone; our memory keeps the
        // row but the dot only shows while a notification is actually posted.
        dot.visibility = if (conversation.live) VISIBLE else GONE

        item.translationY = 0f
        item.alpha = 1f
        item.contentDescription = conversation.title
        item.setOnTouchListener(SwipeTouchListener(item, onClick, onRemove, conversation))
    }

    /** Tap opens the conversation, a vertical swipe removes it — one touch stream, both decisions. */
    private inner class SwipeTouchListener(
        private val item: View,
        private val onClick: (Conversation) -> Unit,
        private val onRemove: (Conversation) -> Unit,
        private val conversation: Conversation,
    ) : OnTouchListener {

        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downAt = event.eventTime
                    dragging = false
                    item.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).start()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downY
                    val dx = event.rawX - downX
                    if (!dragging && abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                        dragging = true
                        item.animate().cancel()
                        item.scaleX = 1f
                        item.scaleY = 1f
                    }
                    if (dragging) {
                        item.translationY = dy
                        item.alpha = 1f - (abs(dy).toFloat() / (removeThreshold * 3)).coerceIn(0f, 0.7f)
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    item.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                    val dy = event.rawY - downY
                    val dx = event.rawX - downX
                    val quick = event.eventTime - downAt
                    when {
                        dragging && abs(dy) >= removeThreshold -> flyOut(dy > 0)
                        dragging -> item.animate().translationY(0f).alpha(1f).setDuration(120).start()
                        // A plain tap: the touch stream is ours, so the click is decided here.
                        abs(dy) <= touchSlop && abs(dx) <= touchSlop && quick <= TAP_MAX_MS -> onClick(conversation)
                        else -> Unit
                    }
                    dragging = false
                    return true
                }
            }
            return false
        }

        private fun flyOut(downwards: Boolean) {
            item.animate()
                .translationY(if (downwards) item.height * 1.6f else -item.height * 1.6f)
                .alpha(0f)
                .setDuration(160)
                .withEndAction { onRemove(conversation) }
                .start()
        }
    }

    private fun itemName(item: View): TextView? = item.findViewById(NAME_ID)

    /**
     * A `BitmapDrawable` whose bitmap has been recycled must never reach the view: drawing it throws
     * `Canvas: trying to use a recycled bitmap` (that is a real HyperOS fault — it happens inside
     * `BitmapDrawable.draw` when a crossfade keeps drawing artwork the ROM recycled). Our avatars are
     * copies we own (see [RecentConversations.ownAvatar]), this is the belt-and-braces check for the
     * fallback icon and for anything an icon cache hands us.
     */
    private fun Drawable.usable(): Boolean = !(this is BitmapDrawable && bitmap.isRecycled)

    private fun newItem(): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        val avatar = CircleImageView(context).apply {
            id = AVATAR_ID
            layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize)
            borderWidth = dp(1)
            borderColor = Color.parseColor("#33000000")
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val name = TextView(context).apply {
            id = NAME_ID
            layoutParams = LinearLayout.LayoutParams(nameWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        column.addView(avatar)
        column.addView(name)

        val dot = View(context).apply {
            id = DOT_ID
            layoutParams = FrameLayout.LayoutParams(dp(6), dp(6), Gravity.TOP or Gravity.END).apply {
                marginEnd = dp(6)
                topMargin = dp(2)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF3B30"))
            }
            visibility = GONE
        }

        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = itemPadding / 2
                marginEnd = itemPadding / 2
            }
            isClickable = false
            addView(column)
            addView(dot)
        }
    }

    /** How many items fit in [widthPx], so a resize drag never clips a half avatar. */
    fun capacityFor(widthPx: Int): Int {
        val itemWidth = avatarSize + itemPadding * 2
        val usable = widthPx - paddingLeft - paddingRight
        if (usable <= itemWidth) return 1
        return (usable / itemWidth).coerceIn(1, MAX_ITEMS)
    }

    fun setPaddingDp(horizontal: Int, vertical: Int) {
        setPadding(dp(horizontal), dp(vertical), dp(horizontal), dp(vertical))
    }

    /** Bar height including the name line (Flyme's `notification_window_bg_width` = 63dp). */
    fun barHeightPx(): Int = dp(BAR_HEIGHT_DP)

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

    fun isEmpty(): Boolean = row.childCount == 0

    fun fadeIn() {
        if (visibility != VISIBLE) {
            alpha = 0f
            visibility = VISIBLE
        }
        animate().alpha(1f).setDuration(140).start()
    }

    private companion object {
        /** Flyme caps the list at 20 but only a handful fit a portrait window. */
        const val MAX_ITEMS = 6

        /** Fallback when the ROM does not report a window radius (user-measured ≈13dp). */
        const val DEFAULT_CORNER_DP = 13

        /** Flyme `notification_window_bg_width` = 63dp: 7dp top padding + 36dp avatar + name. */
        const val BAR_HEIGHT_DP = 63

        const val TAP_MAX_MS = 280L

        val AVATAR_ID = View.generateViewId()
        val NAME_ID = View.generateViewId()
        val DOT_ID = View.generateViewId()
    }
}
