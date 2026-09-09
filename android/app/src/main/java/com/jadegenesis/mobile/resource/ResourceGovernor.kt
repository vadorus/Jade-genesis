package com.jadegenesis.mobile.resource

import com.jadegenesis.mobile.config.JadeConfig
import com.jadegenesis.mobile.config.JadeConfigRuntime
import com.jadegenesis.mobile.config.ResourceTuning
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.model.DeviceProfile
import com.jadegenesis.mobile.model.ResourceBudget
import com.jadegenesis.mobile.model.ResourceMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

class ResourceGovernor(
    private val configProvider: () -> JadeConfig = { JadeConfigRuntime.current() }
) {

    fun evaluate(device: DeviceProfile): ResourceBudget {
        val config = configProvider().validated()
        val tuning = config.resource

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
                        device.ramAvailableGb <=
                        device.ramLowThresholdGb *
                        SafetyPolicy.MEMORY_CRITICAL_THRESHOLD_MULTIPLIER
                    ) ||
                (
                    !memoryThresholdKnown &&
                        ramRatio <= SafetyPolicy.FALLBACK_CRITICAL_RAM_RATIO
                    )

        val memoryEco =
            !memoryCritical &&
                (
                    (
                        memoryThresholdKnown &&
                            device.ramAvailableGb <=
                            device.ramLowThresholdGb * tuning.memoryEcoThresholdMultiplier
                        ) ||
                        ramRatio <= tuning.fallbackEcoRamRatio
                    )

        val storageLowThresholdGb =
            max(
                tuning.storageLowMinGb,
                min(
                    tuning.storageLowMaxGb,
                    device.storageTotalGb * tuning.storageLowFraction
                )
            )

        val critical =
            memoryCritical ||
                heapRatio >= SafetyPolicy.CRITICAL_HEAP_RATIO ||
                thermalRank >= SafetyPolicy.CRITICAL_THERMAL_RANK ||
                (
                    batteryKnown &&
                        !device.charging &&
                        device.batteryPercent <= SafetyPolicy.CRITICAL_BATTERY_PERCENT
                    ) ||
                device.storageFreeGb < SafetyPolicy.CRITICAL_STORAGE_FREE_GB

        val eco =
            memoryEco ||
                device.powerSaveMode ||
                heapRatio >= tuning.ecoHeapRatio ||
                thermalRank >= tuning.ecoThermalRank ||
                (
                    batteryKnown &&
                        !device.charging &&
                        device.batteryPercent <= tuning.ecoBatteryPercent
                    ) ||
                device.storageFreeGb < storageLowThresholdGb

        val healthyMemoryForPerformance = if (memoryThresholdKnown) {
            device.ramAvailableGb >= max(
                device.ramTotalGb * tuning.performanceRamTotalRatio,
                device.ramLowThresholdGb * tuning.performanceThresholdMultiplier
            )
        } else {
            ramRatio >= tuning.fallbackPerformanceRamRatio
        }

        val performance =
            device.charging &&
                batteryKnown &&
                device.batteryPercent >= tuning.performanceBatteryPercent &&
                healthyMemoryForPerformance &&
                heapRatio < tuning.performanceHeapRatio &&
                thermalRank <= tuning.performanceThermalRank &&
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
            mode = mode,
            tuning = tuning
        )

        val modeTuning = tuning.mode(mode)
        val systemFraction = min(
            modeTuning.systemFraction,
            SafetyPolicy.MAX_SYSTEM_BUDGET_FRACTION
        )
        val heapFraction = min(
            modeTuning.heapFraction,
            SafetyPolicy.MAX_HEAP_BUDGET_FRACTION
        )
        val classFraction = min(
            modeTuning.appClassFraction,
            SafetyPolicy.MAX_APP_CLASS_BUDGET_FRACTION
        )

        val thresholdReserveGb = if (memoryThresholdKnown) {
            device.ramLowThresholdGb * tuning.reserveThresholdMultiplier
        } else {
            0.0
        }
        val reserveGb = round(
            max(
                SafetyPolicy.MIN_SYSTEM_RAM_RESERVE_GB,
                max(
                    device.ramTotalGb * tuning.reserveRamFraction,
                    thresholdReserveGb
                )
            )
                .coerceAtMost(SafetyPolicy.MAX_SYSTEM_RAM_RESERVE_GB) * 100.0
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
                .coerceAtLeast(SafetyPolicy.MIN_WORKING_SET_MB)

        return ResourceBudget(
            mode = mode,
            reasons = reasons,
            systemRamReserveGb = reserveGb,
            recommendedWorkingSetMb = recommendedWorkingSetMb,
            maxParallelTasks = modeTuning.maxParallelTasks.coerceIn(
                1,
                SafetyPolicy.MAX_PARALLEL_TASKS
            ),
            heavyBackgroundWorkAllowed =
                mode == ResourceMode.PERFORMANCE &&
                    modeTuning.heavyBackgroundWorkAllowed,
            preferRemoteCompute =
                mode == ResourceMode.CRITICAL ||
                    modeTuning.preferRemoteCompute,
            maxTaskSliceSeconds = modeTuning.maxTaskSliceSeconds.coerceIn(
                1,
                SafetyPolicy.MAX_TASK_SLICE_SECONDS
            ),
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
        mode: ResourceMode,
        tuning: ResourceTuning
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
        } else if (ramRatio <= tuning.fallbackEcoRamRatio) {
            reasons +=
                "RAM disponible faible : ${device.ramAvailableGb} Go ($ramPercent%). " +
                    "Android ne signale pas encore de pression mémoire critique."
        }

        if (heapRatio >= tuning.ecoHeapRatio) {
            reasons +=
                "Le tas mémoire de Jade est déjà fortement utilisé : " +
                    "${(heapRatio * 100.0).roundToInt()}%."
        }

        if (
            device.batteryPercent in 0..tuning.ecoBatteryPercent &&
            !device.charging
        ) {
            reasons +=
                "Batterie limitée (${device.batteryPercent}%) sans chargeur."
        }

        if (device.powerSaveMode) {
            reasons += "Le mode économie d'énergie Android est actif."
        }

        if (thermalRank >= tuning.ecoThermalRank) {
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
