package com.repl.bubbledrawer.data.predict

import com.repl.bubbledrawer.data.PinnedRef
import com.repl.bubbledrawer.pin.Cell
import com.repl.bubbledrawer.pin.Sections
import com.repl.bubbledrawer.pinyin.BubbleApp
import com.repl.bubbledrawer.xposed.RemotePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AppPredictorTest {

    @Test
    fun testTimeSlotAndDayOfWeekFormulas() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 14) // 14:25
        cal.set(Calendar.MINUTE, 25)
        cal.set(Calendar.DAY_OF_WEEK, Calendar.FRIDAY)

        val slot = BayesModel.timeSlotOf(cal.timeInMillis)
        // 14 * 6 + (25 / 10) = 84 + 2 = 86
        assertEquals(86, slot)

        val dow = BayesModel.dayOfWeekOf(cal.timeInMillis)
        // Friday should be 4 (Monday=0 ... Friday=4)
        assertEquals(4, dow)
    }

    @Test
    fun testBayesModelTrainingAndPrediction() {
        val bayes = BayesModel()
        val now = System.currentTimeMillis()

        // Train pattern: (wechat, qq) -> bilibili
        bayes.record("com.tencent.mm", "com.tencent.mobileqq", "WIFI", now, "tv.danmaku.bili")
        bayes.record("com.tencent.mm", "com.tencent.mobileqq", "WIFI", now, "tv.danmaku.bili")
        // Alternative pattern: (wechat, qq) -> browser
        bayes.record("com.tencent.mm", "com.tencent.mobileqq", "WIFI", now, "com.android.browser")

        val predictions = bayes.predict("com.tencent.mm", "com.tencent.mobileqq", "WIFI", now)
        assertTrue(predictions.isNotEmpty())
        assertEquals("tv.danmaku.bili", predictions[0])
    }

    @Test
    fun testBayesTimeSlotCircularSmoothing() {
        val bayes = BayesModel()
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 5) // slot 0 (00:00 - 00:09)
        val timeSlot0 = cal.timeInMillis

        // Train at slot 0
        bayes.record("appA", "appB", "WIFI", timeSlot0, "targetApp")

        // Query at slot 143 (23:55) -> circular adjacent
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 55)
        val timeSlot143 = cal.timeInMillis

        val predictions = bayes.predict("appA", "appB", "WIFI", timeSlot143)
        assertTrue("Circular smoothing should connect slot 143 and slot 0", predictions.contains("targetApp"))
    }

    @Test
    fun testMarkovModel1stAnd2ndOrder() {
        val markov = MarkovModel()

        // 1st order: B -> C
        markov.record("A", "B", "C")
        markov.record("X", "B", "C")
        // 2nd order: A, B -> D
        markov.record("A", "B", "D")

        val predAB = markov.predict("A", "B")
        // Both C and D should be predicted
        assertTrue(predAB.contains("C"))
        assertTrue(predAB.contains("D"))

        // Predict with unobserved first app
        val predUnknownB = markov.predict("Unknown", "B")
        assertEquals("C", predUnknownB[0])
    }

    @Test
    fun testRankFusionRRF() {
        val bayes = listOf("appA", "appB", "appC")
        val markov = listOf("appB", "appA", "appD")

        val fused = RankFusion.fuse(bayes, markov)
        // appA score = 1/(0+1) + 1/(1+1) = 1.0 + 0.5 = 1.5
        // appB score = 1/(1+1) + 1/(0+1) = 0.5 + 1.0 = 1.5
        // Both A and B must be top 2
        assertTrue(fused.take(2).contains("appA"))
        assertTrue(fused.take(2).contains("appB"))
    }

    @Test
    fun testMultiUserKeyHandling() {
        val dualApp = PredictApp("com.tencent.mm", 999)
        assertEquals("com.tencent.mm#999", dualApp.key)

        val restored = PredictApp.fromKey(dualApp.key)
        assertEquals("com.tencent.mm", restored.pkg)
        assertEquals(999, restored.userId)

        val defaultUserApp = PredictApp("com.tencent.mm", 0)
        assertEquals("com.tencent.mm", defaultUserApp.key)
    }

    @Test
    fun testSectionsDynamicRecommendationCount() {
        val all = listOf(
            BubbleApp("app1", "App 1", 0, usageCount = 10),
            BubbleApp("app2", "App 2", 0, usageCount = 9),
            BubbleApp("app3", "App 3", 0, usageCount = 8),
            BubbleApp("app4", "App 4", 0, usageCount = 7),
            BubbleApp("app5", "App 5", 0, usageCount = 6),
            BubbleApp("app6", "App 6", 0, usageCount = 5),
            BubbleApp("app7", "App 7", 0, usageCount = 4),
            BubbleApp("app8", "App 8", 0, usageCount = 3),
            BubbleApp("app9", "App 9", 0, usageCount = 2),
        )

        // When recommend is false
        val cellsDisabled = Sections.build(all, emptyList(), recommend = false, recommendCount = 8)
        assertFalse(cellsDisabled.any { it is Cell.Label && it.text == "推荐" })

        // When recommend is true with count = 4
        val cells4 = Sections.build(all, emptyList(), recommend = true, recommendCount = 4)
        val recApps4 = cells4.filterIsInstance<Cell.App>().filter { it.section == "推荐" }
        assertEquals(4, recApps4.size)

        // When recommend is true with count = 8
        val cells8 = Sections.build(all, emptyList(), recommend = true, recommendCount = 8)
        val recApps8 = cells8.filterIsInstance<Cell.App>().filter { it.section == "推荐" }
        assertEquals(8, recApps8.size)
    }

    @Test
    fun testRecommendCountSnapping() {
        val steps = RemotePrefs.RECOMMEND_COUNT_STEP
        val min = RemotePrefs.RECOMMEND_COUNT_MIN
        val max = RemotePrefs.RECOMMEND_COUNT_MAX

        fun snap(value: Int): Int {
            val raw = value.coerceIn(min, max)
            return (((raw - min + (steps / 2)) / steps) * steps + min).coerceIn(min, max)
        }

        assertEquals(4, snap(4))
        assertEquals(4, snap(5))
        assertEquals(8, snap(6))
        assertEquals(8, snap(8))
        assertEquals(12, snap(10))
        assertEquals(12, snap(12))
        assertEquals(16, snap(15))
        assertEquals(20, snap(19))
        assertEquals(20, snap(20))
        assertEquals(20, snap(25))
    }
}
