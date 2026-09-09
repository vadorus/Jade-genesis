package com.jadegenesis.mobile.brain

import com.jadegenesis.mobile.config.JadeConfigRuntime
import com.jadegenesis.mobile.model.BrainBackendType
import com.jadegenesis.mobile.model.BrainContext
import com.jadegenesis.mobile.model.BrainInfo
import com.jadegenesis.mobile.model.BrainResourceClass
import com.jadegenesis.mobile.model.BrainResult
import com.jadegenesis.mobile.model.DistributedTaskRequest
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.model.TaskWorkload
import com.jadegenesis.mobile.node.NodeManager
import com.jadegenesis.mobile.resource.NodeResourceScorer
import com.jadegenesis.mobile.resource.ResourceAdmissionController
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class LocalPCBrain(
    private val nodeManager: NodeManager,
    private val admissionController: ResourceAdmissionController =
        ResourceAdmissionController()
) : BrainBackend {

    override val info = BrainInfo(
        id = "distributed-local-brain-0.1.13",
        displayName = "Distributed Cognitive Brain",
        backendType = BrainBackendType.LOCAL_NODE,
        location = "compute-mesh",
        resourceClass = BrainResourceClass.HEAVY,
        requiresNetwork = true,
        paidApi = false,
        available = true,
        priority = 110,
        details =
            "Backend génératif distribué avec profils FAST/GENERAL/REASONING/CODE/CRITIC. " +
                "Le modèle est une ressource cognitive interchangeable ; la sélection combine " +
                "rôle demandé, télémétrie, performances mesurées et Resource Lease."
    )

    override fun availableFor(nodes: List<GenesisNode>): Boolean =
        compatibleNodes(nodes).isNotEmpty()

    override suspend fun think(context: BrainContext): BrainResult {
        val brainPlan = CognitiveBrainPolicy.plan(context)
        val compatible = compatibleNodes(context.selfModel.knownNodes)
        val preferredId = context.selfModel.preferredComputeNodeId
        val routing = JadeConfigRuntime.current().validated().routing
        val ranked = compatible
            .sortedByDescending { candidate ->
                NodeResourceScorer.generativeScore(
                    node = candidate,
                    routing = routing,
                    preferredNodeId = preferredId
                )
            }

        if (ranked.isEmpty()) {
            error("Aucun nœud génératif en ligne n'annonce brain_chat.")
        }

        val memories = context.memories
            .sortedBy {
                if (it.source.startsWith("JADE_CONSOLIDATION_")) 1 else 0
            }
            .take(10)

        val taskId = "brain-${UUID.randomUUID()}"
        val admissionProbe = DistributedTaskRequest(
            taskId = taskId,
            taskKind = "brain_chat",
            payload = context.userInput.take(10_000),
            requiredCapability = "brain_chat",
            workload = when (brainPlan.profile) {
                CognitiveBrainProfile.FAST -> TaskWorkload.MEDIUM
                else -> TaskWorkload.HEAVY
            },
            createdAt = System.currentTimeMillis()
        )

        var selectedNode: GenesisNode? = null
        var selectedLease: com.jadegenesis.mobile.resource.ResourceLease? = null
        val admissionFailures = mutableListOf<String>()

        for (candidate in ranked) {
            val admission = admissionController.tryAcquire(
                request = admissionProbe,
                node = candidate,
                budget = context.selfModel.resourceBudget
            )
            if (admission.admitted && admission.lease != null) {
                selectedNode = candidate
                selectedLease = admission.lease
                break
            }
            admissionFailures +=
                "${candidate.name}: ${admission.action} ${admission.reason}"
        }

        val node = selectedNode ?: error(
            "Aucun nœud génératif n'a obtenu de Resource Lease. " +
                admissionFailures.joinToString(" | ").take(700)
        )
        val lease = selectedLease ?: error("Resource Lease génératif absent.")

        val payload = JSONObject().apply {
            put(
                "identity",
                JSONObject().apply {
                    put("jade_id", context.selfModel.identity.jadeId)
                    put("name", context.selfModel.identity.name)
                    put("version", context.selfModel.identity.version)
                }
            )
            put("operation", context.operation)
            put("brain_profile", brainPlan.profile.name.lowercase())
            put("brain_profile_reason", brainPlan.reason.take(300))
            put("desired_context_tokens", brainPlan.desiredContextTokens)
            put("desired_temperature", brainPlan.temperature)
            put("user_input", context.userInput.take(10_000))
            put("draft_response", context.draftResponse?.take(14_000) ?: "")
            put("review_note", context.reviewNote?.take(2_000) ?: "")
            put(
                "self",
                JSONObject().apply {
                    put("node_id", context.selfModel.nodeId)
                    put(
                        "device",
                        listOf(
                            context.selfModel.device.manufacturer,
                            context.selfModel.device.model,
                            "Android ${context.selfModel.device.androidVersion}"
                        ).filter { it.isNotBlank() }
                            .joinToString(" ")
                    )
                    put(
                        "resource_mode",
                        context.selfModel.resourceBudget.mode.name
                    )
                    put(
                        "preferred_compute_node_id",
                        preferredId ?: ""
                    )
                    put(
                        "preferred_compute_node",
                        context.selfModel.knownNodes
                            .firstOrNull { it.nodeId == preferredId }
                            ?.name
                            ?: "aucun"
                    )
                    put("selected_brain_node_id", node.nodeId)
                    put("selected_brain_node", node.name)
                }
            )
            put(
                "nodes",
                JSONArray().apply {
                    context.selfModel.knownNodes.forEach { known ->
                        put(
                            JSONObject().apply {
                                put("node_id", known.nodeId)
                                put("name", known.name)
                                put("kind", known.kind.name)
                                put("status", known.status.name)
                                put("cpu_cores", known.cpuCores)
                                put("cpu_load_percent", known.cpuLoadPercent)
                                put("ram_total_gb", known.ramTotalGb)
                                put("ram_available_gb", known.ramAvailableGb)
                                put("gpu_name", known.gpuName)
                                put("gpu_vram_total_gb", known.gpuVramTotalGb)
                                put("gpu_vram_free_gb", known.gpuVramFreeGb)
                                put("gpu_utilization_percent", known.gpuUtilizationPercent)
                                put("gpu_temperature_c", known.gpuTemperatureC)
                                put("active_task_count", known.activeTaskCount)
                                put("runtime_version", known.runtimeVersion)
                                put("brain_backend", known.brainBackend)
                                put("brain_model", known.brainModel)
                                put("brain_ready", known.brainReady)
                                put("brain_loaded", known.brainLoaded)
                                put("brain_loaded_model", known.brainLoadedModel)
                                put("brain_tokens_per_second", known.brainTokensPerSecond)
                                put("brain_last_duration_ms", known.brainLastDurationMs)
                                put("capabilities", JSONArray(known.capabilities))
                                put(
                                    "routes",
                                    JSONArray().apply {
                                        known.routes.forEach { route ->
                                            put(
                                                JSONObject().apply {
                                                    put("kind", route.kind.name)
                                                    put("host", route.host)
                                                    put("port", route.port)
                                                    put("status", route.status.name)
                                                    put("latency_ms", route.latencyMs ?: -1L)
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            )
            put(
                "memories",
                JSONArray().apply {
                    memories.forEach { memory ->
                        put(
                            JSONObject().apply {
                                put("id", memory.id)
                                put("type", memory.type)
                                put("content", memory.content.take(1_500))
                                put("confidence", memory.confidence)
                            }
                        )
                    }
                }
            )
        }.toString()

        val response = try {
            nodeManager.executeTask(
                nodeId = node.nodeId,
                request = admissionProbe.copy(payload = payload)
            )
        } finally {
            admissionController.release(lease)
        }

        val json = JSONObject(response.output)
        val text = json.optString("text").trim()
        if (text.isBlank()) {
            error("Le backend génératif a renvoyé une réponse vide.")
        }

        return BrainResult(
            text = text,
            backendId = info.id,
            backendDisplayName = info.displayName,
            model = json.optString("model")
        )
    }

    private fun compatibleNodes(
        nodes: List<GenesisNode>
    ): List<GenesisNode> =
        nodes.filter {
            it.kind != NodeKind.PHONE &&
                it.status == NodeStatus.ONLINE &&
                "task_execution_v3" in it.capabilities &&
                "local_brain" in it.capabilities &&
                "brain_chat" in it.capabilities
        }
}
