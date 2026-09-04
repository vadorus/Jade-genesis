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

enum class ResourceMode {
    CRITICAL,
    ECO,
    BALANCED,
    PERFORMANCE
}

enum class BrainBackendType {
    PROTOTYPE,
    LOCAL_PHONE,
    LOCAL_NODE,
    REMOTE_NODE,
    CLOUD_OPTIONAL
}

enum class BrainResourceClass {
    MINIMAL,
    LIGHT,
    MEDIUM,
    HEAVY
}

data class JadeIdentity(
    val jadeId: String,
    val name: String = "Jade Genesis",
    val version: String = "0.0.2",
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
    val cpuCores: Int,
    val ramTotalGb: Double,
    val ramAvailableGb: Double,
    val ramLow: Boolean,
    val appMemoryClassMb: Int,
    val processHeapUsedMb: Double,
    val processHeapMaxMb: Double,
    val storageTotalGb: Double,
    val storageFreeGb: Double,
    val batteryPercent: Int,
    val charging: Boolean,
    val powerSaveMode: Boolean,
    val deviceIdleMode: Boolean,
    val thermalStatus: String,
    val capturedAt: Long
)

data class ResourceBudget(
    val mode: ResourceMode,
    val reasons: List<String>,
    val systemRamReserveGb: Double,
    val recommendedWorkingSetMb: Int,
    val maxParallelTasks: Int,
    val heavyBackgroundWorkAllowed: Boolean,
    val preferRemoteCompute: Boolean,
    val maxTaskSliceSeconds: Int,
    val evaluatedAt: Long
)

data class BrainInfo(
    val id: String,
    val displayName: String,
    val backendType: BrainBackendType,
    val location: String,
    val resourceClass: BrainResourceClass,
    val requiresNetwork: Boolean,
    val paidApi: Boolean,
    val available: Boolean,
    val priority: Int,
    val details: String = ""
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
    val resourceBudget: ResourceBudget,
    val activeBrain: BrainInfo,
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
