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


if __name__ == "__main__":
    unittest.main()
