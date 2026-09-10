package com.jadegenesis.mobile.brain

import com.jadegenesis.mobile.config.JadeConfigRuntime
import com.jadegenesis.mobile.eval.RuntimeEvalRuntime
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

    private data class RankedCandidate(
        val node: GenesisNode,
        val priorScore: Double,
        val evidence: AdaptiveBrainEvidence
    ) {
        val score: Double
            get() = priorScore + evidence.adjustment
    }

    override val info = BrainInfo(
        id = "distributed-local-brain-0.1.16",
        displayName = "Adaptive Distributed Cognitive Brain",
        backendType = BrainBackendType.LOCAL_NODE,
        location = "compute-mesh",
        resourceClass = BrainResourceClass.HEAVY,
        requiresNetwork = true,
        paidApi = false,
        available = true,
        priority = 110,
        details =
            "Backend génératif distribué avec profils FAST/GENERAL/REASONING/CODE/CRITIC. " +
                "Le prior matériel est corrigé par Runtime Eval et, après assez de preuves, " +
                "par les outcomes explicites réellement rattachés aux réponses précédentes."
    )

    override fun availableFor(nodes: List<GenesisNode>): Boolean =
        compatibleNodes(nodes).isNotEmpty()

    override suspend fun think(context: BrainContext): BrainResult {
        val brainPlan = CognitiveBrainPolicy.plan(context)
        val compatible = compatibleNodes(context.selfModel.knownNodes)
        val preferredId = context.selfModel.preferredComputeNodeId
        val routing = JadeConfigRuntime.current().validated().routing
        val ranked = compatible
            .map { candidate ->
                RankedCandidate(
                    node = candidate,
                    priorScore = NodeResourceScorer.generativeScore(
                        node = candidate,
                        routing = routing,
                        preferredNodeId = preferredId
                    ),
                    evidence = AdaptiveBrainRouting.evidence(
                        nodeId = candidate.nodeId,
                        profile = brainPlan.profile,
                        routing = routing
                    )
                )
            }
            .sortedByDescending { it.score }

        if (ranked.isEmpty()) {
            error("Aucun nœud génératif en ligne n'annonce brain_chat.")
        }

        // MemoryStore.latestForContext() already prepares a bounded cognitive
        // mix with reserved USER and JADE_CONSOLIDATION slots. Do not reorder
        // consolidated knowledge behind volatile observations here: the old
        // sort + take(10) could discard exactly the durable knowledge produced
        // by the night consolidation cycle.
        val memories = context.memories.take(10)

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

        var selected: RankedCandidate? = null
        var selectedLease: com.jadegenesis.mobile.resource.ResourceLease? = null
        val admissionFailures = mutableListOf<String>()

        for (candidate in ranked) {
            val admission = admissionController.tryAcquire(
                request = admissionProbe,
                node = candidate.node,
                budget = context.selfModel.resourceBudget
            )
            if (admission.admitted && admission.lease != null) {
                selected = candidate
                selectedLease = admission.lease
                break
            }
            admissionFailures +=
                "${candidate.node.name}: ${admission.action} ${admission.reason}"
        }

        val selectedCandidate = selected ?: error(
            "Aucun nœud génératif n'a obtenu de Resource Lease. " +
                admissionFailures.joinToString(" | ").take(700)
        )
        val node = selectedCandidate.node
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
                "adaptive_routing",
                JSONObject().apply {
                    put("prior_score", selectedCandidate.priorScore)
                    put("posterior_adjustment", selectedCandidate.evidence.adjustment)
                    put("final_score", selectedCandidate.score)
                    put("samples", selectedCandidate.evidence.samples)
                    put("confidence", selectedCandidate.evidence.confidence)
                    put("observed_success_rate", selectedCandidate.evidence.successRate)
                    put("observed_average_duration_ms", selectedCandidate.evidence.averageDurationMs)
                    put("observed_tokens_per_second", selectedCandidate.evidence.averageTokensPerSecond)
                    put("observed_fallback_rate", selectedCandidate.evidence.fallbackRate)
                    put("last_observed_model", selectedCandidate.evidence.lastModel)
                    put("outcome_samples", selectedCandidate.evidence.outcomeSamples)
                    put("outcome_confidence", selectedCandidate.evidence.outcomeConfidence)
                    put("outcome_quality_score", selectedCandidate.evidence.outcomeQualityScore)
                    put("positive_outcomes", selectedCandidate.evidence.positiveOutcomes)
                    put("negative_outcomes", selectedCandidate.evidence.negativeOutcomes)
                    put("corrections", selectedCandidate.evidence.corrections)
                    put("active", selectedCandidate.evidence.active)
                }
            )
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
                    put("resource_mode", context.selfModel.resourceBudget.mode.name)
                    put("preferred_compute_node_id", preferredId ?: "")
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

        val evalStore = RuntimeEvalRuntime.currentOrNull()
        val attemptedAt = System.currentTimeMillis()
        val response = try {
            nodeManager.executeTask(
                nodeId = node.nodeId,
                request = admissionProbe.copy(payload = payload)
            )
        } catch (error: Exception) {
            evalStore?.recordExecution(
                request = admissionProbe,
                node = node,
                success = false,
                durationMs = System.currentTimeMillis() - attemptedAt,
                error = error.message,
                brainProfile = brainPlan.profile.name.lowercase(),
                createdAt = System.currentTimeMillis()
            )
            throw error
        } finally {
            admissionController.release(lease)
        }

        val json = JSONObject(response.output)
        val text = json.optString("text").trim()
        if (text.isBlank()) {
            evalStore?.recordExecution(
                request = admissionProbe,
                node = node,
                success = false,
                durationMs = response.durationMs,
                output = response.output,
                error = "Réponse générative vide",
                brainProfile = brainPlan.profile.name.lowercase(),
                createdAt = System.currentTimeMillis()
            )
            error("Le backend génératif a renvoyé une réponse vide.")
        }

        val evalObservation = evalStore?.recordExecution(
            request = admissionProbe,
            node = node,
            success = true,
            durationMs = response.durationMs,
            output = response.output,
            brainProfile = brainPlan.profile.name.lowercase(),
            createdAt = System.currentTimeMillis()
        )
        val actualModel = json.optString("model").trim()
            .ifBlank { evalObservation?.model.orEmpty() }

        return BrainResult(
            text = text,
            backendId = info.id,
            backendDisplayName = info.displayName,
            model = actualModel,
            nodeId = node.nodeId,
            brainProfile = brainPlan.profile.name.lowercase(),
            runtimeEvalObservationId = evalObservation?.observationId.orEmpty()
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
