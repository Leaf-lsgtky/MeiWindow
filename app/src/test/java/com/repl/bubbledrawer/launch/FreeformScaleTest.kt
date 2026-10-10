package com.repl.bubbledrawer.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 小窗 scale cap: "Flyme-style size, but never bigger than what the ROM itself would allow".
 *
 * Background (see `hookFreeformScale` and `MiuiMultiWindowUtils`):
 *
 *  - the ROM scales its own *unscaled* task bounds by one factor; portrait bounds are
 *    `shortSide * ratio` wide, landscape bounds are `shortSide * ratio * aspect` wide — on this
 *    phone (1220x2656) that is ~2.2x wider on the same short side;
 *  - `reviewFreeFormBounds` shrinks a scale until the visual rect fits the freeform accessible
 *    area, and that reviewed value is what the ROM returns next to our preferred one.
 *
 * So a fixed scale is only safe in portrait, and 横屏 inside the 小窗 used to push the window's
 * visual width past the display — which the rotation path only *offsets*, never shrinks.
 */
class FreeformScaleTest {

    private companion object {
        /** Target device: 1220x2656 @ 520dpi. */
        const val DISPLAY_W = 1220
        const val DISPLAY_H = 2656

        /** `PHONE_FREEFORM_ACCESSIBLE_AREA_SIDE_MARGIN` = 6dp ≈ 20px, applied on both sides. */
        const val ACCESSIBLE_W = DISPLAY_W - 2 * 20

        /** A landscape window's aspect on this display (`getAspectRatio(landscape = true)`). */
        val ASPECT = DISPLAY_H.toFloat() / DISPLAY_W

        /** The ROM's `original_ratio` for a layout (`MiuiFreeformLaunchConfig` ARGS_ORIGINAL_RATIO). */
        const val RATIO = 0.85f
    }

    @Test
    fun portraitKeepsTheSettingWhenTheRomAgrees() {
        // Portrait: 1037 x 2260 unscaled, visual 830 x 1808 at 80% — comfortably inside the area,
        // so the ROM's review leaves the preferred scale untouched.
        assertEquals(0.80f, fitFreeformScale(0.80f, 0.80f), 1e-6f)
    }

    @Test
    fun landscapeIsClampedToWhatFitsOnScreen() {
        val unscaledW = DISPLAY_W * RATIO * ASPECT
        val wouldBeW = unscaledW * 0.80f

        // The old behaviour: the setting verbatim, i.e. a window wider than the phone (the report).
        assertTrue("landscape at 80% must overflow the display", wouldBeW > DISPLAY_W)

        // What the ROM answers for that same window: its own review of our preferred scale.
        val romFit = 0.80f * ACCESSIBLE_W / wouldBeW
        val used = fitFreeformScale(0.80f, romFit)

        assertEquals(romFit, used, 1e-6f)
        assertTrue("clamped window fits the accessible area", unscaledW * used <= ACCESSIBLE_W + 0.5f)
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
}
