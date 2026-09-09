package com.jadegenesis.mobile.night

import android.content.Context
import com.jadegenesis.mobile.config.SafetyPolicy
import com.jadegenesis.mobile.state.SharedGenesisStateStore
import com.jadegenesis.mobile.state.SharedStateEvent
import org.json.JSONArray
import org.json.JSONObject

data class NightResearchQuestion(
    val questionId: String,
    val query: String,
    val reason: String,
    val signalKind: String
)

data class NightResearchEvidence(
    val evidenceId: String,
    val questionId: String,
    val provider: String,
    val title: String,
    val url: String,
    val snippet: String,
    val confidence: Double
)

data class NightHypothesis(
    val hypothesisId: String,
    val signalKind: String,
    val statement: String,
    val confidence: Double,
    val falsifiableMetric: String,
    val successCriterion: String
)

data class NightExperimentProposal(
    val experimentId: String,
    val hypothesisId: String,
    val target: String,
    val changeHint: String,
    val primaryMetric: String,
    val minimumSamplesPerSide: Int,
    val maximumSamplesPerSide: Int,
    val pairedScenariosRequired: Boolean,
    val frozenChampionRequired: Boolean,
    val sandboxRequired: Boolean
)

data class NightImprovementCandidate(
    val candidateId: String,
    val kind: String,
    val status: String,
    val title: String,
    val rationale: String,
    val experimentId: String,
    val target: String,
    val sandboxRequired: Boolean
)

data class NightLearningSnapshot(
    val runId: String,
    val sourceRevision: Long,
    val generatedAt: Long,
    val reviewedAt: Long,
    val runtimeObservationCount: Int,
    val runtimeConfidence: Double,
    val researchQuestions: List<NightResearchQuestion>,
    val researchEvidence: List<NightResearchEvidence>,
    val hypotheses: List<NightHypothesis>,
    val experiments: List<NightExperimentProposal>,
    val improvementCandidates: List<NightImprovementCandidate>,
    val externalResearchEvidenceCount: Int
)

class NightLearningInbox(context: Context) {
    private val store = SharedGenesisStateStore(context.applicationContext)

    fun latest(): NightLearningSnapshot? = parseLatest(store.cachedEvents())

    companion object {
        private const val EVENT_KIND = "vps_learning_snapshot"
        private const val SCHEMA_VERSION = 1

        fun parseLatest(events: List<SharedStateEvent>): NightLearningSnapshot? {
            val ordered = events
                .asSequence()
                .filter { it.kind == EVENT_KIND }
                .sortedWith(
                    compareByDescending<SharedStateEvent> { it.serverRevision }
                        .thenByDescending { it.createdAt }
                        .thenByDescending { it.eventId }
                )
                .toList()

            for (event in ordered) {
                val parsed = runCatching { parse(event.payload) }.getOrNull()
                if (parsed != null) return parsed
            }
            return null
        }

        private fun parse(raw: String): NightLearningSnapshot {
            require(raw.length <= SafetyPolicy.MAX_SHARED_STATE_EVENT_PAYLOAD_CHARS) {
                "Snapshot Night Learning trop volumineux."
            }
            val json = JSONObject(raw)
            require(json.optInt("schema_version", -1) == SCHEMA_VERSION) {
                "Schéma Night Learning incompatible."
            }

            // Fail closed: a learning snapshot is review-only. Any request to
            // execute, promote, rewrite production code or run shell commands
            // makes the whole snapshot untrusted and therefore ignored.
            require(!json.optBoolean("automatic_experiment_execution", false)) {
                "Night Learning demande une exécution automatique interdite."
            }
            require(!json.optBoolean("automatic_promotion", false)) {
                "Night Learning demande une promotion automatique interdite."
            }
            require(!json.optBoolean("production_code_rewrite", false)) {
                "Night Learning demande une réécriture de production interdite."
            }
            require(!json.optBoolean("shell_execution", false)) {
                "Night Learning demande une commande shell interdite."
            }

            val questions = parseQuestions(json.optJSONArray("research_questions"))
            val evidence = parseEvidence(json.optJSONArray("research_evidence"))
            val hypotheses = parseHypotheses(json.optJSONArray("hypotheses"))
            val experiments = parseExperiments(json.optJSONArray("experiments"))
            val candidates = parseCandidates(json.optJSONArray("improvement_candidates"))

            return NightLearningSnapshot(
                runId = clean(json.optString("run_id"), 160),
                sourceRevision = json.optLong("source_revision", 0L).coerceAtLeast(0L),
                generatedAt = json.optLong("generated_at", 0L).coerceAtLeast(0L),
                reviewedAt = json.optLong("reviewed_at", 0L).coerceAtLeast(0L),
                runtimeObservationCount = json.optInt("runtime_observation_count", 0)
                    .coerceAtLeast(0),
                runtimeConfidence = finiteDouble(json, "runtime_confidence")
                    .coerceIn(0.0, 1.0),
                researchQuestions = questions,
                researchEvidence = evidence,
                hypotheses = hypotheses,
                experiments = experiments,
                improvementCandidates = candidates,
                externalResearchEvidenceCount = json.optInt(
                    "external_research_evidence_count",
                    evidence.size
                ).coerceIn(0, SafetyPolicy.MAX_NIGHT_RESEARCH_EVIDENCE)
            )
        }

        private fun parseQuestions(array: JSONArray?): List<NightResearchQuestion> =
            parseObjects(array, SafetyPolicy.MAX_NIGHT_RESEARCH_QUESTIONS) { item ->
                NightResearchQuestion(
                    questionId = required(item, "question_id", 160),
                    query = required(item, "query"),
                    reason = clean(item.optString("reason")),
                    signalKind = clean(item.optString("signal_kind"), 120)
                )
            }

        private fun parseEvidence(array: JSONArray?): List<NightResearchEvidence> =
            parseObjects(array, SafetyPolicy.MAX_NIGHT_RESEARCH_EVIDENCE) { item ->
                val url = required(item, "url", 500)
                require(url.startsWith("https://")) {
                    "Preuve Night Learning sans HTTPS."
                }
                NightResearchEvidence(
                    evidenceId = required(item, "evidence_id", 160),
                    questionId = required(item, "question_id", 160),
                    provider = required(item, "provider", 120),
                    title = required(item, "title", 220),
                    url = url,
                    snippet = required(item, "snippet"),
                    confidence = finiteDouble(item, "confidence").coerceIn(0.0, 1.0)
                )
            }

        private fun parseHypotheses(array: JSONArray?): List<NightHypothesis> =
            parseObjects(array, SafetyPolicy.MAX_NIGHT_HYPOTHESES) { item ->
                NightHypothesis(
                    hypothesisId = required(item, "hypothesis_id", 160),
                    signalKind = clean(item.optString("signal_kind"), 120),
                    statement = required(item, "statement"),
                    confidence = finiteDouble(item, "confidence").coerceIn(0.0, 1.0),
                    falsifiableMetric = required(item, "falsifiable_metric", 160),
                    successCriterion = required(item, "success_criterion")
                )
            }

        private fun parseExperiments(array: JSONArray?): List<NightExperimentProposal> =
            parseObjects(array, SafetyPolicy.MAX_NIGHT_EXPERIMENTS) { item ->
                require(!item.optBoolean("automatic_execution", false)) {
                    "Une expérience Night Learning demande une exécution automatique."
                }
                require(item.optBoolean("requires_explicit_promotion_approval", true)) {
                    "Une expérience Night Learning retire l'approbation explicite."
                }
                val minimum = item.optInt("minimum_samples_per_side", 0).coerceAtLeast(0)
                val maximum = item.optInt("maximum_samples_per_side", minimum)
                    .coerceAtLeast(minimum)
                NightExperimentProposal(
                    experimentId = required(item, "experiment_id", 160),
                    hypothesisId = required(item, "hypothesis_id", 160),
                    target = required(item, "target", 180),
                    changeHint = required(item, "change_hint"),
                    primaryMetric = required(item, "primary_metric", 180),
                    minimumSamplesPerSide = minimum.coerceAtMost(100),
                    maximumSamplesPerSide = maximum.coerceAtMost(100),
                    pairedScenariosRequired = item.optBoolean(
                        "paired_scenarios_required",
                        true
                    ),
                    frozenChampionRequired = item.optBoolean(
                        "frozen_champion_required",
                        true
                    ),
                    sandboxRequired = item.optBoolean("sandbox_required", true)
                )
            }

        private fun parseCandidates(array: JSONArray?): List<NightImprovementCandidate> =
            parseObjects(array, SafetyPolicy.MAX_NIGHT_IMPROVEMENT_CANDIDATES) { item ->
                require(item.optString("status") == "CANDIDATE") {
                    "Un candidat Night Learning n'est pas en statut CANDIDATE."
                }
                require(!item.optBoolean("automatic_activation", false)) {
                    "Un candidat Night Learning demande une activation automatique."
                }
                require(!item.optBoolean("automatic_promotion", false)) {
                    "Un candidat Night Learning demande une promotion automatique."
                }
                require(!item.optBoolean("production_code_change", false)) {
                    "Un candidat Night Learning demande une modification de production."
                }
                NightImprovementCandidate(
                    candidateId = required(item, "candidate_id", 160),
                    kind = required(item, "kind", 100),
                    status = "CANDIDATE",
                    title = required(item, "title", 220),
                    rationale = required(item, "rationale"),
                    experimentId = required(item, "experiment_id", 160),
                    target = required(item, "target", 180),
                    sandboxRequired = item.optBoolean("sandbox_required", true)
                )
            }

        private fun <T> parseObjects(
            array: JSONArray?,
            maximum: Int,
            parser: (JSONObject) -> T
        ): List<T> {
            if (array == null) return emptyList()
            require(array.length() <= maximum) {
                "Night Learning dépasse une limite SafetyPolicy."
            }
            return buildList {
                for (index in 0 until array.length()) {
                    add(parser(array.getJSONObject(index)))
                }
            }
        }

        private fun required(
            json: JSONObject,
            key: String,
            maxChars: Int = SafetyPolicy.MAX_NIGHT_LEARNING_TEXT_CHARS
        ): String {
            val value = clean(json.optString(key), maxChars)
            require(value.isNotBlank()) { "Champ Night Learning manquant: $key" }
            return value
        }

        private fun clean(
            value: String,
            maxChars: Int = SafetyPolicy.MAX_NIGHT_LEARNING_TEXT_CHARS
        ): String = value
            .replace('\u0000', ' ')
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .trim()
            .take(maxChars)

        private fun finiteDouble(json: JSONObject, key: String): Double {
            val value = json.optDouble(key, 0.0)
            return if (value.isFinite()) value else 0.0
        }
    }
}
