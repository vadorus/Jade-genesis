from __future__ import annotations

import json
import unittest

from night_learning_lab import (
    MAX_EXPERIMENTS,
    MAX_HYPOTHESES,
    MAX_IMPROVEMENT_CANDIDATES,
    MAX_RESEARCH_EVIDENCE,
    MAX_RESEARCH_QUESTIONS,
    NightLearningLab,
)


class FakeResearchProvider:
    def search(self, query: str) -> list[dict]:
        return [{
            "provider": "FakePublic",
            "title": "Measured routing evidence",
            "url": "https://example.test/evidence",
            "snippet": f"Public evidence for {query[:80]}",
            "confidence": 0.7,
        }]


class CountingResearchProvider(FakeResearchProvider):
    def __init__(self) -> None:
        self.calls: list[str] = []

    def search(self, query: str) -> list[dict]:
        self.calls.append(query)
        return super().search(query)


class FailingResearchProvider:
    def search(self, query: str) -> list[dict]:
        raise TimeoutError("offline")


class NightLearningLabTest(unittest.TestCase):
    @staticmethod
    def event(kind: str, payload: dict, created_at: int = 1000) -> dict:
        return {
            "event_id": f"{kind}-1",
            "origin_node": "pixel-test",
            "kind": kind,
            "entity_id": "current",
            "payload": json.dumps(payload, separators=(",", ":")),
            "created_at": created_at,
        }

    def view(self, runtime: dict) -> dict:
        return {
            "identity_id": "jade-test",
            "revision": 42,
            "entities": [
                self.event("memory_cursor", {"id": 12}),
                self.event("config_snapshot", {"config": {"config_id": "cfg-1"}}),
                self.event("runtime_eval_snapshot", runtime),
                self.event("evolution_snapshot", {
                    "automatic_promotion": False,
                    "candidates": [],
                }),
            ],
        }

    def test_low_runtime_confidence_produces_evidence_collection_candidate(self) -> None:
        lab = NightLearningLab(FakeResearchProvider())
        result = lab.review(self.view({
            "observation_count": 5,
            "confidence": 0.3,
            "score": 70.0,
            "groups": [],
        }), now_ms=2000)

        self.assertGreaterEqual(len(result["research_questions"]), 1)
        self.assertGreaterEqual(len(result["hypotheses"]), 1)
        self.assertGreaterEqual(len(result["experiments"]), 1)
        self.assertGreaterEqual(len(result["improvement_candidates"]), 1)
        self.assertEqual(
            "EVIDENCE_COLLECTION",
            result["improvement_candidates"][0]["kind"],
        )
        self.assertFalse(result["automatic_experiment_execution"])
        self.assertFalse(result["automatic_promotion"])
        self.assertFalse(result["production_code_rewrite"])
        self.assertFalse(result["shell_execution"])

    def test_reliability_and_fallback_signals_prepare_config_hints_only(self) -> None:
        lab = NightLearningLab(FakeResearchProvider())
        result = lab.review(self.view({
            "observation_count": 30,
            "confidence": 1.0,
            "score": 65.0,
            "groups": [{
                "node_id": "pc-slow",
                "node_name": "PC slow",
                "task_kind": "brain_chat",
                "model": "model-a",
                "samples": 15,
                "success_rate": 0.70,
                "average_duration_ms": 5000.0,
                "fallback_rate": 0.40,
                "average_tokens_per_second": 20.0,
            }, {
                "node_id": "pc-fast",
                "node_name": "PC fast",
                "task_kind": "brain_chat",
                "model": "model-b",
                "samples": 15,
                "success_rate": 1.0,
                "average_duration_ms": 1000.0,
                "fallback_rate": 0.0,
                "average_tokens_per_second": 100.0,
            }],
        }), now_ms=2000)

        kinds = {item["kind"] for item in result["signals"]}
        self.assertIn("reliability_bottleneck", kinds)
        self.assertIn("fallback_pressure", kinds)
        self.assertIn("latency_outlier", kinds)
        self.assertIn("throughput_outlier", kinds)
        config_candidates = [
            item for item in result["improvement_candidates"]
            if item["kind"] == "CONFIG_HINT"
        ]
        self.assertGreaterEqual(len(config_candidates), 1)
        self.assertTrue(all(item["status"] == "CANDIDATE" for item in config_candidates))
        self.assertTrue(all(not item["automatic_activation"] for item in config_candidates))
        self.assertTrue(all(not item["automatic_promotion"] for item in config_candidates))

    def test_public_research_failure_keeps_queries_without_inventing_evidence(self) -> None:
        lab = NightLearningLab(FailingResearchProvider())
        result = lab.review(self.view({
            "observation_count": 4,
            "confidence": 0.2,
            "score": 40.0,
            "groups": [],
        }), now_ms=2000)

        self.assertGreaterEqual(len(result["research_questions"]), 1)
        self.assertEqual([], result["research_evidence"])
        self.assertGreaterEqual(len(result["research_errors"]), 1)
        self.assertGreaterEqual(len(result["hypotheses"]), 1)
        self.assertFalse(result["automatic_promotion"])

    def test_output_is_strictly_bounded(self) -> None:
        groups = []
        for index in range(30):
            groups.append({
                "node_id": f"node-{index}",
                "node_name": f"Node {index}",
                "task_kind": "brain_chat",
                "model": f"model-{index}",
                "samples": 20,
                "success_rate": 0.2,
                "average_duration_ms": 10000.0 + index,
                "fallback_rate": 0.9,
                "average_tokens_per_second": 1.0 + index,
            })
        result = NightLearningLab(FakeResearchProvider()).review(self.view({
            "observation_count": 200,
            "confidence": 1.0,
            "score": 30.0,
            "groups": groups,
        }), now_ms=2000)

        self.assertLessEqual(len(result["research_questions"]), MAX_RESEARCH_QUESTIONS)
        self.assertLessEqual(len(result["research_evidence"]), MAX_RESEARCH_EVIDENCE)
        self.assertLessEqual(len(result["hypotheses"]), MAX_HYPOTHESES)
        self.assertLessEqual(len(result["experiments"]), MAX_EXPERIMENTS)
        self.assertLessEqual(
            len(result["improvement_candidates"]),
            MAX_IMPROVEMENT_CANDIDATES,
        )

    def test_sparse_outcomes_collect_evidence_without_public_research(self) -> None:
        provider = CountingResearchProvider()
        result = NightLearningLab(provider).review(self.view({
            "observation_count": 20,
            "confidence": 1.0,
            "score": 85.0,
            "outcome_feedback_count": 2,
            "overall_outcome_quality": -0.5,
            "groups": [],
        }), now_ms=2000)

        self.assertEqual("outcome_evidence_low", result["signals"][0]["kind"])
        self.assertEqual("EVIDENCE_COLLECTION", result["improvement_candidates"][0]["kind"])
        self.assertFalse(result["external_research_attempted"])
        self.assertEqual([], provider.calls)
        self.assertTrue(result["outcome_consolidation_used"])
        self.assertFalse(result["raw_conversation_text_used"])
        self.assertFalse(result["user_feedback_promoted_to_external_fact"])

    def test_negative_outcomes_prepare_strategy_hint_only(self) -> None:
        provider = CountingResearchProvider()
        result = NightLearningLab(provider).review(self.view({
            "observation_count": 30,
            "confidence": 1.0,
            "score": 80.0,
            "outcome_feedback_count": 5,
            "overall_outcome_quality": -0.6,
            "groups": [{
                "node_id": "pc-a",
                "node_name": "PC A",
                "task_kind": "brain_chat",
                "model": "model-a",
                "brain_profile": "code",
                "samples": 20,
                "success_rate": 1.0,
                "average_duration_ms": 1000.0,
                "fallback_rate": 0.0,
                "average_tokens_per_second": 80.0,
                "outcome_samples": 5,
                "positive_outcomes": 1,
                "negative_outcomes": 2,
                "corrections": 2,
                "outcome_quality_score": -0.6,
                "outcome_confidence": 0.5,
            }],
        }), now_ms=2000)

        kinds = {item["kind"] for item in result["signals"]}
        self.assertIn("outcome_quality_risk", kinds)
        self.assertIn("outcome_correction_pressure", kinds)
        strategy = [
            item for item in result["improvement_candidates"]
            if item["kind"] == "STRATEGY_HINT"
        ]
        self.assertGreaterEqual(len(strategy), 1)
        self.assertTrue(all(item["status"] == "CANDIDATE" for item in strategy))
        self.assertTrue(all(item["sandbox_required"] for item in strategy))
        self.assertTrue(all(not item["automatic_activation"] for item in strategy))
        self.assertTrue(all(not item["automatic_promotion"] for item in strategy))
        self.assertEqual([], provider.calls)

    def test_outcome_advantage_requires_same_profile_and_enough_evidence(self) -> None:
        provider = CountingResearchProvider()
        result = NightLearningLab(provider).review(self.view({
            "observation_count": 40,
            "confidence": 1.0,
            "score": 90.0,
            "outcome_feedback_count": 10,
            "overall_outcome_quality": 0.5,
            "groups": [{
                "node_id": "pc-a",
                "node_name": "PC A",
                "task_kind": "brain_chat",
                "model": "model-good",
                "brain_profile": "code",
                "samples": 20,
                "success_rate": 1.0,
                "average_duration_ms": 1000.0,
                "fallback_rate": 0.0,
                "average_tokens_per_second": 80.0,
                "outcome_samples": 5,
                "positive_outcomes": 5,
                "negative_outcomes": 0,
                "corrections": 0,
                "outcome_quality_score": 0.8,
                "outcome_confidence": 0.5,
            }, {
                "node_id": "pc-b",
                "node_name": "PC B",
                "task_kind": "brain_chat",
                "model": "model-weak",
                "brain_profile": "code",
                "samples": 20,
                "success_rate": 1.0,
                "average_duration_ms": 1000.0,
                "fallback_rate": 0.0,
                "average_tokens_per_second": 80.0,
                "outcome_samples": 5,
                "positive_outcomes": 3,
                "negative_outcomes": 2,
                "corrections": 0,
                "outcome_quality_score": 0.2,
                "outcome_confidence": 0.5,
            }],
        }), now_ms=2000)

        advantage = [
            item for item in result["signals"]
            if item["kind"] == "outcome_quality_advantage"
        ]
        self.assertEqual(1, len(advantage))
        self.assertEqual("model-good", advantage[0]["preferred_model"])
        self.assertEqual("model-weak", advantage[0]["comparison_model"])
        self.assertGreaterEqual(advantage[0]["metric"], 0.35)
        strategy = [
            item for item in result["improvement_candidates"]
            if item["kind"] == "STRATEGY_HINT"
        ]
        self.assertGreaterEqual(len(strategy), 1)
        self.assertEqual([], provider.calls)

    def test_outcome_advantage_is_not_created_across_different_profiles(self) -> None:
        result = NightLearningLab(FakeResearchProvider()).review(self.view({
            "observation_count": 40,
            "confidence": 1.0,
            "score": 90.0,
            "outcome_feedback_count": 10,
            "overall_outcome_quality": 0.5,
            "groups": [{
                "node_id": "pc-a",
                "task_kind": "brain_chat",
                "model": "model-good",
                "brain_profile": "code",
                "samples": 20,
                "success_rate": 1.0,
                "average_duration_ms": 1000.0,
                "fallback_rate": 0.0,
                "average_tokens_per_second": 80.0,
                "outcome_samples": 5,
                "positive_outcomes": 5,
                "outcome_quality_score": 0.8,
                "outcome_confidence": 0.5,
            }, {
                "node_id": "pc-b",
                "task_kind": "brain_chat",
                "model": "model-weak",
                "brain_profile": "general",
                "samples": 20,
                "success_rate": 1.0,
                "average_duration_ms": 1000.0,
                "fallback_rate": 0.0,
                "average_tokens_per_second": 80.0,
                "outcome_samples": 5,
                "positive_outcomes": 3,
                "negative_outcomes": 2,
                "outcome_quality_score": 0.2,
                "outcome_confidence": 0.5,
            }],
        }), now_ms=2000)

        kinds = {item["kind"] for item in result["signals"]}
        self.assertNotIn("outcome_quality_advantage", kinds)


if __name__ == "__main__":
    unittest.main()
