package com.repl.bubbledrawer.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小窗的大小与落点：Flyme 样式的大小，但**不许超出屏幕**，并且**居中**。
 *
 * Background (see `hookFreeformScale` / `centerRectOnScreen` and `MiuiMultiWindowUtils`):
 *
 *  - the ROM scales its own *unscaled* task bounds by one factor; portrait bounds are
 *    `shortSide * ratio` wide, landscape bounds are `shortSide * ratio * aspect` wide — on this
 *    phone (1220x2656) that is ~2.2x wider on the same short side;
 *  - `reviewFreeFormBounds` shrinks a scale until the visual rect fits the freeform accessible
 *    area, and that reviewed value is what the ROM returns next to our preferred one;
 *  - the ROM only ever centers a *portrait* 小窗 (`getLeftMargin`) and pins a landscape one to the
 *    top of the screen (`getTopMargin` returns 0 while the display itself is portrait), which is why
 *    the module does the centering — with the fitted scale, not the raw setting.
 */
class FreeformPlacementTest {

    private companion object {
        /** Target device: 1220x2656 @ 520dpi. */
        const val DISPLAY_W = 1220
        const val DISPLAY_H = 2656

        /** `PHONE_FREEFORM_ACCESSIBLE_AREA_SIDE_MARGIN` = 6dp ≈ 20px, applied on both sides. */
        const val ACCESSIBLE_W = DISPLAY_W - 2 * 20

        /** A landscape window's aspect on this display (`getAspectRatio(landscape = true)`). */
        const val ASPECT = DISPLAY_H.toFloat() / DISPLAY_W

        /** The ROM's `original_ratio` (`MiuiFreeformLaunchConfig` ARGS_ORIGINAL_RATIO). */
        const val RATIO = 0.85f

        /** `FREEFORM_SIDE_MARGIN_DP` in pixels at this display's density (6dp x 3.25). */
        const val MARGIN = 20
    }

    /** Landscape task bounds: `shortSide * ratio` tall, `aspect` times wider than that. */
    private val landscapeW = DISPLAY_W * RATIO * ASPECT
    private val landscapeH = DISPLAY_W * RATIO

    /** Portrait task bounds: `shortSide * ratio` wide, phone-shaped. */
    private val portraitW = DISPLAY_W * RATIO
    private val portraitH = portraitW * DISPLAY_H / DISPLAY_W

    @Test
    fun portraitKeepsTheSettingWhenTheRomAgrees() {
        // Portrait: 1037 x 2257 unscaled, visual 830 x 1806 at 80% — comfortably inside the area,
        // so the ROM's review leaves the preferred scale untouched.
        assertEquals(0.80f, fitFreeformScale(0.80f, 0.80f), 1e-6f)
    }

    @Test
    fun landscapeIsClampedToWhatFitsOnScreen() {
        val wouldBeW = landscapeW * 0.80f

        // The old behaviour: the setting verbatim, i.e. a window wider than the phone (the report).
        assertTrue("landscape at 80% must overflow the display", wouldBeW > DISPLAY_W)

        // What the ROM answers for that same window: its own review of our preferred scale.
        val romFit = 0.80f * ACCESSIBLE_W / wouldBeW
        val used = fitFreeformScale(0.80f, romFit)

        assertEquals(romFit, used, 1e-6f)
        assertTrue("clamped window fits the accessible area", landscapeW * used <= ACCESSIBLE_W + 0.5f)
    }

    @Test
    fun theSettingWinsWhenItIsTheSmallerOne() {
        // A user-chosen 60% stays 60% even if the ROM would have allowed more.
        assertEquals(0.60f, fitFreeformScale(0.60f, 0.95f), 1e-6f)
    }

    @Test
    fun unusableRomAnswersFallBackToTheSetting() {
        // `getFreeformArg` returns 0 when the resolution config cannot be read; a missing method
        // answers null. Both must keep the previous behaviour (the setting verbatim).
        assertEquals(0.80f, fitFreeformScale(0.80f, null), 1e-6f)
        assertEquals(0.80f, fitFreeformScale(0.80f, 0f), 1e-6f)
        assertEquals(0.80f, fitFreeformScale(0.80f, -0.5f), 1e-6f)
        assertEquals(0.80f, fitFreeformScale(0.80f, Float.NaN), 1e-6f)
        assertEquals(0.80f, fitFreeformScale(0.80f, Float.POSITIVE_INFINITY), 1e-6f)
    }

    @Test
    fun aDirectlyOpenedLandscapeWindowIsCenteredAndFullyOnScreen() {
        // `reviewFreeFormBounds` with the 80% setting: the scale that fills the accessible width.
        val romFit = ACCESSIBLE_W / landscapeW
        val scale = fitFreeformScale(0.80f, romFit)
        val visualW = landscapeW * scale
        val visualH = landscapeH * scale

        val (x, y) = centeredOrigin(visualW, visualH, DISPLAY_W, DISPLAY_H)

        assertTrue("fully on screen horizontally", x >= 0 && x + visualW <= DISPLAY_W + 0.5f)
        assertTrue("fully on screen vertically", y >= 0 && y + visualH <= DISPLAY_H + 0.5f)
        assertEquals("horizontally centered", (DISPLAY_W - visualW) / 2f, x.toFloat(), 1f)
        assertEquals("vertically centered", (DISPLAY_H - visualH) / 2f, y.toFloat(), 1f)
        // The ROM's own placement puts it at the top (getTopMargin -> 0); this is what replaces it.
        assertTrue("not pinned to the top", y > DISPLAY_H / 4)
    }

    @Test
    fun centeringWithTheRawSettingWouldHangTheLandscapeWindowOffBothEdges() {
        // The reason the centering needs the *fitted* scale: 80% of a landscape window is wider than
        // the phone, and a scaled window is positioned by its unscaled origin.
        val visualW = landscapeW * 0.80f
        val (x, _) = centeredOrigin(visualW, landscapeH * 0.80f, DISPLAY_W, DISPLAY_H)

        assertTrue("left edge off screen", x < 0)
        assertTrue("right edge off screen", x + visualW > DISPLAY_W)
    }

    @Test
    fun aPortraitWindowKeepsThePositionItHadBefore() {
        // Portrait never hits the cap (`review` returns the setting), so the module's centering is
        // the arithmetic it always was: 830 x 1806 window on a 1220 x 2656 screen.
        val scale = fitFreeformScale(0.80f, 0.80f)
        val (x, y) = centeredOrigin(portraitW * scale, portraitH * scale, DISPLAY_W, DISPLAY_H)

        assertEquals(Math.round((DISPLAY_W - portraitW * 0.80f) / 2f), x)
        assertEquals(Math.round((DISPLAY_H - portraitH * 0.80f) / 2f), y)
        assertTrue(x > 0 && y > 0)
    }

    // ---------------------------------------------------------------------------------------------
    // Which scale the centring may believe (小窗居中 on a ROM whose size does not follow the setting)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun theScaleTheRomSaidItWillApplyWins() {
        // 迷你 → 普通 restore: `getRestoredBounds` hands back the scale it puts into the anim target,
        // so it is *the* size of the restored window — even when it is not the setting.
        assertEquals(0.70f, centerScale(applied = 0.70f, assumed = 0.80f, setting = 0.80f), 1e-6f)
        // A window launched after the module's scale hook answered: same thing.
        assertEquals(0.66f, centerScale(applied = 0.66f, assumed = null, setting = 0.80f), 1e-6f)
    }

    @Test
    fun withoutARomAnswerTheReviewedSettingIsUsedAsBefore() {
        assertEquals(0.80f, centerScale(applied = null, assumed = 0.80f, setting = 0.80f), 1e-6f)
        // 横屏 inside the window: the review shrank the setting, and that is what was used before.
        assertEquals(0.36f, centerScale(applied = null, assumed = 0.36f, setting = 0.80f), 1e-6f)
    }

    @Test
    fun unusableRomAnswersFallBackInsteadOfPoisoningTheMath() {
        // `getFreeformArg` answers 0 when its resolution table cannot be read, and a scale above 1
        // would mean a window larger than its own unscaled bounds. Neither may decide the placement.
        for (bad in listOf(null, 0f, -0.5f, Float.NaN, Float.POSITIVE_INFINITY, 1.6f)) {
            assertTrue("$bad must not be usable", !bad.isUsableScale())
            assertEquals(0.80f, centerScale(applied = bad, assumed = 0.80f, setting = 0.80f), 1e-6f)
        }
        // Nothing usable at all: the raw setting is still better than no scale.
        assertEquals(0.80f, centerScale(applied = null, assumed = null, setting = 0.80f), 1e-6f)
        assertTrue(0.8f.isUsableScale())
    }

    @Test
    fun aWrongScaleIsWhatPushesACentredWindowOffTheMiddle() {
        // The failure this ordering exists for: the ROM sizes the window at 65% while the module
        // centred it as if it were 80%. The top-left origin is the same, so the *window* ends up
        // (0.80 - 0.65) / 2 of its unscaled bounds up and to the left of the middle.
        val applied = 0.65f
        val assumed = 0.80f
        val (x, y) = centeredOrigin(portraitW * assumed, portraitH * assumed, DISPLAY_W, DISPLAY_H)
        val offX = (x + portraitW * applied / 2f) - DISPLAY_W / 2f
        val offY = (y + portraitH * applied / 2f) - DISPLAY_H / 2f

        assertTrue("visibly off centre horizontally", offX < -60f)
        assertTrue("visibly off centre vertically", offY < -130f)

        // With the ROM's own scale the window is exactly in the middle.
        val good = centerScale(applied, assumed, 0.80f)
        val (gx, gy) = centeredOrigin(portraitW * good, portraitH * good, DISPLAY_W, DISPLAY_H)
        assertEquals(DISPLAY_W / 2f, gx + portraitW * good / 2f, 1f)
        assertEquals(DISPLAY_H / 2f, gy + portraitH * good / 2f, 1f)
    }

    // ---------------------------------------------------------------------------------------------
    // 横屏落点跟触发侧 (2026-10-11: "不管在左侧触发还是右侧触发，小窗都会显示在左侧")
    // ---------------------------------------------------------------------------------------------

    /** 横屏的屏与窗：2656 x 1220 的屏，小窗可见宽 = 1220 x 0.7。 */
    private val landScreenW = DISPLAY_H
    private val landScreenH = DISPLAY_W
    private val landVisualW = Math.round(DISPLAY_W * 0.70f)

    @Test
    fun theTwoCornersLandOnTheTwoSidesInsteadOfTheSameSpot() {
        val left = triggerSideLeft(anchorX = 0, visualW = landVisualW, screenW = landScreenW, marginPx = MARGIN)
        val right = triggerSideLeft(anchorX = landScreenW, visualW = landVisualW, screenW = landScreenW, marginPx = MARGIN)

        // The report was exactly this: two different triggers, one identical place.
        assertTrue("the two sides must differ", right - left > landScreenW / 2)
        assertEquals("left corner hugs the left edge", MARGIN, left)
        assertEquals("right corner is flush with the right edge", landScreenW - landVisualW - MARGIN, right)
        // And both are fully on screen — the ROM clamps again, but never on our behalf.
        assertTrue(left >= 0)
        assertTrue(right + landVisualW <= landScreenW)
    }

    @Test
    fun theHalfDecidesNotTheDistanceFromTheMiddle() {
        val mid = landScreenW / 2
        assertEquals(
            "just inside the left half stays left",
            MARGIN,
            triggerSideLeft(mid - 1, landVisualW, landScreenW, MARGIN),
        )
        assertEquals(
            "the first pixel of the right half goes right",
            landScreenW - landVisualW - MARGIN,
            triggerSideLeft(mid, landVisualW, landScreenW, MARGIN),
        )
    }

    @Test
    fun theVisibleWidthIsWhatHugsTheEdge() {
        // The window is drawn at `unscaled x scale` while its position is the unscaled origin's:
        // hugging with the unscaled width would leave a 30% gap at the right edge.
        val unscaledHug = triggerSideLeft(landScreenW, DISPLAY_W, landScreenW, MARGIN)
        val visualHug = triggerSideLeft(landScreenW, landVisualW, landScreenW, MARGIN)

        assertEquals(landScreenW - DISPLAY_W - MARGIN, unscaledHug)
        assertTrue("the gap the visible width exists to remove", visualHug - unscaledHug > DISPLAY_W / 4)
    }

    @Test
    fun aWindowWiderThanTheScreenStillGetsAUsableAnswer() {
        // No answer can put it in view (the ROM clamps the size, not us); it must at least stay
        // inside the screen rather than hang off the left edge, which is what a negative would do.
        val left = triggerSideLeft(0, landScreenW + 400, landScreenW, MARGIN)
        val right = triggerSideLeft(landScreenW, landScreenW + 400, landScreenW, MARGIN)

        assertEquals(MARGIN, left)
        assertEquals(MARGIN, right)
    }

    @Test
    fun portraitKeepsTheRomsOwnPlacement() {
        // The trigger only takes over a screen that is wider than it is tall: in portrait the ROM
        // centres (`getLeftMargin`) and that is the position the user has been using.
        assertTrue("landscape is where the side is a question", isWideScreen(DISPLAY_H, DISPLAY_W))
        assertTrue("portrait is the ROM's business", !isWideScreen(DISPLAY_W, DISPLAY_H))
        assertTrue("a square screen is not landscape", !isWideScreen(DISPLAY_W, DISPLAY_W))
    }

    @Test
    fun anAskedForPositionIsNotCenteredAway() {
        // The fan's own launch states where the window goes; 小窗居中 must leave that half alone
        // (it still owns the vertical placement). The sentinel is "ROM, you decide" — no request.
        assertEquals(
            1782,
            requestedLeft(listOf("context", 1782, 107, true, "pkg", true)),
        )
        assertEquals(0, requestedLeft(listOf("context", 0, 107, true, "pkg", true)))
        assertEquals(null, requestedLeft(listOf("context", MiuiFreeform.POS_AUTO, MiuiFreeform.POS_AUTO, true, "pkg", true)))
        assertEquals(null, requestedLeft(listOf("context", 20, MiuiFreeform.POS_AUTO, true, "pkg", true)))
        // addLaunchFreeformActivityOptionIfNeed's "no source rect" answer is not a request either.
        assertEquals(null, requestedLeft(listOf("context", -1, -1, true, "pkg", true)))

        // Other shapes of the same family (or other methods entirely) must not be read as a request.
        assertEquals(null, requestedLeft(listOf("context")))
        assertEquals(null, requestedLeft(listOf("context", "not an int", 107, true, "pkg")))
        assertEquals(null, requestedLeft(emptyList()))
    }

    // ---------------------------------------------------------------------------------------------
    // 小窗上下位置（滑杆）—— 用户 2026-10-11：竖方向默认居中，但可以上下调，横屏不生效
    // ---------------------------------------------------------------------------------------------

    @Test
    fun theVerticalSliderMovesWithinTheSlackAndNeverOffScreen() {
        // Portrait: 2656 tall screen, a 小窗 drawn at 1952 x scale.
        val visualH = portraitH * 0.70f
        val slack = DISPLAY_H - visualH
        val top = Math.round((DISPLAY_H - visualH) / 2f)

        assertEquals("0 = 正中", 0, verticalOffsetPx(0, DISPLAY_H, visualH))
        // 贴底/贴顶按"一像素以内"判：上下各取一次整，差 1px 是取整而不是位置错。
        assertEquals(
            "+100 = 贴底",
            (DISPLAY_H - visualH).toFloat(),
            (top + verticalOffsetPx(100, DISPLAY_H, visualH)).toFloat(),
            1f,
        )
        assertEquals("−100 = 贴顶", 0f, (top + verticalOffsetPx(-100, DISPLAY_H, visualH)).toFloat(), 1f)
        assertEquals("一半 = 余量的一半", Math.round(slack / 4f), verticalOffsetPx(50, DISPLAY_H, visualH))
        assertEquals("往上", -Math.round(slack / 4f), verticalOffsetPx(-50, DISPLAY_H, visualH))
    }

    @Test
    fun theSliderIsClampedAndHarmlessWhenThereIsNoSlack() {
        val visualH = portraitH * 0.70f
        assertEquals(verticalOffsetPx(100, DISPLAY_H, visualH), verticalOffsetPx(400, DISPLAY_H, visualH))
        assertEquals(verticalOffsetPx(-100, DISPLAY_H, visualH), verticalOffsetPx(-400, DISPLAY_H, visualH))
        // A window as tall as the screen has nowhere to go: the offset must stay 0, not push it out.
        assertEquals(0, verticalOffsetPx(100, DISPLAY_H, DISPLAY_H.toFloat()))
        assertEquals(0, verticalOffsetPx(-100, DISPLAY_H, DISPLAY_H + 200f))
    }

    @Test
    fun theSliderOnlyAppliesToPortrait() {
        // 横屏不生效 —— 横屏小窗几乎占满高度，挪一下就出屏（`centerRectOnScreen` 只在竖屏调它）。
        val landVisualH = DISPLAY_W * 0.59f
        assertTrue("landscape would have slack too", DISPLAY_W - landVisualH > 100f)
        assertTrue("but the caller must not use it", isWideScreen(DISPLAY_H, DISPLAY_W))
    }
}
