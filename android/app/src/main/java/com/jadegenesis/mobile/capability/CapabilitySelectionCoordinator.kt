package com.jadegenesis.mobile.capability

import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.replay.CapabilityAlternativeTrace
import com.jadegenesis.mobile.replay.CapabilityDecisionTrace
import java.util.UUID

data class CapabilitySelectionOutcome(
    val selected: CapabilityDescriptor?,
    val trace: CapabilityDecisionTrace
)

/**
 * Selection-only orchestration for discovered capabilities.
 *
 * No provider is executed here. The coordinator creates a fresh registry from
 * current node discovery, applies the free-first policy, records the complete
 * provider choice context and returns the selected descriptor.
 */
class CapabilitySelectionCoordinator(
    private val policyProvider: () -> CapabilitySelectionPolicy = {
        CapabilitySelectionPolicy.freeOnly()
    },
    private val traceSink: (CapabilityDecisionTrace) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val traceIdProvider: () -> String = {
        "capability-${UUID.randomUUID()}"
    }
) {

    fun select(
        operation: String,
        nodes: List<GenesisNode>
    ): CapabilitySelectionOutcome {
        val normalizedOperation = operation.trim().lowercase()
        require(normalizedOperation.isNotBlank()) {
            "Capability operation must not be blank."
        }

        val startedAt = clock()
        val policy = policyProvider()
        val registry = CapabilityRegistry { policy }
        val discovered = NodeCapabilityBridge.discovered(nodes)
        registry.registerAll(discovered)

        val eligible = registry.availableFor(normalizedOperation)
        val selected = eligible.firstOrNull()
        val eligibleInstances = eligible
            .map { instanceKey(it) }
            .toSet()

        val alternatives = discovered
            .filter { it.supports(normalizedOperation) }
            .map { descriptor ->
                CapabilityAlternativeTrace(
                    providerId = descriptor.id,
                    displayName = descriptor.displayName,
                    nodeId = descriptor.nodeId,
                    providerType = descriptor.providerType.name,
                    costClass = descriptor.costClass.name,
                    available = descriptor.available,
                    eligible =
                        instanceKey(descriptor) in eligibleInstances,
                    requiresNetwork = descriptor.requiresNetwork,
                    operations =
                        descriptor.operations.sorted()
                )
            }

        val trace = CapabilityDecisionTrace(
            traceId = traceIdProvider(),
            decisionKind = "capability_selection",
            operation = normalizedOperation,
            policyName = "free_first_v0",
            preferLocalFree = policy.preferLocalFree,
            allowCloudFreeFallback =
                policy.allowCloudFreeFallback,
            allowPaidProviders =
                policy.allowPaidProviders,
            alternatives = alternatives,
            chosenProviderId = selected?.id,
            chosenNodeId = selected?.nodeId,
            startedAt = startedAt,
            completedAt = clock()
        )
        traceSink(trace)

        return CapabilitySelectionOutcome(
            selected = selected,
            trace = trace
        )
    }

    private fun instanceKey(
        descriptor: CapabilityDescriptor
    ): String = buildString {
        append(descriptor.id)
        append("@")
        append(descriptor.nodeId.orEmpty())
    }
}
