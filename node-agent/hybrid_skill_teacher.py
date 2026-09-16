"""Hybrid teacher for Jade Genesis 0.1.21 V3: LLM ingredients + typed search.

Live V1/V2 showed that small CODE models name the right operations but cannot
assemble a valid JADE_PROCEDURE_DSL_V1 AST. V3 keeps the LLM as the proposal
source and adds a deterministic assembler:

1. the LLM teacher proposes a body; if it already reproduces every visible
   case it is returned unchanged;
2. otherwise its operation names and string literals become hints for
   `typed_skill_search`, which assembles a body from visible cases only;
3. if the LLM is unavailable or answers malformed JSON, the search still runs
   without hints, so learning never depends on one node being online.

The hybrid teacher sees exactly the same request as the LLM teacher. It never
receives SEALED_TEST data, never controls provenance, verifier, activation or
verdict, and the synthesis loop still evaluates and freezes every proposal.
"""

from __future__ import annotations

from typing import Any

from procedure_runtime import ExecutionBudget, _canonical_json, _eval_node
from typed_skill_search import hints_from_body, search_body

_VISIBLE_PARTITIONS = frozenset({"TRAIN", "VALIDATION"})


def _visible_cases(request: dict[str, Any]) -> list[tuple[Any, Any]]:
    cases = []
    for case in request.get("visible_cases", []):
        if isinstance(case, dict) and case.get("partition") in _VISIBLE_PARTITIONS:
            cases.append((case.get("input"), case.get("expected_output")))
    return cases


def _reproduces(body: Any, cases: list[tuple[Any, Any]]) -> bool:
    if not isinstance(body, dict) or not cases:
        return False
    for input_value, expected in cases:
        try:
            actual = _eval_node(body, input_value, ExecutionBudget())
        except Exception:
            return False
        if _canonical_json(actual) != _canonical_json(expected):
            return False
    return True


class HybridSkillTeacher:
    """Callable teacher: LLM proposal first, typed search assembly second."""

    def __init__(self, llm_teacher: Any):
        self.llm_teacher = llm_teacher
        self.call_count = 0
        self.last_path = ""
        self.last_llm_error = ""

    def __call__(self, request: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(request, dict):
            raise ValueError("teacher_request_must_be_object")
        for flag in (
            "sealed_test_inputs_exposed",
            "sealed_test_answers_exposed",
            "seal_nonce_exposed",
        ):
            if request.get(flag) is not False:
                raise PermissionError("hybrid_teacher_hidden_data_must_remain_hidden")

        cases = _visible_cases(request)
        proposal: dict[str, Any] | None = None
        self.last_llm_error = ""
        try:
            proposal = self.llm_teacher(request)
        except PermissionError:
            raise
        except (ValueError, RuntimeError, OSError) as exc:
            self.last_llm_error = f"{type(exc).__name__}: {str(exc)[:160]}"

        body = proposal.get("body") if isinstance(proposal, dict) else None
        if _reproduces(body, cases):
            self.call_count += 1
            self.last_path = "llm"
            return proposal

        hint_ops, hint_literals = hints_from_body(body)
        found = search_body(cases, hint_ops=hint_ops, hint_literals=hint_literals)
        if found is None:
            if proposal is None:
                raise ValueError("hybrid_teacher_no_candidate")
            # Let the synthesis loop record the LLM proposal's visible failure.
            self.call_count += 1
            self.last_path = "llm_unrepaired"
            return proposal

        self.call_count += 1
        self.last_path = "typed_search_with_llm_hints" if proposal else "typed_search_only"
        skill_id = proposal.get("skill_id") if isinstance(proposal, dict) else ""
        return {
            "skill_id": skill_id or f"learned-{request.get('task_family', 'skill')}",
            "description": "Assembled by deterministic typed search from visible cases.",
            "domain": "verifiable_procedure",
            "body": found,
        }

    def status(self) -> dict[str, Any]:
        llm_status = getattr(self.llm_teacher, "status", None)
        return {
            "teacher_kind": "hybrid_llm_typed_search",
            "proposal_only": True,
            "call_count": self.call_count,
            "last_path": self.last_path,
            "last_llm_error": self.last_llm_error,
            "llm_teacher": llm_status() if callable(llm_status) else {},
            "search_uses_visible_cases_only": True,
            "controls_provenance": False,
            "controls_verifier": False,
            "controls_sealed_partition": False,
            "controls_activation": False,
        }
