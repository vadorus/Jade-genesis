"""Persistent restricted Skill Registry for Jade Genesis 0.1.21.

Developer-authored skills keep the explicit approval path introduced in 0.1.20.
0.1.21 adds one second path for learned procedures: an EXTERNAL_TEACHER or
FUTURE_SYNTHESIS SkillSpec can enter the registry only after the Verifiable Task
Ledger proves that the exact frozen spec hash passed its one-shot SEALED_TEST.

Verified learned skills may then become the active exact-family procedure and be
reused locally after restart without another LLM call. The registry itself does
no network access, no LLM calls, no shell execution and no arbitrary code.
Execution is always delegated to procedure_runtime.
"""

from __future__ import annotations

import json
import os
import threading
import time
from pathlib import Path
from typing import Any

from procedure_runtime import execute_skill
from skill_spec import normalize_skill_spec

SCHEMA_VERSION = 1
MAX_SKILLS = 256
MAX_ID_CHARS = 160
LEARNED_SOURCE_KINDS = frozenset({"EXTERNAL_TEACHER", "FUTURE_SYNTHESIS"})
CONFIG_DIR = Path(
    os.environ.get("JADE_GENESIS_CONFIG_DIR", str(Path.home() / ".jade-genesis"))
)
SKILL_REGISTRY_PATH = CONFIG_DIR / "skill-registry.json"


def _now_ms() -> int:
    return int(time.time() * 1_000)


def _clean_id(value: Any, name: str) -> str:
    text = " ".join(str(value or "").replace("\x00", " ").split())[:MAX_ID_CHARS]
    if not text:
        raise ValueError(f"missing_{name}")
    return text


def _skill_key(skill_id: str, skill_version: int) -> str:
    return f"{skill_id}@{skill_version}"


class SkillRegistry:
    """Identity-bound atomic store for approved or verifier-proven skills."""

    def __init__(self, path: Path = SKILL_REGISTRY_PATH):
        self.path = path
        self.backup_path = path.with_suffix(path.suffix + ".bak")
        self.lock = threading.RLock()

    @staticmethod
    def _empty() -> dict[str, Any]:
        return {
            "schema_version": SCHEMA_VERSION,
            "identity_id": "",
            "revision": 0,
            "skills": {},
            "active_by_family": {},
            "updated_at": 0,
        }

    def _decode(self, path: Path) -> dict[str, Any] | None:
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return None
        if not isinstance(raw, dict) or raw.get("schema_version") != SCHEMA_VERSION:
            return None
        if not isinstance(raw.get("skills", {}), dict):
            return None
        if not isinstance(raw.get("active_by_family", {}), dict):
            return None
        return raw

    def _load(self) -> dict[str, Any]:
        if not self.path.exists():
            return self._empty()
        primary = self._decode(self.path)
        if primary is not None:
            return primary
        backup = self._decode(self.backup_path)
        if backup is not None:
            try:
                self.path.write_bytes(self.backup_path.read_bytes())
            except OSError:
                pass
            return backup
        raise RuntimeError("skill_registry_corrupt")

    def _save(self, state: dict[str, Any]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        encoded = json.dumps(
            state,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        temp = self.path.with_suffix(self.path.suffix + ".tmp")
        temp.write_text(encoded, encoding="utf-8")
        if self.path.exists() and self._decode(self.path) is not None:
            try:
                self.backup_path.write_bytes(self.path.read_bytes())
            except OSError:
                pass
        temp.replace(self.path)

    @staticmethod
    def _bind_identity(state: dict[str, Any], identity_id: str) -> None:
        requested = _clean_id(identity_id, "identity_id")
        bound = str(state.get("identity_id", "")).strip()[:MAX_ID_CHARS]
        if bound and bound != requested:
            raise ValueError("skill_registry_identity_mismatch")
        if not bound:
            state["identity_id"] = requested

    def _touch_and_save(self, state: dict[str, Any], now: int) -> None:
        state["revision"] = max(0, int(state.get("revision", 0))) + 1
        state["updated_at"] = now
        self._save(state)

    def _store(
        self,
        state: dict[str, Any],
        spec: dict[str, Any],
        source_kind: str,
        now: int,
        *,
        verification: dict[str, Any] | None,
    ) -> tuple[dict[str, Any], bool]:
        skill_id = _clean_id(spec["skill_id"], "skill_id")
        skill_version = int(spec["skill_version"])
        key = _skill_key(skill_id, skill_version)
        skills = state["skills"]
        existing = skills.get(key)
        if isinstance(existing, dict):
            if existing.get("spec_sha256") != spec["spec_sha256"]:
                raise ValueError("skill_registry_version_conflict")
            return existing, False
        if len(skills) >= MAX_SKILLS:
            raise ValueError("skill_registry_limit_reached")
        stored = {
            "skill_id": skill_id,
            "skill_version": skill_version,
            "task_family": spec["task_family"],
            "spec_sha256": spec["spec_sha256"],
            "body_sha256": spec["body_sha256"],
            "source_kind": source_kind,
            "registered_at": now,
            "activated_at": 0,
            "verification": verification,
            "spec": spec,
        }
        skills[key] = stored
        return stored, True

    def register_developer_skill(
        self,
        identity_id: str,
        raw_spec: dict[str, Any],
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        """Persist one closed DEVELOPER SkillSpec; never auto-activate it."""

        spec = normalize_skill_spec(raw_spec)
        source_kind = str(spec["provenance"]["source_kind"]).upper()
        if source_kind != "DEVELOPER":
            raise PermissionError("skill_registry_developer_source_required")
        if spec.get("dependencies"):
            raise PermissionError("skill_registry_dependencies_disabled")
        now = _now_ms() if now_ms is None else max(0, int(now_ms))

        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            stored, created = self._store(
                state,
                spec,
                source_kind,
                now,
                verification=None,
            )
            if created:
                self._touch_and_save(state, now)
            return self._public_skill(stored)

    def register_verified_skill(
        self,
        identity_id: str,
        raw_spec: dict[str, Any],
        *,
        ledger: Any,
        dataset_id: str,
        activate: bool,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        """Retain a learned SkillSpec only after the exact frozen hash passed."""

        spec = normalize_skill_spec(raw_spec)
        source_kind = str(spec["provenance"]["source_kind"]).upper()
        if source_kind not in LEARNED_SOURCE_KINDS:
            raise PermissionError("skill_registry_learned_source_required")
        if spec.get("dependencies"):
            raise PermissionError("skill_registry_dependencies_disabled")
        dataset_id = _clean_id(dataset_id, "dataset_id")
        proof = ledger.verified_exam_for_candidate(
            identity_id,
            dataset_id,
            spec["spec_sha256"],
        )
        if proof.get("verdict") is not True:
            raise PermissionError("skill_registry_verified_exam_required")
        if str(proof.get("task_family", "")) != str(spec["task_family"]):
            raise ValueError("skill_registry_verified_family_mismatch")
        policy_hash = str(spec["evaluation_policy"].get("sealed_set_sha256", ""))
        if not policy_hash or policy_hash != str(proof.get("sealed_set_sha256", "")):
            raise ValueError("skill_registry_verified_sealed_set_mismatch")

        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        verification = {
            "verified": True,
            "dataset_id": dataset_id,
            "exam_id": proof.get("exam_id", ""),
            "candidate_spec_sha256": spec["spec_sha256"],
            "sealed_set_sha256": proof.get("sealed_set_sha256", ""),
            "verified_at": max(0, int(proof.get("evaluated_at", 0))),
        }
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            stored, created = self._store(
                state,
                spec,
                source_kind,
                now,
                verification=verification,
            )
            changed = created
            if activate:
                family = _clean_id(stored.get("task_family"), "task_family")
                key = _skill_key(stored["skill_id"], stored["skill_version"])
                if state["active_by_family"].get(family) != key:
                    state["active_by_family"][family] = key
                    changed = True
                if int(stored.get("activated_at", 0)) <= 0:
                    stored["activated_at"] = now
                    changed = True
            if changed:
                self._touch_and_save(state, now)
            result = self._public_skill(stored)
            result["active"] = bool(activate)
            result["activation_kind"] = "verified_learning_gate" if activate else "none"
            return result

    def activate_developer_skill(
        self,
        identity_id: str,
        skill_id: str,
        skill_version: int,
        *,
        approved: bool,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        """Explicitly select one developer skill for its exact task family."""

        if approved is not True:
            raise PermissionError("skill_registry_activation_requires_approval")
        skill_id = _clean_id(skill_id, "skill_id")
        try:
            version = int(skill_version)
        except (TypeError, ValueError):
            raise ValueError("invalid_skill_version") from None
        if version < 1:
            raise ValueError("invalid_skill_version")
        key = _skill_key(skill_id, version)
        now = _now_ms() if now_ms is None else max(0, int(now_ms))

        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            stored = state["skills"].get(key)
            if not isinstance(stored, dict):
                raise ValueError("skill_registry_skill_not_found")
            if str(stored.get("source_kind", "")).upper() != "DEVELOPER":
                raise PermissionError("skill_registry_developer_source_required")
            family = _clean_id(stored.get("task_family"), "task_family")
            state["active_by_family"][family] = key
            stored["activated_at"] = now
            self._touch_and_save(state, now)
            return {
                **self._public_skill(stored),
                "active": True,
                "activation_kind": "explicit_developer_approval",
            }

    def deactivate_family(
        self,
        identity_id: str,
        task_family: str,
        *,
        approved: bool,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        if approved is not True:
            raise PermissionError("skill_registry_deactivation_requires_approval")
        family = _clean_id(task_family, "task_family")
        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            previous = state["active_by_family"].pop(family, None)
            if previous is not None:
                self._touch_and_save(state, now)
            return {
                "task_family": family,
                "deactivated": previous is not None,
                "activation_automatic": False,
            }

    def selected_skill(
        self,
        identity_id: str,
        task_family: str,
    ) -> dict[str, Any] | None:
        family = _clean_id(task_family, "task_family")
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            key = state["active_by_family"].get(family)
            if not isinstance(key, str) or not key:
                return None
            stored = state["skills"].get(key)
            if not isinstance(stored, dict):
                raise RuntimeError("skill_registry_active_reference_missing")
            return self._public_skill(stored)

    @staticmethod
    def _learned_verification_valid(stored: dict[str, Any]) -> bool:
        verification = stored.get("verification")
        if not isinstance(verification, dict) or verification.get("verified") is not True:
            return False
        spec_sha = str(stored.get("spec_sha256", ""))
        return (
            str(verification.get("candidate_spec_sha256", "")) == spec_sha
            and bool(str(verification.get("exam_id", "")))
            and bool(str(verification.get("sealed_set_sha256", "")))
        )

    def execute_for_family(
        self,
        identity_id: str,
        task_family: str,
        input_value: Any,
    ) -> dict[str, Any]:
        """Select the active exact-family skill and execute it locally."""

        family = _clean_id(task_family, "task_family")
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            key = state["active_by_family"].get(family)
            if not isinstance(key, str) or not key:
                raise LookupError("skill_registry_no_active_skill_for_family")
            stored = state["skills"].get(key)
            if not isinstance(stored, dict):
                raise RuntimeError("skill_registry_active_reference_missing")
            spec = stored.get("spec")
            if not isinstance(spec, dict):
                raise RuntimeError("skill_registry_spec_missing")
            if spec.get("spec_sha256") != stored.get("spec_sha256"):
                raise RuntimeError("skill_registry_spec_hash_mismatch")
            if str(spec.get("task_family", "")) != family:
                raise RuntimeError("skill_registry_family_mismatch")
            source_kind = str(stored.get("source_kind", "")).upper()
            if source_kind == "DEVELOPER":
                pass
            elif source_kind in LEARNED_SOURCE_KINDS:
                if not self._learned_verification_valid(stored):
                    raise PermissionError("skill_registry_learned_verification_missing")
            else:
                raise PermissionError("skill_registry_source_not_executable")

        execution = execute_skill(
            spec,
            input_value,
            allowed_source_kinds={source_kind},
        )
        return {
            "selection_kind": "exact_task_family",
            "task_family": family,
            "selected_skill_id": stored["skill_id"],
            "selected_skill_version": stored["skill_version"],
            "selected_spec_sha256": stored["spec_sha256"],
            "selected_source_kind": source_kind,
            "verified_learned_skill": source_kind in LEARNED_SOURCE_KINDS,
            "execution": execution,
            "llm_used": False,
            "network_used": False,
            "selection_automatic_learning": False,
        }

    @staticmethod
    def _public_skill(stored: dict[str, Any]) -> dict[str, Any]:
        verification = stored.get("verification")
        verified = isinstance(verification, dict) and verification.get("verified") is True
        return {
            "skill_id": stored.get("skill_id", ""),
            "skill_version": max(1, int(stored.get("skill_version", 1))),
            "task_family": stored.get("task_family", ""),
            "spec_sha256": stored.get("spec_sha256", ""),
            "body_sha256": stored.get("body_sha256", ""),
            "source_kind": stored.get("source_kind", ""),
            "registered_at": max(0, int(stored.get("registered_at", 0))),
            "activated_at": max(0, int(stored.get("activated_at", 0))),
            "sealed_verified": bool(verified),
            "verification_exam_id": verification.get("exam_id", "") if verified else "",
            "verification_dataset_id": verification.get("dataset_id", "") if verified else "",
        }

    def status(self) -> dict[str, Any]:
        with self.lock:
            state = self._load()
            skills = [item for item in state.get("skills", {}).values() if isinstance(item, dict)]
            learned = [
                item
                for item in skills
                if str(item.get("source_kind", "")).upper() in LEARNED_SOURCE_KINDS
            ]
            verified_learned = [item for item in learned if self._learned_verification_valid(item)]
            return {
                "schema_version": SCHEMA_VERSION,
                "identity_bound": bool(str(state.get("identity_id", "")).strip()),
                "revision": max(0, int(state.get("revision", 0))),
                "registered_skill_count": len(skills),
                "verified_learned_skill_count": len(verified_learned),
                "active_family_count": len(state.get("active_by_family", {})),
                "developer_authored_only": False,
                "activation_requires_explicit_approval": True,
                "developer_activation_requires_explicit_approval": True,
                "activation_automatic": True,
                "automatic_activation_scope": "sealed_verified_learning_only",
                "generated_skill_registration": True,
                "generated_skill_registration_gate": "passed_sealed_exam_exact_hash",
                "generated_skill_execution": True,
                "generated_skill_execution_gate": "verified_registry_only",
                "external_teacher_execution": True,
                "external_teacher_execution_gate": "visible_eval_or_verified_registry_only",
                "exact_family_selection": True,
                "fuzzy_recall": False,
                "dependencies_allowed": False,
                "llm_used_for_selection": False,
                "network_access": False,
                "updated_at": max(0, int(state.get("updated_at", 0))),
            }


_GLOBAL_SKILL_REGISTRY = SkillRegistry()


def skill_registry_status() -> dict[str, Any]:
    try:
        return _GLOBAL_SKILL_REGISTRY.status()
    except RuntimeError:
        return {
            "schema_version": SCHEMA_VERSION,
            "healthy": False,
            "error": "skill_registry_corrupt",
            "developer_authored_only": False,
            "activation_requires_explicit_approval": True,
            "developer_activation_requires_explicit_approval": True,
            "activation_automatic": True,
            "automatic_activation_scope": "sealed_verified_learning_only",
            "generated_skill_registration": True,
            "generated_skill_registration_gate": "passed_sealed_exam_exact_hash",
            "generated_skill_execution": True,
            "generated_skill_execution_gate": "verified_registry_only",
            "external_teacher_execution": True,
            "external_teacher_execution_gate": "visible_eval_or_verified_registry_only",
            "exact_family_selection": True,
            "fuzzy_recall": False,
            "dependencies_allowed": False,
            "llm_used_for_selection": False,
            "network_access": False,
        }
