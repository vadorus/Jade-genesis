package com.jadegenesis.mobile.evolution

import android.content.Context
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.research.ResearchEngine
import com.jadegenesis.mobile.research.ResearchEvidence
import java.util.UUID

enum class EvolutionFailureResearchState {
    IDLE,
    EVIDENCE_FOUND,
    NO_EVIDENCE
}

data class EvolutionFailureResearchEvidence(
    val provider: String,
    val title: String,
    val url: String,
    val snippet: String,
    val confidence: Double,
    val primarySource: Boolean
)

data class EvolutionFailureResearchRecord(
    val recordId: String,
    val lessonId: String,
    val hypothesisKey: String?,
    val mutationKey: String?,
    val question: String,
    val queries: List<String>,
    val evidence: List<EvolutionFailureResearchEvidence>,
    val confidence: Double,
    val providerErrors: List<String>,
    val createdAt: Long
)

data class EvolutionFailureResearchRun(
    val state: EvolutionFailureResearchState,
    val lessonId: String? = null,
    val evidenceCount: Int = 0,
    val confidence: Double = 0.0,
    val summary: String
)

object EvolutionFailureResearchPlanner {
    fun selectLesson(
        lessons: List<EvolutionFailureLesson>,
        existing: List<EvolutionFailureResearchRecord>
    ): EvolutionFailureLesson? {
        val researched = existing.mapTo(hashSetOf()) { it.lessonId }
        return lessons
            .asSequence()
            .filter { it.nextDirection == EvolutionNextDirection.RESEARCH_BEFORE_RETRY }
            .filter { it.lessonId !in researched }
            .maxByOrNull { it.createdAt }
    }

    fun buildQuestion(lesson: EvolutionFailureLesson): String = buildString {
        append("Pourquoi deux approches d'amélioration de routage ont-elles échoué pour ")
        append(lesson.taskKind ?: "une tâche distribuée")
        append(" ? Hypothèse étudiée : ")
        append(lesson.refutedClaim.take(300))
        append(". Chercher des mécanismes alternatifs, les compromis et les conditions où ils sont préférables.")
    }.take(1_000)

    fun buildObservation(lesson: EvolutionFailureLesson): String = buildString {
        appendLine("Contexte technique d'expérience de routage distribué.")
        appendLine("Type de tâche: ${lesson.taskKind ?: "non spécifié"}.")
        appendLine("Échec classé: ${lesson.kind.name}.")
        appendLine("Hypothèse: ${lesson.refutedClaim.take(300)}")
        appendLine("Mutation testée: ${lesson.mutationKey ?: "non spécifiée"}.")
        appendLine("Champion succès: ${(lesson.baselineSuccessRate * 100.0).toInt()} %.")
        appendLine("Challenger succès: ${(lesson.challengerSuccessRate * 100.0).toInt()} %.")
        if (lesson.baselineAverageDurationMs > 0.0) {
            appendLine("Champion durée moyenne: ${lesson.baselineAverageDurationMs.toLong()} ms.")
        }
        if (lesson.challengerAverageDurationMs > 0.0) {
            appendLine("Challenger durée moyenne: ${lesson.challengerAverageDurationMs.toLong()} ms.")
        }
    }.take(2_000)

    fun snapshot(evidence: ResearchEvidence): EvolutionFailureResearchEvidence =
        EvolutionFailureResearchEvidence(
            provider = evidence.provider.take(120),
            title = evidence.title.take(300),
            url = evidence.url.take(1_000),
            snippet = evidence.snippet.take(800),
            confidence = evidence.confidence.coerceIn(0.0, 1.0),
            primarySource = evidence.primarySource
        )
}

class EvolutionFailureResearchCoordinator(context: Context) {
    private val store = EvolutionFailureResearchStore(context.applicationContext)
    private val research = ResearchEngine()

    suspend fun runStep(
        lessons: List<EvolutionFailureLesson>
    ): EvolutionFailureResearchRun {
        val existing = store.recent(SafetyPolicy.MAX_EVOLUTION_FAILURE_RESEARCH_RECORDS)
        val lesson = EvolutionFailureResearchPlanner.selectLesson(lessons, existing)
            ?: return EvolutionFailureResearchRun(
                state = EvolutionFailureResearchState.IDLE,
                summary = "Aucune leçon d'échec en attente de recherche."
            )
        val question = EvolutionFailureResearchPlanner.buildQuestion(lesson)
        val observation = EvolutionFailureResearchPlanner.buildObservation(lesson)
        val report = research.investigate(
            observation = observation,
            focusInstruction = question
        )
        val evidence = report.evidence
            .take(SafetyPolicy.MAX_EVOLUTION_FAILURE_RESEARCH_EVIDENCE)
            .map(EvolutionFailureResearchPlanner::snapshot)
        val record = EvolutionFailureResearchRecord(
            recordId = "evo-research-${UUID.randomUUID()}",
            lessonId = lesson.lessonId,
            hypothesisKey = lesson.hypothesisKey,
            mutationKey = lesson.mutationKey,
            question = question,
            queries = report.queries.take(3),
            evidence = evidence,
            confidence = report.confidence,
            providerErrors = report.providerErrors.take(6),
            createdAt = System.currentTimeMillis()
        )
        store.record(record)
        val state = if (evidence.isEmpty()) {
            EvolutionFailureResearchState.NO_EVIDENCE
        } else {
            EvolutionFailureResearchState.EVIDENCE_FOUND
        }
        return EvolutionFailureResearchRun(
            state = state,
            lessonId = lesson.lessonId,
            evidenceCount = evidence.size,
            confidence = report.confidence,
            summary = if (evidence.isEmpty()) {
                "Recherche terminée sans preuve publique exploitable; aucune nouvelle mutation n'est générée."
            } else {
                "Recherche terminée avec ${evidence.size} source(s); résultats conservés comme preuves, sans modification automatique."
            }
        )
    }

    fun recent(limit: Int = 20): List<EvolutionFailureResearchRecord> =
        store.recent(limit)
}