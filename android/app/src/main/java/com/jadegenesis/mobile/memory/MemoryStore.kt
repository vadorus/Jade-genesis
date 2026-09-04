package com.jadegenesis.mobile.memory

import com.jadegenesis.mobile.model.MemorySnapshot
import com.jadegenesis.mobile.model.MemoryType
import java.util.UUID

class MemoryStore(private val dao: MemoryDao) {

    suspend fun remember(
        type: MemoryType,
        content: String,
        source: String,
        confidence: Double,
        originNode: String
    ): MemoryEntity {
        val event = MemoryEntity(
            id = UUID.randomUUID().toString(),
            type = type.name,
            content = content.trim(),
            source = source,
            confidence = confidence.coerceIn(0.0, 1.0),
            originNode = originNode,
            createdAt = System.currentTimeMillis()
        )
        dao.insert(event)
        return event
    }

    suspend fun rememberUserFact(content: String, originNode: String) =
        remember(
            type = MemoryType.FACT,
            content = content,
            source = "USER",
            confidence = 1.0,
            originNode = originNode
        )

    suspend fun latest(limit: Int = 20): List<MemorySnapshot> =
        dao.latest(limit).map { it.toSnapshot() }

    suspend fun search(query: String, limit: Int = 10): List<MemorySnapshot> =
        dao.search(query, limit).map { it.toSnapshot() }

    suspend fun count(): Int = dao.count()

    private fun MemoryEntity.toSnapshot() = MemorySnapshot(
        id = id,
        type = type,
        content = content,
        source = source,
        confidence = confidence,
        createdAt = createdAt
    )
}
