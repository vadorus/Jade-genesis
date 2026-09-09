package com.jadegenesis.mobile.state

import android.content.Context
import com.jadegenesis.mobile.config.JadeConfigRuntime
import com.jadegenesis.mobile.device.DeviceProfiler
import com.jadegenesis.mobile.diagnostics.DiagnosticLogger
import com.jadegenesis.mobile.identity.IdentityManager
import com.jadegenesis.mobile.memory.MemoryLifecycleManager
import com.jadegenesis.mobile.node.NodeManager
import com.jadegenesis.mobile.resource.ResourceGovernor
import org.json.JSONObject

class SharedGenesisStateBootstrapper(context: Context) {
    private val appContext = context.applicationContext
    private val profiler = DeviceProfiler(appContext)
    private val diagnostics = DiagnosticLogger(appContext)
    private val nodeManager = NodeManager(appContext, profiler, diagnostics)
    private val identityManager = IdentityManager(appContext)
    private val memoryLifecycle = MemoryLifecycleManager(appContext)
    private val stateStore = SharedGenesisStateStore(appContext)
    private val coordinator = SharedGenesisStateCoordinator(
        nodeManager = nodeManager,
        store = stateStore,
        logger = diagnostics
    )
    private val resourceGovernor = ResourceGovernor()

    suspend fun syncCurrentState(): SharedStateSyncResult {
        val identity = identityManager.loadOrCreate()
        val replicaId = profiler.nodeId()
        val device = profiler.capture()
        val budget = resourceGovernor.evaluate(device)
        val config = JadeConfigRuntime.current().validated()
        val cursor = memoryLifecycle.currentCursor()
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
                put("schema_version", config.schemaVersion)
                put("revision", config.revision)
                put("config_id", config.configId)
                put("parent_config_id", config.parentConfigId ?: "")
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
