package com.jadegenesis.mobile

import com.jadegenesis.mobile.brain.BrainBackend
import com.jadegenesis.mobile.brain.BrainRouter
import com.jadegenesis.mobile.brain.DistributedChatAvailability
import com.jadegenesis.mobile.device.ThermalStatusReader
import com.jadegenesis.mobile.model.*
import com.jadegenesis.mobile.resource.ResourceAdmissionController
import com.jadegenesis.mobile.resource.ResourceGovernor
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.*

class ChatAvailabilityRegressionTest {
    private fun remote(ready: Boolean = true, status: NodeStatus = NodeStatus.ONLINE) = GenesisNode(
        nodeId = "synthetic-home", name = "Synthetic HOME", kind = NodeKind.PC, status = status,
        capabilities = listOf("task_execution_v3", "local_brain", "brain_chat"), brainReady = ready,
        cpuCores = 8, cpuLoadPercent = 5.0, ramTotalGb = 16.0, ramAvailableGb = 8.0,
        gpuTemperatureC = -1.0, gpuUtilizationPercent = -1.0, gpuVramTotalGb = 0.0, gpuVramFreeGb = 0.0)
    private fun device() = DeviceProfile("Google", "Synthetic Pixel", "test", "16", 37, "Google", "Tensor",
        listOf("arm64-v8a"), 8, 12.0, 6.0, false, 512, 100.0, 512.0, 128.0, 64.0, 80, true,
        false, false, "UNKNOWN", 1000)
    private fun context(nodes: List<GenesisNode>): BrainContext {
        val profile = device()
        return BrainContext("Synthetic routing test", SelfModel(JadeIdentity(jadeId = "synthetic", version = "test", createdAt = 1000), "synthetic-pixel",
            profile, ResourceGovernor().evaluate(profile), backend(BrainBackendType.PROTOTYPE).info, nodes, null, emptyList(), emptyList()), emptyList(), emptyList())
    }
    private fun backend(type: BrainBackendType, error: Boolean = false): BrainBackend = object : BrainBackend {
        override val info = BrainInfo(type.name, type.name, type, if (type == BrainBackendType.PROTOTYPE) "phone" else "node",
            BrainResourceClass.MINIMAL, type != BrainBackendType.PROTOTYPE, false, true, 1)
        override fun availableFor(nodes: List<GenesisNode>) = type == BrainBackendType.PROTOTYPE || DistributedChatAvailability.reason(nodes) == null
        override fun unavailableReason(nodes: List<GenesisNode>) = if (type == BrainBackendType.PROTOTYPE) null else DistributedChatAvailability.reason(nodes)
        override suspend fun think(context: BrainContext): BrainResult {
            if (error) throw IllegalStateException("Synthetic transport failure")
            return BrainResult(if (type == BrainBackendType.PROTOTYPE) "Limited rule response" else "Synthetic generative response")
        }
    }
    private fun <T> sync(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> { override val context = EmptyCoroutineContext
            override fun resumeWith(value: Result<T>) { result = value } })
        return (result ?: error("Unexpected asynchronous test")).getOrThrow()
    }
    @Test fun missingGpuTemperatureAndPhoneThermalSourceDoNotBlockRemoteChat() {
        val node = remote(); val context = context(listOf(node))
        assertNull(DistributedChatAvailability.reason(listOf(node)))
        val request = DistributedTaskRequest("synthetic", "brain_chat", "Synthetic prompt", "brain_chat", TaskWorkload.MEDIUM, createdAt = 1000)
        val decision = ResourceAdmissionController(leaseStore = mutableMapOf(), leaseLock = Any()).tryAcquire(request, node, context.selfModel.resourceBudget)
        assertTrue(decision.admitted)
        val result = sync { BrainRouter(listOf(backend(BrainBackendType.LOCAL_NODE), backend(BrainBackendType.PROTOTYPE))).think(context) }
        assertFalse(result.fallbackUsed); assertEquals("Synthetic generative response", result.text)
    }
    @Test fun missingAndOfflineNodesHaveDifferentActionableReasons() {
        assertTrue(DistributedChatAvailability.reason(emptyList())!!.contains("configuré"))
        assertTrue(DistributedChatAvailability.reason(listOf(remote(status = NodeStatus.OFFLINE)))!!.contains("hors ligne"))
    }
    @Test fun reachableNodeWithoutReadyModelDoesNotClaimChatReadiness() {
        assertTrue(DistributedChatAvailability.reason(listOf(remote(ready = false)))!!.contains("pas prêt"))
        val result = sync { BrainRouter(listOf(backend(BrainBackendType.LOCAL_NODE), backend(BrainBackendType.PROTOTYPE))).think(context(listOf(remote(ready = false)))) }
        assertTrue(result.fallbackUsed); assertTrue(result.fallbackReason!!.contains("pas prêt"))
    }
    @Test fun directPrototypeSelectionIsAlwaysRecordedAsFallback() {
        val result = sync { BrainRouter(listOf(backend(BrainBackendType.LOCAL_NODE), backend(BrainBackendType.PROTOTYPE))).think(context(emptyList())) }
        assertTrue(result.fallbackUsed); assertTrue(result.fallbackReason!!.contains("configuré"))
    }
    @Test fun realBackendFailureRetainsItsCauseWithoutClaimingThermalFailure() {
        val result = sync { BrainRouter(listOf(backend(BrainBackendType.LOCAL_NODE, error = true), backend(BrainBackendType.PROTOTYPE))).think(context(listOf(remote()))) }
        assertTrue(result.fallbackUsed); assertEquals("Synthetic transport failure", result.fallbackReason)
    }
    @Test fun validationAppExplainsItsIntentionalOfflineBoundary() {
        assertTrue(DistributedChatAvailability.reason(listOf(remote()), validationOffline = true)!!.contains("hors réseau"))
    }
    @Test fun failedThermalApiRemainsUnknownAndRealCriticalStatusIsPreserved() {
        assertEquals("UNKNOWN", ThermalStatusReader.read { throw SecurityException("Synthetic missing sensor") })
        assertEquals("UNKNOWN", ThermalStatusReader.read { throw IllegalStateException("Synthetic service unavailable") })
        assertEquals("NONE", ThermalStatusReader.read { 0 })
        assertEquals("CRITICAL", ThermalStatusReader.read { 4 })
    }
}
