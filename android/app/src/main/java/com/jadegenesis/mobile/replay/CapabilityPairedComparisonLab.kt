package com.jadegenesis.mobile.replay

import java.util.UUID

enum class CapabilityPairStatus {
    BOTH_VERIFIED,
    INCUMBENT_ONLY_VERIFIED,
    CHALLENGER_ONLY_VERIFIED,
    NEITHER_VERIFIED
}

data class CapabilityPairedComparison(
    val comparisonId: String,
    val providerId: String,
    val operation: String,
    val incumbentEvidenceId: String,
    val incumbentNodeId: String,
    val incumbentNodeName: String,
    val incumbentSuccess: Boolean,
    val incumbentVerified: Boolean,
    val incumbentDurationMs: Long,
    val challengerEvidenceId: String,
    val challengerNodeId: String,
    val challengerNodeName: String,
    val challengerSuccess: Boolean,
    val challengerVerified: Boolean,
    val challengerDurationMs: Long,
    val status: CapabilityPairStatus,
    val latencyComparable: Boolean,
    val latencyDeltaMs: Long?,
    val fasterNodeId: String?,
    val automaticPromotionAllowed: Boolean = false
)

/**
 * V0 paired evaluator for one already-measured capability.
 *
 * Comparison order is deliberate:
 * 1) machine-verified success;
 * 2) latency only when both executions passed verification.
 *
 * The evaluator never executes a capability and never promotes a policy.
 */
class CapabilityPairedComparisonLab {

    fun compare(
        incumbent: CapabilityExecutionEvidence,
        challenger: CapabilityExecutionEvidence
    ): CapabilityPairedComparison {
        require(incumbent.providerId == challenger.providerId) {
            "Paired evidence providers must match."
        }
        require(incumbent.operation == challenger.operation) {
            "Paired evidence operations must match."
        }
        require(incumbent.nodeId != challenger.nodeId) {
            "Paired evidence must come from different nodes."
        }

        val incumbentVerified =
            incumbent.success && incumbent.verificationPassed
        val challengerVerified =
            challenger.success && challenger.verificationPassed

        val status = when {
            incumbentVerified && challengerVerified ->
                CapabilityPairStatus.BOTH_VERIFIED
            incumbentVerified ->
                CapabilityPairStatus.INCUMBENT_ONLY_VERIFIED
            challengerVerified ->
                CapabilityPairStatus.CHALLENGER_ONLY_VERIFIED
            else ->
                CapabilityPairStatus.NEITHER_VERIFIED
        }

        val latencyComparable =
            status == CapabilityPairStatus.BOTH_VERIFIED

        val latencyDeltaMs = if (latencyComparable) {
            challenger.durationMs - incumbent.durationMs
        } else {
            null
        }

        val fasterNodeId = if (!latencyComparable) {
            null
        } else {
            when {
                incumbent.durationMs < challenger.durationMs ->
                    incumbent.nodeId
                challenger.durationMs < incumbent.durationMs ->
                    challenger.nodeId
                else -> null
            }
        }

        return CapabilityPairedComparison(
            comparisonId = "comparison-${UUID.randomUUID()}",
            providerId = incumbent.providerId,
            operation = incumbent.operation,
            incumbentEvidenceId = incumbent.evidenceId,
            incumbentNodeId = incumbent.nodeId,
            incumbentNodeName = incumbent.nodeName,
            incumbentSuccess = incumbent.success,
            incumbentVerified = incumbentVerified,
            incumbentDurationMs = incumbent.durationMs,
            challengerEvidenceId = challenger.evidenceId,
            challengerNodeId = challenger.nodeId,
            challengerNodeName = challenger.nodeName,
            challengerSuccess = challenger.success,
            challengerVerified = challengerVerified,
            challengerDurationMs = challenger.durationMs,
            status = status,
            latencyComparable = latencyComparable,
            latencyDeltaMs = latencyDeltaMs,
            fasterNodeId = fasterNodeId,
            automaticPromotionAllowed = false
        )
    }

    fun latestForNodes(
        evidence: List<CapabilityExecutionEvidence>,
        incumbentNodeId: String,
        challengerNodeId: String,
        providerId: String = "ffmpeg-local",
        operation: String = "media_transcode_probe"
    ): CapabilityPairedComparison? {
        val incumbent = evidence.firstOrNull {
            it.nodeId == incumbentNodeId &&
                it.providerId == providerId &&
                it.operation == operation
        } ?: return null

        val challenger = evidence.firstOrNull {
            it.nodeId == challengerNodeId &&
                it.providerId == providerId &&
                it.operation == operation
        } ?: return null

        return compare(incumbent, challenger)
    }
}
