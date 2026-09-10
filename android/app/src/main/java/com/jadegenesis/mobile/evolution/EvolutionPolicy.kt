package com.jadegenesis.mobile.evolution

import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.eval.RuntimeEvalObservation
import com.jadegenesis.mobile.eval.RuntimeEvalReport
import java.security.MessageDigest

object EvolutionPolicy {
    fun evidence(
        report: RuntimeEvalReport,
        source: String,
        scenarioSetId: String,
        evidenceSha256: String
    ): EvolutionEvidenceSnapshot = EvolutionEvidenceSnapshot(
        source = source,
        scenarioSetId = scenarioSetId,
        observationCount = report.observationCount,
        overallSuccessRate = report.overallSuccessRate,
        // Evolution intentionally compares the unsaturated objective score.
        // The bounded report.score remains suitable for UI/health displays.
        score = report.rawScore,
        // Do not reuse Runtime Eval's 12-sample routing confidence here: that
        // made the Evolution confidence gate automatically true as soon as the
        // minimum trial size was reached. Evolution has its own evidence curve.
        confidence = evidenceConfidence(report.observationCount),
        evidenceSha256 = evidenceSha256,
        generatedAt = report.generatedAt
    )

    fun evidenceConfidence(observationCount: Int): Double {
        if (observationCount < SafetyPolicy.MIN_EVOLUTION_TRIAL_SAMPLES) {
            return 0.0
        }
        return (
            observationCount.toDouble() /
                SafetyPolicy.STRONG_EVOLUTION_EVIDENCE_SAMPLES.toDouble()
            ).coerceIn(0.0, 1.0)
    }

    fun compare(
        baseline: EvolutionEvidenceSnapshot,
        challenger: EvolutionEvidenceSnapshot,
        pairedScenarioCompatible: Boolean
    ): EvolutionComparison {
        val enoughEvidence =
            baseline.observationCount >= SafetyPolicy.MIN_EVOLUTION_TRIAL_SAMPLES &&
                challenger.observationCount >= SafetyPolicy.MIN_EVOLUTION_TRIAL_SAMPLES
        val confidenceSatisfied =
            baseline.confidence >= SafetyPolicy.MIN_EVOLUTION_EVIDENCE_CONFIDENCE &&
                challenger.confidence >= SafetyPolicy.MIN_EVOLUTION_EVIDENCE_CONFIDENCE
        val successDelta =
            challenger.overallSuccessRate - baseline.overallSuccessRate
        val successRateProtected =
            successDelta >= -SafetyPolicy.MAX_EVOLUTION_SUCCESS_RATE_REGRESSION
        val scoreDelta = challenger.score - baseline.score
        val scoreImproved = scoreDelta >= SafetyPolicy.MIN_EVOLUTION_SCORE_DELTA
        val promotionEligible =
            pairedScenarioCompatible &&
                enoughEvidence &&
                confidenceSatisfied &&
                successRateProtected &&
                scoreImproved

        val reason = when {
            !pairedScenarioCompatible ->
                "Les scénarios champion/challenger ne sont pas appariés."
            !enoughEvidence ->
                "Échantillon insuffisant pour une promotion objective."
            !confidenceSatisfied ->
                "Confiance Evolution insuffisante pour une promotion."
            !successRateProtected ->
                "Le challenger régresse trop en fiabilité."
            !scoreImproved ->
                "Le gain mesuré est trop faible pour remplacer le champion."
            else ->
                "Le challenger dépasse le champion sur un essai apparié sans régression de fiabilité."
        }

        return EvolutionComparison(
            baselineScore = baseline.score,
            challengerScore = challenger.score,
            scoreDelta = scoreDelta,
            baselineSuccessRate = baseline.overallSuccessRate,
            challengerSuccessRate = challenger.overallSuccessRate,
            successRateDelta = successDelta,
            enoughEvidence = enoughEvidence,
            confidenceSatisfied = confidenceSatisfied,
            pairedScenarioCompatible = pairedScenarioCompatible,
            successRateProtected = successRateProtected,
            scoreImproved = scoreImproved,
            promotionEligible = promotionEligible,
            reason = reason
        )
    }

    fun pairedScenarioCompatible(
        baseline: List<RuntimeEvalObservation>,
        challenger: List<RuntimeEvalObservation>
    ): Boolean {
        if (baseline.isEmpty() || challenger.isEmpty()) return false
        if (baseline.size != challenger.size) return false
        return scenarioSignature(baseline) == scenarioSignature(challenger)
    }

    fun scenarioSignature(
        observations: List<RuntimeEvalObservation>
    ): Map<String, Int> = observations
        .groupingBy { observation ->
            "${observation.taskKind}|${observation.workload.name}"
        }
        .eachCount()
        .toSortedMap()

    fun evidenceSha256(observations: List<RuntimeEvalObservation>): String {
        val canonical = observations
            .sortedWith(
                compareBy<RuntimeEvalObservation> { it.taskKind }
                    .thenBy { it.workload.name }
                    .thenBy { it.observationId }
            )
            .joinToString("\n") { observation ->
                listOf(
                    observation.observationId,
                    observation.taskId,
                    observation.taskKind,
                    observation.workload.name,
                    observation.nodeId,
                    observation.model,
                    observation.success.toString(),
                    observation.durationMs.toString(),
                    observation.outputChars.toString(),
                    "%.6f".format(java.util.Locale.ROOT, observation.tokensPerSecond),
                    observation.fallbackUsed.toString(),
                    observation.createdAt.toString()
                ).joinToString("|")
            }
        return sha256(canonical)
    }

    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
}
