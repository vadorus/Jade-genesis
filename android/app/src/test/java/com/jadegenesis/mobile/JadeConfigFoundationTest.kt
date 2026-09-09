package com.jadegenesis.mobile

import com.jadegenesis.mobile.config.JadeConfig
import com.jadegenesis.mobile.config.ResourceModeTuning
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.model.DeviceProfile
import com.jadegenesis.mobile.model.ResourceMode
import com.jadegenesis.mobile.resource.ResourceGovernor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JadeConfigFoundationTest {

    @Test
    fun defaultConfigPreservesCurrentRoutingAndRetentionValues() {
        val config = JadeConfig.defaults().validated()

        assertEquals(1, config.schemaVersion)
        assertEquals(1L, config.revision)
        assertEquals("builtin-1", config.configId)
        assertEquals(1.5, config.routing.cpuCoreWeight, 0.0001)
        assertEquals(4.0, config.routing.ramAvailableGbWeight, 0.0001)
        assertEquals(0.02, config.routing.storageFreeGbWeight, 0.0001)
        assertEquals(40, config.task.routingHistoryLimit)
        assertEquals(24, config.task.memoryItems)
        assertEquals(40, config.retention.taskHistoryMaxItems)
        assertEquals(30, config.retention.queueMaxItems)
        assertEquals(100, config.retention.cognitiveEventsMaxItems)
    }

    @Test
    fun resourceGovernorKeepsHardCriticalBatteryGuardOutsideJadeConfig() {
        val aggressive = JadeConfig.defaults().copy(
            resource = JadeConfig.defaults().resource.copy(
                ecoBatteryPercent = 0,
                performanceBatteryPercent = 0
            )
        )
        val budget = ResourceGovernor { aggressive }.evaluate(
            healthyDevice(
                batteryPercent = 5,
                charging = false
            )
        )

        assertEquals(ResourceMode.CRITICAL, budget.mode)
        assertTrue(budget.preferRemoteCompute)
        assertFalse(budget.heavyBackgroundWorkAllowed)
    }

    @Test
    fun resourceGovernorClampsEvolvablePerformanceBudgetToSafetyPolicy() {
        val defaults = JadeConfig.defaults()
        val unsafePerformance = ResourceModeTuning(
            systemFraction = 0.95,
            heapFraction = 0.95,
            appClassFraction = 0.95,
            maxParallelTasks = 99,
            heavyBackgroundWorkAllowed = true,
            preferRemoteCompute = false,
            maxTaskSliceSeconds = 999
        )
        val config = defaults.copy(
            resource = defaults.resource.copy(
                modeBudgets = defaults.resource.modeBudgets +
                    (ResourceMode.PERFORMANCE to unsafePerformance)
            )
        )

        val budget = ResourceGovernor { config }.evaluate(
            healthyDevice(
                batteryPercent = 90,
                charging = true,
                ramAvailableGb = 6.0,
                ramTotalGb = 8.0
            )
        )

        assertEquals(ResourceMode.PERFORMANCE, budget.mode)
        assertEquals(SafetyPolicy.MAX_PARALLEL_TASKS, budget.maxParallelTasks)
        assertEquals(SafetyPolicy.MAX_TASK_SLICE_SECONDS, budget.maxTaskSliceSeconds)
        assertTrue(budget.heavyBackgroundWorkAllowed)
    }

    private fun healthyDevice(
        batteryPercent: Int = 80,
        charging: Boolean = true,
        ramAvailableGb: Double = 6.0,
        ramTotalGb: Double = 8.0
    ): DeviceProfile = DeviceProfile(
        manufacturer = "Google",
        model = "Pixel Test",
        device = "pixel-test",
        androidVersion = "16",
        sdkInt = 37,
        socManufacturer = "Google",
        socModel = "Tensor Test",
        abis = listOf("arm64-v8a"),
        cpuCores = 8,
        ramTotalGb = ramTotalGb,
        ramAvailableGb = ramAvailableGb,
        ramLow = false,
        appMemoryClassMb = 512,
        processHeapUsedMb = 100.0,
        processHeapMaxMb = 512.0,
        storageTotalGb = 128.0,
        storageFreeGb = 64.0,
        batteryPercent = batteryPercent,
        charging = charging,
        powerSaveMode = false,
        deviceIdleMode = false,
        thermalStatus = "NONE",
        capturedAt = 1L,
        ramLowThresholdGb = 0.0
    )
}
