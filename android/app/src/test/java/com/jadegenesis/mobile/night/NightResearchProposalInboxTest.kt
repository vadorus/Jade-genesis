package com.jadegenesis.mobile.night

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightResearchProposalInboxTest {
    private val hash = "a".repeat(64)

    private fun docs(): JSONArray = JSONArray().put(JSONObject().apply {
        put("source_key", "roblox_blender")
        put("url", "https://create.roblox.com/docs/art/blender")
        put("sha256", hash)
        put("inspected", false)
        put("evidence_stance", "UNASSESSED")
        put("excerpts", JSONArray().put(JSONObject().apply {
            put("block_index", 12)
            put("excerpt_sha256", hash)
            put("untrusted", true)
        }))
        put("caveat_excerpt_hashes", JSONArray().put(hash))
    })

    private fun runs(): JSONArray = JSONArray().put(JSONObject().apply {
        put("test_kind", "BLENDER_LOCAL_SYNTHETIC_FBX_ROUNDTRIP")
        put("scope", "BLENDER_ONLY_NOT_ROBLOX")
        put("test_status", "LOCAL_ARTIFACT_CHECKS_PASSED_NOT_INDEPENDENTLY_ATTESTED")
        put("fbx_sha256", hash)
        put("report_sha256", hash)
        put("local_checks_passed", true)
        put("trusted_external_attestation", false)
        put("inspected_by_human", false)
    })

    private fun nextSteps(): JSONArray = JSONArray().apply {
        put(JSONObject().put("id", "ROBLOX_STUDIO_IMPORT_ISOLATED_COPY")
            .put("status", "NOT_EXECUTED"))
        put(JSONObject().put("id", "MATERIAL_TEXTURE_FIDELITY_ISOLATED_COPY")
            .put("status", "BLOCKED_BY_DEPENDENCY"))
        put(JSONObject().put("id", "INDEPENDENT_SOURCE_REVIEW")
            .put("status", "NOT_EXECUTED"))
    }

    private fun packet(): JSONObject = JSONObject().apply {
        put("schema", "jade.goal_evidence_review.v1")
        put("scope", "SYNTHETIC_BLENDER_FBX_WORKFLOW")
        put("goal_sha256", hash)
        put("night_review_status", "REQUIRES_HUMAN_EVIDENCE_INSPECTION")
        put("automatic_promotion", false)
        put("automatic_tool_execution", false)
        put("evidence_ledger_import_allowed", false)
        put("source_references", docs())
        put("experiment_artifacts", runs())
        put("next_experiments", nextSteps())
        put("reasoning", JSONObject().apply {
            put("documentation_interpreted", false)
            put("independent_source_consensus_proven", false)
            put("contradiction_proven", false)
            put("roblox_studio_import_tested", false)
            put("roblox_studio_material_fidelity_tested", false)
            put("new_knowledge_verified", false)
            put("blender_local_replays_checked", 1)
            put("potential_caveat_count", 1)
        })
    }

    private fun rejected(body: JSONObject) {
        assertTrue(runCatching { NightResearchPacketParser.parse(body.toString()) }.isFailure)
    }

    @Test fun acceptsOnlyProvisionalResearchSummary() {
        val p = NightResearchPacketParser.parse(packet().toString())
        assertEquals(hash, p.goalSha256)
        assertEquals(1, p.sourceCount)
        assertEquals(1, p.potentialCaveats)
        assertEquals(1, p.blenderLocalChecks)
        assertEquals("REQUIRES_HUMAN_EVIDENCE_INSPECTION", p.reviewStatus)
        assertEquals("ROBLOX_STUDIO_IMPORT_ISOLATED_COPY", p.nextExperiment)
        assertEquals(3, p.experiments.size)
        assertEquals("BLOCKED_BY_DEPENDENCY", p.experiments.single { it.id == "MATERIAL_TEXTURE_FIDELITY_ISOLATED_COPY" }.status)
        assertTrue(p.experiments.all { it.status in setOf("NOT_EXECUTED", "BLOCKED_BY_DEPENDENCY") })
    }

    @Test fun blocksPromotedOrExecutedResults() {
        rejected(packet().put("automatic_promotion", true))
        rejected(packet().put("automatic_tool_execution", true))
        rejected(packet().put("evidence_ledger_import_allowed", true))
    }

    @Test fun blocksFabricatedRobloxOrHumanVerification() {
        rejected(packet().also { it.getJSONObject("reasoning")
            .put("roblox_studio_import_tested", true) })
        rejected(packet().also { it.getJSONObject("reasoning")
            .put("new_knowledge_verified", true) })
        rejected(packet().also { it.getJSONArray("experiment_artifacts")
            .getJSONObject(0).put("inspected_by_human", true) })
        rejected(packet().also { it.getJSONArray("experiment_artifacts")
            .getJSONObject(0).put("trusted_external_attestation", true) })
    }

    @Test fun rejectsUnapprovedSourceAndDigest() {
        rejected(packet().also { it.getJSONArray("source_references")
            .getJSONObject(0).put("url", "https://evil.invalid") })
        rejected(packet().also { it.getJSONArray("source_references")
            .getJSONObject(0).put("sha256", "not-a-sha") })
        rejected(packet().also { it.getJSONArray("source_references")
            .getJSONObject(0).put("inspected", true) })
    }

    @Test fun duplicateCaveatsOrFalseRunCountsAreRejected() {
        rejected(packet().also { it.getJSONObject("reasoning")
            .put("potential_caveat_count", 2) })
        rejected(packet().also { it.getJSONObject("reasoning")
            .put("blender_local_replays_checked", 2) })
    }

    @Test fun repeatedCaveatCannotInflateCountEvenWhenDeclaredCountMatches() {
        val root = packet()
        val source = root.getJSONArray("source_references").getJSONObject(0)
        source.getJSONArray("excerpts").put(JSONObject().put("block_index", 13)
            .put("excerpt_sha256", "b".repeat(64)).put("untrusted", true))
        source.getJSONArray("caveat_excerpt_hashes").put(hash)
        root.getJSONObject("reasoning").put("potential_caveat_count", 2)
        rejected(root)
    }

    @Test fun nextExperimentsCannotBeDeclaredExecuted() {
        rejected(packet().also { it.getJSONArray("next_experiments")
            .getJSONObject(0).put("status", "PASS") })
    }

    @Test fun unboundedOrInvalidJsonRejected() {
        assertTrue(runCatching { NightResearchPacketParser.parse("x".repeat(13_000)) }.isFailure)
        assertTrue(runCatching { NightResearchPacketParser.parse("{}") }.isFailure)
        assertFalse(runCatching { NightResearchPacketParser.parse(packet().toString()) }.isFailure)
    }
}
