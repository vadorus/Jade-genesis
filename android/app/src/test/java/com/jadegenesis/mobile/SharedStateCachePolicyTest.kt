package com.jadegenesis.mobile

import com.jadegenesis.mobile.state.SharedStateCachePolicy
import com.jadegenesis.mobile.state.SharedStateEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedStateCachePolicyTest {

    @Test
    fun operationalSnapshotsCoalesceByOriginKindAndEntity() {
        val events = mutableListOf<SharedStateEvent>()
        var revision = 0L
        repeat(100) { round ->
            SharedStateCachePolicy.COALESCED_OPERATIONAL_KINDS.forEach { kind ->
                revision += 1
                events += event(
                    id = "op-$kind-$round",
                    kind = kind,
                    entityId = if (kind == "phone_node_snapshot") "pixel" else "current",
                    revision = revision
                )
            }
        }

        val compacted = SharedStateCachePolicy.compact(events, maxEvents = 500)

        assertEquals(
            SharedStateCachePolicy.COALESCED_OPERATIONAL_KINDS.size,
            compacted.size
        )
        SharedStateCachePolicy.COALESCED_OPERATIONAL_KINDS.forEach { kind ->
            assertEquals(1, compacted.count { it.kind == kind })
            assertTrue(
                compacted.single { it.kind == kind }
                    .eventId
                    .contains("-99")
            )
        }
    }

    @Test
    fun rareNightLearningReportsSurviveOperationalChurn() {
        val rare = event(
            id = "night-report-1",
            kind = "vps_learning_snapshot",
            entityId = "night-1",
            revision = 1L
        )
        val events = mutableListOf(rare)
        var revision = 1L
        repeat(120) { round ->
            SharedStateCachePolicy.COALESCED_OPERATIONAL_KINDS.forEach { kind ->
                revision += 1
                events += event(
                    id = "snapshot-$kind-$round",
                    kind = kind,
                    entityId = "current",
                    revision = revision
                )
            }
        }

        val compacted = SharedStateCachePolicy.compact(events, maxEvents = 500)

        assertTrue(compacted.any { it.eventId == rare.eventId })
        assertEquals(
            1 + SharedStateCachePolicy.COALESCED_OPERATIONAL_KINDS.size,
            compacted.size
        )
    }

    @Test
    fun differentOriginsDoNotOverwriteEachOther() {
        val first = event(
            id = "pixel-runtime",
            origin = "pixel",
            kind = "runtime_eval_snapshot",
            entityId = "current",
            revision = 1L
        )
        val second = event(
            id = "pc-runtime",
            origin = "pc",
            kind = "runtime_eval_snapshot",
            entityId = "current",
            revision = 2L
        )

        val compacted = SharedStateCachePolicy.compact(
            listOf(first, second),
            maxEvents = 500
        )

        assertEquals(2, compacted.size)
    }

    @Test
    fun nonOperationalEventsStillDeduplicateOnlyByEventId() {
        val first = event(
            id = "learning-a",
            kind = "vps_learning_snapshot",
            entityId = "same",
            revision = 1L
        )
        val second = event(
            id = "learning-b",
            kind = "vps_learning_snapshot",
            entityId = "same",
            revision = 2L
        )

        val compacted = SharedStateCachePolicy.compact(
            listOf(first, second),
            maxEvents = 500
        )

        assertEquals(2, compacted.size)
    }

    private fun event(
        id: String,
        origin: String = "pixel",
        kind: String,
        entityId: String,
        revision: Long
    ): SharedStateEvent = SharedStateEvent(
        eventId = id,
        originNode = origin,
        kind = kind,
        entityId = entityId,
        payload = "{}",
        createdAt = revision * 1_000L,
        serverRevision = revision
    )
}
