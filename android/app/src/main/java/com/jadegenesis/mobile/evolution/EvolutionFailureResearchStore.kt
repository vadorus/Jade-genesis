package com.jadegenesis.mobile.evolution

import android.content.Context
import com.jadegenesis.mobile.config.SafetyPolicy
import org.json.JSONArray
import org.json.JSONObject

class EvolutionFailureResearchStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val lock = Any()

    fun record(record: EvolutionFailureResearchRecord) = synchronized(lock) {
        val current = loadUnsafe().filterNot { it.recordId == record.recordId }
        saveUnsafe(
            (listOf(record) + current)
                .take(SafetyPolicy.MAX_EVOLUTION_FAILURE_RESEARCH_RECORDS)
        )
    }

    fun recent(limit: Int = 20): List<EvolutionFailureResearchRecord> = synchronized(lock) {
        loadUnsafe().take(
            limit.coerceIn(0, SafetyPolicy.MAX_EVOLUTION_FAILURE_RESEARCH_RECORDS)
        )
    }

    private fun loadUnsafe(): List<EvolutionFailureResearchRecord> {        val primary = prefs.getString(KEY_LEDGER, null)
        if (!primary.isNullOrBlank()) decode(primary)?.let { return it }
        val backup = prefs.getString(KEY_BACKUP, null)
        if (!backup.isNullOrBlank()) decode(backup)?.let { recovered ->
            prefs.edit().putString(KEY_LEDGER, encode(recovered)).apply()
            return recovered
        }
        return emptyList()
    }

    private fun saveUnsafe(items: List<EvolutionFailureResearchRecord>) {
        val encoded = encode(items)
        val previous = prefs.getString(KEY_LEDGER, null)
        val editor = prefs.edit()
        if (!previous.isNullOrBlank()) editor.putString(KEY_BACKUP, previous)
        editor.putString(KEY_LEDGER, encoded).apply()
    }

    private fun encode(items: List<EvolutionFailureResearchRecord>): String =
        JSONArray().apply { items.forEach { put(toJson(it)) } }.toString()

    private fun decode(raw: String): List<EvolutionFailureResearchRecord>? = runCatching {
        val array = JSONArray(raw)
        buildList {
            val count = minOf(
                array.length(),
                SafetyPolicy.MAX_EVOLUTION_FAILURE_RESEARCH_RECORDS
            )
            for (index in 0 until count) {
                runCatching { fromJson(array.getJSONObject(index)) }
                    .getOrNull()
                    ?.let(::add)
            }
        }
    }.getOrNull()

    private fun toJson(record: EvolutionFailureResearchRecord): JSONObject =
        JSONObject().apply {
            put("schema_version", SCHEMA_VERSION)
            put("record_id", record.recordId)
            put("lesson_id", record.lessonId)
            put("hypothesis_key", record.hypothesisKey ?: "")
            put("mutation_key", record.mutationKey ?: "")
            put("question", record.question)
            put("queries", JSONArray(record.queries))
            put("confidence", record.confidence)
            put("provider_errors", JSONArray(record.providerErrors))
            put("created_at", record.createdAt)
            put(
                "evidence",
                JSONArray().apply {
                    record.evidence.forEach { item -> put(evidenceToJson(item)) }
                }
            )
        }

    private fun evidenceToJson(item: EvolutionFailureResearchEvidence): JSONObject =
        JSONObject().apply {            put("provider", item.provider)
            put("title", item.title)
            put("url", item.url)
            put("snippet", item.snippet)
            put("confidence", item.confidence)
            put("primary_source", item.primarySource)
        }

    private fun fromJson(json: JSONObject): EvolutionFailureResearchRecord {
        require(json.optInt("schema_version", SCHEMA_VERSION) == SCHEMA_VERSION)
        val evidenceJson = json.optJSONArray("evidence") ?: JSONArray()
        val evidence = buildList {
            val count = minOf(
                evidenceJson.length(),
                SafetyPolicy.MAX_EVOLUTION_FAILURE_RESEARCH_EVIDENCE
            )
            for (index in 0 until count) {
                runCatching { evidenceFromJson(evidenceJson.getJSONObject(index)) }
                    .getOrNull()
                    ?.let(::add)
            }
        }
        return EvolutionFailureResearchRecord(
            recordId = json.getString("record_id"),
            lessonId = json.getString("lesson_id"),
            hypothesisKey = json.optString("hypothesis_key").takeIf { it.isNotBlank() },
            mutationKey = json.optString("mutation_key").takeIf { it.isNotBlank() },
            question = json.optString("question").take(1_000),
            queries = json.optJSONArray("queries").toStringList(3),            evidence = evidence,
            confidence = json.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
            providerErrors = json.optJSONArray("provider_errors").toStringList(6),
            createdAt = json.optLong("created_at", 0L).coerceAtLeast(0L)
        )
    }

    private fun evidenceFromJson(json: JSONObject): EvolutionFailureResearchEvidence =
        EvolutionFailureResearchEvidence(
            provider = json.optString("provider").take(120),
            title = json.optString("title").take(300),
            url = json.optString("url").take(1_000),
            snippet = json.optString("snippet").take(800),
            confidence = json.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
            primarySource = json.optBoolean("primary_source", false)
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
        private const val PREFS_NAME = "jade_evolution_failure_research"
        private const val KEY_LEDGER = "failure_research_v1"
        private const val KEY_BACKUP = "failure_research_v1_backup"
    }
}