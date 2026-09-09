package com.jadegenesis.mobile.resource

import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.model.DistributedTaskRequest
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.model.ResourceBudget
import com.jadegenesis.mobile.model.TaskWorkload
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class ResourceAdmissionAction {
    LOCAL,
    REMOTE,
    DEFER,
    REJECT
}

data class TaskResourceEstimate(
    val memoryMb: Int,
    val cpuPercent: Double,
    val vramGb: Double,
    val payloadBytes: Int,
    val workload: TaskWorkload
)

data class ResourceLease(
    val leaseId: String,
    val taskId: String,
    val taskKind: String,
    val nodeId: String,
    val nodeName: String,
    val action: ResourceAdmissionAction,
    val memoryMb: Int,
    val cpuPercent: Double,
    val vramGb: Double,
    val acquiredAt: Long
)

data class ResourceAdmissionDecision(
    val action: ResourceAdmissionAction,
    val reason: String,
    val estimate: TaskResourceEstimate,
    val lease: ResourceLease? = null
) {
    val admitted: Boolean
        get() = lease != null &&
            (action == ResourceAdmissionAction.LOCAL ||
                action == ResourceAdmissionAction.REMOTE)
}

/**
 * Admission quantitative des tâches avant exécution.
 *
 * ResourceGovernor définit le budget du téléphone. Resource Intelligence décrit
 * la capacité instantanée des nœuds. Ce contrôleur relie les deux : il estime le
 * coût d'une tâche, vérifie la marge réellement disponible puis crée un lease
 * en mémoire qui représente l'engagement de ressources de Jade jusqu'à la fin
 * de l'exécution.
 *
 * Le registre est volontairement process-local pour 0.1.7.4. Le déplacement de
 * l'état opérationnel vers un stockage partagé VPS/phone appartient à 0.1.7.5.
 */
class ResourceAdmissionController(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val leaseStore: MutableMap<String, ResourceLease> = sharedLeases,
    private val leaseLock: Any = sharedLeaseLock
) {

    fun estimate(
        request: DistributedTaskRequest,
        node: GenesisNode
    ): TaskResourceEstimate {
        val payloadBytes = request.payload.toByteArray(Charsets.UTF_8).size
        val payloadMb = ceil(payloadBytes / BYTES_PER_MB).toInt()

        val baseMemoryMb = when (request.workload) {
            TaskWorkload.LIGHT -> 8
            TaskWorkload.MEDIUM -> 24
            TaskWorkload.HEAVY -> 64
        }
        val payloadMultiplier = when (request.workload) {
            TaskWorkload.LIGHT -> 1
            TaskWorkload.MEDIUM -> 2
            TaskWorkload.HEAVY -> 3
        }
        val memoryMb = (baseMemoryMb + payloadMb * payloadMultiplier)
            .coerceIn(
                SafetyPolicy.MIN_RESOURCE_LEASE_MEMORY_MB,
                SafetyPolicy.MAX_RESOURCE_LEASE_MEMORY_MB
            )

        val cpuPercent = when (request.workload) {
            TaskWorkload.LIGHT -> 10.0
            TaskWorkload.MEDIUM -> 25.0
            TaskWorkload.HEAVY -> 45.0
        }

        val vramGb = when {
            request.taskKind == "brain_chat" && node.gpuVramTotalGb > 0.0 -> {
                if (node.brainLoaded) {
                    SafetyPolicy.MIN_BRAIN_VRAM_HEADROOM_GB
                } else if (node.brainModelVramGb > 0.0) {
                    node.brainModelVramGb +
                        SafetyPolicy.MIN_BRAIN_VRAM_HEADROOM_GB
                } else {
                    SafetyPolicy.MIN_BRAIN_VRAM_HEADROOM_GB
                }
            }

            request.requiredCapability == "vision_analyze" ||
                request.requiredCapability == "screen_analyze" -> {
                if (node.gpuVramTotalGb > 0.0) {
                    SafetyPolicy.MIN_VISION_VRAM_HEADROOM_GB
                } else {
                    0.0
                }
            }

            else -> 0.0
        }.coerceAtMost(SafetyPolicy.MAX_RESOURCE_LEASE_VRAM_GB)

        return TaskResourceEstimate(
            memoryMb = memoryMb,
            cpuPercent = cpuPercent,
            vramGb = vramGb,
            payloadBytes = payloadBytes,
            workload = request.workload
        )
    }

    fun evaluate(
        request: DistributedTaskRequest,
        node: GenesisNode,
        budget: ResourceBudget
    ): ResourceAdmissionDecision = synchronized(leaseLock) {
        evaluateLocked(
            request = request,
            node = node,
            budget = budget,
            leases = leasesForNodeLocked(node.nodeId)
        )
    }

    fun tryAcquire(
        request: DistributedTaskRequest,
        node: GenesisNode,
        budget: ResourceBudget
    ): ResourceAdmissionDecision = synchronized(leaseLock) {
        val leases = leasesForNodeLocked(node.nodeId)
        val decision = evaluateLocked(
            request = request,
            node = node,
            budget = budget,
            leases = leases
        )

        if (
            decision.action != ResourceAdmissionAction.LOCAL &&
            decision.action != ResourceAdmissionAction.REMOTE
        ) {
            return@synchronized decision
        }

        val estimate = decision.estimate
        val lease = ResourceLease(
            leaseId = "lease-${UUID.randomUUID()}",
            taskId = request.taskId,
            taskKind = request.taskKind,
            nodeId = node.nodeId,
            nodeName = node.name,
            action = decision.action,
            memoryMb = estimate.memoryMb,
            cpuPercent = estimate.cpuPercent,
            vramGb = estimate.vramGb,
            acquiredAt = clock()
        )
        leaseStore[lease.leaseId] = lease
        decision.copy(lease = lease)
    }

    fun release(lease: ResourceLease?) {
        if (lease == null) return
        synchronized(leaseLock) {
            leaseStore.remove(lease.leaseId)
        }
    }

    fun activeLeases(nodeId: String? = null): List<ResourceLease> =
        synchronized(leaseLock) {
            leaseStore.values
                .filter { nodeId == null || it.nodeId == nodeId }
                .sortedBy { it.acquiredAt }
                .toList()
        }

    private fun evaluateLocked(
        request: DistributedTaskRequest,
        node: GenesisNode,
        budget: ResourceBudget,
        leases: List<ResourceLease>
    ): ResourceAdmissionDecision {
        val estimate = estimate(request, node)
        val local = node.status == NodeStatus.LOCAL
        val action = if (local) {
            ResourceAdmissionAction.LOCAL
        } else {
            ResourceAdmissionAction.REMOTE
        }

        if (node.status != NodeStatus.LOCAL && node.status != NodeStatus.ONLINE) {
            return rejected(
                estimate,
                "${node.name} n'est pas disponible (${node.status})."
            )
        }
        if ("task_execution_v3" !in node.capabilities) {
            return rejected(
                estimate,
                "${node.name} n'annonce pas task_execution_v3."
            )
        }
        if (request.requiredCapability !in node.capabilities) {
            return rejected(
                estimate,
                "${node.name} n'annonce pas ${request.requiredCapability}."
            )
        }
        if (request.taskKind == "brain_chat" && !node.brainReady) {
            return rejected(
                estimate,
                "Le cerveau local de ${node.name} n'est pas prêt."
            )
        }

        if (local) {
            if (
                request.workload == TaskWorkload.HEAVY &&
                !budget.heavyBackgroundWorkAllowed
            ) {
                return deferred(
                    estimate,
                    "Le mode ${budget.mode} n'autorise pas une tâche lourde locale."
                )
            }

            if (leases.size >= budget.maxParallelTasks.coerceAtLeast(1)) {
                return deferred(
                    estimate,
                    "Le téléphone a déjà ${leases.size} lease(s) actif(s) pour une limite de ${budget.maxParallelTasks}."
                )
            }

            val committedMemoryMb = leases.sumOf { it.memoryMb }
            val remainingMb =
                (budget.recommendedWorkingSetMb - committedMemoryMb)
                    .coerceAtLeast(0)
            if (estimate.memoryMb > remainingMb) {
                return deferred(
                    estimate,
                    "Budget local insuffisant : ${estimate.memoryMb} Mo estimés, ${remainingMb} Mo encore disponibles."
                )
            }

            return admitted(
                action,
                estimate,
                "Lease local admissible : ${estimate.memoryMb} Mo, ${estimate.cpuPercent.toInt()}% CPU estimé."
            )
        }

        val telemetryTime = node.lastSeenAt
        val leasesAfterTelemetry = leases.filter {
            telemetryTime <= 0L || it.acquiredAt > telemetryTime
        }
        val parallelLimit = remoteParallelLimit(node, request.workload)
        val effectiveActiveTasks =
            node.activeTaskCount.coerceAtLeast(0) + leasesAfterTelemetry.size

        if (effectiveActiveTasks >= parallelLimit) {
            return deferred(
                estimate,
                "${node.name} est à sa capacité de concurrence : $effectiveActiveTasks/$parallelLimit tâche(s)."
            )
        }

        if (node.ramAvailableGb > 0.0) {
            val reserveGb = max(
                SafetyPolicy.MIN_REMOTE_RAM_RESERVE_GB,
                if (node.ramTotalGb > 0.0) {
                    node.ramTotalGb * SafetyPolicy.REMOTE_RAM_RESERVE_FRACTION
                } else {
                    0.0
                }
            )
            val committedGb =
                leasesAfterTelemetry.sumOf { it.memoryMb }.toDouble() / 1024.0
            val safeFreeGb =
                (node.ramAvailableGb - reserveGb - committedGb)
                    .coerceAtLeast(0.0)
            if (estimate.memoryMb.toDouble() / 1024.0 > safeFreeGb) {
                return deferred(
                    estimate,
                    "RAM distante insuffisante sur ${node.name} : ${estimate.memoryMb} Mo estimés après réserve système."
                )
            }
        }

        if (node.cpuLoadPercent >= 0.0) {
            val committedCpu = leasesAfterTelemetry.sumOf { it.cpuPercent }
            val projectedCpu =
                node.cpuLoadPercent + committedCpu + estimate.cpuPercent
            if (projectedCpu > SafetyPolicy.REMOTE_CPU_ADMISSION_CEILING_PERCENT) {
                return deferred(
                    estimate,
                    "CPU de ${node.name} trop chargé : ${node.cpuLoadPercent.toInt()}%, projection ${projectedCpu.toInt()}%."
                )
            }
        }

        if (estimate.vramGb > 0.0 && node.gpuVramTotalGb > 0.0) {
            val committedVram = leasesAfterTelemetry.sumOf { it.vramGb }
            val safeVramGb =
                (node.gpuVramFreeGb - committedVram).coerceAtLeast(0.0)
            if (estimate.vramGb > safeVramGb) {
                return deferred(
                    estimate,
                    "VRAM insuffisante sur ${node.name} : ${roundOne(estimate.vramGb)} Go estimés, ${roundOne(safeVramGb)} Go disponibles."
                )
            }

            if (
                node.gpuUtilizationPercent >=
                SafetyPolicy.REMOTE_GPU_ADMISSION_CEILING_PERCENT
            ) {
                return deferred(
                    estimate,
                    "GPU de ${node.name} déjà saturé à ${node.gpuUtilizationPercent.toInt()}%."
                )
            }
        }

        return admitted(
            action,
            estimate,
            "Lease distant admissible sur ${node.name} : ${estimate.memoryMb} Mo RAM" +
                if (estimate.vramGb > 0.0) {
                    ", ${roundOne(estimate.vramGb)} Go VRAM."
                } else {
                    "."
                }
        )
    }

    private fun remoteParallelLimit(
        node: GenesisNode,
        workload: TaskWorkload
    ): Int {
        val byCpu = when {
            node.cpuCores <= 0 -> SafetyPolicy.MAX_PARALLEL_TASKS
            node.cpuCores <= 4 -> 1
            node.cpuCores <= 8 -> 2
            else -> SafetyPolicy.MAX_PARALLEL_TASKS
        }
        return when (workload) {
            TaskWorkload.LIGHT -> byCpu
            TaskWorkload.MEDIUM -> min(byCpu, 2)
            TaskWorkload.HEAVY -> 1
        }.coerceIn(1, SafetyPolicy.MAX_PARALLEL_TASKS)
    }

    private fun admitted(
        action: ResourceAdmissionAction,
        estimate: TaskResourceEstimate,
        reason: String
    ) = ResourceAdmissionDecision(
        action = action,
        reason = reason,
        estimate = estimate
    )

    private fun deferred(
        estimate: TaskResourceEstimate,
        reason: String
    ) = ResourceAdmissionDecision(
        action = ResourceAdmissionAction.DEFER,
        reason = reason,
        estimate = estimate
    )

    private fun rejected(
        estimate: TaskResourceEstimate,
        reason: String
    ) = ResourceAdmissionDecision(
        action = ResourceAdmissionAction.REJECT,
        reason = reason,
        estimate = estimate
    )

    private fun leasesForNodeLocked(nodeId: String): List<ResourceLease> =
        leaseStore.values.filter { it.nodeId == nodeId }

    private fun roundOne(value: Double): Double =
        kotlin.math.round(value * 10.0) / 10.0

    companion object {
        private const val BYTES_PER_MB = 1024.0 * 1024.0
        private val sharedLeases = linkedMapOf<String, ResourceLease>()
        private val sharedLeaseLock = Any()
    }
}
