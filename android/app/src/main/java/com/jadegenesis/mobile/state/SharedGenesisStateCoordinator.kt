package com.jadegenesis.mobile.state

import com.jadegenesis.mobile.config.JadeConfigRuntime
import com.jadegenesis.mobile.diagnostics.DiagnosticLogger
import com.jadegenesis.mobile.model.DeviceProfile
import com.jadegenesis.mobile.model.DiagnosticLevel
import com.jadegenesis.mobile.model.DistributedTaskRequest
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.model.TaskWorkload
import com.jadegenesis.mobile.node.NodeManager
import com.jadegenesis.mobile.resource.NodeResourceScorer
import java.util.UUID

class SharedGenesisStateCoordinator(
    private val nodeManager: NodeManager,
    private val store: SharedGenesisStateStore,
    private val logger: DiagnosticLogger? = null
) {
    suspend fun sync(
        identityId: String,
        replicaId: String,
        device: DeviceProfile
    ): SharedStateSyncResult {
        val nodes = nodeManager.nodes(device = device, refreshRemote = true)
        val routing = JadeConfigRuntime.current().validated().routing
        val target = nodes
            .filter(::isStateReplica)
            .maxByOrNull { NodeResourceScorer.genericScore(it, routing) }
            ?: error(
                "Aucun VPS en ligne n'annonce Shared Genesis State v1. " +
                    "Les événements restent dans l'outbox locale."
            )

        val envelope = store.buildSyncRequest(
            identityId = identityId,
            replicaId = replicaId
        )
        val request = DistributedTaskRequest(
            taskId = "state-${UUID.randomUUID()}",
            taskKind = "shared_state_sync",
            payload = envelope.payload,
            requiredCapability = "shared_state_sync",
            workload = TaskWorkload.LIGHT,
            createdAt = System.currentTimeMillis()
        )
        val response = nodeManager.executeTask(
            nodeId = target.nodeId,
            request = request
        )
        val result = store.applySyncResponse(
            raw = response.output,
            expectedIdentityId = identityId,
            expectedReplicaId = replicaId,
            uploadedEventIds = envelope.uploadedEventIds,
            nodeId = response.nodeId,
            nodeName = response.nodeName
        )

        logger?.log(
            DiagnosticLevel.INFO,
            "shared_state_sync_complete",
            "Shared Genesis State synchronisé avec ${result.nodeName}.",
            mapOf(
                "node_id" to result.nodeId,
                "server_revision" to result.serverRevision,
                "uploaded_events" to result.uploadedEvents,
                "received_events" to result.receivedEvents,
                "outbox_remaining" to result.outboxRemaining,
                "reset_applied" to result.resetApplied
            )
        )
        return result
    }

    fun publish(
        originNode: String,
        kind: String,
        entityId: String,
        payload: String
    ): SharedStateEvent = store.publish(
        originNode = originNode,
        kind = kind,
        entityId = entityId,
        payload = payload
    )

    fun cachedEvents(): List<SharedStateEvent> = store.cachedEvents()

    fun outboxSize(): Int = store.outbox().size

    fun knownRevision(): Long = store.knownRevision()

    fun lastSyncAt(): Long = store.lastSyncAt()

    private fun isStateReplica(node: GenesisNode): Boolean =
        node.kind == NodeKind.VPS &&
            node.status == NodeStatus.ONLINE &&
            "task_execution_v3" in node.capabilities &&
            "shared_genesis_state_v1" in node.capabilities &&
            "shared_state_sync" in node.capabilities
}
