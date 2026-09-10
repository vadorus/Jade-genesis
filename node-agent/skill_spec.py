"""Strict SkillSpec contract for Jade Genesis 0.1.20.

0.1.20 introduces a restricted deterministic interpreter, but only for
explicitly developer-authored procedures. Generated/external-teacher skills
remain non-executable until the 0.1.21 synthesis and verifier isolation boundary
exists.

Skill-to-skill dependencies are intentionally disabled in 0.1.20. A procedure
must be a closed, pure AST over bounded JSON values. The AST has no shell,
filesystem, network, import, process, environment, clock, randomness, eval or
arbitrary Python capability.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
from typing import Any

SCHEMA_VERSION = 1
BODY_KIND = "JADE_PROCEDURE_DSL_V1"
VERIFIER_KIND = "exact_json_v1"
MAX_ID_CHARS = 160
MAX_DESCRIPTION_CHARS = 500
MAX_BODY_NODES = 256
MAX_BODY_DEPTH = 16
MAX_ARGS_PER_NODE = 32
MAX_DEPENDENCIES = 0
MAX_LITERAL_BYTES = 8_192
ALLOWED_CONTRACT_TYPES = {
    "any",
    "object",
    "array",
    "string",
    "number",
    "integer",
    "boolean",
    "null",
}
ALLOWED_SOURCE_KINDS = {
    "DEVELOPER",
    "EXTERNAL_TEACHER",
    "FUTURE_SYNTHESIS",
    "MIGRATED",
}
ALLOWED_OPS = {
    "input",
    "literal",
    "get",
    "object",
    "array",
    "concat",
    "lower",
    "upper",
    "trim",
    "replace",
    "split",
    "join",
    "length",
    "add",
    "subtract",
    "multiply",
    "divide",
    "modulo",
    "equals",
    "lt",
    "lte",
    "gt",
    "gte",
    "not",
    "and",
    "or",
    "if",
    "pipeline",
}
FORBIDDEN_CAPABILITY_WORDS = {
    "shell",
    "exec",
    "eval",
    "python",
    "subprocess",
    "process",
    "network",
    "socket",
    "http",
    "filesystem",
    "file_write",
    "file_read",
    "import",
    "privilege",
    "environment",
    "env",
    "clock",
    "time",
    "random",
    "randomness",
}
_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


def _clean_id(value: Any, name: str) -> str:
    text = " ".join(str(value or "").replace("\x00", " ").split())[:MAX_ID_CHARS]
    if not text:
        raise ValueError(f"missing_{name}")
    return text


def _clean_description(value: Any) -> str:
    return " ".join(str(value or "").replace("\x00", " ").split())[:MAX_DESCRIPTION_CHARS]


def _normalize_literal(value: Any, depth: int = 0) -> Any:
    if depth > MAX_BODY_DEPTH:
        raise ValueError("skill_literal_too_deep")
    if value is None or isinstance(value, bool) or isinstance(value, int):
        return value
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValueError("skill_literal_non_finite")
        return value
    if isinstance(value, str):
        if len(value) > MAX_DESCRIPTION_CHARS:
            raise ValueError("skill_literal_string_too_large")
        return value
    if isinstance(value, list):
        if len(value) > MAX_ARGS_PER_NODE:
            raise ValueError("skill_literal_collection_too_large")
        return [_normalize_literal(item, depth + 1) for item in value]
    if isinstance(value, dict):
        if len(value) > MAX_ARGS_PER_NODE:
            raise ValueError("skill_literal_collection_too_large")
        result: dict[str, Any] = {}
        for key, item in value.items():
            if not isinstance(key, str) or not key.strip():
                raise ValueError("skill_literal_invalid_key")
            lowered = key.strip().lower()
            if lowered in FORBIDDEN_CAPABILITY_WORDS:
                raise ValueError("skill_forbidden_capability")
            result[key.strip()] = _normalize_literal(item, depth + 1)
        return result
    raise ValueError("skill_literal_unsupported_type")


def _canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def _validate_contract(raw: Any, name: str) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ValueError(f"invalid_{name}_contract")
    allowed_keys = {"type", "required_fields", "description"}
    if any(key not in allowed_keys for key in raw):
        raise ValueError(f"unsupported_{name}_contract_field")
    contract_type = str(raw.get("type", "any")).strip().lower()
    if contract_type not in ALLOWED_CONTRACT_TYPES:
        raise ValueError(f"unsupported_{name}_contract_type")
    required_raw = raw.get("required_fields", [])
    if not isinstance(required_raw, list) or len(required_raw) > 64:
        raise ValueError(f"invalid_{name}_required_fields")
    required_fields: list[str] = []
    for item in required_raw:
        field = _clean_id(item, f"{name}_required_field")
        if field not in required_fields:
            required_fields.append(field)
    return {
        "type": contract_type,
        "required_fields": required_fields,
        "description": _clean_description(raw.get("description", "")),
    }


def _validate_node(raw: Any, depth: int, counter: list[int]) -> dict[str, Any]:
    if depth > MAX_BODY_DEPTH:
        raise ValueError("skill_body_too_deep")
    if not isinstance(raw, dict):
        raise ValueError("skill_body_node_must_be_object")
    if set(raw.keys()) != {"op", "args"}:
        raise ValueError("skill_body_node_fields_invalid")
    op = str(raw.get("op", "")).strip().lower()
    if op not in ALLOWED_OPS:
        raise ValueError("skill_body_op_not_allowed")
    if op in FORBIDDEN_CAPABILITY_WORDS:
        raise ValueError("skill_forbidden_capability")
    args = raw.get("args")
    if not isinstance(args, list) or len(args) > MAX_ARGS_PER_NODE:
        raise ValueError("skill_body_args_invalid")

    counter[0] += 1
    if counter[0] > MAX_BODY_NODES:
        raise ValueError("skill_body_too_many_nodes")

    if op == "literal":
        if len(args) != 1:
            raise ValueError("skill_literal_requires_one_argument")
        literal = _normalize_literal(args[0])
        if len(_canonical(literal).encode("utf-8")) > MAX_LITERAL_BYTES:
            raise ValueError("skill_literal_too_large")
        return {"op": op, "args": [literal]}

    if op == "input":
        if args:
            raise ValueError("skill_input_takes_no_arguments")
        return {"op": op, "args": []}

    normalized_args = [_validate_node(item, depth + 1, counter) for item in args]
    return {"op": op, "args": normalized_args}


def _validate_provenance(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ValueError("invalid_skill_provenance")
    allowed = {"source_kind", "source_id", "source_model", "created_at"}
    if any(key not in allowed for key in raw):
        raise ValueError("unsupported_skill_provenance_field")
    source_kind = str(raw.get("source_kind", "")).strip().upper()
    if source_kind not in ALLOWED_SOURCE_KINDS:
        raise ValueError("unsupported_skill_source_kind")
    created_at = raw.get("created_at", 0)
    try:
        created_at_int = max(0, int(created_at))
    except (TypeError, ValueError):
        raise ValueError("invalid_skill_created_at") from None
    return {
        "source_kind": source_kind,
        "source_id": _clean_id(raw.get("source_id"), "source_id"),
        "source_model": _clean_description(raw.get("source_model", "")),
        "created_at": created_at_int,
    }


def _validate_evaluation_policy(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ValueError("invalid_skill_evaluation_policy")
    allowed = {
        "verifier_kind",
        "sealed_set_sha256",
        "min_pass_rate",
        "max_protected_failures",
    }
    if any(key not in allowed for key in raw):
        raise ValueError("unsupported_skill_evaluation_policy_field")
    verifier = str(raw.get("verifier_kind", VERIFIER_KIND)).strip()
    if verifier != VERIFIER_KIND:
        raise ValueError("unsupported_skill_verifier")
    sealed_hash = str(raw.get("sealed_set_sha256", "")).strip().lower()
    if sealed_hash and not _SHA256_RE.fullmatch(sealed_hash):
        raise ValueError("invalid_sealed_set_sha256")
    try:
        pass_rate = float(raw.get("min_pass_rate", 0.9))
    except (TypeError, ValueError):
        raise ValueError("invalid_min_pass_rate") from None
    if not math.isfinite(pass_rate) or not 0.0 <= pass_rate <= 1.0:
        raise ValueError("invalid_min_pass_rate")
    try:
        protected = int(raw.get("max_protected_failures", 0))
    except (TypeError, ValueError):
        raise ValueError("invalid_max_protected_failures") from None
    if protected < 0:
        raise ValueError("invalid_max_protected_failures")
    return {
        "verifier_kind": VERIFIER_KIND,
        "sealed_set_sha256": sealed_hash,
        "min_pass_rate": pass_rate,
        "max_protected_failures": protected,
    }


def normalize_skill_spec(raw: Any) -> dict[str, Any]:
    """Validate and canonicalize one closed SkillSpec payload."""
    if not isinstance(raw, dict):
        raise ValueError("skill_spec_must_be_object")
    allowed_fields = {
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
    unknown = set(raw.keys()) - allowed_fields
    if unknown:
        raise ValueError("unsupported_skill_spec_field")
    if int(raw.get("schema_version", SCHEMA_VERSION)) != SCHEMA_VERSION:
        raise ValueError("unsupported_skill_spec_schema")
    try:
        skill_version = int(raw.get("skill_version", 1))
    except (TypeError, ValueError):
        raise ValueError("invalid_skill_version") from None
    if skill_version < 1:
        raise ValueError("invalid_skill_version")

    body_kind = str(raw.get("body_kind", "")).strip().upper()
    if body_kind != BODY_KIND:
        raise ValueError("unsupported_skill_body_kind")
    counter = [0]
    body = _validate_node(raw.get("body"), 0, counter)

    dependencies_raw = raw.get("dependencies", [])
    if not isinstance(dependencies_raw, list):
        raise ValueError("invalid_skill_dependencies")
    if dependencies_raw:
        raise PermissionError("skill_dependencies_disabled_in_0_1_20")
    dependencies: list[str] = []

    spec = {
        "schema_version": SCHEMA_VERSION,
        "skill_id": _clean_id(raw.get("skill_id"), "skill_id"),
        "skill_version": skill_version,
        "task_family": _clean_id(raw.get("task_family"), "task_family"),
        "domain": _clean_id(raw.get("domain", "verifiable_procedure"), "domain"),
        "description": _clean_description(raw.get("description", "")),
        "input_contract": _validate_contract(
            raw.get("input_contract", {"type": "any"}),
            "input",
        ),
        "output_contract": _validate_contract(
            raw.get("output_contract", {"type": "any"}),
            "output",
        ),
        "body_kind": BODY_KIND,
        "body": body,
        "dependencies": dependencies,
        "provenance": _validate_provenance(raw.get("provenance")),
        "evaluation_policy": _validate_evaluation_policy(raw.get("evaluation_policy", {})),
        "execution_enabled": False,
        "network_allowed": False,
        "filesystem_allowed": False,
        "shell_allowed": False,
        "arbitrary_code_allowed": False,
        "model_weight_mutation": False,
    }
    spec["body_sha256"] = hashlib.sha256(_canonical(body).encode("utf-8")).hexdigest()
    digest_payload = dict(spec)
    spec["spec_sha256"] = hashlib.sha256(_canonical(digest_payload).encode("utf-8")).hexdigest()
    return spec


def skill_spec_status() -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "body_kind": BODY_KIND,
        "verifier_kind": VERIFIER_KIND,
        "allowed_op_count": len(ALLOWED_OPS),
        "execution_enabled": False,
        "interpreter_present": False,
        "dependencies_allowed": False,
        "max_dependencies": MAX_DEPENDENCIES,
        "network_allowed": False,
        "filesystem_allowed": False,
        "shell_allowed": False,
        "arbitrary_code_allowed": False,
        "model_weight_mutation": False,
        "generated_skill_execution": False,
    }
