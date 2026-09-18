package com.jadegenesis.mobile.capability

import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.NodeStatus

/**
 * Converts the Node Runtime's backward-compatible capability markers into
 * typed CapabilityDescriptor instances.
 *
 * Wire marker format:
 *
 *     local_free:<catalog-id>
 *
 * Unknown ids are ignored fail-closed. A marker is trusted only while its node
 * is LOCAL or ONLINE.
 */
object NodeCapabilityBridge {
    const val LOCAL_FREE_PREFIX = "local_free:"

    fun discovered(nodes: List<GenesisNode>): List<CapabilityDescriptor> {
        val catalog = LocalFreeCapabilityCatalog
            .eligibleProviders()
            .associateBy { it.id }

        return nodes
            .asSequence()
            .filter {
                it.status == NodeStatus.LOCAL ||
                    it.status == NodeStatus.ONLINE
            }
            .flatMap { node ->
                node.capabilities
                    .asSequence()
                    .filter { it.startsWith(LOCAL_FREE_PREFIX) }
                    .mapNotNull { marker ->
                        val catalogId = marker
                            .removePrefix(LOCAL_FREE_PREFIX)
                            .trim()
                        val template = catalog[catalogId]
                            ?: return@mapNotNull null

                        template.copy(
                            available = true,
                            nodeId = node.nodeId,
                            details = buildString {
                                append(template.details)
                                append(" Discovered on ")
                                append(node.name)
                                append(" (")
                                append(node.nodeId)
                                append(").")
                            }
                        )
                    }
            }
            .distinctBy { "${it.id}@${it.nodeId.orEmpty()}" }
            .toList()
    }

    fun populate(
        registry: CapabilityRegistry,
        nodes: List<GenesisNode>
    ): List<CapabilityDescriptor> {
        val discovered = discovered(nodes)
        registry.registerAll(discovered)
        return discovered
    }
}
