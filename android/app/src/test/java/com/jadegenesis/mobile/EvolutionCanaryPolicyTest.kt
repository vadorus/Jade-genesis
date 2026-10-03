package com.jadegenesis.mobile

import com.jadegenesis.mobile.evolution.EvolutionCanaryDecision
import com.jadegenesis.mobile.evolution.EvolutionCanaryPolicy
import com.jadegenesis.mobile.eval.RuntimeEvalObservation
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.TaskWorkload
import org.junit.Assert.assertEquals
import org.junit.Test

class EvolutionCanaryPolicyTest {

    @Test
    fun smallHealthySampleContinues() {
        val baseline = observations("base", 3, success = true, durationMs = 100)
        val challenger = observations("challenger", 3, success = true, durationMs = 90)

        val result = EvolutionCanaryPolicy.assess(baseline, challenger)

        assertEquals(EvolutionCanaryDecision.CONTINUE, result.decision)
        assertEquals(3, result.pairCount)
    }

    @Test
    fun oneHardReliabilityRegressionStopsImmediately() {
        val baseline = observations("base", 2, success = true, durationMs = 100)
        val challenger = listOf(
            observation("challenger-1", true, 90),
            observation("challenger-2", false, 90)
        )

        val result = EvolutionCanaryPolicy.assess(baseline, challenger)

        assertEquals(EvolutionCanaryDecision.STOP_REGRESSION, result.decision)
    }

    @Test
    fun largeLatencyRegressionStopsAfterEarlyGate() {
        val baseline = observations("base", 4, success = true, durationMs = 100)
        val challenger = observations("challenger", 4, success = true, durationMs = 250)

        val result = EvolutionCanaryPolicy.assess(baseline, challenger)

        assertEquals(EvolutionCanaryDecision.STOP_REGRESSION, result.decision)
    }

    @Test
    fun strongEvidenceCompletesAtTwentyFourPairs() {
        val baseline = observations("base", 24, success = true, durationMs = 100)
        val challenger = observations("challenger", 24, success = true, durationMs = 80)

        val result = EvolutionCanaryPolicy.assess(baseline, challenger)

        assertEquals(EvolutionCanaryDecision.COMPLETE, result.decision)
        assertEquals(24, result.pairCount)
    }

    @Test
    fun mismatchedScenariosAreRejected() {
        val baseline = observations("base", 4, success = true, durationMs = 100)
        val challenger = observations(
            "challenger", 4, success = true, durationMs = 100,
            workload = TaskWorkload.LIGHT
        )

        val result = EvolutionCanaryPolicy.assess(baseline, challenger)

        assertEquals(
            EvolutionCanaryDecision.ABORT_INVALID_EVIDENCE,
            result.decision
        )
    }

    private fun observations(
        prefix: String,
        count: Int,
        success: Boolean,
        durationMs: Long,
        workload: TaskWorkload = TaskWorkload.MEDIUM
    ): List<RuntimeEvalObservation> = (1..count).map { index ->
        observation(
            id = "$prefix-$index",
            success = success,
            durationMs = durationMs,
            workload = workload
        )
    }

    private fun observation(
        id: String,
        success: Boolean,
        durationMs: Long,
        workload: TaskWorkload = TaskWorkload.MEDIUM
    ): RuntimeEvalObservation = RuntimeEvalObservation(
        observationId = id,
        taskId = "task-$id",
        taskKind = "genesis_probe",
        workload = workload,
        nodeId = "node-$id",
        nodeName = "node-$id",
        nodeKind = NodeKind.UNKNOWN,
        success = success,
        durationMs = durationMs,
        createdAt = 1_000L
    )
}
