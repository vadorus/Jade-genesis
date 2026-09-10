package com.jadegenesis.mobile.brain

import com.jadegenesis.mobile.config.RoutingTuning
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.eval.RuntimeEvalEngine
import com.jadegenesis.mobile.eval.RuntimeEvalRuntime
import com.jadegenesis.mobile.eval.RuntimeEvalStats

/**
 * Combine le prior matériel avec l'expérience réellement mesurée.
 *
 * Depuis 0.1.16, le posterior distingue deux familles de preuves :
 * - opérationnelles : succès d'exécution, latence, débit, fallback ;
 * - outcome : validation, rejet ou correction explicite de la réponse par
 *   l'utilisateur, rattachée à l'observation exacte qui a produit la réponse.
 *
 * Chaque famille possède son propre minimum de preuves et l'ajustement final
 * reste borné par SafetyPolicy.
 */
data class AdaptiveBrainEvidence(
    val nodeId: String,
    val profile: CognitiveBrainProfile,
    val samples: Int,
    val confidence: Double,
    val successRate: Double,
    val averageDurationMs: Double,
    val averageTokensPerSecond: Double,
    val fallbackRate: Double,
    val lastModel: String,
    val outcomeSamples: Int,
    val outcomeConfidence: Double,
    val outcomeQualityScore: Double,
    val positiveOutcomes: Int,
    val negativeOutcomes: Int,
    val corrections: Int,
    val adjustment: Double
) {
    val active: Boolean
        get() = (
            samples >= SafetyPolicy.MIN_RUNTIME_EVAL_POSTERIOR_SAMPLES ||
                outcomeSamples >= SafetyPolicy.MIN_RUNTIME_EVAL_OUTCOME_SAMPLES
            ) && adjustment != 0.0
}

object AdaptiveBrainRouting {
    fun evidence(
        nodeId: String,
        profile: CognitiveBrainProfile,
        routing: RoutingTuning
    ): AdaptiveBrainEvidence {
        val stats = RuntimeEvalRuntime.currentOrNull()?.stats(
            nodeId = nodeId,
            taskKind = "brain_chat",
            brainProfile = profile.name.lowercase()
        )
        return evidenceFromStats(nodeId, profile, routing, stats)
    }

    fun evidenceFromStats(
        nodeId: String,
        profile: CognitiveBrainProfile,
        routing: RoutingTuning,
        stats: RuntimeEvalStats?
    ): AdaptiveBrainEvidence {
        val raw = RuntimeEvalEngine.posteriorAdjustment(stats, routing)
        val bounded = raw.coerceIn(
            -SafetyPolicy.MAX_ADAPTIVE_BRAIN_ROUTING_ADJUSTMENT,
            SafetyPolicy.MAX_ADAPTIVE_BRAIN_ROUTING_ADJUSTMENT
        )
        return AdaptiveBrainEvidence(
            nodeId = nodeId,
            profile = profile,
            samples = stats?.samples ?: 0,
            confidence = RuntimeEvalEngine.posteriorConfidence(stats?.samples ?: 0),
            successRate = stats?.successRate ?: 0.0,
            averageDurationMs = stats?.averageDurationMs ?: 0.0,
            averageTokensPerSecond = stats?.averageTokensPerSecond ?: 0.0,
            fallbackRate = stats?.fallbackRate ?: 0.0,
            lastModel = stats?.model.orEmpty(),
            outcomeSamples = stats?.outcomeSamples ?: 0,
            outcomeConfidence = stats?.outcomeConfidence ?: 0.0,
            outcomeQualityScore = stats?.outcomeQualityScore ?: 0.0,
            positiveOutcomes = stats?.positiveOutcomes ?: 0,
            negativeOutcomes = stats?.negativeOutcomes ?: 0,
            corrections = stats?.corrections ?: 0,
            adjustment = bounded
        )
    }
}
