package com.jadegenesis.mobile.state

import com.jadegenesis.mobile.config.JadeConfigRuntime
import com.jadegenesis.mobile.config.SafetyPolicy
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

        var round = 0
        var uploadedTotal = 0
        var receivedTotal = 0
        var resetApplied = false
        var lastResult: SharedStateSyncResult? = null

        while (round < SafetyPolicy.MAX_SHARED_STATE_SYNC_ROUNDS) {
            round += 1
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
            uploadedTotal += result.uploadedEvents
            receivedTotal += result.receivedEvents
            resetApplied = resetApplied || result.resetApplied
            lastResult = result

            if (!result.hasMore && result.outboxRemaining == 0) break
        }

        val last = lastResult ?: error("Shared Genesis State n'a exécuté aucun tour de synchronisation.")
        val result = last.copy(
            uploadedEvents = uploadedTotal,
            receivedEvents = receivedTotal,
            resetApplied = resetApplied
        )

        logger?.log(
            DiagnosticLevel.INFO,
            "shared_state_sync_complete",
            "Shared Genesis State synchronisé avec ${result.nodeName}.",
            mapOf(
                "node_id" to result.nodeId,
                "server_revision" to result.serverRevision,
                "server_head_revision" to result.serverHeadRevision,
                "uploaded_events" to result.uploadedEvents,
                "received_events" to result.receivedEvents,
                "outbox_remaining" to result.outboxRemaining,
                "has_more" to result.hasMore,
                "sync_rounds" to round,
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
