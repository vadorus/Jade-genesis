package com.jadegenesis.mobile.memory

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import java.text.Normalizer
import java.util.Locale

/** Private linkage. Pending proposals are never memory facts. No diagnostic serialization. */
@Entity(tableName = "user_corrections")
data class UserCorrection(
    @PrimaryKey val id: String,
    val question: String,
    val answerRef: String,
    val wrongAnswer: String,
    val fact: String,
    val createdAt: Long,
    val state: String,
    val targets: String = "|",
    val lastExamAt: Long = 0L
)

@Entity(tableName = "correction_exam_nights")
data class CorrectionExamNight(
    @PrimaryKey val night: String,
    val attempts: Int = 0,
    val replayed: Int = 0,
    val evaluable: Int = 0,
    val repeated: Int = 0,
    val unavailable: Int = 0,
    val interrupted: Int = 0,
    val usedMs: Long = 0,
    val updatedAt: Long = 0
)

object CorrectionPolicy {
    const val SOURCE = "USER_CORRECTION"
    const val INSTRUCTION = "Faits corrigés par Alexandre : ils priment sur tes connaissances."
    const val MAX_FACT = 1500
    fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

    fun command(text: String): Pair<String, String>? =
        Regex("^(corrige|oublie)\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE)
            .matchEntire(text.trim())?.let { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2].trim() }

    fun score(question: String, fact: String, query: String): Int {
        if (normalize(question) == normalize(query) && question.isNotBlank()) return 1000
        val terms = MemoryContextSearch.terms(query)
        return MemoryContextSearch.matchingTerms("$question $fact", terms)
    }

    fun overlaps(question: String, fact: String, existingQuestion: String, existingFact: String): Boolean =
        (question.isNotBlank() && normalize(question) == normalize(existingQuestion)) ||
            normalize(fact) == normalize(existingFact) ||
            MemoryContextSearch.matchingTerms(existingFact, MemoryContextSearch.terms(fact)) >= 2

    /** Exact lexical indicator only: negation or semantic contradictions are not decided here. */
    fun keys(fact: String): List<String> {
        val numbers = Regex("(?<![\\p{L}\\p{N}])[+-]?\\d+(?:[.,:/-]\\d+)*(?:\\s?(?:%|€|kg|km|cm|ms|h))?")
            .findAll(fact).map { it.value }.toList()
        val names = Regex("(?<![\\p{L}\\p{N}])[\\p{Lu}][\\p{L}'’-]{2,}")
            .findAll(fact).map { it.value }.filterNot { it in setOf("Le", "Les", "La", "Un", "Une", "The", "Il", "Elle", "Mon", "Ma") }.toList()
        return (numbers + names).distinct()
    }

    fun passes(fact: String, answer: String): Boolean? {
        val keys = keys(fact)
        if (keys.isEmpty()) return null
        val normalized = Normalizer.normalize(answer, Normalizer.Form.NFC)
        return keys.all { key ->
            val escaped = Regex.escape(Normalizer.normalize(key, Normalizer.Form.NFC))
            val pattern = if (key.first().isDigit() || key.first() in "+-")
                "(?<![\\p{L}\\p{N}+\\-])(?<!\\d[.,:/-])$escaped(?![\\p{L}\\p{N}]|[.,:/-]\\d)"
            else "(?<![\\p{L}\\p{N}])$escaped(?![\\p{L}\\p{N}])"
            Regex(pattern).containsMatchIn(normalized)
        }
    }
}

object CorrectionExamBudget {
    const val NIGHT_MS = 900000L
    const val CALL_MS = 90000L
    fun slice(stats: CorrectionExamNight, elapsedMs: Long): Long =
        if (stats.attempts >= 10) 0 else minOf(CALL_MS, NIGHT_MS - stats.usedMs,
            NIGHT_MS - elapsedMs.coerceAtLeast(0)).coerceAtLeast(0)
}
