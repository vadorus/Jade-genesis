package com.jadegenesis.mobile

import com.jadegenesis.mobile.model.ResourceMode
import com.jadegenesis.mobile.model.TaskWorkload
import com.jadegenesis.mobile.replay.DecisionAlternativeTrace
import com.jadegenesis.mobile.replay.DecisionTrace
import com.jadegenesis.mobile.replay.RoutingReplayLab
import com.jadegenesis.mobile.replay.selectRecentDecisionTraces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingReplayLabTest {

    private val lab = RoutingReplayLab()

    @Test
    fun aaReplayReproducesRecordedWinner() {
        val trace = trace(
            chosenNodeId = "pc-fast",
            alternatives = listOf(
                alternative(
                    id = "pixel",
                    score = 61.0,
                    ram = 4.0,
                    cores = 8
                ),
                alternative(
                    id = "pc-fast",
                    score = 92.0,
                    ram = 12.0,
                    cores = 12
                ),
                alternative(
                    id = "vps",
                    score = 74.0,
                    ram = 8.0,
                    cores = 4
                )
            )
        )

        val replay = lab.replay(trace)

        assertTrue(replay.matched)
        assertEquals("pc-fast", replay.replayedNodeId)
    }

    @Test
    fun aaReplayUsesSameRamAndCoreTieBreakers() {
        val trace = trace(
            chosenNodeId = "more-ram",
            alternatives = listOf(
                alternative(
                    id = "less-ram",
                    score = 80.0,
                    ram = 8.0,
                    cores = 16
                ),
                alternative(
                    id = "more-ram",
                    score = 80.0,
                    ram = 12.0,
                    cores = 8
                )
            )
        )

        assertEquals("more-ram", lab.replay(trace).replayedNodeId)
    }

    @Test
    fun aaReplayPreservesOriginalOrderOnFullTie() {
        val trace = trace(
            chosenNodeId = "first",
            alternatives = listOf(
                alternative(
                    id = "first",
                    score = 80.0,
                    ram = 8.0,
                    cores = 8
                ),
                alternative(
                    id = "second",
                    score = 80.0,
                    ram = 8.0,
                    cores = 8
                )
            )
        )

        assertEquals("first", lab.replay(trace).replayedNodeId)
    }

    @Test
    fun aaReplayIgnoresIneligibleAlternativeEvenWithHigherScore() {
        val trace = trace(
            chosenNodeId = "eligible",
            alternatives = listOf(
                alternative(
                    id = "blocked",
                    score = 999.0,
                    eligible = false
                ),
                alternative(
                    id = "eligible",
                    score = 20.0
                )
            )
        )

        assertEquals("eligible", lab.replay(trace).replayedNodeId)
    }

    @Test
    fun mismatchIsVisibleAndReportFailsClosed() {
        val trace = trace(
            chosenNodeId = "recorded",
            alternatives = listOf(
                alternative(
                    id = "recorded",
                    score = 10.0
                ),
                alternative(
                    id = "replay-winner",
                    score = 20.0
                )
            )
        )

        val report = lab.evaluate(listOf(trace))

        assertEquals(1, report.total)
        assertEquals(0, report.matched)
        assertEquals(1, report.mismatched)
        assertFalse(report.passed)
        assertEquals(0.0, report.matchRate, 0.0001)
    }

    @Test
    fun emptyReplayDoesNotClaimPass() {
        val report = lab.evaluate(emptyList())

        assertEquals(0, report.total)
        assertFalse(report.passed)
        assertEquals(1.0, report.matchRate, 0.0001)
    }

    @Test
    fun routingSelectionFiltersBeforeApplyingLimit() {
        val measurementTraces = (1..12).map { index ->
            trace(
                chosenNodeId = "measurement-$index",
                alternatives = listOf(
                    alternative(id = "measurement-$index", score = 10.0)
                )
            ).copy(
                traceId = "measurement-$index",
                decisionKind = "capability_measurement"
            )
        }
        val routingTraces = (1..5).map { index ->
            trace(
                chosenNodeId = "routing-$index",
                alternatives = listOf(
                    alternative(id = "routing-$index", score = 10.0)
                )
            ).copy(traceId = "routing-$index")
        }

        val selected = selectRecentDecisionTraces(
            traces = measurementTraces + routingTraces,
            limit = 5,
            decisionKind = "task_routing"
        )

        assertEquals(5, selected.size)
        assertTrue(selected.all { it.decisionKind == "task_routing" })
    }

    private fun trace(
        chosenNodeId: String?,
        alternatives: List<DecisionAlternativeTrace>
    ): DecisionTrace = DecisionTrace(
        traceId = "decision-1",
        decisionKind = "task_routing",
        taskId = "task-1",
        taskKind = "text_analysis",
        requiredCapability = "text_analysis",
        workload = TaskWorkload.LIGHT,
        configId = "builtin-1",
        configRevision = 1L,
        resourceMode = ResourceMode.BALANCED,
        preferRemoteCompute = false,
        maxParallelTasks = 2,
        alternatives = alternatives,
        chosenNodeId = chosenNodeId,
        chosenNodeName = chosenNodeId,
        routeReason = "test",
        outcomeSuccess = true,
        outcomeNodeId = chosenNodeId ?: "",
        outcomeNodeName = chosenNodeId ?: "",
        outcomeDurationMs = 10L,
        fallbackUsed = false,
        startedAt = 1L,
        completedAt = 11L
    )

    private fun alternative(
        id: String,
        score: Double?,
        eligible: Boolean = true,
        ram: Double = 8.0,
        cores: Int = 8
    ): DecisionAlternativeTrace = DecisionAlternativeTrace(
        nodeId = id,
        nodeName = id,
        nodeKind = "PC",
        nodeStatus = "ONLINE",
        eligible = eligible,
        score = score,
        cpuCores = cores,
        ramAvailableGb = ram,
        activeTaskCount = 0,
        brainReady = true,
        brainModel = "test-model",
        capabilities = listOf("task_execution_v3", "text_analysis")
    )
}
