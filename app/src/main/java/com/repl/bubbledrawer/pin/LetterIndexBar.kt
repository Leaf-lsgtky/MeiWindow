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
        set(v) { field = v; bar.letters = v }

    var currentLetter: String?
        get() = bar.currentLetter
        set(v) { bar.currentLetter = v }

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
            normalPaint.color = token
            bgPaint.color = token
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
            set(v) { field = v; requestLayout(); invalidate() }
        var currentLetter: String? = null
            set(v) { field = v; invalidate() }
        var onLetterSelected: ((String) -> Unit)? = null

        private val barW = 28f * density
        private val padRight = 4f * density
        private val vSpace = 4f * density
        private val selR = 7f * density

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension((barW + padRight).toInt(), MeasureSpec.getSize(heightMeasureSpec))
        }

        /** pitch = textPx + 4dp (C4424c :539-542 rect height = textSize + space),
         *  shrunk to fit when the screen is short (:226 logic). */
        private fun pitch(): Float {
            val natural = textPx + vSpace
            val usable = height.toFloat()
            val total = letters.size * natural
            return if (usable > 0 && total > usable) usable / letters.size else natural
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val p = pitch()
            val cx = barW + padRight - selR          // :384 x = width − padRight − r
            val textX = cx                            // letters share the circle centre line
            letters.forEachIndexed { i, l ->
                val cy = p * (i + 0.5f)
                val isCurrent = l == currentLetter
                if (isCurrent) {
                    canvas.drawCircle(cx, cy, selR, bgPaint)
                }
                val paint = if (isCurrent) currentPaint else normalPaint
                val fm = paint.fontMetrics
                canvas.drawText(l, textX, cy - (fm.ascent + fm.descent) / 2f, paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val p = pitch()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val idx = (event.y / p).toInt().coerceIn(0, letters.size - 1)
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
