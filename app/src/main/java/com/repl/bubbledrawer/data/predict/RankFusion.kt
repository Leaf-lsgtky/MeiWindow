package com.repl.bubbledrawer.data.predict

/**
 * Reciprocal Rank Fusion (RRF) reproducing Xiaomi SecurityCenter's `p185fd.AbstractC11279f.m38452c`.
 *
 * Combines ranked candidate predictions from BayesModel and MarkovModel:
 *   Score(App) = sum_{rank in [Bayes, Markov]} (1.0 / (rank + 1))
 * for top 50 candidates in each list.
 */
object RankFusion {

    private const val MAX_CANDIDATES = 50

    fun fuse(bayesRanks: List<String>, markovRanks: List<String>): List<String> {
        val scoreMap = HashMap<String, Float>()
        accumulateRRF(bayesRanks, scoreMap)
        accumulateRRF(markovRanks, scoreMap)

        if (scoreMap.isEmpty()) return emptyList()

        val sorted = ArrayList(scoreMap.entries)
        sorted.sortByDescending { it.value }
        return sorted.map { it.key }
    }

    private fun accumulateRRF(ranks: List<String>?, map: HashMap<String, Float>) {
        if (ranks.isNullOrEmpty()) return
        val count = Math.min(ranks.size, MAX_CANDIDATES)
        for (i in 0 until count) {
            val app = ranks[i]
            val score = 1.0f / (i + 1)
            map[app] = (map[app] ?: 0.0f) + score
        }
    }
}
