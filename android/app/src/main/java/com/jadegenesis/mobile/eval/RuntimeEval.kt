package com.jadegenesis.mobile.eval

import com.jadegenesis.mobile.config.RuntimeEvalTuning
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
    val success: Boolean,
    val durationMs: Long,
    val outputChars: Int = 0,
    val tokensPerSecond: Double = 0.0,
    val fallbackUsed: Boolean = false,
    val error: String? = null,
    val createdAt: Long
)

data class RuntimeEvalStats(
    val nodeId: String,
    val nodeName: String,
    val taskKind: String,
    val model: String,
    val samples: Int,
    val successes: Int,
    val failures: Int,
    val successRate: Double,
    val averageDurationMs: Double,
    val p90DurationMs: Long,
    val fallbackRate: Double,
    val averageTokensPerSecond: Double,
    val firstObservedAt: Long,
    val lastObservedAt: Long
)

data class RuntimeEvalReport(
    val schemaVersion: Int,
    val generatedAt: Long,
    val observationCount: Int,
    val successfulObservations: Int,
    val overallSuccessRate: Double,
    val groups: List<RuntimeEvalStats>,
    val score: Double,
    val confidence: Double
)

data class RuntimeEvalComparison(
    val baselineScore: Double,
    val candidateScore: Double,
    val scoreDelta: Double,
    val baselineSuccessRate: Double,
    val candidateSuccessRate: Double,
    val enoughEvidence: Boolean,
    val successRateProtected: Boolean,
    val promotionEligible: Boolean,
    val reason: String
)

object RuntimeEvalEngine {
    const val SCHEMA_VERSION = 1

    fun aggregate(
        observations: List<RuntimeEvalObservation>,
        nodeId: String,
        taskKind: String,
        model: String? = null
    ): RuntimeEvalStats? {
        val cleanModel = model?.trim().orEmpty()
        val relevant = observations.filter { observation ->
            observation.nodeId == nodeId &&
                observation.taskKind == taskKind &&
                (cleanModel.isBlank() || observation.model == cleanModel)
        }
        if (relevant.isEmpty()) return null
        return aggregateGroup(relevant)
    }

    fun report(
        observations: List<RuntimeEvalObservation>,
        tuning: RuntimeEvalTuning,
        generatedAt: Long = System.currentTimeMillis()
    ): RuntimeEvalReport {
        val ordered = observations.sortedByDescending { it.createdAt }
        val groups = ordered
            .groupBy { Triple(it.nodeId, it.taskKind, it.model) }
            .values
            .map(::aggregateGroup)
            .sortedWith(
                compareByDescending<RuntimeEvalStats> { it.samples }
                    .thenByDescending { it.lastObservedAt }
                    .thenBy { it.nodeName.lowercase() }
                    .thenBy { it.taskKind }
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
                normalizedGroupScore(stats, tuning) * stats.samples.toDouble()
            } / totalWeight.toDouble()
        }
        val confidence = evidenceConfidence(
            samples = ordered.size,
            tuning = tuning
        )

        return RuntimeEvalReport(
            schemaVersion = SCHEMA_VERSION,
            generatedAt = generatedAt,
            observationCount = ordered.size,
            successfulObservations = successes,
            overallSuccessRate = successRate,
            groups = groups,
            score = weightedScore.coerceIn(0.0, 100.0),
            confidence = confidence
        )
    }

    /**
     * Ajustement de routage fondé sur les mesures réelles.
     *
     * Avant minPosteriorSamples, le matériel et l'ancien TaskLedger restent le
     * prior dominant. La confiance monte progressivement puis, à
     * strongPosteriorSamples, le posterior mesuré peut peser davantage que les
     * heuristiques matérielles.
     */
    fun posteriorAdjustment(
        stats: RuntimeEvalStats?,
        tuning: RuntimeEvalTuning
    ): Double {
        if (stats == null || stats.samples < tuning.minPosteriorSamples) return 0.0

        val confidence = posteriorConfidence(stats.samples, tuning)
        val centeredReliability = (stats.successRate - 0.5) * 2.0
        val reliability = centeredReliability * tuning.posteriorSuccessWeight
        val latency = if (stats.averageDurationMs > 0.0) {
            tuning.posteriorLatencyBonus /
                (1.0 + stats.averageDurationMs / tuning.posteriorLatencyScaleMs)
        } else {
            0.0
        }
        val fallbackPenalty = stats.fallbackRate * tuning.posteriorFallbackPenalty
        val throughput = stats.averageTokensPerSecond
            .coerceIn(0.0, tuning.posteriorMaxTokensPerSecond) *
            tuning.posteriorTokensPerSecondWeight

        return confidence * (
            reliability + latency + throughput - fallbackPenalty
            )
    }

    fun posteriorConfidence(
        samples: Int,
        tuning: RuntimeEvalTuning
    ): Double {
        if (samples < tuning.minPosteriorSamples) return 0.0
        if (samples >= tuning.strongPosteriorSamples) return 1.0
        val span = (
            tuning.strongPosteriorSamples - tuning.minPosteriorSamples
            ).coerceAtLeast(1)
        return (
            (samples - tuning.minPosteriorSamples + 1).toDouble() /
                (span + 1).toDouble()
            ).coerceIn(0.0, 1.0)
    }

    fun compare(
        baseline: RuntimeEvalReport,
        candidate: RuntimeEvalReport,
        tuning: RuntimeEvalTuning
    ): RuntimeEvalComparison {
        val enoughEvidence =
            baseline.observationCount >= tuning.promotionMinSamples &&
                candidate.observationCount >= tuning.promotionMinSamples
        val protected = candidate.overallSuccessRate +
            tuning.promotionMaxSuccessRateRegression >=
            baseline.overallSuccessRate
        val delta = candidate.score - baseline.score
        val eligible = enoughEvidence &&
            protected &&
            delta >= tuning.promotionMinScoreDelta

        val reason = when {
            !enoughEvidence ->
                "Échantillon insuffisant pour une promotion objective."
            !protected ->
                "La fiabilité régresse au-delà de la tolérance autorisée."
            delta < tuning.promotionMinScoreDelta ->
                "Le gain mesuré est trop faible pour justifier une promotion."
            else ->
                "Le candidat dépasse le seuil mesuré sans régression de fiabilité."
        }

        return RuntimeEvalComparison(
            baselineScore = baseline.score,
            candidateScore = candidate.score,
            scoreDelta = delta,
            baselineSuccessRate = baseline.overallSuccessRate,
            candidateSuccessRate = candidate.overallSuccessRate,
            enoughEvidence = enoughEvidence,
            successRateProtected = protected,
            promotionEligible = eligible,
            reason = reason
        )
    }

    private fun aggregateGroup(
        items: List<RuntimeEvalObservation>
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

        return RuntimeEvalStats(
            nodeId = last.nodeId,
            nodeName = last.nodeName,
            taskKind = last.taskKind,
            model = last.model,
            samples = ordered.size,
            successes = successes,
            failures = ordered.size - successes,
            successRate = successes.toDouble() / ordered.size.toDouble(),
            averageDurationMs = averageDuration,
            p90DurationMs = p90,
            fallbackRate = fallbackCount.toDouble() / ordered.size.toDouble(),
            averageTokensPerSecond = throughput,
            firstObservedAt = first.createdAt,
            lastObservedAt = last.createdAt
        )
    }

    private fun normalizedGroupScore(
        stats: RuntimeEvalStats,
        tuning: RuntimeEvalTuning
    ): Double {
        val reliability = stats.successRate * 70.0
        val latency = if (stats.averageDurationMs <= 0.0) {
            0.0
        } else {
            20.0 /
                (1.0 + stats.averageDurationMs / tuning.posteriorLatencyScaleMs)
        }
        val throughput = if (stats.averageTokensPerSecond <= 0.0) {
            0.0
        } else {
            10.0 * (
                stats.averageTokensPerSecond /
                    tuning.posteriorMaxTokensPerSecond
                ).coerceIn(0.0, 1.0)
        }
        val fallbackPenalty = stats.fallbackRate * 10.0
        return (reliability + latency + throughput - fallbackPenalty)
            .coerceIn(0.0, 100.0)
    }

    private fun evidenceConfidence(
        samples: Int,
        tuning: RuntimeEvalTuning
    ): Double {
        if (samples <= 0) return 0.0
        return (
            samples.toDouble() / tuning.promotionMinSamples.coerceAtLeast(1).toDouble()
            ).coerceIn(0.0, 1.0)
    }

    private fun percentile90(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val index = (ceil(values.size * 0.90).toInt() - 1)
            .coerceIn(0, values.lastIndex)
        return values[index]
    }
}
