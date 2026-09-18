package com.jadegenesis.mobile.replay

data class CapabilityManualChallengerPolicy(
    val policyId: String,
    val operation: String,
    val preferredProviderId: String,
    val preferredNodeId: String? = null
) {
    init {
        require(policyId.isNotBlank()) { "Challenger policy id must not be blank." }
        require(operation.isNotBlank()) { "Challenger operation must not be blank." }
        require(preferredProviderId.isNotBlank()) {
            "Preferred provider id must not be blank."
        }
    }
}

data class CapabilityShadowObservation(
    val traceId: String,
    val policyId: String,
    val operation: String,
    val applicable: Boolean,
    val targetObserved: Boolean,
    val incumbentProviderId: String?,
    val incumbentNodeId: String?,
    val shadowProviderId: String?,
    val shadowNodeId: String?,
    val changed: Boolean,
    val reason: String
)

data class CapabilityShadowReport(
    val policyId: String,
    val totalTraces: Int,
    val applicableTraces: Int,
    val skippedOtherOperation: Int,
    val tracesWithTargetObserved: Int,
    val tracesWithoutTargetObserved: Int,
    val changedChoices: Int,
    val unchangedChoices: Int,
    val observations: List<CapabilityShadowObservation>
) {
    val changeRate: Double
        get() = if (applicableTraces == 0) {
            0.0
        } else {
            changedChoices.toDouble() / applicableTraces.toDouble()
        }

    val hasComparableEvidence: Boolean
        get() = tracesWithTargetObserved > 0
}

/**
 * Manual challenger shadow evaluator for Capability Replay Lab V0.
 *
 * This class never executes a provider and never mutates production policy.
 * It can only choose an already observed, available and free provider from
 * the original CapabilityDecisionTrace. If the requested challenger was not
 * observed in that historical decision, the incumbent remains selected.
 */
class CapabilityShadowLab(
    private val harvester: CapabilityBranchHarvester =
        CapabilityBranchHarvester()
) {

    fun compare(
        trace: CapabilityDecisionTrace,
        policy: CapabilityManualChallengerPolicy
    ): CapabilityShadowObservation {
        val traceOperation = trace.operation.trim().lowercase()
        val policyOperation = policy.operation.trim().lowercase()

        if (traceOperation != policyOperation) {
            return CapabilityShadowObservation(
                traceId = trace.traceId,
                policyId = policy.policyId,
                operation = trace.operation,
                applicable = false,
                targetObserved = false,
                incumbentProviderId = trace.chosenProviderId,
                incumbentNodeId = trace.chosenNodeId,
                shadowProviderId = trace.chosenProviderId,
                shadowNodeId = trace.chosenNodeId,
                changed = false,
                reason = "Trace operation is outside this manual challenger policy."
            )
        }

        val preferredProvider = policy.preferredProviderId.trim()
        val preferredNode = policy.preferredNodeId?.trim()
            ?.takeIf { it.isNotBlank() }

        val branches = harvester.harvest(trace)
            .asSequence()
            .filter { it.costClass.uppercase() in FREE_COST_CLASSES }
            .filter { it.challengerProviderId == preferredProvider }
            .filter {
                preferredNode == null || it.challengerNodeId == preferredNode
            }
            .toList()

        val selectedBranch = branches.firstOrNull()
        if (selectedBranch == null) {
            return CapabilityShadowObservation(
                traceId = trace.traceId,
                policyId = policy.policyId,
                operation = trace.operation,
                applicable = true,
                targetObserved = false,
                incumbentProviderId = trace.chosenProviderId,
                incumbentNodeId = trace.chosenNodeId,
                shadowProviderId = trace.chosenProviderId,
                shadowNodeId = trace.chosenNodeId,
                changed = false,
                reason =
                    "Requested free challenger was not an observed policy-safe alternative; incumbent preserved."
            )
        }

        val changed =
            selectedBranch.challengerProviderId != trace.chosenProviderId ||
                selectedBranch.challengerNodeId != trace.chosenNodeId

        return CapabilityShadowObservation(
            traceId = trace.traceId,
            policyId = policy.policyId,
            operation = trace.operation,
            applicable = true,
            targetObserved = true,
            incumbentProviderId = trace.chosenProviderId,
            incumbentNodeId = trace.chosenNodeId,
            shadowProviderId = selectedBranch.challengerProviderId,
            shadowNodeId = selectedBranch.challengerNodeId,
            changed = changed,
            reason =
                "Manual challenger selected an observed free alternative in shadow only."
        )
    }

    fun evaluate(
        traces: List<CapabilityDecisionTrace>,
        policy: CapabilityManualChallengerPolicy
    ): CapabilityShadowReport {
        val observations = traces.map { compare(it, policy) }
        val applicable = observations.filter { it.applicable }
        val withTarget = applicable.count { it.targetObserved }
        val changed = applicable.count { it.changed }

        return CapabilityShadowReport(
            policyId = policy.policyId,
            totalTraces = observations.size,
            applicableTraces = applicable.size,
            skippedOtherOperation = observations.size - applicable.size,
            tracesWithTargetObserved = withTarget,
            tracesWithoutTargetObserved = applicable.size - withTarget,
            changedChoices = changed,
            unchangedChoices = applicable.size - changed,
            observations = observations
        )
    }

    private companion object {
        val FREE_COST_CLASSES = setOf("LOCAL_FREE", "CLOUD_FREE")
    }
}
