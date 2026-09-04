package com.jadegenesis.mobile.brain

import com.jadegenesis.mobile.model.BrainContext
import com.jadegenesis.mobile.model.BrainResult

interface BrainBackend {
    suspend fun think(context: BrainContext): BrainResult
}
