package com.jadegenesis.mobile.brain

import com.jadegenesis.mobile.model.BrainContext
import com.jadegenesis.mobile.model.BrainInfo
import com.jadegenesis.mobile.model.BrainResourceClass
import com.jadegenesis.mobile.model.BrainResult
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

    fun activeInfo(resourceBudget: ResourceBudget): BrainInfo =
        select(resourceBudget).info

    suspend fun think(context: BrainContext): BrainResult =
        select(context.selfModel.resourceBudget).think(context)

    private fun select(resourceBudget: ResourceBudget): BrainBackend {
        val available = backends.filter { it.info.available }

        require(available.isNotEmpty()) {
            "Aucun BrainBackend disponible."
        }

        val maximumClass = when (resourceBudget.mode) {
            ResourceMode.CRITICAL -> BrainResourceClass.MINIMAL
            ResourceMode.ECO -> BrainResourceClass.LIGHT
            ResourceMode.BALANCED -> BrainResourceClass.MEDIUM
            ResourceMode.PERFORMANCE -> BrainResourceClass.HEAVY
        }

        val eligible = available.filter {
            it.info.resourceClass.ordinal <= maximumClass.ordinal
        }

        val candidates = if (eligible.isNotEmpty()) {
            eligible
        } else {
            listOf(
                available.minBy { it.info.resourceClass.ordinal }
            )
        }

        fun locationScore(backend: BrainBackend): Int {
            val onPhone = backend.info.location == "phone"
            return if (resourceBudget.preferRemoteCompute) {
                if (onPhone) 0 else 100
            } else {
                if (onPhone) 100 else 0
            }
        }

        return candidates.maxWithOrNull(
            compareBy<BrainBackend> { locationScore(it) }
                .thenBy { it.info.priority }
        ) ?: candidates.first()
    }
}
