package com.jadegenesis.mobile.ui

import com.jadegenesis.mobile.model.BrainBackendType
import com.jadegenesis.mobile.model.BrainInfo
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus

internal fun brainStatusLabel(brain: BrainInfo?, nodes: List<GenesisNode>): String {
    if (brain == null) return "inconnu"
    val unverifiedRemoteBrain = nodes.any {
        it.kind != NodeKind.PHONE && it.status == NodeStatus.UNKNOWN &&
            it.lastSeenAt > 0L &&
            listOf("task_execution_v3", "local_brain", "brain_chat")
                .all { capability -> capability in it.capabilities }
    }
    return if (brain.backendType == BrainBackendType.PROTOTYPE && unverifiedRemoteBrain) {
        "Cerveau distant — état à rafraîchir"
    } else {
        brain.displayName
    }
}
