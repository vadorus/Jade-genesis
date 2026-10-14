package com.jadegenesis.mobile.memory

import java.text.Normalizer
import java.util.Locale

/**
 * Lightweight, bounded lexical retrieval. No network call, model inference,
 * schema migration or new persistent user data.
 */
internal object MemoryContextSearch {
    private const val MAX_TERMS = 5
    private val tokenPattern = Regex("[a-z0-9][a-z0-9+#._-]*")
    private val diacritics = Regex("\\p{Mn}+")
    private val stopWords = setOf(
        "avec", "avoir", "cette", "dans", "donc", "elle", "entre",
        "etre", "faire", "fait", "faut", "mais", "meme", "montrer",
        "montre", "peux", "peut", "plus", "pour", "quand", "quelle",
        "quelles", "quoi", "rappelle", "retrouve", "retrouver",
        "savoir", "souvenir", "souvenirs", "tous", "toutes", "veux",
        "votre", "vous", "notre", "nous", "cela", "comment", "pourquoi",
        "jade", "genesis", "sujet", "question", "reponse", "ancien",
        "ancienne", "anciennes", "anciens", "parler", "parlais",
        "parle", "probleme", "problemes", "encore", "aujourdhui"
    )

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(diacritics, "")
            .replace('’', '\'')

    private fun tokenize(text: String): Set<String> =
        tokenPattern.findAll(normalize(text))
            .map { it.value.trim('.', '-', '_') }
            .filter { it.isNotBlank() }
            .toSet()

    fun terms(query: String): List<String> =
        tokenPattern.findAll(normalize(query.take(1_000)))
            .map { it.value.trim('.', '-', '_') }
            .filter { token ->
                (token.length >= 4 || '+' in token || '#' in token) &&
                    token !in stopWords && token.any(Char::isLetter)
            }
            .distinct()
            .take(MAX_TERMS)
            .toList()

    fun matchingTerms(content: String, terms: List<String>): Int {
        if (terms.isEmpty()) return 0
        val tokens = tokenize(content)
        return terms.count { it in tokens }
    }
}
