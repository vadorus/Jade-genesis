package com.jadegenesis.mobile.brain

import com.jadegenesis.mobile.config.RoutingTuning
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.eval.RuntimeEvalEngine
import com.jadegenesis.mobile.eval.RuntimeEvalRuntime
import com.jadegenesis.mobile.eval.RuntimeEvalStats

/**
 * Combine le prior matériel du routeur avec l'expérience réellement mesurée.
 *
 * Ce module n'évalue pas encore la qualité sémantique d'une réponse. Il apprend
 * uniquement la fiabilité opérationnelle d'un profil cognitif sur un nœud :
 * succès/échec, latence, débit et fallback. Avant le nombre minimum de preuves,
 * il n'influence pas du tout le choix.
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
    val adjustment: Double
) {
    val active: Boolean
        get() = samples >= SafetyPolicy.MIN_RUNTIME_EVAL_POSTERIOR_SAMPLES &&
            adjustment != 0.0
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
            adjustment = bounded
        )
    }
}
