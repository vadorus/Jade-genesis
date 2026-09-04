package com.jadegenesis.mobile.memory

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: MemoryEntity)

    @Query("SELECT * FROM memory_events ORDER BY createdAt DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<MemoryEntity>

    @Query("""
        SELECT * FROM memory_events
        WHERE content LIKE '%' || :query || '%'
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun search(query: String, limit: Int): List<MemoryEntity>

    @Query("SELECT COUNT(*) FROM memory_events")
    suspend fun count(): Int
}
