package com.jadegenesis.mobile.replay

data class CapabilityReplayObservation(
    val traceId: String,
    val recordedProviderId: String?,
    val recordedNodeId: String?,
    val replayedProviderId: String?,
    val replayedNodeId: String?,
    val matched: Boolean,
    val reason: String
)

data class CapabilityAAReplayReport(
    val total: Int,
    val matched: Int,
    val mismatched: Int,
    val observations: List<CapabilityReplayObservation>
) {
    val matchRate: Double
        get() = if (total == 0) {
            1.0
        } else {
            matched.toDouble() / total.toDouble()
        }

    val passed: Boolean
        get() = total > 0 && mismatched == 0
}

/**
 * Independent A/A replay of the capability-selection policy.
 *
 * It never executes a provider. It recomputes eligibility and ordering only
 * from the captured decision context.
 */
class CapabilityReplayLab {

    fun replay(
        trace: CapabilityDecisionTrace
    ): CapabilityReplayObservation {
        val operation = trace.operation.trim().lowercase()

        val replayed = trace.alternatives
            .asSequence()
            .filter { alternative ->
                alternative.available &&
                    alternative.operations.any {
                        it.trim().lowercase() == operation
                    } &&
                    policyAllows(alternative, trace)
            }
            .sortedWith(
                compareBy<CapabilityAlternativeTrace>(
                    {
                        costRank(
                            it.costClass,
                            trace.preferLocalFree
                        )
                    },
                    { if (it.requiresNetwork) 1 else 0 },
                    { it.displayName.lowercase() },
                    { it.providerId },
                    { it.nodeId.orEmpty() }
                )
            )
            .firstOrNull()

        val recordedProvider = trace.chosenProviderId
        val recordedNode = trace.chosenNodeId
        val matched =
            replayed?.providerId == recordedProvider &&
                replayed?.nodeId == recordedNode

        return CapabilityReplayObservation(
            traceId = trace.traceId,
            recordedProviderId = recordedProvider,
            recordedNodeId = recordedNode,
            replayedProviderId = replayed?.providerId,
            replayedNodeId = replayed?.nodeId,
            matched = matched,
            reason = if (matched) {
                "A/A replay reproduced the recorded capability choice."
            } else {
                "A/A replay selected a different capability choice."
            }
        )
    }

    fun evaluate(
        traces: List<CapabilityDecisionTrace>
    ): CapabilityAAReplayReport {
        val observations = traces.map(::replay)
        val matched = observations.count { it.matched }

        return CapabilityAAReplayReport(
            total = observations.size,
            matched = matched,
            mismatched = observations.size - matched,
            observations = observations
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
