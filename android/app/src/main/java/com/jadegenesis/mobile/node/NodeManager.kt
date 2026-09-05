package com.jadegenesis.mobile.node

import android.content.Context
import com.jadegenesis.mobile.device.DeviceProfiler
import com.jadegenesis.mobile.model.DeviceProfile
import com.jadegenesis.mobile.model.DistributedTaskRequest
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.model.NodeTaskResponse
import com.jadegenesis.mobile.model.ResourceBudget
import com.jadegenesis.mobile.model.TaskWorkload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class NodeManager(
    context: Context,
    private val profiler: DeviceProfiler
) {
    private val prefs = context.getSharedPreferences(
        "jade_genesis_nodes",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_REMOTE_NODES = "remote_nodes_v1"
        private const val PROTOCOL = "jade-genesis-node/0.0.6"
        private const val LEGACY_PROTOCOL_005 = "jade-genesis-node/0.0.5"
        private const val LEGACY_PROTOCOL_004 = "jade-genesis-node/0.0.4"
        private const val DEFAULT_PORT = 8765
        private const val MAX_PAYLOAD_CHARS = 48_000
    }

    private data class StoredNode(
        val nodeId: String,
        val name: String,
        val kind: NodeKind,
        val host: String,
        val port: Int,
        val token: String,
        val protocol: String,
        val status: NodeStatus,
        val osName: String,
        val cpuName: String,
        val cpuCores: Int,
        val ramTotalGb: Double,
        val ramAvailableGb: Double,
        val storageFreeGb: Double,
        val capabilities: List<String>,
        val lastSeenAt: Long,
        val lastError: String?
    ) {
        fun publicNode(): GenesisNode = GenesisNode(
            nodeId = nodeId,
            name = name,
            kind = kind,
            status = status,
            host = host,
            port = port,
            protocol = protocol,
            osName = osName,
            cpuName = cpuName,
            cpuCores = cpuCores,
            ramTotalGb = ramTotalGb,
            ramAvailableGb = ramAvailableGb,
            storageFreeGb = storageFreeGb,
            capabilities = capabilities,
            lastSeenAt = lastSeenAt,
            lastError = lastError
        )
    }

    fun localNode(device: DeviceProfile): GenesisNode = GenesisNode(
        nodeId = profiler.nodeId(),
        name = "Pixel — ${device.model}",
        kind = NodeKind.PHONE,
        status = NodeStatus.LOCAL,
        protocol = PROTOCOL,
        osName = "Android ${device.androidVersion}",
        cpuName = listOf(
            device.socManufacturer,
            device.socModel
        ).filter { it.isNotBlank() }.joinToString(" "),
        cpuCores = device.cpuCores,
        ramTotalGb = device.ramTotalGb,
        ramAvailableGb = device.ramAvailableGb,
        storageFreeGb = device.storageFreeGb,
        capabilities = listOf(
            "identity",
            "memory",
            "device_inspection",
            "resource_governor",
            "prototype_brain",
            "local_pc_brain_client",
            "task_execution_v1",
            "task_execution_v2",
            "task_execution_v3",
            "genesis_probe",
            "text_analysis",
            "memory_consolidation",
            "task_queue_v1"
        ),
        lastSeenAt = System.currentTimeMillis()
    )

    suspend fun nodes(
        device: DeviceProfile,
        refreshRemote: Boolean = false
    ): List<GenesisNode> {
        if (refreshRemote) {
            refreshRemoteNodes()
        }

        return buildList {
            add(localNode(device))
            addAll(
                loadStoredNodes()
                    .sortedWith(
                        compareBy<StoredNode> { it.kind.ordinal }
                            .thenBy { it.name.lowercase() }
                    )
                    .map { currentPublicNode(it) }
            )
        }
    }

    suspend fun registerPcNode(
        host: String,
        port: Int,
        token: String
    ): GenesisNode {
        val cleanHost = normalizeHost(host)
        require(cleanHost.isNotBlank()) {
            "Adresse du PC vide."
        }
        require(port in 1..65535) {
            "Port invalide."
        }
        require(token.isNotBlank()) {
            "Jeton du Node Agent vide."
        }

        val nodes = loadStoredNodes().toMutableList()
        val existing = nodes.firstOrNull {
            it.host.equals(cleanHost, ignoreCase = true) &&
                it.port == port
        }

        val draft = existing?.copy(
            token = token.trim(),
            kind = NodeKind.PC,
            status = NodeStatus.UNKNOWN,
            lastError = null
        ) ?: StoredNode(
            nodeId = "remote-${UUID.randomUUID()}",
            name = "PC Genesis",
            kind = NodeKind.PC,
            host = cleanHost,
            port = port,
            token = token.trim(),
            protocol = "",
            status = NodeStatus.UNKNOWN,
            osName = "",
            cpuName = "",
            cpuCores = 0,
            ramTotalGb = 0.0,
            ramAvailableGb = 0.0,
            storageFreeGb = 0.0,
            capabilities = emptyList(),
            lastSeenAt = 0L,
            lastError = null
        )

        val probed = probe(draft)

        nodes.removeAll {
            (it.host.equals(cleanHost, ignoreCase = true) &&
                it.port == port) ||
                it.nodeId == probed.nodeId
        }
        nodes.add(probed)
        saveStoredNodes(nodes)

        return probed.publicNode()
    }

    suspend fun refreshRemoteNodes(): List<GenesisNode> {
        val current = loadStoredNodes()
        val refreshed = current.map { node ->
            probe(node)
        }

        saveStoredNodes(refreshed)
        return refreshed.map { it.publicNode() }
    }

    fun preferredComputeNode(
        nodes: List<GenesisNode>,
        budget: ResourceBudget
    ): GenesisNode? {
        val local = nodes.firstOrNull {
            it.status == NodeStatus.LOCAL
        }

        val onlineRemote = nodes
            .filter {
                it.status == NodeStatus.ONLINE &&
                    it.kind != NodeKind.PHONE
            }
            .sortedWith(
                compareByDescending<GenesisNode> {
                    "compute" in it.capabilities
                }.thenByDescending {
                    "task_execution_v3" in it.capabilities
                }.thenByDescending {
                    it.ramAvailableGb
                }.thenByDescending {
                    it.cpuCores
                }
            )

        return if (budget.preferRemoteCompute) {
            onlineRemote.firstOrNull() ?: local
        } else {
            local ?: onlineRemote.firstOrNull()
        }
    }

    suspend fun executeTask(
        nodeId: String,
        request: DistributedTaskRequest
    ): NodeTaskResponse = withContext(Dispatchers.IO) {
        require(request.payload.length <= MAX_PAYLOAD_CHARS) {
            "Charge de tâche trop grande."
        }
        if (request.taskKind == "genesis_probe") {
            require(request.iterations in 1..100_000) {
                "Nombre d'itérations hors limites."
            }
        }

        val node = loadStoredNodes().firstOrNull {
            it.nodeId == nodeId
        } ?: error("Nœud distant inconnu : $nodeId")

        if (node.status != NodeStatus.ONLINE) {
            error("Le nœud ${node.name} n'est pas en ligne.")
        }
        if ("task_execution_v3" !in node.capabilities) {
            error("Le nœud ${node.name} n'annonce pas task_execution_v3.")
        }
        if (request.requiredCapability !in node.capabilities) {
            error(
                "Le nœud ${node.name} ne sait pas exécuter " +
                    "${request.taskKind}."
            )
        }

        val requestBody = JSONObject().apply {
            put("protocol", PROTOCOL)
            put("task_id", request.taskId)
            put("task_kind", request.taskKind)
            put("payload", request.payload)
            put("workload", request.workload.name)
            put("iterations", request.iterations)
        }.toString().toByteArray(Charsets.UTF_8)

        val readTimeoutMs = if (request.taskKind == "brain_chat") {
            120_000
        } else {
            when (request.workload) {
                TaskWorkload.LIGHT -> 8_000
                TaskWorkload.MEDIUM -> 15_000
                TaskWorkload.HEAVY -> 45_000
            }
        }

        val connection = (
            URL("http://${node.host}:${node.port}/task")
                .openConnection() as HttpURLConnection
            ).apply {
            requestMethod = "POST"
            connectTimeout = 2_500
            readTimeout = readTimeoutMs
            useCaches = false
            doOutput = true
            setRequestProperty("X-Jade-Token", node.token)
            setRequestProperty("Accept", "application/json")
            setRequestProperty(
                "Content-Type",
                "application/json; charset=utf-8"
            )
            setFixedLengthStreamingMode(requestBody.size)
        }

        try {
            connection.outputStream.use {
                it.write(requestBody)
            }

            val responseCode = connection.responseCode
            val body = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode != HttpURLConnection.HTTP_OK) {
                val details = runCatching {
                    JSONObject(body).optString("error")
                }.getOrNull().orEmpty()

                error(
                    if (details.isNotBlank()) {
                        "Node Runtime HTTP $responseCode : $details"
                    } else {
                        "Node Runtime HTTP $responseCode"
                    }
                )
            }

            val json = JSONObject(body)
            val protocol = json.optString("protocol")
            if (protocol != PROTOCOL) {
                error("Réponse de tâche avec protocole incompatible : $protocol")
            }
            if (!json.optBoolean("success", false)) {
                error(json.optString("error", "Échec de la tâche distante."))
            }

            val returnedTaskId = json.optString("task_id")
            if (returnedTaskId != request.taskId) {
                error("Le Node Runtime a renvoyé un autre identifiant de tâche.")
            }

            val returnedTaskKind = json.optString("task_kind")
            if (returnedTaskKind != request.taskKind) {
                error("Le Node Runtime a renvoyé un autre type de tâche.")
            }

            NodeTaskResponse(
                taskId = request.taskId,
                taskKind = request.taskKind,
                nodeId = json.optString("node_id", node.nodeId),
                nodeName = json.optString("node_name", node.name)
                    .ifBlank { node.name },
                output = json.getString("result"),
                durationMs = json.optLong("duration_ms", 0L)
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun currentPublicNode(node: StoredNode): GenesisNode {
        val staleOnline =
            node.status == NodeStatus.ONLINE &&
                node.lastSeenAt > 0L &&
                System.currentTimeMillis() - node.lastSeenAt > 60_000L

        return if (staleOnline) {
            node.copy(
                status = NodeStatus.UNKNOWN,
                lastError = "État à rafraîchir."
            ).publicNode()
        } else {
            node.publicNode()
        }
    }

    private suspend fun probe(node: StoredNode): StoredNode =
        withContext(Dispatchers.IO) {
            try {
                val connection = (
                    URL("http://${node.host}:${node.port}/health")
                        .openConnection() as HttpURLConnection
                    ).apply {
                    requestMethod = "GET"
                    connectTimeout = 1_800
                    readTimeout = 2_500
                    useCaches = false
                    setRequestProperty("X-Jade-Token", node.token)
                    setRequestProperty("Accept", "application/json")
                }

                try {
                    val responseCode = connection.responseCode

                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        return@withContext node.copy(
                            status = if (responseCode == 401) {
                                NodeStatus.ERROR
                            } else {
                                NodeStatus.OFFLINE
                            },
                            lastError = if (responseCode == 401) {
                                "Jeton refusé par le Node Runtime."
                            } else {
                                "Node Runtime HTTP $responseCode."
                            }
                        )
                    }

                    val body = connection.inputStream
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }

                    val json = JSONObject(body)
                    val protocol = json.optString("protocol")

                    if (
                        protocol != PROTOCOL &&
                        protocol != LEGACY_PROTOCOL_005 &&
                        protocol != LEGACY_PROTOCOL_004
                    ) {
                        return@withContext node.copy(
                            status = NodeStatus.ERROR,
                            protocol = protocol,
                            lastError = "Protocole incompatible : $protocol"
                        )
                    }

                    val remoteKind = parseKind(
                        json.optString("kind", node.kind.name)
                    )
                    val remoteId = json
                        .optString("node_id", node.nodeId)
                        .ifBlank { node.nodeId }

                    node.copy(
                        nodeId = remoteId,
                        name = json.optString("name", node.name)
                            .ifBlank { node.name },
                        kind = remoteKind,
                        protocol = protocol,
                        status = NodeStatus.ONLINE,
                        osName = json.optString("os", node.osName),
                        cpuName = json.optString("cpu", node.cpuName),
                        cpuCores = json.optInt("cpu_cores", node.cpuCores),
                        ramTotalGb = json.optDouble(
                            "ram_total_gb",
                            node.ramTotalGb
                        ),
                        ramAvailableGb = json.optDouble(
                            "ram_available_gb",
                            node.ramAvailableGb
                        ),
                        storageFreeGb = json.optDouble(
                            "storage_free_gb",
                            node.storageFreeGb
                        ),
                        capabilities = parseStringArray(
                            json.optJSONArray("capabilities")
                        ),
                        lastSeenAt = System.currentTimeMillis(),
                        lastError = null
                    )
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                node.copy(
                    status = NodeStatus.OFFLINE,
                    lastError = e.message
                        ?.take(140)
                        ?: e::class.java.simpleName
                )
            }
        }

    private fun normalizeHost(raw: String): String =
        raw.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .substringBefore(":")
            .trim()

    private fun parseKind(value: String): NodeKind =
        runCatching {
            NodeKind.valueOf(value.uppercase())
        }.getOrDefault(NodeKind.UNKNOWN)

    private fun parseStatus(value: String): NodeStatus =
        runCatching {
            NodeStatus.valueOf(value.uppercase())
        }.getOrDefault(NodeStatus.UNKNOWN)

    private fun parseStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()

        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun loadStoredNodes(): List<StoredNode> {
        val raw = prefs.getString(KEY_REMOTE_NODES, null)
            ?: return emptyList()

        return runCatching {
            val array = JSONArray(raw)

            buildList {
                for (index in 0 until array.length()) {
                    val json = array.getJSONObject(index)

                    add(
                        StoredNode(
                            nodeId = json.getString("node_id"),
                            name = json.optString("name", "Nœud Genesis"),
                            kind = parseKind(
                                json.optString(
                                    "kind",
                                    NodeKind.UNKNOWN.name
                                )
                            ),
                            host = json.getString("host"),
                            port = json.optInt("port", DEFAULT_PORT),
                            token = json.getString("token"),
                            protocol = json.optString("protocol"),
                            status = parseStatus(
                                json.optString(
                                    "status",
                                    NodeStatus.UNKNOWN.name
                                )
                            ),
                            osName = json.optString("os"),
                            cpuName = json.optString("cpu"),
                            cpuCores = json.optInt("cpu_cores"),
                            ramTotalGb = json.optDouble("ram_total_gb"),
                            ramAvailableGb = json.optDouble("ram_available_gb"),
                            storageFreeGb = json.optDouble("storage_free_gb"),
                            capabilities = parseStringArray(
                                json.optJSONArray("capabilities")
                            ),
                            lastSeenAt = json.optLong("last_seen_at"),
                            lastError = json
                                .optString("last_error")
                                .takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveStoredNodes(nodes: List<StoredNode>) {
        val array = JSONArray()

        nodes.forEach { node ->
            array.put(
                JSONObject().apply {
                    put("node_id", node.nodeId)
                    put("name", node.name)
                    put("kind", node.kind.name)
                    put("host", node.host)
                    put("port", node.port)
                    put("token", node.token)
                    put("protocol", node.protocol)
                    put("status", node.status.name)
                    put("os", node.osName)
                    put("cpu", node.cpuName)
                    put("cpu_cores", node.cpuCores)
                    put("ram_total_gb", node.ramTotalGb)
                    put("ram_available_gb", node.ramAvailableGb)
                    put("storage_free_gb", node.storageFreeGb)
                    put("capabilities", JSONArray(node.capabilities))
                    put("last_seen_at", node.lastSeenAt)
                    put("last_error", node.lastError ?: "")
                }
            )
        }

        prefs.edit()
            .putString(KEY_REMOTE_NODES, array.toString())
            .apply()
    }
}
