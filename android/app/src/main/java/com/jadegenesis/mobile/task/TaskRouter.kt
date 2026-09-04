package com.jadegenesis.mobile.task

import com.jadegenesis.mobile.model.DeviceProfile
import com.jadegenesis.mobile.model.DistributedTaskRequest
import com.jadegenesis.mobile.model.DistributedTaskResult
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.model.ResourceBudget
import com.jadegenesis.mobile.model.TaskAttempt
import com.jadegenesis.mobile.model.TaskExecutionLocation
import com.jadegenesis.mobile.model.TaskStatus
import com.jadegenesis.mobile.model.TaskWorkload
import com.jadegenesis.mobile.node.NodeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

class TaskRouter(
    private val nodeManager: NodeManager,
    private val ledger: TaskLedger
) {
    companion object {
        private const val PROBE_ITERATIONS = 20_000
        private const val MAX_TEXT_CHARS = 12_000
    }

    private data class RankedNode(
        val node: GenesisNode,
        val score: Double,
        val historyAttempts: Int,
        val historySuccesses: Int,
        val averageDurationMs: Double?
    )

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

        return runTask(
            request = DistributedTaskRequest(
                taskId = taskId,
                taskKind = "genesis_probe",
                payload = payload,
                requiredCapability = "genesis_probe",
                workload = TaskWorkload.MEDIUM,
                iterations = PROBE_ITERATIONS,
                createdAt = System.currentTimeMillis()
            ),
            device = device,
            budget = budget
        )
    }

    suspend fun runTextAnalysis(
        text: String,
        device: DeviceProfile,
        budget: ResourceBudget
    ): DistributedTaskResult {
        val cleanText = text.trim()
        require(cleanText.isNotBlank()) {
            "Le texte à analyser est vide."
        }
        require(cleanText.length <= MAX_TEXT_CHARS) {
            "Le texte est trop long pour la 0.0.5."
        }

        return runTask(
            request = DistributedTaskRequest(
                taskId = "task-${UUID.randomUUID()}",
                taskKind = "text_analysis",
                payload = cleanText,
                requiredCapability = "text_analysis",
                workload = if (cleanText.length > 4_000) {
                    TaskWorkload.MEDIUM
                } else {
                    TaskWorkload.LIGHT
                },
                createdAt = System.currentTimeMillis()
            ),
            device = device,
            budget = budget
        )
    }

    private suspend fun runTask(
        request: DistributedTaskRequest,
        device: DeviceProfile,
        budget: ResourceBudget
    ): DistributedTaskResult {
        val startedAt = System.currentTimeMillis()
        val nodes = nodeManager.nodes(
            device = device,
            refreshRemote = true
        )
        val history = ledger.recent(40)
        val ranked = nodes
            .filter { supportsTask(it, request) }
            .map {
                rankNode(
                    node = it,
                    request = request,
                    budget = budget,
                    history = history
                )
            }
            .sortedWith(
                compareByDescending<RankedNode> { it.score }
                    .thenByDescending { it.node.ramAvailableGb }
                    .thenByDescending { it.node.cpuCores }
            )

        val requested = ranked.firstOrNull()
        val routeReason = routeReason(
            request = request,
            budget = budget,
            ranked = ranked
        )
        val attempts = mutableListOf<TaskAttempt>()
        val failures = mutableListOf<String>()

        if (requested == null) {
            val local = nodeManager.localNode(device)
            val result = DistributedTaskResult(
                taskId = request.taskId,
                taskKind = request.taskKind,
                requestedNodeId = null,
                requestedNodeName = null,
                executedNodeId = local.nodeId,
                executedNodeName = local.name,
                executionLocation = TaskExecutionLocation.LOCAL,
                status = TaskStatus.FAILED,
                success = false,
                output = "",
                durationMs = 0L,
                fallbackUsed = false,
                fallbackReason = "Aucun nœud compatible avec ${request.taskKind}.",
                routeReason = routeReason,
                attempts = emptyList(),
                startedAt = startedAt,
                completedAt = System.currentTimeMillis()
            )
            ledger.record(result)
            return result
        }

        for ((index, candidate) in ranked.withIndex()) {
            val node = candidate.node
            val location = if (node.status == NodeStatus.LOCAL) {
                TaskExecutionLocation.LOCAL
            } else {
                TaskExecutionLocation.REMOTE
            }
            val attemptStartedNs = System.nanoTime()

            val attempt = runCatching {
                if (location == TaskExecutionLocation.LOCAL) {
                    val output = executeLocal(request)
                    validateOutput(request, output)
                    val elapsed = elapsedMs(attemptStartedNs)
                    Triple(output, elapsed, node)
                } else {
                    val response = nodeManager.executeTask(
                        nodeId = node.nodeId,
                        request = request
                    )
                    validateOutput(request, response.output)
                    val elapsed = maxOf(
                        elapsedMs(attemptStartedNs),
                        response.durationMs
                    )
                    Triple(response.output, elapsed, node.copy(
                        nodeId = response.nodeId,
                        name = response.nodeName
                    ))
                }
            }

            attempt.getOrNull()?.let { (output, elapsed, executedNode) ->
                attempts.add(
                    TaskAttempt(
                        nodeId = executedNode.nodeId,
                        nodeName = executedNode.name,
                        executionLocation = location,
                        success = true,
                        durationMs = elapsed,
                        error = null
                    )
                )

                val result = DistributedTaskResult(
                    taskId = request.taskId,
                    taskKind = request.taskKind,
                    requestedNodeId = requested.node.nodeId,
                    requestedNodeName = requested.node.name,
                    executedNodeId = executedNode.nodeId,
                    executedNodeName = executedNode.name,
                    executionLocation = location,
                    status = TaskStatus.COMPLETED,
                    success = true,
                    output = output,
                    durationMs = elapsed,
                    fallbackUsed = index > 0,
                    fallbackReason = failures
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(" | "),
                    routeReason = routeReason,
                    attempts = attempts.toList(),
                    startedAt = startedAt,
                    completedAt = System.currentTimeMillis()
                )
                ledger.record(result)
                return result
            }

            val elapsed = elapsedMs(attemptStartedNs)
            val error = attempt.exceptionOrNull()
                ?.message
                ?.take(180)
                ?: "Échec inconnu."
            failures.add("${node.name}: $error")
            attempts.add(
                TaskAttempt(
                    nodeId = node.nodeId,
                    nodeName = node.name,
                    executionLocation = location,
                    success = false,
                    durationMs = elapsed,
                    error = error
                )
            )
        }

        val last = ranked.last().node
        val result = DistributedTaskResult(
            taskId = request.taskId,
            taskKind = request.taskKind,
            requestedNodeId = requested.node.nodeId,
            requestedNodeName = requested.node.name,
            executedNodeId = last.nodeId,
            executedNodeName = last.name,
            executionLocation = if (last.kind == NodeKind.PHONE) {
                TaskExecutionLocation.LOCAL
            } else {
                TaskExecutionLocation.REMOTE
            },
            status = TaskStatus.FAILED,
            success = false,
            output = "",
            durationMs = attempts.sumOf { it.durationMs },
            fallbackUsed = attempts.size > 1,
            fallbackReason = failures.joinToString(" | "),
            routeReason = routeReason,
            attempts = attempts.toList(),
            startedAt = startedAt,
            completedAt = System.currentTimeMillis()
        )
        ledger.record(result)
        return result
    }

    private fun supportsTask(
        node: GenesisNode,
        request: DistributedTaskRequest
    ): Boolean {
        val usableStatus =
            node.status == NodeStatus.LOCAL ||
                node.status == NodeStatus.ONLINE

        return usableStatus &&
            "task_execution_v2" in node.capabilities &&
            request.requiredCapability in node.capabilities
    }

    private fun rankNode(
        node: GenesisNode,
        request: DistributedTaskRequest,
        budget: ResourceBudget,
        history: List<DistributedTaskResult>
    ): RankedNode {
        val remote = node.kind != NodeKind.PHONE
        var score = 0.0

        score += node.cpuCores.coerceAtMost(32) * 1.5
        score += node.ramAvailableGb.coerceAtMost(32.0) * 4.0
        score += node.storageFreeGb.coerceAtMost(250.0) * 0.02

        if (budget.preferRemoteCompute && remote) score += 90.0
        if (budget.preferRemoteCompute && !remote) score -= 15.0
        if (!budget.preferRemoteCompute && !remote) score += 60.0
        if (!budget.preferRemoteCompute && remote) score += 15.0

        when (request.workload) {
            TaskWorkload.LIGHT -> {
                if (!remote) score += 25.0 else score += 5.0
            }

            TaskWorkload.MEDIUM -> {
                if (remote) score += 25.0 else score += 12.0
            }

            TaskWorkload.HEAVY -> {
                if (remote) score += 55.0 else score -= 20.0
            }
        }

        val relevantAttempts = history
            .filter { it.taskKind == request.taskKind }
            .flatMap { it.attempts }
            .filter { it.nodeId == node.nodeId }

        val successes = relevantAttempts.count { it.success }
        val failures = relevantAttempts.size - successes
        val successfulDurations = relevantAttempts
            .filter { it.success && it.durationMs > 0L }
            .map { it.durationMs.toDouble() }
        val averageDuration = successfulDurations
            .takeIf { it.isNotEmpty() }
            ?.average()

        if (relevantAttempts.isNotEmpty()) {
            val successRate = successes.toDouble() / relevantAttempts.size.toDouble()
            score += successRate * 35.0
            score -= failures * 8.0

            averageDuration?.let { average ->
                score += 30.0 / (1.0 + average / 50.0)
            }
        }

        return RankedNode(
            node = node,
            score = score,
            historyAttempts = relevantAttempts.size,
            historySuccesses = successes,
            averageDurationMs = averageDuration
        )
    }

    private fun routeReason(
        request: DistributedTaskRequest,
        budget: ResourceBudget,
        ranked: List<RankedNode>
    ): String {
        val best = ranked.firstOrNull()
            ?: return "Aucun nœud compatible avec ${request.taskKind}."

        val preference = if (budget.preferRemoteCompute) {
            "le Resource Governor préfère déléguer"
        } else {
            "le Resource Governor autorise le local"
        }
        val roundedScore = (best.score * 10.0).toInt() / 10.0

        return buildString {
            append(
                "${request.taskKind}/${request.workload} : mode ${budget.mode}, " +
                    "$preference. ${best.node.name} obtient le meilleur score " +
                    "$roundedScore parmi ${ranked.size} nœud(s) compatible(s)."
            )

            if (best.historyAttempts > 0) {
                append(
                    " Historique mesuré : ${best.historySuccesses}/" +
                        "${best.historyAttempts} succès"
                )
                best.averageDurationMs?.let {
                    append(", moyenne ${it.toLong()} ms")
                }
                append(".")
            } else {
                append(" Aucun historique antérieur pour ce type de tâche sur ce nœud.")
            }
        }
    }

    private suspend fun executeLocal(
        request: DistributedTaskRequest
    ): String = withContext(Dispatchers.Default) {
        when (request.taskKind) {
            "genesis_probe" -> runGenesisProbeLocal(
                payload = request.payload,
                iterations = request.iterations
            )

            "text_analysis" -> runTextAnalysisLocal(request.payload)

            else -> error(
                "Tâche locale non autorisée : ${request.taskKind}"
            )
        }
    }

    private fun runGenesisProbeLocal(
        payload: String,
        iterations: Int
    ): String {
        require(iterations in 1..100_000)
        var data = payload.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")

        repeat(iterations) {
            data = digest.digest(data)
        }

        return data.toHex()
    }

    private fun runTextAnalysisLocal(payload: String): String {
        val words = Regex("[\\p{L}\\p{N}'’-]+")
            .findAll(payload)
            .map { it.value.lowercase() }
            .toList()
        val frequencies = words.groupingBy { it }.eachCount()
        val topTerms = frequencies.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { it.key }
            )
            .take(5)
            .joinToString(",") { "${it.key}:${it.value}" }

        return JSONObject().apply {
            put("characters", payload.length)
            put("bytes_utf8", payload.toByteArray(Charsets.UTF_8).size)
            put("lines", if (payload.isEmpty()) 0 else payload.lineSequence().count())
            put("words", words.size)
            put("unique_words", frequencies.size)
            put("top_terms", topTerms)
            put("sha256", MessageDigest.getInstance("SHA-256")
                .digest(payload.toByteArray(Charsets.UTF_8))
                .toHex())
        }.toString()
    }

    private fun validateOutput(
        request: DistributedTaskRequest,
        output: String
    ) {
        when (request.taskKind) {
            "genesis_probe" -> require(
                Regex("^[0-9a-f]{64}$").matches(output)
            ) {
                "Résultat genesis_probe invalide."
            }

            "text_analysis" -> {
                val json = JSONObject(output)
                val expected = MessageDigest.getInstance("SHA-256")
                    .digest(request.payload.toByteArray(Charsets.UTF_8))
                    .toHex()
                require(json.optString("sha256") == expected) {
                    "Le résultat text_analysis ne correspond pas au payload envoyé."
                }
            }
        }
    }

    private fun elapsedMs(startedNs: Long): Long =
        ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(0L)

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
}
