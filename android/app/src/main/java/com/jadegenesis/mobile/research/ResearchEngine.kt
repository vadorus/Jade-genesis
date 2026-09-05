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
    val query: String,
    val evidence: List<ResearchEvidence>,
    val providerErrors: List<String>,
    val createdAt: Long
) {
    val providerCount: Int
        get() = evidence.map { it.provider }.distinct().size

    val confidence: Double
        get() = when {
            providerCount >= 3 && evidence.size >= 4 -> 0.88
            providerCount >= 2 -> 0.80
            evidence.isNotEmpty() -> 0.62
            else -> 0.25
        }

    fun providerSummary(): String =
        evidence.map { it.provider }.distinct().joinToString(", ").ifBlank { "aucune" }

    fun renderForModel(maxEvidence: Int = 6): String = buildString {
        appendLine("Requête de recherche : $query")
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

    fun renderForUser(maxEvidence: Int = 5): String = buildString {
        appendLine("Recherche : $query")
        if (evidence.isEmpty()) {
            appendLine("Aucune source publique suffisamment exploitable n'a été trouvée.")
        } else {
            appendLine("Résultats trouvés : ${evidence.size} • fournisseurs indépendants : $providerCount • confiance recherche ${(confidence * 100).toInt()} %")
            evidence.take(maxEvidence).forEach { item ->
                appendLine("• ${item.provider} — ${item.title}")
                appendLine("  ${item.url}")
            }
        }
        if (providerErrors.isNotEmpty()) {
            appendLine("Limites : ${providerErrors.joinToString(" | ")}")
        }
    }.trim()
}

class ResearchEngine {

    suspend fun investigate(observation: String): ResearchReport = withContext(Dispatchers.IO) {
        val query = buildSafeQuery(observation)
        val evidence = mutableListOf<ResearchEvidence>()
        val errors = mutableListOf<String>()

        runCatching { wikipedia(query) }
            .onSuccess { evidence += it }
            .onFailure { errors += "Wikipedia: ${shortError(it)}" }

        runCatching { wikidata(query) }
            .onSuccess { evidence += it }
            .onFailure { errors += "Wikidata: ${shortError(it)}" }

        if (looksTechnical(query)) {
            runCatching { githubIssues(query) }
                .onSuccess { evidence += it }
                .onFailure { errors += "GitHub: ${shortError(it)}" }
        }

        ResearchReport(
            query = query,
            evidence = evidence
                .distinctBy { it.url }
                .sortedByDescending { it.confidence }
                .take(8),
            providerErrors = errors,
            createdAt = System.currentTimeMillis()
        )
    }

    fun buildSafeQuery(observation: String): String {
        val redacted = redactSensitive(observation)
        val usefulLines = redacted
            .lineSequence()
            .map { it.trim().trimStart('-', '•', '*', '#', ' ') }
            .filter { it.length >= 4 }
            .filterNot {
                val lower = it.lowercase()
                lower.startsWith("conseil") ||
                    lower.startsWith("incertain") ||
                    lower.startsWith("confiance") ||
                    lower.contains("confiance faible")
            }
            .map {
                it.replace(
                    Regex("""(?i)\b(visible|élevée|moyenne|faible)\b\s*[:\-–—]*"""),
                    " "
                ).trim()
            }
            .filter { it.isNotBlank() }
            .take(4)
            .toList()

        val combined = usefulLines.joinToString(" ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return combined.take(220).ifBlank {
            "analyse visuelle information visible"
        }
    }

    private fun wikipedia(query: String): List<ResearchEvidence> {
        val url =
            "https://fr.wikipedia.org/w/api.php?action=query&list=search&utf8=1&format=json&srlimit=3&srsearch=${enc(query)}"
        val json = JSONObject(get(url))
        val results = json.optJSONObject("query")
            ?.optJSONArray("search") ?: JSONArray()
        return buildList {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                val snippet = cleanHtml(item.optString("snippet"))
                if (title.isBlank()) continue
                add(
                    ResearchEvidence(
                        provider = "Wikipedia",
                        title = title,
                        url = "https://fr.wikipedia.org/wiki/${encPath(title.replace(' ', '_'))}",
                        snippet = snippet.take(700),
                        confidence = 0.78
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

    private fun githubIssues(query: String): List<ResearchEvidence> {
        val technical = query
            .replace(Regex("""[^\p{L}\p{N}._+\- ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .take(180)
        val url =
            "https://api.github.com/search/issues?q=${enc(technical)}&per_page=3&sort=updated&order=desc"
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
            connection.connectTimeout = 6_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty(
                "User-Agent",
                "JadeGenesis/0.1.3 (personal research assistant; public-data research)"
            )
            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                error("HTTP $code ${body.take(120)}")
            }
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun redactSensitive(value: String): String {
        var text = value
        text = text.replace(
            Regex("""(?i)\b[A-Z0-9._%+\-]+@[A-Z0-9.\-]+\.[A-Z]{2,}\b"""),
            "[email]"
        )
        text = text.replace(
            Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b"""),
            "[ip]"
        )
        text = text.replace(
            Regex("""(?i)\b(?:bearer\s+)?[A-Za-z0-9_\-]{28,}\b"""),
            "[secret]"
        )
        text = text.replace(
            Regex("""(?i)\b[A-Z]:\\[^\s]{2,}"""),
            "[path]"
        )
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
        value
            .replace(Regex("""<[^>]+>"""), " ")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    private fun encPath(value: String): String =
        enc(value).replace("+", "%20")

    private fun shortError(t: Throwable): String =
        (t.message ?: t::class.java.simpleName).replace(Regex("""\s+"""), " ").take(160)
}
