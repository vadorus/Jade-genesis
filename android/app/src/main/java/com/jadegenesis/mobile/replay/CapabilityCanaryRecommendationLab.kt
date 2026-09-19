package com.jadegenesis.mobile.replay

enum class CapabilityCanaryRecommendationStatus {
    ELIGIBLE_FOR_MANUAL_CANARY,
    INCONCLUSIVE,
    INSUFFICIENT_EVIDENCE,
    CHALLENGER_VERIFICATION_REGRESSION
}

data class CapabilityCanaryRecommendation(
    val providerId: String,
    val operation: String,
    val incumbentNodeId: String,
    val challengerNodeId: String,
    val status: CapabilityCanaryRecommendationStatus,
    val reason: String,
    val roundsCompleted: Int,
    val bothVerifiedRounds: Int,
    val challengerFasterRounds: Int,
    val incumbentMedianMs: Long?,
    val challengerMedianMs: Long?,
    val medianImprovementPercent: Double?,
    val incumbentEndToEndMedianMs: Long? = null,
    val challengerEndToEndMedianMs: Long? = null,
    val requiresExplicitApproval: Boolean = true,
    val automaticPromotionAllowed: Boolean = false
)

/**
 * Conservative V0 canary recommendation.
 *
 * This class never changes routing. It only determines whether a repeated,
 * already-verified comparison has enough evidence to justify a *manual*
 * canary trial.
 */
object CapabilityCanaryRecommendationLab {

    private const val MIN_FASTER_FRACTION = 0.80
    private const val MIN_MEDIAN_IMPROVEMENT_PERCENT = 10.0

    fun evaluate(
        report: CapabilityRepeatedPairReport
    ): CapabilityCanaryRecommendation {
        val rounds = report.roundsCompleted
        val incumbentMedian = report.incumbentMedianMs
        val challengerMedian = report.challengerMedianMs

        if (
            rounds < CapabilityMeasurementProtocol.MIN_CANARY_ROUNDS ||
            !CapabilityMeasurementProtocol.isBalancedRoundCount(rounds)
        ) {
            return recommendation(
                report = report,
                status = CapabilityCanaryRecommendationStatus.INSUFFICIENT_EVIDENCE,
                reason =
                    "Au moins 6 tours pairs terminés sont requis avant un canary manuel.",
                improvement = null
            )
        }

        if (report.challengerVerifiedRounds < report.incumbentVerifiedRounds) {
            return recommendation(
                report = report,
                status = CapabilityCanaryRecommendationStatus.CHALLENGER_VERIFICATION_REGRESSION,
                reason = "Le challenger a moins de tours vérifiés que l'incumbent.",
                improvement = improvementPercent(incumbentMedian, challengerMedian)
            )
        }

        val allComparable =
            report.bothVerifiedRounds == rounds &&
                report.comparableLatencyRounds == rounds

        if (!allComparable || incumbentMedian == null || challengerMedian == null) {
            return recommendation(
                report = report,
                status = CapabilityCanaryRecommendationStatus.INCONCLUSIVE,
                reason = "Tous les tours ne sont pas vérifiés et comparables.",
                improvement = improvementPercent(incumbentMedian, challengerMedian)
            )
        }

        val fasterFraction =
            report.challengerFasterRounds.toDouble() / rounds.toDouble()
        val improvement =
            improvementPercent(incumbentMedian, challengerMedian)

        val eligible =
            fasterFraction >= MIN_FASTER_FRACTION &&
                improvement != null &&
                improvement >= MIN_MEDIAN_IMPROVEMENT_PERCENT

        return if (eligible) {
            recommendation(
                report = report,
                status = CapabilityCanaryRecommendationStatus.ELIGIBLE_FOR_MANUAL_CANARY,
                reason =
                    "Le challenger est plus rapide sur au moins 80 % des tours " +
                        "et améliore la médiane d'au moins 10 %.",
                improvement = improvement
            )
        } else {
            recommendation(
                report = report,
                status = CapabilityCanaryRecommendationStatus.INCONCLUSIVE,
                reason =
                    "Le signal de performance n'est pas assez stable pour proposer un canary manuel.",
                improvement = improvement
            )
        }
    }

    private fun improvementPercent(
        incumbentMedian: Long?,
        challengerMedian: Long?
    ): Double? {
        if (
            incumbentMedian == null ||
            challengerMedian == null ||
            incumbentMedian <= 0L
        ) {
            return null
        }
        return (
            (incumbentMedian - challengerMedian).toDouble() /
                incumbentMedian.toDouble()
            ) * 100.0
    }

    private fun recommendation(
        report: CapabilityRepeatedPairReport,
        status: CapabilityCanaryRecommendationStatus,
        reason: String,
        improvement: Double?
    ): CapabilityCanaryRecommendation =
        CapabilityCanaryRecommendation(
            providerId = report.providerId,
            operation = report.operation,
            incumbentNodeId = report.incumbentNodeId,
            challengerNodeId = report.challengerNodeId,
            status = status,
            reason = reason,
            roundsCompleted = report.roundsCompleted,
            bothVerifiedRounds = report.bothVerifiedRounds,
            challengerFasterRounds = report.challengerFasterRounds,
            incumbentMedianMs = report.incumbentMedianMs,
            challengerMedianMs = report.challengerMedianMs,
            medianImprovementPercent = improvement,
            incumbentEndToEndMedianMs = report.incumbentEndToEndMedianMs,
            challengerEndToEndMedianMs = report.challengerEndToEndMedianMs,
            requiresExplicitApproval = true,
            automaticPromotionAllowed = false
        )
}
