package com.repl.bubbledrawer.bubble

import android.view.animation.PathInterpolator

/**
 * Motion constants copied verbatim from decompiled `GestureAppLauncher`
 * (expand `m9714B` :460-504, collapse `m9713A` :417-457) and
 * `SlideGestureItemView` (ring :163-199, interpolator :25).
 *
 * The original animates ALL children per channel with a single AnimatorSet —
 * no per-child startDelay. Keep it that way; the "one by one" feel comes from
 * polar layout + shared rotation sweep.
 */
object MotionSpec {

    fun pi(x1: Float, y1: Float, x2: Float, y2: Float) = PathInterpolator(x1, y1, x2, y2)

    // ---- expand (GestureAppLauncher.m9714B, :460-504), j6 = 130 ----
    const val EXPAND_PHASE1 = 130L
    const val EXPAND_PHASE2 = 250L
    const val EXPAND_PHASE2_DELAY = 130L

    val EXPAND_ALPHA = pi(0.33f, 0.0f, 0.67f, 1.0f)      // :466 container alpha 0→1
    val EXPAND_ROT_SWEEP = pi(0.24f, 0.17f, 0.53f, 0.82f) // :471 children rotation -270→5
    val EXPAND_SCALE_IN = pi(0.24f, 0.74f, 0.53f, 0.92f)  // :477 children scale .8→1.05 (skip aimed)
    val EXPAND_FLY_OUT = pi(0.24f, 0.55f, 0.53f, 0.8f)    // :481 anchor→arc position 0→1
    val EXPAND_ROT_SETTLE = pi(0.19f, -7.6f, 0.48f, 1.0f) // :486 rotation 5→0 (elastic)
    val EXPAND_SCALE_SETTLE = pi(0.19f, 0.31f, 0.48f, 1.0f) // :491 scale 1.05→1.0
    val EXPAND_X_SETTLE = pi(0.19f, 1.23f, 0.67f, 1.0f)   // :496 x jitter →0

    // ---- collapse (m9713A, :417-457): ALL seven channels duration = 100 (j6),
    //      the last three also carry startDelay = 100 ----
    const val COLLAPSE_PHASE1 = 100L
    const val COLLAPSE_PHASE2 = 100L
    const val COLLAPSE_PHASE2_DELAY = 100L

    val COLLAPSE_ALPHA = pi(0.33f, 0.0f, 0.67f, 1.0f)     // :425 alpha 1→0
    val COLLAPSE_ROT_UP = pi(0.17f, 0.0f, 0.53f, -6.56f)  // :429 rotation 0→5 (elastic dip)
    val COLLAPSE_SCALE_UP = pi(0.17f, 0.0f, 0.53f, 0.7f)  // :433 scale 1→1.05
    val COLLAPSE_X_NUDGE = pi(0.33f, 0.0f, 0.53f, -0.22f) // :437 x 0→∓18
    val COLLAPSE_ROT_OUT = pi(0.19f, -0.06f, 0.32f, 1.0f) // :441 rotation 5→-270
    val COLLAPSE_SCALE_DOWN = pi(0.19f, 0.0f, 0.32f, 1.0f) // :445 scale 1.05→.8
    val COLLAPSE_FLY_IN = pi(0.19f, 0.06f, 0.32f, 1.0f)   // :451 arc→anchor

    const val ROT_FROM_HIDDEN = -270f                     // original -270↔5 sweep endpoints
    const val ROT_SETTLE = 5f
    const val SCALE_HIDDEN = 0.8f
    const val SCALE_OVERSHOOT = 1.05f
    const val X_JITTER_PX = 18f                           // f10706s = 18 (px, not dp) :936

    // ---- aiming ring (SlideGestureItemView) ----
    val RING_INTERPOLATOR = pi(0.33f, 0.0f, 0.66f, 1.0f)  // f10833m :25
    const val RING_DURATION = 130L                        // :174 / :192

    // ---- fling-to-launch (onFling :739-767) ----
    const val FLING_SPEED_MIN = 2500f
    const val FLING_ANGLE_LO = 45.0
    const val FLING_ANGLE_HI = 135.0
    const val FLING_VEL_LIMIT = 1000f                     // ±1000 px/s quadrant gates

    // ---- retreat-cancel (onScroll :815-826) ----
    const val SCROLL_VX_CANCEL = 5f

    // ---- aim bands (m9730q :559-590, onLayout :788-789) ----
    const val SECTOR_DEGREES = 90.0f
    const val EXTRA_DEGREES = 3                           // setSafeDegrees(3) portrait :502

    // radius dimens are resources: slide_gesture_launcher_item_radius 277dp
    // (no-nav 242dp), applied per launch via setRadius (:878).
}
