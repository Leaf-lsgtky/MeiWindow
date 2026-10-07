package com.repl.bubbledrawer.pin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Behavioural re-implementation of `com.meizu.common.fastscrollletter.FastScrollLetter`
 * (MzX library class, source not in the APK — the decompiled code only shows its
 * API surface: setLetters/setCurrentLetter/onLetterClick + an overlay bubble).
 * Visual parameters ARE in the APK resources (spec §6.5):
 *   bar width 28dp (mc_fastscroll_letter_layout_wdith)
 *   letter text 12px (mc_fastscroll_letter_text_size), normal #cccccc
 *   vertical gap 4dp (mc_fastscroll_navigation_letter_vertical_space)
 *   selected background radius 7dp (mc_fastscroll_navigation_letter_selected_background_radius)
 *   overlay bubble 59dp wide (mc_fastscroll_letter_overlay_layout_width), text 32.5sp
 *     (18sp 2-char / 16sp 3-char variants), white (fastscroller_overlay_textcolor),
 *     right offset 44dp from the bar (mc_fastscroll_letter_overlay_layout_margin_right)
 *   bar padding top/bottom 50dp (fastscroller_letterbar_padding_top/bottom), right 3dp
 * Letters: A–Z constant array in the original (:39); the settings page prepends ★
 * (SpecialLetters.STAR, FastScrollLetter.java:74).
 */
class LetterIndexBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val fontScale = resources.displayMetrics.scaledDensity

    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#cccccc") // mc_fast_scroll_letter_color_default
        textAlign = Paint.Align.CENTER
        textSize = 12 * fontScale          // mc_fastscroll_letter_text_size 12px (px→sp≈ on phones)
    }
    private val selectedBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0x33, 0x33, 0x33, 0x33) // tinted pill behind current letter
    }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#cccccc")
        textAlign = Paint.Align.CENTER
    }
    private val overlayTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ffffffff") // fastscroller_overlay_textcolor
        textAlign = Paint.Align.CENTER
    }

    var letters: List<String> = DEFAULT_LETTERS
        set(v) {
            field = v
            invalidate()
        }

    /** Selected letter, drawn with background pill; ★ = current/pinned (setCurrentLetter). */
    var currentLetter: String? = null
        set(v) {
            field = v
            invalidate()
        }

    var onLetterSelected: ((String) -> Unit)? = null

    private val barWidthPx = (28 * density)          // 28dp
    private val padVPx = (50 * density)              // 50dp top/bottom
    private val padRPx = (3 * density)               // 3dp right
    private val gapPx = (4 * density)                // 4dp vertical space
    private val selRadiusPx = (7 * density)          // 7dp corner radius

    private var overlayText: String? = null
    private var downY = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (barWidthPx + padRPx).toInt()
        setMeasuredDimension(w, MeasureSpec.getSize(heightMeasureSpec))
    }

    private fun itemPitch(): Float {
        val fm = letterPaint.fontMetrics
        val h = max(-(fm.ascent), fm.descent)
        return h + gapPx
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pitch = itemPitch()
        val total = letters.size * pitch
        val usable = height - padVPx * 2
        val step = if (total > usable) usable / letters.size else pitch
        val cx = width - padRPx / 2 - barWidthPx / 2
        val fm = letterPaint.fontMetrics
        letters.forEachIndexed { i, l ->
            val cy = padVPx + step * (i + 0.5f)
            if (l == currentLetter) {
                val rw = barWidthPx * 0.8f
                canvas.drawRoundRect(
                    cx - rw / 2, cy - step / 2, cx + rw / 2, cy + step / 2,
                    selRadiusPx, selRadiusPx, selectedBgPaint,
                )
            }
            canvas.drawText(l, cx, cy - (fm.ascent + fm.descent) / 2, letterPaint)
        }
        // overlay bubble (setOverlayLetterBackgroundColors single-color map — one bubble)
        overlayText?.let { t ->
            val size = when (t.length) {
                3 -> 16f // mc_fastscroll_letter_overlay_three_text_size
                2 -> 18f // two
                else -> 32.5f // single / default
            }
            overlayTextPaint.textSize = size * fontScale
            val w = 59f * density
            val x = width - 44f * density // margin_right 44dp from screen edge
            val y = min(max(downY, padVPx + w / 2f), height - padVPx - w / 2f)
            canvas.drawCircle(x, y, w / 2f, overlayPaint)
            val ofm = overlayTextPaint.fontMetrics
            canvas.drawText(t, x, y - (ofm.ascent + ofm.descent) / 2, overlayTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                downY = event.y
                val pitch = itemPitch()
                val total = letters.size * pitch
                val usable = height - padVPx * 2
                val step = if (total > usable) usable / letters.size else pitch
                val idx = (((event.y - padVPx) / step).toInt()).coerceIn(0, letters.size - 1)
                val l = letters[idx]
                if (l != currentLetter) {
                    currentLetter = l
                    overlayText = l
                    onLetterSelected?.invoke(l)
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                overlayText = null
                invalidate()
                return true
            }
        }
        return false
    }

    companion object {
        val DEFAULT_LETTERS: List<String> =
            listOf("★") + ('A'..'Z').map { it.toString() } + listOf("#")
    }
}
