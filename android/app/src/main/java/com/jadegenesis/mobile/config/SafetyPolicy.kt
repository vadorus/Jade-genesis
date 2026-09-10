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
    const val MAX_ADAPTIVE_BRAIN_ROUTING_ADJUSTMENT = 40.0

    // Outcome Quality : chaque réponse générative ne peut contribuer qu'un seul
    // retour utilisateur actif. Un petit nombre de retours ne doit jamais
    // renverser à lui seul le prior matériel et opérationnel.
    const val MAX_RUNTIME_EVAL_OUTCOME_FEEDBACK = 200
    const val MIN_RUNTIME_EVAL_OUTCOME_SAMPLES = 3
    const val STRONG_RUNTIME_EVAL_OUTCOME_SAMPLES = 8
    const val MAX_RUNTIME_EVAL_OUTCOME_ADJUSTMENT = 12.0

    // Conversation Learning : état local borné, utilisé uniquement comme
    // contexte et expérience. Les retours utilisateur ne deviennent jamais
    // automatiquement des faits externes vérifiés ni une mutation de modèle.
    // Le contexte conversationnel a son propre petit quota et ne doit jamais
    // évincer la mémoire principale préparée par JadeCore.
    const val MAX_CONVERSATION_LEARNING_TURNS = 12
    const val MAX_CONVERSATION_LEARNING_OUTCOMES = 80
    const val MAX_CONVERSATION_LEARNING_TOPICS = 80
    const val MAX_CONVERSATION_LEARNING_TOPICS_PER_TURN = 6
    const val MAX_CONVERSATION_LEARNING_CONTEXT_TURNS = 2
    const val MAX_CONVERSATION_LEARNING_CONTEXT_ITEMS = 3
    const val MAX_CONVERSATION_LEARNING_TEXT_CHARS = 1_000

    // Memory Health : l'historique sert uniquement à mesurer la croissance du
    // stockage. Il ne déclenche jamais de purge automatique. 75 échantillons à
    // 12 h couvrent plus de 30 jours de tendance.
    const val MAX_MEMORY_HEALTH_SAMPLES = 75
    const val MIN_MEMORY_HEALTH_SAMPLE_INTERVAL_HOURS = 12

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

    // Night Learning Lab : les résultats remontés par le VPS restent des
    // propositions bornées et non exécutables. Le Pixel ne doit jamais accepter
    // un snapshot qui demande une promotion, une exécution d'expérience, une
    // réécriture de code ou une commande shell automatique.
    const val MAX_NIGHT_RESEARCH_QUESTIONS = 3
    const val MAX_NIGHT_RESEARCH_EVIDENCE = 6
    const val MAX_NIGHT_HYPOTHESES = 4
    const val MAX_NIGHT_EXPERIMENTS = 4
    const val MAX_NIGHT_IMPROVEMENT_CANDIDATES = 4
    const val MAX_NIGHT_LEARNING_TEXT_CHARS = 500

    // Strategy Registry : premier substrat adaptatif persistant propre à Jade.
    // Les candidats peuvent être persistés automatiquement, mais jamais promus
    // ni appliqués au routage automatiquement dans la 0.1.18. Une stratégie
    // validée exige un test sandbox et une quantité minimale de preuves.
    const val MAX_STRATEGY_REGISTRY_ENTRIES = 100
    const val MAX_STRATEGY_REGISTRY_SNAPSHOT_ENTRIES = 8
    const val MAX_STRATEGY_REGISTRY_EVALUATIONS_PER_ENTRY = 20
    const val MAX_STRATEGY_REGISTRY_TEXT_CHARS = 500
    const val MIN_STRATEGY_PROMOTION_SAMPLES = 5
    const val MIN_STRATEGY_PROMOTION_CONFIDENCE = 0.75

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
