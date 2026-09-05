package com.jadegenesis.mobile.tools

import android.content.Context
import com.jadegenesis.mobile.model.ToolCandidateSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

class ToolLab(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "jade_genesis_tool_lab",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_CANDIDATES = "candidates_v1"
        private const val MAX_CANDIDATES = 30
        private const val MAX_SOURCE_CHARS = 30_000
    }

    fun list(limit: Int = 20): List<ToolCandidateSnapshot> =
        load().sortedByDescending { it.updatedAt }.take(limit)

    fun saveFromBrain(raw: String, generator: String): ToolCandidateSnapshot {
        val json = parseObject(raw)
        val name = json.optString("name").trim()
            .lowercase()
            .replace(Regex("[^a-z0-9_]+"), "_")
            .trim('_')
            .take(64)
        require(name.isNotBlank()) { "Le candidat outil n'a pas de nom valide." }

        val description = json.optString("description").trim().take(2_000)
        val language = json.optString("language", "python").trim().lowercase()
        val sourceCode = json.optString("source_code").trim()
        require(sourceCode.isNotBlank()) { "Le candidat outil ne contient aucun code." }
        require(sourceCode.length <= MAX_SOURCE_CHARS) {
            "Le candidat outil dépasse $MAX_SOURCE_CHARS caractères."
        }

        val permissions = stringList(json.optJSONArray("permissions"), 24, 80)
        val tests = stringList(json.optJSONArray("tests"), 20, 500)
        val warnings = staticWarnings(language, sourceCode, permissions)
        val now = System.currentTimeMillis()
        val candidate = ToolCandidateSnapshot(
            id = "tool-${UUID.randomUUID()}",
            name = name,
            description = description,
            language = language,
            permissions = permissions,
            sourceCode = sourceCode,
            tests = tests,
            status = if (warnings.isEmpty()) {
                "CANDIDATE_STATIC_OK"
            } else {
                "CANDIDATE_REVIEW_REQUIRED"
            },
            validationWarnings = warnings,
            sourceSha256 = sha256(sourceCode),
            generator = generator.take(120),
            createdAt = now,
            updatedAt = now
        )

        val next = (listOf(candidate) + load().filterNot { it.id == candidate.id })
            .take(MAX_CANDIDATES)
        save(next)
        return candidate
    }

    private fun staticWarnings(
        language: String,
        sourceCode: String,
        permissions: List<String>
    ): List<String> {
        val warnings = mutableListOf<String>()
        if (language != "python") {
            warnings += "Tool Lab v1 ne sait pas encore valider statiquement le langage '$language'."
        }
        val lowered = sourceCode.lowercase()
        val sensitivePatterns = mapOf(
            "subprocess" to "lancement de processus",
            "os.system" to "commande système",
            "socket" to "accès réseau brut",
            "ctypes" to "accès natif",
            "eval(" to "évaluation dynamique",
            "exec(" to "exécution dynamique",
            "__import__" to "import dynamique",
            "shutil.rmtree" to "suppression récursive"
        )
        sensitivePatterns.forEach { (pattern, label) ->
            if (pattern in lowered) warnings += "Revue requise : $label détecté."
        }
        if ("network" in permissions.map { it.lowercase() } && "socket" !in lowered) {
            warnings += "Permission réseau déclarée : validation d'exécution encore requise."
        }
        return warnings.distinct()
    }

    private fun parseObject(raw: String): JSONObject {
        val clean = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        require(start >= 0 && end > start) {
            "Le backend n'a pas renvoyé un manifeste d'outil JSON."
        }
        return JSONObject(clean.substring(start, end + 1))
    }

    private fun stringList(
        array: JSONArray?,
        maxItems: Int,
        maxChars: Int
    ): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until minOf(array.length(), maxItems)) {
                val value = array.optString(index).trim().take(maxChars)
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun load(): List<ToolCandidateSnapshot> = runCatching {
        val array = JSONArray(prefs.getString(KEY_CANDIDATES, "[]") ?: "[]")
        buildList {
            for (index in 0 until array.length()) {
                val json = array.getJSONObject(index)
                add(
                    ToolCandidateSnapshot(
                        id = json.getString("id"),
                        name = json.getString("name"),
                        description = json.optString("description"),
                        language = json.optString("language", "python"),
                        permissions = stringList(json.optJSONArray("permissions"), 24, 80),
                        sourceCode = json.optString("source_code"),
                        tests = stringList(json.optJSONArray("tests"), 20, 500),
                        status = json.optString("status", "CANDIDATE"),
                        validationWarnings = stringList(
                            json.optJSONArray("validation_warnings"),
                            30,
                            300
                        ),
                        sourceSha256 = json.optString("source_sha256"),
                        generator = json.optString("generator"),
                        createdAt = json.optLong("created_at"),
                        updatedAt = json.optLong("updated_at")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun save(candidates: List<ToolCandidateSnapshot>) {
        val array = JSONArray()
        candidates.take(MAX_CANDIDATES).forEach { candidate ->
            array.put(
                JSONObject().apply {
                    put("id", candidate.id)
                    put("name", candidate.name)
                    put("description", candidate.description)
                    put("language", candidate.language)
                    put("permissions", JSONArray(candidate.permissions))
                    put("source_code", candidate.sourceCode)
                    put("tests", JSONArray(candidate.tests))
                    put("status", candidate.status)
                    put("validation_warnings", JSONArray(candidate.validationWarnings))
                    put("source_sha256", candidate.sourceSha256)
                    put("generator", candidate.generator)
                    put("created_at", candidate.createdAt)
                    put("updated_at", candidate.updatedAt)
                }
            )
        }
        prefs.edit().putString(KEY_CANDIDATES, array.toString()).apply()
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
