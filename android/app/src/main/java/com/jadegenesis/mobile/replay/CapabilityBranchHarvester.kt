package com.jadegenesis.mobile.replay

data class CapabilityHarvestedBranch(
    val sourceTraceId: String,
    val operation: String,
    val incumbentProviderId: String?,
    val incumbentNodeId: String?,
    val challengerProviderId: String,
    val challengerNodeId: String?,
    val costClass: String,
    val requiresNetwork: Boolean,
    val reason: String
)

data class CapabilityBranchHarvestReport(
    val totalTraces: Int,
    val tracesWithCounterfactuals: Int,
    val tracesWithoutCounterfactuals: Int,
    val harvestedBranches: Int,
    val branches: List<CapabilityHarvestedBranch>
) {
    val coverageRate: Double
        get() = if (totalTraces == 0) {
            0.0
        } else {
            tracesWithCounterfactuals.toDouble() / totalTraces.toDouble()
        }

    val hasCoverage: Boolean
        get() = totalTraces > 0 && tracesWithCounterfactuals > 0
}

/**
 * Extracts policy-safe counterfactual branches from recorded capability
 * decisions.
 *
 * V0 never executes a provider and never invents an unobserved provider.
 * It only exposes alternatives that were present in the original trace and
 * that satisfy the recorded free-first policy boundary.
 */
class CapabilityBranchHarvester {

    fun harvest(
        trace: CapabilityDecisionTrace
    ): List<CapabilityHarvestedBranch> {
        val operation = trace.operation.trim().lowercase()

        return trace.alternatives
            .asSequence()
            .filter { it.available }
            .filter { alternative ->
                alternative.operations.any {
                    it.trim().lowercase() == operation
                }
            }
            .filter { policyAllows(it, trace) }
            .filterNot {
                it.providerId == trace.chosenProviderId &&
                    it.nodeId == trace.chosenNodeId
            }
            .sortedWith(
                compareBy<CapabilityAlternativeTrace>(
                    { costRank(it.costClass, trace.preferLocalFree) },
                    { if (it.requiresNetwork) 1 else 0 },
                    { it.displayName.lowercase() },
                    { it.providerId },
                    { it.nodeId.orEmpty() }
                )
            )
            .map { alternative ->
                CapabilityHarvestedBranch(
                    sourceTraceId = trace.traceId,
                    operation = trace.operation,
                    incumbentProviderId = trace.chosenProviderId,
                    incumbentNodeId = trace.chosenNodeId,
                    challengerProviderId = alternative.providerId,
                    challengerNodeId = alternative.nodeId,
                    costClass = alternative.costClass,
                    requiresNetwork = alternative.requiresNetwork,
                    reason =
                        "Observed policy-safe alternative from the original capability decision trace."
                )
            }
            .toList()
    }

    fun evaluate(
        traces: List<CapabilityDecisionTrace>
    ): CapabilityBranchHarvestReport {
        val perTrace = traces.associateWith(::harvest)
        val covered = perTrace.count { (_, branches) -> branches.isNotEmpty() }
        val branches = perTrace.values.flatten()

        return CapabilityBranchHarvestReport(
            totalTraces = traces.size,
            tracesWithCounterfactuals = covered,
            tracesWithoutCounterfactuals = traces.size - covered,
            harvestedBranches = branches.size,
            branches = branches
        )
    }

    private fun policyAllows(
        alternative: CapabilityAlternativeTrace,
        trace: CapabilityDecisionTrace
    ): Boolean = when (alternative.costClass.uppercase()) {
        "LOCAL_FREE" -> true
        "CLOUD_FREE" -> trace.allowCloudFreeFallback
        "PAID" -> trace.allowPaidProviders
        else -> false
    }

    private fun costRank(
        costClass: String,
        preferLocalFree: Boolean
    ): Int = when (costClass.uppercase()) {
        "LOCAL_FREE" -> if (preferLocalFree) 0 else 1
        "CLOUD_FREE" -> if (preferLocalFree) 1 else 0
        "PAID" -> 2
        else -> 3
    }
}
