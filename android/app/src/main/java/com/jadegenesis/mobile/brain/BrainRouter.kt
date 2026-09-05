package com.jadegenesis.mobile.brain

import com.jadegenesis.mobile.model.BrainBackendType
import com.jadegenesis.mobile.model.BrainContext
import com.jadegenesis.mobile.model.BrainInfo
import com.jadegenesis.mobile.model.BrainResourceClass
import com.jadegenesis.mobile.model.BrainResult
import com.jadegenesis.mobile.model.GenesisNode
import com.jadegenesis.mobile.model.ResourceBudget
import com.jadegenesis.mobile.model.ResourceMode

class BrainRouter(backends: List<BrainBackend>) {

    private val backends = backends.toList()

    init {
        require(this.backends.isNotEmpty()) {
            "BrainRouter nécessite au moins un BrainBackend."
        }

        require(
            this.backends.map { it.info.id }.distinct().size ==
                this.backends.size
        ) {
            "Chaque BrainBackend doit avoir un ID unique."
        }
    }

    fun allInfos(): List<BrainInfo> =
        backends.map { it.info }

    fun activeInfo(
        resourceBudget: ResourceBudget,
        nodes: List<GenesisNode>
    ): BrainInfo =
        select(resourceBudget, nodes).info

    suspend fun think(context: BrainContext): BrainResult {
        val nodes = context.selfModel.knownNodes
        val primary = select(
            resourceBudget = context.selfModel.resourceBudget,
            nodes = nodes
        )

        return try {
            primary.think(context)
        } catch (primaryError: Exception) {
            val fallback = backends.firstOrNull {
                it !== primary &&
                    it.info.backendType == BrainBackendType.PROTOTYPE &&
                    it.info.available &&
                    it.availableFor(nodes)
            } ?: throw primaryError

            val fallbackResult = fallback.think(context)
            BrainResult(
                text = buildString {
                    append(
                        "Mon cerveau local PC n'a pas pu répondre à cette requête. " +
                            "J'utilise mon cerveau de secours pour rester disponible."
                    )
                    val reason = primaryError.message
                        ?.trim()
                        ?.take(160)
                        .orEmpty()
                    if (reason.isNotBlank()) {
                        append(" Raison : $reason")
                    }
                    append("\n\n")
                    append(fallbackResult.text)
                },
                toolName = fallbackResult.toolName
            )
        }
    }

    private fun select(
        resourceBudget: ResourceBudget,
        nodes: List<GenesisNode>
    ): BrainBackend {
        val available = backends.filter {
            it.info.available && it.availableFor(nodes)
        }

        require(available.isNotEmpty()) {
            "Aucun BrainBackend disponible."
        }

        val maximumPhoneClass = when (resourceBudget.mode) {
            ResourceMode.CRITICAL -> BrainResourceClass.MINIMAL
            ResourceMode.ECO -> BrainResourceClass.LIGHT
            ResourceMode.BALANCED -> BrainResourceClass.MEDIUM
            ResourceMode.PERFORMANCE -> BrainResourceClass.HEAVY
        }

        val eligible = available.filter { backend ->
            val runsOnPhone =
                backend.info.backendType == BrainBackendType.LOCAL_PHONE ||
                    backend.info.location == "phone"

            !runsOnPhone ||
                backend.info.resourceClass.ordinal <=
                maximumPhoneClass.ordinal
        }

        val candidates = if (eligible.isNotEmpty()) {
            eligible
        } else {
            listOf(
                available.minBy { it.info.resourceClass.ordinal }
            )
        }

        fun locationScore(backend: BrainBackend): Int =
            when (backend.info.backendType) {
                BrainBackendType.LOCAL_NODE,
                BrainBackendType.REMOTE_NODE ->
                    if (resourceBudget.preferRemoteCompute) 120 else 100

                BrainBackendType.LOCAL_PHONE ->
                    if (resourceBudget.preferRemoteCompute) 20 else 90

                BrainBackendType.CLOUD_OPTIONAL ->
                    if (resourceBudget.preferRemoteCompute) 70 else 40

                BrainBackendType.PROTOTYPE -> 10
            }

        return candidates.maxWithOrNull(
            compareBy<BrainBackend> { locationScore(it) }
                .thenBy { it.info.priority }
        ) ?: candidates.first()
    }
}
