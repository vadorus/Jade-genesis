package com.jadegenesis.mobile.night

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Base64

/** Reviews Jade's aggregate observations. Proposed changes never become runners. */
internal object JadeLearningReviewParser {
    fun parse(raw: String, root: JSONObject): MobileResearchDossier {
        val allowed = setOf("schema", "goal", "night_report_base64", "night_report_sha256",
            "verified", "action_authorized", "automatic_promotion")
        require(root.keys().asSequence().toSet() == allowed)
        require(root.opt("verified") == false && root.opt("action_authorized") == false && root.opt("automatic_promotion") == false)
        val goal = text(root, "goal", 320)
        val bytes = Base64.getDecoder().decode(text(root, "night_report_base64", 300_000))
        require(bytes.size in 2..150_000 && researchSha(bytes) == root.getString("night_report_sha256")) { "Compte rendu de Jade modifié" }
        val reportText = bytes.toString(Charsets.UTF_8)
        require(reportText.length <= 64_000)
        val report = JSONObject(reportText)
        require(report.opt("schema_version") is Int && report.getInt("schema_version") == 1)
        val flags = listOf("automatic_experiment_execution", "automatic_promotion", "production_code_rewrite",
            "shell_execution", "raw_conversation_text_used", "user_feedback_promoted_to_external_fact")
        require(flags.all { report.opt(it) == false }) { "Une proposition ne peut pas autoriser une action" }
        for (key in listOf("source_revision", "generated_at", "runtime_observation_count", "outcome_feedback_count")) {
            require((report.opt(key) is Int || report.opt(key) is Long) && report.getLong(key) >= 0)
        }
        require(report.getLong("runtime_observation_count") <= Int.MAX_VALUE && report.getLong("outcome_feedback_count") <= Int.MAX_VALUE)
        require(report.getDouble("runtime_confidence").isFinite() && report.getDouble("runtime_confidence") in 0.0..1.0)
        require(report.getDouble("overall_outcome_quality").isFinite() && report.getDouble("overall_outcome_quality") in -1.0..1.0)
        val questions = rows(report, "research_questions", 3)
        val evidence = rows(report, "research_evidence", 6)
        val hypotheses = rows(report, "hypotheses", 4)
        val experiments = rows(report, "experiments", 4)
        val questionIds = ids(questions, "question_id")
        ids(evidence, "evidence_id")
        val hypothesisIds = ids(hypotheses, "hypothesis_id")
        ids(experiments, "experiment_id")
        questions.forEach { text(it, "query", 500) }
        evidence.forEach {
            val uri = URI(text(it, "url", 500))
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null)
            text(it, "title", 220); text(it, "snippet", 500)
            require(it.getString("question_id") in questionIds)
        }
        hypotheses.forEach {
            text(it, "statement", 500); text(it, "falsifiable_metric", 180); text(it, "success_criterion", 500)
            require(it.opt("promoted_as_external_fact") == false)
        }
        experiments.forEach {
            require(it.getString("hypothesis_id") in hypothesisIds)
            require(it.opt("automatic_execution") == false && it.opt("requires_explicit_promotion_approval") == true &&
                it.opt("paired_scenarios_required") == true && it.opt("frozen_champion_required") == true && it.opt("sandbox_required") is Boolean)
            text(it, "target", 180); text(it, "primary_metric", 180); text(it, "change_hint", 500)
            require(it.opt("minimum_samples_per_side") is Int && it.opt("maximum_samples_per_side") is Int)
            require(it.getInt("minimum_samples_per_side") in 1..100 &&
                it.getInt("maximum_samples_per_side") in it.getInt("minimum_samples_per_side")..100)
        }
        // Reuse Jade's existing general NightLearningInbox parser, without sync.
        val snapshot = NightLearningInbox.parseLocalReport(reportText)
        require(snapshot.hypotheses.size == hypotheses.size && snapshot.experiments.size == experiments.size && snapshot.researchEvidence.size == evidence.size)
        val sources = mutableListOf<ResearchSource>()
        fun source(key: String, title: String, text: String, url: String = "") {
            val hash = researchSha(text.toByteArray(Charsets.UTF_8))
            sources += ResearchSource(key, url, hash, listOf(ResearchPassage(0, hash, text, false)), title)
        }
        source("jade_observations", "Observations déclarées de Jade",
            "Observations : ${report.get("runtime_observation_count")}. Confiance : ${report.get("runtime_confidence")}. " +
                "Retours de qualité : ${report.get("outcome_feedback_count")}. Qualité globale : ${report.get("overall_outcome_quality")}.")
        hypotheses.forEachIndexed { index, item -> source("jade_hypothesis_$index", "Hypothèse de Jade ${index + 1}", item.getString("statement")) }
        evidence.forEachIndexed { index, item -> source("jade_external_$index", item.getString("title"), item.getString("snippet"), item.getString("url")) }
        val proposals = JSONArray()
        val byHypothesis = hypotheses.associateBy { it.getString("hypothesis_id") }
        experiments.forEach { item -> proposals.put(JSONObject().put("id", item.getString("experiment_id"))
            .put("status", "PROPOSED_REQUIRES_EXPLICIT_SANDBOX_PLAN").put("title", item.getString("change_hint"))
            .put("target", item.getString("target")).put("primary_metric", item.getString("primary_metric"))
            .put("hypothesis_id", item.getString("hypothesis_id")).put("minimum_samples_per_side", item.getInt("minimum_samples_per_side"))
            .put("success_criterion", byHypothesis.getValue(item.getString("hypothesis_id")).getString("success_criterion"))
            .put("maximum_samples_per_side", item.getInt("maximum_samples_per_side")).put("sandbox_required", item.getBoolean("sandbox_required"))) }
        val packet = JSONObject().put("schema", "jade.night_research_proposal.v2").put("scope", "JADE_SELF_IMPROVEMENT")
            .put("goal_sha256", researchSha(goal.toByteArray(Charsets.UTF_8))).put("report_sha256", root.getString("night_report_sha256"))
            .put("source_count", sources.size).put("hypothesis_count", hypotheses.size).put("next_experiments", proposals)
            .put("night_review_status", "REQUIRES_HUMAN_EVIDENCE_INSPECTION").put("automatic_promotion", false)
            .put("automatic_tool_execution", false).put("evidence_ledger_import_allowed", false)
        val proposal = NightResearchPacketParser.parse(packet.toString())
        return MobileResearchDossier(raw, researchSha(raw.toByteArray(Charsets.UTF_8)), goal, sources, emptyList(),
            packet.toString(), proposal.experiments, "JADE_SELF_IMPROVEMENT")
    }

    private fun text(row: JSONObject, key: String, maximum: Int): String {
        require(row.opt(key) is String)
        return row.getString(key).also { require(it.isNotBlank() && it.length <= maximum) }
    }
    private fun rows(report: JSONObject, key: String, maximum: Int): List<JSONObject> {
        val array = report.getJSONArray(key); require(array.length() <= maximum)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    private fun ids(rows: List<JSONObject>, key: String): Set<String> {
        val ids = rows.map { text(it, key, 160) }; require(ids.toSet().size == ids.size)
        return ids.toSet()
    }
}
