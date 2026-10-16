package com.jadegenesis.mobile.node

import com.jadegenesis.mobile.model.NodeStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A process-shared single-flight gate without Android or networking dependencies. */
internal class NodeRefreshCoordinator {
    private val refreshMutex = Mutex()
    @Volatile private var completedGeneration = 0L

    suspend fun <T> refreshOrReuse(
        refresh: suspend () -> T,
        reuse: suspend () -> T
    ): T {
        val generationAtRequest = completedGeneration
        return refreshMutex.withLock {
            if (completedGeneration != generationAtRequest) {
                reuse()
            } else {
                refresh().also { completedGeneration++ }
            }
        }
    }
}

/**
 * Pure decisions used by [NodeManager.refreshRemoteNodes], kept free of Android
 * and network code so they can be covered by JVM tests.
 */
internal object NodeRefreshPolicy {

    /**
     * Nodes worth a second, more patient probe.
     *
     * A retry is only useful when no remote node answered: typically the phone's
     * Tailscale tunnel is still waking up. Only OFFLINE nodes are retried. ERROR
     * nodes (refused token, wrong identity, no Tailscale route) would fail the
     * same way, but they must not prevent the retry of the OFFLINE ones.
     */
    fun nodesToRetry(statuses: List<Pair<String, NodeStatus>>): Set<String> {
        if (statuses.any { (_, status) -> status == NodeStatus.ONLINE }) return emptySet()
        return statuses
            .filter { (_, status) -> status == NodeStatus.OFFLINE }
            .map { (id, _) -> id }
            .toSet()
    }

    /** First patient probe always allowed; later attempts use a monotonic 60 s window. */
    fun shouldRunPatientRetry(
        nowMs: Long,
        previousAttemptMs: Long?,
        cooldownMs: Long
    ): Boolean {
        require(cooldownMs > 0L)
        return previousAttemptMs == null || nowMs < previousAttemptMs ||
            nowMs - previousAttemptMs >= cooldownMs
    }

    /**
     * Applies probe results to the registry as it is now, not as it was when the
     * probe started.
     *
     * - A node deleted while the probe was running is not added back.
     * - A node whose stored record changed while the probe was running
     *   (re-pairing, token or route edit, another probe) keeps the newer stored
     *   record; the next refresh will probe it again.
     * - Nodes added while the probe was running are kept unchanged.
     */
    fun <T> mergeProbeResults(
        snapshot: List<T>,
        latest: List<T>,
        probed: List<T>,
        id: (T) -> String
    ): List<T> {
        val snapshotById = snapshot.associateBy(id)
        val probedById = probed.associateBy(id)
        return latest.map { stored ->
            val key = id(stored)
            val result = probedById[key]
            if (result != null && snapshotById[key] == stored) result else stored
        }
    }
}
