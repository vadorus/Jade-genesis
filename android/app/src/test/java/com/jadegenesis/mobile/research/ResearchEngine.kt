package com.jadegenesis.mobile.research

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

enum class ResearchTargetKind {
    GITHUB_REPOSITORY
}

data class ResearchTarget(
    val kind: ResearchTargetKind,
    val owner: String,
    val repository: String
) {
    val canonical: String
        get() = "$owner/$repository"
}

data class ResearchEvidence(
    val provider: String,
    val title: String,
    val url: String,
    val snippet: String,
    val confidence: Double,
    val primarySource: Boolean = false
)

data class ResearchReport(
    val queries: List<String>,
    val targets: List<ResearchTarget>,
    val evidence: List<ResearchEvidence>,
    val providerErrors: List<String>,
    val createdAt: Long
) {
    val query: String
        get() = queries.joinToString(" | ")

    val providerCount: Int
        get() = evidence.map { it.provider }.distinct().size

    val primarySourceCount: Int
        get() = evidence.count { it.primarySource }

    val confidence: Double
        get() = when {
            primarySourceCount >= 2 -> 0.94
            primarySourceCount >= 1 -> 0.90
            providerCount >= 3 && evidence.size >= 4 -> 0.88
            providerCount >= 2 && evidence.size >= 2 -> 0.80
            evidence.isNotEmpty() -> 0.62
            else -> 0.25
        }

    fun providerSummary(): String =
        evidence.map { it.provider }.distinct().joinToString(", ").ifBlank { "aucune" }

    fun renderForModel(maxEvidence: Int = 8): String = buildString {
        if (targets.isNotEmpty()) {
            appendLine("Cibles structurées détectées :")
            targets.forEachIndexed { index, target ->
                appendLine("T${index + 1}: ${target.kind} — ${target.canonical}")
            }
        }
        appendLine("Requêtes ciblées :")
        queries.forEachIndexed { index, item -> appendLine("Q${index + 1}: $item") }
        appendLine("Sources trouvées : ${evidence.size}")
        evidence.take(maxEvidence).forEachIndexed { index, item ->
            val sourceType = if (item.primarySource) "source primaire" else "source secondaire"
            appendLine("[${index + 1}] ${item.provider} — ${item.title} — $sourceType")
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
                "${evidence.size} résultat(s) • ${providerCount} fournisseur(s) • " +
                    "$primarySourceCount source(s) primaire(s) • " +
                    "confiance recherche ${(confidence * 100).toInt()} %"
            )
            evidence.take(maxEvidence).forEachIndexed { index, item ->
                val primary = if (item.primarySource) " • primaire" else ""
                appendLine("[${index + 1}] ${item.provider}$primary — ${item.title}")
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
        val targets = extractGithubRepositories("$focusInstruction\n$observation")
        val queries = buildResearchQueries(observation, focusInstruction)
        val evidence = mutableListOf<ResearchEvidence>()
        val errors = mutableListOf<String>()

        targets.take(2).forEach { target ->
            val directResult = runCatching { githubRepositoryEvidence(target) }
            directResult.onSuccess { evidence += it }

            if (directResult.isFailure) {
                val fallback = runCatching {
                    githubRepositorySearch("${target.repository} ${target.owner}")
                }
                fallback.onSuccess { evidence += it }
                if (fallback.isFailure) {
                    errors += "GitHub ${target.canonical}: ${shortError(directResult.exceptionOrNull())}; " +
                        "recherche: ${shortError(fallback.exceptionOrNull())}"
                }
            }
        }

        val githubContext = targets.isNotEmpty() || looksLikeGithubContext("$focusInstruction\n$observation")
        if (targets.isEmpty() && githubContext) {
            val query = queries.firstOrNull().orEmpty()
            if (query.isNotBlank()) {
                runCatching { githubRepositorySearch(query) }
                    .onSuccess { evidence += it }
                    .onFailure { errors += "GitHub dépôts: ${shortError(it)}" }
            }
        }

        val generalQueryCount = if (targets.isNotEmpty()) 1 else 2
        queries.take(generalQueryCount).forEachIndexed { queryIndex, query ->
            if (!githubContext) {
                runCatching { wikipedia(query) }
                    .onSuccess { evidence += it }
                    .onFailure { errors += "Wikipedia Q${queryIndex + 1}: ${shortError(it)}" }

                runCatching { wikidata(query) }
                    .onSuccess { evidence += it }
                    .onFailure { errors += "Wikidata Q${queryIndex + 1}: ${shortError(it)}" }
            }

            runCatching { duckDuckGo(query) }
                .onSuccess { evidence += it }
                .onFailure { errors += "DuckDuckGo Q${queryIndex + 1}: ${shortError(it)}" }
        }

        val technicalQuery = queries.firstOrNull { looksTechnical(it) }
        if (targets.isEmpty() && technicalQuery != null && looksIssueOrErrorContext(observation)) {
            runCatching { githubIssues(technicalQuery) }
                .onSuccess { evidence += it }
                .onFailure { errors += "GitHub Issues: ${shortError(it)}" }
        }

        ResearchReport(
            queries = queries,
            targets = targets,
            evidence = evidence
                .filter { it.title.isNotBlank() && it.url.startsWith("https://") }
                .distinctBy { canonicalizeUrl(it.url) }
                .sortedWith(
                    compareByDescending<ResearchEvidence> { it.primarySource }
                        .thenByDescending { it.confidence }
                )
                .take(12),
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
        val targets = extractGithubRepositories("$safeFocus\n$safeObservation")
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

        extractGithubCanonicalUrls("$safeFocus\n$safeObservation").forEach { url ->
            queries += url
        }

        targets.forEach { target ->
            if (queries.none { it.contains(target.canonical, ignoreCase = true) }) {
                queries += "${target.canonical} GitHub"
            }
        }

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
            .distinctBy { it.lowercase() }
            .take(3)
            .ifEmpty { listOf("information visible à identifier") }
    }

    private fun extractGithubCanonicalUrls(text: String): List<String> {
        val safeText = redactSensitive(text)
        val urlRegex = Regex(
            """(?i)https?://(?:www\.)?github\.com\s*/\s*([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))\s*/\s*([A-Za-z0-9_.-]{1,100})"""
        )
        return urlRegex.findAll(safeText)
            .map { match ->
                val owner = match.groupValues[1]
                val repository = match.groupValues[2]
                    .trim('.', ',', ';', ':', ')', ']', '}')
                    .removeSuffix(".git")
                "https://github.com/$owner/$repository"
            }
            .distinctBy { it.lowercase() }
            .take(2)
            .toList()
    }

    fun extractGithubRepositories(text: String): List<ResearchTarget> {
        val safeText = redactSensitive(text)
        val output = mutableListOf<ResearchTarget>()

        val urlRegex = Regex(
            """(?i)(?:https?://)?(?:www\.)?github\.com\s*/\s*([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))\s*/\s*([A-Za-z0-9_.-]{1,100})"""
        )
        urlRegex.findAll(safeText).forEach { match ->
            addGithubTarget(output, match.groupValues[1], match.groupValues[2])
        }

        val owner = Regex(
            """(?i)(?:nom\s+d['’]utilisateur|utilisateur|compte|owner)\s*[:\-–—]*\s*\**\s*["'`/ ]*([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))"""
        ).find(safeText)?.groupValues?.getOrNull(1)

        val repository = Regex(
            """(?i)(?:nom\s+du\s+d[eé]p[oô]t|repository|repo)\s*[:\-–—]*\s*\**\s*["'`/ ]*([A-Za-z0-9_.-]{1,100})"""
        ).find(safeText)?.groupValues?.getOrNull(1)

        if (!owner.isNullOrBlank() && !repository.isNullOrBlank()) {
            addGithubTarget(output, owner, repository)
        }

        if (safeText.contains("github", ignoreCase = true)) {
            val pairRegex = Regex(
                """\b([A-Za-z0-9](?:[A-Za-z0-9-]{1,38}))\s*/\s*([A-Za-z0-9_.-]{2,100})\b"""
            )
            pairRegex.findAll(safeText).forEach { match ->
                val candidateOwner = match.groupValues[1]
                val candidateRepo = match.groupValues[2]
                if (
                    candidateOwner.lowercase() !in setOf("http", "https", "github", "com", "www") &&
                    candidateRepo.lowercase() !in setOf("workflows", "issues", "pulls", "actions")
                ) {
                    addGithubTarget(output, candidateOwner, candidateRepo)
                }
            }
        }

        return output.distinctBy { it.canonical.lowercase() }.take(4)
    }

    private fun addGithubTarget(
        output: MutableList<ResearchTarget>,
        ownerRaw: String,
        repositoryRaw: String
    ) {
        val owner = ownerRaw.trim().trim('"', '\'', '`', '/', ' ').take(39)
        val repository = repositoryRaw
            .trim()
            .trim('"', '\'', '`', '/', ' ', '.', ',', ';', ':', ')', ']', '}')
            .removeSuffix(".git")
            .take(100)

        if (!owner.matches(Regex("""[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})"""))) return
        if (!repository.matches(Regex("""[A-Za-z0-9_.-]{1,100}"""))) return
        if (repository in setOf(".", "..")) return

        output += ResearchTarget(
            kind = ResearchTargetKind.GITHUB_REPOSITORY,
            owner = owner,
            repository = repository
        )
    }

    private fun githubRepositoryEvidence(target: ResearchTarget): List<ResearchEvidence> {
        val owner = encPathSegment(target.owner)
        val repo = encPathSegment(target.repository)
        val repositoryUrl = "https://api.github.com/repos/$owner/$repo"
        val repository = JSONObject(get(repositoryUrl, accept = "application/vnd.github+json"))
        val fullName = repository.optString("full_name").trim().ifBlank { target.canonical }
        val htmlUrl = repository.optString("html_url").trim()
        if (!htmlUrl.startsWith("https://github.com/")) error("Réponse GitHub sans URL de dépôt valide")

        val description = repository.optString("description").trim()
        val language = repository.optString("language").trim()
        val defaultBranch = repository.optString("default_branch").trim()
        val updatedAt = repository.optString("updated_at").trim()
        val stars = repository.optInt("stargazers_count", 0)
        val forks = repository.optInt("forks_count", 0)
        val archived = repository.optBoolean("archived", false)

        val output = mutableListOf<ResearchEvidence>()
        output += ResearchEvidence(
            provider = "GitHub",
            title = fullName,
            url = htmlUrl,
            snippet = buildString {
                append("Dépôt GitHub public vérifié")
                if (description.isNotBlank()) append(". Description : $description")
                if (language.isNotBlank()) append(". Langage principal : $language")
                if (defaultBranch.isNotBlank()) append(". Branche par défaut : $defaultBranch")
                append(". Étoiles : $stars. Forks : $forks")
                if (updatedAt.isNotBlank()) append(". Mis à jour : $updatedAt")
                append(". Archivé : ${if (archived) "oui" else "non"}.")
            }.take(1_000),
            confidence = 0.99,
            primarySource = true
        )

        runCatching { githubRootContents(target, htmlUrl) }
            .getOrNull()
            ?.let(output::add)

        runCatching { githubReadme(target) }
            .getOrNull()
            ?.let(output::add)

        return output
    }

    private fun githubRootContents(
        target: ResearchTarget,
        repositoryHtmlUrl: String
    ): ResearchEvidence? {
        val owner = encPathSegment(target.owner)
        val repo = encPathSegment(target.repository)
        val array = JSONArray(
            get(
                "https://api.github.com/repos/$owner/$repo/contents",
                accept = "application/vnd.github+json"
            )
        )
        val names = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                val type = item.optString("type").trim()
                if (name.isNotBlank()) add(if (type == "dir") "$name/" else name)
            }
        }.take(40)

        if (names.isEmpty()) return null
        return ResearchEvidence(
            provider = "GitHub",
            title = "${target.canonical} — contenu racine",
            url = repositoryHtmlUrl,
            snippet = "Éléments visibles à la racine du dépôt : ${names.joinToString(", ")}",
            confidence = 0.97,
            primarySource = true
        )
    }

    private fun githubReadme(target: ResearchTarget): ResearchEvidence? {
        val owner = encPathSegment(target.owner)
        val repo = encPathSegment(target.repository)
        val json = JSONObject(
            get(
                "https://api.github.com/repos/$owner/$repo/readme",
                accept = "application/vnd.github+json"
            )
        )
        val htmlUrl = json.optString("html_url").trim()
        val encoded = json.optString("content").replace("\n", "").trim()
        if (!htmlUrl.startsWith("https://github.com/") || encoded.isBlank()) return null

        val decoded = runCatching {
            String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
        }.getOrNull()?.let(::cleanMarkdown).orEmpty()
        if (decoded.isBlank()) return null

        return ResearchEvidence(
            provider = "GitHub",
            title = "${target.canonical} — README",
            url = htmlUrl,
            snippet = decoded.take(1_000),
            confidence = 0.96,
            primarySource = true
        )
    }

    private fun githubRepositorySearch(query: String): List<ResearchEvidence> {
        val technical = query
            .replace(Regex("""[^\p{L}\p{N}._+\-/ ]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(120)
        if (technical.isBlank()) return emptyList()

        val url =
            "https://api.github.com/search/repositories?q=${enc(technical)}&per_page=4&sort=updated&order=desc"
        val json = JSONObject(get(url, accept = "application/vnd.github+json"))
        val results = json.optJSONArray("items") ?: JSONArray()
        return buildList {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val fullName = item.optString("full_name").trim()
                val htmlUrl = item.optString("html_url").trim()
                val description = item.optString("description").trim()
                val language = item.optString("language").trim()
                if (fullName.isBlank() || !htmlUrl.startsWith("https://github.com/")) continue
                add(
                    ResearchEvidence(
                        provider = "GitHub Search",
                        title = fullName,
                        url = htmlUrl,
                        snippet = buildString {
                            if (description.isNotBlank()) append(description)
                            if (language.isNotBlank()) {
                                if (isNotEmpty()) append(". ")
                                append("Langage principal : $language")
                            }
                        }.take(800),
                        confidence = 0.84,
                        primarySource = false
                    )
                )
            }
        }
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
                        provider = "GitHub Issues",
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
                "JadeGenesis/0.1.4-research-v3-candidate (personal research assistant; public-data research)"
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
        val confidenceOnly = Regex(
            """(?i)^(?:confiance\s+)?(?:élevée|elevee|moyenne|faible|high|medium|low)\s*[:\-–—]*\s*\d{1,3}(?:[.,]\d+)?\s*%\s*$"""
        ).matches(value.trim())
        return confidenceOnly ||
            lower in setOf("visible", "incertain", "conseil", "confiance") ||
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
        if (Regex("""\b(android|windows|linux|gradle|kotlin|java|python|ollama|pixel|nvidia|amd|intel|github|repository|dépôt|http|api|runtime)\b""").containsMatchIn(lower)) score += 5
        if (Regex("""\b[A-Z][A-Z0-9_\-]{3,}\b""").containsMatchIn(value)) score += 3
        score += minOf(value.length / 50, 3)
        return score
    }

    private fun extractIdentityTerms(text: String): String {
        val githubTargets = extractGithubRepositories(text)
            .map { it.canonical }
            .take(2)
        val versions = Regex("""\b\d+(?:\.\d+){1,3}\b""")
            .findAll(text).map { it.value }.distinct().take(2).toList()
        val technical = Regex(
            """(?i)\b(android|windows|linux|gradle|kotlin|java|python|ollama|pixel|nvidia|amd|intel|github|http|api|runtime|jade genesis)\b"""
        ).findAll(text).map { it.value }.distinctBy { it.lowercase() }.take(4).toList()
        return (githubTargets + technical + versions).joinToString(" ")
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

    private fun looksLikeGithubContext(text: String): Boolean {
        val lower = text.lowercase()
        return "github" in lower ||
            "dépôt" in lower ||
            "depot" in lower ||
            "repository" in lower ||
            "fork" in lower ||
            "readme" in lower
    }

    private fun looksIssueOrErrorContext(text: String): Boolean {
        val lower = text.lowercase()
        val markers = listOf(
            "error", "erreur", "exception", "failed", "échec", "bug",
            "stacktrace", "compile", "compilation", "crash", "warning", "avertissement"
        )
        return markers.any(lower::contains)
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

    private fun cleanMarkdown(value: String): String =
        value
            .replace(Regex("""```[\s\S]*?```"""), " ")
            .replace(Regex("""`([^`]*)`"""), "\$1")
            .replace(Regex("""!\[[^]]*]\([^)]*\)"""), " ")
            .replace(Regex("""\[([^]]+)]\([^)]*\)"""), "\$1")
            .replace(Regex("""[#>*_~]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun canonicalizeUrl(value: String): String =
        value.trim().removeSuffix("/").lowercase()

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    private fun encPath(value: String): String = enc(value).replace("+", "%20")

    private fun encPathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
            .replace("+", "%20")
            .replace("%2F", "", ignoreCase = true)

    private fun shortError(t: Throwable?): String =
        (t?.message ?: t?.javaClass?.simpleName ?: "erreur inconnue")
            .replace(Regex("""\s+"""), " ")
            .take(160)
}
