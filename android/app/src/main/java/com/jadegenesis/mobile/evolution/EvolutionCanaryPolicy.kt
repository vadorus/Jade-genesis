package com.jadegenesis.mobile.evolution

import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.eval.RuntimeEvalObservation

enum class EvolutionCanaryDecision {
    CONTINUE,
    STOP_REGRESSION,
    ABORT_INVALID_EVIDENCE,
    COMPLETE
}

data class EvolutionCanaryAssessment(
    val decision: EvolutionCanaryDecision,
    val pairCount: Int,
    val baselineSuccessRate: Double,
    val challengerSuccessRate: Double,
    val successRateDelta: Double,
    val baselineAverageDurationMs: Double,
    val challengerAverageDurationMs: Double,
    val reason: String
)

object EvolutionCanaryPolicy {
    fun assess(
        baseline: List<RuntimeEvalObservation>,
        challenger: List<RuntimeEvalObservation>
    ): EvolutionCanaryAssessment {
        if (!EvolutionPolicy.pairedScenarioCompatible(baseline, challenger)) {
            return assessment(
                EvolutionCanaryDecision.ABORT_INVALID_EVIDENCE,
                baseline, challenger,
                "Les observations champion/challenger ne décrivent pas les mêmes scénarios."
            )
        }

        val immediateHardRegression = baseline.indices.any { index ->
            baseline[index].success && !challenger[index].success
        }
        if (immediateHardRegression) {
            return assessment(
                EvolutionCanaryDecision.STOP_REGRESSION,
                baseline, challenger,
                "Le challenger a échoué sur un scénario où le champion a réussi."
            )
        }

        val pairCount = baseline.size
        if (pairCount >= SafetyPolicy.MIN_EVOLUTION_CANARY_EARLY_STOP_PAIRS) {
            val baselineRate = successRate(baseline)
            val challengerRate = successRate(challenger)
            if (challengerRate - baselineRate < -SafetyPolicy.MAX_EVOLUTION_CANARY_EARLY_SUCCESS_RATE_REGRESSION) {
                return assessment(
                    EvolutionCanaryDecision.STOP_REGRESSION,
                    baseline, challenger,
                    "La fiabilité du challenger régresse au-delà du seuil canary."
                )
            }

            val baselineDuration = averageSuccessfulDuration(baseline)
            val challengerDuration = averageSuccessfulDuration(challenger)
            if (
                baselineDuration > 0.0 &&
                challengerDuration > baselineDuration * SafetyPolicy.MAX_EVOLUTION_CANARY_LATENCY_MULTIPLIER
            ) {
                return assessment(
                    EvolutionCanaryDecision.STOP_REGRESSION,
                    baseline, challenger,
                    "La latence moyenne du challenger dépasse le plafond canary."
                )
            }
        }

        val complete =
            pairCount >= SafetyPolicy.STRONG_EVOLUTION_EVIDENCE_SAMPLES
        return assessment(
            if (complete) EvolutionCanaryDecision.COMPLETE
            else EvolutionCanaryDecision.CONTINUE,
            baseline,
            challenger,
            if (complete) {
                "Échantillon canary complet; les preuves peuvent passer à EvolutionPolicy."
            } else {
                "Aucune régression canary détectée; poursuivre l'échantillonnage borné."
            }
        )
    }

    private fun assessment(
        decision: EvolutionCanaryDecision,
        baseline: List<RuntimeEvalObservation>,
        challenger: List<RuntimeEvalObservation>,
        reason: String
    ): EvolutionCanaryAssessment {
        val baseRate = successRate(baseline)
        val challengerRate = successRate(challenger)
        return EvolutionCanaryAssessment(
            decision = decision,
            pairCount = minOf(baseline.size, challenger.size),
            baselineSuccessRate = baseRate,
            challengerSuccessRate = challengerRate,
            successRateDelta = challengerRate - baseRate,
            baselineAverageDurationMs = averageSuccessfulDuration(baseline),
            challengerAverageDurationMs = averageSuccessfulDuration(challenger),
            reason = reason
        )
    }

    private fun successRate(items: List<RuntimeEvalObservation>): Double =
        if (items.isEmpty()) 0.0
        else items.count { it.success }.toDouble() / items.size.toDouble()

    private fun averageSuccessfulDuration(
        items: List<RuntimeEvalObservation>
    ): Double {
        val durations = items
            .filter { it.success && it.durationMs > 0L }
            .map { it.durationMs.toDouble() }
        return if (durations.isEmpty()) 0.0 else durations.average()
    }
}
