package com.jadegenesis.mobile.tools

import com.jadegenesis.mobile.device.DeviceProfiler
import com.jadegenesis.mobile.model.DeviceProfile
import com.jadegenesis.mobile.model.ToolInfo

sealed interface ToolObservation {
    data class Device(val profile: DeviceProfile) : ToolObservation
}

data class JadeTool(
    val info: ToolInfo,
    val execute: suspend () -> ToolObservation
)

class ToolRegistry(private val profiler: DeviceProfiler) {

    private val tools = linkedMapOf<String, JadeTool>()

    init {
        register(
            JadeTool(
                info = ToolInfo(
                    name = "inspect_device",
                    description = "Inspecte le matériel et l'état réel du téléphone Android."
                ),
                execute = {
                    ToolObservation.Device(profiler.capture())
                }
            )
        )
    }

    private fun register(tool: JadeTool) {
        tools[tool.info.name] = tool
    }

    fun names(): List<String> = tools.keys.toList()

    fun describe(): List<ToolInfo> = tools.values.map { it.info }

    suspend fun call(name: String): ToolObservation =
        tools[name]?.execute?.invoke()
            ?: error("Outil inconnu : $name")
}
