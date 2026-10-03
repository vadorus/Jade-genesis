package com.jadegenesis.mobile.evolution

import java.util.UUID

enum class EvolutionFailureKind {
    HARD_FAILURE,
    RELIABILITY_REGRESSION,
    LATENCY_REGRESSION,
    INSUFFICIENT_GAIN,
    INVALID_EVIDENCE,
    CHAMPION_CHANGED,
    MANUAL_REJECTION,
    OTHER
}

data class EvolutionFailureMetrics(
    val baselineSamples: Int,
    val challengerSamples: Int,
    val baselineSuccessRate: Double,
    val challengerSuccessRate: Double,
    val baselineAverageDurationMs: Double = 0.0,
    val challengerAverageDurationMs: Double = 0.0,
    val baselineScore: Double? = null,
    val challengerScore: Double? = null
)

enum class EvolutionNextDirection {
    TRY_ALTERNATE_RELIABILITY_MECHANISM,
    TRY_ALTERNATE_LATENCY_MECHANISM,
    IMPROVE_MEASUREMENT,
    REBASE_ON_CURRENT_CHAMPION,
    GATHER_FRESH_EVIDENCE,
    RESEARCH_BEFORE_RETRY
}

data class EvolutionFailureLesson(
    val lessonId: String,
    val candidateId: String,
    val taskKind: String?,
    val hypothesisKey: String?,
    val mutationKey: String?,
    val kind: EvolutionFailureKind,
    val reason: String,
    val refutedClaim: String,
    val nextDirection: EvolutionNextDirection,
    val baselineSamples: Int,
    val challengerSamples: Int,
    val baselineSuccessRate: Double,
    val challengerSuccessRate: Double,
    val baselineAverageDurationMs: Double,
    val challengerAverageDurationMs: Double,
    val baselineScore: Double?,
    val challengerScore: Double?,
    val createdAt: Long
)

object EvolutionFailureLearner {
    fun fromCandidate(
        candidate: EvolutionCandidate,
        kind: EvolutionFailureKind,
        reason: String,
        metrics: EvolutionFailureMetrics? = null,
        now: Long = System.currentTimeMillis()
    ): EvolutionFailureLesson {
        val baseline = candidate.pairedBaselineEvidence
        val challenger = candidate.pairedChallengerEvidence
        return EvolutionFailureLesson(
            lessonId = "failure-${UUID.randomUUID()}",
            candidateId = candidate.candidateId,
            taskKind = candidate.experimentTaskKind,
            hypothesisKey = candidate.hypothesisKey,
            mutationKey = candidate.mutationKey,
            kind = kind,
            reason = reason.trim().take(500),
            refutedClaim = extractHypothesis(candidate.rationale),
            nextDirection = nextDirection(kind),
            baselineSamples = metrics?.baselineSamples ?: baseline?.observationCount ?: 0,
            challengerSamples = metrics?.challengerSamples ?: challenger?.observationCount ?: 0,
            baselineSuccessRate = metrics?.baselineSuccessRate ?: baseline?.overallSuccessRate ?: 0.0,
            challengerSuccessRate = metrics?.challengerSuccessRate ?: challenger?.overallSuccessRate ?: 0.0,
            baselineAverageDurationMs = metrics?.baselineAverageDurationMs ?: 0.0,
            challengerAverageDurationMs = metrics?.challengerAverageDurationMs ?: 0.0,
            baselineScore = metrics?.baselineScore ?: baseline?.score,
            challengerScore = metrics?.challengerScore ?: challenger?.score,
            createdAt = now
        )
    }

    fun classifyComparison(comparison: EvolutionComparison): EvolutionFailureKind = when {
        !comparison.pairedScenarioCompatible -> EvolutionFailureKind.INVALID_EVIDENCE
        !comparison.successRateProtected -> EvolutionFailureKind.RELIABILITY_REGRESSION
        comparison.enoughEvidence && comparison.confidenceSatisfied &&
            !comparison.scoreImproved -> EvolutionFailureKind.INSUFFICIENT_GAIN
        else -> EvolutionFailureKind.OTHER
    }

    fun withEscalation(
        lesson: EvolutionFailureLesson,
        priorLessons: List<EvolutionFailureLesson>
    ): EvolutionFailureLesson {
        val priorDistinct = lesson.hypothesisKey != null && isRefuting(lesson.kind) &&
            priorLessons.any { previous ->
                previous.hypothesisKey == lesson.hypothesisKey &&
                    previous.mutationKey != null &&
                    previous.mutationKey != lesson.mutationKey &&
                    isRefuting(previous.kind)
            }
        return if (priorDistinct) {
            lesson.copy(nextDirection = EvolutionNextDirection.RESEARCH_BEFORE_RETRY)
        } else lesson
    }

    fun isRefuting(kind: EvolutionFailureKind): Boolean = kind in setOf(
        EvolutionFailureKind.HARD_FAILURE,
        EvolutionFailureKind.RELIABILITY_REGRESSION,
        EvolutionFailureKind.LATENCY_REGRESSION,
        EvolutionFailureKind.INSUFFICIENT_GAIN,
        EvolutionFailureKind.MANUAL_REJECTION
    )

    fun nextDirection(kind: EvolutionFailureKind): EvolutionNextDirection = when (kind) {
        EvolutionFailureKind.HARD_FAILURE,
        EvolutionFailureKind.RELIABILITY_REGRESSION ->
            EvolutionNextDirection.TRY_ALTERNATE_RELIABILITY_MECHANISM
        EvolutionFailureKind.LATENCY_REGRESSION ->
            EvolutionNextDirection.TRY_ALTERNATE_LATENCY_MECHANISM
        EvolutionFailureKind.INVALID_EVIDENCE ->
            EvolutionNextDirection.IMPROVE_MEASUREMENT
        EvolutionFailureKind.CHAMPION_CHANGED ->
            EvolutionNextDirection.REBASE_ON_CURRENT_CHAMPION
        EvolutionFailureKind.INSUFFICIENT_GAIN ->
            EvolutionNextDirection.GATHER_FRESH_EVIDENCE
        EvolutionFailureKind.MANUAL_REJECTION,
        EvolutionFailureKind.OTHER ->
            EvolutionNextDirection.RESEARCH_BEFORE_RETRY
    }

    private fun extractHypothesis(rationale: String): String {
        val marker = "HYPOTHESE:"
        val start = rationale.indexOf(marker)
        if (start < 0) return rationale.take(500)
        val from = start + marker.length
        val prediction = rationale.indexOf("PREDICTION:", from)
        val end = if (prediction > from) prediction else rationale.length
        return rationale.substring(from, end).trim().take(500)
    }
}