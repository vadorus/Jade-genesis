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

object RuntimeEvalEngine {
    const val SCHEMA_VERSION = 1

    private data class GroupKey(
        val nodeId: String,
        val taskKind: String,
        val model: String,
        val brainProfile: String
    )

    fun aggregate(
        observations: List<RuntimeEvalObservation>,
        nodeId: String,
        taskKind: String,
        model: String? = null,
        brainProfile: String? = null
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
        return aggregateGroup(relevant)
    }

    fun report(
        observations: List<RuntimeEvalObservation>,
        routing: RoutingTuning,
        generatedAt: Long = System.currentTimeMillis()
    ): RuntimeEvalReport {
        val ordered = observations.sortedByDescending { it.createdAt }
        val groups = ordered
            .groupBy {
                GroupKey(
                    nodeId = it.nodeId,
                    taskKind = it.taskKind,
                    model = it.model,
                    brainProfile = it.brainProfile.trim().lowercase()
                )
            }
            .values
            .map(::aggregateGroup)
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
     * Avant le minimum de preuves, le matériel et l'ancien TaskLedger restent
     * le prior. La confiance augmente ensuite jusqu'au seuil fort compilé dans
     * SafetyPolicy afin qu'une seule mesure chanceuse ne puisse pas dominer le
     * routage.
     */
    fun posteriorAdjustment(
        stats: RuntimeEvalStats?,
        routing: RoutingTuning
    ): Double {
        if (
            stats == null ||
            stats.samples < SafetyPolicy.MIN_RUNTIME_EVAL_POSTERIOR_SAMPLES
        ) {
            return 0.0
        }

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

        return confidence * (
            reliability + latency + throughput -
                fallbackPenalty - failurePenalty
            )
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
            lastObservedAt = last.createdAt
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
        return (reliability + latency + throughput - fallbackPenalty)
            .coerceIn(0.0, 100.0)
    }

    private fun percentile90(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val index = (ceil(values.size * 0.90).toInt() - 1)
            .coerceIn(0, values.lastIndex)
        return values[index]
    }
}
