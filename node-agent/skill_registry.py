"""Archived Skill Registry + separate production route store for 0.1.21.

The archive retains immutable SkillSpec versions and verification provenance.
Production contains only the minimal exact-family route selecting which archived
skill may serve the user. Workshop code cannot write a production route except
through an explicit developer approval or a passed sealed-learning gate.

Learned skills (EXTERNAL_TEACHER/FUTURE_SYNTHESIS) enter the archive only when
the Verifiable Task Ledger proves the exact frozen spec hash passed its one-shot
SEALED_TEST. Reuse after restart never calls an LLM.
"""

from __future__ import annotations

import json
import shutil
import threading
import time
from pathlib import Path
from typing import Any

from learning_environment import (
    ARCHIVE_ATTRIBUTION_LOG_PATH,
    ARCHIVE_SKILL_REGISTRY_PATH,
    CONFIG_DIR,
    PRODUCTION_SKILL_ROUTES_PATH,
    append_attribution_event,
    attribution_summary,
    ensure_learning_environment,
)
from procedure_runtime import execute_skill
from skill_spec import normalize_skill_spec

SCHEMA_VERSION = 1
ROUTE_SCHEMA_VERSION = 1
MAX_SKILLS = 256
MAX_ID_CHARS = 160
LEARNED_SOURCE_KINDS = frozenset({"EXTERNAL_TEACHER", "FUTURE_SYNTHESIS"})
SKILL_REGISTRY_PATH = ARCHIVE_SKILL_REGISTRY_PATH
LEGACY_SKILL_REGISTRY_PATH = CONFIG_DIR / "skill-registry.json"


def _now_ms() -> int:
    return int(time.time() * 1_000)


def _clean_id(value: Any, name: str) -> str:
    text = " ".join(str(value or "").replace("\x00", " ").split())[:MAX_ID_CHARS]
    if not text:
        raise ValueError(f"missing_{name}")
    return text


def _skill_key(skill_id: str, skill_version: int) -> str:
    return f"{skill_id}@{skill_version}"


class ProductionSkillRouteStore:
    """Minimal production state: exact task family -> archived skill reference."""

    def __init__(self, path: Path = PRODUCTION_SKILL_ROUTES_PATH):
        self.path = path
        self.backup_path = path.with_suffix(path.suffix + ".bak")
        self.lock = threading.RLock()

    @staticmethod
    def _empty() -> dict[str, Any]:
        return {
            "schema_version": ROUTE_SCHEMA_VERSION,
            "identity_id": "",
            "revision": 0,
            "active_by_family": {},
            "updated_at": 0,
        }

    def _decode(self, path: Path) -> dict[str, Any] | None:
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return None
        if not isinstance(raw, dict) or raw.get("schema_version") != ROUTE_SCHEMA_VERSION:
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
        raise RuntimeError("production_skill_routes_corrupt")

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
            raise ValueError("production_skill_routes_identity_mismatch")
        if not bound:
            state["identity_id"] = requested

    def set_route(
        self,
        identity_id: str,
        task_family: str,
        skill_key: str,
        spec_sha256: str,
        *,
        activation_kind: str,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        family = _clean_id(task_family, "task_family")
        key = _clean_id(skill_key, "skill_key")
        spec_hash = str(spec_sha256 or "").strip().lower()
        if len(spec_hash) != 64:
            raise ValueError("production_route_invalid_spec_sha256")
        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            state["active_by_family"][family] = {
                "skill_key": key,
                "spec_sha256": spec_hash,
                "activation_kind": _clean_id(activation_kind, "activation_kind"),
                "activated_at": now,
            }
            state["revision"] = max(0, int(state.get("revision", 0))) + 1
            state["updated_at"] = now
            self._save(state)
            return dict(state["active_by_family"][family])

    def remove_route(
        self,
        identity_id: str,
        task_family: str,
        *,
        now_ms: int | None = None,
    ) -> bool:
        family = _clean_id(task_family, "task_family")
        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            removed = state["active_by_family"].pop(family, None) is not None
            if removed:
                state["revision"] = max(0, int(state.get("revision", 0))) + 1
                state["updated_at"] = now
                self._save(state)
            return removed

    def selected_route(self, identity_id: str, task_family: str) -> dict[str, Any] | None:
        family = _clean_id(task_family, "task_family")
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            route = state["active_by_family"].get(family)
            return dict(route) if isinstance(route, dict) else None

    def migrate_legacy(
        self,
        identity_id: str,
        legacy_routes: dict[str, Any],
        skills: dict[str, Any],
        *,
        now_ms: int | None = None,
    ) -> int:
        """One-way migration of the old archive-embedded active_by_family map."""

        if not legacy_routes:
            return 0
        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        migrated = 0
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            for family, old_key in legacy_routes.items():
                if family in state["active_by_family"]:
                    continue
                if not isinstance(old_key, str):
                    continue
                stored = skills.get(old_key)
                if not isinstance(stored, dict):
                    continue
                spec_hash = str(stored.get("spec_sha256", "")).lower()
                if len(spec_hash) != 64:
                    continue
                state["active_by_family"][str(family)] = {
                    "skill_key": old_key,
                    "spec_sha256": spec_hash,
                    "activation_kind": "legacy_0.1.20_migration",
                    "activated_at": max(0, int(stored.get("activated_at", now))),
                }
                migrated += 1
            if migrated:
                state["revision"] = max(0, int(state.get("revision", 0))) + 1
                state["updated_at"] = now
                self._save(state)
        return migrated

    def status(self) -> dict[str, Any]:
        with self.lock:
            state = self._load()
            return {
                "schema_version": ROUTE_SCHEMA_VERSION,
                "identity_bound": bool(str(state.get("identity_id", "")).strip()),
                "revision": max(0, int(state.get("revision", 0))),
                "active_family_count": len(state.get("active_by_family", {})),
                "production_state_contains_skill_bodies": False,
                "production_state_contains_sealed_answers": False,
                "updated_at": max(0, int(state.get("updated_at", 0))),
            }


class SkillRegistry:
    """Archived identity-bound store for approved or verifier-proven skills."""

    def __init__(
        self,
        path: Path = SKILL_REGISTRY_PATH,
        *,
        route_path: Path | None = None,
        attribution_path: Path | None = None,
    ):
        ensure_learning_environment()
        self.path = path
        self.backup_path = path.with_suffix(path.suffix + ".bak")
        if route_path is None:
            route_path = (
                PRODUCTION_SKILL_ROUTES_PATH
                if path == SKILL_REGISTRY_PATH
                else path.with_name(f"{path.stem}-routes.json")
            )
        self.routes = ProductionSkillRouteStore(route_path)
        self.attribution_path = (
            ARCHIVE_ATTRIBUTION_LOG_PATH
            if attribution_path is None and path == SKILL_REGISTRY_PATH
            else attribution_path or path.with_name(f"{path.stem}-attribution.jsonl")
        )
        self.lock = threading.RLock()
        self._migrate_default_legacy_file_if_needed()

    @staticmethod
    def _empty() -> dict[str, Any]:
        return {
            "schema_version": SCHEMA_VERSION,
            "identity_id": "",
            "revision": 0,
            "skills": {},
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
        if "active_by_family" in raw and not isinstance(raw.get("active_by_family"), dict):
            return None
        return raw

    def _migrate_default_legacy_file_if_needed(self) -> None:
        if self.path != SKILL_REGISTRY_PATH or self.path.exists() or not LEGACY_SKILL_REGISTRY_PATH.exists():
            return
        legacy = self._decode(LEGACY_SKILL_REGISTRY_PATH)
        if legacy is None:
            raise RuntimeError("legacy_skill_registry_corrupt")
        self.path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(LEGACY_SKILL_REGISTRY_PATH, self.path)
        identity = str(legacy.get("identity_id", "")).strip()
        if identity:
            self.routes.migrate_legacy(
                identity,
                legacy.get("active_by_family", {}),
                legacy.get("skills", {}),
                now_ms=max(0, int(legacy.get("updated_at", 0))),
            )
        cleaned = dict(legacy)
        cleaned.pop("active_by_family", None)
        for stored in cleaned.get("skills", {}).values():
            if isinstance(stored, dict):
                stored.pop("activated_at", None)
        self._save(cleaned)

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
        persisted = dict(state)
        persisted.pop("active_by_family", None)
        encoded = json.dumps(
            persisted,
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

    def _migrate_embedded_routes(self, state: dict[str, Any], identity_id: str) -> None:
        legacy_routes = state.get("active_by_family")
        if isinstance(legacy_routes, dict) and legacy_routes:
            self.routes.migrate_legacy(identity_id, legacy_routes, state.get("skills", {}))
            state.pop("active_by_family", None)
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
            self._migrate_embedded_routes(state, identity_id)
            stored, created = self._store(state, spec, source_kind, now, verification=None)
            if created:
                self._touch_and_save(state, now)
            return self._public_skill(stored, active=False)

    def register_verified_skill(
        self,
        identity_id: str,
        raw_spec: dict[str, Any],
        *,
        ledger: Any,
        dataset_id: str,
        activate: bool,
        measured_gain: dict[str, Any] | None = None,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        spec = normalize_skill_spec(raw_spec)
        source_kind = str(spec["provenance"]["source_kind"]).upper()
        if source_kind not in LEARNED_SOURCE_KINDS:
            raise PermissionError("skill_registry_learned_source_required")
        if spec.get("dependencies"):
            raise PermissionError("skill_registry_dependencies_disabled")
        dataset_id = _clean_id(dataset_id, "dataset_id")
        proof = ledger.verified_exam_for_candidate(identity_id, dataset_id, spec["spec_sha256"])
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
            self._migrate_embedded_routes(state, identity_id)
            stored, created = self._store(state, spec, source_kind, now, verification=verification)
            if created:
                self._touch_and_save(state, now)
                append_attribution_event(
                    "skill_retained",
                    {
                        "identity_id": _clean_id(identity_id, "identity_id"),
                        "skill_id": stored["skill_id"],
                        "skill_version": stored["skill_version"],
                        "task_family": stored["task_family"],
                        "spec_sha256": stored["spec_sha256"],
                        "provenance": spec["provenance"],
                        "verification": verification,
                        "measured_gain": measured_gain or {
                            "metric": "capability_available_after_sealed_exam",
                            "before": 0.0,
                            "after": 1.0,
                            "delta": 1.0,
                        },
                    },
                    path=self.attribution_path,
                    now_ms=now,
                )

            active = False
            if activate:
                family = _clean_id(stored.get("task_family"), "task_family")
                key = _skill_key(stored["skill_id"], stored["skill_version"])
                self.routes.set_route(
                    identity_id,
                    family,
                    key,
                    stored["spec_sha256"],
                    activation_kind="verified_learning_gate",
                    now_ms=now,
                )
                active = True
            result = self._public_skill(stored, active=active)
            result["activation_kind"] = "verified_learning_gate" if active else "none"
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
            self._migrate_embedded_routes(state, identity_id)
            stored = state["skills"].get(key)
            if not isinstance(stored, dict):
                raise ValueError("skill_registry_skill_not_found")
            if str(stored.get("source_kind", "")).upper() != "DEVELOPER":
                raise PermissionError("skill_registry_developer_source_required")
            family = _clean_id(stored.get("task_family"), "task_family")
            self.routes.set_route(
                identity_id,
                family,
                key,
                stored["spec_sha256"],
                activation_kind="explicit_developer_approval",
                now_ms=now,
            )
            return {
                **self._public_skill(stored, active=True),
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
        removed = self.routes.remove_route(identity_id, family, now_ms=now_ms)
        return {
            "task_family": family,
            "deactivated": removed,
            "activation_automatic": False,
        }

    def _route_and_skill(
        self,
        identity_id: str,
        task_family: str,
    ) -> tuple[dict[str, Any] | None, dict[str, Any] | None]:
        family = _clean_id(task_family, "task_family")
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            self._migrate_embedded_routes(state, identity_id)
            route = self.routes.selected_route(identity_id, family)
            if route is None:
                return None, None
            key = str(route.get("skill_key", ""))
            stored = state["skills"].get(key)
            if not isinstance(stored, dict):
                raise RuntimeError("skill_registry_active_reference_missing")
            if str(route.get("spec_sha256", "")) != str(stored.get("spec_sha256", "")):
                raise RuntimeError("production_route_spec_hash_mismatch")
            return route, stored

    def selected_skill(self, identity_id: str, task_family: str) -> dict[str, Any] | None:
        route, stored = self._route_and_skill(identity_id, task_family)
        if route is None or stored is None:
            return None
        result = self._public_skill(stored, active=True)
        result["activation_kind"] = route.get("activation_kind", "")
        result["activated_at"] = max(0, int(route.get("activated_at", 0)))
        return result

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
        family = _clean_id(task_family, "task_family")
        route, stored = self._route_and_skill(identity_id, family)
        if route is None or stored is None:
            raise LookupError("skill_registry_no_active_skill_for_family")
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

        execution = execute_skill(spec, input_value, allowed_source_kinds={source_kind})
        attribution_recorded = True
        try:
            append_attribution_event(
                "skill_used",
                {
                    "identity_id": _clean_id(identity_id, "identity_id"),
                    "skill_id": stored["skill_id"],
                    "skill_version": stored["skill_version"],
                    "task_family": family,
                    "spec_sha256": stored["spec_sha256"],
                    "execution_result_sha256": execution.get("body_sha256", ""),
                },
                path=self.attribution_path,
            )
        except RuntimeError:
            attribution_recorded = False
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
            "production_route_separate_from_archive": True,
            "attribution_recorded": attribution_recorded,
        }

    def skill_attribution(self, skill_id: str, skill_version: int) -> dict[str, Any]:
        return attribution_summary(
            skill_id,
            skill_version,
            path=self.attribution_path,
        )

    @staticmethod
    def _public_skill(stored: dict[str, Any], *, active: bool) -> dict[str, Any]:
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
            "active": bool(active),
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
            route_status = self.routes.status()
            return {
                "schema_version": SCHEMA_VERSION,
                "identity_bound": bool(str(state.get("identity_id", "")).strip()),
                "revision": max(0, int(state.get("revision", 0))),
                "registered_skill_count": len(skills),
                "verified_learned_skill_count": len(verified_learned),
                "active_family_count": route_status["active_family_count"],
                "archive_and_production_physically_separate": self.path != self.routes.path,
                "production_contains_skill_bodies": False,
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
                "attribution_append_only": True,
                "updated_at": max(0, int(state.get("updated_at", 0))),
            }


_GLOBAL_SKILL_REGISTRY = SkillRegistry()


def skill_registry_status() -> dict[str, Any]:
    try:
        return _GLOBAL_SKILL_REGISTRY.status()
    except RuntimeError as exc:
        return {
            "schema_version": SCHEMA_VERSION,
            "healthy": False,
            "error": str(exc),
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
            "archive_and_production_physically_separate": True,
            "production_contains_skill_bodies": False,
            "attribution_append_only": True,
        }
