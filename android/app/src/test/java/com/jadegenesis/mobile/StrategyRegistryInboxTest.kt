package com.jadegenesis.mobile

import com.jadegenesis.mobile.night.StrategyRegistryInbox
import com.jadegenesis.mobile.state.SharedStateEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyRegistryInboxTest {
    @Test
    fun validCandidateSnapshotIsParsedAsPersistentReviewState() {
        val snapshot = StrategyRegistryInbox.parseLatest(
            listOf(event(validPayload(), 20L))
        )

        assertNotNull(snapshot)
        requireNotNull(snapshot)
        assertEquals("run-strategy-1", snapshot.runId)
        assertEquals(7L, snapshot.registryRevision)
        assertEquals(42L, snapshot.sourceRevision)
        assertEquals(1, snapshot.entryCount)
        assertEquals(1, snapshot.candidateCount)
        assertEquals(1, snapshot.entries.size)

        val entry = snapshot.entries.first()
        assertEquals("strategy-1", entry.strategyId)
        assertEquals("CANDIDATE", entry.status)
        assertEquals("routing.profile_model_preference", entry.target)
        assertEquals("prefer", entry.mode)
        assertEquals("brain_chat", entry.taskKind)
        assertEquals("code", entry.brainProfile)
        assertEquals("model-fast", entry.preferredModel)
        assertTrue(entry.sandboxRequired)
        assertTrue(entry.pairedScenariosRequired)
        assertTrue(entry.frozenChampionRequired)
        assertTrue(entry.explicitPromotionApprovalRequired)
        assertFalse(entry.explicitPromotionApproved)
        assertFalse(entry.runtimeApplicationEnabled)
    }

    @Test
    fun unsafeNewestSnapshotFallsBackToOlderSafeSnapshot() {
        val unsafe = JSONObject(validPayload())
            .put("automatic_runtime_application", true)
            .toString()

        val snapshot = StrategyRegistryInbox.parseLatest(
            listOf(
                event(validPayload(), 20L),
                event(unsafe, 21L)
            )
        )

        assertNotNull(snapshot)
        assertEquals(42L, snapshot?.sourceRevision)
    }

    @Test
    fun automaticPromotionSnapshotIsRejected() {
        val unsafe = JSONObject(validPayload())
            .put("automatic_promotion", true)
            .toString()

        assertNull(StrategyRegistryInbox.parseLatest(listOf(event(unsafe, 20L))))
    }

    @Test
    fun performedRuntimeApplicationSnapshotIsRejected() {
        val unsafe = JSONObject(validPayload())
            .put("runtime_application_performed", true)
            .toString()

        assertNull(StrategyRegistryInbox.parseLatest(listOf(event(unsafe, 20L))))
    }

    @Test
    fun rawConversationAndExternalFactPromotionAreRejected() {
        val raw = JSONObject(validPayload())
            .put("raw_conversation_text_used", true)
            .toString()
        assertNull(StrategyRegistryInbox.parseLatest(listOf(event(raw, 20L))))

        val externalFact = JSONObject(validPayload())
            .put("user_feedback_promoted_to_external_fact", true)
            .toString()
        assertNull(StrategyRegistryInbox.parseLatest(listOf(event(externalFact, 20L))))
    }

    @Test
    fun missingRequiredSafetyGuardsAreRejected() {
        val missingTopLevel = JSONObject(validPayload())
        missingTopLevel.remove("automatic_runtime_application")
        assertNull(
            StrategyRegistryInbox.parseLatest(
                listOf(event(missingTopLevel.toString(), 20L))
            )
        )

        val missingEntryGuard = JSONObject(validPayload())
        missingEntryGuard.getJSONArray("entries")
            .getJSONObject(0)
            .remove("sandbox_required")
        assertNull(
            StrategyRegistryInbox.parseLatest(
                listOf(event(missingEntryGuard.toString(), 20L))
            )
        )
    }

    @Test
    fun entryWithRuntimeApplicationEnabledIsRejected() {
        val json = JSONObject(validPayload())
        json.getJSONArray("entries")
            .getJSONObject(0)
            .put("runtime_application_enabled", true)

        assertNull(
            StrategyRegistryInbox.parseLatest(
                listOf(event(json.toString(), 20L))
            )
        )
    }

    @Test
    fun activeEntryWithoutExplicitApprovalIsRejected() {
        val json = JSONObject(validPayload())
        json.getJSONArray("entries")
            .getJSONObject(0)
            .put("status", "ACTIVE")
            .put("evaluation_passed", true)
            .put("evaluation_confidence", 0.90)
            .put("evaluation_sample_count", 8)
            .put("explicit_promotion_approved", false)

        assertNull(
            StrategyRegistryInbox.parseLatest(
                listOf(event(json.toString(), 20L))
            )
        )
    }

    @Test
    fun validExplicitlyApprovedActiveEntryParsesButRuntimeStaysDisabled() {
        val json = JSONObject(validPayload())
        json.put("candidate_count", 0)
            .put("active_count", 1)
        json.getJSONArray("entries")
            .getJSONObject(0)
            .put("status", "ACTIVE")
            .put("evaluation_passed", true)
            .put("evaluation_confidence", 0.90)
            .put("evaluation_sample_count", 8)
            .put("explicit_promotion_approved", true)
            .put("runtime_application_enabled", false)

        val snapshot = StrategyRegistryInbox.parseLatest(
            listOf(event(json.toString(), 20L))
        )

        assertNotNull(snapshot)
        requireNotNull(snapshot)
        assertEquals(1, snapshot.activeCount)
        val entry = snapshot.entries.first()
        assertEquals("ACTIVE", entry.status)
        assertTrue(entry.explicitPromotionApproved)
        assertTrue(entry.evaluationPassed)
        assertFalse(entry.runtimeApplicationEnabled)
    }

    @Test
    fun validatedEntryNeedsMinimumSandboxEvidence() {
        val tooFew = JSONObject(validPayload())
        tooFew.getJSONArray("entries")
            .getJSONObject(0)
            .put("status", "VALIDATED")
            .put("evaluation_passed", true)
            .put("evaluation_confidence", 0.90)
            .put("evaluation_sample_count", 4)
        assertNull(
            StrategyRegistryInbox.parseLatest(
                listOf(event(tooFew.toString(), 20L))
            )
        )

        val tooWeak = JSONObject(validPayload())
        tooWeak.getJSONArray("entries")
            .getJSONObject(0)
            .put("status", "VALIDATED")
            .put("evaluation_passed", true)
            .put("evaluation_confidence", 0.74)
            .put("evaluation_sample_count", 8)
        assertNull(
            StrategyRegistryInbox.parseLatest(
                listOf(event(tooWeak.toString(), 20L))
            )
        )
    }

    @Test
    fun oversizedEntryArrayIsRejected() {
        val json = JSONObject(validPayload())
        val template = json.getJSONArray("entries").getJSONObject(0)
        val entries = JSONArray()
        repeat(9) { index ->
            entries.put(JSONObject(template.toString()).put("strategy_id", "strategy-$index"))
        }
        json.put("entries", entries)

        assertNull(
            StrategyRegistryInbox.parseLatest(
                listOf(event(json.toString(), 20L))
            )
        )
    }

    @Test
    fun aggregateCountsAndScoresAreClamped() {
        val json = JSONObject(validPayload())
            .put("entry_count", 999_999)
            .put("candidate_count", 999_999)
        json.getJSONArray("entries")
            .getJSONObject(0)
            .put("score", 999.0)
            .put("confidence", 8.0)
            .put("outcome_confidence", -8.0)
            .put("sample_count", 999_999)

        val snapshot = StrategyRegistryInbox.parseLatest(
            listOf(event(json.toString(), 20L))
        )

        assertNotNull(snapshot)
        requireNotNull(snapshot)
        assertEquals(100, snapshot.entryCount)
        assertEquals(100, snapshot.candidateCount)
        val entry = snapshot.entries.first()
        assertEquals(2.0, entry.score, 0.0001)
        assertEquals(1.0, entry.confidence, 0.0001)
        assertEquals(0.0, entry.outcomeConfidence, 0.0001)
        assertEquals(1_000, entry.sampleCount)
    }

    private fun event(payload: String, revision: Long): SharedStateEvent =
        SharedStateEvent(
            eventId = "strategy-event-$revision",
            originNode = "vps-test",
            kind = "vps_strategy_registry_snapshot",
            entityId = "current",
            payload = payload,
            createdAt = 2_000L + revision,
            serverRevision = revision
        )

    private fun validPayload(): String = JSONObject().apply {
        put("schema_version", 1)
        put("run_id", "run-strategy-1")
        put("registry_revision", 7)
        put("source_revision", 42)
        put("generated_at", 3_000)
        put("reviewed_at", 3_500)
        put("updated_at", 3_400)
        put("entry_count", 1)
        put("candidate_count", 1)
        put("validated_count", 0)
        put("active_count", 0)
        put("rejected_count", 0)
        put("automatic_candidate_persistence", true)
        put("automatic_promotion", false)
        put("automatic_runtime_application", false)
        put("runtime_application_performed", false)
        put("production_code_rewrite", false)
        put("shell_execution", false)
        put("raw_conversation_text_used", false)
        put("user_feedback_promoted_to_external_fact", false)
        put(
            "entries",
            JSONArray().put(
                JSONObject()
                    .put("strategy_id", "strategy-1")
                    .put("semantic_key", "strategy-key-1")
                    .put("version", 1)
                    .put("status", "CANDIDATE")
                    .put("kind", "ROUTING_STRATEGY")
                    .put("target", "routing.profile_model_preference")
                    .put("mode", "prefer")
                    .put("task_kind", "brain_chat")
                    .put("brain_profile", "code")
                    .put("node_id", "pc-node")
                    .put("model", "model-fast")
                    .put("preferred_node_id", "pc-node")
                    .put("preferred_model", "model-fast")
                    .put("comparison_node_id", "vps-node")
                    .put("comparison_model", "model-slow")
                    .put("score", 0.55)
                    .put("confidence", 0.86)
                    .put("outcome_confidence", 0.88)
                    .put("sample_count", 9)
                    .put("source_revision", 42)
                    .put("candidate_id", "candidate-1")
                    .put("experiment_id", "experiment-1")
                    .put("hypothesis_id", "hypothesis-1")
                    .put("signal_kind", "outcome_quality_advantage")
                    .put("sandbox_required", true)
                    .put("paired_scenarios_required", true)
                    .put("frozen_champion_required", true)
                    .put("explicit_promotion_approval_required", true)
                    .put("explicit_promotion_approved", false)
                    .put("runtime_application_enabled", false)
                    .put("automatic_activation", false)
                    .put("automatic_promotion", false)
                    .put("production_code_change", false)
                    .put("evaluation_passed", false)
                    .put("evaluation_confidence", 0.0)
                    .put("evaluation_sample_count", 0)
                    .put("replaces_strategy_id", "")
            )
        )
    }.toString()
}
