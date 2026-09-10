"""Persistent restricted Skill Registry for Jade Genesis 0.1.20.

0.1.20 proves the causal path:

    registered SkillSpec -> explicit activation -> exact family selection
    -> restricted execution -> deterministic result

Only DEVELOPER-authored SkillSpecs can be registered or activated in this
version. Generated/external-teacher candidates remain outside this registry
until the 0.1.21 synthesis, verifier and promotion boundary is implemented.

The registry performs no network access, no LLM calls, no automatic promotion
and no arbitrary code execution. Execution is delegated to procedure_runtime.
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
    """Identity-bound atomic store for explicitly approved developer skills."""

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

        skill_id = _clean_id(spec["skill_id"], "skill_id")
        skill_version = int(spec["skill_version"])
        key = _skill_key(skill_id, skill_version)
        now = _now_ms() if now_ms is None else max(0, int(now_ms))

        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            skills = state["skills"]
            existing = skills.get(key)
            if isinstance(existing, dict):
                if existing.get("spec_sha256") != spec["spec_sha256"]:
                    raise ValueError("skill_registry_version_conflict")
                return self._public_skill(existing)
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
                "spec": spec,
            }
            skills[key] = stored
            self._touch_and_save(state, now)
            return self._public_skill(stored)

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
                "activation_automatic": False,
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

        execution = execute_skill(spec, input_value)
        return {
            "selection_kind": "exact_task_family",
            "task_family": family,
            "selected_skill_id": stored["skill_id"],
            "selected_skill_version": stored["skill_version"],
            "selected_spec_sha256": stored["spec_sha256"],
            "execution": execution,
            "llm_used": False,
            "network_used": False,
            "selection_automatic_learning": False,
        }

    @staticmethod
    def _public_skill(stored: dict[str, Any]) -> dict[str, Any]:
        return {
            "skill_id": stored.get("skill_id", ""),
            "skill_version": max(1, int(stored.get("skill_version", 1))),
            "task_family": stored.get("task_family", ""),
            "spec_sha256": stored.get("spec_sha256", ""),
            "body_sha256": stored.get("body_sha256", ""),
            "source_kind": stored.get("source_kind", ""),
            "registered_at": max(0, int(stored.get("registered_at", 0))),
            "activated_at": max(0, int(stored.get("activated_at", 0))),
        }

    def status(self) -> dict[str, Any]:
        with self.lock:
            state = self._load()
            return {
                "schema_version": SCHEMA_VERSION,
                "identity_bound": bool(str(state.get("identity_id", "")).strip()),
                "revision": max(0, int(state.get("revision", 0))),
                "registered_skill_count": len(state.get("skills", {})),
                "active_family_count": len(state.get("active_by_family", {})),
                "developer_authored_only": True,
                "activation_requires_explicit_approval": True,
                "activation_automatic": False,
                "generated_skill_registration": False,
                "generated_skill_execution": False,
                "external_teacher_execution": False,
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
            "developer_authored_only": True,
            "activation_requires_explicit_approval": True,
            "activation_automatic": False,
            "generated_skill_registration": False,
            "generated_skill_execution": False,
            "external_teacher_execution": False,
            "exact_family_selection": True,
            "fuzzy_recall": False,
            "dependencies_allowed": False,
            "llm_used_for_selection": False,
            "network_access": False,
        }
