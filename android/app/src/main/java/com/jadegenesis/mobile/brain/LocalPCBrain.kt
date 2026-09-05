package com.jadegenesis.mobile.brain

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
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class LocalPCBrain(
    private val nodeManager: NodeManager
) : BrainBackend {

    override val info = BrainInfo(
        id = "local-pc-brain-0.0.8",
        displayName = "Local PC Brain — Ollama",
        backendType = BrainBackendType.LOCAL_NODE,
        location = "pc",
        resourceClass = BrainResourceClass.HEAVY,
        requiresNetwork = true,
        paidApi = false,
        available = true,
        priority = 100,
        details =
            "Backend génératif local via le Node Runtime PC et Ollama. " +
                "Aucune API payante n'est requise."
    )

    override fun availableFor(nodes: List<GenesisNode>): Boolean =
        compatibleNodes(nodes).isNotEmpty()

    override suspend fun think(context: BrainContext): BrainResult {
        val compatible = compatibleNodes(context.selfModel.knownNodes)
        val preferredId = context.selfModel.preferredComputeNodeId
        val node = compatible
            .sortedWith(
                compareByDescending<GenesisNode> {
                    it.nodeId == preferredId
                }.thenByDescending {
                    it.ramAvailableGb
                }.thenByDescending {
                    it.cpuCores
                }
            )
            .firstOrNull()
            ?: error(
                "Aucun PC en ligne n'annonce la capacité brain_chat."
            )

        val memories = context.memories
            .sortedBy {
                if (it.source.startsWith("JADE_CONSOLIDATION_")) 1 else 0
            }
            .take(8)

        val payload = JSONObject().apply {
            put(
                "identity",
                JSONObject().apply {
                    put("jade_id", context.selfModel.identity.jadeId)
                    put("name", context.selfModel.identity.name)
                    put("version", context.selfModel.identity.version)
                }
            )
            put("user_input", context.userInput.take(8_000))
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
                        "preferred_compute_node",
                        context.selfModel.knownNodes
                            .firstOrNull {
                                it.nodeId == preferredId
                            }
                            ?.name
                            ?: "aucun"
                    )
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

        val response = nodeManager.executeTask(
            nodeId = node.nodeId,
            request = DistributedTaskRequest(
                taskId = "brain-${UUID.randomUUID()}",
                taskKind = "brain_chat",
                payload = payload,
                requiredCapability = "brain_chat",
                workload = TaskWorkload.HEAVY,
                createdAt = System.currentTimeMillis()
            )
        )

        val json = JSONObject(response.output)
        val text = json.optString("text").trim()
        if (text.isBlank()) {
            error("Le backend Ollama a renvoyé une réponse vide.")
        }

        return BrainResult(text = text)
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
