package com.jadegenesis.mobile.memory

import com.jadegenesis.mobile.cognitive.ConversationLearningRuntime
import com.jadegenesis.mobile.config.RetentionTuning
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.model.MemorySnapshot
import com.jadegenesis.mobile.model.MemoryType
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

data class MemoryRetentionResult(
    val deletedSuperseded: Int,
    val deletedEphemeral: Int,
    val processedThroughCreatedAt: Long
) {
    val totalDeleted: Int
        get() = deletedSuperseded + deletedEphemeral
}

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

    /**
     * Lecture d'affichage/administration : ne compte pas comme un rappel cognitif.
     */
    suspend fun latest(limit: Int = 20): List<MemorySnapshot> =
        dao.latest(limit.coerceAtLeast(1)).map { it.toSnapshot() }

    /**
     * Mémoire réellement injectée dans un contexte de raisonnement.
     *
     * Depuis 0.1.15, le contexte conversationnel local borné est injecté en
     * premier lorsqu'un message utilisateur est en cours. Il ne remplace pas
     * la mémoire Room durable et ne compte pas artificiellement comme rappel
     * d'une ligne de la base. Le reste du budget est rempli avec la mémoire
     * persistante existante, qui conserve son suivi de rappel normal.
     */
    suspend fun latestForContext(limit: Int = 20): List<MemorySnapshot> {
        val safeLimit = limit.coerceAtLeast(1)
        val conversation = ConversationLearningRuntime.currentOrNull()
            ?.contextMemories(
                min(
                    safeLimit,
                    SafetyPolicy.MAX_CONVERSATION_LEARNING_CONTEXT_ITEMS
                )
            )
            .orEmpty()
            .take(safeLimit)

        val persistentLimit = (safeLimit - conversation.size).coerceAtLeast(0)
        if (persistentLimit == 0) return conversation

        val entities = dao.latest(persistentLimit)
        val recalledAt = markRecalled(entities)
        val persistent = entities.map { entity ->
            entity.toSnapshot(
                recalledAtOverride = recalledAt,
                recallCountOverride = entity.recallCount + 1
            )
        }
        return (conversation + persistent)
            .distinctBy { it.id }
            .take(safeLimit)
    }

    suspend fun search(query: String, limit: Int = 10): List<MemorySnapshot> =
        dao.search(query.trim(), limit.coerceAtLeast(1)).map { it.toSnapshot() }

    suspend fun searchForContext(
        query: String,
        limit: Int = 10
    ): List<MemorySnapshot> {
        val entities = dao.search(query.trim(), limit.coerceAtLeast(1))
        val recalledAt = markRecalled(entities)
        return entities.map { entity ->
            entity.toSnapshot(
                recalledAtOverride = recalledAt,
                recallCountOverride = entity.recallCount + 1
            )
        }
    }

    /**
     * Parcourt l'historique dans l'ordre chronologique avec un curseur stable.
     * La consolidation ne dépend donc plus des N souvenirs les plus récents.
     */
    suspend fun consolidationBatch(
        afterCreatedAt: Long,
        afterId: String,
        limit: Int
    ): List<MemorySnapshot> =
        dao.consolidationCandidates(
            afterCreatedAt = afterCreatedAt.coerceAtLeast(0L),
            afterId = afterId,
            limit = limit.coerceIn(1, SafetyPolicy.MAX_MEMORY_ITEMS_PER_TASK)
        ).map { it.toSnapshot() }

    suspend fun markVerified(id: String, verifiedAt: Long = System.currentTimeMillis()) {
        require(id.isNotBlank()) { "Identifiant mémoire vide." }
        dao.markVerified(id, verifiedAt)
    }

    suspend fun markSuperseded(id: String, replacementId: String) {
        require(id.isNotBlank()) { "Identifiant mémoire vide." }
        require(replacementId.isNotBlank()) { "Identifiant de remplacement vide." }
        require(id != replacementId) { "Une mémoire ne peut pas se remplacer elle-même." }
        dao.markSuperseded(id, replacementId)
    }

    /**
     * Purge uniquement ce que le cycle de consolidation a déjà dépassé.
     * Les faits USER, mémoires vérifiées, fortement rappelées ou trop confiantes
     * restent protégés par la requête DAO et par les plafonds SafetyPolicy.
     */
    suspend fun applyRetention(
        processedThroughCreatedAt: Long,
        tuning: RetentionTuning,
        now: Long = System.currentTimeMillis()
    ): MemoryRetentionResult {
        if (processedThroughCreatedAt <= 0L) {
            return MemoryRetentionResult(0, 0, 0L)
        }

        val safeEphemeralDays = max(
            tuning.ephemeralMemoryRetentionDays,
            SafetyPolicy.MIN_EPHEMERAL_MEMORY_RETENTION_DAYS
        )
        val safeSupersededDays = max(
            tuning.supersededMemoryRetentionDays,
            SafetyPolicy.MIN_SUPERSEDED_MEMORY_RETENTION_DAYS
        )
        val safeRecallProtection = max(
            tuning.memoryRecallProtectionCount,
            SafetyPolicy.MIN_RECALL_PROTECTION_COUNT
        )
        val safeConfidence = min(
            tuning.memoryLowConfidenceThreshold,
            SafetyPolicy.MAX_AUTO_DELETE_CONFIDENCE
        )
        val safeBatch = min(
            tuning.memoryPurgeBatchSize,
            SafetyPolicy.MAX_MEMORY_PURGE_BATCH_SIZE
        ).coerceAtLeast(1)

        val supersededCutoff = now - daysToMillis(safeSupersededDays)
        val ephemeralCutoff = now - daysToMillis(safeEphemeralDays)

        val supersededIds = dao.supersededRetentionCandidateIds(
            processedThroughCreatedAt = processedThroughCreatedAt,
            cutoffCreatedAt = supersededCutoff,
            limit = safeBatch
        )
        val deletedSuperseded = if (supersededIds.isEmpty()) {
            0
        } else {
            dao.deleteByIds(supersededIds)
        }

        val remaining = (safeBatch - deletedSuperseded).coerceAtLeast(0)
        val ephemeralIds = if (remaining == 0) {
            emptyList()
        } else {
            dao.ephemeralRetentionCandidateIds(
                processedThroughCreatedAt = processedThroughCreatedAt,
                cutoffCreatedAt = ephemeralCutoff,
                recallProtectionCount = safeRecallProtection,
                maxConfidence = safeConfidence,
                limit = remaining
            )
        }
        val deletedEphemeral = if (ephemeralIds.isEmpty()) {
            0
        } else {
            dao.deleteByIds(ephemeralIds)
        }

        return MemoryRetentionResult(
            deletedSuperseded = deletedSuperseded,
            deletedEphemeral = deletedEphemeral,
            processedThroughCreatedAt = processedThroughCreatedAt
        )
    }

    suspend fun count(): Int = dao.count()

    suspend fun activeCount(): Int = dao.activeCount()

    private suspend fun markRecalled(entities: List<MemoryEntity>): Long {
        val ids = entities.map { it.id }.distinct()
        if (ids.isEmpty()) return 0L

        val recalledAt = System.currentTimeMillis()
        dao.markRecalled(ids, recalledAt)
        return recalledAt
    }

    private fun daysToMillis(days: Int): Long =
        days.toLong().coerceAtLeast(1L) * 24L * 60L * 60L * 1_000L

    private fun MemoryEntity.toSnapshot(
        recalledAtOverride: Long? = null,
        recallCountOverride: Int? = null
    ) = MemorySnapshot(
        id = id,
        type = type,
        content = content,
        source = source,
        confidence = confidence,
        createdAt = createdAt,
        originNode = originNode,
        lastRecalledAt = recalledAtOverride ?: lastRecalledAt,
        recallCount = recallCountOverride ?: recallCount,
        verifiedAt = verifiedAt,
        supersededBy = supersededBy
    )
}
