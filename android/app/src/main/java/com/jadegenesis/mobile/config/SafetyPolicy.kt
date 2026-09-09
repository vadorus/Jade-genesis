package com.jadegenesis.mobile.config

/**
 * Limites de sécurité non évolutives.
 *
 * JadeConfig peut être ajustée et, plus tard, faire l'objet d'expériences
 * champion/challenger. Ces limites restent compilées afin qu'une configuration
 * candidate ne puisse pas assouplir seule les garde-fous système.
 */
object SafetyPolicy {
    const val MAX_TEXT_CHARS = 12_000
    const val MAX_MEMORY_PAYLOAD_CHARS = 48_000
    const val MAX_PROBE_ITERATIONS = 100_000
    const val MAX_MEMORY_ITEMS_PER_TASK = 64
    const val MAX_ROUTING_HISTORY_ITEMS = 200
    const val MAX_TASK_HISTORY_ITEMS = 200
    const val MAX_QUEUE_ITEMS = 200
    const val MAX_COGNITIVE_EVENTS = 500

    const val MAX_PARALLEL_TASKS = 3
    const val MAX_TASK_SLICE_SECONDS = 60
    const val MIN_WORKING_SET_MB = 8

    const val MAX_SYSTEM_BUDGET_FRACTION = 0.18
    const val MAX_HEAP_BUDGET_FRACTION = 0.50
    const val MAX_APP_CLASS_BUDGET_FRACTION = 0.50

    const val MIN_SYSTEM_RAM_RESERVE_GB = 1.0
    const val MAX_SYSTEM_RAM_RESERVE_GB = 3.0

    const val MEMORY_CRITICAL_THRESHOLD_MULTIPLIER = 1.10
    const val FALLBACK_CRITICAL_RAM_RATIO = 0.05
    const val CRITICAL_HEAP_RATIO = 0.90
    const val CRITICAL_THERMAL_RANK = 3
    const val CRITICAL_BATTERY_PERCENT = 8
    const val CRITICAL_STORAGE_FREE_GB = 0.75

    // Une configuration candidate ne peut pas provoquer une purge agressive.
    const val MIN_EPHEMERAL_MEMORY_RETENTION_DAYS = 14
    const val MIN_SUPERSEDED_MEMORY_RETENTION_DAYS = 7
    const val MAX_MEMORY_PURGE_BATCH_SIZE = 100
    const val MAX_AUTO_DELETE_CONFIDENCE = 0.70
    const val MIN_RECALL_PROTECTION_COUNT = 1
}
