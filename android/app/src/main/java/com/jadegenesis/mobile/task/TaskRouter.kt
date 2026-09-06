package com.jadegenesis.mobile.task

import com.jadegenesis.mobile.model.DeviceProfile
import com.jadegenesis.mobile.model.DistributedTaskRequest
import com.jadegenesis.mobile.model.DistributedTaskResult
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.MemorySnapshot
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
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

class TaskRouter(
    private val nodeManager: NodeManager,
    private val ledger: TaskLedger,
    private val queue: TaskQueue
) {
    companion object {
        private const val PROBE_ITERATIONS = 20_000
        private const val MAX_TEXT_CHARS = 12_000
        private const val MAX_MEMORY_ITEMS = 24
        private const val MAX_MEMORY_PAYLOAD_CHARS = 48_000

        private val MEMORY_STOP_WORDS = setOf(
            "le", "la", "les", "un", "une", "des", "de", "du",
            "et", "ou", "a", "à", "au", "aux", "en", "dans",
            "sur", "pour", "par", "avec", "que", "qui", "je",
            "tu", "il", "elle", "nous", "vous", "ils", "elles",
            "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa",
            "ses", "ce", "cet", "cette", "ces", "est", "sont",
            "être", "etre", "ai", "as", "avons", "avez", "ont"
        )

        private val NEGATION_WORDS = setOf(
            "ne", "n", "pas", "jamais", "aucun", "aucune",
            "non", "plus", "sans"
        )
    }

    private data class RankedNode(
        val node: GenesisNode,
        val score: Double,
        val historyAttempts: Int,
        val historySuccesses: Int,
        val averageDurationMs: Double?
    )

    private data class ConsolidationMemory(
        val id: String,
        val type: String,
        val content: String
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
            "Le texte est trop long pour la 0.0.6."
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

    suspend fun runMemoryConsolidation(
        identityId: String,
        memories: List<MemorySnapshot>,
        device: DeviceProfile,
        budget: ResourceBudget
    ): DistributedTaskResult {
        val sourceMemories = memories
            .filterNot {
                it.source.startsWith("JADE_CONSOLIDATION_")
            }
            .take(MAX_MEMORY_ITEMS)

        require(sourceMemories.isNotEmpty()) {
            "Aucune mémoire source à consolider."
        }

        val memoryArray = JSONArray()
        sourceMemories.forEach { memory ->
            memoryArray.put(
                JSONObject().apply {
                    put("id", memory.id)
                    put("type", memory.type)
                    put("content", memory.content)
                    put("source", memory.source)
                    put("confidence", memory.confidence)
                    put("created_at", memory.createdAt)
                }
            )
        }

        val payload = JSONObject().apply {
            put("identity_id", identityId)
            put("memories", memoryArray)
        }.toString()

        require(payload.length <= MAX_MEMORY_PAYLOAD_CHARS) {
            "Le lot de mémoire est trop grand pour la 0.0.6."
        }

        return runTask(
            request = DistributedTaskRequest(
                taskId = "task-${UUID.randomUUID()}",
                taskKind = "memory_consolidation",
                payload = payload,
                requiredCapability = "memory_consolidation",
                workload = if (
                    sourceMemories.size >= 12 ||
                    payload.length > 12_000
                ) {
                    TaskWorkload.HEAVY
                } else {
                    TaskWorkload.MEDIUM
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
        queue.enqueue(request)

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
            val reason = "Aucun nœud compatible avec ${request.taskKind}."
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
                fallbackReason = reason,
                routeReason = routeReason,
                attempts = emptyList(),
                startedAt = startedAt,
                completedAt = System.currentTimeMillis()
            )
            queue.markFailed(
                taskId = request.taskId,
                nodeName = null,
                attempts = 0,
                error = reason
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

            queue.markRunning(
                taskId = request.taskId,
                nodeName = node.name,
                attempts = index + 1
            )

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
                    Triple(
                        response.output,
                        elapsed,
                        node.copy(
                            nodeId = response.nodeId,
                            name = response.nodeName
                        )
                    )
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
                queue.markCompleted(
                    taskId = request.taskId,
                    nodeName = executedNode.name,
                    attempts = attempts.size
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
        val failureSummary = failures.joinToString(" | ")
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
            fallbackReason = failureSummary,
            routeReason = routeReason,
            attempts = attempts.toList(),
            startedAt = startedAt,
            completedAt = System.currentTimeMillis()
        )
        queue.markFailed(
            taskId = request.taskId,
            nodeName = last.name,
            attempts = attempts.size,
            error = failureSummary
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
            "task_execution_v3" in node.capabilities &&
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

        if (
            request.taskKind == "memory_consolidation" &&
            remote &&
            node.ramAvailableGb >= 1.0
        ) {
            score += 20.0
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
            val successRate =
                successes.toDouble() / relevantAttempts.size.toDouble()
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
                append(
                    " Aucun historique antérieur pour ce type de tâche sur ce nœud."
                )
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

            "memory_consolidation" ->
                runMemoryConsolidationLocal(request.payload)

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
        val words = tokenize(payload)
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
            put(
                "lines",
                if (payload.isEmpty()) 0 else payload.lineSequence().count()
            )
            put("words", words.size)
            put("unique_words", frequencies.size)
            put("top_terms", topTerms)
            put("sha256", sha256(payload))
        }.toString()
    }

    private fun runMemoryConsolidationLocal(payload: String): String {
        val input = JSONObject(payload)
        val memories = input.optJSONArray("memories") ?: JSONArray()
        require(memories.length() > 0) {
            "Lot de mémoire vide."
        }

        val items = buildList {
            for (index in 0 until memories.length()) {
                val memory = memories.getJSONObject(index)
                add(
                    ConsolidationMemory(
                        id = memory.optString("id"),
                        type = memory.optString("type", "UNKNOWN"),
                        content = memory.optString("content")
                    )
                )
            }
        }

        val normalizedGroups = items
            .groupBy { normalizeMemory(it.content) }
            .filterKeys { it.isNotBlank() }
        val duplicateGroups = normalizedGroups
            .values
            .count { it.size > 1 }
        val duplicateItems = normalizedGroups
            .values
            .filter { it.size > 1 }
            .sumOf { it.size - 1 }

        val typeCounts = items
            .groupingBy { it.type }
            .eachCount()
            .toSortedMap()

        val contradictionCandidates = countPotentialContradictions(items)

        val topTerms = items
            .flatMap { tokenize(it.content) }
            .filterNot { it in MEMORY_STOP_WORDS }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { it.key }
            )
            .take(6)
            .joinToString(",") { "${it.key}:${it.value}" }

        val uniqueCount = normalizedGroups.size
        val summary = buildString {
            append(
                "${items.size} mémoire(s) examinée(s), " +
                    "$uniqueCount contenu(s) unique(s), " +
                    "$duplicateGroups groupe(s) de doublons"
            )
            append(
                ", $contradictionCandidates contradiction(s) potentielle(s)."
            )
            if (topTerms.isNotBlank()) {
                append(" Thèmes dominants : $topTerms.")
            }
        }

        val typesJson = JSONObject()
        typeCounts.forEach { (type, count) ->
            typesJson.put(type, count)
        }

        return JSONObject().apply {
            put("input_count", items.size)
            put("unique_count", uniqueCount)
            put("duplicate_groups", duplicateGroups)
            put("duplicate_items", duplicateItems)
            put(
                "potential_contradictions",
                contradictionCandidates
            )
            put("type_counts", typesJson)
            put("top_terms", topTerms)
            put("summary", summary)
            put("input_sha256", sha256(payload))
        }.toString()
    }

    private fun countPotentialContradictions(
        items: List<ConsolidationMemory>
    ): Int {
        var count = 0

        for (leftIndex in 0 until items.size) {
            val left = items[leftIndex]
            val leftTokens = semanticTokens(left.content)
            if (leftTokens.size < 2) continue

            for (rightIndex in leftIndex + 1 until items.size) {
                val right = items[rightIndex]
                val rightTokens = semanticTokens(right.content)
                if (rightTokens.size < 2) continue

                val union = leftTokens union rightTokens
                if (union.isEmpty()) continue
                val similarity =
                    (leftTokens intersect rightTokens).size.toDouble() /
                        union.size.toDouble()

                if (
                    similarity >= 0.6 &&
                    hasNegation(left.content) != hasNegation(right.content)
                ) {
                    count += 1
                }
            }
        }

        return count
    }

    private fun semanticTokens(text: String): Set<String> =
        tokenize(text)
            .map {
                it.removePrefix("n'")
                    .removePrefix("n’")
            }
            .filterNot {
                it.isBlank() ||
                    it in MEMORY_STOP_WORDS ||
                    it in NEGATION_WORDS
            }
            .toSet()

    private fun hasNegation(text: String): Boolean {
        val lower = " ${text.lowercase()}"
        return " n'" in lower ||
            " n’" in lower ||
            tokenize(text).any { it in NEGATION_WORDS }
    }

    private fun normalizeMemory(text: String): String =
        tokenize(text).joinToString(" ")

    private fun tokenize(text: String): List<String> =
        Regex("[\\p{L}\\p{N}'’-]+")
            .findAll(text)
            .map { it.value.lowercase() }
            .toList()

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
                require(json.optString("sha256") == sha256(request.payload)) {
                    "Le résultat text_analysis ne correspond pas au payload envoyé."
                }
            }

            "memory_consolidation" -> {
                val json = JSONObject(output)
                require(
                    json.optString("input_sha256") == sha256(request.payload)
                ) {
                    "La consolidation mémoire ne correspond pas au lot envoyé."
                }
                require(json.optInt("input_count", 0) > 0) {
                    "La consolidation mémoire est vide."
                }
            }
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .toHex()

    private fun elapsedMs(startedNs: Long): Long =
        ((System.nanoTime() - startedNs) / 1_000_000L)
            .coerceAtLeast(0L)

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
}
