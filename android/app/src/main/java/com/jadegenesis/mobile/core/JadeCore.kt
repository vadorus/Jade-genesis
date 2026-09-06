package com.jadegenesis.mobile.core

import android.content.Context
import android.util.Base64
import com.jadegenesis.mobile.brain.BrainRouter
import com.jadegenesis.mobile.brain.LocalPCBrain
import com.jadegenesis.mobile.brain.PrototypeBrain
import com.jadegenesis.mobile.cognitive.CognitiveCore
import com.jadegenesis.mobile.cognitive.CognitiveLedger
import com.jadegenesis.mobile.cognitive.LearningEngine
import com.jadegenesis.mobile.cognitive.VisualLearningStore
import com.jadegenesis.mobile.device.DeviceProfiler
import com.jadegenesis.mobile.diagnostics.AdminGate
import com.jadegenesis.mobile.diagnostics.DiagnosticLogger
import com.jadegenesis.mobile.identity.IdentityManager
import com.jadegenesis.mobile.memory.JadeDatabase
import com.jadegenesis.mobile.memory.MemoryLifecycleAnalysis
import com.jadegenesis.mobile.memory.MemoryLifecycleManager
import com.jadegenesis.mobile.memory.MemoryStore
import com.jadegenesis.mobile.mesh.ComputeMesh
import com.jadegenesis.mobile.model.BrainContext
import com.jadegenesis.mobile.model.BrainInfo
import com.jadegenesis.mobile.model.CognitiveTraceEvent
import com.jadegenesis.mobile.model.DeviceProfile
import com.jadegenesis.mobile.model.DiagnosticLevel
import com.jadegenesis.mobile.model.DiagnosticLogEntry
import com.jadegenesis.mobile.model.DistributedTaskResult
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.JadeIdentity
import com.jadegenesis.mobile.model.LearningCandidate
import com.jadegenesis.mobile.model.MemorySnapshot
import com.jadegenesis.mobile.model.MemoryType
import com.jadegenesis.mobile.model.MeshProbeSummary
import com.jadegenesis.mobile.model.QueuedTaskSnapshot
import com.jadegenesis.mobile.model.RuntimeNodeSnapshot
import com.jadegenesis.mobile.model.SelfModel
import com.jadegenesis.mobile.model.TaskExecutionLocation
import com.jadegenesis.mobile.model.TaskStatus
import com.jadegenesis.mobile.model.TaskWorkload
import com.jadegenesis.mobile.model.ToolCandidateSnapshot
import com.jadegenesis.mobile.node.NodeManager
import com.jadegenesis.mobile.resource.ResourceGovernor
import com.jadegenesis.mobile.research.ResearchEngine
import com.jadegenesis.mobile.screen.ScreenObserverRepository
import com.jadegenesis.mobile.runtime.RuntimeManager
import com.jadegenesis.mobile.selfmodel.SelfModelBuilder
import com.jadegenesis.mobile.task.TaskLedger
import com.jadegenesis.mobile.task.TaskQueue
import com.jadegenesis.mobile.task.TaskRouter
import com.jadegenesis.mobile.tools.ToolLab
import com.jadegenesis.mobile.tools.ToolObservation
import com.jadegenesis.mobile.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

class JadeCore(context: Context) {
    private val appContext = context.applicationContext
    private val identityManager = IdentityManager(appContext)
    private val profiler = DeviceProfiler(appContext)
    private val resourceGovernor = ResourceGovernor()
    private val tools = ToolRegistry(profiler)
    private val memory = MemoryStore(JadeDatabase.get(appContext).memoryDao())
    private val memoryLifecycle = MemoryLifecycleManager(appContext)
    private val selfModelBuilder = SelfModelBuilder()
    private val diagnostics = DiagnosticLogger(appContext)
    private val adminGate = AdminGate(appContext)
    private val cognitiveLedger = CognitiveLedger(appContext)
    private val nodeManager = NodeManager(appContext, profiler, diagnostics)
    private val brainRouter = BrainRouter(
        listOf(
            LocalPCBrain(nodeManager),
            PrototypeBrain()
        )
    )
    private val cognitiveCore = CognitiveCore(
        brainRouter = brainRouter,
        ledger = cognitiveLedger,
        logger = diagnostics
    )
    private val taskLedger = TaskLedger(appContext)
    private val taskQueue = TaskQueue(appContext)
    private val taskRouter = TaskRouter(
        nodeManager = nodeManager,
        ledger = taskLedger,
        queue = taskQueue
    )
    private val computeMesh = ComputeMesh(nodeManager, diagnostics)
    private val learningEngine = LearningEngine()
    private val visualLearning = VisualLearningStore(appContext)
    private val researchEngine = ResearchEngine()
    private val runtimeManager = RuntimeManager()
    private val screenObserver = ScreenObserverRepository(appContext)
    private val toolLab = ToolLab(appContext)

    private var identity: JadeIdentity? = null

    suspend fun initialize(): SelfModel {
        identity = identityManager.loadOrCreate()
        diagnostics.log(
            DiagnosticLevel.INFO,
            "jade_initialize",
            "Jade Genesis 0.1.4 initialisée.",
            mapOf("jade_id" to identity?.jadeId)
        )
        return selfModel()
    }

    fun toolNames(): List<String> = tools.names()

    fun brainInfos(): List<BrainInfo> = brainRouter.allInfos()

    suspend fun selfModel(): SelfModel {
        val activeIdentity = activeIdentity()
        val device = profiler.capture()
        val resourceBudget = resourceGovernor.evaluate(device)
        val nodes = nodeManager.nodes(
            device = device,
            refreshRemote = false
        )
        val activeBrain = brainRouter.activeInfo(
            resourceBudget = resourceBudget,
            nodes = nodes
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

    suspend fun registerNode(
        host: String,
        port: Int,
        token: String
    ): GenesisNode = nodeManager.registerNode(host, port, token)

    suspend fun registerPcNode(
        host: String,
        port: Int,
        token: String
    ): GenesisNode = registerNode(host, port, token)

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

    suspend fun runDistributedTextAnalysis(text: String): DistributedTaskResult {
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
        val recentMemories = memory.latest(64)
        val sourceMemories = memoryLifecycle.sourceMemories(recentMemories)
        val lifecycle = memoryLifecycle.analyze(sourceMemories)

        if (!lifecycle.needsConsolidation) {
            return lifecycleNoOpResult(
                analysis = lifecycle,
                device = device
            )
        }

        val result = taskRouter.runMemoryConsolidation(
            identityId = activeIdentity.jadeId,
            memories = sourceMemories,
            device = device,
            budget = resourceBudget
        )

        if (result.success) {
            val json = JSONObject(result.output)
            val summary = json.optString("summary").trim().ifBlank {
                "Consolidation terminée sans résumé textuel."
            }
            val duplicateGroups = json.optInt("duplicate_groups", 0)
            val contradictions = json.optInt("potential_contradictions", 0)

            val knowledge = memory.remember(
                type = MemoryType.KNOWLEDGE,
                content = buildString {
                    append("Consolidation mémoire : ")
                    append(memoryLifecycle.lifecycleSummary(lifecycle))
                    append(" ")
                    append(summary)
                    append(
                        " Doublons groupés : $duplicateGroups. " +
                            "Contradictions potentielles : $contradictions. "
                    )
                    append(
                        "Les mémoires sources restent intactes ; " +
                            "OBSOLETE_CANDIDATE n'entraîne jamais une suppression automatique."
                    )
                },
                source = "JADE_CONSOLIDATION_0.1.2",
                confidence = 0.88,
                originNode = result.executedNodeId
            )

            memoryLifecycle.markConsolidated(
                analysis = lifecycle,
                knowledgeId = knowledge.id,
                resultSha256 = sha256(result.output)
            )
        }

        return result
    }

    suspend fun runComputeMeshProbe(): MeshProbeSummary =
        computeMesh.runParallelProbe(profiler.capture())

    fun recentTaskHistory(limit: Int = 16): List<DistributedTaskResult> =
        taskLedger.recent(limit)

    fun recentTaskQueue(limit: Int = 16): List<QueuedTaskSnapshot> =
        taskQueue.recent(limit)

    fun pendingTaskCount(): Int = taskQueue.pendingCount()

    fun cognitiveTrace(limit: Int = 50): List<CognitiveTraceEvent> =
        cognitiveLedger.recent(limit)

    fun learningCandidates(limit: Int = 8): List<LearningCandidate> =
        (
            visualLearning.candidates(limit) +
                learningEngine.candidates(taskLedger.recent(50), limit)
            )
            .distinctBy { it.id }
            .sortedByDescending { it.confidence }
            .take(limit)

    fun recentDiagnostics(limit: Int = 120): List<DiagnosticLogEntry> =
        diagnostics.recent(limit)

    fun runtimeSnapshots(nodes: List<GenesisNode>): List<RuntimeNodeSnapshot> =
        runtimeManager.snapshots(nodes)

    fun toolCandidates(limit: Int = 20): List<ToolCandidateSnapshot> =
        toolLab.list(limit)

    suspend fun proposeToolCandidate(idea: String): ToolCandidateSnapshot {
        val clean = idea.trim()
        require(clean.isNotBlank()) { "Décris l'outil que Jade doit concevoir." }
        require(clean.length <= 4_000) { "La description de l'outil est trop longue." }

        runCatching { nodeManager.refreshRemoteNodes() }
        val self = selfModel()
        val result = brainRouter.think(
            BrainContext(
                userInput = clean,
                selfModel = self,
                memories = memory.latest(8),
                tools = tools.describe(),
                operation = "tool_build"
            )
        )
        val candidate = toolLab.saveFromBrain(
            raw = result.text,
            generator = result.model.ifBlank { result.backendDisplayName }
        )
        diagnostics.log(
            DiagnosticLevel.INFO,
            "tool_candidate_created",
            "Tool Lab a créé un candidat non activé.",
            mapOf(
                "tool_id" to candidate.id,
                "tool_name" to candidate.name,
                "status" to candidate.status,
                "sha256" to candidate.sourceSha256
            )
        )
        return candidate
    }

    suspend fun analyzeLatestPhoneScreen(captureRequestedAt: Long = 0L): String {
        val frame = if (captureRequestedAt > 0L) {
            screenObserver.awaitFrameAfter(captureRequestedAt)
        } else {
            screenObserver.latestFrame()
                ?: error("Aucune image Pixel/partagée récente n'est prête à analyser.")
        }
        val device = profiler.capture()
        val nodes = nodeManager.nodes(device = device, refreshRemote = true)
        val target = nodes
            .filter { node ->
                node.kind != com.jadegenesis.mobile.model.NodeKind.PHONE &&
                    node.status == com.jadegenesis.mobile.model.NodeStatus.ONLINE &&
                    "task_execution_v3" in node.capabilities &&
                    "vision_analyze" in node.capabilities
            }
            .maxWithOrNull(
                compareBy<GenesisNode> { it.ramAvailableGb }
                    .thenBy { it.cpuCores }
            )
            ?: error(
                "Aucun nœud en ligne ne dispose d'un modèle vision compatible. " +
                    "L'image reste enregistrée localement pour une analyse ultérieure."
            )

        val focus = frame.focusInstruction.trim().take(1_200)
        val prompt = buildString {
            append(
                "IMPORTANT : réponds exclusivement en français. Analyse uniquement cette image précise. " +
                    "Décris seulement les éléments réellement visibles et lisibles. N'infère aucune fonction, donnée cachée, " +
                    "intention ou signification à partir d'une icône ou d'un élément ambigu. "
            )
            if (focus.isNotBlank()) {
                append(
                    "PRIORITÉ UTILISATEUR : $focus. Concentre d'abord ton analyse sur cette demande et sur la zone déjà cadrée. " +
                        "N'élargis à d'autres éléments que s'ils sont indispensables pour répondre. "
                )
            }
            append(
                "Si tu n'es pas sûr, écris explicitement 'incertain'. Structure la réponse en trois sections courtes : " +
                    "Visible, Incertain, Conseil. Pour chaque observation importante, indique une confiance élevée, moyenne ou faible. " +
                    "N'invente aucun texte absent de l'image."
            )
        }

        val payload = JSONObject().apply {
            put("prompt", prompt)
            put("image_b64", Base64.encodeToString(frame.bytes, Base64.NO_WRAP))
            put("image_sha256", frame.sha256)
            put("source", frame.source)
        }.toString()

        val response = nodeManager.executeTask(
            nodeId = target.nodeId,
            request = com.jadegenesis.mobile.model.DistributedTaskRequest(
                taskId = "vision-${UUID.randomUUID()}",
                taskKind = "vision_analyze",
                payload = payload,
                requiredCapability = "vision_analyze",
                workload = TaskWorkload.HEAVY,
                createdAt = System.currentTimeMillis()
            )
        )
        val json = JSONObject(response.output)
        val answer = json.optString("text").trim()
        if (answer.isBlank()) error("Le backend vision a renvoyé une réponse vide.")

        val wasKnown = visualLearning.hasObservation(frame.sha256)
        visualLearning.recordVision(
            imageSha256 = frame.sha256,
            source = frame.source,
            focusInstruction = focus,
            visionText = answer
        )
        if (!wasKnown) {
            memory.remember(
                type = MemoryType.OBSERVATION,
                content = buildString {
                    if (focus.isNotBlank()) appendLine("Cible utilisateur : $focus")
                    append(answer)
                },
                source = "VISION_TARGETED:${frame.source}:${frame.sha256.take(16)}",
                confidence = 0.68,
                originNode = response.nodeId
            )
        }

        diagnostics.log(
            DiagnosticLevel.INFO,
            "screen_targeted_analyzed",
            "Image ciblée analysée par un nœud vision.",
            mapOf(
                "node_id" to response.nodeId,
                "image_sha256" to frame.sha256,
                "image_source" to frame.source,
                "focus_defined" to focus.isNotBlank(),
                "duration_ms" to response.durationMs
            )
        )
        return answer
    }

    suspend fun analyzePcScreen(): String {
        val device = profiler.capture()
        val nodes = nodeManager.nodes(device = device, refreshRemote = true)
        val target = nodes
            .filter { node ->
                node.kind == com.jadegenesis.mobile.model.NodeKind.PC &&
                    node.status == com.jadegenesis.mobile.model.NodeStatus.ONLINE &&
                    "task_execution_v3" in node.capabilities &&
                    "screen_analyze" in node.capabilities
            }
            .maxWithOrNull(
                compareBy<GenesisNode> { it.ramAvailableGb }
                    .thenBy { it.cpuCores }
            )
            ?: error(
                "Aucun PC en ligne n'annonce screen_analyze. " +
                    "Le runtime PC 0.1.1 avec un modèle vision Ollama est requis."
            )

        val payload = JSONObject().apply {
            put(
                "prompt",
                "IMPORTANT : réponds exclusivement en français. Observe uniquement l'image actuelle de l'écran du PC. " +
                    "Décris les éléments réellement visibles et lisibles, puis signale les erreurs seulement si elles sont explicitement affichées. " +
                    "N'infère aucune fonction, donnée cachée ou intention à partir d'une icône ou d'un élément ambigu. " +
                    "Si tu n'es pas sûr, écris explicitement 'incertain'. Structure la réponse en trois sections courtes : " +
                    "Visible, Incertain, Conseil. Pour chaque observation importante, indique une confiance élevée, moyenne ou faible. " +
                    "N'invente aucun texte absent de l'image."
            )
            put("source", "pc_screen")
        }.toString()

        val response = nodeManager.executeTask(
            nodeId = target.nodeId,
            request = com.jadegenesis.mobile.model.DistributedTaskRequest(
                taskId = "screen-${UUID.randomUUID()}",
                taskKind = "screen_analyze",
                payload = payload,
                requiredCapability = "screen_analyze",
                workload = TaskWorkload.HEAVY,
                createdAt = System.currentTimeMillis()
            )
        )
        val json = JSONObject(response.output)
        val answer = json.optString("text").trim()
        if (answer.isBlank()) error("Le backend Screen Observer a renvoyé une réponse vide.")

        val pcImageSha = json.optString("image_sha256").trim().ifBlank {
            sha256("pc-screen:${System.currentTimeMillis()}:$answer")
        }
        val wasKnown = visualLearning.hasObservation(pcImageSha)
        visualLearning.recordVision(
            imageSha256 = pcImageSha,
            source = "pc_screen",
            focusInstruction = "",
            visionText = answer
        )
        if (!wasKnown) {
            memory.remember(
                type = MemoryType.OBSERVATION,
                content = answer,
                source = "VISION_PC:${pcImageSha.take(16)}",
                confidence = 0.68,
                originNode = response.nodeId
            )
        }

        diagnostics.log(
            DiagnosticLevel.INFO,
            "screen_pc_analyzed",
            "Écran PC analysé par le runtime local.",
            mapOf(
                "node_id" to response.nodeId,
                "duration_ms" to response.durationMs
            )
        )
        return answer
    }

    suspend fun deepResearchLastVisualObservation(): String {
        val observation = visualLearning.last()
            ?: error("Aucune observation visuelle récente à approfondir.")

        val report = researchEngine.investigate(
            observation = observation.visionText,
            focusInstruction = observation.focusInstruction
        )
        val synthesis = if (report.evidence.isNotEmpty()) {
            val self = selfModel()
            val prompt = buildString {
                appendLine("Tu es le module de synthèse de Jade Genesis.")
                appendLine("Réponds exclusivement en français.")
                appendLine("Ne prétends jamais qu'une information est confirmée si les sources ne la confirment pas.")
                appendLine("Ne recopie pas la liste des sources : elle sera affichée séparément.")
                appendLine("Sépare : Faits confirmés, Probable, Non vérifié, Conclusion.")
                if (observation.focusInstruction.isNotBlank()) {
                    appendLine("Question/cible de l'utilisateur : ${observation.focusInstruction.take(1_200)}")
                }
                appendLine("Observation visuelle locale :")
                appendLine(observation.visionText.take(6_000))
                appendLine()
                appendLine("Données publiques récupérées :")
                appendLine(report.renderForModel())
                appendLine()
                appendLine("Cite [1], [2]... seulement quand la source soutient réellement le fait.")
            }
            val result = brainRouter.think(
                BrainContext(
                    userInput = prompt,
                    selfModel = self,
                    memories = memory.latest(8),
                    tools = tools.describe(),
                    operation = "visual_research_synthesis"
                )
            )
            result.text.trim().ifBlank {
                "Des sources ont été trouvées mais la synthèse générative n'a pas produit de texte exploitable."
            }
        } else {
            "Je n'ai pas trouvé assez de données publiques pour confirmer cette observation avec les fournisseurs disponibles."
        }

        if (report.evidence.isNotEmpty()) {
            memory.remember(
                type = MemoryType.HYPOTHESIS,
                content = synthesis,
                source = "RESEARCH_CANDIDATE_V2:${report.providerSummary()}",
                confidence = report.confidence,
                originNode = profiler.nodeId()
            )
        }

        visualLearning.recordResearch(
            imageSha256 = observation.imageSha256,
            query = report.query,
            researchText = synthesis,
            evidenceCount = report.evidence.size,
            confidence = report.confidence
        )

        diagnostics.log(
            if (report.evidence.isNotEmpty()) DiagnosticLevel.INFO else DiagnosticLevel.WARN,
            "visual_research_v2_completed",
            if (report.evidence.isNotEmpty()) {
                "Recherche ciblée terminée ; résultat conservé comme hypothèse candidate."
            } else {
                "Recherche ciblée terminée sans corroboration suffisante."
            },
            mapOf(
                "image_sha256" to observation.imageSha256,
                "source" to observation.source,
                "focus_defined" to observation.focusInstruction.isNotBlank(),
                "research_query" to report.query.take(240),
                "evidence_count" to report.evidence.size,
                "provider_count" to report.providerCount,
                "providers" to report.providerSummary(),
                "confidence" to report.confidence
            )
        )

        return buildString {
            appendLine("ANALYSE APPROFONDIE")
            if (observation.focusInstruction.isNotBlank()) {
                appendLine("Cible : ${observation.focusInstruction}")
                appendLine()
            }
            appendLine(synthesis)
            appendLine()
            appendLine("SOURCES CONSULTÉES")
            appendLine(report.renderSourcesForUser())
            appendLine()
            appendLine("Statut mémoire : HYPOTHÈSE candidate — pas encore connaissance stable.")
            append(
                "Confidentialité : l'image n'a pas été envoyée aux sources Internet publiques ; " +
                    "seules des requêtes texte ciblées et filtrées ont été utilisées."
            )
        }.trim()
    }

    fun isAdminConfigured(): Boolean = adminGate.isConfigured()

    fun isAdminUnlocked(): Boolean = adminGate.isUnlocked()

    fun configureAdminPin(pin: String) {
        adminGate.configure(pin)
        diagnostics.log(
            DiagnosticLevel.INFO,
            "admin_configured",
            "Mode Admin configuré."
        )
    }

    fun unlockAdmin(pin: String): Boolean {
        val success = adminGate.unlock(pin)
        diagnostics.log(
            if (success) DiagnosticLevel.INFO else DiagnosticLevel.WARN,
            "admin_unlock",
            if (success) "Mode Admin déverrouillé." else "Échec de déverrouillage Admin."
        )
        return success
    }

    fun lockAdmin() {
        adminGate.lock()
        diagnostics.log(DiagnosticLevel.INFO, "admin_lock", "Mode Admin verrouillé.")
    }

    fun setDebugEnabled(enabled: Boolean) {
        require(adminGate.isUnlocked()) {
            "Le mode Admin doit être déverrouillé."
        }
        diagnostics.setDebugEnabled(enabled)
    }

    fun isDebugEnabled(): Boolean = diagnostics.isDebugEnabled()

    suspend fun generateDiagnosticBundle(): String {
        require(adminGate.isUnlocked()) {
            "Le mode Admin doit être déverrouillé."
        }
        val self = selfModel()
        val summary = JSONObject().apply {
            put("generated_at", System.currentTimeMillis())
            put("jade_id", self.identity.jadeId)
            put("version", self.identity.version)
            put("interface_node", self.nodeId)
            put("resource_mode", self.resourceBudget.mode.name)
            put("active_brain", self.activeBrain.displayName)
            put(
                "nodes",
                JSONArray().apply {
                    self.knownNodes.forEach { node ->
                        put(
                            JSONObject().apply {
                                put("node_id", node.nodeId)
                                put("name", node.name)
                                put("kind", node.kind.name)
                                put("status", node.status.name)
                                put("runtime_version", node.runtimeVersion)
                                put("runtime_channel", node.runtimeChannel)
                                put("capabilities", JSONArray(node.capabilities))
                                put(
                                    "routes",
                                    JSONArray().apply {
                                        node.routes.forEach { route ->
                                            put(
                                                JSONObject().apply {
                                                    put("kind", route.kind.name)
                                                    put("host", route.host)
                                                    put("port", route.port)
                                                    put("status", route.status.name)
                                                    put("latency_ms", route.latencyMs ?: -1L)
                                                    put("last_error", route.lastError ?: "")
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
                "recent_tasks",
                JSONArray().apply {
                    taskLedger.recent(20).forEach { task ->
                        put(
                            JSONObject().apply {
                                put("task_id", task.taskId)
                                put("task_kind", task.taskKind)
                                put("success", task.success)
                                put("executed_node", task.executedNodeName)
                                put("duration_ms", task.durationMs)
                                put("fallback", task.fallbackUsed)
                            }
                        )
                    }
                }
            )
        }.toString(2)
        return diagnostics.exportBundle(summary)
    }

    suspend fun rememberUserFact(content: String) {
        memory.rememberUserFact(content, profiler.nodeId())
        diagnostics.log(
            DiagnosticLevel.INFO,
            "memory_user_fact",
            "Un fait utilisateur a été enregistré sans journaliser son contenu."
        )
    }

    suspend fun latestMemories(limit: Int = 30): List<MemorySnapshot> =
        memory.latest(limit)

    suspend fun memoryCount(): Int = memory.count()

    suspend fun ask(userInput: String): String {
        runCatching { nodeManager.refreshRemoteNodes() }
            .onFailure { error ->
                diagnostics.log(
                    DiagnosticLevel.WARN,
                    "pre_chat_node_refresh_failed",
                    "Rafraîchissement des nœuds incomplet avant conversation.",
                    mapOf("error" to error.message)
                )
            }

        val self = selfModel()
        val context = BrainContext(
            userInput = userInput,
            selfModel = self,
            memories = memory.latest(14),
            tools = tools.describe()
        )

        val first = cognitiveCore.think(context)
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

    private fun lifecycleNoOpResult(
        analysis: MemoryLifecycleAnalysis,
        device: DeviceProfile
    ): DistributedTaskResult {
        val now = System.currentTimeMillis()
        val local = nodeManager.localNode(device)
        val output = JSONObject().apply {
            put("skipped", true)
            put("reason", analysis.reason)
            put("lifecycle_summary", memoryLifecycle.lifecycleSummary(analysis))
            put("source_fingerprint", analysis.sourceFingerprint)
            put("source_count", analysis.sourceCount)
            put("new_count", analysis.newCount)
            put("confirmed_count", analysis.confirmedCount)
            put("contradiction_count", analysis.contradictionCount)
            put("obsolete_candidate_count", analysis.obsoleteCandidateCount)
            put("last_consolidated_at", analysis.lastConsolidatedAt)
        }.toString()

        return DistributedTaskResult(
            taskId = "lifecycle-${UUID.randomUUID()}",
            taskKind = "memory_lifecycle_noop",
            requestedNodeId = null,
            requestedNodeName = null,
            executedNodeId = local.nodeId,
            executedNodeName = local.name,
            executionLocation = TaskExecutionLocation.LOCAL,
            status = TaskStatus.COMPLETED,
            success = true,
            output = output,
            durationMs = 0L,
            fallbackUsed = false,
            fallbackReason = null,
            routeReason = analysis.reason,
            attempts = emptyList(),
            startedAt = now,
            completedAt = now
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }

    private suspend fun activeIdentity(): JadeIdentity =
        identity ?: identityManager.loadOrCreate().also { identity = it }
}
