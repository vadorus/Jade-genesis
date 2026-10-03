package com.jadegenesis.mobile.evolution

import com.jadegenesis.mobile.config.JadeConfig
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.eval.RuntimeEvalReport
import com.jadegenesis.mobile.eval.RuntimeEvalStats
import kotlin.math.abs
import kotlin.math.max

enum class SelfImprovementSignal {
    RELIABILITY,
    FALLBACK,
    LATENCY
}

data class SelfImprovementProposal(
    val signal: SelfImprovementSignal,
    val title: String,
    val rationale: String,
    val sourceGroupKey: String,
    val sourceTaskKind: String,
    val hypothesisKey: String,
    val mutationKey: String,
    val proposedConfig: JadeConfig
)

/**
 * First closed-loop bridge between Runtime Eval and Evolution Engine.
 *
 * This planner is deliberately conservative: it only turns strong measured
 * runtime evidence into a single-field JadeConfig challenger. It never applies
 * the challenger, starts a trial, or promotes anything by itself.
 *
 * The rationale is structured as observation -> hypothesis -> prediction ->
 * falsification so Jade keeps a distinction between evidence and inference.
 */
class SelfImprovementPlanner {

    fun propose(
        report: RuntimeEvalReport,
        champion: JadeConfig,
        existingCandidates: List<EvolutionCandidate>,
        failureLessons: List<EvolutionFailureLesson> = emptyList()
    ): SelfImprovementProposal? {
        if (report.observationCount < SafetyPolicy.STRONG_RUNTIME_EVAL_POSTERIOR_SAMPLES) {
            return null
        }
        if (report.confidence < SafetyPolicy.MIN_EVOLUTION_EVIDENCE_CONFIDENCE) {
            return null
        }
        if (existingCandidates.any { it.status in ACTIVE_STATUSES }) {
            return null
        }

        val newestCandidateAt = existingCandidates.maxOfOrNull { it.createdAt } ?: 0L
        val groups = report.groups.filter { stats ->
            stats.taskKind in CANARY_SUPPORTED_TASK_KINDS &&
                stats.samples >= SafetyPolicy.STRONG_RUNTIME_EVAL_POSTERIOR_SAMPLES &&
                stats.lastObservedAt > newestCandidateAt
        }
        if (groups.isEmpty()) return null

        val reliability = groups
            .filter { it.successRate < MIN_ACCEPTABLE_SUCCESS_RATE }
            .minWithOrNull(
                compareBy<RuntimeEvalStats> { it.successRate }
                    .thenByDescending { it.samples }
            )
        if (reliability != null) {
            val routing = champion.routing
            val primaryKey = mutationKey(SelfImprovementSignal.RELIABILITY, reliability.taskKind, "historyFailurePenalty:up")
            val alternateKey = mutationKey(SelfImprovementSignal.RELIABILITY, reliability.taskKind, "historySuccessRateWeight:up")
            val useAlternate = mutationRefuted(primaryKey, failureLessons)
            if (useAlternate && mutationRefuted(alternateKey, failureLessons)) return null
            val selectedMutationKey = if (useAlternate) alternateKey else primaryKey
            val reliabilityRouting = if (useAlternate) {
                routing.copy(historySuccessRateWeight = stepUp(routing.historySuccessRateWeight, 1.0))
            } else {
                routing.copy(historyFailurePenalty = stepUp(routing.historyFailurePenalty, 0.5))
            }
            return proposal(
                signal = SelfImprovementSignal.RELIABILITY,
                stats = reliability,
                champion = champion,
                proposedConfig = champion.copy(routing = reliabilityRouting),
                mutationKey = selectedMutationKey,
                observation =
                    "Le groupe mesuré réussit ${(reliability.successRate * 100.0).format1()} % " +
                        "sur ${reliability.samples} essai(s).",
                hypothesis =
                    "Le routage sous-pondère probablement la fiabilité historique pour ce type de décision.",
                prediction =
                    "Renforcer prudemment le poids de la fiabilité historique devrait réduire la sélection future de " +
                        "chemins historiquement peu fiables sans modifier les limites de sécurité."
            )
        }

        val fallback = groups
            .filter { it.fallbackRate > MAX_ACCEPTABLE_FALLBACK_RATE }
            .maxWithOrNull(
                compareBy<RuntimeEvalStats> { it.fallbackRate }
                    .thenBy { it.samples }
            )
        if (fallback != null) {
            val routing = champion.routing
            val primaryKey = mutationKey(SelfImprovementSignal.FALLBACK, fallback.taskKind, "historyFailurePenalty:up")
            val alternateKey = mutationKey(SelfImprovementSignal.FALLBACK, fallback.taskKind, "historySuccessRateWeight:up")
            val useAlternate = mutationRefuted(primaryKey, failureLessons)
            if (useAlternate && mutationRefuted(alternateKey, failureLessons)) return null
            val selectedMutationKey = if (useAlternate) alternateKey else primaryKey
            val fallbackRouting = if (useAlternate) {
                routing.copy(historySuccessRateWeight = stepUp(routing.historySuccessRateWeight, 1.0))
            } else {
                routing.copy(historyFailurePenalty = stepUp(routing.historyFailurePenalty, 0.5))
            }
            return proposal(
                signal = SelfImprovementSignal.FALLBACK,
                stats = fallback,
                champion = champion,
                proposedConfig = champion.copy(routing = fallbackRouting),
                mutationKey = selectedMutationKey,
                observation =
                    "Le groupe mesuré utilise un fallback dans ${(fallback.fallbackRate * 100.0).format1()} % " +
                        "des ${fallback.samples} essai(s).",
                hypothesis =
                    "Le routage ne distingue probablement pas assez les routes directes des routes qui déclenchent un fallback.",
                prediction =
                    "Renforcer prudemment le signal de fiabilité historique devrait favoriser les routes qui terminent " +
                        "directement la tâche."
            )
        }

        val latencyThreshold = champion.routing.historyDurationScaleMs * LATENCY_SCALE_MULTIPLIER
        val latency = groups
            .filter {
                it.successes > 0 &&
                    it.averageDurationMs > latencyThreshold
            }
            .maxWithOrNull(
                compareBy<RuntimeEvalStats> { it.averageDurationMs }
                    .thenBy { it.samples }
            )
        if (latency != null) {
            val routing = champion.routing
            val primaryKey = mutationKey(SelfImprovementSignal.LATENCY, latency.taskKind, "historyDurationBonus:up")
            val alternateKey = mutationKey(SelfImprovementSignal.LATENCY, latency.taskKind, "historyDurationScaleMs:down")
            val useAlternate = mutationRefuted(primaryKey, failureLessons)
            if (useAlternate && mutationRefuted(alternateKey, failureLessons)) return null
            val selectedMutationKey = if (useAlternate) alternateKey else primaryKey
            val latencyRouting = if (useAlternate) {
                routing.copy(historyDurationScaleMs = stepDown(routing.historyDurationScaleMs, 5.0))
            } else {
                routing.copy(historyDurationBonus = stepUp(routing.historyDurationBonus, 1.0))
            }
            return proposal(
                signal = SelfImprovementSignal.LATENCY,
                stats = latency,
                champion = champion,
                proposedConfig = champion.copy(routing = latencyRouting),
                mutationKey = selectedMutationKey,
                observation =
                    "Le groupe mesuré prend en moyenne ${latency.averageDurationMs.format1()} ms, " +
                        "au-dessus du seuil exploratoire ${latencyThreshold.format1()} ms.",
                hypothesis =
                    "Le routage ne récompense peut-être pas assez les alternatives historiquement plus rapides.",
                prediction =
                    "Rendre le signal de durée plus discriminant devrait mieux séparer les routes rapides des routes lentes, " +
                        "plus discriminantes lors du classement."
            )
        }

        return null
    }

    private fun proposal(
        signal: SelfImprovementSignal,
        stats: RuntimeEvalStats,
        champion: JadeConfig,
        proposedConfig: JadeConfig,
        mutationKey: String,
        observation: String,
        hypothesis: String,
        prediction: String
    ): SelfImprovementProposal {
        val groupKey = listOf(
            stats.nodeId,
            stats.taskKind,
            stats.model.ifBlank { "no-model" },
            stats.brainProfile.ifBlank { "no-profile" }
        ).joinToString("|")
        val rationale = buildString {
            append("OBSERVATION: ").append(observation).append(' ')
            append("CONTEXTE: node=").append(stats.nodeName)
            append(", task=").append(stats.taskKind)
            if (stats.model.isNotBlank()) append(", model=").append(stats.model)
            if (stats.brainProfile.isNotBlank()) append(", profile=").append(stats.brainProfile)
            append(". HYPOTHESE: ").append(hypothesis)
            append(" PREDICTION: ").append(prediction)
            append(" FALSIFICATION: le challenger doit être rejeté s'il ne gagne pas au moins ")
            append(SafetyPolicy.MIN_EVOLUTION_SCORE_DELTA.format1())
            append(" points de score, si la fiabilité régresse de plus de ")
            append((SafetyPolicy.MAX_EVOLUTION_SUCCESS_RATE_REGRESSION * 100.0).format1())
            append(" %, ou si les preuves appariées sont insuffisantes. ")
            append("CHAMPION: ").append(champion.configId).append('.')
        }
        return SelfImprovementProposal(
            signal = signal,
            title = "Auto-hypothèse ${signal.name.lowercase()} — ${stats.taskKind}".take(240),
            rationale = rationale.take(2_000),
            sourceGroupKey = groupKey,
            sourceTaskKind = stats.taskKind,
            hypothesisKey = hypothesisKey(signal, stats.taskKind),
            mutationKey = mutationKey,
            proposedConfig = proposedConfig
        )
    }

    private fun hypothesisKey(signal: SelfImprovementSignal, taskKind: String): String =
        "${signal.name}|$taskKind"

    private fun mutationKey(signal: SelfImprovementSignal, taskKind: String, change: String): String =
        "${hypothesisKey(signal, taskKind)}|$change"

    private fun mutationRefuted(key: String, lessons: List<EvolutionFailureLesson>): Boolean =
        lessons.any { it.mutationKey == key && EvolutionFailureLearner.isRefuting(it.kind) }

    private fun stepDown(value: Double, minimumDelta: Double): Double {
        val delta = max(abs(value) * TUNING_STEP_FRACTION, minimumDelta)
        return (value - delta).coerceAtLeast(1.0)
    }

    private fun stepUp(value: Double, minimumDelta: Double): Double {
        val delta = max(abs(value) * TUNING_STEP_FRACTION, minimumDelta)
        return value + delta
    }

    private fun Double.format1(): String =
        "%.1f".format(java.util.Locale.US, this)

    companion object {
        private val CANARY_SUPPORTED_TASK_KINDS = setOf("genesis_probe", "text_analysis")
        private val ACTIVE_STATUSES = setOf(
            EvolutionCandidateStatus.CANDIDATE,
            EvolutionCandidateStatus.PROPOSED,
            EvolutionCandidateStatus.TESTING,
            EvolutionCandidateStatus.VALIDATED
        )
        private const val MIN_ACCEPTABLE_SUCCESS_RATE = 0.90
        private const val MAX_ACCEPTABLE_FALLBACK_RATE = 0.10
        private const val LATENCY_SCALE_MULTIPLIER = 2.0
        private const val TUNING_STEP_FRACTION = 0.10
    }
}
