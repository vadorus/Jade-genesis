package com.jadegenesis.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jadegenesis.mobile.core.JadeCore
import com.jadegenesis.mobile.model.DistributedTaskResult
import com.jadegenesis.mobile.model.MemorySnapshot
import com.jadegenesis.mobile.model.NodeStatus
import com.jadegenesis.mobile.model.SelfModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private data class RefreshBundle(
    val self: SelfModel,
    val memories: List<MemorySnapshot>,
    val memoryCount: Int,
    val taskHistory: List<DistributedTaskResult>
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
                    taskHistory = core.recentTaskHistory()
                )
            }.onSuccess { bundle ->
                _state.value = _state.value.copy(
                    loading = false,
                    selfModel = bundle.self,
                    memories = bundle.memories,
                    memoryCount = bundle.memoryCount,
                    taskHistory = bundle.taskHistory,
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
                    nodeMessage = "Nœuds rafraîchis : $online distant(s) en ligne.",
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
                        "${node.name} est en ligne (${node.protocol.ifBlank { "protocole inconnu" }})."
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
            beginTask("Le Task Router classe les nœuds pour genesis_probe…")

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
            beginTask("Le Task Router classe les nœuds pour text_analysis…")

            runCatching {
                core.runDistributedTextAnalysis(clean)
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
        _state.value = _state.value.copy(
            taskBusy = false,
            selfModel = self,
            lastTaskResult = result,
            taskHistory = history,
            taskMessage = buildString {
                append(
                    "Tâche ${result.taskKind} exécutée sur ${result.executedNodeName}."
                )
                if (result.fallbackUsed) {
                    append(" Fallback utilisé.")
                }
            },
            error = null
        )
    }

    private fun failTask(e: Throwable) {
        _state.value = _state.value.copy(
            taskBusy = false,
            taskMessage = "",
            error = e.message ?: e.toString()
        )
    }
}
