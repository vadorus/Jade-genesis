package com.jadegenesis.mobile.core

import android.content.Context
import com.jadegenesis.mobile.brain.BrainBackend
import com.jadegenesis.mobile.brain.PrototypeBrain
import com.jadegenesis.mobile.device.DeviceProfiler
import com.jadegenesis.mobile.identity.IdentityManager
import com.jadegenesis.mobile.memory.JadeDatabase
import com.jadegenesis.mobile.memory.MemoryStore
import com.jadegenesis.mobile.model.BrainContext
import com.jadegenesis.mobile.model.JadeIdentity
import com.jadegenesis.mobile.model.MemorySnapshot
import com.jadegenesis.mobile.model.SelfModel
import com.jadegenesis.mobile.selfmodel.SelfModelBuilder
import com.jadegenesis.mobile.tools.ToolObservation
import com.jadegenesis.mobile.tools.ToolRegistry

class JadeCore(
    context: Context,
    private val brain: BrainBackend = PrototypeBrain()
) {
    private val appContext = context.applicationContext
    private val identityManager = IdentityManager(appContext)
    private val profiler = DeviceProfiler(appContext)
    private val tools = ToolRegistry(profiler)
    private val memory = MemoryStore(JadeDatabase.get(appContext).memoryDao())
    private val selfModelBuilder = SelfModelBuilder()

    private var identity: JadeIdentity? = null

    suspend fun initialize(): SelfModel {
        identity = identityManager.loadOrCreate()
        return selfModel()
    }

    fun toolNames(): List<String> = tools.names()

    suspend fun selfModel(): SelfModel {
        val activeIdentity = identity ?: identityManager.loadOrCreate().also {
            identity = it
        }
        return selfModelBuilder.build(
            identity = activeIdentity,
            nodeId = profiler.nodeId(),
            device = profiler.capture(),
            toolNames = tools.names()
        )
    }

    suspend fun rememberUserFact(content: String) {
        memory.rememberUserFact(content, profiler.nodeId())
    }

    suspend fun latestMemories(limit: Int = 20): List<MemorySnapshot> =
        memory.latest(limit)

    suspend fun memoryCount(): Int = memory.count()

    suspend fun ask(userInput: String): String {
        val context = BrainContext(
            userInput = userInput,
            selfModel = selfModel(),
            memories = memory.latest(12),
            tools = tools.describe()
        )

        val first = brain.think(context)

        return when (val toolName = first.toolName) {
            null -> first.text
            else -> when (val observation = tools.call(toolName)) {
                is ToolObservation.Device -> {
                    val d = observation.profile
                    """
                    ${first.text}

                    Appareil : ${d.manufacturer} ${d.model}
                    Android : ${d.androidVersion} (API ${d.sdkInt})
                    SoC : ${d.socManufacturer} ${d.socModel}
                    Architecture : ${d.abis.joinToString()}
                    RAM : ${d.ramTotalGb} Go (${d.ramAvailableGb} Go disponibles)
                    Stockage : ${d.storageFreeGb} Go libres / ${d.storageTotalGb} Go
                    Batterie : ${d.batteryPercent}%${if (d.charging) " — en charge" else ""}
                    Thermique : ${d.thermalStatus}
                    """.trimIndent()
                }
            }
        }
    }
}
