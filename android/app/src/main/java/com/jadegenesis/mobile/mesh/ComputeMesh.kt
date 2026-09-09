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
import com.jadegenesis.mobile.resource.ResourceAdmissionController
import com.jadegenesis.mobile.resource.ResourceGovernor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID

class ComputeMesh(
    private val nodeManager: NodeManager,
    private val logger: DiagnosticLogger,
    private val admissionController: ResourceAdmissionController =
        ResourceAdmissionController()
) {
    suspend fun runParallelProbe(device: DeviceProfile): MeshProbeSummary {
        val startedAt = System.currentTimeMillis()
        val nodes = nodeManager.nodes(device = device, refreshRemote = true)
        val budget = ResourceGovernor().evaluate(device)
        val compatible = nodes
            .filter {
                it.kind != NodeKind.PHONE &&
                    it.status == NodeStatus.ONLINE &&
                    "task_execution_v3" in it.capabilities &&
                    "genesis_probe" in it.capabilities
            }
            .sortedWith(
                compareByDescending<com.jadegenesis.mobile.model.GenesisNode> {
                    it.ramAvailableGb
                }.thenByDescending {
                    it.cpuCores
                }
            )
        val candidates = compatible.take(
            budget.maxParallelTasks.coerceAtLeast(1)
        )

        logger.log(
            DiagnosticLevel.INFO,
            "mesh_probe_start",
            "Benchmark parallèle du Compute Mesh avec Resource Lease.",
            mapOf(
                "candidate_count" to candidates.size,
                "compatible_count" to compatible.size,
                "max_parallel_tasks" to budget.maxParallelTasks,
                "resource_mode" to budget.mode.name
            )
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
                    val admission = admissionController.tryAcquire(
                        request = request,
                        node = node,
                        budget = budget
                    )
                    val lease = admission.lease
                    if (!admission.admitted || lease == null) {
                        return@async MeshNodeResult(
                            nodeId = node.nodeId,
                            nodeName = node.name,
                            success = false,
                            durationMs = 0L,
                            error =
                                "Admission ${admission.action}: ${admission.reason}"
                                    .take(180)
                        )
                    }

                    val startedNs = System.nanoTime()
                    try {
                        val response = nodeManager.executeTask(node.nodeId, request)
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
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        MeshNodeResult(
                            nodeId = node.nodeId,
                            nodeName = node.name,
                            success = false,
                            durationMs = (System.nanoTime() - startedNs) / 1_000_000L,
                            error = error.message?.take(180) ?: error::class.java.simpleName
                        )
                    } finally {
                        admissionController.release(lease)
                    }
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
