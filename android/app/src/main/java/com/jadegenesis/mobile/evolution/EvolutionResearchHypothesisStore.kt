package com.jadegenesis.mobile.evolution

import android.content.Context
import com.jadegenesis.mobile.config.SafetyPolicy
import org.json.JSONArray
import org.json.JSONObject

class EvolutionResearchHypothesisStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val lock = Any()

    fun record(item: EvolutionResearchHypothesis) = synchronized(lock) {
        val current = loadUnsafe().filterNot { it.hypothesisId == item.hypothesisId }
        saveUnsafe(
            (listOf(item) + current)
                .take(SafetyPolicy.MAX_EVOLUTION_RESEARCH_HYPOTHESES)
        )
    }

    fun recent(limit: Int = 20): List<EvolutionResearchHypothesis> = synchronized(lock) {
        loadUnsafe().take(
            limit.coerceIn(0, SafetyPolicy.MAX_EVOLUTION_RESEARCH_HYPOTHESES)
        )
    }

    private fun loadUnsafe(): List<EvolutionResearchHypothesis> {
        val primary = prefs.getString(KEY_LEDGER, null)
        if (!primary.isNullOrBlank()) decode(primary)?.let { return it }
        val backup = prefs.getString(KEY_BACKUP, null)
        if (!backup.isNullOrBlank()) decode(backup)?.let { recovered ->
            prefs.edit().putString(KEY_LEDGER, encode(recovered)).apply()
            return recovered
        }
        return emptyList()
    }

    private fun saveUnsafe(items: List<EvolutionResearchHypothesis>) {
        val encoded = encode(items)
        val previous = prefs.getString(KEY_LEDGER, null)
        val editor = prefs.edit()
        if (!previous.isNullOrBlank()) editor.putString(KEY_BACKUP, previous)
        editor.putString(KEY_LEDGER, encoded).apply()
    }

    private fun encode(items: List<EvolutionResearchHypothesis>): String =
        JSONArray().apply { items.forEach { put(toJson(it)) } }.toString()

    private fun decode(raw: String): List<EvolutionResearchHypothesis>? = runCatching {
        val array = JSONArray(raw)
        buildList {
            val count = minOf(
                array.length(),
                SafetyPolicy.MAX_EVOLUTION_RESEARCH_HYPOTHESES
            )
            for (index in 0 until count) {
                runCatching { fromJson(array.getJSONObject(index)) }
                    .getOrNull()
                    ?.let(::add)
            }
        }
    }.getOrNull()
    private fun toJson(item: EvolutionResearchHypothesis): JSONObject = JSONObject().apply {
        put("schema_version", SCHEMA_VERSION)
        put("hypothesis_id", item.hypothesisId)
        put("research_record_id", item.researchRecordId)
        put("source_lesson_id", item.sourceLessonId)
        put("source_hypothesis_key", item.sourceHypothesisKey ?: "")
        put("mechanism", item.mechanism)
        put("rationale", item.rationale)
        put("prediction", item.prediction)
        put("falsification", item.falsification)
        put("evidence_urls", JSONArray(item.evidenceUrls))
        put("uncertainty", item.uncertainty)
        put("model_confidence", item.modelConfidence)
        put("status", item.status.name)
        put("gate_reason", item.gateReason)
        put("created_at", item.createdAt)
    }

    private fun fromJson(json: JSONObject): EvolutionResearchHypothesis =
        EvolutionResearchHypothesis(
            hypothesisId = json.getString("hypothesis_id"),
            researchRecordId = json.getString("research_record_id"),
            sourceLessonId = json.getString("source_lesson_id"),
            sourceHypothesisKey = json.optString("source_hypothesis_key")
                .takeIf { it.isNotBlank() },
            mechanism = json.optString("mechanism").take(240),
            rationale = json.optString("rationale").take(1_200),
            prediction = json.optString("prediction").take(600),
            falsification = json.optString("falsification").take(600),            evidenceUrls = json.optJSONArray("evidence_urls").toStringList(4),
            uncertainty = json.optString("uncertainty").take(500),
            modelConfidence = json.optDouble("model_confidence", 0.0).coerceIn(0.0, 1.0),
            status = runCatching {
                EvolutionResearchHypothesisStatus.valueOf(json.optString("status"))
            }.getOrDefault(EvolutionResearchHypothesisStatus.REJECTED),
            gateReason = json.optString("gate_reason").take(1_000),
            createdAt = json.optLong("created_at", 0L).coerceAtLeast(0L)
        )

    private fun JSONArray?.toStringList(limit: Int): List<String> {
        if (this == null) return emptyList()
        return buildList {
            val count = minOf(length(), limit)
            for (index in 0 until count) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val PREFS_NAME = "jade_evolution_research_hypotheses"
        private const val KEY_LEDGER = "research_hypotheses_v1"
        private const val KEY_BACKUP = "research_hypotheses_v1_backup"
    }
}