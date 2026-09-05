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

enum class NodeKind {
    PHONE,
    PC,
    VPS,
    UNKNOWN
}

enum class NodeStatus {
    LOCAL,
    ONLINE,
    OFFLINE,
    UNKNOWN,
    ERROR
}

enum class NodeRouteKind {
    LAN,
    TAILSCALE,
    MANUAL
}

enum class NodeRouteStatus {
    ONLINE,
    OFFLINE,
    UNKNOWN,
    ERROR
}

enum class TaskExecutionLocation {
    LOCAL,
    REMOTE
}

enum class TaskStatus {
    CREATED,
    ROUTING,
    RUNNING,
    COMPLETED,
    FAILED
}

enum class TaskWorkload {
    LIGHT,
    MEDIUM,
    HEAVY
}

enum class QueueTaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}

enum class CognitivePhase {
    OBSERVE,
    PLAN,
    EXECUTE,
    VERIFY,
    REVISE,
    LEARN,
    COMPLETE
}

enum class DiagnosticLevel {
    INFO,
    WARN,
    ERROR,
    DEBUG
}

enum class LearningCandidateStatus {
    CANDIDATE,
    ACCEPTED,
    REJECTED
}

data class JadeIdentity(
    val jadeId: String,
    val name: String = "Jade Genesis",
    val version: String = "0.1.0",
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

data class NodeRouteSnapshot(
    val routeId: String,
    val kind: NodeRouteKind,
    val host: String,
    val port: Int,
    val status: NodeRouteStatus,
    val latencyMs: Long? = null,
    val lastSeenAt: Long = 0L,
    val lastError: String? = null
)

data class GenesisNode(
    val nodeId: String,
    val name: String,
    val kind: NodeKind,
    val status: NodeStatus,
    val host: String = "",
    val port: Int = 0,
    val protocol: String = "",
    val osName: String = "",
    val cpuName: String = "",
    val cpuCores: Int = 0,
    val ramTotalGb: Double = 0.0,
    val ramAvailableGb: Double = 0.0,
    val storageFreeGb: Double = 0.0,
    val capabilities: List<String> = emptyList(),
    val lastSeenAt: Long = 0L,
    val lastError: String? = null,
    val routes: List<NodeRouteSnapshot> = emptyList(),
    val activeRouteId: String? = null,
    val runtimeVersion: String = "",
    val runtimeChannel: String = "",
    val brainBackend: String = "",
    val brainModel: String = ""
)

data class DistributedTaskRequest(
    val taskId: String,
    val taskKind: String,
    val payload: String,
    val requiredCapability: String,
    val workload: TaskWorkload,
    val iterations: Int = 0,
    val createdAt: Long
)

data class NodeTaskResponse(
    val taskId: String,
    val taskKind: String,
    val nodeId: String,
    val nodeName: String,
    val output: String,
    val durationMs: Long
)

data class TaskAttempt(
    val nodeId: String,
    val nodeName: String,
    val executionLocation: TaskExecutionLocation,
    val success: Boolean,
    val durationMs: Long,
    val error: String? = null
)

data class DistributedTaskResult(
    val taskId: String,
    val taskKind: String,
    val requestedNodeId: String?,
    val requestedNodeName: String?,
    val executedNodeId: String,
    val executedNodeName: String,
    val executionLocation: TaskExecutionLocation,
    val status: TaskStatus,
    val success: Boolean,
    val output: String,
    val durationMs: Long,
    val fallbackUsed: Boolean,
    val fallbackReason: String? = null,
    val routeReason: String,
    val attempts: List<TaskAttempt>,
    val startedAt: Long,
    val completedAt: Long
)

data class QueuedTaskSnapshot(
    val taskId: String,
    val taskKind: String,
    val workload: TaskWorkload,
    val status: QueueTaskStatus,
    val selectedNodeName: String? = null,
    val attempts: Int = 0,
    val error: String? = null,
    val queuedAt: Long,
    val updatedAt: Long
)

data class MemoryConsolidationSummary(
    val inputCount: Int,
    val uniqueCount: Int,
    val duplicateGroups: Int,
    val duplicateItems: Int,
    val potentialContradictions: Int,
    val topTerms: String,
    val summary: String,
    val inputSha256: String
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
    val knownNodes: List<GenesisNode>,
    val preferredComputeNodeId: String?,
    val capabilities: List<Capability>,
    val knownLimits: List<String>
)

data class BrainContext(
    val userInput: String,
    val selfModel: SelfModel,
    val memories: List<MemorySnapshot>,
    val tools: List<ToolInfo>,
    val operation: String = "answer",
    val draftResponse: String? = null,
    val reviewNote: String? = null
)

data class BrainResult(
    val text: String,
    val toolName: String? = null,
    val backendId: String = "",
    val backendDisplayName: String = "",
    val model: String = "",
    val fallbackUsed: Boolean = false,
    val fallbackReason: String? = null
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

data class CognitiveTraceEvent(
    val id: String,
    val phase: CognitivePhase,
    val summary: String,
    val backendId: String? = null,
    val nodeId: String? = null,
    val durationMs: Long = 0L,
    val success: Boolean = true,
    val createdAt: Long
)

data class LearningCandidate(
    val id: String,
    val title: String,
    val description: String,
    val evidence: String,
    val confidence: Double,
    val status: LearningCandidateStatus = LearningCandidateStatus.CANDIDATE,
    val createdAt: Long
)

data class MeshNodeResult(
    val nodeId: String,
    val nodeName: String,
    val success: Boolean,
    val durationMs: Long,
    val outputPreview: String = "",
    val error: String? = null
)

data class MeshProbeSummary(
    val startedAt: Long,
    val completedAt: Long,
    val nodeResults: List<MeshNodeResult>
) {
    val successCount: Int
        get() = nodeResults.count { it.success }
}

data class DiagnosticLogEntry(
    val level: DiagnosticLevel,
    val event: String,
    val message: String,
    val metadata: Map<String, String>,
    val createdAt: Long
)

data class RuntimeNodeSnapshot(
    val nodeId: String,
    val nodeName: String,
    val runtimeVersion: String,
    val channel: String,
    val online: Boolean,
    val updateAvailable: Boolean
)
