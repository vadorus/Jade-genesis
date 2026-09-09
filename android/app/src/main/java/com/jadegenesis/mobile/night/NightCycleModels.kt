package com.jadegenesis.mobile.night

import com.jadegenesis.mobile.config.SafetyPolicy

enum class NightCycleStatus {
    SKIPPED,
    SUCCESS,
    PARTIAL,
    FAILED
}

enum class NightCyclePhase {
    PREPARE,
    SYNC_BEFORE,
    MEMORY_CONSOLIDATION,
    RUNTIME_EVAL_REVIEW,
    EVOLUTION_REVIEW,
    SYNC_AFTER,
    COMPLETE
}

data class NightCycleStep(
    val phase: NightCyclePhase,
    val success: Boolean,
    val summary: String,
    val durationMs: Long
)

data class NightCycleRun(
    val runId: String,
    val status: NightCycleStatus,
    val startedAt: Long,
    val completedAt: Long,
    val memoryBatchesProcessed: Int,
    val runtimeObservationCount: Int,
    val runtimeScore: Double,
    val runtimeConfidence: Double,
    val evolutionCandidateCount: Int,
    val evolutionValidatedCount: Int,
    val steps: List<NightCycleStep>,
    val error: String? = null
)

object NightCyclePolicy {
    fun shouldRun(
        lastSuccessfulCompletedAt: Long,
        now: Long
    ): Boolean {
        if (lastSuccessfulCompletedAt <= 0L) return true
        if (now <= lastSuccessfulCompletedAt) return false
        val minimumGapMs = SafetyPolicy.MIN_NIGHT_CYCLE_INTERVAL_HOURS * 60L * 60L * 1_000L
        return now - lastSuccessfulCompletedAt >= minimumGapMs
    }

    fun finalStatus(steps: List<NightCycleStep>): NightCycleStatus {
        if (steps.isEmpty()) return NightCycleStatus.FAILED
        val prepare = steps.firstOrNull { it.phase == NightCyclePhase.PREPARE }
        if (prepare?.success != true) return NightCycleStatus.FAILED
        return if (steps.all { it.success }) {
            NightCycleStatus.SUCCESS
        } else {
            NightCycleStatus.PARTIAL
        }
    }
}
