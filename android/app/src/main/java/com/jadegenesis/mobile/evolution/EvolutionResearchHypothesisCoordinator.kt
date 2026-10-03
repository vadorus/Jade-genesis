package com.jadegenesis.mobile.evolution

import android.content.Context
import com.jadegenesis.mobile.config.SafetyPolicy

enum class EvolutionResearchHypothesisRunState {
    IDLE,
    CREATED,
    REJECTED,
    SYNTHESIS_FAILED
}

data class EvolutionResearchHypothesisRun(
    val state: EvolutionResearchHypothesisRunState,
    val hypothesisId: String? = null,
    val researchRecordId: String? = null,
    val summary: String
)

object EvolutionResearchHypothesisPlanner {
    fun selectRecord(
        records: List<EvolutionFailureResearchRecord>,
        hypotheses: List<EvolutionResearchHypothesis>
    ): EvolutionFailureResearchRecord? {
        val processed = hypotheses.mapTo(hashSetOf()) { it.researchRecordId }
        return records
            .asSequence()
            .filter { it.recordId !in processed }
            .filter { it.evidence.isNotEmpty() }
            .filter { it.confidence >= 0.62 }
            .maxByOrNull { it.createdAt }
    }
}
class EvolutionResearchHypothesisCoordinator(context: Context) {
    private val store = EvolutionResearchHypothesisStore(context.applicationContext)

    suspend fun runStep(
        researchRecords: List<EvolutionFailureResearchRecord>,
        synthesizer: suspend (EvolutionFailureResearchRecord) -> String
    ): EvolutionResearchHypothesisRun {
        val existing = store.recent(SafetyPolicy.MAX_EVOLUTION_RESEARCH_HYPOTHESES)
        val record = EvolutionResearchHypothesisPlanner.selectRecord(
            researchRecords,
            existing
        ) ?: return EvolutionResearchHypothesisRun(
            state = EvolutionResearchHypothesisRunState.IDLE,
            summary = "Aucune recherche suffisamment étayée en attente de synthèse."
        )

        val raw = runCatching { synthesizer(record) }.getOrElse { error ->
            return EvolutionResearchHypothesisRun(
                state = EvolutionResearchHypothesisRunState.SYNTHESIS_FAILED,
                researchRecordId = record.recordId,
                summary = "Synthèse impossible : ${error.message?.take(240) ?: "erreur inconnue"}."
            )
        }
        val draft = EvolutionResearchHypothesisParser.parse(raw)
            ?: return EvolutionResearchHypothesisRun(
                state = EvolutionResearchHypothesisRunState.SYNTHESIS_FAILED,
                researchRecordId = record.recordId,
                summary = "Le cerveau n'a pas renvoyé le JSON d'hypothèse attendu."
            )
        val hypothesis = EvolutionResearchHypothesisFactory.create(record, draft)
        store.record(hypothesis)
        val created = hypothesis.status == EvolutionResearchHypothesisStatus.EVIDENCE_SUPPORTED
        return EvolutionResearchHypothesisRun(
            state = if (created) {
                EvolutionResearchHypothesisRunState.CREATED
            } else {
                EvolutionResearchHypothesisRunState.REJECTED
            },
            hypothesisId = hypothesis.hypothesisId,
            researchRecordId = record.recordId,
            summary = if (created) {
                "Hypothèse de recherche conservée avec ${hypothesis.evidenceUrls.size} preuve(s); aucune expérience n'est lancée automatiquement."
            } else {
                "Hypothèse rejetée par le gate : ${hypothesis.gateReason.take(350)}"
            }
        )
    }

    fun recent(limit: Int = 20): List<EvolutionResearchHypothesis> =
        store.recent(limit)
}