package com.jadegenesis.mobile

import com.jadegenesis.mobile.brain.AdaptiveBrainRouting
import com.jadegenesis.mobile.brain.CognitiveBrainProfile
import com.jadegenesis.mobile.config.RoutingTuning
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.eval.RuntimeEvalEngine
import com.jadegenesis.mobile.eval.RuntimeEvalObservation
import com.jadegenesis.mobile.eval.RuntimeOutcomeFeedback
import com.jadegenesis.mobile.eval.RuntimeOutcomeKind
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
    fun aggregateKeepsCognitiveProfilesSeparated() {
        val observations = listOf(
            observation(
                id = "fast-1",
                success = true,
                durationMs = 40,
                tokensPerSecond = 90.0,
                brainProfile = "fast",
                createdAt = 1
            ),
            observation(
                id = "reasoning-1",
                success = false,
                durationMs = 4_000,
                tokensPerSecond = 3.0,
                brainProfile = "reasoning",
                createdAt = 2
            )
        )

        val fast = RuntimeEvalEngine.aggregate(
            observations = observations,
            nodeId = "node-a",
            taskKind = "brain_chat",
            brainProfile = "FAST"
        ) ?: error("Stats FAST absentes")
        val reasoning = RuntimeEvalEngine.aggregate(
            observations = observations,
            nodeId = "node-a",
            taskKind = "brain_chat",
            brainProfile = "reasoning"
        ) ?: error("Stats REASONING absentes")

        assertEquals(1, fast.samples)
        assertEquals(1.0, fast.successRate, 0.0001)
        assertEquals("fast", fast.brainProfile)
        assertEquals(1, reasoning.samples)
        assertEquals(0.0, reasoning.successRate, 0.0001)
        assertEquals("reasoning", reasoning.brainProfile)
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
    fun outcomeQualityDoesNotActBeforeEnoughUserEvidence() {
        val observations = (1 until SafetyPolicy.MIN_RUNTIME_EVAL_POSTERIOR_SAMPLES)
            .map { index ->
                observation(
                    id = "sparse-$index",
                    success = true,
                    durationMs = 80,
                    tokensPerSecond = 30.0,
                    createdAt = index.toLong()
                )
            }
        val feedback = observations
            .take(SafetyPolicy.MIN_RUNTIME_EVAL_OUTCOME_SAMPLES - 1)
            .mapIndexed { index, item ->
                outcome(
                    id = "feedback-$index",
                    targetObservationId = item.observationId,
                    kind = RuntimeOutcomeKind.NEGATIVE,
                    createdAt = 100L + index
                )
            }
        val stats = RuntimeEvalEngine.aggregate(
            observations = observations,
            nodeId = "node-a",
            taskKind = "brain_chat",
            brainProfile = "general",
            outcomeFeedback = feedback
        ) ?: error("Stats attendues")

        assertEquals(feedback.size, stats.outcomeSamples)
        assertEquals(0.0, stats.outcomeConfidence, 0.0001)
        assertEquals(
            0.0,
            RuntimeEvalEngine.posteriorAdjustment(stats, RoutingTuning()),
            0.0001
        )
    }

    @Test
    fun strongUserOutcomesRewardValidatedBrainAndPenalizeRejectedBrain() {
        val observations = (1..SafetyPolicy.STRONG_RUNTIME_EVAL_POSTERIOR_SAMPLES)
            .map { index ->
                observation(
                    id = "quality-$index",
                    success = true,
                    durationMs = 100,
                    tokensPerSecond = 35.0,
                    createdAt = index.toLong()
                )
            }
        val baseStats = RuntimeEvalEngine.aggregate(
            observations,
            "node-a",
            "brain_chat",
            brainProfile = "general"
        ) ?: error("Stats de base attendues")
        val base = RuntimeEvalEngine.posteriorAdjustment(baseStats, RoutingTuning())

        val targets = observations.take(SafetyPolicy.STRONG_RUNTIME_EVAL_OUTCOME_SAMPLES)
        val positive = targets.mapIndexed { index, item ->
            outcome(
                id = "positive-$index",
                targetObservationId = item.observationId,
                kind = RuntimeOutcomeKind.POSITIVE,
                createdAt = 1_000L + index
            )
        }
        val negative = targets.mapIndexed { index, item ->
            outcome(
                id = "negative-$index",
                targetObservationId = item.observationId,
                kind = RuntimeOutcomeKind.NEGATIVE,
                createdAt = 2_000L + index
            )
        }

        val goodStats = RuntimeEvalEngine.aggregate(
            observations,
            "node-a",
            "brain_chat",
            brainProfile = "general",
            outcomeFeedback = positive
        ) ?: error("Stats positives attendues")
        val badStats = RuntimeEvalEngine.aggregate(
            observations,
            "node-a",
            "brain_chat",
            brainProfile = "general",
            outcomeFeedback = negative
        ) ?: error("Stats négatives attendues")
        val good = RuntimeEvalEngine.posteriorAdjustment(goodStats, RoutingTuning())
        val bad = RuntimeEvalEngine.posteriorAdjustment(badStats, RoutingTuning())

        assertEquals(1.0, goodStats.outcomeConfidence, 0.0001)
        assertEquals(1.0, goodStats.outcomeQualityScore, 0.0001)
        assertEquals(-1.0, badStats.outcomeQualityScore, 0.0001)
        assertTrue(good > base)
        assertTrue(bad < base)
        assertEquals(
            2.0 * SafetyPolicy.MAX_RUNTIME_EVAL_OUTCOME_ADJUSTMENT,
            good - bad,
            0.0001
        )
    }

    @Test
    fun outcomeQualityStaysBoundToTargetObservationAndProfile() {
        val observations = listOf(
            observation("fast-1", success = true, durationMs = 50, tokensPerSecond = 60.0, brainProfile = "fast", createdAt = 1),
            observation("fast-2", success = true, durationMs = 50, tokensPerSecond = 60.0, brainProfile = "fast", createdAt = 2),
            observation("fast-3", success = true, durationMs = 50, tokensPerSecond = 60.0, brainProfile = "fast", createdAt = 3),
            observation("reason-1", success = true, durationMs = 50, tokensPerSecond = 60.0, brainProfile = "reasoning", createdAt = 4),
            observation("reason-2", success = true, durationMs = 50, tokensPerSecond = 60.0, brainProfile = "reasoning", createdAt = 5),
            observation("reason-3", success = true, durationMs = 50, tokensPerSecond = 60.0, brainProfile = "reasoning", createdAt = 6)
        )
        val feedback = listOf(
            outcome("f1", "fast-1", RuntimeOutcomeKind.POSITIVE, brainProfile = "fast", createdAt = 10),
            outcome("f2", "fast-2", RuntimeOutcomeKind.POSITIVE, brainProfile = "fast", createdAt = 11),
            outcome("f3", "fast-3", RuntimeOutcomeKind.POSITIVE, brainProfile = "fast", createdAt = 12),
            outcome("r1", "reason-1", RuntimeOutcomeKind.NEGATIVE, brainProfile = "reasoning", createdAt = 13),
            outcome("r2", "reason-2", RuntimeOutcomeKind.NEGATIVE, brainProfile = "reasoning", createdAt = 14),
            outcome("r3", "reason-3", RuntimeOutcomeKind.NEGATIVE, brainProfile = "reasoning", createdAt = 15),
            outcome("orphan", "pruned-observation", RuntimeOutcomeKind.NEGATIVE, brainProfile = "fast", createdAt = 16)
        )

        val fast = RuntimeEvalEngine.aggregate(
            observations,
            "node-a",
            "brain_chat",
            brainProfile = "fast",
            outcomeFeedback = feedback
        ) ?: error("Stats FAST attendues")
        val reasoning = RuntimeEvalEngine.aggregate(
            observations,
            "node-a",
            "brain_chat",
            brainProfile = "reasoning",
            outcomeFeedback = feedback
        ) ?: error("Stats REASONING attendues")

        assertEquals(3, fast.outcomeSamples)
        assertEquals(3, fast.positiveOutcomes)
        assertEquals(0, fast.negativeOutcomes)
        assertTrue(fast.outcomeQualityScore > 0.0)
        assertEquals(3, reasoning.outcomeSamples)
        assertEquals(3, reasoning.negativeOutcomes)
        assertTrue(reasoning.outcomeQualityScore < 0.0)
    }

    @Test
    fun latestOutcomeForSameResponseWinsInsteadOfDoubleCounting() {
        val observation = observation(
            id = "same-response",
            success = true,
            durationMs = 80,
            tokensPerSecond = 40.0,
            createdAt = 1
        )
        val feedback = listOf(
            outcome("old-positive", observation.observationId, RuntimeOutcomeKind.POSITIVE, createdAt = 10),
            outcome("new-correction", observation.observationId, RuntimeOutcomeKind.CORRECTION, createdAt = 20)
        )
        val stats = RuntimeEvalEngine.aggregate(
            observations = listOf(observation),
            nodeId = "node-a",
            taskKind = "brain_chat",
            brainProfile = "general",
            outcomeFeedback = feedback
        ) ?: error("Stats attendues")

        assertEquals(1, stats.outcomeSamples)
        assertEquals(0, stats.positiveOutcomes)
        assertEquals(1, stats.corrections)
        assertEquals(-1.0, stats.outcomeQualityScore, 0.0001)
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
    fun adaptiveBrainEvidenceUsesOnlyStrongProfileEvidenceAndStaysBounded() {
        val observations = (1..SafetyPolicy.STRONG_RUNTIME_EVAL_POSTERIOR_SAMPLES)
            .map { index ->
                observation(
                    id = "reasoning-$index",
                    success = true,
                    durationMs = 35,
                    tokensPerSecond = 120.0,
                    brainProfile = "reasoning",
                    createdAt = index.toLong()
                )
            }
        val stats = RuntimeEvalEngine.aggregate(
            observations = observations,
            nodeId = "node-a",
            taskKind = "brain_chat",
            brainProfile = "reasoning"
        )
        val evidence = AdaptiveBrainRouting.evidenceFromStats(
            nodeId = "node-a",
            profile = CognitiveBrainProfile.REASONING,
            routing = RoutingTuning(),
            stats = stats
        )

        assertTrue(evidence.active)
        assertEquals(1.0, evidence.confidence, 0.0001)
        assertEquals("reasoning", stats?.brainProfile)
        assertTrue(evidence.adjustment > 0.0)
        assertTrue(
            evidence.adjustment <=
                SafetyPolicy.MAX_ADAPTIVE_BRAIN_ROUTING_ADJUSTMENT
        )
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
        brainProfile: String = "general",
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
        brainProfile = brainProfile,
        success = success,
        durationMs = durationMs,
        outputChars = if (success) 100 else 0,
        tokensPerSecond = tokensPerSecond,
        fallbackUsed = fallback,
        error = if (success) null else "failure",
        createdAt = createdAt
    )

    private fun outcome(
        id: String,
        targetObservationId: String,
        kind: RuntimeOutcomeKind,
        nodeId: String = "node-a",
        brainProfile: String = "general",
        createdAt: Long
    ): RuntimeOutcomeFeedback = RuntimeOutcomeFeedback(
        feedbackId = id,
        targetObservationId = targetObservationId,
        nodeId = nodeId,
        taskKind = "brain_chat",
        model = "jade-model",
        brainProfile = brainProfile,
        kind = kind,
        confidence = 1.0,
        createdAt = createdAt
    )
}
