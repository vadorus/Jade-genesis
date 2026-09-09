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
        score = report.score,
        confidence = report.confidence,
        evidenceSha256 = evidenceSha256,
        generatedAt = report.generatedAt
    )

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
                "Confiance Runtime Eval insuffisante pour une promotion."
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