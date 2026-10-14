package com.jadegenesis.mobile.device

import android.os.PowerManager

internal object ThermalStatusReader {
    fun read(reader: () -> Int): String = try {
        when (reader()) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN"
        }
    } catch (_: RuntimeException) {
        // Sensor/API failure is unknown telemetry, never an invented cool value.
        "UNKNOWN"
    }
}
