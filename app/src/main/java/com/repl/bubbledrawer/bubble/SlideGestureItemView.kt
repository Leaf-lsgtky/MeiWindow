package com.repl.bubbledrawer.bubble

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.widget.RelativeLayout
import com.repl.bubbledrawer.R
import kotlin.math.min

/**
 * Port of decompiled `windowmode/widget/SlideGestureItemView.java` — bubble tile
 * that paints the aiming ring around its icon while `pressed` (set by
 * GestureAppLauncher.performHoverChild → child.setPressed, :643-651).
 *
 * Facts (line refs):
 *  - ring Paint ANTI_ALIAS STROKE #40ffffff (:145-148)
 *  - press  → 0→1 130ms PathInterpolator(.33,0,.66,1) (:167-181)
 *  - release→ 1→0 130ms same (:184-199)
 *  - busy handoff: opposite anim running → pend it, fire on end
 *    (dispatchSetPressed :202-222 + C2978d/C2980f onAnimationEnd :98-137)
 *  - draw: sw = (rView-rIcon)*p; circle(rIcon + sw/2) at center (:225-231).
 *    With the user's 图标形状 = 系统样式圆角矩形 the same ring is drawn as a rounded square
 *    (`drawRoundRect`, corner = icon radius × [SlideIconView.CORNER_RATIO); everything else —
 *    timing, handoff, geometry source — is unchanged.
 *  - rIcon = min(icon w,h)/2 ; rView = min(self w,h)/2 (:278-283)
 *  - attached → scales 1, p 0 (:245-248 via m9811g :158-162)
 * The original's C4411f haptic OnTouchListener (:152-155,269-274) is
 * flyme-internal vibration glue; omitted — no UI difference.
 */
class SlideGestureItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RelativeLayout(context, attrs) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        // original: getResources().getColor(slide_gesture_app_circle_icon_bg_color) (:148)
        color = androidx.core.content.ContextCompat.getColor(
            context, com.repl.bubbledrawer.R.color.slide_gesture_app_circle_icon_bg_color,
        )
    }

    private var iconRadius = 0f
    private var ringGap = 0f
    private var progress = 0f

    /**
     * Shape of the child icon ([SlideIconView.SHAPE_CIRCLE] / [SlideIconView.SHAPE_ROUNDED_RECT]).
     * The aiming ring follows it: a circle ring around a rounded-square icon would leave the corners
     * poking out of the highlight.
     */
    var iconShape: Int = SlideIconView.SHAPE_CIRCLE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val ringRect = android.graphics.RectF()

    private var expandAnim: ValueAnimator? = null
    private var collapseAnim: ValueAnimator? = null
    private var pending: Runnable? = null

    private val runExpand = Runnable { startExpand() }
    private val runCollapse = Runnable { startCollapse() }

    init { setWillNotDraw(false) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scaleX = 1f; scaleY = 1f
        progress = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pending = null
        expandAnim?.cancel(); collapseAnim?.cancel()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val icon = findViewById<View>(R.id.slide_icon)
        iconRadius = min(icon.measuredWidth, icon.measuredHeight) / 2f
        val viewRadius = min(measuredWidth, measuredHeight) / 2f
        ringGap = viewRadius - iconRadius
    }

    override fun dispatchSetPressed(pressed: Boolean) {
        super.dispatchSetPressed(pressed)
        // VERIFIED handoff direction, original dispatchSetPressed :202-222:
        //   pressed=true  → checks the COLLAPSE (1→0) anim; running → pend expand to its end
        //   pressed=false → checks the EXPAND (0→1) anim; running → pend collapse to its end
        // (previous port had the two checks swapped → two ValueAnimators writing `progress`
        // concurrently on fast hover churn: ring jumped/stuck.)
        if (pressed) {
            if (collapseAnim?.isRunning == true) pending = runExpand
            else { pending = null; startExpand() }
        } else {
            if (expandAnim?.isRunning == true) pending = runCollapse
            else { pending = null; startCollapse() }
        }
    }

    private fun startExpand() {
        val a = expandAnim ?: ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MotionSpec.RING_DURATION
            interpolator = MotionSpec.RING_INTERPOLATOR
            addUpdateListener { progress = it.animatedValue as Float; postInvalidate() }
            addListener(endHandoff())
            expandAnim = this
        }.also { expandAnim = it }
        if (a.isRunning) return
        a.start()
    }

    private fun startCollapse() {
        val a = collapseAnim ?: ValueAnimator.ofFloat(1f, 0f).apply {
            duration = MotionSpec.RING_DURATION
            interpolator = MotionSpec.RING_INTERPOLATOR
            addUpdateListener { progress = it.animatedValue as Float; postInvalidate() }
            addListener(endHandoff())
            collapseAnim = this
        }.also { collapseAnim = it }
        if (a.isRunning) return
        a.start()
    }

    private fun endHandoff() = object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            pending?.run()
            pending = null
        }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val w = ringGap * progress
        if (w > 0f) {
            ringPaint.strokeWidth = w
            val cx = measuredWidth / 2f
            val cy = measuredHeight / 2f
            if (iconShape == SlideIconView.SHAPE_ROUNDED_RECT) {
                // Same ring, rounded: half-size + the icon's own corner ratio, both grown by the
                // stroke's half width so the ring hugs the icon exactly like the circular one does.
                val half = iconRadius + w * 0.5f
                val corner = iconRadius * SlideIconView.CORNER_RATIO + w * 0.5f
                ringRect.set(cx - half, cy - half, cx + half, cy + half)
                canvas.drawRoundRect(ringRect, corner, corner, ringPaint)
            } else {
                canvas.drawCircle(cx, cy, iconRadius + w * 0.5f, ringPaint)
            }
        }
    }
}
