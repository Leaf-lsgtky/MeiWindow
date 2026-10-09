package com.repl.bubbledrawer.data.predict

/**
 * Historical application event data point representing an app launch / foreground transition.
 * Matches Xiaomi SecurityCenter's `com.miui.mlkit.mobilerec.bean.PredictApp`, with native
 * multi-user support (User 0, User 999 dual apps, Work Profiles).
 */
data class PredictApp(
    val pkg: String,
    val userId: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val network: String = "WIFI",
) {
    /** Unique identity key distinguishing multi-user instances. */
    val key: String get() = if (userId == 0) pkg else "$pkg#$userId"

    companion object {
        fun fromKey(key: String, timestamp: Long = System.currentTimeMillis(), network: String = "WIFI"): PredictApp {
            val parts = key.split("#")
            return if (parts.size >= 2) {
                PredictApp(parts[0], parts[1].toIntOrNull() ?: 0, timestamp, network)
            } else {
                PredictApp(key, 0, timestamp, network)
            }
        }
    }
}
