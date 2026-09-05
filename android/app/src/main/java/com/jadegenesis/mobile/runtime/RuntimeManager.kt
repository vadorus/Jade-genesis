package com.jadegenesis.mobile.runtime

import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.model.RuntimeNodeSnapshot

class RuntimeManager {
    companion object {
        const val EXPECTED_RUNTIME_VERSION = "0.1.0"
    }

    fun snapshots(nodes: List<GenesisNode>): List<RuntimeNodeSnapshot> =
        nodes.filter { it.kind != NodeKind.PHONE }
            .map { node ->
                RuntimeNodeSnapshot(
                    nodeId = node.nodeId,
                    nodeName = node.name,
                    runtimeVersion = node.runtimeVersion.ifBlank { "inconnue" },
                    channel = node.runtimeChannel.ifBlank { "stable" },
                    online = node.status == NodeStatus.ONLINE,
                    updateAvailable =
                        node.runtimeVersion.isNotBlank() &&
                            node.runtimeVersion != EXPECTED_RUNTIME_VERSION
                )
            }
}
