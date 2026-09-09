package com.jadegenesis.mobile

import com.jadegenesis.mobile.night.NightLearningInbox
import com.jadegenesis.mobile.state.SharedStateEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightLearningInboxTest {
    @Test
    fun validLearningSnapshotIsParsedAsReviewOnlyCandidate() {
        val snapshot = NightLearningInbox.parseLatest(
            listOf(event(payload = validPayload(), revision = 10L))
        )

        assertNotNull(snapshot)
        requireNotNull(snapshot)
        assertEquals("run-1", snapshot.runId)
        assertEquals(1, snapshot.researchQuestions.size)
        assertEquals(1, snapshot.researchEvidence.size)
        assertEquals(1, snapshot.hypotheses.size)
        assertEquals(1, snapshot.experiments.size)
        assertEquals(1, snapshot.improvementCandidates.size)
        assertEquals("CANDIDATE", snapshot.improvementCandidates.first().status)
        assertTrue(snapshot.improvementCandidates.first().sandboxRequired)
    }

    @Test
    fun dangerousNewestSnapshotIsIgnoredInFavorOfOlderSafeSnapshot() {
        val unsafe = JSONObject(validPayload())
            .put("automatic_promotion", true)
            .toString()

        val snapshot = NightLearningInbox.parseLatest(
            listOf(
                event(payload = validPayload(), revision = 10L),
                event(payload = unsafe, revision = 11L)
            )
        )

        assertNotNull(snapshot)
        assertEquals(42L, snapshot?.sourceRevision)
    }

    @Test
    fun onlyDangerousSnapshotIsRejected() {
        val unsafe = JSONObject(validPayload())
            .put("shell_execution", true)
            .toString()

        assertNull(
            NightLearningInbox.parseLatest(
                listOf(event(payload = unsafe, revision = 10L))
            )
        )
    }

    @Test
    fun oversizedCandidateArrayIsRejected() {
        val json = JSONObject(validPayload())
        val candidates = JSONArray()
        repeat(5) { index ->
            candidates.put(
                JSONObject()
                    .put("candidate_id", "c-$index")
                    .put("kind", "CONFIG_HINT")
                    .put("status", "CANDIDATE")
                    .put("title", "Candidate $index")
                    .put("rationale", "Bounded")
                    .put("experiment_id", "exp-$index")
                    .put("target", "routing")
                    .put("sandbox_required", true)
                    .put("automatic_activation", false)
                    .put("automatic_promotion", false)
                    .put("production_code_change", false)
            )
        }
        json.put("improvement_candidates", candidates)

        assertNull(
            NightLearningInbox.parseLatest(
                listOf(event(payload = json.toString(), revision = 10L))
            )
        )
    }

    private fun event(payload: String, revision: Long): SharedStateEvent =
        SharedStateEvent(
            eventId = "event-$revision",
            originNode = "vps-test",
            kind = "vps_learning_snapshot",
            entityId = "current",
            payload = payload,
            createdAt = 1_000L + revision,
            serverRevision = revision
        )

    private fun validPayload(): String = JSONObject().apply {
        put("schema_version", 1)
        put("run_id", "run-1")
        put("source_revision", 42)
        put("generated_at", 1_000)
        put("reviewed_at", 1_500)
        put("runtime_observation_count", 20)
        put("runtime_confidence", 0.9)
        put("external_research_evidence_count", 1)
        put("automatic_experiment_execution", false)
        put("automatic_promotion", false)
        put("production_code_rewrite", false)
        put("shell_execution", false)
        put(
            "research_questions",
            JSONArray().put(
                JSONObject()
                    .put("question_id", "rq-1")
                    .put("query", "adaptive routing reliability")
                    .put("reason", "Measured fallback pressure")
                    .put("signal_kind", "fallback_pressure")
            )
        )
        put(
            "research_evidence",
            JSONArray().put(
                JSONObject()
                    .put("evidence_id", "ev-1")
                    .put("question_id", "rq-1")
                    .put("provider", "Public")
                    .put("title", "Evidence")
                    .put("url", "https://example.test/evidence")
                    .put("snippet", "Public bounded evidence")
                    .put("confidence", 0.7)
            )
        )
        put(
            "hypotheses",
            JSONArray().put(
                JSONObject()
                    .put("hypothesis_id", "hyp-1")
                    .put("signal_kind", "fallback_pressure")
                    .put("statement", "Recent failures are under-penalized")
                    .put("confidence", 0.8)
                    .put("falsifiable_metric", "fallback_rate")
                    .put("success_criterion", "lower fallback without reliability loss")
            )
        )
        put(
            "experiments",
            JSONArray().put(
                JSONObject()
                    .put("experiment_id", "exp-1")
                    .put("hypothesis_id", "hyp-1")
                    .put("target", "routing.history_failure_penalty")
                    .put("change_hint", "small bounded challenger")
                    .put("primary_metric", "fallback_rate")
                    .put("minimum_samples_per_side", 12)
                    .put("maximum_samples_per_side", 24)
                    .put("paired_scenarios_required", true)
                    .put("frozen_champion_required", true)
                    .put("sandbox_required", true)
                    .put("automatic_execution", false)
                    .put("requires_explicit_promotion_approval", true)
            )
        )
        put(
            "improvement_candidates",
            JSONArray().put(
                JSONObject()
                    .put("candidate_id", "candidate-1")
                    .put("kind", "CONFIG_HINT")
                    .put("status", "CANDIDATE")
                    .put("title", "Bounded routing challenger")
                    .put("rationale", "Test a small failure penalty change")
                    .put("experiment_id", "exp-1")
                    .put("target", "routing.history_failure_penalty")
                    .put("sandbox_required", true)
                    .put("automatic_activation", false)
                    .put("automatic_promotion", false)
                    .put("production_code_change", false)
            )
        )
    }.toString()
}
