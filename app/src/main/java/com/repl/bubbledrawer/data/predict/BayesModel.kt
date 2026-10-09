package com.repl.bubbledrawer.data.predict

import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * 5-dimensional Naive Bayes Classifier replicating Xiaomi SecurityCenter's `p185fd.C11277d`.
 *
 * Feature vector:
 * 0: App_{t-2} (two steps prior app)
 * 1: App_{t-1} (one step prior app)
 * 2: Network type ("WIFI", "MOBILE", "OFFLINE")
 * 3: TimeSlot (0..143: 144 10-minute slots per day, with circular ±1 slot smoothing)
 * 4: DayOfWeek (0..6: Monday to Sunday)
 */
class BayesModel {

    companion object {
        const val FEATURE_COUNT = 5
        const val TOTAL_SLOTS = 144

        /** Converts timestamp to time slot (0..143, 10 min per slot) verbatim `AbstractC11279f.m38454e`. */
        fun timeSlotOf(timestamp: Long): Int {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = timestamp
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)
            return (minute / 10) + (hour * 6)
        }

        /** Converts timestamp to day of week (0..6, Monday to Sunday) verbatim `AbstractC11279f.m38450a`. */
        fun dayOfWeekOf(timestamp: Long): Int {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = timestamp
            return when (calendar.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> 0
                Calendar.TUESDAY -> 1
                Calendar.WEDNESDAY -> 2
                Calendar.THURSDAY -> 3
                Calendar.FRIDAY -> 4
                Calendar.SATURDAY -> 5
                Calendar.SUNDAY -> 6
                else -> 0
            }
        }
    }

    private var totalSamples: Int = 0

    // app -> (featureIndex -> (featureValue -> count))
    private val appFeatureCounts = ConcurrentHashMap<String, MutableMap<Int, MutableMap<String, Int>>>()

    // featureIndex -> (featureValue -> totalCount)
    private val globalFeatureCounts = ConcurrentHashMap<Int, MutableMap<String, Int>>()

    init {
        for (i in 0 until FEATURE_COUNT) {
            globalFeatureCounts[i] = ConcurrentHashMap()
        }
    }

    @Synchronized
    fun clear() {
        totalSamples = 0
        appFeatureCounts.clear()
        globalFeatureCounts.clear()
        for (i in 0 until FEATURE_COUNT) {
            globalFeatureCounts[i] = ConcurrentHashMap()
        }
    }

    /**
     * Records a single training transition:
     * (prev2, prev1, network, timestamp) -> targetApp
     */
    @Synchronized
    fun record(
        prev2: String,
        prev1: String,
        network: String,
        timestamp: Long,
        targetApp: String,
    ) {
        val features = listOf(
            prev2,
            prev1,
            network,
            timeSlotOf(timestamp).toString(),
            dayOfWeekOf(timestamp).toString(),
        )
        recordFeatures(features, targetApp)
    }

    /**
     * Batch trains on a chronological sequence of app launch events.
     */
    @Synchronized
    fun train(events: List<PredictApp>) {
        if (events.size < 3) return
        for (i in 2 until events.size) {
            val prev2 = events[i - 2].key
            val prev1 = events[i - 1].key
            val network = events[i - 1].network
            val time = events[i - 1].timestamp
            val target = events[i].key
            record(prev2, prev1, network, time, target)
        }
    }

    private fun recordFeatures(features: List<String>, targetApp: String) {
        if (features.size != FEATURE_COUNT) return

        val appMap = appFeatureCounts.getOrPut(targetApp) {
            val m = ConcurrentHashMap<Int, MutableMap<String, Int>>()
            for (i in 0 until FEATURE_COUNT) {
                m[i] = ConcurrentHashMap()
            }
            m
        }

        for (i in 0 until FEATURE_COUNT) {
            val fVal = features[i]
            val fMap = appMap.getOrPut(i) { ConcurrentHashMap() }
            fMap[fVal] = (fMap[fVal] ?: 0) + 1

            val globalMap = globalFeatureCounts.getOrPut(i) { ConcurrentHashMap() }
            globalMap[fVal] = (globalMap[fVal] ?: 0) + 1
        }
        totalSamples++
    }

    /**
     * Predicts candidate apps based on given context features.
     * Implements SecurityCenter's `C11277d.m38449l` with circular time-slot smoothing.
     * Returns app keys sorted by posterior score descending.
     */
    fun predict(
        prev2: String,
        prev1: String,
        network: String,
        timestamp: Long,
    ): List<String> {
        if (totalSamples < 1 || appFeatureCounts.isEmpty()) return emptyList()

        val features = listOf(
            prev2,
            prev1,
            network,
            timeSlotOf(timestamp).toString(),
            dayOfWeekOf(timestamp).toString(),
        )

        val slot = features[3].toIntOrNull() ?: 0
        val slotPrev = ((slot - 1 + TOTAL_SLOTS) % TOTAL_SLOTS).toString()
        val slotNext = ((slot + 1) % TOTAL_SLOTS).toString()
        val slotCurr = slot.toString()

        val scored = ArrayList<Pair<String, Double>>()

        for ((app, appFeatures) in appFeatureCounts) {
            var score = 0.0

            for (i in 0 until FEATURE_COUNT) {
                val fVal = features[i]
                val fMap = appFeatures[i] ?: emptyMap()
                val globalMap = globalFeatureCounts[i] ?: emptyMap()

                val appCoCount: Int
                val globalCount: Int

                if (i != 3) {
                    // Regular categorical feature
                    appCoCount = fMap[fVal] ?: 0
                    globalCount = globalMap[fVal] ?: 0
                } else {
                    // TimeSlot feature with ±1 circular smoothing (verbatim SecurityCenter C11277d)
                    val cPrev = fMap[slotPrev] ?: 0
                    val cCurr = fMap[slotCurr] ?: 0
                    val cNext = fMap[slotNext] ?: 0
                    appCoCount = cPrev + cCurr + cNext

                    val gPrev = globalMap[slotPrev] ?: 0
                    val gCurr = globalMap[slotCurr] ?: 0
                    val gNext = globalMap[slotNext] ?: 0
                    globalCount = gPrev + gCurr + gNext
                }

                if (globalCount > 0) {
                    score += appCoCount.toDouble() / (globalCount.toDouble() + 1e-10)
                }
            }

            scored.add(app to score)
        }

        // Sort descending by posterior probability score
        scored.sortByDescending { it.second }
        return scored.map { it.first }
    }
}
