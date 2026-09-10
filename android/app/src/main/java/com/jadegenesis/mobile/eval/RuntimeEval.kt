package com.jadegenesis.mobile.eval

import com.jadegenesis.mobile.config.RoutingTuning
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.TaskWorkload
import kotlin.math.ceil

/**
 * Une observation Runtime Eval représente une exécution réellement tentée.
 * Les refus d'admission Resource Lease ne sont pas des échecs du runtime et ne
 * doivent donc pas être enregistrés ici.
 */
data class RuntimeEvalObservation(
    val observationId: String,
    val taskId: String,
    val taskKind: String,
    val workload: TaskWorkload,
    val nodeId: String,
    val nodeName: String,
    val nodeKind: NodeKind,
    val model: String = "",
    val brainProfile: String = "",
    val success: Boolean,
    val durationMs: Long,
    val outputChars: Int = 0,
    val tokensPerSecond: Double = 0.0,
    val fallbackUsed: Boolean = false,
    val error: String? = null,
    val createdAt: Long
)

enum class RuntimeOutcomeKind {
    POSITIVE,
    NEGATIVE,
    CORRECTION
}

/**
 * Retour utilisateur rattaché à une réponse générative réellement exécutée.
 * Aucun texte utilisateur n'est copié ici : Runtime Eval ne conserve que le
 * signal, sa confiance et l'identité exacte de l'observation ciblée.
 */
data class RuntimeOutcomeFeedback(
    val feedbackId: String,
    val targetObservationId: String,
    val nodeId: String,
    val taskKind: String,
    val model: String,
    val brainProfile: String,
    val kind: RuntimeOutcomeKind,
    val confidence: Double,
    val createdAt: Long
)

data class RuntimeEvalStats(
    val nodeId: String,
    val nodeName: String,
    val taskKind: String,
    val model: String,
    val brainProfile: String,
    val samples: Int,
    val successes: Int,
    val failures: Int,
    val successRate: Double,
    val averageDurationMs: Double,
    val p90DurationMs: Long,
    val fallbackRate: Double,
    val averageTokensPerSecond: Double,
    val firstObservedAt: Long,
    val lastObservedAt: Long,
    val outcomeSamples: Int = 0,
    val positiveOutcomes: Int = 0,
    val negativeOutcomes: Int = 0,
    val corrections: Int = 0,
    val outcomeQualityScore: Double = 0.0,
    val outcomeConfidence: Double = 0.0
)

data class RuntimeEvalReport(
    val schemaVersion: Int,
    val generatedAt: Long,
    val observationCount: Int,
    val successfulObservations: Int,
    val overallSuccessRate: Double,
    val groups: List<RuntimeEvalStats>,
    val score: Double,
    val confidence: Double,
    val outcomeFeedbackCount: Int = 0,
    val overallOutcomeQuality: Double = 0.0
)

object RuntimeEvalEngine {
    const val SCHEMA_VERSION = 1

    private data class GroupKey(
        val nodeId: String,
        val taskKind: String,
        val model: String,
        val brainProfile: String
    )

    private data class OutcomeSummary(
        val samples: Int,
        val positives: Int,
        val negatives: Int,
        val corrections: Int,
        val quality: Double,
        val confidence: Double
    )

    fun aggregate(
        observations: List<RuntimeEvalObservation>,
        nodeId: String,
        taskKind: String,
        model: String? = null,
        brainProfile: String? = null,
        outcomeFeedback: List<RuntimeOutcomeFeedback> = emptyList()
    ): RuntimeEvalStats? {
        val cleanModel = model?.trim().orEmpty()
        val cleanProfile = brainProfile?.trim()?.lowercase().orEmpty()
        val relevant = observations.filter { observation ->
            observation.nodeId == nodeId &&
                observation.taskKind == taskKind &&
                (cleanModel.isBlank() || observation.model == cleanModel) &&
                (
                    cleanProfile.isBlank() ||
                        observation.brainProfile.trim().lowercase() == cleanProfile
                    )
        }
        if (relevant.isEmpty()) return null

        val relevantIds = relevant.mapTo(hashSetOf()) { it.observationId }
        val relevantOutcomes = outcomeFeedback.filter { feedback ->
            feedback.targetObservationId in relevantIds &&
                feedback.nodeId == nodeId &&
                feedback.taskKind == taskKind &&
                (cleanModel.isBlank() || feedback.model == cleanModel) &&
                (
                    cleanProfile.isBlank() ||
                        feedback.brainProfile.trim().lowercase() == cleanProfile
                    )
        }
        return aggregateGroup(relevant, relevantOutcomes)
    }

    fun report(
        observations: List<RuntimeEvalObservation>,
        routing: RoutingTuning,
        generatedAt: Long = System.currentTimeMillis(),
        outcomeFeedback: List<RuntimeOutcomeFeedback> = emptyList()
    ): RuntimeEvalReport {
        val ordered = observations.sortedByDescending { it.createdAt }
        val retainedIds = ordered.mapTo(hashSetOf()) { it.observationId }
        val retainedOutcomes = outcomeFeedback.filter {
            it.targetObservationId in retainedIds
        }
        val groups = ordered
            .groupBy {
                GroupKey(
                    nodeId = it.nodeId,
                    taskKind = it.taskKind,
                    model = it.model,
                    brainProfile = it.brainProfile.trim().lowercase()
                )
            }
            .map { (key, items) ->
                val groupIds = items.mapTo(hashSetOf()) { it.observationId }
                val matchingOutcomes = retainedOutcomes.filter { feedback ->
                    feedback.targetObservationId in groupIds &&
                        feedback.nodeId == key.nodeId &&
                        feedback.taskKind == key.taskKind &&
                        feedback.model == key.model &&
                        feedback.brainProfile.trim().lowercase() == key.brainProfile
                }
                aggregateGroup(items, matchingOutcomes)
            }
            .sortedWith(
                compareByDescending<RuntimeEvalStats> { it.samples }
                    .thenByDescending { it.lastObservedAt }
                    .thenBy { it.nodeName.lowercase() }
                    .thenBy { it.taskKind }
                    .thenBy { it.brainProfile }
            )

        val successes = ordered.count { it.success }
        val successRate = if (ordered.isEmpty()) {
            0.0
        } else {
            successes.toDouble() / ordered.size.toDouble()
        }
        val weightedScore = if (groups.isEmpty()) {
            0.0
        } else {
            val totalWeight = groups.sumOf { it.samples }.coerceAtLeast(1)
            groups.sumOf { stats ->
                normalizedGroupScore(stats, routing) * stats.samples.toDouble()
            } / totalWeight.toDouble()
        }
        val confidence = (
            ordered.size.toDouble() /
                SafetyPolicy.STRONG_RUNTIME_EVAL_POSTERIOR_SAMPLES.toDouble()
            ).coerceIn(0.0, 1.0)
        val overallOutcome = summarizeOutcomes(retainedOutcomes)

        return RuntimeEvalReport(
            schemaVersion = SCHEMA_VERSION,
            generatedAt = generatedAt,
            observationCount = ordered.size,
            successfulObservations = successes,
            overallSuccessRate = successRate,
            groups = groups,
            score = weightedScore.coerceIn(0.0, 100.0),
            confidence = confidence,
            outcomeFeedbackCount = overallOutcome.samples,
            overallOutcomeQuality = overallOutcome.quality
        )
    }

    /**
     * Ajustement de routage fondé sur les mesures réelles.
     *
     * La composante opérationnelle apprend succès/échec, latence, débit et
     * fallback. La composante Outcome Quality apprend séparément si les réponses
     * ont réellement été validées, rejetées ou corrigées par l'utilisateur.
     * Chacune reste inactive avant son propre minimum de preuves.
     */
    fun posteriorAdjustment(
        stats: RuntimeEvalStats?,
        routing: RoutingTuning
    ): Double {
        if (stats == null) return 0.0

        var adjustment = 0.0
        if (stats.samples >= SafetyPolicy.MIN_RUNTIME_EVAL_POSTERIOR_SAMPLES) {
            val confidence = posteriorConfidence(stats.samples)
            val centeredReliability = (stats.successRate - 0.5) * 2.0
            val reliability = centeredReliability *
                routing.historySuccessRateWeight * 2.0
            val latency = if (stats.averageDurationMs > 0.0) {
                routing.historyDurationBonus * 1.5 /
                    (1.0 + stats.averageDurationMs / routing.historyDurationScaleMs)
            } else {
                0.0
            }
            val fallbackPenalty = stats.fallbackRate *
                routing.historyFailurePenalty * 3.0
            val failureRate = 1.0 - stats.successRate
            val failurePenalty = failureRate *
                routing.historyFailurePenalty * 4.0
            val throughput = stats.averageTokensPerSecond
                .coerceIn(0.0, 200.0) *
                routing.brainTokensPerSecondWeight

            adjustment += confidence * (
                reliability + latency + throughput -
                    fallbackPenalty - failurePenalty
                )
        }

        if (stats.outcomeSamples >= SafetyPolicy.MIN_RUNTIME_EVAL_OUTCOME_SAMPLES) {
            adjustment += stats.outcomeQualityScore *
                stats.outcomeConfidence *
                SafetyPolicy.MAX_RUNTIME_EVAL_OUTCOME_ADJUSTMENT
        }

        return adjustment
    }

    fun posteriorConfidence(samples: Int): Double {
        val minimum = SafetyPolicy.MIN_RUNTIME_EVAL_POSTERIOR_SAMPLES
        val strong = SafetyPolicy.STRONG_RUNTIME_EVAL_POSTERIOR_SAMPLES
        if (samples < minimum) return 0.0
        if (samples >= strong) return 1.0
        val span = (strong - minimum).coerceAtLeast(1)
        return (
            (samples - minimum + 1).toDouble() /
                (span + 1).toDouble()
            ).coerceIn(0.0, 1.0)
    }

    fun outcomePosteriorConfidence(samples: Int): Double {
        val minimum = SafetyPolicy.MIN_RUNTIME_EVAL_OUTCOME_SAMPLES
        val strong = SafetyPolicy.STRONG_RUNTIME_EVAL_OUTCOME_SAMPLES
        if (samples < minimum) return 0.0
        if (samples >= strong) return 1.0
        val span = (strong - minimum).coerceAtLeast(1)
        return (
            (samples - minimum + 1).toDouble() /
                (span + 1).toDouble()
            ).coerceIn(0.0, 1.0)
    }

    private fun aggregateGroup(
        items: List<RuntimeEvalObservation>,
        outcomeFeedback: List<RuntimeOutcomeFeedback> = emptyList()
    ): RuntimeEvalStats {
        require(items.isNotEmpty())
        val ordered = items.sortedBy { it.createdAt }
        val successful = ordered.filter { it.success }
        val successes = successful.size
        val durations = successful
            .map { it.durationMs.coerceAtLeast(0L) }
            .filter { it > 0L }
            .sorted()
        val averageDuration = durations
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?: 0.0
        val p90 = percentile90(durations)
        val throughput = successful
            .map { it.tokensPerSecond }
            .filter { it.isFinite() && it > 0.0 }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?: 0.0
        val fallbackCount = ordered.count { it.fallbackUsed }
        val first = ordered.first()
        val last = ordered.last()
        val outcome = summarizeOutcomes(outcomeFeedback)

        return RuntimeEvalStats(
            nodeId = last.nodeId,
            nodeName = last.nodeName,
            taskKind = last.taskKind,
            model = last.model,
            brainProfile = last.brainProfile.trim().lowercase(),
            samples = ordered.size,
            successes = successes,
            failures = ordered.size - successes,
            successRate = successes.toDouble() / ordered.size.toDouble(),
            averageDurationMs = averageDuration,
            p90DurationMs = p90,
            fallbackRate = fallbackCount.toDouble() / ordered.size.toDouble(),
            averageTokensPerSecond = throughput,
            firstObservedAt = first.createdAt,
            lastObservedAt = last.createdAt,
            outcomeSamples = outcome.samples,
            positiveOutcomes = outcome.positives,
            negativeOutcomes = outcome.negatives,
            corrections = outcome.corrections,
            outcomeQualityScore = outcome.quality,
            outcomeConfidence = outcome.confidence
        )
    }

    private fun summarizeOutcomes(
        items: List<RuntimeOutcomeFeedback>
    ): OutcomeSummary {
        val activeItems = items
            .sortedByDescending { it.createdAt }
            .distinctBy { it.targetObservationId }
        if (activeItems.isEmpty()) {
            return OutcomeSummary(0, 0, 0, 0, 0.0, 0.0)
        }

        var weighted = 0.0
        var weight = 0.0
        var positives = 0
        var negatives = 0
        var corrections = 0
        activeItems.forEach { feedback ->
            val confidence = feedback.confidence.coerceIn(0.0, 1.0)
            val value = when (feedback.kind) {
                RuntimeOutcomeKind.POSITIVE -> {
                    positives += 1
                    1.0
                }
                RuntimeOutcomeKind.NEGATIVE -> {
                    negatives += 1
                    -1.0
                }
                RuntimeOutcomeKind.CORRECTION -> {
                    corrections += 1
                    -1.0
                }
            }
            weighted += value * confidence
            weight += confidence
        }
        val quality = if (weight > 0.0) weighted / weight else 0.0
        return OutcomeSummary(
            samples = activeItems.size,
            positives = positives,
            negatives = negatives,
            corrections = corrections,
            quality = quality.coerceIn(-1.0, 1.0),
            confidence = outcomePosteriorConfidence(activeItems.size)
        )
    }

    private fun normalizedGroupScore(
        stats: RuntimeEvalStats,
        routing: RoutingTuning
    ): Double {
        val reliability = stats.successRate * 70.0
        val latency = if (stats.averageDurationMs <= 0.0) {
            0.0
        } else {
            20.0 /
                (1.0 + stats.averageDurationMs / routing.historyDurationScaleMs)
        }
        val throughput = if (stats.averageTokensPerSecond <= 0.0) {
            0.0
        } else {
            10.0 * (stats.averageTokensPerSecond / 200.0)
                .coerceIn(0.0, 1.0)
        }
        val fallbackPenalty = stats.fallbackRate * 10.0
        val outcomeQuality = stats.outcomeQualityScore *
            stats.outcomeConfidence * 15.0
        return (
            reliability + latency + throughput -
                fallbackPenalty + outcomeQuality
            ).coerceIn(0.0, 100.0)
    }

    private fun percentile90(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val index = (ceil(values.size * 0.90).toInt() - 1)
            .coerceIn(0, values.lastIndex)
        return values[index]
    }
}
