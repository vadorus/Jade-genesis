"""Durable Jade-owned adaptive strategy registry.

The registry is the first writable adaptive substrate owned by Jade Genesis.
It can accumulate repeated Night Learning STRATEGY_HINT evidence, record bounded
sandbox evaluations, and keep an explicitly approved active strategy with a
rollback chain. It never executes experiments, mutates model weights, rewrites
production code, changes SafetyPolicy, or promotes a strategy by itself.

Only aggregate operational metadata is stored here. Raw conversation text is
not accepted or persisted.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import threading
import time
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
MAX_STRATEGIES = 200
MAX_PROVENANCE = 20
MAX_HISTORY = 40
MAX_TEXT_CHARS = 500
MAX_ID_CHARS = 160
MIN_VALIDATION_SAMPLES = 5
MIN_VALIDATION_DELTA = 0.25
MIN_VALIDATION_CONFIDENCE = 0.75
ALLOWED_TARGETS = {"routing.profile_model_preference"}
ALLOWED_STATUSES = {
    "CANDIDATE",
    "TESTING",
    "VALIDATED",
    "ACTIVE",
    "RETIRED",
    "REJECTED",
    "ROLLED_BACK",
}

CONFIG_DIR = Path(
    os.environ.get(
        "JADE_GENESIS_CONFIG_DIR",
        str(Path.home() / ".jade-genesis"),
    )
)
STRATEGY_REGISTRY_PATH = CONFIG_DIR / "adaptive-strategy-registry.json"


def _now_ms() -> int:
    return int(time.time() * 1_000)


def _safe_int(value: Any, fallback: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return fallback


def _safe_float(value: Any, fallback: float = 0.0) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return fallback
    if not math.isfinite(parsed):
        return fallback
    return parsed


def _clean(value: Any, limit: int = MAX_TEXT_CHARS) -> str:
    return " ".join(str(value or "").replace("\x00", " ").split())[:limit]


def _required_id(value: Any, name: str) -> str:
    text = _clean(value, MAX_ID_CHARS)
    if not text:
        raise ValueError(f"missing_{name}")
    return text


def _clamp(value: Any, minimum: float, maximum: float, fallback: float = 0.0) -> float:
    return min(maximum, max(minimum, _safe_float(value, fallback)))


def _stable_id(prefix: str, *parts: Any) -> str:
    raw = "|".join(_clean(part, 800) for part in parts)
    digest = hashlib.sha256(raw.encode("utf-8")).hexdigest()[:20]
    return f"{prefix}-{digest}"


def _clean_scope(candidate: dict[str, Any]) -> dict[str, str]:
    raw = candidate.get("scope")
    scope = raw if isinstance(raw, dict) else {}
    return {
        "task_kind": _clean(scope.get("task_kind") or candidate.get("task_kind") or "brain_chat", 100),
        "brain_profile": _clean(scope.get("brain_profile") or candidate.get("brain_profile"), 100).lower(),
        "model": _clean(scope.get("model") or candidate.get("model"), 160),
        "node_id": _clean(scope.get("node_id") or candidate.get("node_id"), 120),
    }


def _scope_key(target: str, scope: dict[str, str]) -> str:
    return _stable_id(
        "scope",
        target,
        scope.get("task_kind", ""),
        scope.get("brain_profile", ""),
        scope.get("model", ""),
        scope.get("node_id", ""),
    )


class AdaptiveStrategyRegistry:
    """Atomic, identity-bound registry for bounded learned strategies."""

    def __init__(self, path: Path = STRATEGY_REGISTRY_PATH):
        self.path = path
        self.backup_path = path.with_suffix(path.suffix + ".bak")
        self.lock = threading.RLock()

    @staticmethod
    def _empty() -> dict[str, Any]:
        return {
            "schema_version": SCHEMA_VERSION,
            "identity_id": "",
            "revision": 0,
            "entries": {},
            "active_by_scope": {},
            "history": [],
            "updated_at": 0,
        }

    def _decode(self, path: Path) -> dict[str, Any] | None:
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return None
        if not isinstance(raw, dict):
            return None
        if _safe_int(raw.get("schema_version"), 0) != SCHEMA_VERSION:
            return None
        if not isinstance(raw.get("entries", {}), dict):
            return None
        if not isinstance(raw.get("active_by_scope", {}), dict):
            return None
        if not isinstance(raw.get("history", []), list):
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
        raise RuntimeError("strategy_registry_corrupt")

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
    def _bind_identity(state: dict[str, Any], identity_id: str) -> bool:
        clean_identity = _required_id(identity_id, "identity_id")
        bound = _clean(state.get("identity_id"), MAX_ID_CHARS)
        if bound and bound != clean_identity:
            raise ValueError("strategy_registry_identity_mismatch")
        if not bound:
            state["identity_id"] = clean_identity
            return True
        return False

    @staticmethod
    def _history(
        state: dict[str, Any],
        action: str,
        strategy_id: str,
        at: int,
        **metadata: Any,
    ) -> None:
        history = [item for item in state.get("history", []) if isinstance(item, dict)]
        record = {
            "action": _clean(action, 80),
            "strategy_id": _clean(strategy_id, MAX_ID_CHARS),
            "at": max(0, _safe_int(at, 0)),
        }
        for key, value in metadata.items():
            if isinstance(value, bool):
                record[key] = value
            elif isinstance(value, (int, float)):
                record[key] = value
            else:
                record[key] = _clean(value, 200)
        history.insert(0, record)
        state["history"] = history[:MAX_HISTORY]

    @staticmethod
    def _dangerous_candidate(candidate: dict[str, Any]) -> bool:
        return any(
            bool(candidate.get(field, False))
            for field in (
                "automatic_activation",
                "automatic_promotion",
                "production_code_change",
                "production_code_rewrite",
                "shell_execution",
                "raw_conversation_text_used",
                "user_feedback_promoted_to_external_fact",
            )
        )

    def _normalize_candidate(
        self,
        candidate: dict[str, Any],
        source_revision: int,
        now: int,
    ) -> dict[str, Any] | None:
        if candidate.get("kind") != "STRATEGY_HINT":
            return None
        if candidate.get("status") != "CANDIDATE":
            return None
        target = _clean(candidate.get("target"), 160)
        if target not in ALLOWED_TARGETS:
            return None
        if self._dangerous_candidate(candidate):
            return None
        if not bool(candidate.get("sandbox_required", False)):
            return None

        candidate_id = _required_id(candidate.get("candidate_id"), "candidate_id")
        experiment_id = _required_id(candidate.get("experiment_id"), "experiment_id")
        title = _clean(candidate.get("title"), 180)
        rationale = _clean(candidate.get("rationale"), MAX_TEXT_CHARS)
        if not title or not rationale:
            return None
        scope = _clean_scope(candidate)
        scope_id = _scope_key(target, scope)
        strategy_id = _stable_id(
            "strategy",
            target,
            title,
            rationale,
            scope_id,
        )
        provenance = {
            "source": "night_learning",
            "candidate_id": candidate_id,
            "experiment_id": experiment_id,
            "source_revision": max(0, _safe_int(source_revision, 0)),
            "observed_at": now,
        }
        return {
            "strategy_id": strategy_id,
            "kind": "STRATEGY_HINT",
            "status": "CANDIDATE",
            "title": title,
            "rationale": rationale,
            "target": target,
            "scope": scope,
            "scope_key": scope_id,
            "score": 0.0,
            "confidence": 0.0,
            "evidence_count": 1,
            "sandbox_samples": 0,
            "protected_failures": 0,
            "champion_score": 0.0,
            "challenger_score": 0.0,
            "created_at": now,
            "updated_at": now,
            "last_seen_at": now,
            "activated_at": 0,
            "previous_active_strategy_id": "",
            "automatic_activation": False,
            "automatic_promotion": False,
            "production_code_change": False,
            "provenance": [provenance],
        }

    def ingest_candidates(
        self,
        identity_id: str,
        candidates: list[dict[str, Any]],
        source_revision: int,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        if not isinstance(candidates, list):
            raise ValueError("invalid_strategy_candidates")
        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        with self.lock:
            state = self._load()
            changed = self._bind_identity(state, identity_id)
            entries = {
                str(key): value
                for key, value in state.get("entries", {}).items()
                if isinstance(value, dict)
            }
            accepted = 0
            created = 0
            reinforced = 0
            ignored = 0

            for raw in candidates[:MAX_STRATEGIES]:
                if not isinstance(raw, dict):
                    ignored += 1
                    continue
                normalized = self._normalize_candidate(raw, source_revision, now)
                if normalized is None:
                    ignored += 1
                    continue
                accepted += 1
                strategy_id = normalized["strategy_id"]
                previous = entries.get(strategy_id)
                if previous is None:
                    entries[strategy_id] = normalized
                    created += 1
                    changed = True
                    self._history(state, "INGESTED", strategy_id, now)
                    continue

                existing_provenance = [
                    item for item in previous.get("provenance", [])
                    if isinstance(item, dict)
                ]
                new_provenance = normalized["provenance"][0]
                duplicate = any(
                    _clean(item.get("candidate_id"), MAX_ID_CHARS)
                    == new_provenance["candidate_id"]
                    and _safe_int(item.get("source_revision"), -1)
                    == new_provenance["source_revision"]
                    for item in existing_provenance
                )
                if duplicate:
                    continue
                previous["provenance"] = ([new_provenance] + existing_provenance)[:MAX_PROVENANCE]
                previous["evidence_count"] = min(
                    MAX_PROVENANCE,
                    max(1, _safe_int(previous.get("evidence_count"), 1)) + 1,
                )
                previous["last_seen_at"] = now
                previous["updated_at"] = now
                previous["automatic_activation"] = False
                previous["automatic_promotion"] = False
                previous["production_code_change"] = False
                entries[strategy_id] = previous
                reinforced += 1
                changed = True
                self._history(
                    state,
                    "REINFORCED",
                    strategy_id,
                    now,
                    evidence_count=previous["evidence_count"],
                )

            if len(entries) > MAX_STRATEGIES:
                removable = sorted(
                    (
                        item for item in entries.values()
                        if item.get("status") not in {"ACTIVE", "VALIDATED"}
                    ),
                    key=lambda item: (
                        _safe_int(item.get("last_seen_at"), 0),
                        _safe_int(item.get("created_at"), 0),
                    ),
                )
                while len(entries) > MAX_STRATEGIES and removable:
                    victim = removable.pop(0)
                    entries.pop(str(victim.get("strategy_id", "")), None)
                    changed = True

            state["entries"] = entries
            if changed:
                state["revision"] = max(0, _safe_int(state.get("revision"), 0)) + 1
                state["updated_at"] = now
                self._save(state)

            return {
                "accepted": accepted,
                "created": created,
                "reinforced": reinforced,
                "ignored": ignored,
                "registry_revision": max(0, _safe_int(state.get("revision"), 0)),
                "strategy_count": len(entries),
                "automatic_activation": False,
                "automatic_promotion": False,
            }

    def begin_testing(
        self,
        identity_id: str,
        strategy_id: str,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        clean_id = _required_id(strategy_id, "strategy_id")
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            entries = state.get("entries", {})
            entry = entries.get(clean_id)
            if not isinstance(entry, dict):
                raise ValueError("strategy_not_found")
            if entry.get("status") not in {"CANDIDATE", "TESTING"}:
                raise ValueError("strategy_not_testable")
            if entry.get("status") != "TESTING":
                entry["status"] = "TESTING"
                entry["updated_at"] = now
                state["revision"] = max(0, _safe_int(state.get("revision"), 0)) + 1
                state["updated_at"] = now
                self._history(state, "TESTING", clean_id, now)
                self._save(state)
            return dict(entry)

    def record_sandbox_evaluation(
        self,
        identity_id: str,
        strategy_id: str,
        champion_score: float,
        challenger_score: float,
        confidence: float,
        samples: int,
        protected_failures: int = 0,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        clean_id = _required_id(strategy_id, "strategy_id")
        sample_count = max(0, min(10_000, _safe_int(samples, 0)))
        failures = max(0, min(sample_count, _safe_int(protected_failures, 0)))
        champion = _clamp(champion_score, -1.0, 1.0)
        challenger = _clamp(challenger_score, -1.0, 1.0)
        conf = _clamp(confidence, 0.0, 1.0)
        delta = challenger - champion
        validated = (
            sample_count >= MIN_VALIDATION_SAMPLES
            and delta >= MIN_VALIDATION_DELTA
            and conf >= MIN_VALIDATION_CONFIDENCE
            and failures == 0
        )

        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            entries = state.get("entries", {})
            entry = entries.get(clean_id)
            if not isinstance(entry, dict):
                raise ValueError("strategy_not_found")
            if entry.get("status") not in {"CANDIDATE", "TESTING", "VALIDATED"}:
                raise ValueError("strategy_not_evaluable")

            entry["status"] = "VALIDATED" if validated else "TESTING"
            entry["champion_score"] = champion
            entry["challenger_score"] = challenger
            entry["score"] = _clamp(delta, -1.0, 1.0)
            entry["confidence"] = conf
            entry["sandbox_samples"] = sample_count
            entry["protected_failures"] = failures
            entry["updated_at"] = now
            entry["automatic_activation"] = False
            entry["automatic_promotion"] = False
            entry["production_code_change"] = False
            state["revision"] = max(0, _safe_int(state.get("revision"), 0)) + 1
            state["updated_at"] = now
            self._history(
                state,
                "VALIDATED" if validated else "EVALUATED",
                clean_id,
                now,
                score=entry["score"],
                confidence=conf,
                samples=sample_count,
                protected_failures=failures,
            )
            self._save(state)
            return dict(entry)

    def promote(
        self,
        identity_id: str,
        strategy_id: str,
        explicit_approval: bool,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        if explicit_approval is not True:
            raise PermissionError("explicit_strategy_promotion_approval_required")
        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        clean_id = _required_id(strategy_id, "strategy_id")
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            entries = state.get("entries", {})
            entry = entries.get(clean_id)
            if not isinstance(entry, dict):
                raise ValueError("strategy_not_found")
            if entry.get("status") != "VALIDATED":
                raise ValueError("strategy_not_validated")

            scope_id = _required_id(entry.get("scope_key"), "scope_key")
            active_by_scope = {
                str(key): str(value)
                for key, value in state.get("active_by_scope", {}).items()
            }
            previous_id = _clean(active_by_scope.get(scope_id), MAX_ID_CHARS)
            if previous_id and previous_id != clean_id:
                previous = entries.get(previous_id)
                if isinstance(previous, dict) and previous.get("status") == "ACTIVE":
                    previous["status"] = "RETIRED"
                    previous["updated_at"] = now
                    entries[previous_id] = previous

            entry["status"] = "ACTIVE"
            entry["activated_at"] = now
            entry["updated_at"] = now
            entry["previous_active_strategy_id"] = previous_id if previous_id != clean_id else ""
            entry["automatic_activation"] = False
            entry["automatic_promotion"] = False
            active_by_scope[scope_id] = clean_id
            state["entries"] = entries
            state["active_by_scope"] = active_by_scope
            state["revision"] = max(0, _safe_int(state.get("revision"), 0)) + 1
            state["updated_at"] = now
            self._history(
                state,
                "PROMOTED",
                clean_id,
                now,
                explicit_approval=True,
                previous_active_strategy_id=previous_id,
            )
            self._save(state)
            return dict(entry)

    def rollback(
        self,
        identity_id: str,
        strategy_id: str,
        explicit_approval: bool,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        if explicit_approval is not True:
            raise PermissionError("explicit_strategy_rollback_approval_required")
        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        clean_id = _required_id(strategy_id, "strategy_id")
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            entries = state.get("entries", {})
            entry = entries.get(clean_id)
            if not isinstance(entry, dict):
                raise ValueError("strategy_not_found")
            if entry.get("status") != "ACTIVE":
                raise ValueError("strategy_not_active")

            scope_id = _required_id(entry.get("scope_key"), "scope_key")
            active_by_scope = {
                str(key): str(value)
                for key, value in state.get("active_by_scope", {}).items()
            }
            if active_by_scope.get(scope_id) != clean_id:
                raise ValueError("strategy_active_index_mismatch")

            previous_id = _clean(entry.get("previous_active_strategy_id"), MAX_ID_CHARS)
            restored_id = ""
            if previous_id:
                previous = entries.get(previous_id)
                if isinstance(previous, dict) and previous.get("status") in {"RETIRED", "VALIDATED"}:
                    previous["status"] = "ACTIVE"
                    previous["activated_at"] = now
                    previous["updated_at"] = now
                    entries[previous_id] = previous
                    active_by_scope[scope_id] = previous_id
                    restored_id = previous_id
                else:
                    active_by_scope.pop(scope_id, None)
            else:
                active_by_scope.pop(scope_id, None)

            entry["status"] = "ROLLED_BACK"
            entry["updated_at"] = now
            entries[clean_id] = entry
            state["entries"] = entries
            state["active_by_scope"] = active_by_scope
            state["revision"] = max(0, _safe_int(state.get("revision"), 0)) + 1
            state["updated_at"] = now
            self._history(
                state,
                "ROLLED_BACK",
                clean_id,
                now,
                explicit_approval=True,
                restored_strategy_id=restored_id,
            )
            self._save(state)
            return {
                "rolled_back_strategy_id": clean_id,
                "restored_strategy_id": restored_id,
                "registry_revision": state["revision"],
            }

    def snapshot(self, identity_id: str | None = None) -> dict[str, Any]:
        with self.lock:
            state = self._load()
            if identity_id is not None:
                bound = _clean(state.get("identity_id"), MAX_ID_CHARS)
                requested = _required_id(identity_id, "identity_id")
                if bound and bound != requested:
                    raise ValueError("strategy_registry_identity_mismatch")
            entries = [
                dict(item)
                for item in state.get("entries", {}).values()
                if isinstance(item, dict)
            ]
            entries.sort(
                key=lambda item: (
                    _safe_int(item.get("updated_at"), 0),
                    str(item.get("strategy_id", "")),
                ),
                reverse=True,
            )
            status_counts = {
                status: sum(1 for item in entries if item.get("status") == status)
                for status in sorted(ALLOWED_STATUSES)
            }
            return {
                "schema_version": SCHEMA_VERSION,
                "identity_bound": bool(_clean(state.get("identity_id"), MAX_ID_CHARS)),
                "registry_revision": max(0, _safe_int(state.get("revision"), 0)),
                "strategy_count": len(entries),
                "active_strategy_count": status_counts.get("ACTIVE", 0),
                "validated_strategy_count": status_counts.get("VALIDATED", 0),
                "candidate_strategy_count": status_counts.get("CANDIDATE", 0),
                "testing_strategy_count": status_counts.get("TESTING", 0),
                "status_counts": status_counts,
                "entries": entries[:MAX_STRATEGIES],
                "history": [
                    dict(item) for item in state.get("history", [])
                    if isinstance(item, dict)
                ][:MAX_HISTORY],
                "updated_at": max(0, _safe_int(state.get("updated_at"), 0)),
                "raw_conversation_text_stored": False,
                "automatic_activation": False,
                "automatic_promotion": False,
                "production_code_rewrite": False,
                "model_weight_mutation": False,
                "shell_execution": False,
            }

    def status(self) -> dict[str, Any]:
        snapshot = self.snapshot()
        return {
            key: snapshot[key]
            for key in (
                "schema_version",
                "identity_bound",
                "registry_revision",
                "strategy_count",
                "active_strategy_count",
                "validated_strategy_count",
                "candidate_strategy_count",
                "testing_strategy_count",
                "updated_at",
                "automatic_activation",
                "automatic_promotion",
                "production_code_rewrite",
                "model_weight_mutation",
                "shell_execution",
            )
        }


_GLOBAL_REGISTRY = AdaptiveStrategyRegistry()


def adaptive_strategy_registry_status() -> dict[str, Any]:
    return _GLOBAL_REGISTRY.status()
