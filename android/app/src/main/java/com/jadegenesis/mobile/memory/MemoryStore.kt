package com.jadegenesis.mobile.memory

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
     * Le contexte ne doit pas être composé uniquement des événements les plus
     * récents : un flux visuel ou de télémétrie pouvait auparavant repousser le
     * savoir consolidé hors de la fenêtre. On réserve donc de petits quotas aux
     * faits USER et aux connaissances JADE_CONSOLIDATION, puis on remplit le
     * reste avec les souvenirs actifs les plus récents. Seuls les éléments
     * réellement retournés sont marqués comme rappelés.
     */
    suspend fun latestForContext(limit: Int = 20): List<MemorySnapshot> {
        val safeLimit = limit.coerceIn(1, SafetyPolicy.MAX_MEMORY_ITEMS_PER_TASK)
        val protectedQuota = (safeLimit / 4).coerceAtLeast(1).coerceAtMost(2)
        val userFacts = dao.latestUserFacts(protectedQuota)
        val consolidated = dao.latestConsolidated(protectedQuota)
        val recentWindow = (safeLimit * 3)
            .coerceAtMost(SafetyPolicy.MAX_MEMORY_ITEMS_PER_TASK)
        val recent = dao.latest(recentWindow)

        val selectedById = linkedMapOf<String, MemoryEntity>()
        userFacts.forEach { selectedById.putIfAbsent(it.id, it) }
        consolidated.forEach { selectedById.putIfAbsent(it.id, it) }
        recent.forEach { selectedById.putIfAbsent(it.id, it) }

        val selected = selectedById.values.take(safeLimit)
        val recalledAt = markRecalled(selected)
        return selected.map { entity ->
            entity.toSnapshot(
                recalledAtOverride = recalledAt,
                recallCountOverride = entity.recallCount + 1
            )
        }
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
     * Après une consolidation réussie, retire de la mémoire active uniquement
     * les doublons textuels exacts du lot traité. Ce n'est PAS une vérification
     * sémantique : aucune contradiction approximative n'est supprimée ici.
     * Les faits USER ne sont jamais auto-superseded.
     */
    suspend fun supersedeExactDuplicates(ids: List<String>): Int {
        val cleanIds = ids.filter { it.isNotBlank() }.distinct()
        if (cleanIds.size < 2) return 0

        val active = dao.activeByIds(cleanIds)
        val groups = active.groupBy { entity ->
            "${entity.type}|${normalizeExactDuplicateText(entity.content)}"
        }.values.filter { group ->
            group.size > 1 && normalizeExactDuplicateText(group.first().content).isNotBlank()
        }

        var superseded = 0
        groups.forEach { group ->
            val survivor = group.maxWith(
                compareBy<MemoryEntity> { it.source == "USER" }
                    .thenBy { it.verifiedAt != null }
                    .thenBy { it.confidence }
                    .thenBy { it.createdAt }
                    .thenBy { it.id }
            )
            group
                .filter { it.id != survivor.id && it.source != "USER" }
                .forEach { duplicate ->
                    dao.markSuperseded(duplicate.id, survivor.id)
                    superseded += 1
                }
        }
        return superseded
    }

    /**
     * Purge uniquement ce que le cycle de consolidation a déjà dépassé.
     * Les faits USER, mémoires vérifiées, fortement rappelées ou trop confiantes
     * restent protégés par la requête DAO et par les plafonds SafetyPolicy.
     *
     * Les captures visuelles sont des observations transitoires : elles disposent
     * d'un plafond de rétention dédié, compilé et conservateur, afin qu'une
     * confiance de perception standard (0.68 aujourd'hui) ne rende pas ces
     * instantanés immortels.
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
                maxTransientVisionConfidence =
                    SafetyPolicy.MAX_TRANSIENT_VISION_RETENTION_CONFIDENCE,
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

    private fun normalizeExactDuplicateText(text: String): String =
        text.trim().lowercase().replace(Regex("\\s+"), " ")

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
