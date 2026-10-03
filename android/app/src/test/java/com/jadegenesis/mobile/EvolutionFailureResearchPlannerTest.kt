package com.jadegenesis.mobile

import com.jadegenesis.mobile.evolution.EvolutionFailureKind
import com.jadegenesis.mobile.evolution.EvolutionFailureLesson
import com.jadegenesis.mobile.evolution.EvolutionFailureResearchPlanner
import com.jadegenesis.mobile.evolution.EvolutionFailureResearchRecord
import com.jadegenesis.mobile.evolution.EvolutionNextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvolutionFailureResearchPlannerTest {

    @Test
    fun selectsNewestUnresearchedEscalatedLesson() {
        val older = lesson("old", 10L)
        val newer = lesson("new", 20L)

        val selected = EvolutionFailureResearchPlanner.selectLesson(
            lessons = listOf(older, newer),
            existing = emptyList()
        )

        assertEquals("new", selected?.lessonId)
    }

    @Test
    fun skipsLessonThatAlreadyHasResearchRecord() {
        val target = lesson("done", 20L)
        val selected = EvolutionFailureResearchPlanner.selectLesson(
            lessons = listOf(target),            existing = listOf(record("done"))
        )

        assertNull(selected)
    }

    @Test
    fun ignoresFailureThatDoesNotRequireResearch() {
        val target = lesson(
            id = "alternate",
            at = 20L,
            direction = EvolutionNextDirection.TRY_ALTERNATE_LATENCY_MECHANISM
        )

        assertNull(
            EvolutionFailureResearchPlanner.selectLesson(
                lessons = listOf(target),
                existing = emptyList()
            )
        )
    }

    @Test
    fun researchPromptContainsOnlyBoundedTechnicalContext() {
        val target = lesson("research", 20L)
        val question = EvolutionFailureResearchPlanner.buildQuestion(target)
        val observation = EvolutionFailureResearchPlanner.buildObservation(target)

        assertTrue(question.contains("mécanismes alternatifs"))
        assertTrue(observation.contains("LATENCY_REGRESSION"))
        assertTrue(observation.contains("historyDurationScaleMs:down"))
        assertTrue(observation.length <= 2_000)
    }
    private fun lesson(
        id: String,
        at: Long,
        direction: EvolutionNextDirection = EvolutionNextDirection.RESEARCH_BEFORE_RETRY
    ): EvolutionFailureLesson = EvolutionFailureLesson(
        lessonId = id,
        candidateId = "candidate-$id",
        taskKind = "text_analysis",
        hypothesisKey = "LATENCY|text_analysis",
        mutationKey = "LATENCY|text_analysis|historyDurationScaleMs:down",
        kind = EvolutionFailureKind.LATENCY_REGRESSION,
        reason = "latency regression",
        refutedClaim = "duration signal too weak",
        nextDirection = direction,
        baselineSamples = 12,
        challengerSamples = 12,
        baselineSuccessRate = 1.0,
        challengerSuccessRate = 1.0,
        baselineAverageDurationMs = 100.0,
        challengerAverageDurationMs = 240.0,
        baselineScore = 82.0,
        challengerScore = 71.0,
        createdAt = at
    )

    private fun record(lessonId: String): EvolutionFailureResearchRecord =
        EvolutionFailureResearchRecord(
            recordId = "record-$lessonId",
            lessonId = lessonId,            hypothesisKey = "LATENCY|text_analysis",
            mutationKey = "LATENCY|text_analysis|historyDurationScaleMs:down",
            question = "question",
            queries = listOf("query"),
            evidence = emptyList(),
            confidence = 0.25,
            providerErrors = emptyList(),
            createdAt = 30L
        )
}