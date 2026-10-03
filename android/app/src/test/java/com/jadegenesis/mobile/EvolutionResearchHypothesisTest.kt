package com.jadegenesis.mobile

import com.jadegenesis.mobile.evolution.EvolutionFailureResearchEvidence
import com.jadegenesis.mobile.evolution.EvolutionFailureResearchRecord
import com.jadegenesis.mobile.evolution.EvolutionResearchHypothesisDraft
import com.jadegenesis.mobile.evolution.EvolutionResearchHypothesisFactory
import com.jadegenesis.mobile.evolution.EvolutionResearchHypothesisGate
import com.jadegenesis.mobile.evolution.EvolutionResearchHypothesisParser
import com.jadegenesis.mobile.evolution.EvolutionResearchHypothesisPlanner
import com.jadegenesis.mobile.evolution.EvolutionResearchHypothesisStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvolutionResearchHypothesisTest {

    @Test
    fun groundedHypothesisPassesWithTwoProviders() {
        val result = EvolutionResearchHypothesisGate.validate(
            draft = draft(evidenceIndexes = listOf(0, 1)),
            record = record()
        )

        assertTrue(result.passed)
        assertEquals(2, result.evidenceUrls.size)
        assertTrue(result.confidence > 0.6)
    }

    @Test
    fun inventedEvidenceIndexIsRejected() {        val result = EvolutionResearchHypothesisGate.validate(
            draft = draft(evidenceIndexes = listOf(0, 99)),
            record = record()
        )

        assertFalse(result.passed)
        assertTrue(result.reason.contains("inexistante"))
    }

    @Test
    fun weakResearchConfidenceIsRejected() {
        val weak = record().copy(confidence = 0.40)
        val result = EvolutionResearchHypothesisGate.validate(
            draft = draft(evidenceIndexes = listOf(0, 1)),
            record = weak
        )

        assertFalse(result.passed)
        assertTrue(result.reason.contains("Confiance de recherche"))
    }

    @Test
    fun parserAcceptsOnlyStructuredJsonPayload() {
        val raw = """
            prefix ignored
            {"mechanism":"contextual bandit routing","rationale":"Use uncertainty-aware exploration to avoid repeating a fixed scoring heuristic.","prediction":"Reduce repeated route failures without increasing latency materially.","falsification":"Reject if paired canary reliability falls or score fails to improve.","evidence_indexes":[0,1],"uncertainty":"Evidence is indirect and needs a controlled trial.","confidence":0.74}
        """.trimIndent()
        val parsed = EvolutionResearchHypothesisParser.parse(raw)

        assertNotNull(parsed)
        assertEquals(listOf(0, 1), parsed?.evidenceIndexes)
    }
    @Test
    fun factoryNeverCreatesExecutableObject() {
        val hypothesis = EvolutionResearchHypothesisFactory.create(
            record = record(),
            draft = draft(evidenceIndexes = listOf(0, 1)),
            now = 123L
        )

        assertEquals(EvolutionResearchHypothesisStatus.EVIDENCE_SUPPORTED, hypothesis.status)
        assertEquals(123L, hypothesis.createdAt)
        assertEquals(2, hypothesis.evidenceUrls.size)
    }

    @Test
    fun plannerSkipsAlreadyProcessedResearchRecord() {
        val source = record()
        val existing = EvolutionResearchHypothesisFactory.create(
            source,
            draft(evidenceIndexes = listOf(0, 1))
        )

        assertNull(
            EvolutionResearchHypothesisPlanner.selectRecord(
                records = listOf(source),
                hypotheses = listOf(existing)
            )
        )
    }

    private fun draft(evidenceIndexes: List<Int>) = EvolutionResearchHypothesisDraft(
        mechanism = "contextual bandit routing",
        rationale = "Use uncertainty-aware exploration to avoid repeating a fixed scoring heuristic.",
        prediction = "Reduce repeated route failures without materially increasing latency.",
        falsification = "Reject if paired canary reliability falls or score fails to improve.",
        evidenceIndexes = evidenceIndexes,        uncertainty = "Evidence is indirect and needs a controlled trial.",
        modelConfidence = 0.74
    )

    private fun record(): EvolutionFailureResearchRecord = EvolutionFailureResearchRecord(
        recordId = "research-1",
        lessonId = "lesson-1",
        hypothesisKey = "LATENCY|text_analysis",
        mutationKey = "LATENCY|text_analysis|historyDurationScaleMs:down",
        question = "Which alternative routing mechanism could improve latency safely?",
        queries = listOf("contextual bandit routing distributed systems"),
        evidence = listOf(
            EvolutionFailureResearchEvidence(
                provider = "github",
                title = "Adaptive routing study",
                url = "https://example.invalid/a",
                snippet = "Contextual selection can balance exploration and exploitation.",
                confidence = 0.82,
                primarySource = false
            ),
            EvolutionFailureResearchEvidence(
                provider = "wikipedia",
                title = "Multi-armed bandit",
                url = "https://example.invalid/b",
                snippet = "Bandit methods trade off exploitation against exploration.",
                confidence = 0.78,
                primarySource = false
            )
        ),
        confidence = 0.76,
        providerErrors = emptyList(),
        createdAt = 100L
    )
}