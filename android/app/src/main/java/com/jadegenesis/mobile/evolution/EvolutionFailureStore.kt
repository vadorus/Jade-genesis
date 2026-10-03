package com.jadegenesis.mobile.evolution

import android.content.Context
import com.jadegenesis.mobile.config.SafetyPolicy
import org.json.JSONArray
import org.json.JSONObject

class EvolutionFailureStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val lock = Any()

    fun record(lesson: EvolutionFailureLesson) = synchronized(lock) {
        val current = loadUnsafe().filterNot { it.lessonId == lesson.lessonId }
        saveUnsafe((listOf(lesson) + current).take(SafetyPolicy.MAX_EVOLUTION_FAILURE_LESSONS))
    }

    fun recent(limit: Int = 40): List<EvolutionFailureLesson> = synchronized(lock) {
        loadUnsafe().take(limit.coerceIn(0, SafetyPolicy.MAX_EVOLUTION_FAILURE_LESSONS))
    }

    private fun loadUnsafe(): List<EvolutionFailureLesson> {
        val primary = prefs.getString(KEY_LEDGER, null)
        if (!primary.isNullOrBlank()) decode(primary)?.let { return it }
        val backup = prefs.getString(KEY_BACKUP, null)
        if (!backup.isNullOrBlank()) decode(backup)?.let { recovered ->
            prefs.edit().putString(KEY_LEDGER, encode(recovered)).apply()
            return recovered
        }
        return emptyList()
    }

    private fun saveUnsafe(items: List<EvolutionFailureLesson>) {
        val encoded = encode(items)
        val previous = prefs.getString(KEY_LEDGER, null)
        val editor = prefs.edit()
        if (!previous.isNullOrBlank()) editor.putString(KEY_BACKUP, previous)
        editor.putString(KEY_LEDGER, encoded).apply()
    }

    private fun encode(items: List<EvolutionFailureLesson>): String =
        JSONArray().apply { items.forEach { put(toJson(it)) } }.toString()

    private fun decode(raw: String): List<EvolutionFailureLesson>? = runCatching {
        val array = JSONArray(raw)
        buildList {
            val count = minOf(array.length(), SafetyPolicy.MAX_EVOLUTION_FAILURE_LESSONS)
            for (index in 0 until count) {
                runCatching { fromJson(array.getJSONObject(index)) }
                    .getOrNull()
                    ?.let(::add)
            }
        }
    }.getOrNull()

    private fun toJson(item: EvolutionFailureLesson): JSONObject = JSONObject().apply {
        put("schema_version", SCHEMA_VERSION)
        put("lesson_id", item.lessonId)
        put("candidate_id", item.candidateId)
        put("task_kind", item.taskKind ?: "")
        put("hypothesis_key", item.hypothesisKey ?: "")
        put("mutation_key", item.mutationKey ?: "")
        put("kind", item.kind.name)
        put("reason", item.reason)
        put("refuted_claim", item.refutedClaim)
        put("next_direction", item.nextDirection.name)
        put("baseline_samples", item.baselineSamples)
        put("challenger_samples", item.challengerSamples)
        put("baseline_success_rate", item.baselineSuccessRate)
        put("challenger_success_rate", item.challengerSuccessRate)
        put("baseline_average_duration_ms", item.baselineAverageDurationMs)
        put("challenger_average_duration_ms", item.challengerAverageDurationMs)
        put("baseline_score", item.baselineScore ?: JSONObject.NULL)
        put("challenger_score", item.challengerScore ?: JSONObject.NULL)
        put("created_at", item.createdAt)
    }

    private fun fromJson(json: JSONObject): EvolutionFailureLesson {
        require(json.optInt("schema_version", SCHEMA_VERSION) == SCHEMA_VERSION)
        return EvolutionFailureLesson(
            lessonId = json.getString("lesson_id"),
            candidateId = json.getString("candidate_id"),
            taskKind = json.optString("task_kind").takeIf { it.isNotBlank() },
            hypothesisKey = json.optString("hypothesis_key").takeIf { it.isNotBlank() },
            mutationKey = json.optString("mutation_key").takeIf { it.isNotBlank() },
            kind = runCatching {
                EvolutionFailureKind.valueOf(json.optString("kind"))
            }.getOrDefault(EvolutionFailureKind.OTHER),
            reason = json.optString("reason").take(500),
            refutedClaim = json.optString("refuted_claim").take(500),
            nextDirection = runCatching {
                EvolutionNextDirection.valueOf(json.optString("next_direction"))
            }.getOrDefault(EvolutionNextDirection.RESEARCH_BEFORE_RETRY),
            baselineSamples = json.optInt("baseline_samples", 0).coerceAtLeast(0),
            challengerSamples = json.optInt("challenger_samples", 0).coerceAtLeast(0),
            baselineSuccessRate = json.optDouble("baseline_success_rate", 0.0).coerceIn(0.0, 1.0),
            challengerSuccessRate = json.optDouble("challenger_success_rate", 0.0).coerceIn(0.0, 1.0),
            baselineAverageDurationMs = json.optDouble("baseline_average_duration_ms", 0.0).coerceAtLeast(0.0),
            challengerAverageDurationMs = json.optDouble("challenger_average_duration_ms", 0.0).coerceAtLeast(0.0),
            baselineScore = json.optDoubleOrNull("baseline_score"),
            challengerScore = json.optDoubleOrNull("challenger_score"),
            createdAt = json.optLong("created_at", 0L).coerceAtLeast(0L)
        )
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return value.takeIf { it.isFinite() }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val PREFS_NAME = "jade_evolution_failure_lessons"
        private const val KEY_LEDGER = "failure_lessons_v1"
        private const val KEY_BACKUP = "failure_lessons_v1_backup"
    }
}