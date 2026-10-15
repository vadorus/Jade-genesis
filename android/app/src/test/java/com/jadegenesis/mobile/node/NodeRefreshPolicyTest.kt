package com.jadegenesis.mobile.node

import com.jadegenesis.mobile.model.NodeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
