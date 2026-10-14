package com.jadegenesis.mobile.night

import android.content.Context
import org.json.JSONObject

/**
 * Read-only night review inbox for provisional PC research packets.
 *
 * A successfully checked FBX artifact does NOT become an approved memory:
 * documents remain uninspected, experiment reports remain unattested, and
 * this module never invokes NightEvidenceLedger.append().
 */
internal data class NightResearchProposal(
    val goalSha256: String,
    val sourceCount: Int,
    val potentialCaveats: Int,
    val blenderLocalChecks: Int,
    val reviewStatus: String = "REQUIRES_HUMAN_EVIDENCE_INSPECTION",
    val nextExperiment: String = "ROBLOX_STUDIO_IMPORT_ISOLATED_COPY",
    val scope: String = "LEGACY_TEST_DOSSIER", val hypothesisCount: Int = 0,
    val experiments: List<NightProposedExperiment> = listOf(
        NightProposedExperiment("ROBLOX_STUDIO_IMPORT_ISOLATED_COPY", "NOT_EXECUTED"),
        NightProposedExperiment("MATERIAL_TEXTURE_FIDELITY_ISOLATED_COPY", "BLOCKED_BY_DEPENDENCY"),
        NightProposedExperiment("INDEPENDENT_SOURCE_REVIEW", "NOT_EXECUTED")
    )
)

internal data class NightProposedExperiment(val id: String, val status: String,
    val title: String = id, val target: String = "", val metric: String = "",
    val minimumSamples: Int = 0, val maximumSamples: Int = 0, val successCriterion: String = "")

internal object NightResearchPacketParser {
    private const val MAX_PACKET_CHARS = 12_000
    private val HASH = Regex("[0-9a-f]{64}")
    private val APPROVED = mapOf(
        "roblox_export" to
            "https://create.roblox.com/docs/art/modeling/export-requirements",
        "roblox_blender" to "https://create.roblox.com/docs/art/blender",
        "blender_fbx" to
            "https://docs.blender.org/manual/en/latest/files/import_export/fbx.html"
    )

    fun parse(json: String): NightResearchProposal {
        require(json.length in 10..MAX_PACKET_CHARS) { "Invalid packet size" }
        val root = JSONObject(json)
        if (root.optString("schema") == "jade.night_research_proposal.v2") return parseJade(root)
        require(root.optString("schema") == "jade.goal_evidence_review.v1")
        require(root.optString("scope") == "SYNTHETIC_BLENDER_FBX_WORKFLOW")
        require(root.optString("night_review_status") ==
            "REQUIRES_HUMAN_EVIDENCE_INSPECTION")
        require(root.opt("automatic_promotion") == false &&
            root.opt("automatic_tool_execution") == false &&
            root.opt("evidence_ledger_import_allowed") == false)
        val goal = root.optString("goal_sha256")
        require(HASH.matches(goal)) { "Invalid goal hash" }
        val docs = root.optJSONArray("source_references")
            ?: error("Missing document references")
        require(docs.length() in 0..3)
        val unique = mutableSetOf<String>()
        var caveats = 0
        for (i in 0 until docs.length()) {
            val doc = docs.getJSONObject(i)
            val key = doc.optString("source_key")
            require(key in APPROVED && unique.add(key))
            require(doc.optString("url") == APPROVED[key])
            require(HASH.matches(doc.optString("sha256")))
            require(doc.opt("inspected") == false &&
                doc.optString("evidence_stance") == "UNASSESSED")
            val excerpts = doc.optJSONArray("excerpts") ?: error("Missing excerpt hashes")
            require(excerpts.length() <= 5)
            val uniqueExcerptHashes = mutableSetOf<String>()
            val uniqueBlocks = mutableSetOf<Int>()
            for (j in 0 until excerpts.length()) {
                val excerpt = excerpts.getJSONObject(j)
                require(excerpt.opt("untrusted") == true)
                require(HASH.matches(excerpt.optString("excerpt_sha256")))
                require(excerpt.optInt("block_index", -1) in 0..3500)
                require(excerpt.opt("block_index") is Int && uniqueBlocks.add(excerpt.getInt("block_index")))
                require(uniqueExcerptHashes.add(excerpt.getString("excerpt_sha256")))
            }
            val flags = doc.optJSONArray("caveat_excerpt_hashes")
                ?: error("Missing caveat references")
            require(flags.length() <= excerpts.length())
            val excerptHashes = (0 until excerpts.length()).map {
                excerpts.getJSONObject(it).getString("excerpt_sha256")
            }.toSet()
            val uniqueCaveats = mutableSetOf<String>()
            for (j in 0 until flags.length()) {
                require(flags.getString(j) in excerptHashes)
                require(uniqueCaveats.add(flags.getString(j)))
                caveats++
            }
        }
        val experiments = root.optJSONArray("experiment_artifacts")
            ?: error("Missing experiment artifacts")
        require(experiments.length() <= 4)
        val experimentHashes = mutableSetOf<String>()
        for (i in 0 until experiments.length()) {
            val run = experiments.getJSONObject(i)
            require(run.optString("test_kind") ==
                "BLENDER_LOCAL_SYNTHETIC_FBX_ROUNDTRIP")
            require(run.optString("scope") == "BLENDER_ONLY_NOT_ROBLOX")
            require(run.optString("test_status") ==
                "LOCAL_ARTIFACT_CHECKS_PASSED_NOT_INDEPENDENTLY_ATTESTED")
            val hash = run.optString("fbx_sha256")
            require(HASH.matches(hash) && experimentHashes.add(hash))
            require(HASH.matches(run.optString("report_sha256")))
            require(run.opt("local_checks_passed") == true &&
                run.opt("trusted_external_attestation") == false &&
                run.opt("inspected_by_human") == false)
        }
        val reasoning = root.optJSONObject("reasoning") ?: error("Missing reasoning")
        require(reasoning.opt("documentation_interpreted") == false &&
            reasoning.opt("independent_source_consensus_proven") == false &&
            reasoning.opt("contradiction_proven") == false &&
            reasoning.opt("roblox_studio_import_tested") == false &&
            reasoning.opt("roblox_studio_material_fidelity_tested") == false &&
            reasoning.opt("new_knowledge_verified") == false)
        require(reasoning.optInt("blender_local_replays_checked", -1) ==
            experiments.length())
        require(reasoning.optInt("potential_caveat_count", -1) == caveats)
        val steps = root.optJSONArray("next_experiments") ?: error("Missing next steps")
        require(steps.length() == 3)
        val requiredSteps = mapOf(
            "ROBLOX_STUDIO_IMPORT_ISOLATED_COPY" to "NOT_EXECUTED",
            "MATERIAL_TEXTURE_FIDELITY_ISOLATED_COPY" to "BLOCKED_BY_DEPENDENCY",
            "INDEPENDENT_SOURCE_REVIEW" to "NOT_EXECUTED"
        )
        val foundSteps = mutableSetOf<String>()
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            val key = step.optString("id")
            require(key in requiredSteps && foundSteps.add(key))
            require(step.optString("status") == requiredSteps[key])
        }
        require(foundSteps == requiredSteps.keys)
        return NightResearchProposal(
            goalSha256 = goal,
            sourceCount = docs.length(),
            potentialCaveats = caveats,
            blenderLocalChecks = experiments.length()
        )
    }

    private fun parseJade(root: JSONObject): NightResearchProposal {
        require(root.getString("scope") == "JADE_SELF_IMPROVEMENT")
        require(root.opt("automatic_promotion") == false && root.opt("automatic_tool_execution") == false &&
            root.opt("evidence_ledger_import_allowed") == false)
        require(root.getString("night_review_status") == "REQUIRES_HUMAN_EVIDENCE_INSPECTION")
        val goal = root.getString("goal_sha256")
        require(HASH.matches(goal) && HASH.matches(root.getString("report_sha256")))
        val count = root.getInt("source_count"); val hypotheses = root.getInt("hypothesis_count")
        require(count in 1..16 && hypotheses in 0..4)
        val steps = root.getJSONArray("next_experiments"); require(steps.length() <= 4)
        val ids = mutableSetOf<String>()
        val proposed = (0 until steps.length()).map { i ->
            val item = steps.getJSONObject(i); val id = item.getString("id")
            require(id.length in 1..160 && ids.add(id))
            require(item.getString("status") == "PROPOSED_REQUIRES_EXPLICIT_SANDBOX_PLAN")
            val title = item.getString("title"); val target = item.getString("target"); val metric = item.getString("primary_metric")
            require(title.isNotBlank() && title.length <= 500 && target.isNotBlank() && target.length <= 180 && metric.isNotBlank() && metric.length <= 180)
            val minimum = item.getInt("minimum_samples_per_side"); val maximum = item.getInt("maximum_samples_per_side")
            require(minimum in 1..100 && maximum in minimum..100 && item.opt("sandbox_required") is Boolean)
            val criterion = item.getString("success_criterion"); require(criterion.isNotBlank() && criterion.length <= 500)
            NightProposedExperiment(id, item.getString("status"), title, target, metric, minimum, maximum, criterion)
        }
        return NightResearchProposal(goal, count, 0, 0, nextExperiment = proposed.firstOrNull()?.id ?: "",
            scope = "JADE_SELF_IMPROVEMENT", hypothesisCount = hypotheses, experiments = proposed)
    }
}

internal class NightResearchProposalInbox(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "jade_night_research_proposals_v1", Context.MODE_PRIVATE
    )

    fun importPacket(json: String): NightResearchProposal {
        val item = NightResearchPacketParser.parse(json)
        // Store only a compact summary of a non-approved task; no source text,
        // no goal text, no document HTML, and no reviewed "support" evidence.
        val safe = JSONObject().apply {
            put("goal_sha256", item.goalSha256)
            put("source_count", item.sourceCount)
            put("potential_caveats", item.potentialCaveats)
            put("blender_local_checks", item.blenderLocalChecks)
            put("review_status", item.reviewStatus)
            put("next_experiment", item.nextExperiment)
            put("scope", item.scope); put("hypothesis_count", item.hypothesisCount)
            put("proposals", org.json.JSONArray().apply { item.experiments.forEach { experiment ->
                put(JSONObject().put("id", experiment.id).put("status", experiment.status))
            } })
            put("verified", false)
            put("action_authorized", false)
        }
        check(prefs.edit().putString("pending", safe.toString()).commit()) {
            "Research packet not persisted"
        }
        return item
    }

    fun latest(): NightResearchProposal? = runCatching {
        val text = prefs.getString("pending", null) ?: return@runCatching null
        val saved = JSONObject(text)
        if (saved.opt("verified") != false ||
            saved.opt("action_authorized") != false ||
            saved.optString("review_status") != "REQUIRES_HUMAN_EVIDENCE_INSPECTION"
        ) return@runCatching null
        val goalHash = saved.optString("goal_sha256")
        if (!Regex("[0-9a-f]{64}").matches(goalHash)) return@runCatching null
        if (saved.optString("scope") == "JADE_SELF_IMPROVEMENT") {
            val count = saved.getInt("source_count"); val hypotheses = saved.getInt("hypothesis_count")
            require(count in 1..16 && hypotheses in 0..4 && saved.getInt("blender_local_checks") == 0)
            val rows = saved.getJSONArray("proposals"); require(rows.length() <= 4)
            val ids = mutableSetOf<String>()
            val proposals = (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i); val id = row.getString("id")
                require(id.length in 1..160 && ids.add(id) && row.getString("status") == "PROPOSED_REQUIRES_EXPLICIT_SANDBOX_PLAN")
                NightProposedExperiment(id, row.getString("status"))
            }
            return@runCatching NightResearchProposal(goalHash, count, 0, 0,
                nextExperiment = proposals.firstOrNull()?.id ?: "", scope = "JADE_SELF_IMPROVEMENT",
                hypothesisCount = hypotheses, experiments = proposals)
        }
        NightResearchProposal(
            goalSha256 = goalHash,
            sourceCount = saved.optInt("source_count").coerceIn(0, 3),
            potentialCaveats = saved.optInt("potential_caveats").coerceIn(0, 15),
            blenderLocalChecks = saved.optInt("blender_local_checks").coerceIn(0, 4)
        )
    }.getOrNull()

    fun clear() {
        check(prefs.edit().remove("pending").commit()) {
            "Failed to clear provisional research"
        }
    }
}
