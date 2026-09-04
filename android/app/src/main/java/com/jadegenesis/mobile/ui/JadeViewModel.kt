package com.jadegenesis.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jadegenesis.mobile.core.JadeCore
import com.jadegenesis.mobile.model.MemorySnapshot
import com.jadegenesis.mobile.model.SelfModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class JadeUiState(
    val loading: Boolean = true,
    val selfModel: SelfModel? = null,
    val response: String = "",
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
                val self = core.initialize()
                val memories = core.latestMemories()
                val count = core.memoryCount()
                Triple(self, memories, count)
            }.onSuccess { (self, memories, count) ->
                _state.value = _state.value.copy(
                    loading = false,
                    selfModel = self,
                    memories = memories,
                    memoryCount = count,
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
                val prefix = prefixes.firstOrNull { lower.startsWith(it) }

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
                    error = null
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: e.toString()
                )
            }
        }
    }
}
