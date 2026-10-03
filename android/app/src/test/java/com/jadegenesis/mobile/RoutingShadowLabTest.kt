package com.jadegenesis.mobile

import com.jadegenesis.mobile.config.RoutingTuning
import com.jadegenesis.mobile.model.ResourceMode
import com.jadegenesis.mobile.model.TaskWorkload
import com.jadegenesis.mobile.replay.DecisionAlternativeTrace
import com.jadegenesis.mobile.replay.DecisionTrace
import com.jadegenesis.mobile.replay.DecisionTraceCodec
import com.jadegenesis.mobile.replay.RoutingShadowLab
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingShadowLabTest {
    private val lab = RoutingShadowLab()

    @Test
    fun oldTraceIsRefusedAsExactExperimentContext() {
        val oldJson = JSONObject(
            DecisionTraceCodec.toJson(trace(scoringContextVersion = 0)).toString()
        )
        oldJson.remove("scoring_context_version")

        val restored = DecisionTraceCodec.fromJson(oldJson)
        val comparison = lab.compare(restored, neutralRouting(), neutralRouting())

        assertFalse(comparison.exactContext)
        assertFalse(comparison.championScoresReproduced)
        assertFalse(comparison.championReproduced)
    }

    @Test
    fun championReplayReproducesRecordedWinner() {
        val champion = neutralRouting().copy(
            cpuCoreWeight = 1.0,
            ramAvailableGbWeight = 10.0
        )
        val comparison = lab.compare(
            trace = trace(scoringContextVersion = 1),
            champion = champion,
            challenger = champion
        )

        assertTrue(comparison.exactContext)
        assertTrue(comparison.championScoresReproduced)
        assertTrue(comparison.championReproduced)
        assertEquals("phone", comparison.replayedChampionNodeId)
        assertEquals("phone", comparison.challengerNodeId)
        assertFalse(comparison.challengerChangedDecision)
    }

    @Test
    fun scoreMismatchRefusesCounterfactualExperiment() {
        val champion = neutralRouting().copy(
            cpuCoreWeight = 1.0,
            ramAvailableGbWeight = 10.0
        )
        val source = trace(scoringContextVersion = 1)
        val corrupted = source.copy(
            alternatives = source.alternatives.mapIndexed { index, alternative ->
                if (index == 0) alternative.copy(score = 83.0) else alternative
            }
        )
        val comparison = lab.compare(corrupted, champion, champion)

        assertFalse(comparison.championScoresReproduced)
        assertFalse(comparison.championReproduced)
        assertFalse(comparison.challengerChangedDecision)
    }

    @Test
    fun challengerCanChangeDecisionWithoutExecutingTask() {
        val champion = neutralRouting().copy(
            cpuCoreWeight = 1.0,
            ramAvailableGbWeight = 10.0
        )
        val challenger = champion.copy(
            cpuCoreWeight = 10.0,
            ramAvailableGbWeight = 1.0
        )

        val comparison = lab.compare(
            trace = trace(scoringContextVersion = 1),
            champion = champion,
            challenger = challenger
        )

        assertTrue(comparison.championScoresReproduced)
        assertTrue(comparison.championReproduced)
        assertTrue(comparison.challengerChangedDecision)
        assertEquals("pc", comparison.challengerNodeId)
    }

    @Test
    fun codecPreservesExactScoringInputs() {
        val source = trace(scoringContextVersion = 1)
        val restored = DecisionTraceCodec.fromJson(
            DecisionTraceCodec.toJson(source)
        )
        val phone = restored.alternatives.first { it.nodeId == "phone" }

        assertEquals(1, restored.scoringContextVersion)
        assertEquals(64.0, phone.storageFreeGb, 0.0001)
        assertEquals(5, phone.historyAttempts)
        assertEquals(5, phone.historySuccesses)
        assertEquals(30.0, phone.averageDurationMs ?: -1.0, 0.0001)
    }

    private fun trace(scoringContextVersion: Int): DecisionTrace = DecisionTrace(
        traceId = "trace-1",
        decisionKind = "task_routing",
        taskId = "task-1",
        taskKind = "text_analysis",
        requiredCapability = "text_analysis",
        workload = TaskWorkload.LIGHT,
        configId = "champion",
        configRevision = 1L,
        resourceMode = ResourceMode.BALANCED,
        preferRemoteCompute = false,
        maxParallelTasks = 2,
        alternatives = listOf(
            alternative(
                nodeId = "phone",
                nodeKind = "PHONE",
                cpuCores = 4,
                ramAvailableGb = 8.0,
                score = 84.0
            ),
            alternative(
                nodeId = "pc",
                nodeKind = "PC",
                cpuCores = 8,
                ramAvailableGb = 4.0,
                score = 48.0
            )
        ),
        chosenNodeId = "phone",
        chosenNodeName = "phone",
        routeReason = "test",
        outcomeSuccess = true,
        outcomeNodeId = "phone",
        outcomeNodeName = "phone",
        outcomeDurationMs = 30L,
        fallbackUsed = false,
        startedAt = 10L,
        completedAt = 40L,
        scoringContextVersion = scoringContextVersion
    )

    private fun alternative(
        nodeId: String,
        nodeKind: String,
        cpuCores: Int,
        ramAvailableGb: Double,
        score: Double
    ): DecisionAlternativeTrace = DecisionAlternativeTrace(
        nodeId = nodeId,
        nodeName = nodeId,
        nodeKind = nodeKind,
        nodeStatus = "ONLINE",
        eligible = true,
        score = score,
        cpuCores = cpuCores,
        ramAvailableGb = ramAvailableGb,
        storageFreeGb = 64.0,
        activeTaskCount = 0,
        brainReady = false,
        brainModel = "",
        capabilities = listOf("task_execution_v3", "text_analysis"),
        historyAttempts = 5,
        historySuccesses = 5,
        averageDurationMs = 30.0
    )

    private fun neutralRouting(): RoutingTuning = RoutingTuning().copy(
        cpuCoreWeight = 0.0,
        ramAvailableGbWeight = 0.0,
        storageFreeGbWeight = 0.0,
        preferRemoteRemoteBonus = 0.0,
        preferRemoteLocalPenalty = 0.0,
        localAllowedLocalBonus = 0.0,
        localAllowedRemoteBonus = 0.0,
        lightLocalBonus = 0.0,
        lightRemoteBonus = 0.0,
        mediumLocalBonus = 0.0,
        mediumRemoteBonus = 0.0,
        heavyLocalBonus = 0.0,
        heavyRemoteBonus = 0.0,
        consolidationRemoteBonus = 0.0,
        historySuccessRateWeight = 0.0,
        historyFailurePenalty = 0.0,
        historyDurationBonus = 0.0
    )
}
