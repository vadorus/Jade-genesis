package com.jadegenesis.mobile.node

import com.jadegenesis.mobile.model.NodeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

class NodeRefreshPolicyTest {

    private data class Node(val id: String, val token: String, val status: String)

    @Test
    fun noRetryWhenAnyNodeIsOnline() {
        val statuses = listOf("vps" to NodeStatus.ONLINE, "pc" to NodeStatus.OFFLINE)
        assertTrue(NodeRefreshPolicy.nodesToRetry(statuses).isEmpty())
    }

    @Test
    fun retriesOfflineNodesWhenNothingAnswered() {
        val statuses = listOf("vps" to NodeStatus.OFFLINE, "pc" to NodeStatus.OFFLINE)
        assertEquals(setOf("vps", "pc"), NodeRefreshPolicy.nodesToRetry(statuses))
    }

    @Test
    fun errorNodeDoesNotBlockRetryOfOfflineNodes() {
        // Old duplicate node with a refused token must not disable the retry.
        val statuses = listOf(
            "old-home" to NodeStatus.ERROR,
            "vps" to NodeStatus.OFFLINE,
            "pc" to NodeStatus.OFFLINE
        )
        assertEquals(setOf("vps", "pc"), NodeRefreshPolicy.nodesToRetry(statuses))
    }

    @Test
    fun errorNodesAreNeverRetried() {
        val statuses = listOf("a" to NodeStatus.ERROR, "b" to NodeStatus.ERROR)
        assertTrue(NodeRefreshPolicy.nodesToRetry(statuses).isEmpty())
    }

    @Test
    fun noNodesNoRetry() {
        assertTrue(NodeRefreshPolicy.nodesToRetry(emptyList()).isEmpty())
    }

    @Test
    fun probeResultAppliedWhenStoredNodeUnchanged() {
        val stored = Node("vps", "t1", "UNKNOWN")
        val probed = Node("vps", "t1", "ONLINE")
        val merged = NodeRefreshPolicy.mergeProbeResults(
            snapshot = listOf(stored), latest = listOf(stored), probed = listOf(probed)
        ) { it.id }
        assertEquals(listOf(probed), merged)
    }

    @Test
    fun nodeDeletedDuringProbeIsNotAddedBack() {
        val stored = Node("pc", "t1", "UNKNOWN")
        val merged = NodeRefreshPolicy.mergeProbeResults(
            snapshot = listOf(stored),
            latest = emptyList(),
            probed = listOf(Node("pc", "t1", "ONLINE"))
        ) { it.id }
        assertTrue(merged.isEmpty())
    }

    @Test
    fun repairedNodeIsNotOverwrittenByStaleProbe() {
        val before = Node("pc", "old-token", "UNKNOWN")
        val repaired = Node("pc", "new-token", "UNKNOWN")
        val merged = NodeRefreshPolicy.mergeProbeResults(
            snapshot = listOf(before),
            latest = listOf(repaired),
            probed = listOf(Node("pc", "old-token", "ONLINE"))
        ) { it.id }
        assertEquals(listOf(repaired), merged)
    }

    @Test
    fun nodeAddedDuringProbeIsKept() {
        val vps = Node("vps", "t1", "UNKNOWN")
        val added = Node("pc", "t2", "UNKNOWN")
        val probedVps = Node("vps", "t1", "ONLINE")
        val merged = NodeRefreshPolicy.mergeProbeResults(
            snapshot = listOf(vps), latest = listOf(vps, added), probed = listOf(probedVps)
        ) { it.id }
        assertEquals(listOf(probedVps, added), merged)
    }

    @Test
    fun patientRetryRunsOnFirstRefresh() {
        assertTrue(NodeRefreshPolicy.shouldRunPatientRetry(1000L, null, 60_000L))
    }

    @Test
    fun patientRetrySkippedWithinSixtySeconds() {
        assertEquals(false, NodeRefreshPolicy.shouldRunPatientRetry(59_999L, 0L, 60_000L))
    }

    @Test
    fun patientRetryAllowedAtSixtySeconds() {
        assertTrue(NodeRefreshPolicy.shouldRunPatientRetry(60_000L, 0L, 60_000L))
    }

    @Test
    fun simultaneousDistinctCallersReuseOneProbe() = runBlocking {
        val coordinator = NodeRefreshCoordinator()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val probes = AtomicInteger(0)
        var storedResult = -1

        val callerOne = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.refreshOrReuse(
                refresh = {
                    probes.incrementAndGet()
                    started.complete(Unit)
                    release.await()
                    storedResult = 42
                    storedResult
                },
                reuse = { storedResult }
            )
        }
        started.await()
        val callerTwo = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.refreshOrReuse(
                refresh = { probes.incrementAndGet(); -2 },
                reuse = { storedResult }
            )
        }
        release.complete(Unit)

        assertEquals(42, callerOne.await())
        assertEquals(42, callerTwo.await())
        assertEquals(1, probes.get())
    }

    @Test
    fun requestAfterCompletedRefreshStartsNewProbe() = runBlocking {
        val coordinator = NodeRefreshCoordinator()
        val probes = AtomicInteger(0)
        val first = coordinator.refreshOrReuse(
            refresh = { probes.incrementAndGet() },
            reuse = { -1 }
        )
        val second = coordinator.refreshOrReuse(
            refresh = { probes.incrementAndGet() },
            reuse = { -1 }
        )
        assertEquals(1, first)
        assertEquals(2, second)
        assertEquals(2, probes.get())
    }

    @Test
    fun failedRefreshDoesNotSuppressNextProbe() = runBlocking {
        val coordinator = NodeRefreshCoordinator()
        val probes = AtomicInteger(0)
        try {
            coordinator.refreshOrReuse(
                refresh = { probes.incrementAndGet(); error("synthetic failure") },
                reuse = { -1 }
            )
        } catch (_: IllegalStateException) {
            // A failed refresh must not be marked completed.
        }
        assertEquals(2, coordinator.refreshOrReuse(
            refresh = { probes.incrementAndGet() },
            reuse = { -1 }
        ))
    }

    // --- "à vérifier" after a loss of every physical network (0.1.26) ----------

    @Test
    fun onlineNodeSeenBeforeTheLossIsStale() {
        assertTrue(NodeRefreshPolicy.isStale(
            online = true, lastSeenAtMs = 1_000L, nowMs = 2_000L,
            staleAfterMs = 90_000L, networkLostAtMs = 1_500L
        ))
    }

    @Test
    fun probeAfterTheLossMakesTheNodeFreshAgain() {
        assertFalse(NodeRefreshPolicy.isStale(
            online = true, lastSeenAtMs = 3_000L, nowMs = 4_000L,
            staleAfterMs = 90_000L, networkLostAtMs = 1_500L
        ))
    }

    @Test
    fun withoutLossTheNinetySecondRuleIsUnchanged() {
        assertFalse(NodeRefreshPolicy.isStale(true, 1_000L, 91_000L, 90_000L, 0L))
        assertTrue(NodeRefreshPolicy.isStale(true, 1_000L, 91_001L, 90_000L, 0L))
    }

    @Test
    fun nonOnlineOrNeverSeenNodesKeepTheirStatus() {
        assertFalse(NodeRefreshPolicy.isStale(false, 1_000L, 2_000L, 90_000L, 1_500L))
        assertFalse(NodeRefreshPolicy.isStale(true, 0L, 2_000L, 90_000L, 1_500L))
    }
}
