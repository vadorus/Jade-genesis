package com.jadegenesis.mobile.night

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device-local provenance ledger. Only opaque memory/source IDs, evidence
 * types and SHA-256 fingerprints are stored, never private memory content,
 * copied webpages or user conversations.
 */
internal class NightEvidenceLedger(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "jade_night_evidence_ledger_v1", Context.MODE_PRIVATE
    )

    private companion object {
        const val MAX_EVIDENCE = 96
        const val MAX_REVIEWS = 8
    }

    fun append(item: ReviewEvidence) {
        require(item.evidenceId.length in 1..120)
        require(item.memoryId.length in 1..120)
        require(item.sourceId.length in 1..160)
        require(item.fingerprint.matches(Regex("[a-fA-F0-9]{64}")))
        require(item.observedAt > 0L)
        synchronized(this) {
            val next = (listOf(item) + evidence()
                .filterNot { it.evidenceId == item.evidenceId })
                .take(MAX_EVIDENCE)
            persistEvidence(next)
        }
    }

    fun evidence(): List<ReviewEvidence> = runCatching {
        val arr = JSONArray(prefs.getString("evidence_v1", "[]") ?: "[]")
        buildList {
            for (i in 0 until minOf(arr.length(), MAX_EVIDENCE)) {
                val x = arr.optJSONObject(i) ?: continue
                val type = enumValueOrNull<EvidenceType>(x.optString("type")) ?: continue
                val direction = enumValueOrNull<EvidenceDirection>(x.optString("direction"))
                    ?: continue
                val fingerprint = x.optString("sha256")
                if (!fingerprint.matches(Regex("[a-fA-F0-9]{64}"))) continue
                val memoryId = x.optString("memory_id").take(120)
                val evidenceId = x.optString("evidence_id").take(120)
                val sourceId = x.optString("source_id").take(160)
                if (memoryId.isBlank() || evidenceId.isBlank() || sourceId.isBlank()) continue
                add(ReviewEvidence(
                    evidenceId, memoryId, sourceId, type, direction,
                    x.optLong("observed_at"), x.optBoolean("inspected"), fingerprint
                ))
            }
        }
    }.getOrDefault(emptyList())

    fun removeForMemory(memoryId: String) {
        synchronized(this) {
            persistEvidence(evidence().filterNot { it.memoryId == memoryId })
            saveReviews(latestReviews().filterNot { it.memoryId == memoryId })
        }
    }

    fun saveReviews(reviews: List<EvidenceReview>) {
        val arr = JSONArray()
        reviews.take(MAX_REVIEWS).forEach { review ->
            arr.put(JSONObject().apply {
                put("memory_id", review.memoryId.take(120))
                put("decision", review.decision.name)
                put("support_count", review.supportCount)
                put("refute_count", review.refuteCount)
                put("primary_count", review.primarySourceCount)
                put("test_count", review.replayedTestCount)
                put("reason", review.reason.take(180))
                put("automatic_promotion", false)
            })
        }
        check(prefs.edit().putString("reviews_v1", arr.toString()).commit()) {
            "Cannot persist night evidence reviews"
        }
    }

    fun latestReviews(): List<EvidenceReview> = runCatching {
        val arr = JSONArray(prefs.getString("reviews_v1", "[]") ?: "[]")
        buildList {
            for (i in 0 until minOf(arr.length(), MAX_REVIEWS)) {
                val o = arr.optJSONObject(i) ?: continue
                val decision = enumValueOrNull<ReviewDecision>(o.optString("decision"))
                    ?: continue
                val memoryId = o.optString("memory_id").take(120)
                if (memoryId.isBlank()) continue
                add(EvidenceReview(
                    memoryId, decision, o.optInt("support_count").coerceAtLeast(0),
                    o.optInt("refute_count").coerceAtLeast(0),
                    o.optInt("primary_count").coerceAtLeast(0),
                    o.optInt("test_count").coerceAtLeast(0),
                    o.optString("reason").take(180),
                    automaticPromotion = false
                ))
            }
        }
    }.getOrDefault(emptyList())

    private fun persistEvidence(items: List<ReviewEvidence>) {
        val arr = JSONArray()
        items.take(MAX_EVIDENCE).forEach { item ->
            arr.put(JSONObject().apply {
                put("evidence_id", item.evidenceId)
                put("memory_id", item.memoryId)
                put("source_id", item.sourceId)
                put("type", item.type.name)
                put("direction", item.direction.name)
                put("observed_at", item.observedAt)
                put("inspected", item.inspected)
                put("sha256", item.fingerprint)
            })
        }
        check(prefs.edit().putString("evidence_v1", arr.toString()).commit()) {
            "Cannot persist evidence ledger"
        }
    }

    private inline fun <reified T: Enum<T>> enumValueOrNull(value: String): T? =
        enumValues<T>().firstOrNull { it.name == value }
}
