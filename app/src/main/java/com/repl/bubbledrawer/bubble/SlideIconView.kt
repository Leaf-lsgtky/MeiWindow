package com.repl.bubbledrawer.bubble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.ImageView
import kotlin.math.max

/**
 * The fan's app icon — the replacement for `de.hdodenhof.circleimageview.CircleImageView` (which is
 * what the decompiled Flyme tile uses), with the shape made configurable:
 *
 *  - [SHAPE_CIRCLE] (default) — byte-for-byte the CircleImageView look: the icon is scaled
 *    center-crop into the disc, which is what the fan has always shown.
 *  - [SHAPE_ROUNDED_RECT] — the same drawing, but the clip is a rounded square with the platform's
 *    icon corner ratio, so a tile looks like the launcher's icon instead of a circle cut out of it.
 *    Icons that the ROM hands us already masked (adaptive icons) are unaffected by the extra clip.
 *
 * Drawing is shader-based exactly like CircleImageView (`BitmapShader` in a [Paint] over a [Path]),
 * so both shapes are anti-aliased and a non-square icon is cropped rather than squashed.
 */
class SlideIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ImageView(context, attrs, defStyleAttr) {

    companion object {
        const val SHAPE_CIRCLE = 0
        const val SHAPE_ROUNDED_RECT = 1

        /**
         * Corner radius of the rounded-square shape, as a fraction of the icon size — the same
         * order as the platform's icon mask (HyperOS draws launcher icons as squircles of roughly
         * this radius), so the fan tile reads as "系统样式" rather than as a circle.
         */
        const val CORNER_RATIO = 0.225f
    }

    /** [SHAPE_CIRCLE] or [SHAPE_ROUNDED_RECT]. */
    var shape: Int = SHAPE_CIRCLE
        set(value) {
            val next = if (value == SHAPE_ROUNDED_RECT) SHAPE_ROUNDED_RECT else SHAPE_CIRCLE
            if (field != next) {
                field = next
                invalidate()
            }
        }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val shaderMatrix = Matrix()
    private val clipPath = Path()
    private val dst = RectF()

    private var raster: Bitmap? = null
    private var shader: BitmapShader? = null
    private var rasterDirty = true

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        rasterDirty = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // A drawable without an intrinsic size is rasterised at the view's size.
        rasterDirty = true
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = ensureRaster() ?: run {
            super.onDraw(canvas)
            return
        }
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return

        dst.set(
            paddingLeft.toFloat(),
            paddingTop.toFloat(),
            (paddingLeft + w).toFloat(),
            (paddingTop + h).toFloat(),
        )
        if (!updateShaderMatrix(bitmap, dst)) return

        clipPath.reset()
        if (shape == SHAPE_ROUNDED_RECT) {
            val radius = minOf(w, h) * CORNER_RATIO
            clipPath.addRoundRect(dst, radius, radius, Path.Direction.CW)
        } else {
            clipPath.addOval(dst, Path.Direction.CW)
        }
        canvas.drawPath(clipPath, iconPaint)
    }

    /** Center-crop [bitmap] into [target] (CircleImageView's `updateShaderMatrix` rule). */
    private fun updateShaderMatrix(bitmap: Bitmap, target: RectF): Boolean {
        val shader = this.shader ?: return false
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        if (bw <= 0f || bh <= 0f) return false
        val scale = max(target.width() / bw, target.height() / bh)
        val dx = target.left + (target.width() - bw * scale) * 0.5f
        val dy = target.top + (target.height() - bh * scale) * 0.5f
        shaderMatrix.setScale(scale, scale)
        shaderMatrix.postTranslate(dx, dy)
        shader.setLocalMatrix(shaderMatrix)
        return true
    }

    private fun ensureRaster(): Bitmap? {
        if (!rasterDirty) return raster
        rasterDirty = false
        val drawable = drawable
        if (drawable == null) {
            raster = null
            shader = null
            iconPaint.shader = null
            return null
        }
        val bitmap = toBitmap(drawable)
        raster = bitmap
        shader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        iconPaint.shader = shader
        return bitmap
    }

    private fun toBitmap(drawable: Drawable): Bitmap? {
        // A `BitmapDrawable` is copied 1:1 instead of referenced: app icons come from the ROM's icon
        // cache (`AppRepository`), and HyperOS does recycle icon/artwork bitmaps on its own schedule —
        // drawing one of those throws `Canvas: trying to use a recycled bitmap`. The copy keeps the
        // pixels (and the aspect ratio the shader center-crops later) in a bitmap this view owns.
        (drawable as? BitmapDrawable)?.bitmap?.let { source ->
            if (source.isRecycled) return null
            return runCatching {
                val copy = Bitmap.createBitmap(
                    source.width,
                    source.height,
                    source.config ?: Bitmap.Config.ARGB_8888,
                )
                Canvas(copy).drawBitmap(source, 0f, 0f, null)
                copy
            }.getOrNull()
        }
        var w = drawable.intrinsicWidth
        var h = drawable.intrinsicHeight
        if (w <= 0) w = width
        if (h <= 0) h = height
        if (w <= 0 || h <= 0) return null
        return runCatching {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            bitmap
        }.getOrNull()
    }
}
