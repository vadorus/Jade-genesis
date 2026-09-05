package com.jadegenesis.mobile.node

import android.content.Context
import com.jadegenesis.mobile.device.DeviceProfiler
import com.jadegenesis.mobile.diagnostics.DiagnosticLogger
import com.jadegenesis.mobile.model.DeviceProfile
import com.jadegenesis.mobile.model.DiagnosticLevel
import com.jadegenesis.mobile.model.DistributedTaskRequest
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeRouteKind
import com.jadegenesis.mobile.model.NodeRouteSnapshot
import com.jadegenesis.mobile.model.NodeRouteStatus
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.model.NodeTaskResponse
import com.jadegenesis.mobile.model.ResourceBudget
import com.jadegenesis.mobile.model.TaskWorkload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class NodeManager(
    context: Context,
    private val profiler: DeviceProfiler,
    private val logger: DiagnosticLogger? = null
) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "jade_genesis_nodes",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_REMOTE_NODES_V2 = "remote_nodes_v2"
        private const val KEY_REMOTE_NODES_V1 = "remote_nodes_v1"
        private const val PROTOCOL = "jade-genesis-node/0.0.6"
        private const val LEGACY_PROTOCOL_005 = "jade-genesis-node/0.0.5"
        private const val LEGACY_PROTOCOL_004 = "jade-genesis-node/0.0.4"
        private const val DEFAULT_PORT = 8765
        private const val MAX_PAYLOAD_CHARS = 2_500_000
        private const val NODE_STALE_MS = 90_000L
        private const val ASYNC_TASK_TIMEOUT_MS = 180_000L
        private const val ASYNC_POLL_MS = 900L
    }

    private data class StoredRoute(
        val routeId: String,
        val kind: NodeRouteKind,
        val host: String,
        val port: Int,
        val status: NodeRouteStatus,
        val latencyMs: Long?,
        val lastSeenAt: Long,
        val lastError: String?
    ) {
        fun snapshot(): NodeRouteSnapshot = NodeRouteSnapshot(
            routeId = routeId,
            kind = kind,
            host = host,
            port = port,
            status = status,
            latencyMs = latencyMs,
            lastSeenAt = lastSeenAt,
            lastError = lastError
        )
    }

    private data class StoredNode(
        val nodeId: String,
        val name: String,
        val kind: NodeKind,
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
        val lastError: String?,
        val routes: List<StoredRoute>,
        val activeRouteId: String?,
        val runtimeVersion: String,
        val runtimeChannel: String,
        val brainBackend: String,
        val brainModel: String
    ) {
        fun publicNode(stale: Boolean = false): GenesisNode {
            val active = routes.firstOrNull { it.routeId == activeRouteId }
                ?: routes.firstOrNull()
            val publicStatus = if (stale && status == NodeStatus.ONLINE) {
                NodeStatus.UNKNOWN
            } else {
                status
            }
            return GenesisNode(
                nodeId = nodeId,
                name = name,
                kind = kind,
                status = publicStatus,
                host = active?.host.orEmpty(),
                port = active?.port ?: 0,
                protocol = protocol,
                osName = osName,
                cpuName = cpuName,
                cpuCores = cpuCores,
                ramTotalGb = ramTotalGb,
                ramAvailableGb = ramAvailableGb,
                storageFreeGb = storageFreeGb,
                capabilities = capabilities,
                lastSeenAt = lastSeenAt,
                lastError = if (stale && status == NodeStatus.ONLINE) {
                    "État à rafraîchir."
                } else {
                    lastError
                },
                routes = routes.map { route ->
                    if (stale && route.status == NodeRouteStatus.ONLINE) {
                        route.copy(status = NodeRouteStatus.UNKNOWN).snapshot()
                    } else {
                        route.snapshot()
                    }
                },
                activeRouteId = activeRouteId,
                runtimeVersion = runtimeVersion,
                runtimeChannel = runtimeChannel,
                brainBackend = brainBackend,
                brainModel = brainModel
            )
        }
    }

    private data class RouteProbe(
        val route: StoredRoute,
        val json: JSONObject? = null,
        val unauthorized: Boolean = false
    )

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
            "distributed_brain_client",
            "device_registry_v2",
            "device_registry_v2_1",
            "multi_route_v1",
            "compute_mesh_v1",
            "cognitive_core_v1",
            "diagnostics_v1",
            "task_execution_v1",
            "task_execution_v2",
            "task_execution_v3",
            "genesis_probe",
            "text_analysis",
            "memory_consolidation",
            "task_queue_v1"
        ),
        lastSeenAt = System.currentTimeMillis(),
        runtimeVersion = "0.1.1",
        runtimeChannel = "android"
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

    suspend fun registerNode(
        host: String,
        port: Int,
        token: String
    ): GenesisNode {
        val cleanHost = normalizeHost(host)
        require(cleanHost.isNotBlank()) { "Adresse du nœud vide." }
        require(port in 1..65535) { "Port invalide." }
        require(token.isNotBlank()) { "Jeton du Node Runtime vide." }

        val existingNodes = loadStoredNodes().toMutableList()
        val routeOwner = existingNodes.firstOrNull { node ->
            node.routes.any {
                it.host.equals(cleanHost, ignoreCase = true) && it.port == port
            }
        }
        val existingRoute = routeOwner?.routes?.firstOrNull {
            it.host.equals(cleanHost, ignoreCase = true) && it.port == port
        }
        val route = existingRoute?.copy(
            status = NodeRouteStatus.UNKNOWN,
            lastError = null
        ) ?: StoredRoute(
            routeId = "route-${UUID.randomUUID()}",
            kind = classifyRoute(cleanHost),
            host = cleanHost,
            port = port,
            status = NodeRouteStatus.UNKNOWN,
            latencyMs = null,
            lastSeenAt = 0L,
            lastError = null
        )

        val draft = routeOwner?.copy(
            token = token.trim(),
            routes = routeOwner.routes.map {
                if (it.routeId == route.routeId) route else it
            }
        ) ?: StoredNode(
            nodeId = "remote-${UUID.randomUUID()}",
            name = "Nœud Genesis",
            kind = NodeKind.UNKNOWN,
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
            lastError = null,
            routes = listOf(route),
            activeRouteId = route.routeId,
            runtimeVersion = "",
            runtimeChannel = "",
            brainBackend = "",
            brainModel = ""
        )

        val probed = probeNode(draft)
        val remoteId = probed.nodeId
        val sameIdentity = existingNodes.firstOrNull {
            it.nodeId == remoteId && it !== routeOwner
        }

        val merged = if (sameIdentity != null) {
            val mergedRoutes = (sameIdentity.routes + probed.routes)
                .distinctBy { "${it.host.lowercase()}:${it.port}" }
            probed.copy(
                routes = mergedRoutes,
                token = token.trim(),
                activeRouteId = probed.activeRouteId ?: sameIdentity.activeRouteId
            )
        } else {
            probed
        }

        existingNodes.removeAll { existing ->
            existing === routeOwner ||
                existing.nodeId == merged.nodeId ||
                existing.routes.any { oldRoute ->
                    merged.routes.any {
                        it.host.equals(oldRoute.host, ignoreCase = true) &&
                            it.port == oldRoute.port
                    }
                }
        }
        existingNodes.add(merged)
        saveStoredNodes(existingNodes)

        logger?.log(
            if (merged.status == NodeStatus.ONLINE) DiagnosticLevel.INFO else DiagnosticLevel.WARN,
            "node_registered",
            "Nœud ${merged.name} enregistré avec ${merged.routes.size} route(s).",
            mapOf(
                "node_id" to merged.nodeId,
                "kind" to merged.kind.name,
                "route_count" to merged.routes.size,
                "status" to merged.status.name
            )
        )

        return currentPublicNode(merged)
    }

    suspend fun registerPcNode(
        host: String,
        port: Int,
        token: String
    ): GenesisNode = registerNode(host, port, token)

    suspend fun refreshRemoteNodes(): List<GenesisNode> {
        val current = loadStoredNodes()
        val refreshed = current.map { node -> probeNode(node) }
        saveStoredNodes(refreshed)
        return refreshed.map { currentPublicNode(it) }
    }

    fun preferredComputeNode(
        nodes: List<GenesisNode>,
        budget: ResourceBudget
    ): GenesisNode? {
        val local = nodes.firstOrNull { it.status == NodeStatus.LOCAL }
        val onlineRemote = nodes
            .filter {
                it.status == NodeStatus.ONLINE && it.kind != NodeKind.PHONE
            }
            .sortedWith(
                compareByDescending<GenesisNode> { "compute" in it.capabilities }
                    .thenByDescending { "task_execution_v3" in it.capabilities }
                    .thenByDescending { it.ramAvailableGb }
                    .thenByDescending { it.cpuCores }
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

        var node = loadStoredNodes().firstOrNull { it.nodeId == nodeId }
            ?: error("Nœud distant inconnu : $nodeId")

        if (node.status != NodeStatus.ONLINE || routeCandidates(node).isEmpty()) {
            node = probeNode(node)
            replaceStoredNode(node)
        }
        if (node.status != NodeStatus.ONLINE) {
            error("Le nœud ${node.name} n'est pas en ligne.")
        }
        if ("task_execution_v3" !in node.capabilities) {
            error("Le nœud ${node.name} n'annonce pas task_execution_v3.")
        }
        if (request.requiredCapability !in node.capabilities) {
            error("Le nœud ${node.name} ne sait pas exécuter ${request.taskKind}.")
        }

        val response = if (
            request.taskKind == "brain_chat" &&
            "async_tasks_v1" in node.capabilities
        ) {
            executeAsyncTask(node, request)
        } else {
            executeSyncTask(node, request)
        }

        logger?.log(
            DiagnosticLevel.INFO,
            "task_remote_complete",
            "${request.taskKind} terminé sur ${response.nodeName}.",
            mapOf(
                "task_id" to request.taskId,
                "node_id" to response.nodeId,
                "duration_ms" to response.durationMs
            )
        )
        response
    }

    private suspend fun executeSyncTask(
        node: StoredNode,
        request: DistributedTaskRequest
    ): NodeTaskResponse {
        val failures = mutableListOf<String>()
        for (route in routeCandidates(node)) {
            val attempt = runCatching {
                executeSyncOnRoute(node, route, request)
            }
            attempt.getOrNull()?.let { response ->
                markActiveRoute(node.nodeId, route.routeId)
                return response
            }
            failures.add(
                "${route.kind}/${route.host}:${route.port}: " +
                    (attempt.exceptionOrNull()?.message ?: "échec")
            )
            logger?.log(
                DiagnosticLevel.WARN,
                "route_task_failure",
                "Échec de route pendant ${request.taskKind}.",
                mapOf(
                    "node_id" to node.nodeId,
                    "route_kind" to route.kind.name,
                    "host" to route.host,
                    "port" to route.port,
                    "error" to attempt.exceptionOrNull()?.message
                )
            )
        }
        error(failures.joinToString(" | ").take(700))
    }

    private fun executeSyncOnRoute(
        node: StoredNode,
        route: StoredRoute,
        request: DistributedTaskRequest
    ): NodeTaskResponse {
        val requestBody = taskRequestBody(request)
        val readTimeoutMs = when (request.workload) {
            TaskWorkload.LIGHT -> 8_000
            TaskWorkload.MEDIUM -> 18_000
            TaskWorkload.HEAVY -> 60_000
        }
        val (code, body) = httpRequest(
            route = route,
            token = node.token,
            method = "POST",
            path = "/task",
            requestBody = requestBody,
            connectTimeoutMs = 2_500,
            readTimeoutMs = readTimeoutMs
        )
        return parseCompletedTaskResponse(node, request, code, body)
    }

    private suspend fun executeAsyncTask(
        node: StoredNode,
        request: DistributedTaskRequest
    ): NodeTaskResponse {
        val requestBody = taskRequestBody(request)
        val failures = mutableListOf<String>()
        var acceptedRoute: StoredRoute? = null

        for (route in routeCandidates(node)) {
            val submission = runCatching {
                httpRequest(
                    route = route,
                    token = node.token,
                    method = "POST",
                    path = "/tasks",
                    requestBody = requestBody,
                    connectTimeoutMs = 2_500,
                    readTimeoutMs = 5_000
                )
            }
            val pair = submission.getOrNull()
            if (pair != null && pair.first in listOf(200, 202)) {
                val json = JSONObject(pair.second)
                if (json.optBoolean("success", false)) {
                    acceptedRoute = route
                    markActiveRoute(node.nodeId, route.routeId)
                    break
                }
            }
            failures.add(
                "${route.kind}: " +
                    (submission.exceptionOrNull()?.message ?: pair?.second?.take(120) ?: "échec")
            )
        }

        if (acceptedRoute == null) {
            error(
                "Impossible de soumettre la tâche asynchrone : " +
                    failures.joinToString(" | ").take(600)
            )
        }

        logger?.log(
            DiagnosticLevel.INFO,
            "async_task_accepted",
            "${request.taskKind} accepté par ${node.name}; la génération continue indépendamment de la socket du Pixel.",
            mapOf("task_id" to request.taskId, "node_id" to node.nodeId)
        )

        val deadline = System.currentTimeMillis() + ASYNC_TASK_TIMEOUT_MS
        var pollRoutes = routeCandidates(node).toMutableList()
        while (System.currentTimeMillis() < deadline) {
            var anyRouteReached = false
            val iterator = pollRoutes.toList()
            for (route in iterator) {
                val poll = runCatching {
                    httpRequest(
                        route = route,
                        token = node.token,
                        method = "GET",
                        path = "/tasks/${request.taskId}",
                        requestBody = null,
                        connectTimeoutMs = 2_500,
                        readTimeoutMs = 5_000
                    )
                }
                val pair = poll.getOrNull() ?: continue
                anyRouteReached = true
                if (pair.first != HttpURLConnection.HTTP_OK) continue

                val json = JSONObject(pair.second)
                if (!json.optBoolean("success", false)) {
                    error(json.optString("error", "Échec de suivi de tâche."))
                }
                when (json.optString("status").uppercase()) {
                    "COMPLETED" -> {
                        markActiveRoute(node.nodeId, route.routeId)
                        return NodeTaskResponse(
                            taskId = request.taskId,
                            taskKind = request.taskKind,
                            nodeId = json.optString("node_id", node.nodeId),
                            nodeName = json.optString("node_name", node.name)
                                .ifBlank { node.name },
                            output = json.getString("result"),
                            durationMs = json.optLong("duration_ms", 0L)
                        )
                    }

                    "FAILED" -> {
                        error(
                            json.optString(
                                "error",
                                "La tâche asynchrone a échoué."
                            )
                        )
                    }
                }
            }

            if (!anyRouteReached && pollRoutes.size > 1) {
                pollRoutes = pollRoutes.drop(1).plus(pollRoutes.first()).toMutableList()
            }
            delay(ASYNC_POLL_MS)
        }
        error("La tâche asynchrone ${request.taskId} a dépassé ${ASYNC_TASK_TIMEOUT_MS / 1000}s.")
    }

    private fun parseCompletedTaskResponse(
        node: StoredNode,
        request: DistributedTaskRequest,
        responseCode: Int,
        body: String
    ): NodeTaskResponse {
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
        if (!protocolAccepted(protocol)) {
            error("Réponse de tâche avec protocole incompatible : $protocol")
        }
        if (!json.optBoolean("success", false)) {
            error(json.optString("error", "Échec de la tâche distante."))
        }
        if (json.optString("task_id") != request.taskId) {
            error("Le Node Runtime a renvoyé un autre identifiant de tâche.")
        }
        if (json.optString("task_kind") != request.taskKind) {
            error("Le Node Runtime a renvoyé un autre type de tâche.")
        }

        return NodeTaskResponse(
            taskId = request.taskId,
            taskKind = request.taskKind,
            nodeId = json.optString("node_id", node.nodeId),
            nodeName = json.optString("node_name", node.name).ifBlank { node.name },
            output = json.getString("result"),
            durationMs = json.optLong("duration_ms", 0L)
        )
    }

    private fun taskRequestBody(request: DistributedTaskRequest): ByteArray =
        JSONObject().apply {
            put("protocol", PROTOCOL)
            put("task_id", request.taskId)
            put("task_kind", request.taskKind)
            put("payload", request.payload)
            put("workload", request.workload.name)
            put("iterations", request.iterations)
        }.toString().toByteArray(Charsets.UTF_8)

    private suspend fun probeNode(node: StoredNode): StoredNode =
        withContext(Dispatchers.IO) {
            if (node.routes.isEmpty()) {
                return@withContext node.copy(
                    status = NodeStatus.OFFLINE,
                    lastError = "Aucune route enregistrée."
                )
            }

            val probes = node.routes.map { route -> probeRoute(node, route) }
            val successful = probes.filter { it.json != null }
            if (successful.isEmpty()) {
                val unauthorized = probes.any { it.unauthorized }
                val error = probes.mapNotNull { it.route.lastError }
                    .distinct()
                    .joinToString(" | ")
                    .take(300)
                val updated = node.copy(
                    routes = probes.map { it.route },
                    status = if (unauthorized) NodeStatus.ERROR else NodeStatus.OFFLINE,
                    lastError = error.ifBlank { "Aucune route joignable." }
                )
                logger?.log(
                    DiagnosticLevel.WARN,
                    "node_probe_offline",
                    "${node.name} n'est joignable par aucune route.",
                    mapOf("node_id" to node.nodeId, "route_count" to node.routes.size)
                )
                return@withContext updated
            }

            val best = successful.minBy { probe ->
                val latency = probe.route.latencyMs ?: Long.MAX_VALUE / 4
                latency + when (probe.route.kind) {
                    NodeRouteKind.LAN -> 0L
                    NodeRouteKind.TAILSCALE -> 5L
                    NodeRouteKind.MANUAL -> 10L
                }
            }
            val json = best.json ?: JSONObject()
            val protocol = json.optString("protocol")
            val remoteId = json.optString("node_id", node.nodeId)
                .ifBlank { node.nodeId }
            val name = json.optString("name", node.name).ifBlank { node.name }
            val updated = node.copy(
                nodeId = remoteId,
                name = name,
                kind = parseKind(json.optString("kind", node.kind.name)),
                protocol = protocol,
                status = NodeStatus.ONLINE,
                osName = json.optString("os", node.osName),
                cpuName = json.optString("cpu", node.cpuName),
                cpuCores = json.optInt("cpu_cores", node.cpuCores),
                ramTotalGb = json.optDouble("ram_total_gb", node.ramTotalGb),
                ramAvailableGb = json.optDouble("ram_available_gb", node.ramAvailableGb),
                storageFreeGb = json.optDouble("storage_free_gb", node.storageFreeGb),
                capabilities = parseStringArray(json.optJSONArray("capabilities")),
                lastSeenAt = System.currentTimeMillis(),
                lastError = null,
                routes = probes.map { it.route },
                activeRouteId = best.route.routeId,
                runtimeVersion = json.optString("agent_version", node.runtimeVersion),
                runtimeChannel = json.optString("runtime_channel", node.runtimeChannel),
                brainBackend = json.optString("brain_backend", node.brainBackend),
                brainModel = json.optString("brain_model", node.brainModel)
            )

            logger?.log(
                DiagnosticLevel.DEBUG,
                "node_probe_online",
                "${updated.name} joignable via ${best.route.kind}.",
                mapOf(
                    "node_id" to updated.nodeId,
                    "latency_ms" to best.route.latencyMs,
                    "route_kind" to best.route.kind.name,
                    "runtime_version" to updated.runtimeVersion
                )
            )
            updated
        }

    private fun probeRoute(node: StoredNode, route: StoredRoute): RouteProbe {
        val startedNs = System.nanoTime()
        return try {
            val (code, body) = httpRequest(
                route = route,
                token = node.token,
                method = "GET",
                path = "/health",
                requestBody = null,
                connectTimeoutMs = 1_800,
                readTimeoutMs = 2_800
            )
            val latency = (System.nanoTime() - startedNs) / 1_000_000L
            if (code == 401) {
                RouteProbe(
                    route = route.copy(
                        status = NodeRouteStatus.ERROR,
                        latencyMs = latency,
                        lastError = "Jeton refusé par le Node Runtime."
                    ),
                    unauthorized = true
                )
            } else if (code != HttpURLConnection.HTTP_OK) {
                RouteProbe(
                    route = route.copy(
                        status = NodeRouteStatus.OFFLINE,
                        latencyMs = latency,
                        lastError = "Node Runtime HTTP $code."
                    )
                )
            } else {
                val json = JSONObject(body)
                val protocol = json.optString("protocol")
                if (!protocolAccepted(protocol)) {
                    RouteProbe(
                        route = route.copy(
                            status = NodeRouteStatus.ERROR,
                            latencyMs = latency,
                            lastError = "Protocole incompatible : $protocol"
                        )
                    )
                } else {
                    RouteProbe(
                        route = route.copy(
                            status = NodeRouteStatus.ONLINE,
                            latencyMs = latency,
                            lastSeenAt = System.currentTimeMillis(),
                            lastError = null
                        ),
                        json = json
                    )
                }
            }
        } catch (e: Exception) {
            RouteProbe(
                route = route.copy(
                    status = NodeRouteStatus.OFFLINE,
                    latencyMs = null,
                    lastError = e.message?.take(140) ?: e::class.java.simpleName
                )
            )
        }
    }

    private fun httpRequest(
        route: StoredRoute,
        token: String,
        method: String,
        path: String,
        requestBody: ByteArray?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): Pair<Int, String> {
        val connection = (
            URL("http://${route.host}:${route.port}$path")
                .openConnection() as HttpURLConnection
            ).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            setRequestProperty("X-Jade-Token", token)
            setRequestProperty("Accept", "application/json")
            if (requestBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setFixedLengthStreamingMode(requestBody.size)
            }
        }

        return try {
            if (requestBody != null) {
                connection.outputStream.use { it.write(requestBody) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            code to body
        } finally {
            connection.disconnect()
        }
    }

    private fun routeCandidates(node: StoredNode): List<StoredRoute> =
        node.routes.sortedWith(
            compareByDescending<StoredRoute> {
                it.routeId == node.activeRouteId
            }.thenByDescending {
                it.status == NodeRouteStatus.ONLINE
            }.thenBy {
                it.latencyMs ?: Long.MAX_VALUE
            }.thenBy {
                when (it.kind) {
                    NodeRouteKind.LAN -> 0
                    NodeRouteKind.TAILSCALE -> 1
                    NodeRouteKind.MANUAL -> 2
                }
            }
        )

    private fun currentPublicNode(node: StoredNode): GenesisNode {
        val stale =
            node.status == NodeStatus.ONLINE &&
                node.lastSeenAt > 0L &&
                System.currentTimeMillis() - node.lastSeenAt > NODE_STALE_MS
        return node.publicNode(stale)
    }

    private fun markActiveRoute(nodeId: String, routeId: String) {
        val nodes = loadStoredNodes().toMutableList()
        val index = nodes.indexOfFirst { it.nodeId == nodeId }
        if (index < 0) return
        val node = nodes[index]
        nodes[index] = node.copy(activeRouteId = routeId)
        saveStoredNodes(nodes)
    }

    private fun replaceStoredNode(node: StoredNode) {
        val nodes = loadStoredNodes().toMutableList()
        nodes.removeAll { it.nodeId == node.nodeId }
        nodes.add(node)
        saveStoredNodes(nodes)
    }

    private fun protocolAccepted(value: String): Boolean =
        value == PROTOCOL ||
            value == LEGACY_PROTOCOL_005 ||
            value == LEGACY_PROTOCOL_004

    private fun normalizeHost(raw: String): String =
        raw.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .substringBefore(":")
            .trim()

    private fun classifyRoute(host: String): NodeRouteKind {
        val normalized = host.lowercase()
        if (normalized.endsWith(".ts.net")) return NodeRouteKind.TAILSCALE
        val parts = normalized.split('.')
        if (parts.size == 4) {
            val octets = parts.mapNotNull { it.toIntOrNull() }
            if (octets.size == 4) {
                val first = octets[0]
                val second = octets[1]
                if (first == 100 && second in 64..127) {
                    return NodeRouteKind.TAILSCALE
                }
                if (
                    first == 10 ||
                    (first == 172 && second in 16..31) ||
                    (first == 192 && second == 168)
                ) {
                    return NodeRouteKind.LAN
                }
            }
        }
        return NodeRouteKind.MANUAL
    }

    private fun parseKind(value: String): NodeKind =
        runCatching { NodeKind.valueOf(value.uppercase()) }
            .getOrDefault(NodeKind.UNKNOWN)

    private fun parseNodeStatus(value: String): NodeStatus =
        runCatching { NodeStatus.valueOf(value.uppercase()) }
            .getOrDefault(NodeStatus.UNKNOWN)

    private fun parseRouteStatus(value: String): NodeRouteStatus =
        runCatching { NodeRouteStatus.valueOf(value.uppercase()) }
            .getOrDefault(NodeRouteStatus.UNKNOWN)

    private fun parseRouteKind(value: String): NodeRouteKind =
        runCatching { NodeRouteKind.valueOf(value.uppercase()) }
            .getOrDefault(NodeRouteKind.MANUAL)

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
        val rawV2 = prefs.getString(KEY_REMOTE_NODES_V2, null)
        if (!rawV2.isNullOrBlank()) {
            val parsed = parseV2(rawV2)
            val normalized = deduplicateStoredNodes(parsed)
            if (normalized != parsed) {
                saveStoredNodes(normalized)
                logger?.log(
                    DiagnosticLevel.INFO,
                    "node_registry_deduplicated",
                    "Device Registry v2.1 a fusionné les doublons partageant le même Node ID.",
                    mapOf(
                        "before" to parsed.size,
                        "after" to normalized.size
                    )
                )
            }
            return normalized
        }

        val rawV1 = prefs.getString(KEY_REMOTE_NODES_V1, null)
            ?: return emptyList()
        val migrated = deduplicateStoredNodes(migrateV1(rawV1))
        if (migrated.isNotEmpty()) {
            saveStoredNodes(migrated)
            logger?.log(
                DiagnosticLevel.INFO,
                "node_registry_migrated",
                "Ancien registre de nœuds migré vers Device Registry v2.1 et dédupliqué.",
                mapOf("node_count" to migrated.size)
            )
        }
        return migrated
    }

    private fun deduplicateStoredNodes(nodes: List<StoredNode>): List<StoredNode> {
        if (nodes.size < 2) return nodes

        val groups = linkedMapOf<String, MutableList<StoredNode>>()
        nodes.forEach { node ->
            val stableId = node.nodeId.trim().lowercase()
            val fallbackRoute = node.routes.firstOrNull()?.let { route ->
                "${route.host.lowercase()}:${route.port}"
            }.orEmpty()
            val key = if (stableId.isNotBlank()) {
                "id:$stableId"
            } else {
                "route:$fallbackRoute"
            }
            groups.getOrPut(key) { mutableListOf() }.add(node)
        }

        return groups.values.map { group -> mergeStoredNodeGroup(group) }
    }

    private fun mergeStoredNodeGroup(group: List<StoredNode>): StoredNode {
        if (group.size == 1) return group.first()

        fun statusRank(status: NodeStatus): Long = when (status) {
            NodeStatus.ONLINE -> 5L
            NodeStatus.LOCAL -> 4L
            NodeStatus.UNKNOWN -> 3L
            NodeStatus.OFFLINE -> 2L
            NodeStatus.ERROR -> 1L
        }

        fun routeRank(route: StoredRoute): Long {
            val status = when (route.status) {
                NodeRouteStatus.ONLINE -> 4L
                NodeRouteStatus.UNKNOWN -> 3L
                NodeRouteStatus.OFFLINE -> 2L
                NodeRouteStatus.ERROR -> 1L
            }
            return status * 10_000_000_000_000L + route.lastSeenAt
        }

        val preferred = group.maxByOrNull { node ->
            statusRank(node.status) * 10_000_000_000_000L + node.lastSeenAt
        } ?: group.first()

        val mergedRoutes = group
            .flatMap { it.routes }
            .groupBy { "${it.host.lowercase()}:${it.port}" }
            .values
            .map { routes -> routes.maxByOrNull(::routeRank) ?: routes.first() }

        val preferredActive = preferred.routes.firstOrNull {
            it.routeId == preferred.activeRouteId
        }
        val activeRouteId = preferredActive?.let { active ->
            mergedRoutes.firstOrNull { route ->
                route.host.equals(active.host, ignoreCase = true) &&
                    route.port == active.port
            }?.routeId
        } ?: mergedRoutes
            .filter { it.status == NodeRouteStatus.ONLINE }
            .minByOrNull { it.latencyMs ?: Long.MAX_VALUE }
            ?.routeId
            ?: mergedRoutes.firstOrNull()?.routeId

        val mergedCapabilities = group
            .flatMap { it.capabilities }
            .filter { it.isNotBlank() }
            .distinct()

        return preferred.copy(
            token = preferred.token.ifBlank {
                group.firstOrNull { it.token.isNotBlank() }?.token.orEmpty()
            },
            status = if (group.any { it.status == NodeStatus.ONLINE }) {
                NodeStatus.ONLINE
            } else {
                preferred.status
            },
            capabilities = mergedCapabilities,
            lastSeenAt = group.maxOfOrNull { it.lastSeenAt } ?: preferred.lastSeenAt,
            lastError = if (group.any { it.status == NodeStatus.ONLINE }) {
                null
            } else {
                preferred.lastError
            },
            routes = mergedRoutes,
            activeRouteId = activeRouteId
        )
    }

    private fun parseV2(raw: String): List<StoredNode> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val json = array.getJSONObject(index)
                val routesJson = json.optJSONArray("routes") ?: JSONArray()
                val routes = buildList {
                    for (routeIndex in 0 until routesJson.length()) {
                        val route = routesJson.getJSONObject(routeIndex)
                        add(
                            StoredRoute(
                                routeId = route.getString("route_id"),
                                kind = parseRouteKind(route.optString("kind")),
                                host = route.getString("host"),
                                port = route.optInt("port", DEFAULT_PORT),
                                status = parseRouteStatus(route.optString("status")),
                                latencyMs = route.optLong("latency_ms", -1L)
                                    .takeIf { it >= 0L },
                                lastSeenAt = route.optLong("last_seen_at"),
                                lastError = route.optString("last_error")
                                    .takeIf { it.isNotBlank() }
                            )
                        )
                    }
                }
                add(
                    StoredNode(
                        nodeId = json.getString("node_id"),
                        name = json.optString("name", "Nœud Genesis"),
                        kind = parseKind(json.optString("kind")),
                        token = json.getString("token"),
                        protocol = json.optString("protocol"),
                        status = parseNodeStatus(json.optString("status")),
                        osName = json.optString("os"),
                        cpuName = json.optString("cpu"),
                        cpuCores = json.optInt("cpu_cores"),
                        ramTotalGb = json.optDouble("ram_total_gb"),
                        ramAvailableGb = json.optDouble("ram_available_gb"),
                        storageFreeGb = json.optDouble("storage_free_gb"),
                        capabilities = parseStringArray(json.optJSONArray("capabilities")),
                        lastSeenAt = json.optLong("last_seen_at"),
                        lastError = json.optString("last_error")
                            .takeIf { it.isNotBlank() },
                        routes = routes,
                        activeRouteId = json.optString("active_route_id")
                            .takeIf { it.isNotBlank() },
                        runtimeVersion = json.optString("runtime_version"),
                        runtimeChannel = json.optString("runtime_channel"),
                        brainBackend = json.optString("brain_backend"),
                        brainModel = json.optString("brain_model")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun migrateV1(raw: String): List<StoredNode> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val json = array.getJSONObject(index)
                val host = json.getString("host")
                val port = json.optInt("port", DEFAULT_PORT)
                val routeId = "legacy-route-$index"
                val nodeStatus = parseNodeStatus(json.optString("status"))
                val routeStatus = when (nodeStatus) {
                    NodeStatus.ONLINE -> NodeRouteStatus.ONLINE
                    NodeStatus.ERROR -> NodeRouteStatus.ERROR
                    NodeStatus.OFFLINE -> NodeRouteStatus.OFFLINE
                    else -> NodeRouteStatus.UNKNOWN
                }
                add(
                    StoredNode(
                        nodeId = json.getString("node_id"),
                        name = json.optString("name", "Nœud Genesis"),
                        kind = parseKind(json.optString("kind")),
                        token = json.getString("token"),
                        protocol = json.optString("protocol"),
                        status = nodeStatus,
                        osName = json.optString("os"),
                        cpuName = json.optString("cpu"),
                        cpuCores = json.optInt("cpu_cores"),
                        ramTotalGb = json.optDouble("ram_total_gb"),
                        ramAvailableGb = json.optDouble("ram_available_gb"),
                        storageFreeGb = json.optDouble("storage_free_gb"),
                        capabilities = parseStringArray(json.optJSONArray("capabilities")),
                        lastSeenAt = json.optLong("last_seen_at"),
                        lastError = json.optString("last_error")
                            .takeIf { it.isNotBlank() },
                        routes = listOf(
                            StoredRoute(
                                routeId = routeId,
                                kind = classifyRoute(host),
                                host = host,
                                port = port,
                                status = routeStatus,
                                latencyMs = null,
                                lastSeenAt = json.optLong("last_seen_at"),
                                lastError = json.optString("last_error")
                                    .takeIf { it.isNotBlank() }
                            )
                        ),
                        activeRouteId = routeId,
                        runtimeVersion = "",
                        runtimeChannel = "",
                        brainBackend = "",
                        brainModel = ""
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun saveStoredNodes(nodes: List<StoredNode>) {
        val array = JSONArray()
        nodes.forEach { node ->
            array.put(
                JSONObject().apply {
                    put("node_id", node.nodeId)
                    put("name", node.name)
                    put("kind", node.kind.name)
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
                    put("active_route_id", node.activeRouteId ?: "")
                    put("runtime_version", node.runtimeVersion)
                    put("runtime_channel", node.runtimeChannel)
                    put("brain_backend", node.brainBackend)
                    put("brain_model", node.brainModel)
                    put(
                        "routes",
                        JSONArray().apply {
                            node.routes.forEach { route ->
                                put(
                                    JSONObject().apply {
                                        put("route_id", route.routeId)
                                        put("kind", route.kind.name)
                                        put("host", route.host)
                                        put("port", route.port)
                                        put("status", route.status.name)
                                        put("latency_ms", route.latencyMs ?: -1L)
                                        put("last_seen_at", route.lastSeenAt)
                                        put("last_error", route.lastError ?: "")
                                    }
                                )
                            }
                        }
                    )
                }
            )
        }
        prefs.edit().putString(KEY_REMOTE_NODES_V2, array.toString()).apply()
    }
}
