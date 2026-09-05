package com.jadegenesis.mobile.mesh

import com.jadegenesis.mobile.diagnostics.DiagnosticLogger
import com.jadegenesis.mobile.model.DeviceProfile
import com.jadegenesis.mobile.model.DiagnosticLevel
import com.jadegenesis.mobile.model.DistributedTaskRequest
import com.jadegenesis.mobile.model.MeshNodeResult
import com.jadegenesis.mobile.model.MeshProbeSummary
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.model.TaskWorkload
import com.jadegenesis.mobile.node.NodeManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID

class ComputeMesh(
    private val nodeManager: NodeManager,
    private val logger: DiagnosticLogger
) {
    suspend fun runParallelProbe(device: DeviceProfile): MeshProbeSummary {
        val startedAt = System.currentTimeMillis()
        val nodes = nodeManager.nodes(device = device, refreshRemote = true)
        val candidates = nodes.filter {
            it.kind != NodeKind.PHONE &&
                it.status == NodeStatus.ONLINE &&
                "task_execution_v3" in it.capabilities &&
                "genesis_probe" in it.capabilities
        }

        logger.log(
            DiagnosticLevel.INFO,
            "mesh_probe_start",
            "Benchmark parallèle du Compute Mesh.",
            mapOf("candidate_count" to candidates.size)
        )

        val results = coroutineScope {
            candidates.map { node ->
                async {
                    val request = DistributedTaskRequest(
                        taskId = "mesh-${UUID.randomUUID()}",
                        taskKind = "genesis_probe",
                        payload = "jade-genesis-mesh:${node.nodeId}:${System.currentTimeMillis()}",
                        requiredCapability = "genesis_probe",
                        workload = TaskWorkload.MEDIUM,
                        iterations = 18_000,
                        createdAt = System.currentTimeMillis()
                    )
                    val startedNs = System.nanoTime()
                    runCatching {
                        nodeManager.executeTask(node.nodeId, request)
                    }.fold(
                        onSuccess = { response ->
                            MeshNodeResult(
                                nodeId = response.nodeId,
                                nodeName = response.nodeName,
                                success = true,
                                durationMs = maxOf(
                                    response.durationMs,
                                    (System.nanoTime() - startedNs) / 1_000_000L
                                ),
                                outputPreview = response.output.take(32)
                            )
                        },
                        onFailure = { error ->
                            MeshNodeResult(
                                nodeId = node.nodeId,
                                nodeName = node.name,
                                success = false,
                                durationMs = (System.nanoTime() - startedNs) / 1_000_000L,
                                error = error.message?.take(180) ?: error::class.java.simpleName
                            )
                        }
                    )
                }
            }.awaitAll()
        }

        val summary = MeshProbeSummary(
            startedAt = startedAt,
            completedAt = System.currentTimeMillis(),
            nodeResults = results
        )
        logger.log(
            if (summary.successCount == candidates.size) DiagnosticLevel.INFO else DiagnosticLevel.WARN,
            "mesh_probe_complete",
            "Compute Mesh : ${summary.successCount}/${candidates.size} nœud(s) ont terminé en parallèle.",
            mapOf("duration_ms" to (summary.completedAt - summary.startedAt))
        )
        return summary
    }
}
