package com.repl.bubbledrawer.bubble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FanPaginationTest {

    private fun paginate(
        pinnedCount: Int,
        recommendCount: Int,
        maxApps: Int,
        autoFillRecommend: Boolean,
    ): List<List<String>> {
        val pinned = (1..pinnedCount).map { "pinned_$it" }
        val rec = (1..recommendCount).map { "rec_$it" }
        val pages = ArrayList<List<String>>()

        if (pinned.isEmpty() && rec.isEmpty()) {
            return listOf(listOf("More"))
        }

        if (pinned.isEmpty()) {
            var idx = 0
            while (idx < rec.size) {
                val chunk = rec.subList(idx, minOf(idx + maxApps, rec.size))
                pages.add(chunk + "More")
                idx += maxApps
            }
            return pages
        }

        if (!autoFillRecommend && pinned.size <= maxApps) {
            pages.add(pinned + "More")
            var rIdx = 0
            while (rIdx < rec.size) {
                val chunk = rec.subList(rIdx, minOf(rIdx + maxApps, rec.size))
                pages.add(chunk + "More")
                rIdx += maxApps
            }
            return pages
        }

        val pool = ArrayList<String>()
        pool.addAll(pinned)
        pool.addAll(rec)

        var idx = 0
        while (idx < pool.size) {
            val chunk = pool.subList(idx, minOf(idx + maxApps, pool.size))
            pages.add(chunk + "More")
            idx += maxApps
        }
        return pages
    }

    @Test
    fun testTwelvePinnedWithRecommendations() {
        val pages = paginate(pinnedCount = 12, recommendCount = 8, maxApps = 5, autoFillRecommend = false)
        // 12 pinned + 8 rec = 20 total apps -> 4 pages of 5
        assertEquals(4, pages.size)
        // Page 0: pinned 1..5
        assertEquals(listOf("pinned_1", "pinned_2", "pinned_3", "pinned_4", "pinned_5", "More"), pages[0])
        // Page 1: pinned 6..10
        assertEquals(listOf("pinned_6", "pinned_7", "pinned_8", "pinned_9", "pinned_10", "More"), pages[1])
        // Page 2: pinned 11..12 + rec 1..3 (收藏没了就显示推荐的)
        assertEquals(listOf("pinned_11", "pinned_12", "rec_1", "rec_2", "rec_3", "More"), pages[2])
        // Page 3: rec 4..8
        assertEquals(listOf("rec_4", "rec_5", "rec_6", "rec_7", "rec_8", "More"), pages[3])
    }

    @Test
    fun testTwoPinnedWithoutAutoFill() {
        val pages = paginate(pinnedCount = 2, recommendCount = 7, maxApps = 5, autoFillRecommend = false)
        // Page 0 has only the 2 pinned apps + More
        assertEquals(listOf("pinned_1", "pinned_2", "More"), pages[0])
        // Page 1 has recommendations 1..5 + More
        assertEquals(listOf("rec_1", "rec_2", "rec_3", "rec_4", "rec_5", "More"), pages[1])
        // Page 2 has recommendations 6..7 + More
        assertEquals(listOf("rec_6", "rec_7", "More"), pages[2])
    }

    @Test
    fun testTwoPinnedWithAutoFill() {
        val pages = paginate(pinnedCount = 2, recommendCount = 7, maxApps = 5, autoFillRecommend = true)
        // Page 0 is filled to 5 items using recommendations
        assertEquals(listOf("pinned_1", "pinned_2", "rec_1", "rec_2", "rec_3", "More"), pages[0])
        // Page 1 has rec 4..7 + More
        assertEquals(listOf("rec_4", "rec_5", "rec_6", "rec_7", "More"), pages[1])
    }

    @Test
    fun testPressureThresholdLogic() {
        var triggered = 0
        var isHeavyPressed = false
        val threshold = 1.2f
        val releaseThreshold = threshold * 0.4f

        fun onSample(delta: Float) {
            if (delta >= threshold) {
                if (!isHeavyPressed) {
                    isHeavyPressed = true
                    triggered++
                }
            } else if (delta < releaseThreshold) {
                isHeavyPressed = false
            }
        }

        // Light touch, moving around
        onSample(0.1f)
        onSample(0.3f)
        onSample(0.5f)
        assertEquals(0, triggered)

        // Heavy press spikes
        onSample(1.25f)
        assertEquals(1, triggered)

        // Sustained high pressure does not repeat trigger
        onSample(1.5f)
        onSample(1.8f)
        assertEquals(1, triggered)

        // Partial release above release threshold
        onSample(0.7f)
        assertEquals(1, triggered)

        // Full release below release threshold
        onSample(0.2f)
        assertFalse(isHeavyPressed)

        // Second heavy press triggers next page!
        onSample(1.3f)
        assertEquals(2, triggered)
        assertTrue(isHeavyPressed)
    }
}
