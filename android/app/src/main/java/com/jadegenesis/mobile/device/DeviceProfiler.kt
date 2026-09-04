package com.jadegenesis.mobile.device

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import com.jadegenesis.mobile.model.DeviceProfile
import kotlin.math.round

class DeviceProfiler(private val context: Context) {

    fun capture(): DeviceProfile {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo().also {
            activityManager.getMemoryInfo(it)
        }

        val runtime = Runtime.getRuntime()
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val powerManager = context.getSystemService(PowerManager::class.java)

        val batteryPercent =
            batteryManager
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                .coerceIn(0, 100)

        return DeviceProfile(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            androidVersion = Build.VERSION.RELEASE ?: "Unknown",
            sdkInt = Build.VERSION.SDK_INT,
            socManufacturer = Build.SOC_MANUFACTURER.ifBlank { "Unknown" },
            socModel = Build.SOC_MODEL.ifBlank { "Unknown" },
            abis = Build.SUPPORTED_ABIS.toList(),
            cpuCores = runtime.availableProcessors(),
            ramTotalGb = bytesToGb(memoryInfo.totalMem),
            ramAvailableGb = bytesToGb(memoryInfo.availMem),
            ramLow = memoryInfo.lowMemory,
            appMemoryClassMb = activityManager.memoryClass,
            processHeapUsedMb = bytesToMb(
                runtime.totalMemory() - runtime.freeMemory()
            ),
            processHeapMaxMb = bytesToMb(runtime.maxMemory()),
            storageTotalGb = bytesToGb(stat.totalBytes),
            storageFreeGb = bytesToGb(stat.availableBytes),
            batteryPercent = batteryPercent,
            charging = batteryManager.isCharging,
            powerSaveMode = powerManager.isPowerSaveMode,
            deviceIdleMode = powerManager.isDeviceIdleMode,
            thermalStatus = thermalStatusName(powerManager.currentThermalStatus),
            capturedAt = System.currentTimeMillis()
        )
    }

    private fun bytesToGb(bytes: Long): Double =
        round((bytes.toDouble() / 1_073_741_824.0) * 100.0) / 100.0

    private fun bytesToMb(bytes: Long): Double =
        round((bytes.toDouble() / 1_048_576.0) * 10.0) / 10.0

    private fun thermalStatusName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN"
    }

    fun nodeId(): String =
        "pixel-${Build.MODEL.lowercase().replace(" ", "-")}-${Build.DEVICE}"
}
