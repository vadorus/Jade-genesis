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

    // Limites physiques du Resource Lease. Elles ne sont pas auto-évolutives.
    const val MIN_RESOURCE_LEASE_MEMORY_MB = 4
    const val MAX_RESOURCE_LEASE_MEMORY_MB = 512
    const val MAX_RESOURCE_LEASE_VRAM_GB = 64.0
    const val MIN_REMOTE_RAM_RESERVE_GB = 0.5
    const val REMOTE_RAM_RESERVE_FRACTION = 0.10
    const val REMOTE_CPU_ADMISSION_CEILING_PERCENT = 95.0
    const val REMOTE_GPU_ADMISSION_CEILING_PERCENT = 98.0
    const val MIN_BRAIN_VRAM_HEADROOM_GB = 0.5
    const val MIN_VISION_VRAM_HEADROOM_GB = 0.5

    // Runtime Eval : borne la mémoire du journal et impose assez de preuves
    // avant qu'un posterior mesuré influence fortement le routage.
    const val MAX_RUNTIME_EVAL_OBSERVATIONS = 500
    const val MAX_RUNTIME_EVAL_REPORT_WINDOW = 300
    const val MIN_RUNTIME_EVAL_POSTERIOR_SAMPLES = 4
    const val STRONG_RUNTIME_EVAL_POSTERIOR_SAMPLES = 12

    // Evolution Engine : aucun candidat ne peut augmenter seul ces limites.
    // Les essais doivent rester petits, appariés et suffisamment mesurés avant
    // qu'une promotion explicite puisse devenir possible.
    const val MAX_EVOLUTION_CANDIDATES = 40
    const val MAX_EVOLUTION_TRANSITIONS_PER_CANDIDATE = 20
    const val MAX_EVOLUTION_SANDBOX_NOTES = 16
    const val MAX_EVOLUTION_CONFIG_JSON_CHARS = 64_000
    const val MAX_EVOLUTION_CONFIG_CHANGED_FIELDS = 12
    const val MIN_EVOLUTION_TRIAL_SAMPLES = 12
    const val MIN_EVOLUTION_EVIDENCE_CONFIDENCE = 0.75
    const val MAX_EVOLUTION_SUCCESS_RATE_REGRESSION = 0.02
    const val MIN_EVOLUTION_SCORE_DELTA = 2.0

    // Night Cycle : cadence et volume maximum restent compilés. Une évolution
    // de configuration ne peut donc pas transformer une fenêtre nocturne en
    // boucle continue ni multiplier les consolidations sans borne.
    const val MAX_NIGHT_CYCLE_RUNS = 30
    const val MAX_NIGHT_CYCLE_STEPS = 12
    const val MAX_NIGHT_CYCLE_STEP_SUMMARY_CHARS = 500
    const val MAX_NIGHT_MEMORY_BATCHES = 3
    const val MIN_NIGHT_CYCLE_INTERVAL_HOURS = 20

    // Shared Genesis State : protège le téléphone et le VPS contre des lots
    // démesurés. Ces plafonds restent compilés et non auto-évolutifs.
    const val MAX_SHARED_STATE_OUTBOX_EVENTS = 200
    const val MAX_SHARED_STATE_CACHE_EVENTS = 500
    const val MAX_SHARED_STATE_SYNC_EVENTS = 200
    const val MAX_SHARED_STATE_SYNC_ROUNDS = 5
    const val MAX_SHARED_STATE_EVENT_PAYLOAD_CHARS = 64_000
    const val MAX_SHARED_STATE_SYNC_PAYLOAD_CHARS = 512_000
    const val MAX_SHARED_STATE_SYNC_RESPONSE_CHARS = 1_000_000
    const val MAX_SHARED_STATE_RUNTIME_GROUPS = 20

    // Une configuration candidate ne peut pas provoquer une purge agressive.
    const val MIN_EPHEMERAL_MEMORY_RETENTION_DAYS = 14
    const val MIN_SUPERSEDED_MEMORY_RETENTION_DAYS = 7
    const val MAX_MEMORY_PURGE_BATCH_SIZE = 100
    const val MAX_AUTO_DELETE_CONFIDENCE = 0.70
    const val MIN_RECALL_PROTECTION_COUNT = 1
}
