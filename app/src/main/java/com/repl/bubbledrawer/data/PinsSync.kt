package com.repl.bubbledrawer.data

/**
 * Reconciliation of the two pin stores (see [PinBackend.readRev]).
 *
 * Pure function on purpose: this is the whole "which 收藏 list is the real one" decision, and
 * it is the part that has to be testable without a device (see PinsSyncTest).
 *
 * Ordering rule: the newer stamp wins. Equal stamps — including the legacy case where BOTH
 * sides predate stamping (rev 0) — fall back to the shared bus (the LSPosed remote group the
 * two processes read), because that is the copy a third process would have observed. The
 * caller is expected to write the winner back into the losing store ([Decision.pushBack])
 * so the two converge instead of merely agreeing once.
 */
object PinsSync {

    enum class Source { LOCAL, REMOTE, NONE }

    /**
     * @param value the winning encoded pin string ("" = no pins)
     * @param rev stamp to store alongside it
     * @param source which store it came from (for logs)
     * @param pushBack true when the OTHER store must be overwritten with this value
     */
    data class Decision(
        val value: String,
        val rev: Long,
        val source: Source,
        val pushBack: Boolean,
    )

    fun decide(
        localValue: String,
        localRev: Long,
        remoteValue: String,
        remoteRev: Long,
    ): Decision {
        val local = localValue.trim()
        val remote = remoteValue.trim()

        // Stamped data decides purely by freshness — an EMPTY value from a stamped store is a
        // real edit ("unpin everything"), not a cold store. Skipping this is how a "clear all"
        // would silently come back to life from the other side's older list.
        if (localRev > 0L || remoteRev > 0L) {
            if (remoteRev > localRev) return Decision(remote, remoteRev, Source.REMOTE, true)
            if (localRev > remoteRev) return Decision(local, localRev, Source.LOCAL, true)
            return if (local == remote) {
                Decision(local, localRev, Source.LOCAL, false)
            } else {
                // Equal stamps (e.g. two writes inside the same millisecond): the shared bus
                // is the copy a third process would have observed, so it is the tie-breaker.
                Decision(remote, remoteRev, Source.REMOTE, true)
            }
        }

        // Legacy, unstamped data: here "" can only mean "never filled".
        return when {
            local.isEmpty() && remote.isEmpty() -> Decision("", 0L, Source.NONE, false)
            remote.isEmpty() -> Decision(local, localRev, Source.LOCAL, true)
            local.isEmpty() -> Decision(remote, remoteRev, Source.REMOTE, true)
            local == remote -> Decision(local, localRev, Source.LOCAL, false)
            else -> Decision(remote, remoteRev, Source.REMOTE, true)
        }
    }
}
