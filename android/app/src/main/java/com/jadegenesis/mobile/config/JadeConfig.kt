package com.jadegenesis.mobile.config

import com.jadegenesis.mobile.model.ResourceMode
import org.json.JSONObject

data class JadeConfig(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val revision: Long = 1L,
    val configId: String = "builtin-1",
    val parentConfigId: String? = null,
    val resource: ResourceTuning = ResourceTuning(),
    val routing: RoutingTuning = RoutingTuning(),
    val task: TaskTuning = TaskTuning(),
    val retention: RetentionTuning = RetentionTuning()
) {
    fun validated(): JadeConfig {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Version de schéma JadeConfig incompatible : $schemaVersion"
        }
        require(revision >= 1L) { "Révision JadeConfig invalide." }
        require(configId.isNotBlank()) { "Identifiant JadeConfig vide." }
        resource.validate()
        routing.validate()
        task.validate()
        retention.validate()
        return this
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("schema_version", schemaVersion)
        put("revision", revision)
        put("config_id", configId)
        put("parent_config_id", parentConfigId ?: "")
        put("resource", resource.toJson())
        put("routing", routing.toJson())
        put("task", task.toJson())
        put("retention", retention.toJson())
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        fun defaults(): JadeConfig = JadeConfig()

        fun fromJson(json: JSONObject): JadeConfig {
            val defaults = defaults()
            return JadeConfig(
                schemaVersion = json.optInt(
                    "schema_version",
                    defaults.schemaVersion
                ),
                revision = json.optLong("revision", defaults.revision),
                configId = json.optString("config_id", defaults.configId)
                    .ifBlank { defaults.configId },
                parentConfigId = json.optString("parent_config_id")
                    .takeIf { it.isNotBlank() },
                resource = ResourceTuning.fromJson(
                    json.optJSONObject("resource") ?: JSONObject()
                ),
                routing = RoutingTuning.fromJson(
                    json.optJSONObject("routing") ?: JSONObject()
                ),
                task = TaskTuning.fromJson(
                    json.optJSONObject("task") ?: JSONObject()
                ),
                retention = RetentionTuning.fromJson(
                    json.optJSONObject("retention") ?: JSONObject()
                )
            ).validated()
        }
    }
}

data class ResourceModeTuning(
    val systemFraction: Double,
    val heapFraction: Double,
    val appClassFraction: Double,
    val maxParallelTasks: Int,
    val heavyBackgroundWorkAllowed: Boolean,
    val preferRemoteCompute: Boolean,
    val maxTaskSliceSeconds: Int
) {
    fun validate() {
        requireFraction(systemFraction, "systemFraction")
        requireFraction(heapFraction, "heapFraction")
        requireFraction(appClassFraction, "appClassFraction")
        require(maxParallelTasks >= 1) { "maxParallelTasks invalide." }
        require(maxTaskSliceSeconds >= 1) { "maxTaskSliceSeconds invalide." }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("system_fraction", systemFraction)
        put("heap_fraction", heapFraction)
        put("app_class_fraction", appClassFraction)
        put("max_parallel_tasks", maxParallelTasks)
        put("heavy_background_work_allowed", heavyBackgroundWorkAllowed)
        put("prefer_remote_compute", preferRemoteCompute)
        put("max_task_slice_seconds", maxTaskSliceSeconds)
    }

    companion object {
        fun fromJson(json: JSONObject, defaults: ResourceModeTuning): ResourceModeTuning =
            ResourceModeTuning(
                systemFraction = finiteDouble(
                    json,
                    "system_fraction",
                    defaults.systemFraction
                ),
                heapFraction = finiteDouble(
                    json,
                    "heap_fraction",
                    defaults.heapFraction
                ),
                appClassFraction = finiteDouble(
                    json,
                    "app_class_fraction",
                    defaults.appClassFraction
                ),
                maxParallelTasks = json.optInt(
                    "max_parallel_tasks",
                    defaults.maxParallelTasks
                ),
                heavyBackgroundWorkAllowed = json.optBoolean(
                    "heavy_background_work_allowed",
                    defaults.heavyBackgroundWorkAllowed
                ),
                preferRemoteCompute = json.optBoolean(
                    "prefer_remote_compute",
                    defaults.preferRemoteCompute
                ),
                maxTaskSliceSeconds = json.optInt(
                    "max_task_slice_seconds",
                    defaults.maxTaskSliceSeconds
                )
            ).also { it.validate() }
    }
}

data class ResourceTuning(
    val memoryEcoThresholdMultiplier: Double = 1.75,
    val fallbackEcoRamRatio: Double = 0.10,
    val ecoHeapRatio: Double = 0.75,
    val ecoThermalRank: Int = 2,
    val ecoBatteryPercent: Int = 25,
    val storageLowMinGb: Double = 1.0,
    val storageLowMaxGb: Double = 4.0,
    val storageLowFraction: Double = 0.01,
    val performanceRamTotalRatio: Double = 0.25,
    val performanceThresholdMultiplier: Double = 2.5,
    val fallbackPerformanceRamRatio: Double = 0.35,
    val performanceBatteryPercent: Int = 60,
    val performanceHeapRatio: Double = 0.60,
    val performanceThermalRank: Int = 1,
    val reserveThresholdMultiplier: Double = 1.75,
    val reserveRamFraction: Double = 0.12,
    val modeBudgets: Map<ResourceMode, ResourceModeTuning> = defaultModeBudgets()
) {
    fun mode(mode: ResourceMode): ResourceModeTuning =
        modeBudgets[mode] ?: defaultModeBudgets().getValue(mode)

    fun validate() {
        require(memoryEcoThresholdMultiplier >= 1.0)
        requireRatio(fallbackEcoRamRatio, "fallbackEcoRamRatio")
        requireRatio(ecoHeapRatio, "ecoHeapRatio")
        require(ecoThermalRank in 0..6)
        require(ecoBatteryPercent in 0..100)
        require(storageLowMinGb >= 0.0 && storageLowMinGb.isFinite())
        require(storageLowMaxGb >= storageLowMinGb && storageLowMaxGb.isFinite())
        requireRatio(storageLowFraction, "storageLowFraction")
        requireRatio(performanceRamTotalRatio, "performanceRamTotalRatio")
        require(performanceThresholdMultiplier >= 1.0)
        requireRatio(fallbackPerformanceRamRatio, "fallbackPerformanceRamRatio")
        require(performanceBatteryPercent in 0..100)
        requireRatio(performanceHeapRatio, "performanceHeapRatio")
        require(performanceThermalRank in 0..6)
        require(reserveThresholdMultiplier >= 1.0)
        requireRatio(reserveRamFraction, "reserveRamFraction")
        ResourceMode.values().forEach { mode -> this.mode(mode).validate() }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("memory_eco_threshold_multiplier", memoryEcoThresholdMultiplier)
        put("fallback_eco_ram_ratio", fallbackEcoRamRatio)
        put("eco_heap_ratio", ecoHeapRatio)
        put("eco_thermal_rank", ecoThermalRank)
        put("eco_battery_percent", ecoBatteryPercent)
        put("storage_low_min_gb", storageLowMinGb)
        put("storage_low_max_gb", storageLowMaxGb)
        put("storage_low_fraction", storageLowFraction)
        put("performance_ram_total_ratio", performanceRamTotalRatio)
        put("performance_threshold_multiplier", performanceThresholdMultiplier)
        put("fallback_performance_ram_ratio", fallbackPerformanceRamRatio)
        put("performance_battery_percent", performanceBatteryPercent)
        put("performance_heap_ratio", performanceHeapRatio)
        put("performance_thermal_rank", performanceThermalRank)
        put("reserve_threshold_multiplier", reserveThresholdMultiplier)
        put("reserve_ram_fraction", reserveRamFraction)
        put(
            "mode_budgets",
            JSONObject().apply {
                ResourceMode.values().forEach { mode ->
                    put(mode.name, this@ResourceTuning.mode(mode).toJson())
                }
            }
        )
    }

    companion object {
        fun defaultModeBudgets(): Map<ResourceMode, ResourceModeTuning> = mapOf(
            ResourceMode.CRITICAL to ResourceModeTuning(
                systemFraction = 0.04,
                heapFraction = 0.15,
                appClassFraction = 0.15,
                maxParallelTasks = 1,
                heavyBackgroundWorkAllowed = false,
                preferRemoteCompute = true,
                maxTaskSliceSeconds = 5
            ),
            ResourceMode.ECO to ResourceModeTuning(
                systemFraction = 0.08,
                heapFraction = 0.25,
                appClassFraction = 0.25,
                maxParallelTasks = 1,
                heavyBackgroundWorkAllowed = false,
                preferRemoteCompute = true,
                maxTaskSliceSeconds = 15
            ),
            ResourceMode.BALANCED to ResourceModeTuning(
                systemFraction = 0.12,
                heapFraction = 0.35,
                appClassFraction = 0.35,
                maxParallelTasks = 2,
                heavyBackgroundWorkAllowed = false,
                preferRemoteCompute = false,
                maxTaskSliceSeconds = 30
            ),
            ResourceMode.PERFORMANCE to ResourceModeTuning(
                systemFraction = 0.18,
                heapFraction = 0.50,
                appClassFraction = 0.50,
                maxParallelTasks = 3,
                heavyBackgroundWorkAllowed = true,
                preferRemoteCompute = false,
                maxTaskSliceSeconds = 60
            )
        )

        fun fromJson(json: JSONObject): ResourceTuning {
            val defaults = ResourceTuning()
            val budgetJson = json.optJSONObject("mode_budgets") ?: JSONObject()
            val budgets = ResourceMode.values().associateWith { mode ->
                ResourceModeTuning.fromJson(
                    budgetJson.optJSONObject(mode.name) ?: JSONObject(),
                    defaults.mode(mode)
                )
            }
            return ResourceTuning(
                memoryEcoThresholdMultiplier = finiteDouble(
                    json,
                    "memory_eco_threshold_multiplier",
                    defaults.memoryEcoThresholdMultiplier
                ),
                fallbackEcoRamRatio = finiteDouble(
                    json,
                    "fallback_eco_ram_ratio",
                    defaults.fallbackEcoRamRatio
                ),
                ecoHeapRatio = finiteDouble(json, "eco_heap_ratio", defaults.ecoHeapRatio),
                ecoThermalRank = json.optInt("eco_thermal_rank", defaults.ecoThermalRank),
                ecoBatteryPercent = json.optInt("eco_battery_percent", defaults.ecoBatteryPercent),
                storageLowMinGb = finiteDouble(json, "storage_low_min_gb", defaults.storageLowMinGb),
                storageLowMaxGb = finiteDouble(json, "storage_low_max_gb", defaults.storageLowMaxGb),
                storageLowFraction = finiteDouble(
                    json,
                    "storage_low_fraction",
                    defaults.storageLowFraction
                ),
                performanceRamTotalRatio = finiteDouble(
                    json,
                    "performance_ram_total_ratio",
                    defaults.performanceRamTotalRatio
                ),
                performanceThresholdMultiplier = finiteDouble(
                    json,
                    "performance_threshold_multiplier",
                    defaults.performanceThresholdMultiplier
                ),
                fallbackPerformanceRamRatio = finiteDouble(
                    json,
                    "fallback_performance_ram_ratio",
                    defaults.fallbackPerformanceRamRatio
                ),
                performanceBatteryPercent = json.optInt(
                    "performance_battery_percent",
                    defaults.performanceBatteryPercent
                ),
                performanceHeapRatio = finiteDouble(
                    json,
                    "performance_heap_ratio",
                    defaults.performanceHeapRatio
                ),
                performanceThermalRank = json.optInt(
                    "performance_thermal_rank",
                    defaults.performanceThermalRank
                ),
                reserveThresholdMultiplier = finiteDouble(
                    json,
                    "reserve_threshold_multiplier",
                    defaults.reserveThresholdMultiplier
                ),
                reserveRamFraction = finiteDouble(
                    json,
                    "reserve_ram_fraction",
                    defaults.reserveRamFraction
                ),
                modeBudgets = budgets
            ).also { it.validate() }
        }
    }
}

data class RoutingTuning(
    val cpuCoreWeight: Double = 1.5,
    val cpuHeadroomPercentWeight: Double = 0.15,
    val ramAvailableGbWeight: Double = 4.0,
    val storageFreeGbWeight: Double = 0.02,
    val activeTaskPenalty: Double = 8.0,
    val gpuVramFreeGbWeight: Double = 5.0,
    val gpuHeadroomPercentWeight: Double = 0.08,
    val brainReadyBonus: Double = 10.0,
    val brainLoadedBonus: Double = 25.0,
    val brainTokensPerSecondWeight: Double = 1.0,
    val preferredNodeHintBonus: Double = 8.0,
    val preferRemoteRemoteBonus: Double = 90.0,
    val preferRemoteLocalPenalty: Double = -15.0,
    val localAllowedLocalBonus: Double = 60.0,
    val localAllowedRemoteBonus: Double = 15.0,
    val lightLocalBonus: Double = 25.0,
    val lightRemoteBonus: Double = 5.0,
    val mediumLocalBonus: Double = 12.0,
    val mediumRemoteBonus: Double = 25.0,
    val heavyLocalBonus: Double = -20.0,
    val heavyRemoteBonus: Double = 55.0,
    val consolidationRemoteBonus: Double = 20.0,
    val consolidationRemoteMinRamGb: Double = 1.0,
    val historySuccessRateWeight: Double = 35.0,
    val historyFailurePenalty: Double = 8.0,
    val historyDurationBonus: Double = 30.0,
    val historyDurationScaleMs: Double = 50.0
) {
    fun validate() {
        listOf(
            cpuCoreWeight,
            cpuHeadroomPercentWeight,
            ramAvailableGbWeight,
            storageFreeGbWeight,
            activeTaskPenalty,
            gpuVramFreeGbWeight,
            gpuHeadroomPercentWeight,
            brainReadyBonus,
            brainLoadedBonus,
            brainTokensPerSecondWeight,
            preferredNodeHintBonus,
            preferRemoteRemoteBonus,
            preferRemoteLocalPenalty,
            localAllowedLocalBonus,
            localAllowedRemoteBonus,
            lightLocalBonus,
            lightRemoteBonus,
            mediumLocalBonus,
            mediumRemoteBonus,
            heavyLocalBonus,
            heavyRemoteBonus,
            consolidationRemoteBonus,
            historySuccessRateWeight,
            historyFailurePenalty,
            historyDurationBonus
        ).forEach { require(it.isFinite()) }
        require(consolidationRemoteMinRamGb >= 0.0 && consolidationRemoteMinRamGb.isFinite())
        require(historyDurationScaleMs > 0.0 && historyDurationScaleMs.isFinite())
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("cpu_core_weight", cpuCoreWeight)
        put("cpu_headroom_percent_weight", cpuHeadroomPercentWeight)
        put("ram_available_gb_weight", ramAvailableGbWeight)
        put("storage_free_gb_weight", storageFreeGbWeight)
        put("active_task_penalty", activeTaskPenalty)
        put("gpu_vram_free_gb_weight", gpuVramFreeGbWeight)
        put("gpu_headroom_percent_weight", gpuHeadroomPercentWeight)
        put("brain_ready_bonus", brainReadyBonus)
        put("brain_loaded_bonus", brainLoadedBonus)
        put("brain_tokens_per_second_weight", brainTokensPerSecondWeight)
        put("preferred_node_hint_bonus", preferredNodeHintBonus)
        put("prefer_remote_remote_bonus", preferRemoteRemoteBonus)
        put("prefer_remote_local_penalty", preferRemoteLocalPenalty)
        put("local_allowed_local_bonus", localAllowedLocalBonus)
        put("local_allowed_remote_bonus", localAllowedRemoteBonus)
        put("light_local_bonus", lightLocalBonus)
        put("light_remote_bonus", lightRemoteBonus)
        put("medium_local_bonus", mediumLocalBonus)
        put("medium_remote_bonus", mediumRemoteBonus)
        put("heavy_local_bonus", heavyLocalBonus)
        put("heavy_remote_bonus", heavyRemoteBonus)
        put("consolidation_remote_bonus", consolidationRemoteBonus)
        put("consolidation_remote_min_ram_gb", consolidationRemoteMinRamGb)
        put("history_success_rate_weight", historySuccessRateWeight)
        put("history_failure_penalty", historyFailurePenalty)
        put("history_duration_bonus", historyDurationBonus)
        put("history_duration_scale_ms", historyDurationScaleMs)
    }

    companion object {
        fun fromJson(json: JSONObject): RoutingTuning {
            val defaults = RoutingTuning()
            fun value(name: String, fallback: Double): Double =
                finiteDouble(json, name, fallback)
            return RoutingTuning(
                cpuCoreWeight = value("cpu_core_weight", defaults.cpuCoreWeight),
                cpuHeadroomPercentWeight = value(
                    "cpu_headroom_percent_weight",
                    defaults.cpuHeadroomPercentWeight
                ),
                ramAvailableGbWeight = value(
                    "ram_available_gb_weight",
                    defaults.ramAvailableGbWeight
                ),
                storageFreeGbWeight = value(
                    "storage_free_gb_weight",
                    defaults.storageFreeGbWeight
                ),
                activeTaskPenalty = value(
                    "active_task_penalty",
                    defaults.activeTaskPenalty
                ),
                gpuVramFreeGbWeight = value(
                    "gpu_vram_free_gb_weight",
                    defaults.gpuVramFreeGbWeight
                ),
                gpuHeadroomPercentWeight = value(
                    "gpu_headroom_percent_weight",
                    defaults.gpuHeadroomPercentWeight
                ),
                brainReadyBonus = value(
                    "brain_ready_bonus",
                    defaults.brainReadyBonus
                ),
                brainLoadedBonus = value(
                    "brain_loaded_bonus",
                    defaults.brainLoadedBonus
                ),
                brainTokensPerSecondWeight = value(
                    "brain_tokens_per_second_weight",
                    defaults.brainTokensPerSecondWeight
                ),
                preferredNodeHintBonus = value(
                    "preferred_node_hint_bonus",
                    defaults.preferredNodeHintBonus
                ),
                preferRemoteRemoteBonus = value(
                    "prefer_remote_remote_bonus",
                    defaults.preferRemoteRemoteBonus
                ),
                preferRemoteLocalPenalty = value(
                    "prefer_remote_local_penalty",
                    defaults.preferRemoteLocalPenalty
                ),
                localAllowedLocalBonus = value(
                    "local_allowed_local_bonus",
                    defaults.localAllowedLocalBonus
                ),
                localAllowedRemoteBonus = value(
                    "local_allowed_remote_bonus",
                    defaults.localAllowedRemoteBonus
                ),
                lightLocalBonus = value("light_local_bonus", defaults.lightLocalBonus),
                lightRemoteBonus = value("light_remote_bonus", defaults.lightRemoteBonus),
                mediumLocalBonus = value("medium_local_bonus", defaults.mediumLocalBonus),
                mediumRemoteBonus = value("medium_remote_bonus", defaults.mediumRemoteBonus),
                heavyLocalBonus = value("heavy_local_bonus", defaults.heavyLocalBonus),
                heavyRemoteBonus = value("heavy_remote_bonus", defaults.heavyRemoteBonus),
                consolidationRemoteBonus = value(
                    "consolidation_remote_bonus",
                    defaults.consolidationRemoteBonus
                ),
                consolidationRemoteMinRamGb = value(
                    "consolidation_remote_min_ram_gb",
                    defaults.consolidationRemoteMinRamGb
                ),
                historySuccessRateWeight = value(
                    "history_success_rate_weight",
                    defaults.historySuccessRateWeight
                ),
                historyFailurePenalty = value(
                    "history_failure_penalty",
                    defaults.historyFailurePenalty
                ),
                historyDurationBonus = value(
                    "history_duration_bonus",
                    defaults.historyDurationBonus
                ),
                historyDurationScaleMs = value(
                    "history_duration_scale_ms",
                    defaults.historyDurationScaleMs
                )
            ).also { it.validate() }
        }
    }
}

data class TaskTuning(
    val probeIterations: Int = 20_000,
    val memoryItems: Int = 24,
    val textHeavyThresholdChars: Int = 4_000,
    val consolidationHeavyItemThreshold: Int = 12,
    val consolidationHeavyPayloadChars: Int = 12_000,
    val routingHistoryLimit: Int = 40
) {
    fun validate() {
        require(probeIterations >= 1)
        require(memoryItems >= 1)
        require(textHeavyThresholdChars >= 1)
        require(consolidationHeavyItemThreshold >= 1)
        require(consolidationHeavyPayloadChars >= 1)
        require(routingHistoryLimit >= 1)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("probe_iterations", probeIterations)
        put("memory_items", memoryItems)
        put("text_heavy_threshold_chars", textHeavyThresholdChars)
        put("consolidation_heavy_item_threshold", consolidationHeavyItemThreshold)
        put("consolidation_heavy_payload_chars", consolidationHeavyPayloadChars)
        put("routing_history_limit", routingHistoryLimit)
    }

    companion object {
        fun fromJson(json: JSONObject): TaskTuning {
            val defaults = TaskTuning()
            return TaskTuning(
                probeIterations = json.optInt("probe_iterations", defaults.probeIterations),
                memoryItems = json.optInt("memory_items", defaults.memoryItems),
                textHeavyThresholdChars = json.optInt(
                    "text_heavy_threshold_chars",
                    defaults.textHeavyThresholdChars
                ),
                consolidationHeavyItemThreshold = json.optInt(
                    "consolidation_heavy_item_threshold",
                    defaults.consolidationHeavyItemThreshold
                ),
                consolidationHeavyPayloadChars = json.optInt(
                    "consolidation_heavy_payload_chars",
                    defaults.consolidationHeavyPayloadChars
                ),
                routingHistoryLimit = json.optInt(
                    "routing_history_limit",
                    defaults.routingHistoryLimit
                )
            ).also { it.validate() }
        }
    }
}

data class RetentionTuning(
    val taskHistoryMaxItems: Int = 40,
    val queueMaxItems: Int = 30,
    val cognitiveEventsMaxItems: Int = 100,
    val ephemeralMemoryRetentionDays: Int = 90,
    val supersededMemoryRetentionDays: Int = 30,
    val memoryRecallProtectionCount: Int = 2,
    val memoryLowConfidenceThreshold: Double = 0.60,
    val memoryPurgeBatchSize: Int = 50
) {
    fun validate() {
        require(taskHistoryMaxItems >= 1)
        require(queueMaxItems >= 1)
        require(cognitiveEventsMaxItems >= 1)
        require(ephemeralMemoryRetentionDays >= 1)
        require(supersededMemoryRetentionDays >= 1)
        require(memoryRecallProtectionCount >= 0)
        requireRatio(memoryLowConfidenceThreshold, "memoryLowConfidenceThreshold")
        require(memoryPurgeBatchSize >= 1)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("task_history_max_items", taskHistoryMaxItems)
        put("queue_max_items", queueMaxItems)
        put("cognitive_events_max_items", cognitiveEventsMaxItems)
        put("ephemeral_memory_retention_days", ephemeralMemoryRetentionDays)
        put("superseded_memory_retention_days", supersededMemoryRetentionDays)
        put("memory_recall_protection_count", memoryRecallProtectionCount)
        put("memory_low_confidence_threshold", memoryLowConfidenceThreshold)
        put("memory_purge_batch_size", memoryPurgeBatchSize)
    }

    companion object {
        fun fromJson(json: JSONObject): RetentionTuning {
            val defaults = RetentionTuning()
            return RetentionTuning(
                taskHistoryMaxItems = json.optInt(
                    "task_history_max_items",
                    defaults.taskHistoryMaxItems
                ),
                queueMaxItems = json.optInt("queue_max_items", defaults.queueMaxItems),
                cognitiveEventsMaxItems = json.optInt(
                    "cognitive_events_max_items",
                    defaults.cognitiveEventsMaxItems
                ),
                ephemeralMemoryRetentionDays = json.optInt(
                    "ephemeral_memory_retention_days",
                    defaults.ephemeralMemoryRetentionDays
                ),
                supersededMemoryRetentionDays = json.optInt(
                    "superseded_memory_retention_days",
                    defaults.supersededMemoryRetentionDays
                ),
                memoryRecallProtectionCount = json.optInt(
                    "memory_recall_protection_count",
                    defaults.memoryRecallProtectionCount
                ),
                memoryLowConfidenceThreshold = finiteDouble(
                    json,
                    "memory_low_confidence_threshold",
                    defaults.memoryLowConfidenceThreshold
                ),
                memoryPurgeBatchSize = json.optInt(
                    "memory_purge_batch_size",
                    defaults.memoryPurgeBatchSize
                )
            ).also { it.validate() }
        }
    }
}

private fun requireRatio(value: Double, name: String) {
    require(value.isFinite() && value in 0.0..1.0) { "$name invalide." }
}

private fun requireFraction(value: Double, name: String) {
    require(value.isFinite() && value > 0.0 && value <= 1.0) {
        "$name invalide."
    }
}

private fun finiteDouble(
    json: JSONObject,
    key: String,
    fallback: Double
): Double {
    val safeFallback = fallback.takeIf { it.isFinite() } ?: 0.0
    return json.optDouble(key, safeFallback)
        .takeIf { it.isFinite() }
        ?: safeFallback
}
