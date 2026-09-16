"""Fail-closed persistence wrappers for Jade Genesis proof-critical stores.

The ordinary low-level stores remain independently testable, but the production
learning factories need stronger semantics than "recover the previous backup on
any read failure". For proof state, silently rolling revision N back to N-1 can
resurrect a consumed SEALED_TEST or reopen a LearningGoal. These wrappers:

- share one process-local RLock per canonical file path;
- never replace the primary from a backup during an ordinary read;
- distinguish an unavailable read from readable-but-invalid JSON and fail closed;
- write the backup and primary through unique atomic replacements;
- keep proof files private (0600) and their directories private (0700);
- preflight learned Skill keys before a hidden exam can be consumed;
- make the skill_retained attribution idempotently recoverable after a crash.

Backups remain available for explicit operator recovery, but are not an automatic
source of truth.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Callable

from learning_environment import append_attribution_event, read_attribution_events
from shared_store_lock import (
    atomic_write_bytes,
    atomic_write_text,
    ensure_private_store_path,
    shared_path_lock,
)
from skill_registry import (
    LEARNED_SOURCE_KINDS,
    MAX_SKILLS,
    ProductionSkillRouteStore,
    SkillRegistry,
)
from skill_spec import normalize_skill_spec
from skill_synthesis_loop import LearningGoalStore

JsonValidator = Callable[[dict[str, Any]], bool]


def strict_json_load(
    path: Path,
    *,
    empty_factory: Callable[[], dict[str, Any]],
    validator: JsonValidator,
    error_prefix: str,
) -> dict[str, Any]:
    """Read one primary JSON store without ever falling back to stale state."""

    if not path.exists():
        return empty_factory()
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise RuntimeError(f"{error_prefix}_unavailable") from exc
    try:
        raw = json.loads(text)
    except (json.JSONDecodeError, UnicodeError) as exc:
        raise RuntimeError(f"{error_prefix}_corrupt") from exc
    if not isinstance(raw, dict) or not validator(raw):
        raise RuntimeError(f"{error_prefix}_corrupt")
    return raw


def secure_json_save(
    path: Path,
    backup_path: Path,
    state: dict[str, Any],
    *,
    prepare: Callable[[dict[str, Any]], dict[str, Any]] | None = None,
) -> None:
    """Persist one proof store without accepting an unreadable current primary."""

    path.parent.mkdir(parents=True, exist_ok=True)
    ensure_private_store_path(path)
    persisted = prepare(state) if prepare is not None else state
    encoded = json.dumps(
        persisted,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    )
    if path.exists():
        try:
            current = path.read_bytes()
        except OSError as exc:
            raise RuntimeError(f"{path.name}_read_before_save_failed") from exc
        atomic_write_bytes(backup_path, current, mode=0o600)
    atomic_write_text(path, encoded, mode=0o600)
    ensure_private_store_path(path)


class HardenedLearningGoalStore(LearningGoalStore):
    """LearningGoal store whose primary state never silently rolls backward."""

    def __init__(self, path: Path):
        super().__init__(path)
        self.lock = shared_path_lock(self.path)
        ensure_private_store_path(self.path)

    @staticmethod
    def _valid_state(raw: dict[str, Any]) -> bool:
        return raw.get("schema_version") == 1 and isinstance(raw.get("goals", {}), dict)

    def _load(self) -> dict[str, Any]:
        return strict_json_load(
            self.path,
            empty_factory=self._empty,
            validator=self._valid_state,
            error_prefix="learning_goal_store",
        )

    def _save(self, state: dict[str, Any]) -> None:
        secure_json_save(self.path, self.backup_path, state)

    def freeze_candidate(
        self,
        identity_id: str,
        goal_id: str,
        frozen_source_spec: dict[str, Any],
        selected_spec_sha256: str,
        *,
        now_ms: int,
    ) -> dict[str, Any]:
        """Persist the complete frozen source spec before any hidden execution."""

        goal_id = str(goal_id or "").strip()
        spec_copy = json.loads(
            json.dumps(
                frozen_source_spec,
                ensure_ascii=False,
                separators=(",", ":"),
                sort_keys=True,
            )
        )
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            goal = state.get("goals", {}).get(goal_id)
            if not isinstance(goal, dict):
                raise ValueError("learning_goal_not_found")
            current = str(goal.get("state", ""))
            if current not in {"VISIBLE_TESTING", "FROZEN"}:
                raise ValueError("learning_goal_cannot_freeze_from_state")
            goal["state"] = "FROZEN"
            goal["selected_spec_sha256"] = str(selected_spec_sha256 or "").lower()
            goal["frozen_spec"] = spec_copy
            goal["updated_at"] = max(0, int(now_ms))
            self._touch(state, max(0, int(now_ms)))
            return json.loads(json.dumps(goal))


class HardenedProductionSkillRouteStore(ProductionSkillRouteStore):
    """Production route store with shared locking and fail-closed reads."""

    def __init__(self, path: Path):
        super().__init__(path)
        self.lock = shared_path_lock(self.path)
        ensure_private_store_path(self.path)

    @staticmethod
    def _valid_state(raw: dict[str, Any]) -> bool:
        return raw.get("schema_version") == 1 and isinstance(
            raw.get("active_by_family", {}), dict
        )

    def _load(self) -> dict[str, Any]:
        return strict_json_load(
            self.path,
            empty_factory=self._empty,
            validator=self._valid_state,
            error_prefix="production_skill_routes",
        )

    def _save(self, state: dict[str, Any]) -> None:
        secure_json_save(self.path, self.backup_path, state)


class HardenedSkillRegistry(SkillRegistry):
    """Skill archive with shared locks, strict reads and pre-exam admission."""

    def __init__(
        self,
        path: Path,
        *,
        route_path: Path,
        attribution_path: Path | None = None,
    ):
        # Base initialization may perform a one-time legacy migration. Dynamic
        # dispatch already routes registry _save/_load through the hardened
        # methods below. The route object is replaced immediately afterwards.
        super().__init__(
            path,
            route_path=route_path,
            attribution_path=attribution_path,
        )
        self.lock = shared_path_lock(self.path)
        self.routes = HardenedProductionSkillRouteStore(route_path)
        ensure_private_store_path(self.path)

    @staticmethod
    def _valid_state(raw: dict[str, Any]) -> bool:
        if raw.get("schema_version") != 1 or not isinstance(raw.get("skills", {}), dict):
            return False
        return "active_by_family" not in raw or isinstance(raw.get("active_by_family"), dict)

    def _load(self) -> dict[str, Any]:
        return strict_json_load(
            self.path,
            empty_factory=self._empty,
            validator=self._valid_state,
            error_prefix="skill_registry",
        )

    def _save(self, state: dict[str, Any]) -> None:
        def prepare(raw: dict[str, Any]) -> dict[str, Any]:
            persisted = dict(raw)
            persisted.pop("active_by_family", None)
            return persisted

        secure_json_save(self.path, self.backup_path, state, prepare=prepare)

    def preflight_verified_candidate(
        self,
        identity_id: str,
        raw_spec: dict[str, Any],
    ) -> dict[str, Any]:
        """Reject a collision/capacity fault before SEALED_TEST is consumed."""

        spec = normalize_skill_spec(raw_spec)
        source_kind = str(spec["provenance"].get("source_kind", "")).upper()
        if source_kind not in LEARNED_SOURCE_KINDS:
            raise PermissionError("skill_registry_learned_source_required")
        if spec.get("dependencies"):
            raise PermissionError("skill_registry_dependencies_disabled")
        key = f"{spec['skill_id']}@{int(spec['skill_version'])}"
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            self._migrate_embedded_routes(state, identity_id)
            existing = state.get("skills", {}).get(key)
            if isinstance(existing, dict):
                exact = (
                    str(existing.get("spec_sha256", "")) == str(spec["spec_sha256"])
                    and str(existing.get("task_family", "")) == str(spec["task_family"])
                    and str(existing.get("source_kind", "")).upper() in LEARNED_SOURCE_KINDS
                )
                if not exact:
                    raise ValueError("skill_registry_candidate_slot_occupied")
                return {
                    "skill_key": key,
                    "slot_available": True,
                    "existing_exact_verified_candidate": True,
                }
            if len(state.get("skills", {})) >= MAX_SKILLS:
                raise ValueError("skill_registry_limit_reached")
            return {
                "skill_key": key,
                "slot_available": True,
                "existing_exact_verified_candidate": False,
            }

    def _retention_event_exists(
        self,
        identity_id: str,
        stored: dict[str, Any],
    ) -> bool:
        for event in read_attribution_events(path=self.attribution_path):
            if event.get("event_kind") != "skill_retained":
                continue
            payload = event.get("payload", {})
            if not isinstance(payload, dict):
                continue
            if (
                str(payload.get("identity_id", "")) == str(identity_id)
                and str(payload.get("skill_id", "")) == str(stored.get("skill_id", ""))
                and int(payload.get("skill_version", 0) or 0)
                == int(stored.get("skill_version", 0) or 0)
                and str(payload.get("spec_sha256", ""))
                == str(stored.get("spec_sha256", ""))
            ):
                return True
        return False

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
        """Base retention plus idempotent repair of a missing retention event."""

        result = super().register_verified_skill(
            identity_id,
            raw_spec,
            ledger=ledger,
            dataset_id=dataset_id,
            activate=activate,
            measured_gain=measured_gain,
            now_ms=now_ms,
        )
        spec = normalize_skill_spec(raw_spec)
        key = f"{spec['skill_id']}@{int(spec['skill_version'])}"
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            stored = state.get("skills", {}).get(key)
            if not isinstance(stored, dict):
                raise RuntimeError("skill_registry_retained_skill_missing")
            if not self._retention_event_exists(identity_id, stored):
                verification = stored.get("verification", {})
                append_attribution_event(
                    "skill_retained",
                    {
                        "identity_id": str(identity_id),
                        "skill_id": stored.get("skill_id", ""),
                        "skill_version": stored.get("skill_version", 0),
                        "task_family": stored.get("task_family", ""),
                        "spec_sha256": stored.get("spec_sha256", ""),
                        "provenance": spec.get("provenance", {}),
                        "verification": verification,
                        "measured_gain": measured_gain
                        or {
                            "metric": "capability_available_after_sealed_exam",
                            "before": 0.0,
                            "after": 1.0,
                            "delta": 1.0,
                        },
                        "reconciled_after_interruption": True,
                    },
                    path=self.attribution_path,
                    now_ms=now_ms,
                )
        return result
