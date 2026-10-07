package com.repl.bubbledrawer.root

/** Root helpers for edge-hugging mode (spec §4.4). Pure command building → JVM-testable. */
object EdgeModeHelper {

    /** Secure setting Flyme/AOSP gate: system back-gesture inset. 0 disables edge insets. */
    fun buildCmds(enable: Boolean): List<String> = listOf(
        "settings", "put", "secure", "system_gesture_insets_enabled", if (enable) "0" else "1",
    )

    enum class SuResult { AVAILABLE, NO_SU, DENIED }

    /** Decide by probing existence of a su binary (no exec in tests). */
    fun probe(suPaths: List<String>, exists: (String) -> Boolean): SuResult =
        if (suPaths.any(exists)) SuResult.AVAILABLE else SuResult.NO_SU

    fun probeExec(exitCode: Int?): SuResult = when (exitCode) {
        0 -> SuResult.AVAILABLE
        null -> SuResult.NO_SU
        else -> SuResult.DENIED
    }

    val SU_CANDIDATES = listOf("/system/xbin/su", "/system/bin/su", "/sbin/su", "/magisk/bin/su")

    /** Run `su -c <cmds joined>` on a background thread; best-effort, null = unavailable. */
    fun apply(enable: Boolean, exec: (List<String>) -> Int?): SuResult {
        return try {
            val code = exec(listOf("su", "-c", buildCmds(enable).joinToString(" ")))
            probeExec(code)
        } catch (_: Exception) {
            SuResult.NO_SU
        }
    }
}
