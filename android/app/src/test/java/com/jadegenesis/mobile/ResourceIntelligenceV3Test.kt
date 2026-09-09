package com.jadegenesis.mobile

import com.jadegenesis.mobile.config.JadeConfig
import com.jadegenesis.mobile.config.RoutingTuning
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.resource.NodeResourceScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceIntelligenceV3Test {

    @Test
    fun measuredGenerativePerformanceBeatsRamOnlyGuess() {
        val routing = RoutingTuning()
        val measuredGpuNode = node(
            id = "gpu",
            ramAvailableGb = 8.0,
            cpuCores = 8,
            gpuName = "NVIDIA GPU",
            gpuVramTotalGb = 12.0,
            gpuVramFreeGb = 9.0,
            gpuUtilizationPercent = 20.0,
            brainReady = true,
            brainLoaded = true,
            brainTokensPerSecond = 42.0
        )
        val ramHeavyCpuNode = node(
            id = "ram",
            ramAvailableGb = 24.0,
            cpuCores = 16,
            brainReady = true
        )

        val measuredScore = NodeResourceScorer.generativeScore(
            measuredGpuNode,
            routing
        )
        val guessedScore = NodeResourceScorer.generativeScore(
            ramHeavyCpuNode,
            routing
        )

        assertTrue(
            "Une performance réellement mesurée doit pouvoir battre une estimation RAM/CPU.",
            measuredScore > guessedScore
        )
    }

    @Test
    fun cpuPressureAndActiveTasksReduceSafeCapacityScore() {
        val routing = RoutingTuning()
        val idle = node(
            id = "idle",
            cpuLoadPercent = 10.0,
            activeTaskCount = 0
        )
        val busy = node(
            id = "busy",
            cpuLoadPercent = 95.0,
            activeTaskCount = 3
        )

        assertTrue(
            NodeResourceScorer.genericScore(idle, routing) >
                NodeResourceScorer.genericScore(busy, routing)
        )
    }

    @Test
    fun unknownDynamicTelemetryDoesNotInventPressure() {
        val routing = RoutingTuning()
        val unknown = node(
            id = "unknown",
            cpuLoadPercent = -1.0,
            gpuUtilizationPercent = -1.0
        )
        val baseline = node(
            id = "baseline",
            cpuLoadPercent = -1.0,
            gpuUtilizationPercent = -1.0
        )

        assertEquals(
            NodeResourceScorer.genericScore(baseline, routing),
            NodeResourceScorer.genericScore(unknown, routing),
            0.0001
        )
    }

    @Test
    fun routingTelemetryWeightsRoundTripThroughJadeConfig() {
        val defaults = JadeConfig.defaults()
        val custom = defaults.copy(
            routing = defaults.routing.copy(
                cpuHeadroomPercentWeight = 0.31,
                activeTaskPenalty = 11.0,
                gpuVramFreeGbWeight = 7.5,
                gpuHeadroomPercentWeight = 0.12,
                brainReadyBonus = 14.0,
                brainLoadedBonus = 31.0,
                brainTokensPerSecondWeight = 1.7,
                preferredNodeHintBonus = 4.0
            )
        ).validated()

        val restored = JadeConfig.fromJson(custom.toJson())

        assertEquals(0.31, restored.routing.cpuHeadroomPercentWeight, 0.0001)
        assertEquals(11.0, restored.routing.activeTaskPenalty, 0.0001)
        assertEquals(7.5, restored.routing.gpuVramFreeGbWeight, 0.0001)
        assertEquals(0.12, restored.routing.gpuHeadroomPercentWeight, 0.0001)
        assertEquals(14.0, restored.routing.brainReadyBonus, 0.0001)
        assertEquals(31.0, restored.routing.brainLoadedBonus, 0.0001)
        assertEquals(1.7, restored.routing.brainTokensPerSecondWeight, 0.0001)
        assertEquals(4.0, restored.routing.preferredNodeHintBonus, 0.0001)
    }

    private fun node(
        id: String,
        ramAvailableGb: Double = 8.0,
        cpuCores: Int = 8,
        cpuLoadPercent: Double = -1.0,
        gpuName: String = "",
        gpuVramTotalGb: Double = 0.0,
        gpuVramFreeGb: Double = 0.0,
        gpuUtilizationPercent: Double = -1.0,
        activeTaskCount: Int = 0,
        brainReady: Boolean = false,
        brainLoaded: Boolean = false,
        brainTokensPerSecond: Double = 0.0
    ): GenesisNode = GenesisNode(
        nodeId = id,
        name = id,
        kind = NodeKind.PC,
        status = NodeStatus.ONLINE,
        cpuCores = cpuCores,
        cpuLoadPercent = cpuLoadPercent,
        ramTotalGb = 32.0,
        ramAvailableGb = ramAvailableGb,
        storageFreeGb = 100.0,
        gpuName = gpuName,
        gpuVramTotalGb = gpuVramTotalGb,
        gpuVramFreeGb = gpuVramFreeGb,
        gpuUtilizationPercent = gpuUtilizationPercent,
        activeTaskCount = activeTaskCount,
        brainReady = brainReady,
        brainLoaded = brainLoaded,
        brainTokensPerSecond = brainTokensPerSecond,
        capabilities = listOf("task_execution_v3", "brain_chat", "local_brain")
    )
}
