package com.repl.bubbledrawer.bubble

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.repl.bubbledrawer.R
import com.repl.bubbledrawer.pinyin.BubbleApp
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 1:1 port of decompiled `windowmode/widget/GestureAppLauncher.java`.
 *
 * Field mapping (obfuscated → here), with original line refs:
 *  f10687A retractListener :29      f10688a pendingRetract :32
 *  f10689b touchSlop :35            f10690c triggerDistance :38 (stored, never read elsewhere)
 *  f10692e adapter :44              f10693f centerY :47  (-1 = hidden)
 *  f10694g radius :50               f10695h/f10696i lastX/lastY :53/56
 *  f10697j debugPaint :59           f10698k cancelFlag :62
 *  f10699l anchorX :65              f10700m hoveredIndex :68 (-1 = none)
 *  f10702o gestureDetector :74      f10703p side :77 (0=left corner, 1=right corner)
 *  f10704q laidOut :80              f10705r hoverDistance :83
 *  f10706s X_JITTER 18px :86        f10707t callback :89
 *  f10708u state :92  0 hidden / 1 expanding / 2 shown / 3 collapsing
 *  f10709v safeDegrees :95          f10710w expandSet / f10711x collapseSet :98/101
 *  f10712y cachedTops / f10713z cachedLefts :104/107
 *
 * Animation math transcribed formula-by-formula from the update listeners
 * (C2946h…C2952n for expand, C2939a…C2944f for collapse); each channel notes
 * its listener + line. Do not "simplify" — the jitter/settle split is what the
 * original ships.
 */
class GestureAppLauncher @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs), GestureDetector.OnGestureListener {

    private var retractListener: AnimatorListenerAdapter? = null // f10687A
    private var pendingRetract = false                           // f10688a
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop // f10689b :942
    private var adapter: SlideAdapter? = null                    // f10692e
    private var centerY = -1f                                    // f10693f
    private var radius = resources.getDimension(R.dimen.slide_gesture_launcher_item_radius).toFloat() // f10694g :935
    private val debugPaint = Paint(1).apply {                    // f10697j :938-941 (unused beyond ctor, kept for parity)
        style = Paint.Style.FILL
        color = -16776961 // 0xFF00FF00
    }
    private var cancelFlag = false                               // f10698k
    private var anchorX = 0f                                     // f10699l
    private var hoveredIndex = -1                                // f10700m
    private val gestureDetector: GestureDetector                 // f10702o :945
    private var side = 1                                         // f10703p :933 ctor default
    private var laidOut = false                                  // f10704q
    private var hoverDistance = 0.0                              // f10705r
    private var callback: Callback? = null                       // f10707t
    private var safeDegrees = 0                                  // f10709v
    private var expandSet: AnimatorSet? = null                   // f10710w
    private var collapseSet: AnimatorSet? = null                 // f10711x
    private var cachedTops = ArrayList<Float>()                  // f10712y
    private var cachedLefts = ArrayList<Float>()                 // f10713z

    var state = 0                                                // f10708u
        private set

    // ---------------- adapter (C2937F "SlideGestureAdapter" analog) ----------------

    sealed class AdapterItem {
        data class AppItem(val app: BubbleApp) : AdapterItem()
        data object More : AdapterItem()
    }

    fun interface ViewFactory { fun create(item: AdapterItem, index: Int): View }

    class SlideAdapter(val items: List<AdapterItem>, private val factory: ViewFactory) {
        val count: Int get() = items.size
        fun getView(position: Int, parent: ViewGroup): View = factory.create(items[position], position)
    }

    /** InterfaceC2955q (:375-383): d=expansion end, e=expansion start, cancel=dismissed. */
    interface Callback {
        fun onExpanded() {}                     // mo9281d
        fun onExpandStarted() {}                // mo9282e
        fun onGestureCanceled(reason: Int) {}   // i6==0 launcher-side, 1 window-side
        fun onItemSelected(item: AdapterItem, view: View, index: Int) // InterfaceC2957s.j :409
    }

    /** C2956r (:386-404): per-child angle + aim radius band. */
    class LayoutParams : ViewGroup.MarginLayoutParams {
        var angle = 0f        // f10730a
        var radiusMin = 0f    // f10731b
        var radiusMax = 0f    // f10732c
        constructor() : super(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ) // generateDefaultLayoutParams :681 = (-2,-2)
        constructor(source: android.view.ViewGroup.LayoutParams) : super(source)
    }

    init {
        setWillNotDraw(false)                       // :937
        gestureDetector = GestureDetector(context, this) // :945
        side = 1                                    // setLayoutDirection(1) :946
    }

    // ---------------- adapter & angles ----------------

    /** setAdapter (:829-845): rebuild children, assign angles. */
    fun setAdapter(a: SlideAdapter?) {
        if (adapter !== a) removeAllViews()
        adapter = a ?: return
        val n = a.count
        for (i in 0 until n) {
            val v = a.getView(i, this)
            addView(v)
            (v.layoutParams as LayoutParams).angle = angleFor(i, n)  // :840
        }
        requestLayout()
        laidOut = false // :843 (f10704q = false)
    }

    /** m9726m (:507-514). */
    private fun angleFor(i: Int, n: Int): Float =
        if (safeDegrees == 0) {
            (90.0f / (n + 1)) * (i + 1f)
        } else {
            val step = (90.0f - safeDegrees * 2f) / n
            i * step + step * 0.5f + safeDegrees
        }

    /** setSafeDegrees (:882-890). */
    fun setSafeDegrees(deg: Int) {
        if (safeDegrees == deg) return
        safeDegrees = deg
        for (i in 0 until childCount) {
            (getChildAt(i).layoutParams as LayoutParams).angle = angleFor(i, childCount)
        }
    }

    fun setRadiusPx(px: Float) { radius = px }                 // setRadius :878-880
    fun setCenterPosition(y: Float) { centerY = y; requestLayout() } // :863-866
    fun setLayoutSide(s: Int) { side = s; requestLayout() }    // setLayoutDirection :868-872
    fun setCallback(cb: Callback?) { callback = cb }           // :892-897
    fun getAdapter(): SlideAdapter? = adapter                  // :689-691

    // ---------------- measure / layout ----------------

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        measureChildren(widthSpec, heightSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec))
        anchorX = if (side == 1) measuredWidth.toFloat() else 0f // :807-811
    }

    /** onLayout (:770-796) — polar placement; auto-expands while state==0. */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (resources.configuration.orientation == 1) {
            centerY = measuredHeight.toFloat() // :771-773
        }
        if (centerY == -1f) return             // :774-776
        val w = measuredWidth
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as LayoutParams
            val rad = Math.toRadians(lp.angle.toDouble())
            val px = if (side == 0) sin(rad) * radius else w - sin(rad) * radius // :782
            val py = centerY - cos(rad) * radius                                   // :783
            val left = (px - child.measuredWidth / 2.0).roundToInt()               // :784-785
            val top = (py - child.measuredHeight / 2.0).roundToInt()
            lp.radiusMin = radius - child.measuredWidth / 2f                       // :788
            lp.radiusMax = radius + child.measuredWidth / 2f + touchSlop            // :789
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight) // :790
        }
        laidOut = true                  // :792
        if (state == 0) playExpand()    // :793-795
    }

    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams()      // :680-682
    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams = LayoutParams()
    override fun generateLayoutParams(p: android.view.ViewGroup.LayoutParams) = LayoutParams(p) // :684-687
    override fun checkLayoutParams(p: android.view.ViewGroup.LayoutParams) = p is LayoutParams

    override fun dispatchDraw(canvas: Canvas) {   // :672-677 — nothing paints while state==0
        if (state == 0) return
        super.dispatchDraw(canvas)
    }

    // ---------------- expand (m9714B :460-504) ----------------

    private fun playExpand() {
        cachedTops = ArrayList()   // :461 fresh lists each start — the jitter channels
        cachedLefts = ArrayList()  // :462 run BEFORE they fill, hence no-op reads guarded below
        if (centerY == -1f) alpha = 0f   // cold start from hidden: original window begins at
                                         // default alpha 1 and its expand anim drives 0→1;
                                         // after our collapse leaves 0, reset the same way
        var set = expandSet
        if (set == null) {
            val j: Long = MotionSpec.EXPAND_PHASE1 // j6 = 130 (:467)

            // #1 C2946h :225-232 — container alpha 0→1
            val alpha = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = j; interpolator = MotionSpec.EXPAND_ALPHA
                addUpdateListener { this@GestureAppLauncher.alpha = it.animatedValue as Float }
            }
            // #2 C2947i :236-245 — ALL children rotation -270→5
            val rotSweep = ValueAnimator.ofFloat(MotionSpec.ROT_FROM_HIDDEN, MotionSpec.ROT_SETTLE).apply {
                duration = j; interpolator = MotionSpec.EXPAND_ROT_SWEEP
                addUpdateListener { a -> forEachChild { it.rotation = a.animatedValue as Float } }
            }
            // #3 C2948j :249-263 — ALL children (except hovered) scale 0.8→1.05
            val scaleIn = ValueAnimator.ofFloat(MotionSpec.SCALE_HIDDEN, MotionSpec.SCALE_OVERSHOOT).apply {
                duration = j; interpolator = MotionSpec.EXPAND_SCALE_IN
                addUpdateListener { a -> forEachChildSkipHovered { it.scaleX = a.animatedValue as Float; it.scaleY = it.scaleX } }
            }
            // #4 C2949k :266-295 — fly from REST POS (0,0 translation) to arc; caches tops/lefts as it goes
            val flyOut = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = j; interpolator = MotionSpec.EXPAND_FLY_OUT
                addUpdateListener { a ->
                    val p = a.animatedValue as Float
                    forEachChildIndexed { idx, c ->
                        val targetX = anchorX - c.measuredWidth * 0.5f       // :275 "measuredWidth"
                        val targetY = centerY - c.measuredHeight * 0.5f      // :276 "measuredHeight"
                        if (c.top != 0) {                                     // :277-283 cache current top
                            if (idx < cachedTops.size) cachedTops[idx] = c.top.toFloat()
                            else cachedTops.add(idx, c.top.toFloat())
                        }
                        val ty = cachedTops.getOrElse(idx) { c.top.toFloat() }
                        val curY = targetY + (ty - targetY) * p               // :284
                        if (c.left != 0) {                                    // :285-291 cache left
                            if (idx < cachedLefts.size) cachedLefts[idx] = c.left.toFloat()
                            else cachedLefts.add(idx, c.left.toFloat())
                        }
                        val tx = cachedLefts.getOrElse(idx) { c.left.toFloat() }
                        c.translationY = curY - ty                            // :293 wait: :284→ :293 uses cached
                        c.translationX = targetX + (((if (side == 1) tx - MotionSpec.X_JITTER_PX else tx + MotionSpec.X_JITTER_PX) - targetX) * p) - tx // :292
                    }
                }
            }
            // #5 C2950l :299-308 — rotation 5→0, 250ms delay 130
            val rotSettle = ValueAnimator.ofFloat(MotionSpec.ROT_SETTLE, 0f).apply {
                duration = MotionSpec.EXPAND_PHASE2; startDelay = j; interpolator = MotionSpec.EXPAND_ROT_SETTLE
                addUpdateListener { a -> forEachChild { it.rotation = a.animatedValue as Float } }
            }
            // #6 C2951m :312-325 — scale 1.05→1.0 (skip hovered), 250ms delay 130
            val scaleSettle = ValueAnimator.ofFloat(MotionSpec.SCALE_OVERSHOOT, 1f).apply {
                duration = MotionSpec.EXPAND_PHASE2; startDelay = j; interpolator = MotionSpec.EXPAND_SCALE_SETTLE
                addUpdateListener { a -> forEachChildSkipHovered { it.scaleX = a.animatedValue as Float; it.scaleY = it.scaleX } }
            }
            // #7 C2952n :329-342 — x: from (left∓18) → left, 250ms delay 130 (settles #4's leftover jitter)
            val xSettle = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = MotionSpec.EXPAND_PHASE2; startDelay = j; interpolator = MotionSpec.EXPAND_X_SETTLE
                addUpdateListener { a ->
                    val p = a.animatedValue as Float
                    forEachChild { c ->
                        val from = if (side == 1) c.left - MotionSpec.X_JITTER_PX else c.left + MotionSpec.X_JITTER_PX // :339
                        c.translationX = (from + ((c.left - from) * p)) - c.left // :340
                    }
                }
            }
            set = AnimatorSet().apply {                     // :497-498
                playTogether(alpha, rotSweep, scaleIn, flyOut, rotSettle, scaleSettle, xSettle)
                addListener(object : AnimatorListenerAdapter() { // C2953o :346-361
                    override fun onAnimationStart(animation: Animator) = expandStarted()
                    override fun onAnimationEnd(animation: Animator) = expandFinished()
                })
            }
            expandSet = set
        }
        if (set.isRunning) set.cancel()                     // :500-502
        set.start()                                          // :503
    }

    private fun forEachChild(block: (View) -> Unit) {
        for (i in 0 until childCount) block(getChildAt(i))
    }

    private fun forEachChildSkipHovered(block: (View) -> Unit) {
        for (i in 0 until childCount) if (i != hoveredIndex) block(getChildAt(i))
    }

    private fun forEachChildIndexed(block: (Int, View) -> Unit) {
        for (i in 0 until childCount) block(i, getChildAt(i))
    }

    /** m9734v (:633-640). */
    private fun expandStarted() {
        callback?.onExpandStarted()
        state = 1
    }

    /** m9733u (:619-629). */
    private fun expandFinished() {
        if (pendingRetract) playCollapse()
        callback?.onExpanded()
        state = 2
    }

    // ---------------- collapse (m9713A :417-457), all j=100; #5-7 delay 100 ----------------

    private fun playCollapse() {
        pendingRetract = false                              // :418
        var set = collapseSet
        if (set == null) {
            val j: Long = MotionSpec.COLLAPSE_PHASE1        // j6 = 100 (:423)

            // #1 C2954p :364-371 alpha 1→0
            val alpha = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = j; interpolator = MotionSpec.COLLAPSE_ALPHA
                addUpdateListener { this@GestureAppLauncher.alpha = it.animatedValue as Float }
            }
            // #2 C2939a :110-119 rotation 0→5
            val rotUp = ValueAnimator.ofFloat(0f, MotionSpec.ROT_SETTLE).apply {
                duration = j; interpolator = MotionSpec.COLLAPSE_ROT_UP
                addUpdateListener { a -> forEachChild { it.rotation = a.animatedValue as Float } }
            }
            // #3 C2940b :123-136 scale 1→1.05 (skip hovered)
            val scaleUp = ValueAnimator.ofFloat(1f, MotionSpec.SCALE_OVERSHOOT).apply {
                duration = j; interpolator = MotionSpec.COLLAPSE_SCALE_UP
                addUpdateListener { a -> forEachChildSkipHovered { it.scaleX = a.animatedValue as Float; it.scaleY = it.scaleX } }
            }
            // #4 C2941c :140-152 x: translationX → (left∓18 - left): push sideways 18px
            val xNudge = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = j; interpolator = MotionSpec.COLLAPSE_X_NUDGE
                addUpdateListener { a ->
                    val p = a.animatedValue as Float
                    forEachChild { c ->
                        val left = c.left.toFloat()
                        val to = if (side == 1) c.left - MotionSpec.X_JITTER_PX else c.left + MotionSpec.X_JITTER_PX // :150
                        c.translationX = (left + ((to - left) * p)) - left  // :150
                    }
                }
            }
            // #5 C2942d :156-165 rotation 5→-270 (dur100 delay100 :440-441)
            val rotOut = ValueAnimator.ofFloat(MotionSpec.ROT_SETTLE, MotionSpec.ROT_FROM_HIDDEN).apply {
                duration = MotionSpec.COLLAPSE_PHASE2; startDelay = j; interpolator = MotionSpec.COLLAPSE_ROT_OUT
                addUpdateListener { a -> forEachChild { it.rotation = a.animatedValue as Float } }
            }
            // #6 C2943e :169-182 (skip hovered) scale 1.05→0.8 (dur100 delay100 :445-446)
            val scaleDown = ValueAnimator.ofFloat(MotionSpec.SCALE_OVERSHOOT, MotionSpec.SCALE_HIDDEN).apply {
                duration = MotionSpec.COLLAPSE_PHASE2; startDelay = j; interpolator = MotionSpec.COLLAPSE_SCALE_DOWN
                addUpdateListener { a -> forEachChildSkipHovered { it.scaleX = a.animatedValue as Float; it.scaleY = it.scaleX } }
            }
            // #7 C2944f :186-203 arc (rest pos) → anchor corner: translation → anchor-left/top (dur100 delay100)
            val flyIn = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = MotionSpec.COLLAPSE_PHASE2; startDelay = j; interpolator = MotionSpec.COLLAPSE_FLY_IN
                addUpdateListener { a ->
                    val p = a.animatedValue as Float
                    forEachChild { c ->
                        val targetX = anchorX - c.measuredWidth * 0.5f   // :195 "measuredWidth"
                        val targetY = centerY - c.measuredHeight * 0.5f  // :196 "measuredHeight"
                        val fromY = c.top.toFloat()                       // :197
                        c.translationY = (fromY + ((targetY - fromY) * p)) - c.top // :201
                        val fromX = if (side == 1) c.left - MotionSpec.X_JITTER_PX else c.left + MotionSpec.X_JITTER_PX // :199
                        c.translationX = (fromX + ((targetX - fromX) * p)) - c.left // :200
                    }
                }
            }
            set = AnimatorSet().apply {                     // :453-454
                playTogether(alpha, rotUp, scaleUp, xNudge, rotOut, scaleDown, flyIn)
                addListener(object : AnimatorListenerAdapter() { // C2945g :207-222
                    override fun onAnimationStart(animation: Animator) = collapseStarted()
                    override fun onAnimationEnd(animation: Animator) = collapseFinished(animation)
                })
            }
            collapseSet = set
        }
        set.start()                                          // :456
    }

    /** m9732t (:609-615). */
    private fun collapseStarted() {
        retractListener?.onAnimationStart(ValueAnimator())
        state = 3
    }

    /** m9731s (:592-605). */
    private fun collapseFinished(animation: Animator) {
        retractListener?.onAnimationEnd(animation)
        retractListener = null
        centerY = -1f
        alpha = 0f          // collapse #1 ended at 0 (original leaves container alpha 0; next expand starts 0→1)
        state = 0
        pendingRetract = false
        hoveredIndex = -1
    }

    /** m9738l (:703-727) public retract. */
    fun retract(listener: AnimatorListenerAdapter?) {
        if (state == 0) {
            if (listener != null) {
                val va = ValueAnimator()
                listener.onAnimationStart(va)
                listener.onAnimationEnd(va)
            }
            return
        }
        retractListener = listener
        when (state) {
            1 -> pendingRetract = true
            3 -> { /* ignore while collapsing :718-720 */ }
            else -> {
                if (hoveredIndex != -1) unhover(hoveredIndex)  // :721-724
                playCollapse()                                  // :725
            }
        }
    }

    // ---------------- touch forwarding (m9740w :893-927) ----------------

    fun forwardEvent(ev: MotionEvent, pointerId: Int): Boolean {
        val action = ev.actionMasked
        val idx = ev.findPointerIndex(pointerId)
        if (idx < 0) return false                              // :896-898
        val x = ev.getX(idx)
        val y = ev.getY(idx)
        if (x < 0f || y < 0f) return action != MotionEvent.ACTION_DOWN // :901-904
        gestureDetector.onTouchEvent(ev)                       // :905
        when (action) {
            MotionEvent.ACTION_DOWN -> {                       // :906-909
                cancelFlag = false
                return true
            }
            MotionEvent.ACTION_UP -> return onActionUp()       // :911-912 → m9729p
            MotionEvent.ACTION_MOVE -> {                       // :914-922
                invalidate()
                hoverDistance = distanceOf(x, y)
                lastY = y
                lastX = x
                if (centerY != -1f) aimAt(x, y)
            }
            MotionEvent.ACTION_CANCEL -> return false          // :923-925
        }
        return true
    }

    private var lastX = 0f   // f10695h
    private var lastY = 0f   // f10696i

    /** m9728o (:521-528). */
    private fun distanceOf(x: Float, y: Float): Double {
        val dx = if (side == 1) measuredWidth - x else x
        val dy = abs(y - centerY)
        return sqrt((dx * dx).toDouble() + (dy * dy).toDouble())
    }

    /** m9727n (:517-519); NaN when dist<1 (centerY-y)/d — original divides blindly, we guard to -1 (no hit). */
    private fun angleOf(x: Float, y: Float): Double {
        if (hoverDistance < 1.0) return -1.0
        return Math.toDegrees(acos(((centerY - y) / hoverDistance).coerceIn(-1.0, 1.0)))
    }

    /** m9730q (:559-590). */
    private fun aimAt(x: Float, y: Float): Boolean {
        if (!laidOut || centerY == -1f) return false
        val angle = angleOf(x, y)
        val dist = hoverDistance
        val n = childCount
        if (n == 0) return false
        val half = 90.0f / n * 0.5f
        var hit = -1
        for (i in 0 until n) {
            val lp = getChildAt(i).layoutParams as LayoutParams
            if (angle >= lp.angle - half && angle < lp.angle + half && dist >= lp.radiusMin && dist <= lp.radiusMax) {
                hit = i
                break
            }
        }
        if (hoveredIndex != hit) {           // :580-584
            unhover(hoveredIndex)
            hover(hit)
        }
        return hit != -1                      // :585-587
    }

    /** m9735x (:643-651). */
    private fun hover(index: Int) {
        if (index == -1) { hoveredIndex = -1; return }
        val child = getChildAt(index) ?: return
        child.isPressed = true
        hoveredIndex = index
        // original: AbstractC2797j.m9042a(child) flyme haptic → public API equivalent
        child.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** m9737z (:664-669). */
    private fun unhover(index: Int) {
        if (index == -1) return
        getChildAt(index)?.isPressed = false
    }

    /** m9729p (:531-556). */
    private fun onActionUp(): Boolean {
        if (centerY == -1f) cancelFlag = true                  // :533-535
        val idx = hoveredIndex
        if (idx != -1 && getChildAt(idx) != null) {            // :536-543
            val lp = getChildAt(idx).layoutParams as LayoutParams
            if (hoverDistance <= lp.radiusMax && hoverDistance >= lp.radiusMin) {
                cancelFlag = false
            }
        }
        if (cancelFlag) {                                      // :544-550
            callback?.onGestureCanceled(0)
            return false
        }
        if (idx == -1) return true                             // :551-553
        selectHovered()                                        // :554 m9736y
        return false
    }

    /** m9736y (:653-661). */
    private fun selectHovered() {
        val idx = hoveredIndex
        if (idx == -1) return
        val item = adapter?.items?.getOrNull(idx) ?: return
        callback?.onItemSelected(item, getChildAt(idx), 0)   // reason 0 = drag-release select
        // original stats bubble_click (AbstractC2811x) — no-op here
    }

    /** C2937F$a.onClick :56-69 → AppLauncherWindow.mo9283j(item, view, 1):
     *  plain TAP on an open tile launches it (reason 1 = click), no drag involved. */
    fun clickSelect(item: AdapterItem, view: View) {
        callback?.onItemSelected(item, view, 1)
    }

    // ---------------- GestureDetector (:729-838) ----------------

    override fun onDown(e: MotionEvent) = false                // :730-732
    override fun onShowPress(e: MotionEvent) {}                // :831-833
    override fun onSingleTapUp(e: MotionEvent) = false         // :835-838
    override fun onLongPress(e: MotionEvent) {}                // :799-800

    /** onScroll (:815-826): drifting back to the edge (vx sign vs side) sets cancel. */
    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
        if (hoveredIndex == -1) {
            cancelFlag = if (side == 1) dx < -MotionSpec.SCROLL_VX_CANCEL else dx > MotionSpec.SCROLL_VX_CANCEL
        }
        return false
    }

    /** onFling (:739-767): a FAST FLICK TOWARD THE CORNER EDGE dismisses the fan
     *  (sets cancelFlag → UP delivers onGestureCanceled). It is NOT a launch path. */
    override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
        if (hoveredIndex == -1) {
            val angle = angleOf(e2.x, e2.y)   // m9727n uses the stored hoverDistance, like the original
            if (angle < 0.0) { cancelFlag = false; return false }
            val speed = sqrt((vx * vx + vy * vy).toDouble()).toFloat() // :744
            if (speed > MotionSpec.FLING_SPEED_MIN) {
                var fire = true                                          // :750
                if (side == 1) {
                    if ((angle >= 45.0 || vy <= 1000f) &&
                        (angle < 45.0 || angle > 135.0 || vx <= 1000f) &&
                        (angle <= 135.0 || vy <= 1000f)
                    ) fire = false                                       // :752-754
                    cancelFlag = fire                                    // :755
                } else {
                    if ((angle >= 45.0 || vy <= 1000f) &&
                        (angle < 45.0 || angle > 135.0 || vx >= -1000f) &&
                        (angle <= 135.0 || vy <= 1000f)
                    ) fire = false                                       // :757-759
                    cancelFlag = fire                                    // :760
                }
            } else {
                cancelFlag = false                                       // :763
            }
        }
        return false
    }
}
