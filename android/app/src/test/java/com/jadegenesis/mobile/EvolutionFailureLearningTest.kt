package com.jadegenesis.mobile

import com.jadegenesis.mobile.config.JadeConfig
import com.jadegenesis.mobile.evolution.EvolutionCandidate
import com.jadegenesis.mobile.evolution.EvolutionCandidateKind
import com.jadegenesis.mobile.evolution.EvolutionCandidateStatus
import com.jadegenesis.mobile.evolution.EvolutionComparison
import com.jadegenesis.mobile.evolution.EvolutionFailureKind
import com.jadegenesis.mobile.evolution.EvolutionFailureLearner
import com.jadegenesis.mobile.evolution.EvolutionFailureMetrics
import com.jadegenesis.mobile.evolution.EvolutionNextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvolutionFailureLearningTest {
    private val champion = JadeConfig.defaults().validated()

    @Test
    fun failureLessonKeepsHypothesisMutationAndCanaryMetrics() {
        val lesson = EvolutionFailureLearner.fromCandidate(
            candidate = candidate(),
            kind = EvolutionFailureKind.LATENCY_REGRESSION,
            reason = "too slow",
            metrics = EvolutionFailureMetrics(
                baselineSamples = 4,
                challengerSamples = 4,                baselineSuccessRate = 1.0,
                challengerSuccessRate = 1.0,
                baselineAverageDurationMs = 100.0,
                challengerAverageDurationMs = 250.0
            ),
            now = 123L
        )

        assertEquals("LATENCY|text_analysis", lesson.hypothesisKey)
        assertEquals("LATENCY|text_analysis|historyDurationBonus:up", lesson.mutationKey)
        assertEquals("routing duration underweighted", lesson.refutedClaim)
        assertEquals(EvolutionNextDirection.TRY_ALTERNATE_LATENCY_MECHANISM, lesson.nextDirection)
        assertEquals(4, lesson.baselineSamples)
        assertEquals(250.0, lesson.challengerAverageDurationMs, 0.0001)
        assertEquals(123L, lesson.createdAt)
    }

    @Test
    fun invalidEvidenceDoesNotRefuteMutation() {
        assertFalse(EvolutionFailureLearner.isRefuting(EvolutionFailureKind.INVALID_EVIDENCE))
        assertFalse(EvolutionFailureLearner.isRefuting(EvolutionFailureKind.CHAMPION_CHANGED))
        assertTrue(EvolutionFailureLearner.isRefuting(EvolutionFailureKind.HARD_FAILURE))
        assertTrue(EvolutionFailureLearner.isRefuting(EvolutionFailureKind.INSUFFICIENT_GAIN))
    }
    @Test
    fun twoDistinctRefutationsEscalateToResearch() {
        val first = EvolutionFailureLearner.fromCandidate(
            candidate(), EvolutionFailureKind.LATENCY_REGRESSION, "slow", now = 10L
        )
        val second = EvolutionFailureLearner.fromCandidate(
            candidate().copy(mutationKey = "LATENCY|text_analysis|historyDurationScaleMs:down"),
            EvolutionFailureKind.LATENCY_REGRESSION,
            "still slow",
            now = 20L
        )
        val escalated = EvolutionFailureLearner.withEscalation(second, listOf(first))

        assertEquals(EvolutionNextDirection.RESEARCH_BEFORE_RETRY, escalated.nextDirection)
    }

    @Test
    fun comparisonClassificationDistinguishesBadEvidenceAndBadResult() {
        val invalid = comparison(
            paired = false,
            successProtected = true,
            scoreImproved = true
        )
        val unreliable = comparison(
            paired = true,
            successProtected = false,
            scoreImproved = true
        )
        val weakGain = comparison(
            paired = true,
            successProtected = true,
            scoreImproved = false
        )

        assertEquals(EvolutionFailureKind.INVALID_EVIDENCE, EvolutionFailureLearner.classifyComparison(invalid))
        assertEquals(EvolutionFailureKind.RELIABILITY_REGRESSION, EvolutionFailureLearner.classifyComparison(unreliable))
        assertEquals(EvolutionFailureKind.INSUFFICIENT_GAIN, EvolutionFailureLearner.classifyComparison(weakGain))
    }

    private fun comparison(
        paired: Boolean,
        successProtected: Boolean,
        scoreImproved: Boolean
    ): EvolutionComparison = EvolutionComparison(        baselineScore = 80.0,
        challengerScore = 82.0,
        scoreDelta = 2.0,
        baselineSuccessRate = 1.0,
        challengerSuccessRate = if (successProtected) 1.0 else 0.8,
        successRateDelta = if (successProtected) 0.0 else -0.2,
        enoughEvidence = true,
        confidenceSatisfied = true,
        pairedScenarioCompatible = paired,
        successRateProtected = successProtected,
        scoreImproved = scoreImproved,
        promotionEligible = paired && successProtected && scoreImproved,
        reason = "test"
    )

    private fun candidate(): EvolutionCandidate = EvolutionCandidate(
        candidateId = "candidate-failure",
        kind = EvolutionCandidateKind.CONFIG,
        title = "latency",
        rationale = "OBSERVATION: slow. HYPOTHESE: routing duration underweighted PREDICTION: faster.",
        status = EvolutionCandidateStatus.TESTING,
        championConfigId = champion.configId,
        challengerConfigId = "challenger",
        championConfigJson = champion.toJson().toString(),
        challengerConfigJson = champion.toJson().toString(),
        baselineRuntimeEvidence = null,
        experimentTaskKind = "text_analysis",        hypothesisKey = "LATENCY|text_analysis",
        mutationKey = "LATENCY|text_analysis|historyDurationBonus:up",
        createdAt = 1L,
        updatedAt = 1L
    )
}