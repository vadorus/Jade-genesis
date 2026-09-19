package com.jadegenesis.mobile.replay

import kotlin.math.ceil

data class CapabilityRepeatedPairReport(
    val providerId: String,
    val operation: String,
    val incumbentNodeId: String,
    val challengerNodeId: String,
    val roundsRequested: Int,
    val roundsCompleted: Int,
    val incumbentVerifiedRounds: Int,
    val challengerVerifiedRounds: Int,
    val bothVerifiedRounds: Int,
    val comparableLatencyRounds: Int,
    val incumbentFasterRounds: Int,
    val challengerFasterRounds: Int,
    val tiedRounds: Int,
    val incumbentMedianMs: Long?,
    val challengerMedianMs: Long?,
    val incumbentP95Ms: Long?,
    val challengerP95Ms: Long?,
    val medianLatencyDeltaMs: Long?,
    val incumbentEndToEndMedianMs: Long? = null,
    val challengerEndToEndMedianMs: Long? = null,
    val automaticPromotionAllowed: Boolean = false
)

object CapabilityRepeatedPairAnalyzer {

    fun analyze(
        comparisons: List<CapabilityPairedComparison>,
        roundsRequested: Int
    ): CapabilityRepeatedPairReport {
        require(roundsRequested > 0) {
            "roundsRequested must be positive."
        }
        require(comparisons.size <= roundsRequested) {
            "Completed comparisons cannot exceed requested rounds."
        }

        val first = comparisons.firstOrNull()
            ?: return CapabilityRepeatedPairReport(
                providerId = "",
                operation = "",
                incumbentNodeId = "",
                challengerNodeId = "",
                roundsRequested = roundsRequested,
                roundsCompleted = 0,
                incumbentVerifiedRounds = 0,
                challengerVerifiedRounds = 0,
                bothVerifiedRounds = 0,
                comparableLatencyRounds = 0,
                incumbentFasterRounds = 0,
                challengerFasterRounds = 0,
                tiedRounds = 0,
                incumbentMedianMs = null,
                challengerMedianMs = null,
                incumbentP95Ms = null,
                challengerP95Ms = null,
                medianLatencyDeltaMs = null,
                incumbentEndToEndMedianMs = null,
                challengerEndToEndMedianMs = null,
                automaticPromotionAllowed = false
            )

        comparisons.forEach { comparison ->
            require(comparison.providerId == first.providerId) {
                "Repeated comparisons must use one provider."
            }
            require(comparison.operation == first.operation) {
                "Repeated comparisons must use one operation."
            }
            require(comparison.incumbentNodeId == first.incumbentNodeId) {
                "Repeated comparisons changed incumbent node."
            }
            require(comparison.challengerNodeId == first.challengerNodeId) {
                "Repeated comparisons changed challenger node."
            }
        }

        val incumbentVerified = comparisons.filter { it.incumbentVerified }
        val challengerVerified = comparisons.filter { it.challengerVerified }
        val comparable = comparisons.filter { it.latencyComparable }

        val incumbentDurations =
            incumbentVerified.map { it.incumbentDurationMs }
        val challengerDurations =
            challengerVerified.map { it.challengerDurationMs }
        val latencyDeltas =
            comparable.mapNotNull { it.latencyDeltaMs }
        val incumbentEndToEnd =
            incumbentVerified.map { it.incumbentEndToEndMs }
        val challengerEndToEnd =
            challengerVerified.map { it.challengerEndToEndMs }

        val incumbentFaster = comparable.count {
            it.fasterNodeId == first.incumbentNodeId
        }
        val challengerFaster = comparable.count {
            it.fasterNodeId == first.challengerNodeId
        }
        val tied = comparable.size - incumbentFaster - challengerFaster

        return CapabilityRepeatedPairReport(
            providerId = first.providerId,
            operation = first.operation,
            incumbentNodeId = first.incumbentNodeId,
            challengerNodeId = first.challengerNodeId,
            roundsRequested = roundsRequested,
            roundsCompleted = comparisons.size,
            incumbentVerifiedRounds = incumbentVerified.size,
            challengerVerifiedRounds = challengerVerified.size,
            bothVerifiedRounds = comparisons.count {
                it.status == CapabilityPairStatus.BOTH_VERIFIED
            },
            comparableLatencyRounds = comparable.size,
            incumbentFasterRounds = incumbentFaster,
            challengerFasterRounds = challengerFaster,
            tiedRounds = tied,
            incumbentMedianMs = median(incumbentDurations),
            challengerMedianMs = median(challengerDurations),
            incumbentP95Ms = percentile95(incumbentDurations),
            challengerP95Ms = percentile95(challengerDurations),
            medianLatencyDeltaMs = median(latencyDeltas),
            incumbentEndToEndMedianMs = median(incumbentEndToEnd),
            challengerEndToEndMedianMs = median(challengerEndToEnd),
            automaticPromotionAllowed = false
        )
    }

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2L
        }
    }

    private fun percentile95(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val rank = ceil(sorted.size * 0.95).toInt()
            .coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}
