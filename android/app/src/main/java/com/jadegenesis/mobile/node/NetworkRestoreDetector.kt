package com.jadegenesis.mobile.node

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Decides when a network restoration should trigger a node re-check.
 *
 * Pure logic (no Android types) so it can be covered by JVM tests. Rules:
 * - a re-check needs a loss first: the network already present at start-up,
 *   or reported again without any loss in between, never triggers;
 * - starting without a validated network counts as a loss (Android sends no
 *   onLost when there was nothing to lose);
 * - two triggers are at least [minIntervalMs] apart. A return that comes too
 *   soon is not dropped: a single retry is scheduled at the end of the
 *   interval and fires if the network is still validated then;
 * - a new loss or [stop] cancels the pending retry.
 *
 * [onRestored] and [onDeferred] are always called outside the internal lock.
 */
internal class NetworkRestoreDetector(
    private val scheduler: RetryScheduler,
    private val onRestored: () -> Unit,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val onDeferred: (delayMs: Long) -> Unit = {}
) {
    interface RetryScheduler {
        fun schedule(delayMs: Long)
        fun cancel()
    }

    private sealed interface Decision {
        object None : Decision
        object Fire : Decision
        data class Deferred(val delayMs: Long) : Decision
    }

    private var lostSinceLastTrigger = false
    private var validatedNow = false
    private var lastTriggerAtMs: Long? = null
    private var retryScheduled = false
    private var stopped = false

    init {
        require(minIntervalMs > 0L)
    }

    fun onStart(validatedNow: Boolean) {
        synchronized(this) {
            stopped = false
            this.validatedNow = validatedNow
            if (!validatedNow) lostSinceLastTrigger = true
        }
    }

    fun onNetworkLost() {
        synchronized(this) {
            validatedNow = false
            lostSinceLastTrigger = true
            cancelRetryLocked()
        }
    }

    fun onNetworkValidated(nowMs: Long) {
        val decision = synchronized(this) {
            validatedNow = true
            evaluateLocked(nowMs)
        }
        dispatch(decision)
    }

    fun onRetryDue(nowMs: Long) {
        val decision = synchronized(this) {
            retryScheduled = false
            if (validatedNow) evaluateLocked(nowMs) else Decision.None
        }
        dispatch(decision)
    }

    fun stop() {
        synchronized(this) {
            stopped = true
            cancelRetryLocked()
        }
    }

    private fun dispatch(decision: Decision) {
        when (decision) {
            Decision.Fire -> onRestored()
            is Decision.Deferred -> onDeferred(decision.delayMs)
            Decision.None -> Unit
        }
    }

    private fun evaluateLocked(nowMs: Long): Decision {
        if (stopped || !lostSinceLastTrigger) return Decision.None
        val previous = lastTriggerAtMs
        // A clock going backwards (should not happen with elapsedRealtime) must
        // never block re-checks forever: treat it as "interval elapsed".
        if (previous != null && nowMs >= previous && nowMs - previous < minIntervalMs) {
            if (retryScheduled) return Decision.None
            val delayMs = previous + minIntervalMs - nowMs
            retryScheduled = true
            scheduler.schedule(delayMs)
            return Decision.Deferred(delayMs)
        }
        lostSinceLastTrigger = false
        lastTriggerAtMs = nowMs
        cancelRetryLocked()
        return Decision.Fire
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
 * Tracks the validated PHYSICAL networks (Wi-Fi, mobile data, Ethernet) and
 * feeds [NetworkRestoreDetector] with "lost" / "validated" transitions.
 *
 * 0.1.26: 0.1.25 watched the system default network. With Tailscale running,
 * that default network is the VPN, which can stay present and validated while
 * Wi-Fi and data are cut, so no loss and no return were ever seen (real Pixel
 * test, 17/10/2026). The Android watcher now registers for non-VPN networks
 * only and reports each of them here, identified by an opaque id.
 *
 * "Lost" = no validated physical network left. "Back" = at least one again.
 * A Wi-Fi -> mobile handover with an overlap is therefore not a loss.
 *
 * [onPhysicalLost] and [log] are called outside the internal lock.
 * Log metadata never contains addresses, network names or tokens.
 */
internal class PhysicalNetworkMonitor(
    private val detector: NetworkRestoreDetector,
    private val onPhysicalLost: () -> Unit = {},
    private val log: (event: String, metadata: Map<String, Any?>) -> Unit = { _, _ -> }
) {
    private val validated = HashSet<String>()
    private var online = false

    fun start(initialValidated: Collection<String>) {
        val onlineNow = synchronized(this) {
            validated.clear()
            validated.addAll(initialValidated)
            online = validated.isNotEmpty()
            online
        }
        detector.onStart(onlineNow)
    }

    /** Capabilities of one physical network changed ([usable] = INTERNET + VALIDATED). */
    fun onNetworkChanged(id: String, usable: Boolean, transport: String, nowMs: Long) {
        update(nowMs, transport) {
            if (usable) validated.add(id) else validated.remove(id)
        }
    }

    /** One physical network disappeared. */
    fun onNetworkLost(id: String, nowMs: Long) {
        update(nowMs, TRANSPORT_NONE) { validated.remove(id) }
    }

    val validatedCount: Int
        get() = synchronized(this) { validated.size }

    private fun update(nowMs: Long, transport: String, change: () -> Unit) {
        val transition = synchronized(this) {
            change()
            val nowOnline = validated.isNotEmpty()
            val changed = nowOnline != online
            online = nowOnline
            Transition(nowOnline, changed, validated.size)
        }
        if (transition.changed) {
            log(
                if (transition.online) EVENT_PHYSICAL_BACK else EVENT_PHYSICAL_LOST,
                mapOf(
                    "validated_networks" to transition.count,
                    "last_change_transport" to transport
                )
            )
        }
        if (transition.online) {
            detector.onNetworkValidated(nowMs)
        } else {
            detector.onNetworkLost()
            if (transition.changed) onPhysicalLost()
        }
    }

    private data class Transition(val online: Boolean, val changed: Boolean, val count: Int)

    companion object {
        const val EVENT_PHYSICAL_LOST = "network_physical_lost"
        const val EVENT_PHYSICAL_BACK = "network_physical_back"
        const val TRANSPORT_NONE = "none"
    }
}

/**
 * Runs [block] in [scope], never twice at the same time, and never drops a
 * request: a request made while a run is active schedules exactly one more run
 * after it (several requests during one run are merged into that one).
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
                // Deciding to finish and clearing `running` happen under the
                // same lock, so a concurrent request() is either seen here or
                // starts a new run itself.
                val again = synchronized(lock) {
                    if (pending) {
                        pending = false
                        true
                    } else {
                        running = false
                        false
                    }
                }
            } while (again)
        }
        // A failed or cancelled run must not block the runner forever.
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
