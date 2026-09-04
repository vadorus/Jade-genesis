package com.jadegenesis.mobile.model

enum class MemoryType {
    FACT,
    OBSERVATION,
    HYPOTHESIS,
    EXPERIENCE,
    PROCEDURE,
    KNOWLEDGE,
    FAILURE
}

data class JadeIdentity(
    val jadeId: String,
    val name: String = "Jade Genesis",
    val version: String = "0.0.1",
    val createdAt: Long
)

data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidVersion: String,
    val sdkInt: Int,
    val socManufacturer: String,
    val socModel: String,
    val abis: List<String>,
    val ramTotalGb: Double,
    val ramAvailableGb: Double,
    val storageTotalGb: Double,
    val storageFreeGb: Double,
    val batteryPercent: Int,
    val charging: Boolean,
    val thermalStatus: String,
    val capturedAt: Long
)

data class Capability(
    val name: String,
    val available: Boolean,
    val source: String,
    val details: String = ""
)

data class SelfModel(
    val identity: JadeIdentity,
    val nodeId: String,
    val device: DeviceProfile,
    val capabilities: List<Capability>,
    val knownLimits: List<String>
)

data class BrainContext(
    val userInput: String,
    val selfModel: SelfModel,
    val memories: List<MemorySnapshot>,
    val tools: List<ToolInfo>
)

data class BrainResult(
    val text: String,
    val toolName: String? = null
)

data class MemorySnapshot(
    val id: String,
    val type: String,
    val content: String,
    val source: String,
    val confidence: Double,
    val createdAt: Long
)

data class ToolInfo(
    val name: String,
    val description: String
)
