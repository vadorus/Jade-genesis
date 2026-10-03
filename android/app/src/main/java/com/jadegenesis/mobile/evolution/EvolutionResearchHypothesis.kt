package com.jadegenesis.mobile.evolution

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class EvolutionResearchHypothesisStatus {
    DRAFT,
    EVIDENCE_SUPPORTED,
    REJECTED
}

data class EvolutionResearchHypothesisDraft(
    val mechanism: String,
    val rationale: String,
    val prediction: String,
    val falsification: String,
    val evidenceIndexes: List<Int>,
    val uncertainty: String,
    val modelConfidence: Double
)

data class EvolutionResearchHypothesis(
    val hypothesisId: String,
    val researchRecordId: String,
    val sourceLessonId: String,
    val sourceHypothesisKey: String?,
    val mechanism: String,
    val rationale: String,
    val prediction: String,
    val falsification: String,
    val evidenceUrls: List<String>,    val uncertainty: String,
    val modelConfidence: Double,
    val status: EvolutionResearchHypothesisStatus,
    val gateReason: String,
    val createdAt: Long
)

data class EvolutionResearchHypothesisGateResult(
    val passed: Boolean,
    val reason: String,
    val evidenceUrls: List<String>,
    val confidence: Double
)

object EvolutionResearchHypothesisGate {
    fun validate(
        draft: EvolutionResearchHypothesisDraft,
        record: EvolutionFailureResearchRecord
    ): EvolutionResearchHypothesisGateResult {
        val errors = mutableListOf<String>()
        if (draft.mechanism.length !in 8..240) errors += "Mécanisme absent ou hors borne."
        if (draft.rationale.length !in 20..1_200) errors += "Rationnel insuffisant ou hors borne."
        if (draft.prediction.length !in 12..600) errors += "Prédiction insuffisante ou hors borne."
        if (draft.falsification.length !in 12..600) errors += "Critère de réfutation insuffisant ou hors borne."
        if (draft.uncertainty.length !in 5..500) errors += "Incertitude absente ou hors borne."
        if (draft.modelConfidence !in 0.0..1.0) errors += "Confiance modèle invalide."

        val indexes = draft.evidenceIndexes.distinct()
        if (indexes.isEmpty()) errors += "Aucune preuve référencée."
        if (indexes.size > 4) errors += "Trop de preuves référencées."
        if (indexes.any { it !in record.evidence.indices }) {
            errors += "Référence de preuve inexistante."
        }
        val evidence = indexes.mapNotNull { record.evidence.getOrNull(it) }
        val providerCount = evidence.map { it.provider }.distinct().size
        val hasPrimary = evidence.any { it.primarySource }
        if (evidence.isNotEmpty() && providerCount < 2 && !hasPrimary) {
            errors += "Une hypothèse nouvelle exige deux fournisseurs ou une source primaire."
        }
        if (record.confidence < 0.62) errors += "Confiance de recherche insuffisante."

        val groundedConfidence = minOf(
            draft.modelConfidence.coerceIn(0.0, 1.0),
            record.confidence.coerceIn(0.0, 1.0),
            evidence.minOfOrNull { it.confidence } ?: 0.0
        )
        return EvolutionResearchHypothesisGateResult(
            passed = errors.isEmpty(),
            reason = errors.joinToString(" | ").ifBlank {
                "Hypothèse reliée à des preuves existantes; elle peut être conservée comme candidate non exécutable."
            },
            evidenceUrls = evidence.map { it.url }.distinct(),
            confidence = groundedConfidence
        )
    }
}

object EvolutionResearchHypothesisParser {
    fun parse(raw: String): EvolutionResearchHypothesisDraft? {
        val jsonText = raw.substringAfter('{', "")
            .takeIf { it.isNotBlank() }
            ?.let { "{" + it.substringBeforeLast('}', it) + "}" }
            ?: return null
        return runCatching {
            val json = JSONObject(jsonText)
            val indexesJson = json.optJSONArray("evidence_indexes") ?: JSONArray()
            val indexes = buildList {
                for (index in 0 until minOf(indexesJson.length(), 6)) {
                    val value = indexesJson.optInt(index, -1)
                    if (value >= 0) add(value)
                }
            }            EvolutionResearchHypothesisDraft(
                mechanism = json.optString("mechanism").trim().take(240),
                rationale = json.optString("rationale").trim().take(1_200),
                prediction = json.optString("prediction").trim().take(600),
                falsification = json.optString("falsification").trim().take(600),
                evidenceIndexes = indexes,
                uncertainty = json.optString("uncertainty").trim().take(500),
                modelConfidence = json.optDouble("confidence", -1.0)
            )
        }.getOrNull()
    }
}

object EvolutionResearchHypothesisFactory {
    fun create(
        record: EvolutionFailureResearchRecord,
        draft: EvolutionResearchHypothesisDraft,
        now: Long = System.currentTimeMillis()
    ): EvolutionResearchHypothesis {
        val gate = EvolutionResearchHypothesisGate.validate(draft, record)
        return EvolutionResearchHypothesis(
            hypothesisId = "research-hyp-${UUID.randomUUID()}",
            researchRecordId = record.recordId,
            sourceLessonId = record.lessonId,
            sourceHypothesisKey = record.hypothesisKey,
            mechanism = draft.mechanism,
            rationale = draft.rationale,
            prediction = draft.prediction,
            falsification = draft.falsification,
            evidenceUrls = gate.evidenceUrls,
            uncertainty = draft.uncertainty,
            modelConfidence = gate.confidence,
            status = if (gate.passed) {
                EvolutionResearchHypothesisStatus.EVIDENCE_SUPPORTED
            } else {
                EvolutionResearchHypothesisStatus.REJECTED
            },
            gateReason = gate.reason,
            createdAt = now
        )
    }
}