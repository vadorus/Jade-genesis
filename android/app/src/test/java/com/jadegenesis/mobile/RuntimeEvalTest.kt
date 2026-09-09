package com.jadegenesis.mobile

import com.jadegenesis.mobile.config.RoutingTuning
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.eval.RuntimeEvalEngine
import com.jadegenesis.mobile.eval.RuntimeEvalObservation
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.TaskWorkload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeEvalTest {

    @Test
    fun aggregateMeasuresReliabilityLatencyFallbackAndThroughput() {
        val observations = listOf(
            observation(
                id = "1",
                success = true,
                durationMs = 100,
                tokensPerSecond = 40.0,
                fallback = false,
                createdAt = 1
            ),
            observation(
                id = "2",
                success = true,
                durationMs = 200,
                tokensPerSecond = 60.0,
                fallback = true,
                createdAt = 2
            ),
            observation(
                id = "3",
                success = false,
                durationMs = 500,
                tokensPerSecond = 0.0,
                fallback = false,
                createdAt = 3
            )
        )

        val stats = RuntimeEvalEngine.aggregate(
            observations = observations,
            nodeId = "node-a",
            taskKind = "brain_chat",
            model = "jade-model"
        ) ?: error("Stats absentes")

        assertEquals(3, stats.samples)
        assertEquals(2, stats.successes)
        assertEquals(1, stats.failures)
        assertEquals(2.0 / 3.0, stats.successRate, 0.0001)
        assertEquals(150.0, stats.averageDurationMs, 0.0001)
        assertEquals(200L, stats.p90DurationMs)
        assertEquals(1.0 / 3.0, stats.fallbackRate, 0.0001)
        assertEquals(50.0, stats.averageTokensPerSecond, 0.0001)
    }

    @Test
    fun posteriorDoesNotActBeforeEnoughEvidence() {
        val observations = (1 until SafetyPolicy.MIN_RUNTIME_EVAL_POSTERIOR_SAMPLES)
            .map { index ->
                observation(
                    id = index.toString(),
                    success = true,
                    durationMs = 20,
                    tokensPerSecond = 100.0,
                    createdAt = index.toLong()
                )
            }
        val stats = RuntimeEvalEngine.aggregate(
            observations,
            "node-a",
            "brain_chat",
            "jade-model"
        )

        assertEquals(
            0.0,
            RuntimeEvalEngine.posteriorAdjustment(stats, RoutingTuning()),
            0.0001
        )
    }

    @Test
    fun strongMeasuredPosteriorRewardsReliableNodeAndPenalizesBadNode() {
        val good = (1..SafetyPolicy.STRONG_RUNTIME_EVAL_POSTERIOR_SAMPLES)
            .map { index ->
                observation(
                    id = "good-$index",
                    success = true,
                    durationMs = 40,
                    tokensPerSecond = 70.0,
                    createdAt = index.toLong()
                )
            }
        val bad = (1..SafetyPolicy.STRONG_RUNTIME_EVAL_POSTERIOR_SAMPLES)
            .map { index ->
                observation(
                    id = "bad-$index",
                    nodeId = "node-b",
                    success = index % 3 == 0,
                    durationMs = 2_000,
                    tokensPerSecond = 3.0,
                    fallback = true,
                    createdAt = index.toLong()
                )
            }

        val goodStats = RuntimeEvalEngine.aggregate(
            good,
            "node-a",
            "brain_chat",
            "jade-model"
        )
        val badStats = RuntimeEvalEngine.aggregate(
            bad,
            "node-b",
            "brain_chat",
            "jade-model"
        )
        val tuning = RoutingTuning()
        val goodAdjustment = RuntimeEvalEngine.posteriorAdjustment(goodStats, tuning)
        val badAdjustment = RuntimeEvalEngine.posteriorAdjustment(badStats, tuning)

        assertEquals(1.0, RuntimeEvalEngine.posteriorConfidence(good.size), 0.0001)
        assertTrue(goodAdjustment > 0.0)
        assertTrue(badAdjustment < 0.0)
        assertTrue(goodAdjustment > badAdjustment)
    }

    @Test
    fun reportScoresHealthyWindowAboveFailingWindow() {
        val tuning = RoutingTuning()
        val healthy = (1..12).map { index ->
            observation(
                id = "healthy-$index",
                success = true,
                durationMs = 50,
                tokensPerSecond = 80.0,
                createdAt = index.toLong()
            )
        }
        val failing = (1..12).map { index ->
            observation(
                id = "failing-$index",
                success = false,
                durationMs = 2_000,
                tokensPerSecond = 0.0,
                fallback = true,
                createdAt = index.toLong()
            )
        }

        val healthyReport = RuntimeEvalEngine.report(healthy, tuning, generatedAt = 100)
        val failingReport = RuntimeEvalEngine.report(failing, tuning, generatedAt = 100)

        assertEquals(12, healthyReport.observationCount)
        assertEquals(1.0, healthyReport.confidence, 0.0001)
        assertTrue(healthyReport.score > failingReport.score)
        assertEquals(1.0, healthyReport.overallSuccessRate, 0.0001)
        assertEquals(0.0, failingReport.overallSuccessRate, 0.0001)
    }

    private fun observation(
        id: String,
        nodeId: String = "node-a",
        success: Boolean,
        durationMs: Long,
        tokensPerSecond: Double,
        fallback: Boolean = false,
        createdAt: Long
    ): RuntimeEvalObservation = RuntimeEvalObservation(
        observationId = id,
        taskId = "task-$id",
        taskKind = "brain_chat",
        workload = TaskWorkload.HEAVY,
        nodeId = nodeId,
        nodeName = nodeId,
        nodeKind = NodeKind.PC,
        model = "jade-model",
        success = success,
        durationMs = durationMs,
        outputChars = if (success) 100 else 0,
        tokensPerSecond = tokensPerSecond,
        fallbackUsed = fallback,
        error = if (success) null else "failure",
        createdAt = createdAt
    )
}
