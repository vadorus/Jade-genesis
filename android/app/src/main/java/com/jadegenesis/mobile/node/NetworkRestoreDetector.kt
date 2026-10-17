package com.jadegenesis.mobile.node

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Decides when a "network is back" event should trigger a node re-check.
 *
 * Pure state machine, free of Android classes so it can be covered by JVM
 * tests. [NetworkRestoreWatcher] feeds it the system callbacks and provides a
 * [RetryScheduler] backed by a main-thread Handler.
 *
 * Rules:
 * - Only a validated network that returns AFTER a loss triggers. A validated
 *   network already present when watching starts does not: the app probes its
 *   nodes when it opens. If watching starts with NO validated network, that
 *   counts as a loss, so the first network that arrives triggers.
 * - Repeated "still validated" callbacks without a loss in between never
 *   trigger.
 * - Two triggers are at least [minIntervalMs] apart. A reconnection that comes
 *   too soon is not dropped: a retry is scheduled for the end of the interval
 *   and fires if the network is still validated then, because Android does not
 *   have to call back again once the network is stable.
 * - [onRestored] is never called while the detector's lock is held.
 */
internal class NetworkRestoreDetector(
    private val scheduler: RetryScheduler,
    private val onRestored: () -> Unit,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS
) {
    interface RetryScheduler {
        /** Calls [NetworkRestoreDetector.onRetryDue] once, about [delayMs] from now. */
        fun schedule(delayMs: Long)

        /** Drops a pending retry, if any. */
        fun cancel()
    }

    private var lostSinceLastTrigger = false
    private var validatedNow = false
    private var lastTriggerAtMs: Long? = null
    private var retryScheduled = false
    private var stopped = false

    init {
        require(minIntervalMs > 0L)
    }

    /** Watching starts; [validatedNow] = a validated network is already the default. */
    fun onStart(validatedNow: Boolean) {
        synchronized(this) {
            stopped = false
            this.validatedNow = validatedNow
            if (!validatedNow) lostSinceLastTrigger = true
        }
    }

    /** The default network is gone or no longer validated. */
    fun onNetworkLost() {
        synchronized(this) {
            validatedNow = false
            lostSinceLastTrigger = true
            cancelRetryLocked()
        }
    }

    /** The system reported a validated default network. */
    fun onNetworkValidated(nowMs: Long) {
        val fire = synchronized(this) {
            validatedNow = true
            evaluateLocked(nowMs)
        }
        if (fire) onRestored()
    }

    /** The retry scheduled by [RetryScheduler.schedule] is due. */
    fun onRetryDue(nowMs: Long) {
        val fire = synchronized(this) {
            retryScheduled = false
            validatedNow && evaluateLocked(nowMs)
        }
        if (fire) onRestored()
    }

    fun stop() {
        synchronized(this) {
            stopped = true
            cancelRetryLocked()
        }
    }

    private fun evaluateLocked(nowMs: Long): Boolean {
        if (stopped || !lostSinceLastTrigger) return false
        val previous = lastTriggerAtMs
        if (previous != null && nowMs >= previous && nowMs - previous < minIntervalMs) {
            if (!retryScheduled) {
                retryScheduled = true
                scheduler.schedule(previous + minIntervalMs - nowMs)
            }
            return false
        }
        lostSinceLastTrigger = false
        lastTriggerAtMs = nowMs
        cancelRetryLocked()
        return true
    }

    private fun cancelRetryLocked() {
        if (retryScheduled) {
            retryScheduled = false
            scheduler.cancel()
        }
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 15_000L
    }
}

/**
 * Runs [block] for each [request], never twice at the same time. A request that
 * arrives while [block] is running is not dropped: [block] runs once more after
 * the current run, however many requests arrived meanwhile. Thread-safe.
 */
internal class CoalescingRunner(
    private val scope: CoroutineScope,
    private val block: suspend () -> Unit
) {
    private val lock = Any()
    private var running = false
    private var pending = false

    fun request() {
        synchronized(lock) {
            if (running) {
                pending = true
                return
            }
            running = true
            pending = false
        }
        val job = scope.launch {
            do {
                block()
                val again = synchronized(lock) {
                    if (pending) {
                        pending = false
                        true
                    } else {
                        // Decided and released under the same lock, so a
                        // request racing with the end of the run starts a
                        // new one instead of being lost.
                        running = false
                        false
                    }
                }
            } while (again)
        }
        // Cancelled (even before starting) or failed: release the runner.
        job.invokeOnCompletion { cause ->
            if (cause != null) {
                synchronized(lock) {
                    running = false
                    pending = false
                }
            }
        }
    }
}
