package com.jadegenesis.mobile.night

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightEvidenceReviewerTest {
    private val now = 1_800_000_000_000L
    private val candidate = NightReviewCandidate("old-knowledge", "KNOWLEDGE_NEEDS_EVIDENCE", 2)
    private val fingerprint = "a".repeat(64)

    private fun evidence(
        id: String,
        origin: String = id,
        type: EvidenceType = EvidenceType.PRIMARY_DOCUMENT,
        stance: EvidenceDirection = EvidenceDirection.SUPPORTS,
        memoryId: String = candidate.memoryId,
        inspected: Boolean = true,
        observedAt: Long = now - 1_000L,
        sha256: String = fingerprint
    ) = ReviewEvidence(id, memoryId, origin, type, stance, observedAt, inspected, sha256)

    @Test fun noEvidenceIsNotAClaimOfTruth() {
        val review = NightEvidenceReviewer.assess(candidate, emptyList(), now)
        assertEquals(ReviewDecision.NEEDS_EVIDENCE, review.decision)
        assertFalse(review.automaticPromotion)
    }

    @Test fun twoIndependentDocumentsWithoutExperimentAreNotEnough() {
        val review = NightEvidenceReviewer.assess(candidate, listOf(
            evidence("official", origin = "docs.example.org"),
            evidence("other", origin = "paper.example.org", type = EvidenceType.SECONDARY_DOCUMENT)
        ), now)
        assertEquals(ReviewDecision.NEEDS_EXPERIMENT, review.decision)
    }

    @Test fun twoDocumentsAndReproducedTestStillRequireSeparateApproval() {
        val review = NightEvidenceReviewer.assess(candidate, listOf(
            evidence("official", origin = "docs.example.org"),
            evidence("other", origin = "paper.example.org", type = EvidenceType.SECONDARY_DOCUMENT),
            evidence("test", origin = "isolated-test-run-123", type = EvidenceType.REPLAYED_TEST)
        ), now)
        assertEquals(ReviewDecision.READY_FOR_REVIEW, review.decision)
        assertEquals(1, review.replayedTestCount)
        assertFalse(review.automaticPromotion)
    }

    @Test fun contradictoryEvidenceBlocksUnchallengedVerdict() {
        val review = NightEvidenceReviewer.assess(candidate, listOf(
            evidence("official"),
            evidence("other", type = EvidenceType.SECONDARY_DOCUMENT),
            evidence("test", type = EvidenceType.REPLAYED_TEST),
            evidence("counterexample", stance = EvidenceDirection.REFUTES)
        ), now)
        assertEquals(ReviewDecision.CONTESTED, review.decision)
        assertEquals(1, review.refuteCount)
        assertFalse(review.automaticPromotion)
    }

    @Test fun sameOriginTwiceIsNotIndependentConfirmation() {
        val review = NightEvidenceReviewer.assess(candidate, listOf(
            evidence("first", origin = "same-docs"),
            evidence("mirror", origin = "same-docs"),
            evidence("test", type = EvidenceType.REPLAYED_TEST)
        ), now)
        assertEquals(ReviewDecision.NEEDS_INDEPENDENT_SOURCES, review.decision)
    }

    @Test fun secondaryOnlySourcesCannotSubstitutePrimarySource() {
        val review = NightEvidenceReviewer.assess(candidate, listOf(
            evidence("one", type = EvidenceType.SECONDARY_DOCUMENT),
            evidence("two", type = EvidenceType.SECONDARY_DOCUMENT),
            evidence("replay", type = EvidenceType.REPLAYED_TEST)
        ), now)
        assertEquals(ReviewDecision.NEEDS_INDEPENDENT_SOURCES, review.decision)
    }

    @Test fun ignoresUninspectedStaleUnrelatedOrUnsignedReports() {
        val report = NightEvidenceReviewer.assess(candidate, listOf(
            evidence("uninspected", inspected = false),
            evidence("stale", observedAt = now - 190L * 86_400_000L),
            evidence("future", observedAt = now + 60_000L),
            evidence("unrelated", memoryId = "another-memory"),
            evidence("unsigned", sha256 = ""),
            evidence("valid")
        ), now)
        assertEquals(ReviewDecision.NEEDS_INDEPENDENT_SOURCES, report.decision)
        assertEquals(1, report.supportCount)
    }

    @Test fun duplicateEvidenceIdIsNotAdditionalProof() {
        val report = NightEvidenceReviewer.assess(candidate, listOf(
            evidence("same", origin = "docs"),
            evidence("same", origin = "copy"),
            evidence("replay", type = EvidenceType.REPLAYED_TEST)
        ), now)
        assertEquals(ReviewDecision.NEEDS_INDEPENDENT_SOURCES, report.decision)
        assertEquals(2, report.supportCount)
    }

    @Test fun reviewAgendaIsBoundedWithoutChangingTheMemories() {
        val agenda = (1..30).map { NightReviewCandidate("m-$it", "FAILURE_TO_REEXAMINE", 4) }
        val result = NightEvidenceReviewer.reviewAgenda(agenda, emptyList(), now)
        assertEquals(8, result.size)
        assertTrue(result.all { it.decision == ReviewDecision.NEEDS_EVIDENCE })
    }
}
