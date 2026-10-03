package com.jadegenesis.mobile.cognitive

import android.content.Context
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.model.MemorySnapshot
import com.jadegenesis.mobile.research.ResearchEngine
import java.util.UUID

class RightHandLearningCoordinator(context: Context) {
    private val store = RightHandLearningStore(context.applicationContext)
    private val research = ResearchEngine()

    suspend fun contextFor(input: String): List<MemorySnapshot> {
        RightHandLearningPolicy.explicitPreference(input)?.let(store::recordPreference)
        val memories = store.contextMemories(
            input = input,
            limit = SafetyPolicy.MAX_RIGHT_HAND_CONTEXT_ITEMS
        ).toMutableList()

        val need = RightHandLearningPolicy.humanKnowledgeNeed(input)
            ?: return memories
        val knowledge = runCatching { ensureHumanKnowledge(need) }.getOrNull()
        knowledge?.let(store::knowledgeMemory)?.let(memories::add)
        return memories.distinctBy { it.id }
            .take(SafetyPolicy.MAX_RIGHT_HAND_CONTEXT_ITEMS + 1)
    }
    private suspend fun ensureHumanKnowledge(need: HumanKnowledgeNeed): HumanKnowledgeRecord {
        val now = System.currentTimeMillis()
        val existing = store.latestKnowledge(need.domain)
        if (
            existing != null &&
            now - existing.createdAt <= SafetyPolicy.RIGHT_HAND_HUMAN_KNOWLEDGE_FRESH_MS
        ) {
            return existing
        }

        val report = research.investigate(
            observation = "General human psychology knowledge request. No personal user data included. Domain=${need.domain.name}.",
            focusInstruction = need.researchQuestion
        )
        val evidence = report.evidence
            .take(SafetyPolicy.MAX_RIGHT_HAND_HUMAN_KNOWLEDGE_EVIDENCE)
            .map { item ->
                HumanKnowledgeEvidence(
                    provider = item.provider.take(120),
                    title = item.title.take(300),
                    url = item.url.take(1_000),
                    snippet = item.snippet.take(800),
                    confidence = item.confidence.coerceIn(0.0, 1.0)
                )
            }
        val record = HumanKnowledgeRecord(
            id = "human-knowledge-${UUID.randomUUID()}",
            domain = need.domain,
            question = need.researchQuestion,
            evidence = evidence,
            confidence = minOf(report.confidence, need.confidence).coerceIn(0.0, 1.0),
            createdAt = now
        )
        store.recordKnowledge(record)
        return record
    }
}

object RightHandLearningRuntime {
    @Volatile
    private var coordinator: RightHandLearningCoordinator? = null

    fun initialize(context: Context): RightHandLearningCoordinator = synchronized(this) {
        coordinator ?: RightHandLearningCoordinator(context.applicationContext).also {
            coordinator = it
        }
    }

    fun currentOrNull(): RightHandLearningCoordinator? = coordinator

    fun current(): RightHandLearningCoordinator =
        coordinator ?: error("RightHandLearningRuntime non initialisé")
}
