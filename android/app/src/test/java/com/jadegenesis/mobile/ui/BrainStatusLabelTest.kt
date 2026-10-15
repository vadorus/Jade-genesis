package com.jadegenesis.mobile.ui

import com.jadegenesis.mobile.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class BrainStatusLabelTest {
    private val prototype = BrainInfo("prototype", "Prototype Brain — secours",
        BrainBackendType.PROTOTYPE, "phone", BrainResourceClass.MINIMAL,
        false, false, true, 1)
    private val stale = GenesisNode("pc", "PC", NodeKind.PC, NodeStatus.UNKNOWN,
        lastSeenAt = 1L, capabilities = listOf("task_execution_v3", "local_brain", "brain_chat"))

    @Test
    fun staleRemoteBrainDoesNotClaimFallbackIsActive() {
        assertEquals("Cerveau distant — état à rafraîchir", brainStatusLabel(prototype, listOf(stale)))
    }

    @Test
    fun offlineOrNeverObservedNodesKeepFallbackLabel() {
        assertEquals(prototype.displayName, brainStatusLabel(prototype, listOf(stale.copy(status = NodeStatus.OFFLINE))))
        assertEquals(prototype.displayName, brainStatusLabel(prototype, listOf(stale.copy(lastSeenAt = 0))))
        assertEquals(prototype.displayName, brainStatusLabel(prototype, listOf(stale.copy(capabilities = emptyList()))))
    }

    @Test
    fun selectedRemoteBrainKeepsItsName() {
        val remote = prototype.copy(backendType = BrainBackendType.LOCAL_NODE, displayName = "Mistral PC")
        assertEquals("Mistral PC", brainStatusLabel(remote, listOf(stale)))
        assertEquals("inconnu", brainStatusLabel(null, emptyList()))
    }
}
