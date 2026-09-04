package com.jadegenesis.mobile.core

import android.content.Context
import com.jadegenesis.mobile.brain.BrainBackend
import com.jadegenesis.mobile.brain.BrainRouter
import com.jadegenesis.mobile.brain.PrototypeBrain
import com.jadegenesis.mobile.device.DeviceProfiler
import com.jadegenesis.mobile.identity.IdentityManager
import com.jadegenesis.mobile.memory.JadeDatabase
import com.jadegenesis.mobile.memory.MemoryStore
import com.jadegenesis.mobile.model.BrainContext
import com.jadegenesis.mobile.model.BrainInfo
import com.jadegenesis.mobile.model.DistributedTaskResult
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.JadeIdentity
import com.jadegenesis.mobile.model.MemorySnapshot
import com.jadegenesis.mobile.model.MemoryType
import com.jadegenesis.mobile.model.QueuedTaskSnapshot
import com.jadegenesis.mobile.model.SelfModel
import com.jadegenesis.mobile.node.NodeManager
import com.jadegenesis.mobile.resource.ResourceGovernor
import com.jadegenesis.mobile.selfmodel.SelfModelBuilder
import com.jadegenesis.mobile.task.TaskLedger
import com.jadegenesis.mobile.task.TaskQueue
import com.jadegenesis.mobile.task.TaskRouter
import com.jadegenesis.mobile.tools.ToolObservation
import com.jadegenesis.mobile.tools.ToolRegistry
import org.json.JSONObject

class JadeCore(
    context: Context,
    brainBackends: List<BrainBackend> = listOf(PrototypeBrain())
) {
    private val appContext = context.applicationContext
    private val identityManager = IdentityManager(appContext)
    private val profiler = DeviceProfiler(appContext)
    private val resourceGovernor = ResourceGovernor()
    private val tools = ToolRegistry(profiler)
    private val memory = MemoryStore(
        JadeDatabase.get(appContext).memoryDao()
    )
    private val selfModelBuilder = SelfModelBuilder()
    private val brainRouter = BrainRouter(brainBackends)
    private val nodeManager = NodeManager(appContext, profiler)
    private val taskLedger = TaskLedger(appContext)
    private val taskQueue = TaskQueue(appContext)
    private val taskRouter = TaskRouter(
        nodeManager = nodeManager,
        ledger = taskLedger,
        queue = taskQueue
    )

    private var identity: JadeIdentity? = null

    suspend fun initialize(): SelfModel {
        identity = identityManager.loadOrCreate()
        return selfModel()
    }

    fun toolNames(): List<String> = tools.names()

    fun brainInfos(): List<BrainInfo> = brainRouter.allInfos()

    suspend fun selfModel(): SelfModel {
        val activeIdentity = activeIdentity()
        val device = profiler.capture()
        val resourceBudget = resourceGovernor.evaluate(device)
        val activeBrain = brainRouter.activeInfo(resourceBudget)
        val nodes = nodeManager.nodes(
            device = device,
            refreshRemote = false
        )
        val preferredNode = nodeManager.preferredComputeNode(
            nodes = nodes,
            budget = resourceBudget
        )

        return selfModelBuilder.build(
            identity = activeIdentity,
            nodeId = profiler.nodeId(),
            device = device,
            resourceBudget = resourceBudget,
            activeBrain = activeBrain,
            knownNodes = nodes,
            preferredComputeNodeId = preferredNode?.nodeId,
            toolNames = tools.names()
        )
    }

    suspend fun registerPcNode(
        host: String,
        port: Int,
        token: String
    ): GenesisNode = nodeManager.registerPcNode(
        host = host,
        port = port,
        token = token
    )

    suspend fun refreshNodes(): SelfModel {
        nodeManager.refreshRemoteNodes()
        return selfModel()
    }

    suspend fun runDistributedProbe(): DistributedTaskResult {
        val activeIdentity = activeIdentity()
        val device = profiler.capture()
        val resourceBudget = resourceGovernor.evaluate(device)

        return taskRouter.runGenesisProbe(
            identityId = activeIdentity.jadeId,
            device = device,
            budget = resourceBudget
        )
    }

    suspend fun runDistributedTextAnalysis(
        text: String
    ): DistributedTaskResult {
        val device = profiler.capture()
        val resourceBudget = resourceGovernor.evaluate(device)

        return taskRouter.runTextAnalysis(
            text = text,
            device = device,
            budget = resourceBudget
        )
    }

    suspend fun runMemoryConsolidation(): DistributedTaskResult {
        val activeIdentity = activeIdentity()
        val device = profiler.capture()
        val resourceBudget = resourceGovernor.evaluate(device)
        val sourceMemories = memory.latest(24)

        val result = taskRouter.runMemoryConsolidation(
            identityId = activeIdentity.jadeId,
            memories = sourceMemories,
            device = device,
            budget = resourceBudget
        )

        if (result.success) {
            val json = JSONObject(result.output)
            val summary = json
                .optString("summary")
                .trim()
                .ifBlank {
                    "Consolidation terminée sans résumé textuel."
                }
            val duplicateGroups = json.optInt("duplicate_groups", 0)
            val contradictions =
                json.optInt("potential_contradictions", 0)

            memory.remember(
                type = MemoryType.KNOWLEDGE,
                content = buildString {
                    append("Consolidation mémoire 0.0.6 : ")
                    append(summary)
                    append(
                        " Doublons groupés : $duplicateGroups. " +
                            "Contradictions potentielles : $contradictions."
                    )
                },
                source = "JADE_CONSOLIDATION_0.0.6",
                confidence = 0.85,
                originNode = result.executedNodeId
            )
        }

        return result
    }

    fun recentTaskHistory(
        limit: Int = 12
    ): List<DistributedTaskResult> =
        taskLedger.recent(limit)

    fun recentTaskQueue(
        limit: Int = 12
    ): List<QueuedTaskSnapshot> =
        taskQueue.recent(limit)

    fun pendingTaskCount(): Int =
        taskQueue.pendingCount()

    suspend fun rememberUserFact(content: String) {
        memory.rememberUserFact(content, profiler.nodeId())
    }

    suspend fun latestMemories(limit: Int = 20): List<MemorySnapshot> =
        memory.latest(limit)

    suspend fun memoryCount(): Int = memory.count()

    suspend fun ask(userInput: String): String {
        val self = selfModel()

        val context = BrainContext(
            userInput = userInput,
            selfModel = self,
            memories = memory.latest(12),
            tools = tools.describe()
        )

        val first = brainRouter.think(context)

        return when (val toolName = first.toolName) {
            null -> first.text

            else -> when (val observation = tools.call(toolName)) {
                is ToolObservation.Device -> {
                    val d = observation.profile
                    val budget = resourceGovernor.evaluate(d)

                    """
                    ${first.text}

                    Appareil : ${d.manufacturer} ${d.model}
                    Android : ${d.androidVersion} (API ${d.sdkInt})
                    SoC : ${d.socManufacturer} ${d.socModel}
                    CPU : ${d.cpuCores} cœurs logiques
                    Architecture : ${d.abis.joinToString()}
                    RAM : ${d.ramTotalGb} Go (${d.ramAvailableGb} Go disponibles)
                    Mémoire Jade : ${d.processHeapUsedMb} Mo / ${d.processHeapMaxMb} Mo
                    Stockage : ${d.storageFreeGb} Go libres / ${d.storageTotalGb} Go
                    Batterie : ${d.batteryPercent}%${if (d.charging) " — en charge" else ""}
                    Économie d'énergie : ${if (d.powerSaveMode) "active" else "inactive"}
                    Thermique : ${d.thermalStatus}
                    Resource Governor : ${budget.mode}
                    Budget de travail recommandé : ${budget.recommendedWorkingSetMb} Mo
                    """.trimIndent()
                }
            }
        }
    }

    private suspend fun activeIdentity(): JadeIdentity =
        identity ?: identityManager
            .loadOrCreate()
            .also {
                identity = it
            }
}
