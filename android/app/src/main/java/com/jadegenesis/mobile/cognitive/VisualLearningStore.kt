package com.jadegenesis.mobile.cognitive

import android.content.Context
import com.jadegenesis.mobile.model.LearningCandidate
import org.json.JSONArray
import org.json.JSONObject

data class VisualObservationRecord(
    val imageSha256: String,
    val source: String,
    val visionText: String,
    val observedAt: Long,
    val researchQuery: String = "",
    val researchText: String = "",
    val researchEvidenceCount: Int = 0,
    val researchConfidence: Double = 0.0,
    val researchedAt: Long = 0L
)

class VisualLearningStore(context: Context) {
    companion object {
        private const val PREFS = "jade_visual_learning_v1"
        private const val KEY_RECORDS = "records"
        private const val MAX_RECORDS = 24
    }

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun recordVision(
        imageSha256: String,
        source: String,
        visionText: String
    ): VisualObservationRecord {
        val current = records().toMutableList()
        val existingIndex = current.indexOfFirst { it.imageSha256 == imageSha256 }
        val record = if (existingIndex >= 0) {
            current[existingIndex].copy(
                source = source,
                visionText = visionText.trim()
            )
        } else {
            VisualObservationRecord(
                imageSha256 = imageSha256,
                source = source,
                visionText = visionText.trim(),
                observedAt = System.currentTimeMillis()
            )
        }

        if (existingIndex >= 0) {
            current[existingIndex] = record
        } else {
            current.add(0, record)
        }
        save(current)
        return record
    }

    @Synchronized
    fun hasObservation(imageSha256: String): Boolean =
        records().any { it.imageSha256 == imageSha256 }

    @Synchronized
    fun last(): VisualObservationRecord? = records().firstOrNull()

    @Synchronized
    fun recordResearch(
        imageSha256: String,
        query: String,
        researchText: String,
        evidenceCount: Int,
        confidence: Double
    ): VisualObservationRecord? {
        val current = records().toMutableList()
        val index = current.indexOfFirst { it.imageSha256 == imageSha256 }
        if (index < 0) return null
        val updated = current[index].copy(
            researchQuery = query.trim(),
            researchText = researchText.trim(),
            researchEvidenceCount = evidenceCount.coerceAtLeast(0),
            researchConfidence = confidence.coerceIn(0.0, 1.0),
            researchedAt = System.currentTimeMillis()
        )
        current[index] = updated
        save(current)
        return updated
    }

    fun candidates(limit: Int = 6): List<LearningCandidate> =
        records()
            .map { record ->
                if (record.researchedAt > 0L) {
                    LearningCandidate(
                        id = "visual-${record.imageSha256.take(16)}-researched",
                        title = "Apprentissage visuel à consolider",
                        description =
                            "Une observation visuelle a été confrontée à des sources publiques. " +
                                "Elle reste candidate tant que Jade ne dispose pas d'assez de confirmations indépendantes.",
                        evidence =
                            "${record.researchEvidenceCount} élément(s) de preuve • " +
                                "image ${record.imageSha256.take(12)}… • ${record.source}",
                        confidence = record.researchConfidence,
                        createdAt = record.researchedAt
                    )
                } else {
                    LearningCandidate(
                        id = "visual-${record.imageSha256.take(16)}-pending",
                        title = "Observation visuelle à vérifier",
                        description =
                            "Jade a observé l'écran mais cette observation n'a pas encore été recoupée avec des données externes.",
                        evidence =
                            "image ${record.imageSha256.take(12)}… • ${record.source}",
                        confidence = 0.55,
                        createdAt = record.observedAt
                    )
                }
            }
            .sortedByDescending { it.createdAt }
            .take(limit)

    private fun records(): List<VisualObservationRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val sha = item.optString("image_sha256").trim()
                    val text = item.optString("vision_text").trim()
                    if (sha.isBlank() || text.isBlank()) continue
                    add(
                        VisualObservationRecord(
                            imageSha256 = sha,
                            source = item.optString("source", "unknown"),
                            visionText = text,
                            observedAt = item.optLong("observed_at", 0L),
                            researchQuery = item.optString("research_query"),
                            researchText = item.optString("research_text"),
                            researchEvidenceCount =
                                item.optInt("research_evidence_count", 0),
                            researchConfidence =
                                item.optDouble("research_confidence", 0.0),
                            researchedAt = item.optLong("researched_at", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(records: List<VisualObservationRecord>) {
        val array = JSONArray()
        records
            .sortedByDescending { it.observedAt }
            .distinctBy { it.imageSha256 }
            .take(MAX_RECORDS)
            .forEach { record ->
                array.put(
                    JSONObject().apply {
                        put("image_sha256", record.imageSha256)
                        put("source", record.source)
                        put("vision_text", record.visionText)
                        put("observed_at", record.observedAt)
                        put("research_query", record.researchQuery)
                        put("research_text", record.researchText)
                        put(
                            "research_evidence_count",
                            record.researchEvidenceCount
                        )
                        put("research_confidence", record.researchConfidence)
                        put("researched_at", record.researchedAt)
                    }
                )
            }
        prefs.edit().putString(KEY_RECORDS, array.toString()).apply()
    }
}
