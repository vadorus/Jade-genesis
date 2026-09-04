package com.jadegenesis.mobile.task

import com.jadegenesis.mobile.model.DeviceProfile
import com.jadegenesis.mobile.model.DistributedTaskResult
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.model.ResourceBudget
import com.jadegenesis.mobile.model.TaskExecutionLocation
import com.jadegenesis.mobile.node.NodeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

class TaskRouter(
    private val nodeManager: NodeManager,
    private val localNodeId: () -> String
) {
    companion object {
        private const val PROBE_ITERATIONS = 20_000
    }

    suspend fun runGenesisProbe(
        identityId: String,
        device: DeviceProfile,
        budget: ResourceBudget
    ): DistributedTaskResult {
        val taskId = "task-${UUID.randomUUID()}"
        val payload = listOf(
            "jade-genesis",
            identityId,
            taskId,
            System.currentTimeMillis().toString()
        ).joinToString(":")

        val nodes = nodeManager.nodes(
            device = device,
            refreshRemote = true
        )
        val preferred = nodeManager.preferredComputeNode(
            nodes = nodes,
            budget = budget
        )
        val local = nodes.firstOrNull {
            it.status == NodeStatus.LOCAL
        } ?: nodeManager.localNode(device)

        val remoteTarget = preferred?.takeIf {
            it.kind != NodeKind.PHONE &&
                it.status == NodeStatus.ONLINE &&
                "task_execution_v1" in it.capabilities &&
                "genesis_probe" in it.capabilities
        }

        if (remoteTarget != null) {
            val remote = runCatching {
                nodeManager.executeGenesisProbe(
                    nodeId = remoteTarget.nodeId,
                    taskId = taskId,
                    payload = payload,
                    iterations = PROBE_ITERATIONS
                )
            }

            remote.getOrNull()?.let { response ->
                return DistributedTaskResult(
                    taskId = taskId,
                    taskKind = "genesis_probe",
                    requestedNodeId = remoteTarget.nodeId,
                    requestedNodeName = remoteTarget.name,
                    executedNodeId = response.nodeId,
                    executedNodeName = response.nodeName,
                    executionLocation = TaskExecutionLocation.REMOTE,
                    success = true,
                    output = response.output,
                    durationMs = response.durationMs,
                    fallbackUsed = false,
                    completedAt = System.currentTimeMillis()
                )
            }

            val reason = remote.exceptionOrNull()
                ?.message
                ?.take(180)
                ?: "Échec distant inconnu."

            return executeLocal(
                taskId = taskId,
                payload = payload,
                local = local,
                requested = remoteTarget,
                fallbackUsed = true,
                fallbackReason = reason
            )
        }

        return executeLocal(
            taskId = taskId,
            payload = payload,
            local = local,
            requested = preferred,
            fallbackUsed = false,
            fallbackReason = null
        )
    }

    private suspend fun executeLocal(
        taskId: String,
        payload: String,
        local: GenesisNode,
        requested: GenesisNode?,
        fallbackUsed: Boolean,
        fallbackReason: String?
    ): DistributedTaskResult = withContext(Dispatchers.Default) {
        val started = System.nanoTime()
        var data = payload.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")

        repeat(PROBE_ITERATIONS) {
            data = digest.digest(data)
        }

        val durationMs = (System.nanoTime() - started) / 1_000_000L
        val output = data.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

        DistributedTaskResult(
            taskId = taskId,
            taskKind = "genesis_probe",
            requestedNodeId = requested?.nodeId,
            requestedNodeName = requested?.name,
            executedNodeId = localNodeId(),
            executedNodeName = local.name,
            executionLocation = TaskExecutionLocation.LOCAL,
            success = true,
            output = output,
            durationMs = durationMs,
            fallbackUsed = fallbackUsed,
            fallbackReason = fallbackReason,
            completedAt = System.currentTimeMillis()
        )
    }
}
