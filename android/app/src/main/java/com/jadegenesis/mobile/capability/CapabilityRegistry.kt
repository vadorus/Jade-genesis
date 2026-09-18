package com.jadegenesis.mobile.capability

/**
 * Monetary access class used by Jade when choosing an execution capability.
 *
 * LOCAL_FREE is always preferred by the default policy.
 * CLOUD_FREE is allowed only as a free fallback.
 * PAID is blocked by default and must never be selected accidentally.
 */
enum class CapabilityCostClass {
    LOCAL_FREE,
    CLOUD_FREE,
    PAID
}

enum class CapabilityProviderType {
    LOCAL_TOOL,
    LOCAL_MODEL,
    NODE_RUNTIME,
    EXTERNAL_PLUGIN,
    EXTERNAL_API
}

data class CapabilityDescriptor(
    val id: String,
    val displayName: String,
    val operations: Set<String>,
    val providerType: CapabilityProviderType,
    val costClass: CapabilityCostClass,
    val available: Boolean,
    val nodeId: String? = null,
    val requiresNetwork: Boolean = false,
    val details: String = ""
) {
    init {
        require(id.isNotBlank()) { "Capability id must not be blank." }
        require(displayName.isNotBlank()) {
            "Capability displayName must not be blank."
        }
        require(operations.isNotEmpty()) {
            "Capability operations must not be empty."
        }
        require(operations.none { it.isBlank() }) {
            "Capability operations must not contain blanks."
        }
    }

    fun supports(operation: String): Boolean =
        operation.trim().lowercase() in operations.map { it.trim().lowercase() }
}

data class CapabilitySelectionPolicy(
    val preferLocalFree: Boolean = true,
    val allowCloudFreeFallback: Boolean = true,
    val allowPaidProviders: Boolean = false
) {
    companion object {
        fun freeOnly(): CapabilitySelectionPolicy =
            CapabilitySelectionPolicy(
                preferLocalFree = true,
                allowCloudFreeFallback = true,
                allowPaidProviders = false
            )
    }
}

/**
 * Small declarative registry for Jade's N1 orchestration layer.
 *
 * V0 is deliberately conservative:
 * - no tool is executed here;
 * - no provider is installed here;
 * - no paid provider is selected by the default policy;
 * - availability must come from real discovery/probing elsewhere.
 */
class CapabilityRegistry(
    private val policyProvider: () -> CapabilitySelectionPolicy = {
        CapabilitySelectionPolicy.freeOnly()
    }
) {
    private val descriptors = linkedMapOf<String, CapabilityDescriptor>()

    fun register(descriptor: CapabilityDescriptor) {
        descriptors[instanceKey(descriptor)] = descriptor
    }

    fun registerAll(items: Iterable<CapabilityDescriptor>) {
        items.forEach(::register)
    }

    fun remove(id: String): Boolean {
        val direct = descriptors.remove(id) != null
        val matchingKeys = descriptors
            .filterValues { it.id == id }
            .keys
            .toList()
        matchingKeys.forEach(descriptors::remove)
        return direct || matchingKeys.isNotEmpty()
    }

    fun all(): List<CapabilityDescriptor> =
        descriptors.values.toList()

    fun get(id: String): CapabilityDescriptor? =
        descriptors[id] ?: descriptors.values.firstOrNull { it.id == id }

    private fun instanceKey(descriptor: CapabilityDescriptor): String {
        val node = descriptor.nodeId?.trim().orEmpty()
        return if (node.isBlank()) descriptor.id else "${descriptor.id}@$node"
    }

    fun availableFor(operation: String): List<CapabilityDescriptor> {
        val normalized = operation.trim().lowercase()
        if (normalized.isBlank()) {
            return emptyList()
        }

        return descriptors.values
            .asSequence()
            .filter { it.available }
            .filter { descriptor ->
                descriptor.operations.any {
                    it.trim().lowercase() == normalized
                }
            }
            .filter { isAllowed(it, policyProvider()) }
            .sortedWith(selectionComparator(policyProvider()))
            .toList()
    }

    fun select(operation: String): CapabilityDescriptor? =
        availableFor(operation).firstOrNull()

    private fun isAllowed(
        descriptor: CapabilityDescriptor,
        policy: CapabilitySelectionPolicy
    ): Boolean = when (descriptor.costClass) {
        CapabilityCostClass.LOCAL_FREE -> true
        CapabilityCostClass.CLOUD_FREE -> policy.allowCloudFreeFallback
        CapabilityCostClass.PAID -> policy.allowPaidProviders
    }

    private fun selectionComparator(
        policy: CapabilitySelectionPolicy
    ): Comparator<CapabilityDescriptor> =
        compareBy<CapabilityDescriptor>(
            { costRank(it.costClass, policy) },
            { if (it.requiresNetwork) 1 else 0 },
            { it.displayName.lowercase() },
            { it.id },
            { it.nodeId.orEmpty() }
        )

    private fun costRank(
        costClass: CapabilityCostClass,
        policy: CapabilitySelectionPolicy
    ): Int = when (costClass) {
        CapabilityCostClass.LOCAL_FREE ->
            if (policy.preferLocalFree) 0 else 1

        CapabilityCostClass.CLOUD_FREE ->
            if (policy.preferLocalFree) 1 else 0

        CapabilityCostClass.PAID -> 2
    }
}
