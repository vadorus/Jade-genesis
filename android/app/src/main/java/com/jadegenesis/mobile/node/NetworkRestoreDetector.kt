package com.jadegenesis.mobile.node

/**
 * Decides when a "network is back" event should trigger a node re-check.
 *
 * Pure state machine, free of Android classes so it can be covered by JVM
 * tests. [NetworkRestoreWatcher] feeds it the system callbacks.
 *
 * Rules:
 * - Only a validated network that returns AFTER a loss triggers. The first
 *   validated network seen at app start does not: the app already probes its
 *   nodes when it opens.
 * - Repeated "still validated" callbacks without a loss in between never
 *   trigger.
 * - A network that flaps quickly cannot cause a burst of probes: two triggers
 *   are at least [minIntervalMs] apart.
 */
internal class NetworkRestoreDetector(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS
) {
    private var lostSinceLastValidated = false
    private var lastTriggerAtMs: Long? = null

    init {
        require(minIntervalMs > 0L)
    }

    /** The system reported that the default network is gone or no longer validated. */
    @Synchronized
    fun onNetworkLost() {
        lostSinceLastValidated = true
    }

    /**
     * The system reported a validated default network.
     *
     * @return true when the caller should re-check its nodes now.
     */
    @Synchronized
    fun onNetworkValidated(nowMs: Long): Boolean {
        if (!lostSinceLastValidated) return false
        val previous = lastTriggerAtMs
        if (previous != null && nowMs >= previous && nowMs - previous < minIntervalMs) {
            // Keep the loss pending: the next validated callback after the
            // interval can still trigger, so a real reconnection is not lost.
            return false
        }
        lostSinceLastValidated = false
        lastTriggerAtMs = nowMs
        return true
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 15_000L
    }
}
