package com.jadegenesis.mobile.evolution

import android.content.Context
import com.jadegenesis.mobile.config.SafetyPolicy
import org.json.JSONArray
import org.json.JSONObject

class EvolutionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val lock = Any()

    fun upsert(candidate: EvolutionCandidate) = synchronized(lock) {
        val current = loadUnsafe().toMutableList()
        current.removeAll { it.candidateId == candidate.candidateId }
        current.add(0, candidate)
        saveUnsafe(current.take(SafetyPolicy.MAX_EVOLUTION_CANDIDATES))
    }

    fun get(candidateId: String): EvolutionCandidate? = synchronized(lock) {
        loadUnsafe().firstOrNull { it.candidateId == candidateId }
    }

    fun recent(limit: Int = 20): List<EvolutionCandidate> = synchronized(lock) {
        loadUnsafe().take(limit.coerceIn(0, SafetyPolicy.MAX_EVOLUTION_CANDIDATES))
    }

    fun count(): Int = synchronized(lock) { loadUnsafe().size }

    private fun loadUnsafe(): List<EvolutionCandidate> {
        val primary = prefs.getString(KEY_LEDGER, null)
        if (!primary.isNullOrBlank()) {
            decode(primary)?.let { return it }
        }

        val backup = prefs.getString(KEY_LEDGER_BACKUP, null)
        if (!backup.isNullOrBlank()) {
            decode(backup)?.let { recovered ->
                prefs.edit().putString(KEY_LEDGER, encode(recovered)).apply()
                return recovered
            }
        }
        return emptyList()
    }

    private fun saveUnsafe(candidates: List<EvolutionCandidate>) {
        val encoded = encode(candidates)
        val previous = prefs.getString(KEY_LEDGER, null)
        val editor = prefs.edit()
        if (!previous.isNullOrBlank()) {
            editor.putString(KEY_LEDGER_BACKUP, previous)
        }
        editor.putString(KEY_LEDGER, encoded).apply()
    }

    private fun encode(candidates: List<EvolutionCandidate>): String =
        JSONArray().apply {
            candidates.forEach { candidate -> put(candidateToJson(candidate)) }
        }.toString()

    private fun decode(raw: String): List<EvolutionCandidate>? = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until minOf(
                array.length(),
                SafetyPolicy.MAX_EVOLUTION_CANDIDATES
            )) {
                runCatching { candidateFromJson(array.getJSONObject(index)) }
                    .getOrNull()
                    ?.let(::add)
            }
        }
    }.getOrNull()

    private fun candidateToJson(candidate: EvolutionCandidate): JSONObject =
        JSONObject().apply {
            put("schema_version", SCHEMA_VERSION)
            put("candidate_id", candidate.candidateId)
            put("kind", candidate.kind.name)
            put("title", candidate.title)
            put("rationale", candidate.rationale)
            put("status", candidate.status.name)
            put("champion_config_id", candidate.championConfigId)
            put("challenger_config_id", candidate.challengerConfigId)
            put("champion_config_json", candidate.championConfigJson)
            put("challenger_config_json", candidate.challengerConfigJson)
            put(
                "baseline_runtime_evidence",
                candidate.baselineRuntimeEvidence?.let(::evidenceToJson) ?: JSONObject.NULL
            )
            put(
                "paired_baseline_evidence",
                candidate.pairedBaselineEvidence?.let(::evidenceToJson) ?: JSONObject.NULL
            )
            put(
                "paired_challenger_evidence",
                candidate.pairedChallengerEvidence?.let(::evidenceToJson) ?: JSONObject.NULL
            )
            put(
                "comparison",
                candidate.comparison?.let(::comparisonToJson) ?: JSONObject.NULL
            )
            put(
                "sandbox",
                candidate.sandbox?.let(::sandboxToJson) ?: JSONObject.NULL
            )
            put(
                "transitions",
                JSONArray().apply {
                    candidate.transitions
                        .takeLast(SafetyPolicy.MAX_EVOLUTION_TRANSITIONS_PER_CANDIDATE)
                        .forEach { transition ->
                            put(
                                JSONObject().apply {
                                    put("status", transition.status.name)
                                    put("at", transition.at)
                                    put("note", transition.note)
                                }
                            )
                        }
                }
            )
            put("created_at", candidate.createdAt)
            put("updated_at", candidate.updatedAt)
            put("promoted_at", candidate.promotedAt ?: JSONObject.NULL)
            put("rolled_back_at", candidate.rolledBackAt ?: JSONObject.NULL)
            put("last_error", candidate.lastError ?: "")
        }

    private fun candidateFromJson(json: JSONObject): EvolutionCandidate {
        require(json.optInt("schema_version", SCHEMA_VERSION) == SCHEMA_VERSION)
        val transitionsJson = json.optJSONArray("transitions") ?: JSONArray()
        val transitions = buildList {
            val start = maxOf(
                0,
                transitionsJson.length() - SafetyPolicy.MAX_EVOLUTION_TRANSITIONS_PER_CANDIDATE
            )
            for (index in start until transitionsJson.length()) {
                val item = transitionsJson.getJSONObject(index)
                add(
                    EvolutionTransition(
                        status = parseStatus(item.optString("status")),
                        at = item.optLong("at", 0L).coerceAtLeast(0L),
                        note = item.optString("note").take(500)
                    )
                )
            }
        }
        return EvolutionCandidate(
            candidateId = json.getString("candidate_id"),
            kind = parseKind(json.optString("kind")),
            title = json.optString("title").take(240),
            rationale = json.optString("rationale").take(2_000),
            status = parseStatus(json.optString("status")),
            championConfigId = json.getString("champion_config_id"),
            challengerConfigId = json.getString("challenger_config_id"),
            championConfigJson = json.getString("champion_config_json"),
            challengerConfigJson = json.getString("challenger_config_json"),
            baselineRuntimeEvidence = json.optJSONObject("baseline_runtime_evidence")
                ?.let(::evidenceFromJson),
            pairedBaselineEvidence = json.optJSONObject("paired_baseline_evidence")
                ?.let(::evidenceFromJson),
            pairedChallengerEvidence = json.optJSONObject("paired_challenger_evidence")
                ?.let(::evidenceFromJson),
            comparison = json.optJSONObject("comparison")?.let(::comparisonFromJson),
            sandbox = json.optJSONObject("sandbox")?.let(::sandboxFromJson),
            transitions = transitions,
            createdAt = json.optLong("created_at", 0L).coerceAtLeast(0L),
            updatedAt = json.optLong("updated_at", 0L).coerceAtLeast(0L),
            promotedAt = json.optLongOrNull("promoted_at"),
            rolledBackAt = json.optLongOrNull("rolled_back_at"),
            lastError = json.optString("last_error").takeIf { it.isNotBlank() }
        )
    }

    private fun evidenceToJson(evidence: EvolutionEvidenceSnapshot): JSONObject =
        JSONObject().apply {
            put("source", evidence.source)
            put("scenario_set_id", evidence.scenarioSetId)
            put("observation_count", evidence.observationCount)
            put("success_rate", evidence.overallSuccessRate)
            put("score", evidence.score)
            put("confidence", evidence.confidence)
            put("evidence_sha256", evidence.evidenceSha256)
            put("generated_at", evidence.generatedAt)
        }

    private fun evidenceFromJson(json: JSONObject): EvolutionEvidenceSnapshot =
        EvolutionEvidenceSnapshot(
            source = json.optString("source"),
            scenarioSetId = json.optString("scenario_set_id"),
            observationCount = json.optInt("observation_count", 0).coerceAtLeast(0),
            overallSuccessRate = json.optFiniteDouble("success_rate").coerceIn(0.0, 1.0),
            score = json.optFiniteDouble("score").coerceIn(0.0, 100.0),
            confidence = json.optFiniteDouble("confidence").coerceIn(0.0, 1.0),
            evidenceSha256 = json.optString("evidence_sha256"),
            generatedAt = json.optLong("generated_at", 0L).coerceAtLeast(0L)
        )

    private fun comparisonToJson(comparison: EvolutionComparison): JSONObject =
        JSONObject().apply {
            put("baseline_score", comparison.baselineScore)
            put("challenger_score", comparison.challengerScore)
            put("score_delta", comparison.scoreDelta)
            put("baseline_success_rate", comparison.baselineSuccessRate)
            put("challenger_success_rate", comparison.challengerSuccessRate)
            put("success_rate_delta", comparison.successRateDelta)
            put("enough_evidence", comparison.enoughEvidence)
            put("confidence_satisfied", comparison.confidenceSatisfied)
            put("paired_scenario_compatible", comparison.pairedScenarioCompatible)
            put("success_rate_protected", comparison.successRateProtected)
            put("score_improved", comparison.scoreImproved)
            put("promotion_eligible", comparison.promotionEligible)
            put("reason", comparison.reason)
        }

    private fun comparisonFromJson(json: JSONObject): EvolutionComparison =
        EvolutionComparison(
            baselineScore = json.optFiniteDouble("baseline_score"),
            challengerScore = json.optFiniteDouble("challenger_score"),
            scoreDelta = json.optFiniteDouble("score_delta"),
            baselineSuccessRate = json.optFiniteDouble("baseline_success_rate"),
            challengerSuccessRate = json.optFiniteDouble("challenger_success_rate"),
            successRateDelta = json.optFiniteDouble("success_rate_delta"),
            enoughEvidence = json.optBoolean("enough_evidence", false),
            confidenceSatisfied = json.optBoolean("confidence_satisfied", false),
            pairedScenarioCompatible = json.optBoolean("paired_scenario_compatible", false),
            successRateProtected = json.optBoolean("success_rate_protected", false),
            scoreImproved = json.optBoolean("score_improved", false),
            promotionEligible = json.optBoolean("promotion_eligible", false),
            reason = json.optString("reason").take(500)
        )

    private fun sandboxToJson(sandbox: EvolutionSandboxResult): JSONObject =
        JSONObject().apply {
            put("passed", sandbox.passed)
            put("changed_fields", sandbox.changedFields)
            put("checks", JSONArray(sandbox.checks))
            put("errors", JSONArray(sandbox.errors))
            put("champion_config_sha256", sandbox.championConfigSha256)
            put("challenger_config_sha256", sandbox.challengerConfigSha256)
        }

    private fun sandboxFromJson(json: JSONObject): EvolutionSandboxResult =
        EvolutionSandboxResult(
            passed = json.optBoolean("passed", false),
            changedFields = json.optInt("changed_fields", 0).coerceAtLeast(0),
            checks = json.optJSONArray("checks").toStringList(),
            errors = json.optJSONArray("errors").toStringList(),
            championConfigSha256 = json.optString("champion_config_sha256"),
            challengerConfigSha256 = json.optString("challenger_config_sha256")
        )

    private fun parseKind(value: String): EvolutionCandidateKind =
        runCatching { EvolutionCandidateKind.valueOf(value.uppercase()) }
            .getOrDefault(EvolutionCandidateKind.CONFIG)

    private fun parseStatus(value: String): EvolutionCandidateStatus =
        runCatching { EvolutionCandidateStatus.valueOf(value.uppercase()) }
            .getOrDefault(EvolutionCandidateStatus.CANDIDATE)

    private fun JSONObject.optFiniteDouble(key: String): Double {
        val value = optDouble(key, 0.0)
        return if (value.isFinite()) value else 0.0
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (isNull(key)) null else optLong(key, 0L).takeIf { it >= 0L }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val PREFS_NAME = "jade_evolution_ledger"
        private const val KEY_LEDGER = "candidates_v1"
        private const val KEY_LEDGER_BACKUP = "candidates_v1_backup"
    }
}