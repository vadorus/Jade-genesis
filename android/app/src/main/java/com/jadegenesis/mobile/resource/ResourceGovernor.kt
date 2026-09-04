package com.jadegenesis.mobile.resource

import com.jadegenesis.mobile.model.DeviceProfile
import com.jadegenesis.mobile.model.ResourceBudget
import com.jadegenesis.mobile.model.ResourceMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

class ResourceGovernor {

    fun evaluate(device: DeviceProfile): ResourceBudget {
        val ramRatio = if (device.ramTotalGb > 0.0) {
            device.ramAvailableGb / device.ramTotalGb
        } else {
            0.0
        }

        val heapRatio = if (device.processHeapMaxMb > 0.0) {
            device.processHeapUsedMb / device.processHeapMaxMb
        } else {
            1.0
        }

        val thermalRank = thermalRank(device.thermalStatus)

        val storageLowThresholdGb =
            max(1.0, device.storageTotalGb * 0.02)

        val critical =
            device.ramLow ||
                ramRatio <= 0.10 ||
                heapRatio >= 0.90 ||
                thermalRank >= 3 ||
                (!device.charging && device.batteryPercent <= 8) ||
                device.storageFreeGb < 0.75

        val eco =
            device.powerSaveMode ||
                ramRatio <= 0.22 ||
                heapRatio >= 0.75 ||
                thermalRank >= 2 ||
                (!device.charging && device.batteryPercent <= 25) ||
                device.storageFreeGb < storageLowThresholdGb

        val performance =
            device.charging &&
                device.batteryPercent >= 60 &&
                ramRatio >= 0.35 &&
                heapRatio < 0.60 &&
                thermalRank <= 1 &&
                !device.powerSaveMode &&
                !device.ramLow

        val mode = when {
            critical -> ResourceMode.CRITICAL
            eco -> ResourceMode.ECO
            performance -> ResourceMode.PERFORMANCE
            else -> ResourceMode.BALANCED
        }

        val reasons = buildReasons(
            device = device,
            ramRatio = ramRatio,
            heapRatio = heapRatio,
            thermalRank = thermalRank,
            storageLowThresholdGb = storageLowThresholdGb,
            mode = mode
        )

        val systemFraction = when (mode) {
            ResourceMode.CRITICAL -> 0.04
            ResourceMode.ECO -> 0.08
            ResourceMode.BALANCED -> 0.12
            ResourceMode.PERFORMANCE -> 0.18
        }

        val heapFraction = when (mode) {
            ResourceMode.CRITICAL -> 0.15
            ResourceMode.ECO -> 0.25
            ResourceMode.BALANCED -> 0.35
            ResourceMode.PERFORMANCE -> 0.50
        }

        val classFraction = when (mode) {
            ResourceMode.CRITICAL -> 0.15
            ResourceMode.ECO -> 0.25
            ResourceMode.BALANCED -> 0.35
            ResourceMode.PERFORMANCE -> 0.50
        }

        val systemBudgetMb =
            device.ramAvailableGb * 1024.0 * systemFraction

        val heapHeadroomMb =
            max(0.0, device.processHeapMaxMb - device.processHeapUsedMb)

        val heapBudgetMb =
            heapHeadroomMb * heapFraction

        val appClassBudgetMb =
            device.appMemoryClassMb.toDouble() * classFraction

        val recommendedWorkingSetMb =
            min(
                systemBudgetMb,
                min(heapBudgetMb, appClassBudgetMb)
            )
                .roundToInt()
                .coerceAtLeast(8)

        val reserveGb =
            round(max(1.5, device.ramTotalGb * 0.25) * 100.0) / 100.0

        return ResourceBudget(
            mode = mode,
            reasons = reasons,
            systemRamReserveGb = reserveGb,
            recommendedWorkingSetMb = recommendedWorkingSetMb,
            maxParallelTasks = when (mode) {
                ResourceMode.CRITICAL -> 1
                ResourceMode.ECO -> 1
                ResourceMode.BALANCED -> 2
                ResourceMode.PERFORMANCE -> 3
            },
            heavyBackgroundWorkAllowed =
                mode == ResourceMode.PERFORMANCE,
            preferRemoteCompute =
                mode == ResourceMode.CRITICAL ||
                    mode == ResourceMode.ECO,
            maxTaskSliceSeconds = when (mode) {
                ResourceMode.CRITICAL -> 5
                ResourceMode.ECO -> 15
                ResourceMode.BALANCED -> 30
                ResourceMode.PERFORMANCE -> 60
            },
            evaluatedAt = System.currentTimeMillis()
        )
    }

    private fun buildReasons(
        device: DeviceProfile,
        ramRatio: Double,
        heapRatio: Double,
        thermalRank: Int,
        storageLowThresholdGb: Double,
        mode: ResourceMode
    ): List<String> {
        val reasons = mutableListOf<String>()

        if (device.ramLow) {
            reasons += "Android signale une pression mémoire système."
        }

        if (ramRatio <= 0.22) {
            reasons +=
                "RAM disponible faible : ${(ramRatio * 100.0).roundToInt()}%."
        }

        if (heapRatio >= 0.75) {
            reasons +=
                "Le tas mémoire de Jade est déjà fortement utilisé : " +
                    "${(heapRatio * 100.0).roundToInt()}%."
        }

        if (!device.charging && device.batteryPercent <= 25) {
            reasons +=
                "Batterie limitée (${device.batteryPercent}%) sans chargeur."
        }

        if (device.powerSaveMode) {
            reasons += "Le mode économie d'énergie Android est actif."
        }

        if (thermalRank >= 2) {
            reasons +=
                "Température à surveiller : ${device.thermalStatus}."
        }

        if (device.storageFreeGb < storageLowThresholdGb) {
            reasons +=
                "Stockage libre faible : ${device.storageFreeGb} Go."
        }

        if (device.deviceIdleMode) {
            reasons +=
                "Android est en mode idle : les tâches de fond doivent rester légères."
        }

        if (reasons.isEmpty()) {
            reasons += when (mode) {
                ResourceMode.CRITICAL ->
                    "Ressources critiques : priorité à la stabilité."
                ResourceMode.ECO ->
                    "Budget réduit pour préserver l'appareil."
                ResourceMode.BALANCED ->
                    "Ressources normales : exécution équilibrée."
                ResourceMode.PERFORMANCE ->
                    "Appareil en charge et ressources saines : budget augmenté."
            }
        }

        return reasons
    }

    private fun thermalRank(status: String): Int = when (status) {
        "NONE" -> 0
        "LIGHT" -> 1
        "MODERATE" -> 2
        "SEVERE" -> 3
        "CRITICAL" -> 4
        "EMERGENCY" -> 5
        "SHUTDOWN" -> 6
        else -> 2
    }
}
