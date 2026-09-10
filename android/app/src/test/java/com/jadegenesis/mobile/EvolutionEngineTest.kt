package com.jadegenesis.mobile

import com.jadegenesis.mobile.config.JadeConfig
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.evolution.EvolutionEvidenceSnapshot
import com.jadegenesis.mobile.evolution.EvolutionPolicy
import com.jadegenesis.mobile.evolution.EvolutionSandbox
import com.jadegenesis.mobile.eval.RuntimeEvalObservation
import com.jadegenesis.mobile.eval.RuntimeEvalReport
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.ResourceMode
import com.jadegenesis.mobile.model.TaskWorkload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvolutionEngineTest {

    @Test
    fun sandboxAcceptsSmallSafeConfigExperiment() {
        val champion = JadeConfig.defaults().validated()
        val challenger = champion.copy(
            revision = champion.revision + 1,
            configId = "candidate-safe",
            parentConfigId = champion.configId,
            routing = champion.routing.copy(
                preferredNodeHintBonus = champion.routing.preferredNodeHintBonus + 1.0
            )
        )

        val result = EvolutionSandbox.validateConfigCandidate(champion, challenger)

        assertTrue(result.passed)
        assertEquals(1, result.changedFields)
        assertTrue(result.errors.isEmpty())
        assertTrue(result.championConfigSha256 != result.challengerConfigSha256)
    }

    @Test
    fun sandboxRejectsCandidateThatExceedsCompiledSafetyPolicy() {
        val champion = JadeConfig.defaults().validated()
        val performance = champion.resource.mode(ResourceMode.PERFORMANCE)
        val challenger = champion.copy(
            revision = champion.revision + 1,
            configId = "candidate-unsafe",
            parentConfigId = champion.configId,
            resource = champion.resource.copy(
                modeBudgets = champion.resource.modeBudgets +
                    (ResourceMode.PERFORMANCE to performance.copy(maxParallelTasks = 99))
            )
        )

        val result = EvolutionSandbox.validateConfigCandidate(champion, challenger)

        assertFalse(result.passed)
        assertTrue(result.errors.any { it.contains("parallèles", ignoreCase = true) })
    }

    @Test
    fun pairedScenarioCheckIgnoresNodeIdentityButRequiresSameWorkloadShape() {
        val baseline = (1..12).map { index ->
            observation("base-$index", "node-a", TaskWorkload.HEAVY)
        }
        val challenger = (1..12).map { index ->
            observation("challenger-$index", "node-b", TaskWorkload.HEAVY)
        }
        val mismatched = challenger.dropLast(1) +
            observation("different", "node-b", TaskWorkload.LIGHT)

        assertTrue(EvolutionPolicy.pairedScenarioCompatible(baseline, challenger))
        assertFalse(EvolutionPolicy.pairedScenarioCompatible(baseline, mismatched))
    }

    @Test
    fun evolutionConfidenceDoesNotAutoPassAtMinimumTrialSize() {
        val atMinimum = EvolutionPolicy.evidenceConfidence(
            SafetyPolicy.MIN_EVOLUTION_TRIAL_SAMPLES
        )
        val atGate = EvolutionPolicy.evidenceConfidence(18)
        val strong = EvolutionPolicy.evidenceConfidence(
            SafetyPolicy.STRONG_EVOLUTION_EVIDENCE_SAMPLES
        )

        assertEquals(0.5, atMinimum, 0.0001)
        assertTrue(atMinimum < SafetyPolicy.MIN_EVOLUTION_EVIDENCE_CONFIDENCE)
        assertTrue(atGate >= SafetyPolicy.MIN_EVOLUTION_EVIDENCE_CONFIDENCE)
        assertEquals(1.0, strong, 0.0001)
    }

    @Test
    fun evolutionEvidenceUsesUnsaturatedScoreBeyondUiHundred() {
        val report = RuntimeEvalReport(
            schemaVersion = 1,
            generatedAt = 100L,
            observationCount = 24,
            successfulObservations = 24,
            overallSuccessRate = 1.0,
            groups = emptyList(),
            score = 100.0,
            confidence = 1.0,
            rawScore = 106.0
        )

        val snapshot = EvolutionPolicy.evidence(
            report = report,
            source = "test",
            scenarioSetId = "scenario",
            evidenceSha256 = "hash"
        )

        assertEquals(106.0, snapshot.score, 0.0001)
        assertEquals(1.0, snapshot.confidence, 0.0001)
    }

    @Test
    fun promotionCanCompareImprovementsAboveUiSaturation() {
        val baseline = evidence(
            observations = 24,
            successRate = 1.0,
            score = 101.0,
            confidence = 1.0,
            source = "champion"
        )
        val better = evidence(
            observations = 24,
            successRate = 1.0,
            score = 104.0,
            confidence = 1.0,
            source = "challenger"
        )
        val comparison = EvolutionPolicy.compare(
            baseline,
            better,
            pairedScenarioCompatible = true
        )

        assertEquals(3.0, comparison.scoreDelta, 0.0001)
        assertTrue(comparison.scoreImproved)
        assertTrue(comparison.promotionEligible)
    }

    @Test
    fun promotionNeedsEnoughEvidenceConfidenceReliabilityAndScoreGain() {
        val baseline = evidence(
            observations = 12,
            successRate = 1.0,
            score = 80.0,
            confidence = 1.0,
            source = "champion"
        )
        val better = evidence(
            observations = 12,
            successRate = 0.99,
            score = 83.0,
            confidence = 1.0,
            source = "challenger"
        )
        val comparison = EvolutionPolicy.compare(
            baseline,
            better,
            pairedScenarioCompatible = true
        )

        assertTrue(comparison.enoughEvidence)
        assertTrue(comparison.confidenceSatisfied)
        assertTrue(comparison.successRateProtected)
        assertTrue(comparison.scoreImproved)
        assertTrue(comparison.promotionEligible)
    }

    @Test
    fun reliabilityRegressionBlocksPromotionEvenWhenScoreIsHigher() {
        val baseline = evidence(
            observations = 12,
            successRate = 1.0,
            score = 80.0,
            confidence = 1.0,
            source = "champion"
        )
        val unreliable = evidence(
            observations = 12,
            successRate = 0.95,
            score = 90.0,
            confidence = 1.0,
            source = "challenger"
        )
        val comparison = EvolutionPolicy.compare(
            baseline,
            unreliable,
            pairedScenarioCompatible = true
        )

        assertFalse(comparison.successRateProtected)
        assertFalse(comparison.promotionEligible)
    }

    @Test
    fun insufficientEvidenceNeverPromotes() {
        val baseline = evidence(
            observations = 6,
            successRate = 1.0,
            score = 70.0,
            confidence = 0.5,
            source = "champion"
        )
        val challenger = evidence(
            observations = 6,
            successRate = 1.0,
            score = 95.0,
            confidence = 0.5,
            source = "challenger"
        )
        val comparison = EvolutionPolicy.compare(
            baseline,
            challenger,
            pairedScenarioCompatible = true
        )

        assertFalse(comparison.enoughEvidence)
        assertFalse(comparison.confidenceSatisfied)
        assertFalse(comparison.promotionEligible)
    }

    @Test
    fun evidenceHashIsStableAcrossObservationOrder() {
        val observations = (1..12).map { index ->
            observation("obs-$index", "node-a", TaskWorkload.HEAVY)
        }

        assertEquals(
            EvolutionPolicy.evidenceSha256(observations),
            EvolutionPolicy.evidenceSha256(observations.reversed())
        )
    }

    private fun evidence(
        observations: Int,
        successRate: Double,
        score: Double,
        confidence: Double,
        source: String
    ): EvolutionEvidenceSnapshot = EvolutionEvidenceSnapshot(
        source = source,
        scenarioSetId = "scenario-1",
        observationCount = observations,
        overallSuccessRate = successRate,
        score = score,
        confidence = confidence,
        evidenceSha256 = "hash-$source",
        generatedAt = 100L
    )

    private fun observation(
        id: String,
        nodeId: String,
        workload: TaskWorkload
    ): RuntimeEvalObservation = RuntimeEvalObservation(
        observationId = id,
        taskId = "task-$id",
        taskKind = "brain_chat",
        workload = workload,
        nodeId = nodeId,
        nodeName = nodeId,
        nodeKind = NodeKind.PC,
        model = "jade-model",
        success = true,
        durationMs = 100,
        outputChars = 100,
        tokensPerSecond = 50.0,
        fallbackUsed = false,
        error = null,
        createdAt = id.hashCode().toLong().let { if (it < 0) -it else it }
    )
}
