package com.jadegenesis.mobile.night

import com.jadegenesis.mobile.model.MemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightMemoryReviewAgendaTest {
    private val now = 1_800_000_000_000L

    private fun item(
        id: String,
        type: String,
        confidence: Double = 0.5,
        source: String = "JADE_CONSOLIDATION_NIGHT",
        createdAt: Long = now - 3_600_000L,
        verifiedAt: Long? = null,
        supersededBy: String? = null
    ) = MemorySnapshot(
        id = id,
        type = type,
        content = "sample private text which must not be copied into the agenda",
        source = source,
        confidence = confidence,
        createdAt = createdAt,
        originNode = "test-node",
        verifiedAt = verifiedAt,
        supersededBy = supersededBy
    )

    @Test
    fun prioritizesFailuresAndHypothesesOverUncertainKnowledge() {
        val agenda = NightMemoryReviewPlanner.propose(listOf(
            item("knowledge", "KNOWLEDGE", confidence = 0.7),
            item("failure", "FAILURE"),
            item("hypothesis", "HYPOTHESIS")
        ), now)
        assertEquals(listOf("failure", "hypothesis", "knowledge"), agenda.map { it.memoryId })
        assertEquals("FAILURE_TO_REEXAMINE", agenda.first().reason)
    }

    @Test
    fun excludesUserFactsVerifiedOldOrSupersededItems() {
        val agenda = NightMemoryReviewPlanner.propose(listOf(
            item("user", "KNOWLEDGE", source = "USER"),
            item("verified", "KNOWLEDGE", verifiedAt = now - 1L),
            item("superseded", "HYPOTHESIS", supersededBy = "replacement"),
            item("old", "FAILURE", createdAt = now - 48L * 3_600_000L),
            item("future", "FAILURE", createdAt = now + 1_000L),
            item("routine", "OBSERVATION"),
            item("confident", "KNOWLEDGE", confidence = 0.95),
            item("allowed", "HYPOTHESIS")
        ), now)
        assertEquals(listOf("allowed"), agenda.map { it.memoryId })
    }

    @Test
    fun neverCopiesMemoryTextIntoReviewCandidate() {
        val agenda = NightMemoryReviewPlanner.propose(
            listOf(item("example", "HYPOTHESIS")), now
        )
        assertEquals("example", agenda.single().memoryId)
        assertTrue(agenda.single().toString().contains("sample private text").not())
    }

    @Test
    fun isBoundedAndDeduplicatesCandidates() {
        val agenda = NightMemoryReviewPlanner.propose(
            (0..20).map { item("failure-${it % 15}", "FAILURE") }, now,
            limit = Int.MAX_VALUE
        )
        assertEquals(8, agenda.size)
        assertEquals(8, agenda.distinctBy { it.memoryId }.size)
    }
}
