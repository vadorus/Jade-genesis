package com.jadegenesis.mobile.brain

import com.jadegenesis.mobile.model.BrainContext
import com.jadegenesis.mobile.model.BrainInfo
import com.jadegenesis.mobile.model.BrainResult
import com.jadegenesis.mobile.model.GenesisNode

interface BrainBackend {
    val info: BrainInfo

    fun availableFor(nodes: List<GenesisNode>): Boolean =
        info.available

    suspend fun think(context: BrainContext): BrainResult
}
