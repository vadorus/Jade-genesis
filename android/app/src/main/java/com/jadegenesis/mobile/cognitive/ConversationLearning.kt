package com.jadegenesis.mobile.cognitive

import java.text.Normalizer

enum class ConversationFeedbackKind {
    POSITIVE,
    NEGATIVE,
    CORRECTION
}

data class ConversationFeedbackSignal(
    val kind: ConversationFeedbackKind,
    val confidence: Double
)

object ConversationLearningPolicy {
    private val negativePrefixes = listOf(
        "ca marche pas",
        "ca ne marche pas",
        "ca fonctionne pas",
        "ca ne fonctionne pas",
        "c'est faux",
        "c est faux",
        "tu te trompes",
        "tu t'es trompe",
        "tu t es trompe",
        "c'est une erreur",
        "c est une erreur",
        "toujours pas",
        "mauvaise reponse",
        "mauvaise réponse"
    )

    private val correctionPrefixes = listOf(
        "non, c'est",
        "non c'est",
        "non, c est",
        "non c est",
        "correction :",
        "correction:",
        "corrige :",
        "corrige:",
        "la bonne reponse",
        "la bonne réponse"
    )

    private val positiveStatementPrefixes = listOf(
        "ca marche",
        "ca fonctionne",
        "ca a marche",
        "c'est resolu",
        "c est resolu",
        "probleme resolu",
        "merci ca marche",
        "merci ca fonctionne"
    )

    private val positiveStandaloneStatements = listOf(
        "c'est bon",
        "c est bon",
        "c'est ca",
        "c est ca",
        "parfait",
        "resolu",
        "nickel",
        "super"
    )

    private val stopWords = setOf(
        "alors", "avec", "avoir", "cela", "cette", "comme", "comment", "dans", "donc",
        "elle", "elles", "encore", "entre", "etre", "faire", "fait", "faut", "juste",
        "mais", "meme", "mieux", "notre", "nous", "peux", "peut", "plus", "pour",
        "pourquoi", "quand", "quelle", "quelles", "quel", "quels", "quoi", "sans", "sera",
        "sont", "tout", "toute", "toutes", "tous", "truc", "veux", "votre", "vous",
        "aussi", "bien", "besoin", "question", "reponse", "jade", "genesis",
        "aller", "allez", "vais", "devrait", "pourrait", "possible", "actuellement", "maintenant",
        "erreur", "probleme", "fichier", "version", "marche", "faux", "trompes", "toujours"
    )

    fun classifyFeedback(input: String): ConversationFeedbackSignal? {
        val normalized = normalize(input)
        if (normalized.isBlank() || isInterrogative(input, normalized)) return null

        val statement = stripTerminalPunctuation(normalized)
        val negativeCandidate = stripNegativeLeadIn(statement)

        if (matchesAnchored(negativeCandidate, negativePrefixes)) {
            return ConversationFeedbackSignal(
                kind = ConversationFeedbackKind.NEGATIVE,
                confidence = 0.94
            )
        }
        if (matchesAnchored(statement, correctionPrefixes)) {
            return ConversationFeedbackSignal(
                kind = ConversationFeedbackKind.CORRECTION,
                confidence = 0.90
            )
        }

        val positiveCandidate = stripPositiveLeadIn(statement)
        if (startsLikePositive(positiveCandidate)) {
            val tail = adversativeTail(positiveCandidate)
            if (tail != null) {
                val negativeTail = stripNegativeLeadIn(tail)
                if (matchesAnchored(negativeTail, negativePrefixes)) {
                    return ConversationFeedbackSignal(
                        kind = ConversationFeedbackKind.NEGATIVE,
                        confidence = 0.94
                    )
                }
                return null
            }

            if (containsMarkerAnywhere(positiveCandidate, negativePrefixes)) {
                return ConversationFeedbackSignal(
                    kind = ConversationFeedbackKind.NEGATIVE,
                    confidence = 0.94
                )
            }
        }

        if (isExplicitPositive(statement)) {
            return ConversationFeedbackSignal(
                kind = ConversationFeedbackKind.POSITIVE,
                confidence = 0.88
            )
        }
        return null
    }

    fun extractTopics(input: String, limit: Int = 6): List<String> {
        val safeLimit = limit.coerceIn(1, 12)
        val normalized = normalize(input)
        val counts = linkedMapOf<String, Int>()

        normalized
            .split(Regex("[^a-z0-9+#._-]+"))
            .asSequence()
            .map { it.trim('.', '_', '-') }
            .filter { token ->
                token.length >= 4 &&
                    token !in stopWords &&
                    token.any { it.isLetter() } &&
                    !token.all { it.isDigit() }
            }
            .forEach { token -> counts[token] = (counts[token] ?: 0) + 1 }

        return counts.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { it.key.length }
                    .thenBy { it.key }
            )
            .take(safeLimit)
            .map { it.key }
    }

    fun milestoneFor(count: Int): Int = when {
        count >= 24 -> 24
        count >= 12 -> 12
        count >= 6 -> 6
        count >= 3 -> 3
        else -> 0
    }

    fun crossedMilestone(previousCount: Int, currentCount: Int): Int? {
        val previous = milestoneFor(previousCount.coerceAtLeast(0))
        val current = milestoneFor(currentCount.coerceAtLeast(0))
        return current.takeIf { it > previous && it > 0 }
    }

    fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('’', '\'')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isInterrogative(original: String, normalized: String): Boolean {
        if ('?' in original) return true
        val questionStarts = listOf(
            "est-ce que ",
            "est ce que ",
            "pourquoi ",
            "comment ",
            "qu'est-ce que ",
            "qu est ce que ",
            "est-ce qu'",
            "est ce qu'"
        )
        return questionStarts.any { normalized.startsWith(it) }
    }

    private fun stripTerminalPunctuation(value: String): String =
        value.replace(Regex("[\\s.!…]+$"), "").trim()

    private fun stripNegativeLeadIn(value: String): String = when {
        value.startsWith("non, ") -> value.removePrefix("non, ")
        value.startsWith("non ") -> value.removePrefix("non ")
        else -> value
    }

    private fun stripPositiveLeadIn(value: String): String = when {
        value.startsWith("oui, ") -> value.removePrefix("oui, ")
        value.startsWith("oui ") -> value.removePrefix("oui ")
        else -> value
    }

    private fun matchesAnchored(value: String, prefixes: List<String>): Boolean =
        prefixes.map(::normalize).any { marker ->
            value == marker ||
                value.startsWith("$marker ") ||
                value.startsWith("$marker,") ||
                value.startsWith("$marker:") ||
                value.startsWith("$marker;")
        }

    private fun containsMarkerAnywhere(value: String, markers: List<String>): Boolean =
        markers.map(::normalize).any { marker -> marker in value }

    private fun adversativeTail(value: String): String? {
        val match = Regex("\\b(?:mais|sauf que|par contre|cependant)\\b\\s+(.+)$")
            .find(value)
            ?: return null
        return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun startsLikePositive(value: String): Boolean {
        val standalone = positiveStandaloneStatements.map(::normalize)
        val prefixes = positiveStatementPrefixes.map(::normalize)
        return standalone.any { marker ->
            value == marker ||
                value.startsWith("$marker ") ||
                value.startsWith("$marker,") ||
                value.startsWith("$marker:") ||
                value.startsWith("$marker;")
        } || prefixes.any { marker ->
            value == marker ||
                value.startsWith("$marker ") ||
                value.startsWith("$marker,") ||
                value.startsWith("$marker:") ||
                value.startsWith("$marker;")
        }
    }

    private fun isExplicitPositive(statement: String): Boolean {
        val candidate = stripPositiveLeadIn(statement)
        val standalone = positiveStandaloneStatements.map(::normalize)
        if (standalone.any { marker -> candidate == marker }) return true
        if (matchesAnchored(candidate, positiveStatementPrefixes)) return true

        return standalone.any { marker ->
            val remainder = when {
                candidate.startsWith("$marker, ") -> candidate.removePrefix("$marker, ")
                candidate.startsWith("$marker: ") -> candidate.removePrefix("$marker: ")
                candidate.startsWith("$marker; ") -> candidate.removePrefix("$marker; ")
                else -> null
            }
            remainder != null && matchesAnchored(remainder, positiveStatementPrefixes)
        }
    }
}
