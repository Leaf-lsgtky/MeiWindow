package com.repl.bubbledrawer.pin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.RelativeLayout
import android.widget.TextView
import com.repl.bubbledrawer.R
import kotlin.math.max

/**
 * Port of `com.meizu.common.fastscrollletter` (C4423b container + C4424c column +
 * overlay bubble TextView). Constants resolved from the obfuscated R-id chains:
 *
 *  STRUCTURE (C4423b.m16154s :331-345): the letter column and the bubble are
 *  SIBLING children of one RelativeLayout — the bubble is never clipped by the
 *  column. Bubble: 59dp square (mc_fastscroll_letter_overlay_layout_width),
 *  rightMargin 44dp (…overlay_layout_margin_right, :261/:309), circular
 *  background #cccccc (mc_fast_scroll_letter_color_default via single-color map
 *  f15528u, m16153r :302-303; OvalShape+ShapeDrawable per m16148m :250-253),
 *  text #ffffff (mc_fastscroll_letter_overlay_text_color, :304),
 *  includeFontPadding false (:341), gravity center (:342), initially GONE (:343).
 *  Text size by length (m16148m :244-248): len 1–2 → 32.5sp (f26561D0),
 *  len 3 → 16sp (f26564E0). Follow: translationY = y − w/2 clamped
 *  [0, columnH − w] (:121-131); horizontal fixed by margin.
 *
 *  COLUMN (C4424c): width 28dp (mc_fastscroll_letter_layout_wdith f26552A0 :475),
 *  padRight 4dp (…layout_padding_right f26688y0 :473), text 12px
 *  (mc_fastscroll_letter_text_size f26576I0 :469 — paint px units, as original),
 *  vertical space 4dp (f26582K0 :470). Colors from m16172k/m16171j:
 *  normal letter f15566U = fd_sys_color_surface_container_highest_default
 *  (day #664d4d4d / night #6679797e — resource, :395/:465/:479), CURRENT letter
 *  text f15586t = -1 = WHITE (:393/:467); current background = CIRCLE radius 7dp
 *  (mc_fastscroll_navigation_letter_selected_background_radius f26579J0 :471)
 *  fill f15587u = fd_sys_color_surface_container_highest_default (:468),
 *  centered x = width − padRight − r (:384).
 *  Letters A–Z (f15550V :39) + specials ★/# (FastScrollLetter.SpecialLetters :72-85).
 *  Column height = count × (12px + 4dp) (:373); when the screen is short the
 *  original shrinks pitch (m16166g :226) — we fit the pitch the same way.
 */
class LetterIndexBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RelativeLayout(context, attrs) {

    private val density = context.resources.displayMetrics.density

    private val bar = BarColumn(context)
    private val bubble = TextView(context)

    var onLetterSelected: ((String) -> Unit)? = null
        get() = field
        set(v) { field = v; bar.onLetterSelected = v }

    var letters: List<String> = DEFAULT_LETTERS
        set(v) {
            if (field != v) {
                field = v
                bar.letters = v
            }
        }

    var currentLetter: String?
        get() = bar.currentLetter
        set(v) {
            if (bar.currentLetter != v) {
                bar.currentLetter = v
            }
        }

    /** Vertical CENTERING of the letter column (panel host passes true). */
    var centerVertically: Boolean
        get() = bar.centerVertically
        set(v) { bar.centerVertically = v; bar.invalidate() }

    /** Sampled display for short hosts (see [BarColumn.allowSampledDisplay]). */
    var allowSampledDisplay: Boolean
        get() = bar.allowSampledDisplay
        set(v) { bar.allowSampledDisplay = v; bar.requestLayout(); bar.invalidate() }

    init {
        bar.id = 10086 // the id the original assigns (C4423b :334)
        addView(bar, RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.MATCH_PARENT,
        ).apply {
            addRule(RelativeLayout.ALIGN_PARENT_END)
        })

        val w = (59 * density).toInt()
        addView(bubble, RelativeLayout.LayoutParams(w, w).apply {
            addRule(RelativeLayout.ALIGN_PARENT_END)
            marginEnd = (14 * density).toInt() // circle center lands 44dp inside the edge
            addRule(RelativeLayout.ALIGN_TOP, bar.id) // translationY then tracks the finger
        })
        bubble.apply {
            setTextColor(ctxColor(R.color.mc_fastscroll_letter_overlay_text_color)) // #ffffff
            includeFontPadding = false
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = ShapeDrawable(OvalShape()).apply {
                paint.color = ctxColor(R.color.mc_fast_scroll_letter_color_default) // #cccccc
            }
        }
        bar.refreshColors() // initial token colors (normal letters / selected circle)
    }

    /** Re-resolve resource colors (DayNight) — invoked from BarColumn.onConfigurationChanged. */
    fun refreshColors() = bar.refreshColors()

    /**
     * Theme-injected colors (Compose host): the island follows the miuix scheme instead of
     * the resource tokens when the caller passes explicit values. `null` keeps the
     * resource default so the View still works standalone.
     */
    fun refreshColors(normal: Int?, currentText: Int?, selectedBg: Int?) {
        bar.refreshColors(normal, currentText, selectedBg)
    }

    private fun ctxColor(id: Int) = androidx.core.content.ContextCompat.getColor(context, id)

    private inner class BarColumn(context: Context) : View(context) {

        // ORIGINAL C4424c :469: f15588v = AbstractC0058i.m284d(ctx, 12px-dimen) and
        // m284d = TypedValue.applyDimension(COMPLEX_UNIT_SP, 12, dm) → 12 SP, not 12px!
        private val textPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics,
        )

        private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = textPx
        }
        private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = -1                           // f15586t = 0xFFFFFFFF (white, literal in code)
            textAlign = Paint.Align.CENTER
            textSize = textPx
        }
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        /** f15566U/f15587u = fd_sys_color_surface_container_highest_default (day/night) */
        fun refreshColors() {
            val token = ctxColor(R.color.fd_sys_color_surface_container_highest_default)
            refreshColors(normal = token, currentText = null, selectedBg = token)
        }

        /** Overload for a theme-injecting host (see outer [LetterIndexBar.refreshColors]). */
        fun refreshColors(normal: Int?, currentText: Int?, selectedBg: Int?) {
            val token = normal ?: ctxColor(R.color.fd_sys_color_surface_container_highest_default)
            normalPaint.color = token
            bgPaint.color = selectedBg ?: token
            currentPaint.color = currentText ?: -1 // f15586t = white literal in the original
            val bubbleBg = bubble.background as? ShapeDrawable
            bubbleBg?.paint?.color = ctxColor(R.color.mc_fast_scroll_letter_color_default)
            bubble.setTextColor(ctxColor(R.color.mc_fastscroll_letter_overlay_text_color))
            invalidate()
        }

        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
            super.onConfigurationChanged(newConfig)
            post { refreshColors() }
        }

        var letters: List<String> = DEFAULT_LETTERS
            set(v) {
                if (field != v) {
                    field = v
                    requestLayout()
                    invalidate()
                }
            }
        var currentLetter: String? = null
            set(v) {
                if (field != v) {
                    field = v
                    invalidate()
                }
            }
        var onLetterSelected: ((String) -> Unit)? = null

        /** Vertical CENTERING (panel bar): draw the column around the view centre. */
        var centerVertically: Boolean = false

        /**
         * Sampled display for short hosts: when the full column doesn't fit, draw every
         * Nth glyph (A C E …) but keep touch mapped 1:1 onto the FULL list — the meizu
         * original shrank the pitch instead; sampled rows read better on a small panel.
         */
        var allowSampledDisplay: Boolean = false

        private val barW = 28f * density
        private val padRight = 4f * density
        private val vSpace = 4f * density
        private val selR = 7f * density

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension((barW + padRight).toInt(), MeasureSpec.getSize(heightMeasureSpec))
        }

        /** Full-list pitch = textPx + 4dp (C4424c :539-542), shrunk to fit when short. */
        private fun fullPitch(): Float {
            val natural = textPx + vSpace
            val usable = height.toFloat()
            val total = letters.size * natural
            return if (usable > 0 && total > usable) usable / letters.size else natural
        }

        /**
         * Row stride actually drawn. `1` = every letter; N>1 = every Nth glyph drawn
         * (sampled mode), only while [allowSampledDisplay] is on. Touch always divides by
         * [fullPitch] so dragging over drawn rows still selects hidden letters.
         */
        private fun drawStride(): Int {
            if (!allowSampledDisplay) return 1
            val natural = textPx + vSpace
            val total = letters.size * natural
            if (height <= 0 || total <= height) return 1
            var stride = 2
            while (stride < letters.size &&
                (letters.size / stride + 1) * natural > height) {
                stride++
            }
            return stride
        }

        /** Top y of the drawn column (centred when [centerVertically]). */
        private fun topOffset(p: Float, stride: Int): Float {
            if (!centerVertically) return 0f
            val rows = if (stride >= letters.size) letters.size else letters.size / stride + 1
            val drawn = rows * p
            return ((height - drawn) / 2f).coerceAtLeast(0f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val p = fullPitch()
            val stride = drawStride()
            val top = topOffset(p, stride)
            val cx = barW + padRight - selR          // :384 x = width − padRight − r
            val textX = cx                            // letters share the circle centre line
            var i = 0
            while (i < letters.size) {
                val l = letters[i]
                val cy = top + p * (i + 0.5f)
                val isCurrent = l == currentLetter
                if (isCurrent) {
                    canvas.drawCircle(cx, cy, selR, bgPaint)
                }
                val paint = if (isCurrent) currentPaint else normalPaint
                val fm = paint.fontMetrics
                canvas.drawText(l, textX, cy - (fm.ascent + fm.descent) / 2f, paint)
                i += stride
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val p = fullPitch()
            val stride = drawStride()
            val top = topOffset(p, stride)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    // Touch maps onto the FULL list regardless of sampling: y → row under
                    // the finger (anchored to the drawn column), then expand to the real
                    // letter index (row * stride, clamped).
                    val row = ((event.y - top) / p).toInt().coerceIn(0, letters.size - 1)
                    val idx = if (stride <= 1) row else (row * stride).coerceIn(0, letters.size - 1)
                    val l = letters[idx]
                    if (l != currentLetter) {
                        currentLetter = l
                        showBubble(l, event.y)
                        onLetterSelected?.invoke(l)
                        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    } else {
                        moveBubble(event.y)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    bubble.visibility = GONE
                    return true
                }
            }
            return false
        }
    }

    private fun showBubble(text: String, y: Float) {
        bubble.text = text
        bubble.textSize = if (text.length == 3) 16f else 32.5f // m16148m :244-248
        bubble.visibility = VISIBLE
        moveBubble(y)
    }

    /** translationY = y − w/2 clamped [0, columnH − w] (C4423b :121-131). */
    private fun moveBubble(y: Float) {
        val w = 59f * density
        bubble.translationY = (y - w / 2f).coerceIn(0f, max(0f, bar.height - w))
    }

    companion object {
        val DEFAULT_LETTERS: List<String> =
            listOf("★") + ('A'..'Z').map { it.toString() } + listOf("#")
    }
}
