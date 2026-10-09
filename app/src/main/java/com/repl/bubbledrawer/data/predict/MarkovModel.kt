package com.repl.bubbledrawer.data.predict

import java.util.concurrent.ConcurrentHashMap

/**
 * 2-order Markov Transition Chain (PBB) replicating Xiaomi SecurityCenter's `p185fd.C11275b`.
 *
 * Computes:
 * - 1st order transition probability: P(C | B) = Count(B -> C) / TotalCount(B -> *)
 * - 2nd order transition probability: P(C | A, B) = Count(A, B -> C) / TotalCount(A, B -> *)
 * - Combined transition score: Score(C) = P(C | B) + P(C | A, B)
 */
class MarkovModel {

    // 1st order transitions: B -> (C -> count)
    private val firstOrder = ConcurrentHashMap<String, MutableMap<String, Int>>()

    // 2nd order transitions: A -> (B -> (C -> count))
    private val secondOrder = ConcurrentHashMap<String, MutableMap<String, MutableMap<String, Int>>>()

    @Synchronized
    fun clear() {
        firstOrder.clear()
        secondOrder.clear()
    }

    /**
     * Records a transition from (prev2, prev1) -> targetApp.
     * Verbatim SecurityCenter's `C11275b.m38443j`.
     */
    @Synchronized
    fun record(prev2: String, prev1: String, targetApp: String) {
        if (prev1.isEmpty() || targetApp.isEmpty()) return

        // 1. Update 1st-order: prev1 -> targetApp
        val order1Map = firstOrder.getOrPut(prev1) { ConcurrentHashMap() }
        order1Map[targetApp] = (order1Map[targetApp] ?: 0) + 1

        // 2. Update 2nd-order: prev2 -> prev1 -> targetApp
        if (prev2.isNotEmpty()) {
            val order2Outer = secondOrder.getOrPut(prev2) { ConcurrentHashMap() }
            val order2Inner = order2Outer.getOrPut(prev1) { ConcurrentHashMap() }
            order2Inner[targetApp] = (order2Inner[targetApp] ?: 0) + 1
        }
    }

    /**
     * Batch trains on a chronological sequence of app launch events.
     */
    @Synchronized
    fun train(events: List<PredictApp>) {
        if (events.size < 2) return
        for (i in 1 until events.size) {
            val prev2 = if (i >= 2) events[i - 2].key else ""
            val prev1 = events[i - 1].key
            val target = events[i].key
            record(prev2, prev1, target)
        }
    }

    /**
     * Predicts candidate apps given preceding apps (prev2, prev1).
     * Implements verbatim SecurityCenter's `C11275b.m38441e`.
     * Returns candidate app keys sorted by transition score descending.
     */
    fun predict(prev2: String, prev1: String): List<String> {
        val scores = HashMap<String, Float>()

        // 1. First order evaluation: B -> *
        val map1 = firstOrder[prev1]
        if (!map1.isNullOrEmpty()) {
            var sum1 = 0f
            for (count in map1.values) {
                sum1 += count
            }
            if (sum1 > 0f) {
                for ((target, count) in map1) {
                    val p1 = count / sum1
                    scores[target] = (scores[target] ?: 0f) + p1
                }
            }
        }

        // 2. Second order evaluation: A -> B -> *
        if (prev2.isNotEmpty()) {
            val map2 = secondOrder[prev2]?.get(prev1)
            if (!map2.isNullOrEmpty()) {
                var sum2 = 0f
                for (count in map2.values) {
                    sum2 += count
                }
                if (sum2 > 0f) {
                    for ((target, count) in map2) {
                        val p2 = count / sum2
                        scores[target] = (scores[target] ?: 0f) + p2
                    }
                }
            }
        }

        if (scores.isEmpty()) return emptyList()

        val list = ArrayList(scores.entries)
        list.sortByDescending { it.value }
        return list.map { it.key }
    }
}
