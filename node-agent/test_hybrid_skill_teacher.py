from __future__ import annotations

import unittest

from hybrid_skill_teacher import HybridSkillTeacher
from procedure_runtime import ExecutionBudget, _eval_node


def request() -> dict:
    return {
        "request_kind": "JADE_SKILL_TEACHER_REQUEST_V2",
        "task_family": "normalize_label_v1",
        "visible_cases": [
            {"case_id": "t1", "partition": "TRAIN", "input": {"text": "  Rouge"}, "expected_output": {"text": "rouge"}},
            {"case_id": "t2", "partition": "TRAIN", "input": {"text": "MAISON "}, "expected_output": {"text": "maison"}},
            {"case_id": "v1", "partition": "VALIDATION", "input": {"text": " ÉtÉ"}, "expected_output": {"text": "été"}},
        ],
        "sealed_test_inputs_exposed": False,
        "sealed_test_answers_exposed": False,
        "seal_nonce_exposed": False,
    }


CORRECT = {
    "op": "object",
    "args": [
        {"op": "literal", "args": ["text"]},
        {"op": "lower", "args": [{"op": "trim", "args": [{"op": "get", "args": [{"op": "literal", "args": ["text"]}]}]}]},
    ],
}
# Shape observed live from qwen3:4b: right ingredients, wrong assembly.
NEAR_MISS = {
    "op": "concat",
    "args": [
        {"op": "trim", "args": [{"op": "get", "args": ["text"]}]},
        {"op": "lower", "args": [{"op": "get", "args": ["text"]}]},
    ],
}


class HybridSkillTeacherTest(unittest.TestCase):
    def test_llm_proposal_that_reproduces_visible_cases_is_returned_unchanged(self) -> None:
        proposal = {"skill_id": "norm", "description": "d", "domain": "verifiable_procedure", "body": CORRECT}
        teacher = HybridSkillTeacher(lambda _: proposal)
        self.assertIs(proposal, teacher(request()))
        self.assertEqual("llm", teacher.last_path)
        self.assertEqual(1, teacher.call_count)

    def test_near_miss_is_repaired_by_typed_search(self) -> None:
        teacher = HybridSkillTeacher(
            lambda _: {"skill_id": "norm", "description": "d", "domain": "x", "body": NEAR_MISS}
        )
        result = teacher(request())
        self.assertEqual("typed_search_with_llm_hints", teacher.last_path)
        self.assertEqual("norm", result["skill_id"])
        self.assertEqual({"text": "jade"}, _eval_node(result["body"], {"text": " JaDe "}, ExecutionBudget()))

    def test_unavailable_or_malformed_llm_falls_back_to_search(self) -> None:
        for error in (RuntimeError("teacher_code_model_unavailable"), ValueError("teacher_response_invalid_json"), TimeoutError("timed out")):
            def llm(_: dict, error: Exception = error) -> dict:
                raise error

            teacher = HybridSkillTeacher(llm)
            result = teacher(request())
            self.assertEqual("typed_search_only", teacher.last_path)
            self.assertIn(type(error).__name__, teacher.last_llm_error)
            self.assertEqual("learned-normalize_label_v1", result["skill_id"])

    def test_hidden_data_exposure_is_refused_before_llm_call(self) -> None:
        calls = 0

        def llm(_: dict) -> dict:
            nonlocal calls
            calls += 1
            return {}

        teacher = HybridSkillTeacher(llm)
        exposed = request()
        exposed["sealed_test_answers_exposed"] = True
        with self.assertRaises(PermissionError):
            teacher(exposed)
        self.assertEqual(0, calls)

    def test_llm_permission_error_is_never_swallowed(self) -> None:
        def llm(_: dict) -> dict:
            raise PermissionError("teacher_sealed_inputs_must_remain_hidden")

        with self.assertRaises(PermissionError):
            HybridSkillTeacher(llm)(request())

    def test_search_ignores_non_visible_partitions(self) -> None:
        poisoned = request()
        poisoned["visible_cases"].append(
            {"case_id": "s1", "partition": "SEALED_TEST", "input": {"text": "x"}, "expected_output": {"text": "IMPOSSIBLE"}}
        )
        teacher = HybridSkillTeacher(lambda _: {"body": NEAR_MISS})
        result = teacher(poisoned)
        self.assertEqual("typed_search_with_llm_hints", teacher.last_path)
        self.assertEqual({"text": "x"}, _eval_node(result["body"], {"text": "x"}, ExecutionBudget()))

    def test_no_candidate_without_llm_and_without_search_solution(self) -> None:
        impossible = request()
        for case in impossible["visible_cases"]:
            case["expected_output"] = {"text": case["case_id"] + "?"}

        def llm(_: dict) -> dict:
            raise RuntimeError("offline")

        with self.assertRaisesRegex(ValueError, "hybrid_teacher_no_candidate"):
            HybridSkillTeacher(llm)(impossible)


if __name__ == "__main__":
    unittest.main()
