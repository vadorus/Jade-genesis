package com.jadegenesis.mobile

import com.jadegenesis.mobile.config.RetentionTuning
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.memory.MemoryDao
import com.jadegenesis.mobile.memory.MemoryEntity
import com.jadegenesis.mobile.memory.MemoryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryArchitectureTest {

    @Test
    fun contextReadMarksOnlyReturnedMemoriesAsRecalled() = runBlocking {
        val dao = FakeMemoryDao().apply {
            latestItems = listOf(
                memory("m1", createdAt = 10L),
                memory("m2", createdAt = 20L)
            )
        }
        val store = MemoryStore(dao)

        val result = store.latestForContext(2)

        assertEquals(listOf("m1", "m2"), result.map { it.id })
        assertEquals(listOf("m1", "m2"), dao.lastRecalledIds)
        assertTrue(dao.lastRecalledAt > 0L)
    }

    @Test
    fun cognitiveContextReservesDurableKnowledgeAgainstRecentNoise() = runBlocking {
        val recentNoise = (1..24).map { index ->
            memory("noise-$index", createdAt = 1_000L + index)
        }
        val user = memory(
            id = "user-fact",
            createdAt = 100L,
            source = "USER",
            type = "FACT",
            confidence = 1.0
        )
        val knowledge = memory(
            id = "knowledge-1",
            createdAt = 90L,
            source = "JADE_CONSOLIDATION_NIGHT",
            type = "KNOWLEDGE",
            confidence = 0.95
        )
        val dao = FakeMemoryDao().apply {
            latestItems = recentNoise
            latestUserItems = listOf(user)
            latestConsolidatedItems = listOf(knowledge)
        }
        val store = MemoryStore(dao)

        val result = store.latestForContext(8)

        assertEquals(8, result.size)
        assertTrue(result.any { it.id == "user-fact" })
        assertTrue(result.any { it.id == "knowledge-1" })
        assertTrue(dao.lastRecalledIds.contains("user-fact"))
        assertTrue(dao.lastRecalledIds.contains("knowledge-1"))
    }

    @Test
    fun administrativeReadDoesNotInflateRecallCounters() = runBlocking {
        val dao = FakeMemoryDao().apply {
            latestItems = listOf(memory("m1", createdAt = 10L))
        }
        val store = MemoryStore(dao)

        store.latest(1)

        assertTrue(dao.lastRecalledIds.isEmpty())
    }

    @Test
    fun consolidationBatchIsBoundedByImmutableSafetyPolicy() = runBlocking {
        val dao = FakeMemoryDao()
        val store = MemoryStore(dao)

        store.consolidationBatch(
            afterCreatedAt = 123L,
            afterId = "cursor-id",
            limit = Int.MAX_VALUE
        )

        assertEquals(123L, dao.lastConsolidationAfterCreatedAt)
        assertEquals("cursor-id", dao.lastConsolidationAfterId)
        assertEquals(
            SafetyPolicy.MAX_MEMORY_ITEMS_PER_TASK,
            dao.lastConsolidationLimit
        )
    }

    @Test
    fun retentionCannotBecomeMoreAggressiveThanSafetyPolicy() = runBlocking {
        val dao = FakeMemoryDao().apply {
            supersededCandidateIds = listOf("old-superseded")
            ephemeralCandidateIds = listOf("old-ephemeral")
        }
        val store = MemoryStore(dao)
        val now = 1_900_000_000_000L
        val tuning = RetentionTuning(
            ephemeralMemoryRetentionDays = 1,
            supersededMemoryRetentionDays = 1,
            memoryRecallProtectionCount = 0,
            memoryLowConfidenceThreshold = 0.99,
            memoryPurgeBatchSize = 10_000
        )

        val result = store.applyRetention(
            processedThroughCreatedAt = now,
            tuning = tuning,
            now = now
        )

        val dayMs = 24L * 60L * 60L * 1_000L
        assertEquals(
            now - SafetyPolicy.MIN_SUPERSEDED_MEMORY_RETENTION_DAYS * dayMs,
            dao.lastSupersededCutoff
        )
        assertEquals(
            now - SafetyPolicy.MIN_EPHEMERAL_MEMORY_RETENTION_DAYS * dayMs,
            dao.lastEphemeralCutoff
        )
        assertEquals(
            SafetyPolicy.MIN_RECALL_PROTECTION_COUNT,
            dao.lastRecallProtectionCount
        )
        assertEquals(
            SafetyPolicy.MAX_AUTO_DELETE_CONFIDENCE,
            dao.lastMaxConfidence,
            0.0001
        )
        assertEquals(
            SafetyPolicy.MAX_TRANSIENT_VISION_RETENTION_CONFIDENCE,
            dao.lastMaxTransientVisionConfidence,
            0.0001
        )
        assertTrue(dao.lastSupersededLimit <= SafetyPolicy.MAX_MEMORY_PURGE_BATCH_SIZE)
        assertTrue(dao.lastEphemeralLimit <= SafetyPolicy.MAX_MEMORY_PURGE_BATCH_SIZE)
        assertEquals(2, result.totalDeleted)
    }

    private fun memory(
        id: String,
        createdAt: Long,
        source: String = "TEST",
        type: String = "OBSERVATION",
        confidence: Double = 0.4
    ): MemoryEntity = MemoryEntity(
        id = id,
        type = type,
        content = "mémoire $id",
        source = source,
        confidence = confidence,
        originNode = "test-node",
        createdAt = createdAt
    )

    private class FakeMemoryDao : MemoryDao {
        var latestItems: List<MemoryEntity> = emptyList()
        var latestConsolidatedItems: List<MemoryEntity> = emptyList()
        var latestUserItems: List<MemoryEntity> = emptyList()
        var searchItems: List<MemoryEntity> = emptyList()
        var consolidationItems: List<MemoryEntity> = emptyList()
        var supersededCandidateIds: List<String> = emptyList()
        var ephemeralCandidateIds: List<String> = emptyList()

        var lastRecalledIds: List<String> = emptyList()
        var lastRecalledAt: Long = 0L
        var lastConsolidationAfterCreatedAt: Long = -1L
        var lastConsolidationAfterId: String = ""
        var lastConsolidationLimit: Int = -1
        var lastSupersededCutoff: Long = -1L
        var lastEphemeralCutoff: Long = -1L
        var lastRecallProtectionCount: Int = -1
        var lastMaxConfidence: Double = -1.0
        var lastMaxTransientVisionConfidence: Double = -1.0
        var lastSupersededLimit: Int = -1
        var lastEphemeralLimit: Int = -1

        private val inserted = mutableMapOf<String, MemoryEntity>()

        override suspend fun insert(event: MemoryEntity) {
            inserted[event.id] = event
        }

        override suspend fun latest(limit: Int): List<MemoryEntity> =
            latestItems.take(limit)

        override suspend fun latestConsolidated(limit: Int): List<MemoryEntity> =
            latestConsolidatedItems.take(limit)

        override suspend fun latestUserFacts(limit: Int): List<MemoryEntity> =
            latestUserItems.take(limit)

        override suspend fun search(query: String, limit: Int): List<MemoryEntity> =
            searchItems.take(limit)

        override suspend fun markRecalled(ids: List<String>, recalledAt: Long) {
            lastRecalledIds = ids
            lastRecalledAt = recalledAt
        }

        override suspend fun markVerified(id: String, verifiedAt: Long) = Unit

        override suspend fun markSuperseded(id: String, replacementId: String) = Unit

        override suspend fun consolidationCandidates(
            afterCreatedAt: Long,
            afterId: String,
            limit: Int
        ): List<MemoryEntity> {
            lastConsolidationAfterCreatedAt = afterCreatedAt
            lastConsolidationAfterId = afterId
            lastConsolidationLimit = limit
            return consolidationItems.take(limit)
        }

        override suspend fun newestByIds(ids: List<String>): MemoryEntity? =
            (latestItems + latestConsolidatedItems + latestUserItems +
                consolidationItems + inserted.values)
                .filter { it.id in ids }
                .maxWithOrNull(
                    compareBy<MemoryEntity> { it.createdAt }
                        .thenBy { it.id }
                )

        override suspend fun ephemeralRetentionCandidateIds(
            processedThroughCreatedAt: Long,
            cutoffCreatedAt: Long,
            recallProtectionCount: Int,
            maxConfidence: Double,
            maxTransientVisionConfidence: Double,
            limit: Int
        ): List<String> {
            lastEphemeralCutoff = cutoffCreatedAt
            lastRecallProtectionCount = recallProtectionCount
            lastMaxConfidence = maxConfidence
            lastMaxTransientVisionConfidence = maxTransientVisionConfidence
            lastEphemeralLimit = limit
            return ephemeralCandidateIds.take(limit)
        }

        override suspend fun supersededRetentionCandidateIds(
            processedThroughCreatedAt: Long,
            cutoffCreatedAt: Long,
            limit: Int
        ): List<String> {
            lastSupersededCutoff = cutoffCreatedAt
            lastSupersededLimit = limit
            return supersededCandidateIds.take(limit)
        }

        override suspend fun deleteByIds(ids: List<String>): Int = ids.size

        override suspend fun count(): Int = latestItems.size

        override suspend fun activeCount(): Int = latestItems.size
    }
}
