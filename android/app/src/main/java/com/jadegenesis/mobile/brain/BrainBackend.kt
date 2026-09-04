package com.jadegenesis.mobile.brain

import com.jadegenesis.mobile.model.BrainContext
import com.jadegenesis.mobile.model.BrainInfo
import com.jadegenesis.mobile.model.BrainResult

interface BrainBackend {
    val info: BrainInfo
    suspend fun think(context: BrainContext): BrainResult
}
