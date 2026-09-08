package com.jadegenesis.mobile

import com.jadegenesis.mobile.brain.PrototypeBrain
import com.jadegenesis.mobile.model.BrainContext
import com.jadegenesis.mobile.model.DeviceProfile
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.JadeIdentity
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.model.ResourceBudget
import com.jadegenesis.mobile.model.ResourceMode
import com.jadegenesis.mobile.model.SelfModel
import com.jadegenesis.mobile.resource.ResourceGovernor
import com.jadegenesis.mobile.selfmodel.SelfModelBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class HardeningRegressionTest {

    @Test
    fun combinedIdentityPromptKeepsIdentityPriorityAndFullJadeId() {
        val jadeId = "JG-test-12345678-1234-1234-1234-123456789abc"
        val identity = JadeIdentity(
            jadeId = jadeId,
            version = "9.9.9",
            createdAt = 1L
        )
        val prototype = PrototypeBrain()
        val selfModel = SelfModel(
            identity = identity,
            nodeId = "pixel-test-node",
            device = healthyDevice(),
            resourceBudget = balancedBudget(),
            activeBrain = prototype.info,
            knownNodes = listOf(
                GenesisNode(
                    nodeId = "pc-test-node",
                    name = "PC test",
                    kind = NodeKind.PC,
                    status = NodeStatus.ONLINE,
                    capabilities = listOf("task_execution_v3")
                )
            ),
            preferredComputeNodeId = "pc-test-node",
            capabilities = emptyList(),
            knownLimits = emptyList()
        )

        val result = runSuspend {
            prototype.think(
                BrainContext(
                    userInput = "Qui es-tu et quels nœuds connais-tu ?",
                    selfModel = selfModel,
                    memories = emptyList(),
                    tools = emptyList()
                )
            )
        }

        assertTrue(result.text.startsWith("Je suis Jade Genesis 9.9.9."))
        assertTrue(result.text.contains("Mon identité est $jadeId."))
        assertTrue(result.text.contains("Mon Node Manager connaît 1 nœud(s)."))
        assertFalse(result.text.contains("${jadeId.take(16)}…"))
    }

    @Test
    fun selfModelUsesIdentityVersionAndResearchV3Metadata() {
        val identity = JadeIdentity(
            jadeId = "JG-test-selfmodel",
            version = "9.9.9",
            createdAt = 1L
        )
        val prototype = PrototypeBrain()

        val selfModel = SelfModelBuilder().build(
            identity = identity,
            nodeId = "pixel-test-node",
            device = healthyDevice(),
            resourceBudget = balancedBudget(),
            activeBrain = prototype.info,
            knownNodes = emptyList(),
            preferredComputeNodeId = null,
            toolNames = emptyList()
        )

        val cognitiveCore = selfModel.capabilities.first { it.name == "cognitive_core" }
        val diagnostics = selfModel.capabilities.first { it.name == "diagnostics" }
        val research = selfModel.capabilities.first { it.name == "research_engine" }

        assertEquals("CognitiveCore 9.9.9", cognitiveCore.source)
        assertEquals("DiagnosticLogger 9.9.9", diagnostics.source)
        assertEquals("ResearchEngine v3 repo-first", research.source)
        assertTrue(research.details.contains("GitHub"))
        assertTrue(
            selfModel.knownLimits.any {
                it.startsWith("Le Cognitive Core 9.9.9 orchestre")
            }
        )
    }

    @Test
    fun resourceGovernorEntersCriticalModeOnVeryLowBattery() {
        val budget = ResourceGovernor().evaluate(
            healthyDevice(
                batteryPercent = 5,
                charging = false
            )
        )

        assertEquals(ResourceMode.CRITICAL, budget.mode)
        assertEquals(1, budget.maxParallelTasks)
        assertTrue(budget.preferRemoteCompute)
        assertFalse(budget.heavyBackgroundWorkAllowed)
        assertEquals(5, budget.maxTaskSliceSeconds)
    }

    @Test
    fun resourceGovernorEntersPerformanceModeOnlyWithHealthyChargingDevice() {
        val budget = ResourceGovernor().evaluate(
            healthyDevice(
                batteryPercent = 90,
                charging = true,
                ramAvailableGb = 6.0,
                ramTotalGb = 8.0,
                processHeapUsedMb = 100.0,
                processHeapMaxMb = 512.0,
                thermalStatus = "NONE",
                powerSaveMode = false,
                ramLow = false
            )
        )

        assertEquals(ResourceMode.PERFORMANCE, budget.mode)
        assertEquals(3, budget.maxParallelTasks)
        assertFalse(budget.preferRemoteCompute)
        assertTrue(budget.heavyBackgroundWorkAllowed)
        assertEquals(60, budget.maxTaskSliceSeconds)
    }

    private fun healthyDevice(
        batteryPercent: Int = 80,
        charging: Boolean = true,
        ramAvailableGb: Double = 6.0,
        ramTotalGb: Double = 8.0,
        processHeapUsedMb: Double = 100.0,
        processHeapMaxMb: Double = 512.0,
        thermalStatus: String = "NONE",
        powerSaveMode: Boolean = false,
        ramLow: Boolean = false
    ): DeviceProfile = DeviceProfile(
        manufacturer = "Google",
        model = "Pixel Test",
        device = "pixel-test",
        androidVersion = "16",
        sdkInt = 37,
        socManufacturer = "Google",
        socModel = "Tensor Test",
        abis = listOf("arm64-v8a"),
        cpuCores = 8,
        ramTotalGb = ramTotalGb,
        ramAvailableGb = ramAvailableGb,
        ramLow = ramLow,
        appMemoryClassMb = 512,
        processHeapUsedMb = processHeapUsedMb,
        processHeapMaxMb = processHeapMaxMb,
        storageTotalGb = 128.0,
        storageFreeGb = 64.0,
        batteryPercent = batteryPercent,
        charging = charging,
        powerSaveMode = powerSaveMode,
        deviceIdleMode = false,
        thermalStatus = thermalStatus,
        capturedAt = 1L
    )

    private fun balancedBudget(): ResourceBudget = ResourceBudget(
        mode = ResourceMode.BALANCED,
        reasons = listOf("test"),
        systemRamReserveGb = 2.0,
        recommendedWorkingSetMb = 128,
        maxParallelTasks = 2,
        heavyBackgroundWorkAllowed = false,
        preferRemoteCompute = false,
        maxTaskSliceSeconds = 30,
        evaluatedAt = 1L
    )

    private fun <T> runSuspend(block: suspend () -> T): T {
        var completed: Result<T>? = null

        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    completed = result
                }
            }
        )

        val outcome = completed
            ?: error("La coroutine de test ne s'est pas terminée de façon synchrone.")
        return outcome.getOrThrow()
    }
}
