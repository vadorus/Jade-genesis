package com.jadegenesis.mobile

import com.jadegenesis.mobile.model.DistributedTaskRequest
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.model.ResourceBudget
import com.jadegenesis.mobile.model.ResourceMode
import com.jadegenesis.mobile.model.TaskWorkload
import com.jadegenesis.mobile.resource.ResourceAdmissionAction
import com.jadegenesis.mobile.resource.ResourceAdmissionController
import com.jadegenesis.mobile.resource.ResourceLease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceAdmissionControllerTest {

    @Test
    fun localHeavyTaskIsDeferredWhenGovernorDisallowsHeavyWork() {
        val controller = isolatedController()
        val decision = controller.tryAcquire(
            request = request(TaskWorkload.HEAVY),
            node = localNode(),
            budget = budget(
                workingSetMb = 256,
                maxParallelTasks = 2,
                heavyAllowed = false
            )
        )

        assertEquals(ResourceAdmissionAction.DEFER, decision.action)
        assertFalse(decision.admitted)
    }

    @Test
    fun activeLeaseActuallyConsumesLocalParallelCapacity() {
        val controller = isolatedController()
        val node = localNode()
        val budget = budget(
            workingSetMb = 64,
            maxParallelTasks = 1,
            heavyAllowed = false
        )

        val first = controller.tryAcquire(
            request = request(TaskWorkload.LIGHT, id = "first"),
            node = node,
            budget = budget
        )
        assertEquals(ResourceAdmissionAction.LOCAL, first.action)
        assertTrue(first.admitted)
        assertNotNull(first.lease)

        val second = controller.tryAcquire(
            request = request(TaskWorkload.LIGHT, id = "second"),
            node = node,
            budget = budget
        )
        assertEquals(ResourceAdmissionAction.DEFER, second.action)

        controller.release(first.lease)
        val retry = controller.tryAcquire(
            request = request(TaskWorkload.LIGHT, id = "retry"),
            node = node,
            budget = budget
        )
        assertEquals(ResourceAdmissionAction.LOCAL, retry.action)
        assertTrue(retry.admitted)
    }

    @Test
    fun remoteCpuProjectionCanDeferMediumTask() {
        val controller = isolatedController()
        val decision = controller.tryAcquire(
            request = request(TaskWorkload.MEDIUM),
            node = remoteNode(
                cpuLoadPercent = 80.0,
                ramAvailableGb = 8.0
            ),
            budget = budget()
        )

        assertEquals(ResourceAdmissionAction.DEFER, decision.action)
        assertTrue(decision.reason.contains("CPU"))
    }

    @Test
    fun unloadedBrainRequiresEnoughKnownVram() {
        val controller = isolatedController()
        val brainRequest = DistributedTaskRequest(
            taskId = "brain-test",
            taskKind = "brain_chat",
            payload = "bonjour",
            requiredCapability = "brain_chat",
            workload = TaskWorkload.HEAVY,
            createdAt = 1L
        )
        val node = remoteNode(
            gpuVramTotalGb = 8.0,
            gpuVramFreeGb = 4.0,
            brainReady = true,
            brainLoaded = false,
            brainModelVramGb = 6.0,
            capabilities = listOf(
                "task_execution_v3",
                "brain_chat",
                "local_brain"
            )
        )

        val decision = controller.tryAcquire(
            request = brainRequest,
            node = node,
            budget = budget()
        )

        assertEquals(ResourceAdmissionAction.DEFER, decision.action)
        assertTrue(decision.reason.contains("VRAM"))
    }

    @Test
    fun unknownRemoteTelemetryDoesNotInventPressure() {
        val controller = isolatedController()
        val decision = controller.tryAcquire(
            request = request(TaskWorkload.LIGHT),
            node = remoteNode(
                cpuCores = 0,
                cpuLoadPercent = -1.0,
                ramAvailableGb = 0.0,
                ramTotalGb = 0.0
            ),
            budget = budget()
        )

        assertEquals(ResourceAdmissionAction.REMOTE, decision.action)
        assertTrue(decision.admitted)
    }

    private fun isolatedController(): ResourceAdmissionController =
        ResourceAdmissionController(
            clock = { 10_000L },
            leaseStore = linkedMapOf<String, ResourceLease>(),
            leaseLock = Any()
        )

    private fun request(
        workload: TaskWorkload,
        id: String = "task"
    ) = DistributedTaskRequest(
        taskId = id,
        taskKind = "genesis_probe",
        payload = "payload",
        requiredCapability = "genesis_probe",
        workload = workload,
        iterations = 1,
        createdAt = 1L
    )

    private fun localNode() = GenesisNode(
        nodeId = "phone",
        name = "Pixel",
        kind = NodeKind.PHONE,
        status = NodeStatus.LOCAL,
        cpuCores = 8,
        ramTotalGb = 12.0,
        ramAvailableGb = 4.0,
        capabilities = listOf(
            "task_execution_v3",
            "genesis_probe",
            "text_analysis",
            "memory_consolidation"
        )
    )

    private fun remoteNode(
        cpuCores: Int = 8,
        cpuLoadPercent: Double = -1.0,
        ramTotalGb: Double = 16.0,
        ramAvailableGb: Double = 8.0,
        gpuVramTotalGb: Double = 0.0,
        gpuVramFreeGb: Double = 0.0,
        brainReady: Boolean = false,
        brainLoaded: Boolean = false,
        brainModelVramGb: Double = 0.0,
        capabilities: List<String> = listOf(
            "task_execution_v3",
            "genesis_probe"
        )
    ) = GenesisNode(
        nodeId = "remote",
        name = "Remote",
        kind = NodeKind.PC,
        status = NodeStatus.ONLINE,
        cpuCores = cpuCores,
        cpuLoadPercent = cpuLoadPercent,
        ramTotalGb = ramTotalGb,
        ramAvailableGb = ramAvailableGb,
        gpuVramTotalGb = gpuVramTotalGb,
        gpuVramFreeGb = gpuVramFreeGb,
        brainReady = brainReady,
        brainLoaded = brainLoaded,
        brainModelVramGb = brainModelVramGb,
        capabilities = capabilities,
        lastSeenAt = 9_000L
    )

    private fun budget(
        workingSetMb: Int = 128,
        maxParallelTasks: Int = 2,
        heavyAllowed: Boolean = true
    ) = ResourceBudget(
        mode = ResourceMode.BALANCED,
        reasons = listOf("test"),
        systemRamReserveGb = 1.0,
        recommendedWorkingSetMb = workingSetMb,
        maxParallelTasks = maxParallelTasks,
        heavyBackgroundWorkAllowed = heavyAllowed,
        preferRemoteCompute = false,
        maxTaskSliceSeconds = 30,
        evaluatedAt = 1L
    )
}
