package com.jadegenesis.mobile.night

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class JadeLearningReviewParserTest {
    private fun fixture() = javaClass.getResourceAsStream("/jade-learning-review-synthetic.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
    private fun alteredReport(action: (JSONObject) -> Unit): String {
        val bundle = JSONObject(fixture())
        val report = JSONObject(Base64.getDecoder().decode(bundle.getString("night_report_base64")).toString(Charsets.UTF_8))
        action(report)
        val bytes = report.toString().toByteArray(Charsets.UTF_8)
        bundle.put("night_report_base64", Base64.getEncoder().encodeToString(bytes)).put("night_report_sha256", researchSha(bytes))
        return bundle.toString()
    }
    private fun rejected(raw: String) = assertTrue(runCatching { MobileResearchDossierParser.parse(raw) }.isFailure)

    @Test fun acceptsActualPythonExportForJadesOwnProposals() {
        val dossier = MobileResearchDossierParser.parse(fixture())
        assertEquals("JADE_SELF_IMPROVEMENT", dossier.scope)
        assertEquals(2, dossier.sources.size)
        assertEquals(1, dossier.experiments.size)
        assertTrue(dossier.artifacts.isEmpty())
        assertTrue(dossier.experiments.single().successCriterion.isNotBlank())
        assertEquals("runtime_eval", dossier.experiments.single().target)
        assertFalse(dossier.goal.lowercase().contains("roblox"))
        val summary = NightResearchPacketParser.parse(dossier.packet)
        assertEquals("JADE_SELF_IMPROVEMENT", summary.scope)
        assertEquals(1, summary.hypothesisCount)
        assertEquals(0, summary.blenderLocalChecks)
        assertEquals("PROPOSED_REQUIRES_EXPLICIT_SANDBOX_PLAN", summary.experiments.single().status)
    }
    @Test fun mismatchedReportBytesAndInjectedPermissionCannotImport() {
        rejected(JSONObject(fixture()).put("night_report_sha256", "0".repeat(64)).toString())
        rejected(JSONObject(fixture()).put("action_authorized", true).toString())
        rejected(JSONObject(fixture()).put("authorization_id", "injected").toString())
    }
    @Test fun recomputedHashCannotEnableAutomaticExecutionOrPromotion() {
        for (flag in listOf("automatic_experiment_execution", "automatic_promotion", "production_code_rewrite",
            "shell_execution", "raw_conversation_text_used", "user_feedback_promoted_to_external_fact"))
            rejected(alteredReport { it.put(flag, true) })
        rejected(alteredReport { it.getJSONArray("hypotheses").getJSONObject(0).put("promoted_as_external_fact", true) })
    }
    @Test fun hypothesisRelationAndPairingBoundariesAreRequired() {
        for ((key, value) in listOf("hypothesis_id" to "absent", "automatic_execution" to true,
            "frozen_champion_required" to false, "paired_scenarios_required" to false,
            "minimum_samples_per_side" to 1.5, "maximum_samples_per_side" to 101)) {
            rejected(alteredReport { it.getJSONArray("experiments").getJSONObject(0).put(key, value) })
        }
    }
    @Test fun duplicateIdentitiesAndMissingExplicitFlagsAreRejected() {
        rejected(alteredReport { it.getJSONArray("hypotheses").put(JSONObject(it.getJSONArray("hypotheses").getJSONObject(0).toString())) })
        rejected(alteredReport { it.remove("shell_execution") })
        rejected(alteredReport { it.put("runtime_confidence", 2.0) })
    }
    @Test fun untrustedTextRemainsDataAndCannotChangeControlledPlan() {
        val raw = alteredReport { it.getJSONArray("hypotheses").getJSONObject(0).put("statement", "<script>execute_shell()</script>") }
        val dossier = MobileResearchDossierParser.parse(raw)
        assertEquals("<script>execute_shell()</script>", dossier.sources[1].passages.single().text)
        assertEquals("PIXEL_LOCAL_EVIDENCE_RECHECK", MobileResearchAuthorization.ACTION)
        assertTrue(runCatching { MobileResearchAuthorization.pending(emptyList(), dossier.experiments.single().id, dossier.snapshotHash, 1000) }.isFailure)
    }
}
