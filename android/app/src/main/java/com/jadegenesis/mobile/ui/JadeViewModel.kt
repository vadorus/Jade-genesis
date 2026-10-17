package com.jadegenesis.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jadegenesis.mobile.core.JadeCore
import com.jadegenesis.mobile.diagnostics.DiagnosticLogger
import com.jadegenesis.mobile.model.CognitiveTraceEvent
import com.jadegenesis.mobile.model.DiagnosticLevel
import com.jadegenesis.mobile.model.DiagnosticLogEntry
import com.jadegenesis.mobile.model.DistributedTaskResult
import com.jadegenesis.mobile.model.LearningCandidate
import com.jadegenesis.mobile.model.MemorySnapshot
import com.jadegenesis.mobile.model.MeshProbeSummary
import com.jadegenesis.mobile.model.NodeKind
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.model.QueuedTaskSnapshot
import com.jadegenesis.mobile.model.RuntimeNodeSnapshot
import com.jadegenesis.mobile.model.SelfModel
import com.jadegenesis.mobile.model.ToolCandidateSnapshot
import com.jadegenesis.mobile.node.NetworkRestoreWatcher
import com.jadegenesis.mobile.node.NodeManager
import com.jadegenesis.mobile.node.CoalescingRunner
import com.jadegenesis.mobile.node.OrderedEventWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

private data class RefreshBundle(
    val self: SelfModel,
    val memories: List<MemorySnapshot>,
    val memoryCount: Int,
    val taskHistory: List<DistributedTaskResult>,
    val taskQueue: List<QueuedTaskSnapshot>,
    val pendingTasks: Int,
    val cognitiveTrace: List<CognitiveTraceEvent>,
    val learningCandidates: List<LearningCandidate>,
    val diagnostics: List<DiagnosticLogEntry>,
    val runtimes: List<RuntimeNodeSnapshot>,
    val toolCandidates: List<ToolCandidateSnapshot>,
    val adminConfigured: Boolean,
    val adminUnlocked: Boolean,
    val debugEnabled: Boolean
)

data class JadeUiState(
    val loading: Boolean = true,
    val chatBusy: Boolean = false,
    val nodeBusy: Boolean = false,
    val taskBusy: Boolean = false,
    val selfModel: SelfModel? = null,
    val response: String = "",
    val nodeMessage: String = "",
    val taskMessage: String = "",
    val lastTaskResult: DistributedTaskResult? = null,
    val taskHistory: List<DistributedTaskResult> = emptyList(),
    val taskQueue: List<QueuedTaskSnapshot> = emptyList(),
    val pendingTasks: Int = 0,
    val memories: List<MemorySnapshot> = emptyList(),
    val memoryCount: Int = 0,
    val cognitiveTrace: List<CognitiveTraceEvent> = emptyList(),
    val learningCandidates: List<LearningCandidate> = emptyList(),
    val meshProbe: MeshProbeSummary? = null,
    val diagnostics: List<DiagnosticLogEntry> = emptyList(),
    val runtimes: List<RuntimeNodeSnapshot> = emptyList(),
    val toolCandidates: List<ToolCandidateSnapshot> = emptyList(),
    val screenBusy: Boolean = false,
    val screenArmed: Boolean = false,
    val screenMessage: String = "",
    val researchBusy: Boolean = false,
    val researchMessage: String = "",
    val toolBusy: Boolean = false,
    val toolMessage: String = "",
    val canaryBusy: Boolean = false,
    val canaryMessage: String = "",
    val adminConfigured: Boolean = false,
    val adminUnlocked: Boolean = false,
    val debugEnabled: Boolean = false,
    val diagnosticBundlePath: String? = null,
    val error: String? = null
)

class JadeViewModel(application: Application) : AndroidViewModel(application) {

    private val core = JadeCore(application)
    private val _state = MutableStateFlow(JadeUiState())
    private val canaryGate = SingleFlightGate()
    val state: StateFlow<JadeUiState> = _state.asStateFlow()

    init {
        refresh(probeNodesAfterLoad = true)
    }

    fun refresh(probeNodesAfterLoad: Boolean = false) {
        viewModelScope.launch {
            runCatching { loadBundle(initialize = true) }
                .onSuccess {
                    applyBundle(it, loading = false)
                    // Without this, every node shows UNKNOWN (stale) at app
                    // start until the user taps "Actualiser les nœuds".
                    if (probeNodesAfterLoad) refreshNodes()
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = e.message ?: e.toString()
                    )
                }
        }
    }

    fun refreshNodes() {
        viewModelScope.launch { refreshNodesNow() }
    }

    /** Probes the nodes and updates the UI; returns the number of remote nodes ONLINE, or -1 on failure. */
    private suspend fun refreshNodesNow(): Int {
        _state.value = _state.value.copy(
            nodeBusy = true,
            nodeMessage = "Jade sonde les routes enregistrées…",
            error = null
        )
        return runCatching {
            val self = core.refreshNodes()
            Pair(self, core.runtimeSnapshots(self.knownNodes))
        }.fold(
            onSuccess = { (self, runtimes) ->
                val online = self.knownNodes.count { it.status == NodeStatus.ONLINE }
                _state.value = _state.value.copy(
                    nodeBusy = false,
                    selfModel = self,
                    runtimes = runtimes,
                    nodeMessage = "Registre rafraîchi : $online nœud(s) distant(s) en ligne.",
                    error = null
                )
                online
            },
            onFailure = { e ->
                _state.value = _state.value.copy(
                    nodeBusy = false,
                    nodeMessage = "",
                    error = e.message ?: e.toString()
                )
                -1
            }
        )
    }

    // 0.1.25: re-check the nodes when a validated network comes back after a loss.
    // Before, they stayed OFFLINE until the app was reopened (measured: 76 s+).
    // A reconnection signalled while a re-check is running is not dropped: the
    // runner does one more full re-check afterwards (Codex review of #74).
    // 0.1.26 (#77): the watcher follows the physical networks, not the Tailscale
    // VPN, marks the nodes "à vérifier" when every physical network is gone,
    // and every step goes to the diagnostic log.
    private val networkDiagnostics = DiagnosticLogger(application)
    // One ordered writer, off the main thread (Codex review of #79, P2).
    private val networkLog = OrderedEventWriter(viewModelScope + Dispatchers.IO) { event, metadata ->
        networkDiagnostics.log(
            DiagnosticLevel.INFO,
            event,
            NETWORK_LOG_MESSAGES[event] ?: event,
            metadata
        )
    }
    private val networkRestoreRunner = CoalescingRunner(viewModelScope) { recheckAfterRestore() }
    private val networkRestoreWatcher = NetworkRestoreWatcher(
        context = application,
        onRestored = { networkRestoreRunner.request() },
        onPhysicalLost = { onPhysicalNetworkLost() },
        log = { event, metadata -> networkLog.emit(event, metadata) }
    ).also { it.start() }

    private fun onPhysicalNetworkLost() {
        NodeManager.onPhysicalNetworkLost()
        viewModelScope.launch {
            // Display only: no probe, nothing written to the node registry.
            runCatching { core.selfModel() }.onSuccess { self ->
                _state.value = _state.value.copy(
                    selfModel = self,
                    nodeMessage = "Réseau perdu : nœuds distants à vérifier."
                )
            }
        }
    }

    private suspend fun recheckAfterRestore() {
        NodeManager.onNetworkRestored()
        // Written (with every earlier network event) before the probe logs
        // node_probe_online, so the file reads in causal order.
        networkLog.emitAndWait("network_recheck_started")
        // Let the phone's Tailscale tunnel come back before probing.
        delay(NETWORK_RESTORE_FIRST_PROBE_DELAY_MS)
        var online = refreshNodesNow()
        networkLog.emitAndWait("network_recheck_probe", mapOf("attempt" to 1, "online_nodes" to online))
        if (online == 0) {
            // Tunnel slower than expected: one later attempt, never a loop.
            delay(NETWORK_RESTORE_SECOND_PROBE_DELAY_MS)
            NodeManager.onNetworkRestored()
            online = refreshNodesNow()
            networkLog.emitAndWait("network_recheck_probe", mapOf("attempt" to 2, "online_nodes" to online))
        }
    }

    override fun onCleared() {
        networkRestoreWatcher.stop()
        super.onCleared()
    }

    fun registerNode(host: String, port: Int, token: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                nodeBusy = true,
                nodeMessage = "Association du nœud / de la route…",
                error = null
            )
            runCatching {
                val node = core.registerNode(host, port, token)
                Pair(node, core.selfModel())
            }.onSuccess { (node, self) ->
                _state.value = _state.value.copy(
                    nodeBusy = false,
                    selfModel = self,
                    runtimes = core.runtimeSnapshots(self.knownNodes),
                    nodeMessage = if (node.status == NodeStatus.ONLINE) {
                        "${node.name} enregistré : ${node.routes.size} route(s), runtime ${node.runtimeVersion.ifBlank { "inconnu" }}."
                    } else {
                        "${node.name} enregistré mais non joignable : ${node.lastError ?: node.status.name}."
                    },
                    error = null
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    nodeBusy = false,
                    nodeMessage = "",
                    error = e.message ?: e.toString()
                )
            }
        }
    }

    fun runComputeMeshProbe() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                taskBusy = true,
                taskMessage = "Le Compute Mesh envoie simultanément un morceau de travail à chaque nœud distant compatible…",
                error = null
            )
            runCatching { core.runComputeMeshProbe() }
                .onSuccess { summary ->
                    val self = core.selfModel()
                    _state.value = _state.value.copy(
                        taskBusy = false,
                        meshProbe = summary,
                        selfModel = self,
                        runtimes = core.runtimeSnapshots(self.knownNodes),
                        diagnostics = core.recentDiagnostics(),
                        taskMessage =
                            "Compute Mesh : ${summary.successCount}/${summary.nodeResults.size} nœud(s) ont terminé en parallèle en ${summary.completedAt - summary.startedAt} ms.",
                        error = null
                    )
                }
                .onFailure { e -> failTask(e) }
        }
    }

    fun runDistributedProbe() {
        viewModelScope.launch {
            beginTask("Task Router : genesis_probe en cours…")
            runCatching { core.runDistributedProbe() }
                .onSuccess { finishTask(it) }
                .onFailure { failTask(it) }
        }
    }

    fun runPixelFfmpegCanaryMeasurement() {
        if (!canaryGate.tryEnter()) return
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(
                    canaryBusy = true,
                    canaryMessage = "Mesure FFmpeg Pixel : 6 comparaisons VPS → PC en cours…",
                    error = null
                )
                runCatching {
                    val self = core.refreshNodes()
                    val pc = self.knownNodes.firstOrNull { node ->
                        node.kind == NodeKind.PC &&
                            node.status == NodeStatus.ONLINE &&
                            "ffmpeg_transcode_probe_v1" in node.capabilities
                    } ?: error("Aucun PC compatible en ligne.")
                    val vps = self.knownNodes.firstOrNull { node ->
                        node.kind == NodeKind.VPS &&
                            node.status == NodeStatus.ONLINE &&
                            "ffmpeg_transcode_probe_v1" in node.capabilities
                    } ?: error("Aucun VPS compatible en ligne.")
                    Pair(
                        self,
                        core.recommendManualFfmpegCanary(
                            incumbentNodeId = vps.nodeId,
                            challengerNodeId = pc.nodeId,
                            rounds = 6
                        )
                    )
                }.onSuccess { (self, recommendation) ->
                    val gain = recommendation.medianImprovementPercent?.let {
                        String.format(java.util.Locale.US, "%.1f", it)
                    } ?: "n/a"
                    _state.value = _state.value.copy(
                        canaryBusy = false,
                        selfModel = self,
                        runtimes = core.runtimeSnapshots(self.knownNodes),
                        diagnostics = core.recentDiagnostics(),
                        canaryMessage =
                            "Calcul nœud — VPS ${recommendation.incumbentMedianMs ?: -1} ms · " +
                                "PC ${recommendation.challengerMedianMs ?: -1} ms · gain PC $gain %. " +
                                "Bout-en-bout Pixel — VPS " +
                                "${recommendation.incumbentEndToEndMedianMs ?: -1} ms · PC " +
                                "${recommendation.challengerEndToEndMedianMs ?: -1} ms. " +
                                "${recommendation.status.name}. Aucun routage modifié.",
                        error = null
                    )
                }.onFailure { e ->
                    _state.value = _state.value.copy(
                        canaryBusy = false,
                        canaryMessage = "",
                        diagnostics = core.recentDiagnostics(),
                        error = e.message ?: e.toString()
                    )
                }
            } finally {
                canaryGate.leave()
                if (_state.value.canaryBusy) {
                    _state.value = _state.value.copy(canaryBusy = false)
                }
            }
        }
    }


    fun runDistributedTextAnalysis(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) {
            _state.value = _state.value.copy(error = "Entre un texte à analyser.")
            return
        }
        viewModelScope.launch {
            beginTask("Task Router : text_analysis en cours…")
            runCatching { core.runDistributedTextAnalysis(clean) }
                .onSuccess { finishTask(it) }
                .onFailure { failTask(it) }
        }
    }

    fun runMemoryConsolidation() {
        viewModelScope.launch {
            beginTask("Memory Lifecycle vérifie les sources puis consolide si nécessaire…")
            runCatching { core.runMemoryConsolidation() }
                .onSuccess { finishTask(it) }
                .onFailure { failTask(it) }
        }
    }

    fun send(text: String) {
        val input = text.trim()
        if (input.isBlank()) return

        viewModelScope.launch {
            _state.value = _state.value.copy(
                chatBusy = true,
                error = null
            )
            try {
                val lower = input.lowercase()
                val prefixes = listOf(
                    "retiens que ",
                    "retient que ",
                    "souviens-toi que ",
                    "souviens toi que "
                )
                val prefix = prefixes.firstOrNull { lower.startsWith(it) }
                val response = if (prefix != null) {
                    val content = input.substring(prefix.length).trim()
                    core.rememberUserFact(content)
                    "C'est mémorisé comme un fait fourni par l'utilisateur."
                } else {
                    core.ask(input)
                }

                val bundle = loadBundle(initialize = false)
                _state.value = _state.value.copy(
                    chatBusy = false,
                    response = response,
                    selfModel = bundle.self,
                    memories = bundle.memories,
                    memoryCount = bundle.memoryCount,
                    taskHistory = bundle.taskHistory,
                    taskQueue = bundle.taskQueue,
                    pendingTasks = bundle.pendingTasks,
                    cognitiveTrace = bundle.cognitiveTrace,
                    learningCandidates = bundle.learningCandidates,
                    diagnostics = bundle.diagnostics,
                    runtimes = bundle.runtimes,
                    toolCandidates = bundle.toolCandidates,
                    adminConfigured = bundle.adminConfigured,
                    adminUnlocked = bundle.adminUnlocked,
                    debugEnabled = bundle.debugEnabled,
                    error = null
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    chatBusy = false,
                    error = e.message ?: e.toString()
                )
            }
        }
    }

    fun onPhoneScreenCaptureStarted(requestedAt: Long) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                screenBusy = true,
                screenArmed = false,
                screenMessage = "Capture Pixel autorisée. Jade attend l'image puis cherche un nœud vision…",
                researchMessage = "",
                error = null
            )
            delay(250L)
            runCatching { core.analyzeLatestPhoneScreen(requestedAt) }
                .onSuccess { answer ->
                    _state.value = _state.value.copy(
                        screenBusy = false,
                        screenMessage = answer,
                        selfModel = core.selfModel(),
                        diagnostics = core.recentDiagnostics(),
                        error = null
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        screenBusy = false,
                        screenMessage = "",
                        diagnostics = core.recentDiagnostics(),
                        error = e.message ?: e.toString()
                    )
                }
        }
    }

    fun onPhoneScreenArmed() {
        _state.value = _state.value.copy(
            screenBusy = false,
            screenArmed = true,
            screenMessage =
                "Observation armée. Va dans l'application voulue puis utilise la notification Jade → Capturer maintenant. " +
                    "Après la capture, tu pourras choisir une zone et préciser ce que Jade doit regarder.",
            researchMessage = "",
            error = null
        )
    }

    fun onPhoneScreenArmUnavailable() {
        _state.value = _state.value.copy(
            screenBusy = false,
            screenArmed = false,
            screenMessage = "Le mode observation armée nécessite l'autorisation de notification Android.",
            error = null
        )
    }

    fun analyzePreparedVisual() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                screenBusy = true,
                screenMessage = "Jade analyse la dernière image/zone préparée…",
                researchMessage = "",
                error = null
            )
            runCatching { core.analyzeLatestPhoneScreen(0L) }
                .onSuccess { answer ->
                    _state.value = _state.value.copy(
                        screenBusy = false,
                        screenArmed = false,
                        screenMessage = answer,
                        selfModel = core.selfModel(),
                        diagnostics = core.recentDiagnostics(),
                        error = null
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        screenBusy = false,
                        diagnostics = core.recentDiagnostics(),
                        error = e.message ?: e.toString()
                    )
                }
        }
    }

    fun onPhoneScreenCaptureDenied() {
        _state.value = _state.value.copy(
            screenBusy = false,
            screenArmed = false,
            screenMessage = "Capture d'écran annulée. Aucune image n'a été transmise à Jade.",
            error = null
        )
    }

    fun analyzePcScreen() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                screenBusy = true,
                screenMessage = "Jade demande au runtime PC d'observer son écran…",
                researchMessage = "",
                error = null
            )
            runCatching { core.analyzePcScreen() }
                .onSuccess { answer ->
                    _state.value = _state.value.copy(
                        screenBusy = false,
                        screenMessage = answer,
                        selfModel = core.selfModel(),
                        diagnostics = core.recentDiagnostics(),
                        error = null
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        screenBusy = false,
                        screenMessage = "",
                        diagnostics = core.recentDiagnostics(),
                        error = e.message ?: e.toString()
                    )
                }
        }
    }

    fun deepResearchLastVisualObservation() {
    viewModelScope.launch {
        _state.value = _state.value.copy(
            researchBusy = true,
            researchMessage =
                "Jade extrait une requête sûre, consulte des sources publiques et recoupe les résultats…",
            error = null
        )
        runCatching { core.deepResearchLastVisualObservation() }
            .onSuccess { answer ->
                val bundle = loadBundle(initialize = false)
                _state.value = _state.value.copy(
                    researchBusy = false,
                    researchMessage = answer,
                    selfModel = bundle.self,
                    memories = bundle.memories,
                    memoryCount = bundle.memoryCount,
                    learningCandidates = bundle.learningCandidates,
                    diagnostics = bundle.diagnostics,
                    error = null
                )
            }
            .onFailure { e ->
                _state.value = _state.value.copy(
                    researchBusy = false,
                    researchMessage = "",
                    diagnostics = core.recentDiagnostics(),
                    error = e.message ?: e.toString()
                )
            }
    }
}

    fun proposeToolCandidate(idea: String) {
        val clean = idea.trim()
        if (clean.isBlank()) {
            _state.value = _state.value.copy(error = "Décris l'outil à concevoir.")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                toolBusy = true,
                toolMessage = "Tool Lab : conception d'un candidat non activé…",
                error = null
            )
            runCatching { core.proposeToolCandidate(clean) }
                .onSuccess { candidate ->
                    _state.value = _state.value.copy(
                        toolBusy = false,
                        toolCandidates = core.toolCandidates(),
                        toolMessage =
                            "Candidat ${candidate.name} créé (${candidate.status}). " +
                                "Il n'est pas activé automatiquement.",
                        diagnostics = core.recentDiagnostics(),
                        error = null
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        toolBusy = false,
                        toolMessage = "",
                        diagnostics = core.recentDiagnostics(),
                        error = e.message ?: e.toString()
                    )
                }
        }
    }

    fun configureAdminPin(pin: String) {
        runCatching { core.configureAdminPin(pin) }
            .onSuccess {
                _state.value = _state.value.copy(
                    adminConfigured = true,
                    adminUnlocked = true,
                    diagnostics = core.recentDiagnostics(),
                    error = null
                )
            }
            .onFailure { e ->
                _state.value = _state.value.copy(error = e.message ?: e.toString())
            }
    }

    fun unlockAdmin(pin: String) {
        val success = core.unlockAdmin(pin)
        _state.value = _state.value.copy(
            adminUnlocked = success,
            diagnostics = if (success) core.recentDiagnostics() else _state.value.diagnostics,
            error = if (success) null else "PIN Admin incorrect."
        )
    }

    fun lockAdmin() {
        core.lockAdmin()
        _state.value = _state.value.copy(
            adminUnlocked = false,
            diagnosticBundlePath = null
        )
    }

    fun setDebugEnabled(enabled: Boolean) {
        runCatching { core.setDebugEnabled(enabled) }
            .onSuccess {
                _state.value = _state.value.copy(
                    debugEnabled = enabled,
                    diagnostics = core.recentDiagnostics(),
                    error = null
                )
            }
            .onFailure { e ->
                _state.value = _state.value.copy(error = e.message ?: e.toString())
            }
    }

    fun generateDiagnosticBundle() {
        viewModelScope.launch {
            runCatching { core.generateDiagnosticBundle() }
                .onSuccess { path ->
                    _state.value = _state.value.copy(
                        diagnosticBundlePath = path,
                        diagnostics = core.recentDiagnostics(),
                        error = null
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(error = e.message ?: e.toString())
                }
        }
    }

    private suspend fun loadBundle(initialize: Boolean): RefreshBundle {
        val self = if (initialize) core.initialize() else core.selfModel()
        return RefreshBundle(
            self = self,
            memories = core.latestMemories(),
            memoryCount = core.memoryCount(),
            taskHistory = core.recentTaskHistory(),
            taskQueue = core.recentTaskQueue(),
            pendingTasks = core.pendingTaskCount(),
            cognitiveTrace = core.cognitiveTrace(),
            learningCandidates = core.learningCandidates(),
            diagnostics = core.recentDiagnostics(),
            runtimes = core.runtimeSnapshots(self.knownNodes),
            toolCandidates = core.toolCandidates(),
            adminConfigured = core.isAdminConfigured(),
            adminUnlocked = core.isAdminUnlocked(),
            debugEnabled = core.isDebugEnabled()
        )
    }

    private fun applyBundle(bundle: RefreshBundle, loading: Boolean) {
        _state.value = _state.value.copy(
            loading = loading,
            selfModel = bundle.self,
            memories = bundle.memories,
            memoryCount = bundle.memoryCount,
            taskHistory = bundle.taskHistory,
            taskQueue = bundle.taskQueue,
            pendingTasks = bundle.pendingTasks,
            cognitiveTrace = bundle.cognitiveTrace,
            learningCandidates = bundle.learningCandidates,
            diagnostics = bundle.diagnostics,
            runtimes = bundle.runtimes,
            toolCandidates = bundle.toolCandidates,
            adminConfigured = bundle.adminConfigured,
            adminUnlocked = bundle.adminUnlocked,
            debugEnabled = bundle.debugEnabled,
            lastTaskResult = bundle.taskHistory.firstOrNull(),
            error = null
        )
    }

    private fun beginTask(message: String) {
        _state.value = _state.value.copy(
            taskBusy = true,
            taskMessage = message,
            error = null
        )
    }

    private suspend fun finishTask(result: DistributedTaskResult) {
        val bundle = loadBundle(initialize = false)
        _state.value = _state.value.copy(
            taskBusy = false,
            selfModel = bundle.self,
            lastTaskResult = result,
            taskHistory = bundle.taskHistory,
            taskQueue = bundle.taskQueue,
            pendingTasks = bundle.pendingTasks,
            memories = bundle.memories,
            memoryCount = bundle.memoryCount,
            cognitiveTrace = bundle.cognitiveTrace,
            learningCandidates = bundle.learningCandidates,
            diagnostics = bundle.diagnostics,
            runtimes = bundle.runtimes,
            toolCandidates = bundle.toolCandidates,
            taskMessage = taskCompletionMessage(result),
            error = null
        )
    }

    private fun taskCompletionMessage(result: DistributedTaskResult): String =
        when (result.taskKind) {
            "memory_lifecycle_noop" ->
                "Aucune nouvelle source : consolidation identique bloquée."

            "memory_consolidation" ->
                "Mémoire consolidée sur ${result.executedNodeName}${if (result.fallbackUsed) " avec fallback" else ""}."

            else ->
                "${result.taskKind} exécuté sur ${result.executedNodeName}${if (result.fallbackUsed) " avec fallback" else ""}."
        }

    private fun failTask(e: Throwable) {
        _state.value = _state.value.copy(
            taskBusy = false,
            taskMessage = "",
            taskQueue = core.recentTaskQueue(),
            pendingTasks = core.pendingTaskCount(),
            diagnostics = core.recentDiagnostics(),
            error = e.message ?: e.toString()
        )
    }
}

// Delays used when a validated network comes back (see recheckAfterRestore).
private const val NETWORK_RESTORE_FIRST_PROBE_DELAY_MS = 3_000L
private const val NETWORK_RESTORE_SECOND_PROBE_DELAY_MS = 12_000L

// Diagnostic messages of the network watcher (0.1.26). Metadata holds only
// counters, booleans and transport kinds: no address, network name or token.
private val NETWORK_LOG_MESSAGES = mapOf(
    "network_watch_registered" to "Surveillance du réseau physique (hors VPN) active.",
    "network_watch_failed" to "Surveillance du réseau impossible : revérification seulement à l'ouverture, sur Actualiser et avant chaque message.",
    "network_physical_lost" to "Plus aucun réseau physique validé : nœuds distants à vérifier.",
    "network_physical_back" to "Réseau physique validé de retour.",
    "network_restore_deferred" to "Retour du réseau trop rapproché : revérification reportée.",
    "network_restore_triggered" to "Retour du réseau après une perte : revérification des nœuds demandée.",
    "network_recheck_started" to "Revérification des nœuds après retour du réseau.",
    "network_recheck_probe" to "Sonde après retour du réseau terminée."
)
