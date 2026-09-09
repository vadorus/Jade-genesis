package com.jadegenesis.mobile.night

import android.content.Context
import com.jadegenesis.mobile.config.SafetyPolicy
import org.json.JSONArray
import org.json.JSONObject

class NightCycleStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val lock = Any()

    fun recent(limit: Int = 10): List<NightCycleRun> = synchronized(lock) {
        val safeLimit = limit.coerceIn(0, SafetyPolicy.MAX_NIGHT_CYCLE_RUNS)
        if (safeLimit == 0) return@synchronized emptyList()
        loadUnsafe().take(safeLimit)
    }

    fun lastProtectedCompletedAt(): Long = synchronized(lock) {
        loadUnsafe()
            .firstOrNull {
                it.status == NightCycleStatus.SUCCESS ||
                    it.status == NightCycleStatus.PARTIAL
            }
            ?.completedAt
            ?: 0L
    }

    fun save(run: NightCycleRun) = synchronized(lock) {
        validate(run)
        val runs = loadUnsafe().toMutableList()
        runs.removeAll { it.runId == run.runId }
        runs.add(0, run)
        saveUnsafe(runs.take(SafetyPolicy.MAX_NIGHT_CYCLE_RUNS))
    }

    fun count(): Int = synchronized(lock) { loadUnsafe().size }

    private fun loadUnsafe(): List<NightCycleRun> {
        val primary = prefs.getString(KEY_RUNS, null)
        if (!primary.isNullOrBlank()) {
            decode(primary)?.let { return it }
        }

        val backup = prefs.getString(KEY_RUNS_BACKUP, null)
        if (!backup.isNullOrBlank()) {
            decode(backup)?.let { recovered ->
                prefs.edit()
                    .putString(KEY_RUNS, encode(recovered))
                    .apply()
                return recovered
            }
        }
        return emptyList()
    }

    private fun saveUnsafe(runs: List<NightCycleRun>) {
        val encoded = encode(runs)
        val previous = prefs.getString(KEY_RUNS, null)
        val editor = prefs.edit()
        if (!previous.isNullOrBlank()) {
            editor.putString(KEY_RUNS_BACKUP, previous)
        }
        editor.putString(KEY_RUNS, encoded).apply()
    }

    private fun encode(runs: List<NightCycleRun>): String = JSONArray().apply {
        runs.forEach { run ->
            put(
                JSONObject().apply {
                    put("schema_version", SCHEMA_VERSION)
                    put("run_id", run.runId)
                    put("status", run.status.name)
                    put("started_at", run.startedAt)
                    put("completed_at", run.completedAt)
                    put("memory_batches_processed", run.memoryBatchesProcessed)
                    put("runtime_observation_count", run.runtimeObservationCount)
                    put("runtime_score", run.runtimeScore)
                    put("runtime_confidence", run.runtimeConfidence)
                    put("evolution_candidate_count", run.evolutionCandidateCount)
                    put("evolution_validated_count", run.evolutionValidatedCount)
                    put("error", run.error ?: "")
                    put(
                        "steps",
                        JSONArray().apply {
                            run.steps.forEach { step ->
                                put(
                                    JSONObject().apply {
                                        put("phase", step.phase.name)
                                        put("success", step.success)
                                        put(
                                            "summary",
                                            step.summary.take(
                                                SafetyPolicy.MAX_NIGHT_CYCLE_STEP_SUMMARY_CHARS
                                            )
                                        )
                                        put("duration_ms", step.durationMs)
                                    }
                                )
                            }
                        }
                    )
                }
            )
        }
    }.toString()

    private fun decode(raw: String): List<NightCycleRun>? = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val json = array.getJSONObject(index)
                if (json.optInt("schema_version", SCHEMA_VERSION) != SCHEMA_VERSION) {
                    continue
                }
                val stepsJson = json.optJSONArray("steps") ?: JSONArray()
                val steps = buildList {
                    for (stepIndex in 0 until stepsJson.length()) {
                        val stepJson = stepsJson.getJSONObject(stepIndex)
                        val phase = runCatching {
                            NightCyclePhase.valueOf(stepJson.optString("phase"))
                        }.getOrNull() ?: continue
                        add(
                            NightCycleStep(
                                phase = phase,
                                success = stepJson.optBoolean("success", false),
                                summary = stepJson.optString("summary")
                                    .take(SafetyPolicy.MAX_NIGHT_CYCLE_STEP_SUMMARY_CHARS),
                                durationMs = stepJson.optLong("duration_ms", 0L)
                                    .coerceAtLeast(0L)
                            )
                        )
                    }
                }
                val status = runCatching {
                    NightCycleStatus.valueOf(json.optString("status"))
                }.getOrNull() ?: continue
                val run = NightCycleRun(
                    runId = json.optString("run_id"),
                    status = status,
                    startedAt = json.optLong("started_at", 0L).coerceAtLeast(0L),
                    completedAt = json.optLong("completed_at", 0L).coerceAtLeast(0L),
                    memoryBatchesProcessed = json.optInt("memory_batches_processed", 0)
                        .coerceAtLeast(0),
                    runtimeObservationCount = json.optInt("runtime_observation_count", 0)
                        .coerceAtLeast(0),
                    runtimeScore = finiteDouble(json, "runtime_score").coerceIn(0.0, 100.0),
                    runtimeConfidence = finiteDouble(json, "runtime_confidence")
                        .coerceIn(0.0, 1.0),
                    evolutionCandidateCount = json.optInt("evolution_candidate_count", 0)
                        .coerceAtLeast(0),
                    evolutionValidatedCount = json.optInt("evolution_validated_count", 0)
                        .coerceAtLeast(0),
                    steps = steps,
                    error = json.optString("error").takeIf { it.isNotBlank() }
                )
                runCatching { validate(run) }
                    .onSuccess { add(run) }
            }
        }
    }.getOrNull()

    private fun validate(run: NightCycleRun) {
        require(run.runId.isNotBlank())
        require(run.startedAt >= 0L)
        require(run.completedAt >= run.startedAt)
        require(run.memoryBatchesProcessed in 0..SafetyPolicy.MAX_NIGHT_MEMORY_BATCHES)
        require(run.runtimeObservationCount >= 0)
        require(run.runtimeScore.isFinite() && run.runtimeScore in 0.0..100.0)
        require(run.runtimeConfidence.isFinite() && run.runtimeConfidence in 0.0..1.0)
        require(run.evolutionCandidateCount >= 0)
        require(run.evolutionValidatedCount >= 0)
        require(run.steps.size <= SafetyPolicy.MAX_NIGHT_CYCLE_STEPS)
        run.steps.forEach { step ->
            require(step.durationMs >= 0L)
            require(step.summary.length <= SafetyPolicy.MAX_NIGHT_CYCLE_STEP_SUMMARY_CHARS)
        }
    }

    private fun finiteDouble(json: JSONObject, key: String): Double {
        val value = json.optDouble(key, 0.0)
        return if (value.isFinite()) value else 0.0
    }

    companion object {
        private const val PREFS_NAME = "jade_night_cycle"
        private const val KEY_RUNS = "runs_v1"
        private const val KEY_RUNS_BACKUP = "runs_v1_backup"
        private const val SCHEMA_VERSION = 1
    }
}
