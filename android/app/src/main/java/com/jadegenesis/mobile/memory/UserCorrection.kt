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
    val targets: String = "|"
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

}

object CorrectionGate { val mutex = kotlinx.coroutines.sync.Mutex() }
