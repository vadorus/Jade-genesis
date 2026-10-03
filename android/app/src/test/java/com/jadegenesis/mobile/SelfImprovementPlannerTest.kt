package com.jadegenesis.mobile

import com.jadegenesis.mobile.config.JadeConfig
import com.jadegenesis.mobile.evolution.EvolutionCandidate
import com.jadegenesis.mobile.evolution.EvolutionCandidateKind
import com.jadegenesis.mobile.evolution.EvolutionCandidateStatus
import com.jadegenesis.mobile.evolution.EvolutionSandbox
import com.jadegenesis.mobile.evolution.EvolutionFailureKind
import com.jadegenesis.mobile.evolution.EvolutionFailureLesson
import com.jadegenesis.mobile.evolution.EvolutionNextDirection
import com.jadegenesis.mobile.evolution.SelfImprovementPlanner
import com.jadegenesis.mobile.evolution.SelfImprovementSignal
import com.jadegenesis.mobile.eval.RuntimeEvalReport
import com.jadegenesis.mobile.eval.RuntimeEvalStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfImprovementPlannerTest {
    private val planner = SelfImprovementPlanner()
    private val champion = JadeConfig.defaults().validated()

    @Test
    fun insufficientEvidenceDoesNotCreateHypothesis() {
        val proposal = planner.propose(
            report = report(
                observationCount = 6,
                confidence = 0.5,
                groups = listOf(stats(successRate = 0.4, samples = 6))
            ),
            champion = champion,
            existingCandidates = emptyList()
        )

        assertNull(proposal)
    }

    @Test
    fun reliabilityWeaknessCreatesSingleFieldChallenger() {
        val proposal = planner.propose(
            report = report(
                groups = listOf(stats(successRate = 0.70, samples = 12))
            ),
            champion = champion,
            existingCandidates = emptyList()
        ) ?: error("Expected reliability proposal")

        assertEquals(SelfImprovementSignal.RELIABILITY, proposal.signal)
        assertEquals("text_analysis", proposal.sourceTaskKind)
        assertTrue(
            proposal.proposedConfig.routing.historyFailurePenalty >
                champion.routing.historyFailurePenalty
        )
        assertTrue(proposal.rationale.contains("OBSERVATION:"))
        assertTrue(proposal.rationale.contains("HYPOTHESE:"))
        assertTrue(proposal.rationale.contains("FALSIFICATION:"))

        val sandboxCandidate = proposal.proposedConfig.copy(
            revision = champion.revision + 1,
            configId = "self-improvement-test",
            parentConfigId = champion.configId
        )
        val sandbox = EvolutionSandbox.validateConfigCandidate(
            champion,
            sandboxCandidate
        )
        assertTrue(sandbox.passed)
        assertEquals(1, sandbox.changedFields)
    }

    @Test
    fun fallbackWeaknessCreatesPenaltyHypothesisWhenReliabilityIsHealthy() {
        val proposal = planner.propose(
            report = report(
                groups = listOf(
                    stats(
                        successRate = 0.98,
                        fallbackRate = 0.30,
                        samples = 12
                    )
                )
            ),
            champion = champion,
            existingCandidates = emptyList()
        ) ?: error("Expected fallback proposal")

        assertEquals(SelfImprovementSignal.FALLBACK, proposal.signal)
        assertTrue(
            proposal.proposedConfig.routing.historyFailurePenalty >
                champion.routing.historyFailurePenalty
        )
    }

    @Test
    fun latencyWeaknessCreatesDurationHypothesisAfterOtherSignalsPass() {
        val proposal = planner.propose(
            report = report(
                groups = listOf(
                    stats(
                        successRate = 1.0,
                        fallbackRate = 0.0,
                        averageDurationMs = 250.0,
                        samples = 12
                    )
                )
            ),
            champion = champion,
            existingCandidates = emptyList()
        ) ?: error("Expected latency proposal")

        assertEquals(SelfImprovementSignal.LATENCY, proposal.signal)
        assertTrue(
            proposal.proposedConfig.routing.historyDurationBonus >
                champion.routing.historyDurationBonus
        )
    }

    @Test
    fun activeEvolutionCandidatePreventsCompetingAutomaticHypothesis() {
        val proposal = planner.propose(
            report = report(
                groups = listOf(stats(successRate = 0.50, samples = 12))
            ),
            champion = champion,
            existingCandidates = listOf(candidate(EvolutionCandidateStatus.PROPOSED))
        )

        assertNull(proposal)
    }

    @Test
    fun rejectedCandidateRequiresFreshRuntimeEvidenceBeforeRetry() {
        val proposal = planner.propose(
            report = report(
                groups = listOf(
                    stats(
                        successRate = 0.50,
                        samples = 12,
                        lastObservedAt = 1_000L
                    )
                )
            ),
            champion = champion,
            existingCandidates = listOf(
                candidate(
                    status = EvolutionCandidateStatus.REJECTED,
                    createdAt = 2_000L
                )
            )
        )

        assertNull(proposal)
    }

    @Test
    fun failedPrimaryReliabilityMutationUsesAlternateMechanism() {
        val proposal = planner.propose(
            report = report(groups = listOf(stats(successRate = 0.70))),
            champion = champion,
            existingCandidates = emptyList(),
            failureLessons = listOf(lesson("RELIABILITY|text_analysis|historyFailurePenalty:up"))
        ) ?: error("Expected alternate reliability proposal")

        assertEquals("RELIABILITY|text_analysis|historySuccessRateWeight:up", proposal.mutationKey)
        assertEquals(champion.routing.historyFailurePenalty, proposal.proposedConfig.routing.historyFailurePenalty, 0.0001)
        assertTrue(proposal.proposedConfig.routing.historySuccessRateWeight > champion.routing.historySuccessRateWeight)
    }

    @Test
    fun invalidEvidenceDoesNotBlacklistMutation() {
        val proposal = planner.propose(
            report = report(groups = listOf(stats(successRate = 0.70))),
            champion = champion,
            existingCandidates = emptyList(),
            failureLessons = listOf(lesson("RELIABILITY|text_analysis|historyFailurePenalty:up", EvolutionFailureKind.INVALID_EVIDENCE))
        ) ?: error("Expected primary reliability proposal")

        assertEquals("RELIABILITY|text_analysis|historyFailurePenalty:up", proposal.mutationKey)
    }

    @Test
    fun twoRefutedReliabilityMechanismsStopAutomaticRetuning() {
        val proposal = planner.propose(
            report = report(groups = listOf(stats(successRate = 0.70))),
            champion = champion,
            existingCandidates = emptyList(),
            failureLessons = listOf(
                lesson("RELIABILITY|text_analysis|historyFailurePenalty:up"),
                lesson("RELIABILITY|text_analysis|historySuccessRateWeight:up")
            )
        )

        assertNull(proposal)
    }

    private fun report(
        observationCount: Int = 12,
        confidence: Double = 1.0,
        groups: List<RuntimeEvalStats>
    ): RuntimeEvalReport = RuntimeEvalReport(
        schemaVersion = 1,
        generatedAt = 2_000L,
        observationCount = observationCount,
        successfulObservations = observationCount,
        overallSuccessRate = 1.0,
        groups = groups,
        score = 80.0,
        confidence = confidence,
        rawScore = 80.0
    )

    private fun stats(
        successRate: Double,
        fallbackRate: Double = 0.0,
        averageDurationMs: Double = 20.0,
        samples: Int = 12,
        lastObservedAt: Long = 1_500L,
        taskKind: String = "text_analysis"
    ): RuntimeEvalStats {
        val successes = (samples * successRate).toInt().coerceIn(0, samples)
        return RuntimeEvalStats(
            nodeId = "node-a",
            nodeName = "PC",
            taskKind = taskKind,
            model = "model-a",
            brainProfile = "balanced",
            samples = samples,
            successes = successes,
            failures = samples - successes,
            successRate = successRate,
            averageDurationMs = averageDurationMs,
            p90DurationMs = averageDurationMs.toLong(),
            fallbackRate = fallbackRate,
            averageTokensPerSecond = 20.0,
            firstObservedAt = 100L,
            lastObservedAt = lastObservedAt
        )
    }

    private fun lesson(
        mutationKey: String,
        kind: EvolutionFailureKind = EvolutionFailureKind.RELIABILITY_REGRESSION
    ): EvolutionFailureLesson = EvolutionFailureLesson(
        lessonId = "lesson-$mutationKey",
        candidateId = "failed-candidate",
        taskKind = "text_analysis",
        hypothesisKey = "RELIABILITY|text_analysis",
        mutationKey = mutationKey,
        kind = kind,
        reason = "test failure",
        refutedClaim = "test hypothesis",
        nextDirection = EvolutionNextDirection.TRY_ALTERNATE_RELIABILITY_MECHANISM,
        baselineSamples = 12,
        challengerSamples = 12,
        baselineSuccessRate = 1.0,
        challengerSuccessRate = 0.8,
        baselineAverageDurationMs = 100.0,
        challengerAverageDurationMs = 120.0,
        baselineScore = 80.0,
        challengerScore = 70.0,
        createdAt = 1_000L
    )

    private fun candidate(
        status: EvolutionCandidateStatus,
        createdAt: Long = 1_000L
    ): EvolutionCandidate = EvolutionCandidate(
        candidateId = "candidate-$status",
        kind = EvolutionCandidateKind.CONFIG,
        title = "test",
        rationale = "test",
        status = status,
        championConfigId = champion.configId,
        challengerConfigId = "challenger-$status",
        championConfigJson = champion.toJson().toString(),
        challengerConfigJson = champion.toJson().toString(),
        baselineRuntimeEvidence = null,
        createdAt = createdAt,
        updatedAt = createdAt
    )
}
