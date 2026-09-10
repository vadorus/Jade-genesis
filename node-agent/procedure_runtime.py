"""Restricted deterministic procedure interpreter for Jade Genesis 0.1.20.

This runtime executes only validated JADE_PROCEDURE_DSL_V1 SkillSpecs whose
provenance is explicitly DEVELOPER in 0.1.20. Generated/external-teacher skills
remain non-executable until the 0.1.21 isolation and synthesis boundary exists.

The interpreter has no primitive for filesystem, network, shell/process,
imports, environment access, clock, randomness, reflection or arbitrary code.
Inputs/literals/results are restricted to bounded JSON values.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass
from typing import Any, Iterable

from skill_spec import BODY_KIND, normalize_skill_spec

RUNTIME_VERSION = "1"
MAX_STEPS = 512
MAX_COST_UNITS = 32_768
MAX_VALUE_DEPTH = 16
MAX_STRING_CHARS = 4_096
MAX_COLLECTION_ITEMS = 256
MAX_JSON_BYTES = 16_384
EXECUTABLE_SOURCE_KINDS = frozenset({"DEVELOPER"})

_SOURCE_SPEC_FIELDS = frozenset(
    {
        "schema_version",
        "skill_id",
        "skill_version",
        "task_family",
        "domain",
        "description",
        "input_contract",
        "output_contract",
        "body_kind",
        "body",
        "dependencies",
        "provenance",
        "evaluation_policy",
    }
)


class ProcedureRuntimeError(ValueError):
    """Deterministic execution failure with a stable error code."""


@dataclass
class ExecutionBudget:
    max_steps: int = MAX_STEPS
    max_cost_units: int = MAX_COST_UNITS
    steps: int = 0
    cost_units: int = 0

    def charge(self, cost: int = 1) -> None:
        normalized = max(1, int(cost))
        self.steps += 1
        self.cost_units += normalized
        if self.steps > self.max_steps:
            raise ProcedureRuntimeError("procedure_step_budget_exceeded")
        if self.cost_units > self.max_cost_units:
            raise ProcedureRuntimeError("procedure_cost_budget_exceeded")


def _normalize_json(value: Any, depth: int = 0) -> Any:
    if depth > MAX_VALUE_DEPTH:
        raise ProcedureRuntimeError("procedure_value_too_deep")
    if value is None or isinstance(value, bool) or isinstance(value, int):
        return value
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ProcedureRuntimeError("procedure_non_finite_number")
        return value
    if isinstance(value, str):
        if len(value) > MAX_STRING_CHARS:
            raise ProcedureRuntimeError("procedure_string_too_large")
        return value
    if isinstance(value, list):
        if len(value) > MAX_COLLECTION_ITEMS:
            raise ProcedureRuntimeError("procedure_collection_too_large")
        return [_normalize_json(item, depth + 1) for item in value]
    if isinstance(value, dict):
        if len(value) > MAX_COLLECTION_ITEMS:
            raise ProcedureRuntimeError("procedure_collection_too_large")
        result: dict[str, Any] = {}
        for key, item in value.items():
            if not isinstance(key, str) or not key:
                raise ProcedureRuntimeError("procedure_object_key_invalid")
            if len(key) > MAX_STRING_CHARS:
                raise ProcedureRuntimeError("procedure_object_key_too_large")
            result[key] = _normalize_json(item, depth + 1)
        return result
    raise ProcedureRuntimeError("procedure_unsupported_value_type")


def _canonical_json(value: Any) -> str:
    normalized = _normalize_json(value)
    encoded = json.dumps(
        normalized,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    )
    if len(encoded.encode("utf-8")) > MAX_JSON_BYTES:
        raise ProcedureRuntimeError("procedure_json_too_large")
    return encoded


def _value_cost(value: Any, depth: int = 0) -> int:
    if depth > MAX_VALUE_DEPTH:
        raise ProcedureRuntimeError("procedure_value_too_deep")
    if value is None or isinstance(value, (bool, int, float)):
        return 1
    if isinstance(value, str):
        return 1 + len(value.encode("utf-8")) // 32
    if isinstance(value, list):
        return 1 + len(value) + sum(_value_cost(item, depth + 1) for item in value)
    if isinstance(value, dict):
        return (
            1
            + len(value)
            + sum(
                len(key.encode("utf-8")) // 32 + _value_cost(item, depth + 1)
                for key, item in value.items()
            )
        )
    raise ProcedureRuntimeError("procedure_unsupported_value_type")


def _validated_skill_spec(raw_spec: dict[str, Any]) -> dict[str, Any]:
    """Accept source or canonical normalized SkillSpec, never unchecked derived state.

    Registries/verifiers persist the canonical normalized representation because
    it includes stable hashes and explicit safety flags. The source contract
    validator intentionally rejects those derived fields. For a persisted
    normalized spec, reconstruct the source contract, normalize it again, and
    require byte-for-byte-equivalent Python data before execution. Any changed
    hash, safety flag, body, contract or unexpected field therefore fails closed.
    """

    if not isinstance(raw_spec, dict):
        raise ProcedureRuntimeError("procedure_skill_spec_must_be_object")

    if "spec_sha256" not in raw_spec and "body_sha256" not in raw_spec:
        return normalize_skill_spec(raw_spec)

    source = {
        key: raw_spec[key]
        for key in _SOURCE_SPEC_FIELDS
        if key in raw_spec
    }
    try:
        canonical = normalize_skill_spec(source)
    except (ValueError, PermissionError) as exc:
        raise ProcedureRuntimeError("procedure_normalized_skill_invalid") from exc

    if set(raw_spec.keys()) != set(canonical.keys()):
        raise ProcedureRuntimeError("procedure_normalized_skill_shape_invalid")
    if raw_spec != canonical:
        raise ProcedureRuntimeError("procedure_normalized_skill_integrity_mismatch")
    return canonical


def _is_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _expect_arity(op: str, values: list[Any], allowed: Iterable[int]) -> None:
    if len(values) not in set(allowed):
        raise ProcedureRuntimeError(f"procedure_{op}_arity")


def _expect_string(value: Any, op: str) -> str:
    if not isinstance(value, str):
        raise ProcedureRuntimeError(f"procedure_{op}_requires_string")
    return value


def _expect_bool(value: Any, op: str) -> bool:
    if not isinstance(value, bool):
        raise ProcedureRuntimeError(f"procedure_{op}_requires_boolean")
    return value


def _expect_number(value: Any, op: str) -> int | float:
    if not _is_number(value):
        raise ProcedureRuntimeError(f"procedure_{op}_requires_number")
    return value


def _validate_contract_value(value: Any, contract: dict[str, Any], name: str) -> None:
    kind = str(contract.get("type", "any"))
    matches = {
        "any": True,
        "object": isinstance(value, dict),
        "array": isinstance(value, list),
        "string": isinstance(value, str),
        "number": _is_number(value),
        "integer": isinstance(value, int) and not isinstance(value, bool),
        "boolean": isinstance(value, bool),
        "null": value is None,
    }.get(kind, False)
    if not matches:
        raise ProcedureRuntimeError(f"procedure_{name}_contract_type_mismatch")
    if kind == "object":
        required = contract.get("required_fields", [])
        if any(field not in value for field in required):
            raise ProcedureRuntimeError(f"procedure_{name}_contract_missing_field")


def _finish(value: Any, budget: ExecutionBudget) -> Any:
    normalized = _normalize_json(value)
    _canonical_json(normalized)
    budget.charge(_value_cost(normalized))
    return normalized


def _eval_node(
    node: dict[str, Any],
    root_input: Any,
    budget: ExecutionBudget,
    depth: int = 0,
) -> Any:
    if depth > MAX_VALUE_DEPTH:
        raise ProcedureRuntimeError("procedure_ast_too_deep")
    op = node["op"]
    args = node["args"]
    budget.charge(1)

    if op == "input":
        return _finish(root_input, budget)
    if op == "literal":
        return _finish(args[0], budget)

    if op == "if":
        condition = _eval_node(args[0], root_input, budget, depth + 1)
        _expect_bool(condition, op)
        branch = args[1] if condition else args[2]
        return _eval_node(branch, root_input, budget, depth + 1)

    values = [_eval_node(item, root_input, budget, depth + 1) for item in args]

    if op == "get":
        _expect_arity(op, values, (1, 2))
        if len(values) == 1:
            container = root_input
            key = values[0]
        else:
            container, key = values
        if isinstance(container, dict):
            if not isinstance(key, str):
                raise ProcedureRuntimeError("procedure_get_object_key_requires_string")
            if key not in container:
                raise ProcedureRuntimeError("procedure_get_missing_key")
            return _finish(container[key], budget)
        if isinstance(container, list):
            if not isinstance(key, int) or isinstance(key, bool):
                raise ProcedureRuntimeError("procedure_get_array_index_requires_integer")
            if key < 0 or key >= len(container):
                raise ProcedureRuntimeError("procedure_get_index_out_of_range")
            return _finish(container[key], budget)
        raise ProcedureRuntimeError("procedure_get_requires_object_or_array")

    if op == "object":
        if len(values) % 2 != 0:
            raise ProcedureRuntimeError("procedure_object_requires_key_value_pairs")
        result: dict[str, Any] = {}
        for index in range(0, len(values), 2):
            key = values[index]
            if not isinstance(key, str) or not key:
                raise ProcedureRuntimeError("procedure_object_key_requires_string")
            result[key] = values[index + 1]
        return _finish(result, budget)

    if op == "array":
        return _finish(values, budget)

    if op == "concat":
        if not values:
            raise ProcedureRuntimeError("procedure_concat_arity")
        parts = [_expect_string(value, op) for value in values]
        budget.charge(1 + sum(len(part) for part in parts) // 32)
        return _finish("".join(parts), budget)

    if op in {"lower", "upper", "trim"}:
        _expect_arity(op, values, (1,))
        text = _expect_string(values[0], op)
        budget.charge(1 + len(text) // 32)
        if op == "lower":
            return _finish(text.lower(), budget)
        if op == "upper":
            return _finish(text.upper(), budget)
        return _finish(text.strip(), budget)

    if op == "replace":
        _expect_arity(op, values, (3,))
        text = _expect_string(values[0], op)
        old = _expect_string(values[1], op)
        new = _expect_string(values[2], op)
        budget.charge(1 + (len(text) + len(old) + len(new)) // 32)
        return _finish(text.replace(old, new), budget)

    if op == "split":
        _expect_arity(op, values, (2,))
        text = _expect_string(values[0], op)
        separator = _expect_string(values[1], op)
        if separator == "":
            raise ProcedureRuntimeError("procedure_split_empty_separator")
        budget.charge(1 + len(text) // 32)
        return _finish(text.split(separator), budget)

    if op == "join":
        _expect_arity(op, values, (2,))
        separator = _expect_string(values[0], op)
        items = values[1]
        if not isinstance(items, list) or any(not isinstance(item, str) for item in items):
            raise ProcedureRuntimeError("procedure_join_requires_string_array")
        budget.charge(1 + (len(separator) + sum(len(item) for item in items)) // 32)
        return _finish(separator.join(items), budget)

    if op == "length":
        _expect_arity(op, values, (1,))
        value = values[0]
        if not isinstance(value, (str, list, dict)):
            raise ProcedureRuntimeError("procedure_length_unsupported_type")
        return _finish(len(value), budget)

    if op in {"add", "subtract", "multiply", "divide", "modulo"}:
        _expect_arity(op, values, (2,))
        left = _expect_number(values[0], op)
        right = _expect_number(values[1], op)
        if op == "add":
            result = left + right
        elif op == "subtract":
            result = left - right
        elif op == "multiply":
            result = left * right
        elif op == "divide":
            if right == 0:
                raise ProcedureRuntimeError("procedure_division_by_zero")
            result = left / right
        else:
            if right == 0:
                raise ProcedureRuntimeError("procedure_modulo_by_zero")
            result = left % right
        if isinstance(result, float) and not math.isfinite(result):
            raise ProcedureRuntimeError("procedure_non_finite_number")
        return _finish(result, budget)

    if op == "equals":
        _expect_arity(op, values, (2,))
        return _finish(_canonical_json(values[0]) == _canonical_json(values[1]), budget)

    if op in {"lt", "lte", "gt", "gte"}:
        _expect_arity(op, values, (2,))
        left, right = values
        comparable = (
            (_is_number(left) and _is_number(right))
            or (isinstance(left, str) and isinstance(right, str))
        )
        if not comparable:
            raise ProcedureRuntimeError(f"procedure_{op}_incomparable_values")
        if op == "lt":
            result = left < right
        elif op == "lte":
            result = left <= right
        elif op == "gt":
            result = left > right
        else:
            result = left >= right
        return _finish(result, budget)

    if op == "not":
        _expect_arity(op, values, (1,))
        return _finish(not _expect_bool(values[0], op), budget)

    if op in {"and", "or"}:
        _expect_arity(op, values, (2,))
        left = _expect_bool(values[0], op)
        right = _expect_bool(values[1], op)
        return _finish(left and right if op == "and" else left or right, budget)

    raise ProcedureRuntimeError("procedure_operation_not_implemented")


def execute_skill(
    raw_spec: dict[str, Any],
    input_value: Any,
    *,
    allowed_source_kinds: Iterable[str] = EXECUTABLE_SOURCE_KINDS,
    max_steps: int = MAX_STEPS,
    max_cost_units: int = MAX_COST_UNITS,
) -> dict[str, Any]:
    """Execute one developer-authorized pure SkillSpec under deterministic budgets."""

    spec = _validated_skill_spec(raw_spec)
    allowed = {str(item).strip().upper() for item in allowed_source_kinds}
    source_kind = str(spec["provenance"]["source_kind"]).upper()
    if source_kind not in allowed:
        raise PermissionError("procedure_skill_source_not_executable")

    if spec["body_kind"] != BODY_KIND:
        raise ProcedureRuntimeError("procedure_body_kind_unsupported")
    if spec.get("dependencies"):
        raise PermissionError("procedure_skill_dependencies_disabled")

    normalized_input = _normalize_json(input_value)
    _canonical_json(normalized_input)
    _validate_contract_value(normalized_input, spec["input_contract"], "input")

    budget = ExecutionBudget(
        max_steps=max(1, int(max_steps)),
        max_cost_units=max(1, int(max_cost_units)),
    )
    result = _eval_node(spec["body"], normalized_input, budget)
    _validate_contract_value(result, spec["output_contract"], "output")

    return {
        "skill_id": spec["skill_id"],
        "skill_version": spec["skill_version"],
        "task_family": spec["task_family"],
        "spec_sha256": spec["spec_sha256"],
        "body_sha256": spec["body_sha256"],
        "result": result,
        "steps": budget.steps,
        "cost_units": budget.cost_units,
        "deterministic": True,
        "network_used": False,
        "filesystem_used": False,
        "shell_used": False,
        "process_used": False,
        "clock_used": False,
        "randomness_used": False,
        "generated_skill_execution": False,
    }


def procedure_runtime_status() -> dict[str, Any]:
    return {
        "runtime_version": RUNTIME_VERSION,
        "body_kind": BODY_KIND,
        "interpreter_present": True,
        "execution_enabled": True,
        "developer_authored_execution": True,
        "generated_skill_execution": False,
        "external_teacher_execution": False,
        "dependencies_allowed": False,
        "deterministic": True,
        "logical_step_budget": MAX_STEPS,
        "logical_cost_budget": MAX_COST_UNITS,
        "network_allowed": False,
        "filesystem_allowed": False,
        "shell_allowed": False,
        "process_allowed": False,
        "environment_allowed": False,
        "clock_allowed": False,
        "randomness_allowed": False,
        "arbitrary_code_allowed": False,
        "model_weight_mutation": False,
        "os_process_isolation": False,
        "untrusted_generated_execution": False,
    }
