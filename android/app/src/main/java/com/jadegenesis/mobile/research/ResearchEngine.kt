package com.jadegenesis.mobile.research

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class ResearchEvidence(
    val provider: String,
    val title: String,
    val url: String,
    val snippet: String,
    val confidence: Double
)

data class ResearchReport(
    val queries: List<String>,
    val evidence: List<ResearchEvidence>,
    val providerErrors: List<String>,
    val createdAt: Long
) {
    val query: String
        get() = queries.joinToString(" | ")

    val providerCount: Int
        get() = evidence.map { it.provider }.distinct().size

    val confidence: Double
        get() = when {
            providerCount >= 3 && evidence.size >= 4 -> 0.88
            providerCount >= 2 && evidence.size >= 2 -> 0.80
            evidence.isNotEmpty() -> 0.62
            else -> 0.25
        }

    fun providerSummary(): String =
        evidence.map { it.provider }.distinct().joinToString(", ").ifBlank { "aucune" }

    fun renderForModel(maxEvidence: Int = 8): String = buildString {
        appendLine("Requêtes ciblées :")
        queries.forEachIndexed { index, item -> appendLine("Q${index + 1}: $item") }
        appendLine("Sources trouvées : ${evidence.size}")
        evidence.take(maxEvidence).forEachIndexed { index, item ->
            appendLine("[${index + 1}] ${item.provider} — ${item.title}")
            appendLine("URL: ${item.url}")
            appendLine("Extrait: ${item.snippet}")
        }
        if (providerErrors.isNotEmpty()) {
            appendLine("Fournisseurs indisponibles : ${providerErrors.joinToString(" | ")}")
        }
    }.trim()

    fun renderSourcesForUser(maxEvidence: Int = 6): String = buildString {
        if (evidence.isEmpty()) {
            append("Aucune source publique suffisamment exploitable n'a été trouvée.")
        } else {
            appendLine(
                "${evidence.size} résultat(s) • ${providerCount} fournisseur(s) indépendant(s) • " +
                    "confiance recherche ${(confidence * 100).toInt()} %"
            )
            evidence.take(maxEvidence).forEachIndexed { index, item ->
                appendLine("[${index + 1}] ${item.provider} — ${item.title}")
                appendLine(item.url)
            }
        }
        if (providerErrors.isNotEmpty()) {
            appendLine("Limites : ${providerErrors.joinToString(" | ")}")
        }
    }.trim()

    fun renderForUser(maxEvidence: Int = 6): String = buildString {
        appendLine("Requêtes : ${queries.joinToString(" ; ")}")
        append(renderSourcesForUser(maxEvidence))
    }.trim()
}

class ResearchEngine {

    suspend fun investigate(
        observation: String,
        focusInstruction: String = ""
    ): ResearchReport = withContext(Dispatchers.IO) {
        val queries = buildResearchQueries(observation, focusInstruction)
        val evidence = mutableListOf<ResearchEvidence>()
        val errors = mutableListOf<String>()

        queries.take(2).forEachIndexed { queryIndex, query ->
            runCatching { wikipedia(query) }
                .onSuccess { evidence += it }
                .onFailure { errors += "Wikipedia Q${queryIndex + 1}: ${shortError(it)}" }

            runCatching { wikidata(query) }
                .onSuccess { evidence += it }
                .onFailure { errors += "Wikidata Q${queryIndex + 1}: ${shortError(it)}" }

            runCatching { duckDuckGo(query) }
                .onSuccess { evidence += it }
                .onFailure { errors += "DuckDuckGo Q${queryIndex + 1}: ${shortError(it)}" }
        }

        val technicalQuery = queries.firstOrNull { looksTechnical(it) }
        if (technicalQuery != null) {
            runCatching { githubIssues(technicalQuery) }
                .onSuccess { evidence += it }
                .onFailure { errors += "GitHub: ${shortError(it)}" }
        }

        ResearchReport(
            queries = queries,
            evidence = evidence
                .filter { it.title.isNotBlank() && it.url.startsWith("https://") }
                .distinctBy { it.url }
                .sortedByDescending { it.confidence }
                .take(10),
            providerErrors = errors.distinct().take(8),
            createdAt = System.currentTimeMillis()
        )
    }

    fun buildResearchQueries(
        observation: String,
        focusInstruction: String = ""
    ): List<String> {
        val safeFocus = cleanQueryText(redactSensitive(focusInstruction))
        val safeObservation = redactSensitive(observation)
        val lines = safeObservation
            .lineSequence()
            .map { cleanObservationLine(it) }
            .filter { it.length >= 5 }
            .filterNot(::isNoiseLine)
            .distinct()
            .toList()

        val highSignal = lines.sortedWith(
            compareByDescending<String> { signalScore(it) }
                .thenBy { it.length }
        )
        val identityTerms = extractIdentityTerms(lines.joinToString(" "))
        val queries = mutableListOf<String>()

        if (safeFocus.isNotBlank()) {
            val context = highSignal.firstOrNull().orEmpty()
            queries += compactQuery("$safeFocus $context $identityTerms")
        }

        highSignal.take(2).forEach { line ->
            queries += compactQuery("$line $identityTerms")
        }

        if (queries.isEmpty()) {
            queries += compactQuery(lines.take(2).joinToString(" "))
        }

        return queries
            .map { it.trim() }
            .filter { it.length >= 4 }
            .distinct()
            .take(3)
            .ifEmpty { listOf("information visible à identifier") }
    }

    private fun wikipedia(query: String): List<ResearchEvidence> {
        val url =
            "https://fr.wikipedia.org/w/api.php?action=query&generator=search&gsrlimit=3&gsrsearch=${enc(query)}&prop=extracts&exintro=1&explaintext=1&format=json"
        val json = JSONObject(get(url))
        val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: JSONObject()
        return buildList {
            pages.keys().forEach { key ->
                val item = pages.optJSONObject(key) ?: return@forEach
                val title = item.optString("title").trim()
                val extract = item.optString("extract").trim()
                if (title.isBlank()) return@forEach
                add(
                    ResearchEvidence(
                        provider = "Wikipedia",
                        title = title,
                        url = "https://fr.wikipedia.org/wiki/${encPath(title.replace(' ', '_'))}",
                        snippet = extract.replace(Regex("""\s+"""), " ").take(900),
                        confidence = 0.80
                    )
                )
            }
        }
    }

    private fun wikidata(query: String): List<ResearchEvidence> {
        val url =
            "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&language=fr&uselang=fr&limit=3&search=${enc(query)}"
        val json = JSONObject(get(url))
        val results = json.optJSONArray("search") ?: JSONArray()
        return buildList {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                val label = item.optString("label").trim()
                val description = item.optString("description").trim()
                if (id.isBlank() || label.isBlank()) continue
                add(
                    ResearchEvidence(
                        provider = "Wikidata",
                        title = "$label ($id)",
                        url = "https://www.wikidata.org/wiki/$id",
                        snippet = description.take(700),
                        confidence = 0.76
                    )
                )
            }
        }
    }

    private fun duckDuckGo(query: String): List<ResearchEvidence> {
        val url =
            "https://api.duckduckgo.com/?q=${enc(query)}&format=json&no_html=1&no_redirect=1&skip_disambig=1"
        val json = JSONObject(get(url))
        val output = mutableListOf<ResearchEvidence>()

        val abstractText = json.optString("AbstractText").trim()
        val abstractUrl = json.optString("AbstractURL").trim()
        val heading = json.optString("Heading").trim()
        if (abstractText.isNotBlank() && abstractUrl.startsWith("https://")) {
            output += ResearchEvidence(
                provider = "DuckDuckGo",
                title = heading.ifBlank { query },
                url = abstractUrl,
                snippet = abstractText.take(700),
                confidence = 0.72
            )
        }

        flattenRelated(json.optJSONArray("RelatedTopics") ?: JSONArray())
            .take(3)
            .forEach { item ->
                val text = item.optString("Text").trim()
                val firstUrl = item.optString("FirstURL").trim()
                if (text.isNotBlank() && firstUrl.startsWith("https://")) {
                    output += ResearchEvidence(
                        provider = "DuckDuckGo",
                        title = text.substringBefore(" - ").take(180),
                        url = firstUrl,
                        snippet = text.take(700),
                        confidence = 0.66
                    )
                }
            }
        return output
    }

    private fun flattenRelated(array: JSONArray): List<JSONObject> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val nested = item.optJSONArray("Topics")
            if (nested != null) {
                addAll(flattenRelated(nested))
            } else {
                add(item)
            }
        }
    }

    private fun githubIssues(query: String): List<ResearchEvidence> {
        val technical = query
            .replace(Regex("""[^\p{L}\p{N}._+\- ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(150)
        val url =
            "https://api.github.com/search/issues?q=${enc(technical)}&per_page=4&sort=updated&order=desc"
        val json = JSONObject(get(url, accept = "application/vnd.github+json"))
        val results = json.optJSONArray("items") ?: JSONArray()
        return buildList {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                val htmlUrl = item.optString("html_url").trim()
                val body = item.optString("body").trim()
                if (title.isBlank() || htmlUrl.isBlank()) continue
                add(
                    ResearchEvidence(
                        provider = "GitHub",
                        title = title,
                        url = htmlUrl,
                        snippet = body.replace(Regex("""\s+"""), " ").take(700),
                        confidence = 0.70
                    )
                )
            }
        }
    }

    private fun get(url: String, accept: String = "application/json"): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 7_000
            connection.readTimeout = 9_000
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty(
                "User-Agent",
                "JadeGenesis/0.1.4 (personal research assistant; public-data research)"
            )
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code ${body.take(120)}")
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun cleanObservationLine(value: String): String =
        value.trim()
            .trimStart('-', '•', '*', '#', ' ')
            .replace(
                Regex("""(?i)^(visible|incertain|conseil|confiance|analyse approfondie)\s*[:\-–—]*\s*"""),
                ""
            )
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun isNoiseLine(value: String): Boolean {
        val lower = value.lowercase()
        return lower in setOf("visible", "incertain", "conseil", "confiance") ||
            lower.startsWith("confiance élevée") ||
            lower.startsWith("confiance moyenne") ||
            lower.startsWith("confiance faible") ||
            lower.contains("je vois l'image") ||
            lower.contains("analyse de l'image fournie")
    }

    private fun signalScore(value: String): Int {
        val lower = value.lowercase()
        var score = 0
        if (Regex("""\b(error|erreur|exception|failed|échec|warning|avertissement)\b""").containsMatchIn(lower)) score += 8
        if (Regex("""\b\d+(?:\.\d+){1,3}\b""").containsMatchIn(value)) score += 5
        if (Regex("""\b(android|windows|linux|gradle|kotlin|java|python|ollama|pixel|nvidia|amd|intel|http|api|runtime)\b""").containsMatchIn(lower)) score += 4
        if (Regex("""\b[A-Z][A-Z0-9_\-]{3,}\b""").containsMatchIn(value)) score += 3
        score += minOf(value.length / 50, 3)
        return score
    }

    private fun extractIdentityTerms(text: String): String {
        val versions = Regex("""\b\d+(?:\.\d+){1,3}\b""")
            .findAll(text).map { it.value }.distinct().take(2).toList()
        val technical = Regex(
            """(?i)\b(android|windows|linux|gradle|kotlin|java|python|ollama|pixel|nvidia|amd|intel|github|http|api|runtime|jade genesis)\b"""
        ).findAll(text).map { it.value }.distinctBy { it.lowercase() }.take(4).toList()
        return (technical + versions).joinToString(" ")
    }

    private fun cleanQueryText(value: String): String =
        value.replace(Regex("""\s+"""), " ")
            .replace(Regex("""[\r\n\t]"""), " ")
            .trim()
            .take(180)

    private fun compactQuery(value: String): String =
        value.replace(Regex("""\[[^]]{1,30}]"""), " ")
            .replace(Regex("""(?i)\b(confiance|visible|incertain|conseil)\b\s*[:\-–—]*"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(150)

    private fun redactSensitive(value: String): String {
        var text = value
        text = text.replace(
            Regex("""(?i)\b[A-Z0-9._%+\-]+@[A-Z0-9.\-]+\.[A-Z]{2,}\b"""),
            "[email]"
        )
        text = text.replace(Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b"""), "[ip]")
        text = text.replace(
            Regex("""(?i)\b(?:bearer\s+)?[A-Za-z0-9_\-]{28,}\b"""),
            "[secret]"
        )
        text = text.replace(Regex("""(?i)\b[A-Z]:\\[^\s]{2,}"""), "[path]")
        return text
    }

    private fun looksTechnical(query: String): Boolean {
        val lower = query.lowercase()
        val markers = listOf(
            "error", "erreur", "exception", "android", "windows", "linux",
            "github", "gradle", "kotlin", "java", "python", "ollama",
            "pixel", "nvidia", "amd", "intel", "driver", "runtime",
            "version", "http", "api", "logiciel", "application"
        )
        return markers.any(lower::contains)
    }

    private fun cleanHtml(value: String): String =
        value.replace(Regex("""<[^>]+>"""), " ")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    private fun encPath(value: String): String = enc(value).replace("+", "%20")

    private fun shortError(t: Throwable): String =
        (t.message ?: t::class.java.simpleName)
            .replace(Regex("""\s+"""), " ")
            .take(160)
}
