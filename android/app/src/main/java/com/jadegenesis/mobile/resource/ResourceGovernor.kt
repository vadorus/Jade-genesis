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
        val batteryKnown = device.batteryPercent in 0..100
        val memoryThresholdKnown = device.ramLowThresholdGb > 0.0

        val memoryCritical =
            device.ramLow ||
                (
                    memoryThresholdKnown &&
                        device.ramAvailableGb <= device.ramLowThresholdGb * 1.10
                    ) ||
                (
                    !memoryThresholdKnown &&
                        ramRatio <= 0.05
                    )

        val memoryEco =
            !memoryCritical &&
                (
                    (
                        memoryThresholdKnown &&
                            device.ramAvailableGb <= device.ramLowThresholdGb * 1.75
                        ) ||
                        ramRatio <= 0.10
                    )

        val storageLowThresholdGb =
            max(1.0, min(4.0, device.storageTotalGb * 0.01))

        val critical =
            memoryCritical ||
                heapRatio >= 0.90 ||
                thermalRank >= 3 ||
                (
                    batteryKnown &&
                        !device.charging &&
                        device.batteryPercent <= 8
                    ) ||
                device.storageFreeGb < 0.75

        val eco =
            memoryEco ||
                device.powerSaveMode ||
                heapRatio >= 0.75 ||
                thermalRank >= 2 ||
                (
                    batteryKnown &&
                        !device.charging &&
                        device.batteryPercent <= 25
                    ) ||
                device.storageFreeGb < storageLowThresholdGb

        val healthyMemoryForPerformance = if (memoryThresholdKnown) {
            device.ramAvailableGb >= max(
                device.ramTotalGb * 0.25,
                device.ramLowThresholdGb * 2.5
            )
        } else {
            ramRatio >= 0.35
        }

        val performance =
            device.charging &&
                batteryKnown &&
                device.batteryPercent >= 60 &&
                healthyMemoryForPerformance &&
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
            memoryCritical = memoryCritical,
            memoryEco = memoryEco,
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

        val thresholdReserveGb = if (memoryThresholdKnown) {
            device.ramLowThresholdGb * 1.75
        } else {
            0.0
        }
        val reserveGb = round(
            max(
                1.0,
                max(
                    device.ramTotalGb * 0.12,
                    thresholdReserveGb
                )
            )
                .coerceAtMost(3.0) * 100.0
        ) / 100.0

        val safelyAvailableGb =
            max(0.0, device.ramAvailableGb - reserveGb)
        val systemBudgetMb =
            safelyAvailableGb * 1024.0 * systemFraction

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
        memoryCritical: Boolean,
        memoryEco: Boolean,
        mode: ResourceMode
    ): List<String> {
        val reasons = mutableListOf<String>()
        val ramPercent = (ramRatio * 100.0).roundToInt()
        val memoryThresholdKnown = device.ramLowThresholdGb > 0.0

        if (device.ramLow) {
            reasons +=
                "Android signale officiellement une pression mémoire système."
        } else if (memoryCritical && memoryThresholdKnown) {
            reasons +=
                "RAM au seuil critique Android : ${device.ramAvailableGb} Go libres, " +
                    "seuil système ${device.ramLowThresholdGb} Go."
        } else if (memoryEco && memoryThresholdKnown) {
            reasons +=
                "Marge mémoire réduite : ${device.ramAvailableGb} Go libres " +
                    "($ramPercent%), seuil critique Android ${device.ramLowThresholdGb} Go."
        } else if (ramRatio <= 0.10) {
            reasons +=
                "RAM disponible faible : ${device.ramAvailableGb} Go ($ramPercent%). " +
                    "Android ne signale pas encore de pression mémoire critique."
        }

        if (heapRatio >= 0.75) {
            reasons +=
                "Le tas mémoire de Jade est déjà fortement utilisé : " +
                    "${(heapRatio * 100.0).roundToInt()}%."
        }

        if (
            device.batteryPercent in 0..25 &&
            !device.charging
        ) {
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
