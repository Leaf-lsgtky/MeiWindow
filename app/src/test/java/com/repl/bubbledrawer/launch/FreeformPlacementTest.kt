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
}
