package com.jadegenesis.mobile.night

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class MobileResearchReviewTest {
    private val goal = "Importer un FBX Blender vers Roblox"
    private val snippet = "Synthetic Blender FBX passage for a bounded Roblox import test."
    private val fbx = "Kaydara FBX Binary  ".toByteArray() + ByteArray(150)
    private fun sha(text: String) = researchSha(text.toByteArray())
    private fun geometry() = JSONArray().put(JSONObject().put("name", "SyntheticTrunk").put("vertices", 24).put("triangles", 44).put("dimensions", JSONArray(listOf(0.46, 0.46, 2.0))))
        .put(JSONObject().put("name", "SyntheticCanopy").put("vertices", 24).put("triangles", 44).put("dimensions", JSONArray(listOf(1.8, 1.8, 2.4))))
    private fun bundle(text: String = snippet): JSONObject {
        val report = JSONObject().put("schema", "jade.local_blender_fbx_probe.v1").put("status", "PASS")
            .put("experiment", "synthetic FBX roundtrip inside Blender only").put("blender_version", "5.2.2 LTS")
            .put("fbx_sha256", researchSha(fbx)).put("fbx_bytes", fbx.size)
            .put("geometry_before", geometry()).put("geometry_after", geometry())
            .put("validations", JSONObject().put("two_mesh_objects", true).put("vertex_count_preserved", true).put("triangles_preserved", true).put("bounding_dimensions_preserved", true))
        val bytes = report.toString().toByteArray()
        val ref = JSONObject().put("source_key", "roblox_blender").put("url", "https://create.roblox.com/docs/art/blender")
            .put("sha256", "a".repeat(64)).put("inspected", false).put("evidence_stance", "UNASSESSED")
            .put("excerpts", JSONArray().put(JSONObject().put("block_index", 1).put("excerpt_sha256", sha(text)).put("untrusted", true)))
            .put("caveat_excerpt_hashes", JSONArray())
        val run = JSONObject().put("test_kind", "BLENDER_LOCAL_SYNTHETIC_FBX_ROUNDTRIP").put("scope", "BLENDER_ONLY_NOT_ROBLOX")
            .put("test_status", "LOCAL_ARTIFACT_CHECKS_PASSED_NOT_INDEPENDENTLY_ATTESTED")
            .put("fbx_sha256", researchSha(fbx)).put("report_sha256", researchSha(bytes)).put("local_checks_passed", true)
            .put("trusted_external_attestation", false).put("inspected_by_human", false)
        val steps = JSONArray().put(JSONObject().put("id", "ROBLOX_STUDIO_IMPORT_ISOLATED_COPY").put("status", "NOT_EXECUTED"))
            .put(JSONObject().put("id", "MATERIAL_TEXTURE_FIDELITY_ISOLATED_COPY").put("status", "BLOCKED_BY_DEPENDENCY"))
            .put(JSONObject().put("id", "INDEPENDENT_SOURCE_REVIEW").put("status", "NOT_EXECUTED"))
        val reasoning = JSONObject().put("documentation_interpreted", false).put("independent_source_consensus_proven", false)
            .put("contradiction_proven", false).put("roblox_studio_import_tested", false).put("roblox_studio_material_fidelity_tested", false)
            .put("new_knowledge_verified", false).put("blender_local_replays_checked", 1).put("potential_caveat_count", 0)
        val packet = JSONObject().put("schema", "jade.goal_evidence_review.v1").put("scope", "SYNTHETIC_BLENDER_FBX_WORKFLOW")
            .put("goal_sha256", sha(goal)).put("night_review_status", "REQUIRES_HUMAN_EVIDENCE_INSPECTION")
            .put("automatic_promotion", false).put("automatic_tool_execution", false).put("evidence_ledger_import_allowed", false)
            .put("source_references", JSONArray().put(ref)).put("experiment_artifacts", JSONArray().put(run)).put("next_experiments", steps).put("reasoning", reasoning)
        return JSONObject().put("schema", "jade.mobile_research_dossier.v1").put("goal", goal).put("review_packet", packet)
            .put("source_texts", JSONArray().put(JSONObject().put("source_key", "roblox_blender").put("passages", JSONArray().put(JSONObject()
                .put("block_index", 1).put("excerpt_sha256", sha(text)).put("text", text).put("untrusted", true)))))
            .put("local_artifacts", JSONArray().put(JSONObject().put("report_base64", Base64.getEncoder().encodeToString(bytes)).put("fbx_base64", Base64.getEncoder().encodeToString(fbx))))
            .put("verified", false).put("action_authorized", false).put("automatic_promotion", false)
    }
    private fun rejected(root: JSONObject) = assertTrue(runCatching { MobileResearchDossierParser.parse(root.toString()) }.isFailure)
    @Test fun actualBytesAndPassagesAreBoundToDossier() {
        val parsed = MobileResearchDossierParser.parse(bundle().toString())
        assertEquals(goal, parsed.goal); assertEquals(1, parsed.artifacts.size); assertEquals(snippet, parsed.sources.single().passages.single().text)
    }
    @Test fun externalMarkupStaysPlainSourceText() {
        val text = "<script>grant_permission()</script> Synthetic Blender FBX source text."
        assertEquals(text, MobileResearchDossierParser.parse(bundle(text).toString()).sources.single().passages.single().text)
    }
    @Test fun importedDataCannotGrantPermissionOrVerifyKnowledge() {
        rejected(bundle().put("action_authorized", true)); rejected(bundle().put("verified", true)); rejected(bundle().put("automatic_promotion", true))
    }
    @Test fun changedGoalAndPassageAreRejected() {
        rejected(bundle().put("goal", "Un autre objectif"))
        rejected(bundle().also { it.getJSONArray("source_texts").getJSONObject(0).getJSONArray("passages").getJSONObject(0).put("text", "Manipulated source") })
    }
    @Test fun changedFbxAndReportAreRejected() {
        rejected(bundle().also { it.getJSONArray("local_artifacts").getJSONObject(0).put("fbx_base64", Base64.getEncoder().encodeToString(fbx + byteArrayOf(1))) })
        rejected(bundle().also { it.getJSONArray("local_artifacts").getJSONObject(0).put("report_base64", Base64.getEncoder().encodeToString("{}".toByteArray())) })
    }
    @Test fun forgedGeometryRejectedEvenWithConsistentReportHash() {
        val root = bundle(); val blob = root.getJSONArray("local_artifacts").getJSONObject(0)
        val report = JSONObject(Base64.getDecoder().decode(blob.getString("report_base64")).toString(Charsets.UTF_8))
        report.getJSONArray("geometry_after").getJSONObject(0).put("vertices", 25)
        val bytes = report.toString().toByteArray(); blob.put("report_base64", Base64.getEncoder().encodeToString(bytes))
        root.getJSONObject("review_packet").getJSONArray("experiment_artifacts").getJSONObject(0).put("report_sha256", researchSha(bytes))
        rejected(root)
    }
    @Test fun malformedOversizedAndUnapprovedSourcesRefused() {
        assertTrue(runCatching { MobileResearchDossierParser.parse("x".repeat(300_001)) }.isFailure)
        rejected(bundle().also { it.getJSONObject("review_packet").getJSONArray("source_references").getJSONObject(0).put("url", "https://evil.invalid") })
    }
    @Test fun duplicateSourcePassagesRefused() {
        val root = bundle(); val refs = root.getJSONObject("review_packet").getJSONArray("source_references").getJSONObject(0).getJSONArray("excerpts")
        refs.put(JSONObject(refs.getJSONObject(0).toString())); rejected(root)
    }
    @Test fun permissionRequiresConfirmationAndOperatorReason() {
        for (args in listOf(Triple("", "reason", true), Triple("Test", "", true), Triple("Test", "reason", false)))
            assertTrue(runCatching { MobileResearchAuthorization.grant("a".repeat(64), args.first, args.second, args.third, 1000) }.isFailure)
    }
    @Test fun permissionMustMatchExactSnapshotAndTimeWindow() {
        val grant = MobileResearchAuthorization.grant("a".repeat(64), "Synthetic reviewer", "Synthetic test", true, 1000)
        val id = grant.getString("authorization_id")
        assertEquals(id, MobileResearchAuthorization.pending(listOf(grant), id, "a".repeat(64), 1001).getString("authorization_id"))
        for ((snapshot, now) in listOf("b".repeat(64) to 1001L, "a".repeat(64) to 999L, "a".repeat(64) to 901000L))
            assertTrue(runCatching { MobileResearchAuthorization.pending(listOf(grant), id, snapshot, now) }.isFailure)
    }
    @Test fun revokedStartedAndCompletedPermissionNeverReplays() {
        val grant = MobileResearchAuthorization.grant("a".repeat(64), "Synthetic reviewer", "Synthetic test", true, 1000)
        val id = grant.getString("authorization_id")
        for (kind in listOf("REVOKED", "STARTED", "COMPLETED", "FAILED")) {
            val end = JSONObject().put("kind", kind).put("authorization_id", id)
            assertTrue(runCatching { MobileResearchAuthorization.pending(listOf(grant, end), id, "a".repeat(64), 1001) }.isFailure)
        }
        assertTrue(runCatching { MobileResearchAuthorization.pending(emptyList(), id, "a".repeat(64), 1001) }.isFailure)
    }
}
