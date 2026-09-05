package com.jadegenesis.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jadegenesis.mobile.core.JadeCore
import com.jadegenesis.mobile.model.DistributedTaskResult
import com.jadegenesis.mobile.model.MemorySnapshot
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.model.QueuedTaskSnapshot
import com.jadegenesis.mobile.model.SelfModel
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
    val pendingTasks: Int
)

data class JadeUiState(
    val loading: Boolean = true,
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
            runCatching {
                RefreshBundle(
                    self = core.initialize(),
                    memories = core.latestMemories(),
                    memoryCount = core.memoryCount(),
                    taskHistory = core.recentTaskHistory(),
                    taskQueue = core.recentTaskQueue(),
                    pendingTasks = core.pendingTaskCount()
                )
            }.onSuccess { bundle ->
                _state.value = _state.value.copy(
                    loading = false,
                    selfModel = bundle.self,
                    memories = bundle.memories,
                    memoryCount = bundle.memoryCount,
                    taskHistory = bundle.taskHistory,
                    taskQueue = bundle.taskQueue,
                    pendingTasks = bundle.pendingTasks,
                    lastTaskResult = bundle.taskHistory.firstOrNull(),
                    error = null
                )
            }.onFailure { e ->
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
                nodeMessage = "Test des nœuds en cours…",
                error = null
            )

            runCatching {
                core.refreshNodes()
            }.onSuccess { self ->
                val online = self.knownNodes.count {
                    it.status == NodeStatus.ONLINE
                }
                _state.value = _state.value.copy(
                    nodeBusy = false,
                    selfModel = self,
                    nodeMessage =
                        "Nœuds rafraîchis : $online distant(s) en ligne.",
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

    fun registerPcNode(
        host: String,
        port: Int,
        token: String
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                nodeBusy = true,
                nodeMessage = "Connexion au PC Genesis…",
                error = null
            )

            runCatching {
                val node = core.registerPcNode(
                    host = host,
                    port = port,
                    token = token
                )
                Pair(node, core.selfModel())
            }.onSuccess { (node, self) ->
                _state.value = _state.value.copy(
                    nodeBusy = false,
                    selfModel = self,
                    nodeMessage = if (node.status == NodeStatus.ONLINE) {
                        "${node.name} est en ligne " +
                            "(${node.protocol.ifBlank { "protocole inconnu" }})."
                    } else {
                        "${node.name} enregistré, mais pas joignable : " +
                            "${node.lastError ?: node.status.name}"
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

    fun runDistributedProbe() {
        viewModelScope.launch {
            beginTask(
                "La file reçoit genesis_probe puis le Task Router classe les nœuds…"
            )

            runCatching {
                core.runDistributedProbe()
            }.onSuccess { result ->
                finishTask(result)
            }.onFailure { e ->
                failTask(e)
            }
        }
    }

    fun runDistributedTextAnalysis(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) {
            _state.value = _state.value.copy(
                error = "Entre un texte à analyser."
            )
            return
        }

        viewModelScope.launch {
            beginTask(
                "La file reçoit text_analysis puis Jade choisit le meilleur nœud…"
            )

            runCatching {
                core.runDistributedTextAnalysis(clean)
            }.onSuccess { result ->
                finishTask(result)
            }.onFailure { e ->
                failTask(e)
            }
        }
    }

    fun runMemoryConsolidation() {
        viewModelScope.launch {
            beginTask(
                "Memory Lifecycle 0.0.7 vérifie d'abord si les sources ont réellement changé…"
            )

            runCatching {
                core.runMemoryConsolidation()
            }.onSuccess { result ->
                finishTask(result)
            }.onFailure { e ->
                failTask(e)
            }
        }
    }

    fun send(text: String) {
        val input = text.trim()
        if (input.isBlank()) return

        viewModelScope.launch {
            try {
                val lower = input.lowercase()
                val prefixes = listOf(
                    "retiens que ",
                    "retient que ",
                    "souviens-toi que ",
                    "souviens toi que "
                )
                val prefix = prefixes.firstOrNull {
                    lower.startsWith(it)
                }

                val response = if (prefix != null) {
                    val content = input.substring(prefix.length).trim()
                    core.rememberUserFact(content)
                    "C'est mémorisé comme un fait fourni par l'utilisateur."
                } else {
                    core.ask(input)
                }

                _state.value = _state.value.copy(
                    response = response,
                    selfModel = core.selfModel(),
                    memories = core.latestMemories(),
                    memoryCount = core.memoryCount(),
                    taskHistory = core.recentTaskHistory(),
                    taskQueue = core.recentTaskQueue(),
                    pendingTasks = core.pendingTaskCount(),
                    error = null
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: e.toString()
                )
            }
        }
    }

    private fun beginTask(message: String) {
        _state.value = _state.value.copy(
            taskBusy = true,
            taskMessage = message,
            error = null
        )
    }

    private suspend fun finishTask(result: DistributedTaskResult) {
        val self = core.selfModel()
        val history = core.recentTaskHistory()
        val queue = core.recentTaskQueue()
        val memories = core.latestMemories()
        val memoryCount = core.memoryCount()

        _state.value = _state.value.copy(
            taskBusy = false,
            selfModel = self,
            lastTaskResult = result,
            taskHistory = history,
            taskQueue = queue,
            pendingTasks = core.pendingTaskCount(),
            memories = memories,
            memoryCount = memoryCount,
            taskMessage = taskCompletionMessage(result),
            error = null
        )
    }

    private fun taskCompletionMessage(
        result: DistributedTaskResult
    ): String = when (result.taskKind) {
        "memory_lifecycle_noop" ->
            "Memory Lifecycle 0.0.7 : aucune nouvelle source à apprendre. " +
                "La consolidation identique a été bloquée et aucune " +
                "connaissance supplémentaire n'a été créée."

        "memory_consolidation" -> buildString {
            append(
                "Tâche memory_consolidation exécutée sur " +
                    "${result.executedNodeName}."
            )
            if (result.fallbackUsed) {
                append(" Fallback utilisé.")
            }
            if (result.success) {
                append(
                    " Une connaissance 0.0.7 traçable a été ajoutée ; " +
                        "l'empreinte du lot est maintenant enregistrée."
                )
            }
        }

        else -> buildString {
            append(
                "Tâche ${result.taskKind} exécutée sur " +
                    "${result.executedNodeName}."
            )
            if (result.fallbackUsed) {
                append(" Fallback utilisé.")
            }
        }
    }

    private fun failTask(e: Throwable) {
        _state.value = _state.value.copy(
            taskBusy = false,
            taskMessage = "",
            taskQueue = core.recentTaskQueue(),
            pendingTasks = core.pendingTaskCount(),
            error = e.message ?: e.toString()
        )
    }
}
