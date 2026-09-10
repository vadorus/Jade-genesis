package com.jadegenesis.mobile.memory

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: MemoryEntity)

    @Query(
        """
        SELECT * FROM memory_events
        WHERE supersededBy IS NULL
        ORDER BY createdAt DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun latest(limit: Int): List<MemoryEntity>

    @Query(
        """
        SELECT * FROM memory_events
        WHERE supersededBy IS NULL
          AND source LIKE 'JADE_CONSOLIDATION_%'
        ORDER BY createdAt DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun latestConsolidated(limit: Int): List<MemoryEntity>

    @Query(
        """
        SELECT * FROM memory_events
        WHERE supersededBy IS NULL
          AND source = 'USER'
        ORDER BY createdAt DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun latestUserFacts(limit: Int): List<MemoryEntity>

    @Query(
        """
        SELECT * FROM memory_events
        WHERE id IN (:ids)
          AND supersededBy IS NULL
        ORDER BY createdAt DESC, id DESC
        """
    )
    suspend fun activeByIds(ids: List<String>): List<MemoryEntity>

    @Query(
        """
        SELECT * FROM memory_events
        WHERE supersededBy IS NULL
          AND content LIKE '%' || :query || '%'
        ORDER BY createdAt DESC, id DESC
        LIMIT :limit
        """
    )
    suspend fun search(query: String, limit: Int): List<MemoryEntity>

    @Query(
        """
        UPDATE memory_events
        SET lastRecalledAt = :recalledAt,
            recallCount = recallCount + 1
        WHERE id IN (:ids)
        """
    )
    suspend fun markRecalled(ids: List<String>, recalledAt: Long)

    @Query(
        """
        UPDATE memory_events
        SET verifiedAt = :verifiedAt
        WHERE id = :id
        """
    )
    suspend fun markVerified(id: String, verifiedAt: Long)

    @Query(
        """
        UPDATE memory_events
        SET supersededBy = :replacementId
        WHERE id = :id
          AND id != :replacementId
        """
    )
    suspend fun markSuperseded(id: String, replacementId: String)

    @Query(
        """
        SELECT * FROM memory_events
        WHERE supersededBy IS NULL
          AND source NOT LIKE 'JADE_CONSOLIDATION_%'
          AND (
                createdAt > :afterCreatedAt
                OR (createdAt = :afterCreatedAt AND id > :afterId)
              )
        ORDER BY createdAt ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun consolidationCandidates(
        afterCreatedAt: Long,
        afterId: String,
        limit: Int
    ): List<MemoryEntity>

    @Query(
        """
        SELECT * FROM memory_events
        WHERE id IN (:ids)
        ORDER BY createdAt DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun newestByIds(ids: List<String>): MemoryEntity?

    @Query(
        """
        SELECT id FROM memory_events
        WHERE createdAt <= :processedThroughCreatedAt
          AND createdAt < :cutoffCreatedAt
          AND supersededBy IS NULL
          AND source != 'USER'
          AND verifiedAt IS NULL
          AND recallCount < :recallProtectionCount
          AND (
                confidence < :maxConfidence
                OR (
                    type = 'OBSERVATION'
                    AND source LIKE 'VISION_%'
                    AND confidence <= :maxTransientVisionConfidence
                )
              )
          AND type IN ('OBSERVATION', 'HYPOTHESIS', 'FAILURE')
        ORDER BY createdAt ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun ephemeralRetentionCandidateIds(
        processedThroughCreatedAt: Long,
        cutoffCreatedAt: Long,
        recallProtectionCount: Int,
        maxConfidence: Double,
        maxTransientVisionConfidence: Double,
        limit: Int
    ): List<String>

    @Query(
        """
        SELECT id FROM memory_events
        WHERE createdAt <= :processedThroughCreatedAt
          AND createdAt < :cutoffCreatedAt
          AND supersededBy IS NOT NULL
          AND source != 'USER'
        ORDER BY createdAt ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun supersededRetentionCandidateIds(
        processedThroughCreatedAt: Long,
        cutoffCreatedAt: Long,
        limit: Int
    ): List<String>

    @Query("DELETE FROM memory_events WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    @Query("SELECT COUNT(*) FROM memory_events")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM memory_events WHERE supersededBy IS NULL")
    suspend fun activeCount(): Int
}
