package com.jadegenesis.mobile.memory

import android.content.Context
import com.jadegenesis.mobile.model.MemorySnapshot
import java.security.MessageDigest

enum class MemoryLifecycleState {
    NEW,
    CONFIRMED,
    CONTRADICTORY,
    OBSOLETE_CANDIDATE,
    STABLE
}

data class MemoryCursor(
    val createdAt: Long = 0L,
    val id: String = ""
)

data class MemoryLifecycleAnalysis(
    val sourceCount: Int,
    val newCount: Int,
    val confirmedCount: Int,
    val confirmationGroups: Int,
    val contradictionCount: Int,
    val obsoleteCandidateCount: Int,
    val sourceFingerprint: String,
    val lastConsolidatedFingerprint: String?,
    val lastConsolidatedAt: Long,
    val needsConsolidation: Boolean,
    val reason: String,
    val sourceIds: Set<String>
)

class MemoryLifecycleManager(context: Context) {
    private val prefs = context.getSharedPreferences(
        "jade_genesis_memory_lifecycle",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_LAST_FINGERPRINT = "last_source_fingerprint_v1"
        private const val KEY_LAST_SOURCE_IDS = "last_source_ids_v1"
        private const val KEY_LAST_CONSOLIDATED_AT = "last_consolidated_at_v1"
        private const val KEY_LAST_KNOWLEDGE_ID = "last_knowledge_id_v1"
        private const val KEY_LAST_RESULT_SHA256 = "last_result_sha256_v1"

        private const val KEY_CURSOR_CREATED_AT = "consolidation_cursor_created_at_v2"
        private const val KEY_CURSOR_ID = "consolidation_cursor_id_v2"

        private val STOP_WORDS = setOf(
            "le", "la", "les", "un", "une", "des", "de", "du",
            "et", "ou", "a", "à", "au", "aux", "en", "dans",
            "sur", "pour", "par", "avec", "que", "qui", "je",
            "tu", "il", "elle", "nous", "vous", "ils", "elles",
            "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa",
            "ses", "ce", "cet", "cette", "ces", "est", "sont",
            "être", "etre", "ai", "as", "avons", "avez", "ont"
        )

        private val NEGATION_WORDS = setOf(
            "ne", "n", "pas", "jamais", "aucun", "aucune",
            "non", "plus", "sans"
        )
    }

    fun currentCursor(): MemoryCursor = MemoryCursor(
        createdAt = prefs.getLong(KEY_CURSOR_CREATED_AT, 0L).coerceAtLeast(0L),
        id = prefs.getString(KEY_CURSOR_ID, "").orEmpty()
    )

    fun processedThroughCreatedAt(): Long = currentCursor().createdAt

    fun sourceMemories(
        memories: List<MemorySnapshot>,
        limit: Int = 24
    ): List<MemorySnapshot> = memories
        .filterNot { it.source.startsWith("JADE_CONSOLIDATION_") }
        .take(limit.coerceAtLeast(1))

    fun analyze(memories: List<MemorySnapshot>): MemoryLifecycleAnalysis {
        val sources = sourceMemories(
            memories = memories,
            limit = memories.size.coerceAtLeast(1)
        )
        val sourceIds = sources.map { it.id }.toSet()
        val previousIds = prefs
            .getStringSet(KEY_LAST_SOURCE_IDS, emptySet())
            .orEmpty()
            .toSet()
        val lastFingerprint = prefs
            .getString(KEY_LAST_FINGERPRINT, null)
            ?.takeIf { it.isNotBlank() }
        val lastAt = prefs.getLong(KEY_LAST_CONSOLIDATED_AT, 0L)

        val duplicateGroups = sources
            .groupBy { normalize(it.content) }
            .filterKeys { it.isNotBlank() }
            .values
            .filter { it.size > 1 }
        val confirmedIds = duplicateGroups
            .flatten()
            .map { it.id }
            .toSet()

        val contradictionPairs = findContradictions(sources)
        val obsoleteIds = contradictionPairs
            .map { pair ->
                if (pair.first.createdAt <= pair.second.createdAt) {
                    pair.first.id
                } else {
                    pair.second.id
                }
            }
            .toSet()

        val newIds = sourceIds - previousIds
        val fingerprint = sourceFingerprint(sources)
        val needsConsolidation = sources.isNotEmpty()

        val reason = when {
            sources.isEmpty() ->
                "Aucune nouvelle mémoire source après le curseur de consolidation."
            lastFingerprint == null ->
                "Premier lot du balayage historique Memory Lifecycle v2."
            fingerprint == lastFingerprint ->
                "Lot identique au précédent détecté ; le curseur ne doit avancer qu'après succès."
            else -> buildString {
                append("Nouveau lot historique à consolider")
                if (newIds.isNotEmpty()) {
                    append(" : ${newIds.size} mémoire(s) non vues dans le lot précédent")
                }
                append(".")
            }
        }

        return MemoryLifecycleAnalysis(
            sourceCount = sources.size,
            newCount = newIds.size,
            confirmedCount = confirmedIds.size,
            confirmationGroups = duplicateGroups.size,
            contradictionCount = contradictionPairs.size,
            obsoleteCandidateCount = obsoleteIds.size,
            sourceFingerprint = fingerprint,
            lastConsolidatedFingerprint = lastFingerprint,
            lastConsolidatedAt = lastAt,
            needsConsolidation = needsConsolidation,
            reason = reason,
            sourceIds = sourceIds
        )
    }

    /**
     * Compatibilité pendant la migration du Core : enregistre le résultat mais
     * n'avance pas le nouveau curseur historique faute de positions temporelles.
     */
    fun markConsolidated(
        analysis: MemoryLifecycleAnalysis,
        knowledgeId: String,
        resultSha256: String
    ) {
        saveConsolidationMetadata(analysis, knowledgeId, resultSha256)
    }

    fun markConsolidated(
        analysis: MemoryLifecycleAnalysis,
        knowledgeId: String,
        resultSha256: String,
        processedMemories: List<MemorySnapshot>
    ) {
        require(processedMemories.isNotEmpty()) {
            "Impossible d'avancer le curseur sans mémoire traitée."
        }

        val lastProcessed = processedMemories.maxWith(
            compareBy<MemorySnapshot> { it.createdAt }
                .thenBy { it.id }
        )
        val current = currentCursor()
        require(
            lastProcessed.createdAt > current.createdAt ||
                (
                    lastProcessed.createdAt == current.createdAt &&
                        lastProcessed.id > current.id
                    )
        ) {
            "Le curseur mémoire ne peut pas reculer."
        }

        saveConsolidationMetadata(analysis, knowledgeId, resultSha256)
        prefs.edit()
            .putLong(KEY_CURSOR_CREATED_AT, lastProcessed.createdAt)
            .putString(KEY_CURSOR_ID, lastProcessed.id)
            .apply()
    }

    fun lifecycleSummary(analysis: MemoryLifecycleAnalysis): String =
        buildString {
            append("Memory Lifecycle v2 : ")
            append("${analysis.sourceCount} source(s), ")
            append("${analysis.newCount} NEW, ")
            append("${analysis.confirmedCount} CONFIRMED ")
            append("dans ${analysis.confirmationGroups} groupe(s), ")
            append("${analysis.contradictionCount} CONTRADICTORY, ")
            append("${analysis.obsoleteCandidateCount} OBSOLETE_CANDIDATE. ")
            append("Empreinte : ${analysis.sourceFingerprint.take(16)}.")
        }

    private fun saveConsolidationMetadata(
        analysis: MemoryLifecycleAnalysis,
        knowledgeId: String,
        resultSha256: String
    ) {
        prefs.edit()
            .putString(KEY_LAST_FINGERPRINT, analysis.sourceFingerprint)
            .putStringSet(KEY_LAST_SOURCE_IDS, analysis.sourceIds)
            .putLong(KEY_LAST_CONSOLIDATED_AT, System.currentTimeMillis())
            .putString(KEY_LAST_KNOWLEDGE_ID, knowledgeId)
            .putString(KEY_LAST_RESULT_SHA256, resultSha256)
            .apply()
    }

    private fun sourceFingerprint(memories: List<MemorySnapshot>): String {
        if (memories.isEmpty()) return sha256("empty")

        val canonical = memories
            .map { memory ->
                listOf(
                    memory.id,
                    memory.type,
                    normalize(memory.content),
                    memory.source,
                    "%.6f".format(java.util.Locale.US, memory.confidence),
                    memory.createdAt.toString()
                ).joinToString("|")
            }
            .sorted()
            .joinToString("\n")

        return sha256(canonical)
    }

    private fun findContradictions(
        memories: List<MemorySnapshot>
    ): List<Pair<MemorySnapshot, MemorySnapshot>> {
        val pairs = mutableListOf<Pair<MemorySnapshot, MemorySnapshot>>()

        for (leftIndex in 0 until memories.size) {
            val left = memories[leftIndex]
            val leftTokens = semanticTokens(left.content)
            if (leftTokens.size < 2) continue

            for (rightIndex in leftIndex + 1 until memories.size) {
                val right = memories[rightIndex]
                val rightTokens = semanticTokens(right.content)
                if (rightTokens.size < 2) continue

                val union = leftTokens union rightTokens
                if (union.isEmpty()) continue

                val similarity =
                    (leftTokens intersect rightTokens).size.toDouble() /
                        union.size.toDouble()

                if (
                    similarity >= 0.6 &&
                    hasNegation(left.content) != hasNegation(right.content)
                ) {
                    pairs += left to right
                }
            }
        }

        return pairs
    }

    private fun semanticTokens(text: String): Set<String> =
        tokenize(text)
            .map {
                it.removePrefix("n'")
                    .removePrefix("n’")
            }
            .filterNot {
                it.isBlank() ||
                    it in STOP_WORDS ||
                    it in NEGATION_WORDS
            }
            .toSet()

    private fun hasNegation(text: String): Boolean {
        val lower = " ${text.lowercase()}"
        return " n'" in lower ||
            " n’" in lower ||
            tokenize(text).any { it in NEGATION_WORDS }
    }

    private fun normalize(text: String): String =
        tokenize(text).joinToString(" ")

    private fun tokenize(text: String): List<String> =
        Regex("[\\p{L}\\p{N}'’-]+")
            .findAll(text)
            .map { it.value.lowercase() }
            .toList()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
}
