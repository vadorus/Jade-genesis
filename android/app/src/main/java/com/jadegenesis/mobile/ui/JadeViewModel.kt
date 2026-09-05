package com.jadegenesis.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jadegenesis.mobile.core.JadeCore
import com.jadegenesis.mobile.model.CognitiveTraceEvent
import com.jadegenesis.mobile.model.DiagnosticLogEntry
import com.jadegenesis.mobile.model.DistributedTaskResult
import com.jadegenesis.mobile.model.LearningCandidate
import com.jadegenesis.mobile.model.MemorySnapshot
import com.jadegenesis.mobile.model.MeshProbeSummary
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.model.QueuedTaskSnapshot
import com.jadegenesis.mobile.model.RuntimeNodeSnapshot
import com.jadegenesis.mobile.model.SelfModel
import com.jadegenesis.mobile.model.ToolCandidateSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    val screenMessage: String = "",
    val researchBusy: Boolean = false,
    val researchMessage: String = "",
    val toolBusy: Boolean = false,
    val toolMessage: String = "",
    val adminConfigured: Boolean = false,
    val adminUnlocked: Boolean = false,
    val debugEnabled: Boolean = false,
    val diagnosticBundlePath: String? = null,
    val error: String? = null
)

class JadeViewModel(application: Application) : AndroidViewModel(application) {

    private val core = JadeCore(application)
    private val _state = MutableStateFlow(JadeUiState())
    val state: StateFlow<JadeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { loadBundle(initialize = true) }
                .onSuccess { applyBundle(it, loading = false) }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = e.message ?: e.toString()
                    )
                }
        }
    }

    fun refreshNodes() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                nodeBusy = true,
                nodeMessage = "Jade sonde les routes enregistrées…",
                error = null
            )
            runCatching {
                val self = core.refreshNodes()
                Pair(self, core.runtimeSnapshots(self.knownNodes))
            }.onSuccess { (self, runtimes) ->
                val online = self.knownNodes.count { it.status == NodeStatus.ONLINE }
                _state.value = _state.value.copy(
                    nodeBusy = false,
                    selfModel = self,
                    runtimes = runtimes,
                    nodeMessage = "Registre rafraîchi : $online nœud(s) distant(s) en ligne.",
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

    fun onPhoneScreenCaptureDenied() {
        _state.value = _state.value.copy(
            screenBusy = false,
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
