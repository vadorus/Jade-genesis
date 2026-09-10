package com.jadegenesis.mobile.state

import android.content.Context
import com.jadegenesis.mobile.config.JadeConfigRuntime
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.device.DeviceProfiler
import com.jadegenesis.mobile.diagnostics.DiagnosticLogger
import com.jadegenesis.mobile.eval.RuntimeEvalRuntime
import com.jadegenesis.mobile.evolution.EvolutionRuntime
import com.jadegenesis.mobile.identity.IdentityManager
import com.jadegenesis.mobile.memory.MemoryHealthMonitor
import com.jadegenesis.mobile.memory.MemoryLifecycleManager
import com.jadegenesis.mobile.node.NodeManager
import com.jadegenesis.mobile.resource.ResourceAdmissionController
import com.jadegenesis.mobile.resource.ResourceGovernor
import org.json.JSONArray
import org.json.JSONObject

class SharedGenesisStateBootstrapper(context: Context) {
    private val appContext = context.applicationContext
    private val profiler = DeviceProfiler(appContext)
    private val diagnostics = DiagnosticLogger(appContext)
    private val nodeManager = NodeManager(appContext, profiler, diagnostics)
    private val identityManager = IdentityManager(appContext)
    private val memoryLifecycle = MemoryLifecycleManager(appContext)
    private val memoryHealth = MemoryHealthMonitor(appContext)
    private val runtimeEval = RuntimeEvalRuntime.initialize(appContext)
    private val evolution = EvolutionRuntime.initialize(appContext)
    private val stateStore = SharedGenesisStateStore(appContext)
    private val coordinator = SharedGenesisStateCoordinator(
        nodeManager = nodeManager,
        store = stateStore,
        logger = diagnostics
    )
    private val resourceGovernor = ResourceGovernor()
    private val resourceAdmission = ResourceAdmissionController()

    suspend fun syncCurrentState(): SharedStateSyncResult {
        val identity = identityManager.loadOrCreate()
        val replicaId = profiler.nodeId()
        val device = profiler.capture()
        val budget = resourceGovernor.evaluate(device)
        val config = JadeConfigRuntime.current().validated()
        val cursor = memoryLifecycle.currentCursor()
        val storage = memoryHealth.snapshot()
        val now = System.currentTimeMillis()

        coordinator.publish(
            originNode = replicaId,
            kind = "identity_presence",
            entityId = identity.jadeId,
            payload = JSONObject().apply {
                put("name", identity.name)
                put("version", identity.version)
                put("created_at", identity.createdAt)
                put("observed_at", now)
            }.toString()
        )

        coordinator.publish(
            originNode = replicaId,
            kind = "config_snapshot",
            entityId = "active",
            payload = JSONObject().apply {
                put("config", config.toJson())
                put("observed_at", now)
            }.toString()
        )

        coordinator.publish(
            originNode = replicaId,
            kind = "phone_node_snapshot",
            entityId = replicaId,
            payload = JSONObject().apply {
                put("manufacturer", device.manufacturer)
                put("model", device.model)
                put("android_version", device.androidVersion)
                put("cpu_cores", device.cpuCores)
                put("ram_total_gb", device.ramTotalGb)
                put("ram_available_gb", device.ramAvailableGb)
                put("storage_free_gb", device.storageFreeGb)
                put("battery_percent", device.batteryPercent)
                put("charging", device.charging)
                put("thermal_status", device.thermalStatus)
                put("resource_mode", budget.mode.name)
                put("recommended_working_set_mb", budget.recommendedWorkingSetMb)
                put("captured_at", device.capturedAt)
            }.toString()
        )

        coordinator.publish(
            originNode = replicaId,
            kind = "memory_cursor",
            entityId = "memory-v2",
            payload = JSONObject().apply {
                put("created_at", cursor.createdAt)
                put("id", cursor.id)
                put(
                    "last_retention_deleted",
                    memoryLifecycle.lastRetentionDeletedCount()
                )
                put("storage_total_bytes", storage.totalBytes)
                put("core_database_bytes", storage.coreDatabaseBytes)
                put("conversation_learning_bytes", storage.conversationLearningBytes)
                put("runtime_eval_bytes", storage.runtimeEvalBytes)
                put("shared_state_bytes", storage.sharedStateBytes)
                put("other_jade_state_bytes", storage.otherJadeStateBytes)
                put("growth_7d_bytes", storage.growth7dBytes ?: JSONObject.NULL)
                put("growth_30d_bytes", storage.growth30dBytes ?: JSONObject.NULL)
                put("storage_status", storage.status.name)
                put("storage_sampled_at", storage.sampledAt)
                put("observed_at", now)
            }.toString()
        )

        coordinator.publish(
            originNode = replicaId,
            kind = "resource_lease_snapshot",
            entityId = "active",
            payload = JSONObject().apply {
                put(
                    "leases",
                    JSONArray().apply {
                        resourceAdmission.activeLeases().forEach { lease ->
                            put(
                                JSONObject().apply {
                                    put("lease_id", lease.leaseId)
                                    put("task_id", lease.taskId)
                                    put("task_kind", lease.taskKind)
                                    put("node_id", lease.nodeId)
                                    put("node_name", lease.nodeName)
                                    put("action", lease.action.name)
                                    put("memory_mb", lease.memoryMb)
                                    put("cpu_percent", lease.cpuPercent)
                                    put("vram_gb", lease.vramGb)
                                    put("acquired_at", lease.acquiredAt)
                                }
                            )
                        }
                    }
                )
                put("observed_at", now)
            }.toString()
        )

        val runtimeReport = runtimeEval.report()
        coordinator.publish(
            originNode = replicaId,
            kind = "runtime_eval_snapshot",
            entityId = "current",
            payload = JSONObject().apply {
                put("schema_version", runtimeReport.schemaVersion)
                put("generated_at", runtimeReport.generatedAt)
                put("observation_count", runtimeReport.observationCount)
                put("successful_observations", runtimeReport.successfulObservations)
                put("overall_success_rate", runtimeReport.overallSuccessRate)
                put("score", runtimeReport.score)
                put("confidence", runtimeReport.confidence)
                put("outcome_feedback_count", runtimeReport.outcomeFeedbackCount)
                put("overall_outcome_quality", runtimeReport.overallOutcomeQuality)
                put(
                    "groups",
                    JSONArray().apply {
                        runtimeReport.groups
                            .take(SafetyPolicy.MAX_SHARED_STATE_RUNTIME_GROUPS)
                            .forEach { group ->
                                put(
                                    JSONObject().apply {
                                        put("node_id", group.nodeId)
                                        put("node_name", group.nodeName)
                                        put("task_kind", group.taskKind)
                                        put("model", group.model)
                                        put("brain_profile", group.brainProfile)
                                        put("samples", group.samples)
                                        put("success_rate", group.successRate)
                                        put("average_duration_ms", group.averageDurationMs)
                                        put("fallback_rate", group.fallbackRate)
                                        put("average_tokens_per_second", group.averageTokensPerSecond)
                                        put("outcome_samples", group.outcomeSamples)
                                        put("positive_outcomes", group.positiveOutcomes)
                                        put("negative_outcomes", group.negativeOutcomes)
                                        put("corrections", group.corrections)
                                        put("outcome_quality_score", group.outcomeQualityScore)
                                        put("outcome_confidence", group.outcomeConfidence)
                                        put("last_observed_at", group.lastObservedAt)
                                    }
                                )
                            }
                    }
                )
                put("observed_at", now)
            }.toString()
        )

        val evolutionCandidates = evolution.candidates(
            SafetyPolicy.MAX_EVOLUTION_CANDIDATES
        )
        coordinator.publish(
            originNode = replicaId,
            kind = "evolution_snapshot",
            entityId = "current",
            payload = JSONObject().apply {
                put(
                    "candidates",
                    JSONArray().apply {
                        evolutionCandidates.forEach { candidate ->
                            put(
                                JSONObject().apply {
                                    put("candidate_id", candidate.candidateId)
                                    put("kind", candidate.kind.name)
                                    put("title", candidate.title)
                                    put("status", candidate.status.name)
                                    put("champion_config_id", candidate.championConfigId)
                                    put("challenger_config_id", candidate.challengerConfigId)
                                    put(
                                        "promotion_eligible",
                                        candidate.comparison?.promotionEligible == true
                                    )
                                    put("updated_at", candidate.updatedAt)
                                }
                            )
                        }
                    }
                )
                put("automatic_promotion", false)
                put("observed_at", now)
            }.toString()
        )

        return coordinator.sync(
            identityId = identity.jadeId,
            replicaId = replicaId,
            device = device
        )
    }
}
