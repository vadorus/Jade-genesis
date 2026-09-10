"""Persistent Jade-owned strategy registry.

0.1.18 turns Night Learning STRATEGY_HINT artifacts into a durable, identity-bound
adaptive substrate. The registry stores strategy candidates, provenance, bounded
sandbox evaluations, explicit promotions and rollback history.

Important boundaries:
- ingesting a candidate may be automatic, promotion may not;
- ACTIVE means approved inside the registry, not automatically applied to runtime;
- this module never rewrites production code, mutates model weights, executes shell
  commands, lowers SafetyPolicy or turns personal user feedback into external fact;
- only bounded aggregate Night Learning evidence is accepted, never raw conversation
  text.
"""

from __future__ import annotations

import hashlib
import json
import os
import threading
import time
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
MAX_ENTRIES = 100
MAX_SNAPSHOT_ENTRIES = 20
MAX_EVALUATIONS_PER_ENTRY = 20
MAX_TEXT_CHARS = 500
MAX_ID_CHARS = 160
MAX_EVIDENCE_IDS = 12
MIN_PROMOTION_SAMPLES = 5
MIN_PROMOTION_CONFIDENCE = 0.75
STRATEGY_TARGET = "routing.profile_model_preference"

CONFIG_DIR = Path(
    os.environ.get(
        "JADE_GENESIS_CONFIG_DIR",
        str(Path.home() / ".jade-genesis"),
    )
)
REGISTRY_PATH = CONFIG_DIR / "strategy-registry.json"

_ALLOWED_STATES = {
    "CANDIDATE",
    "VALIDATED",
    "ACTIVE",
    "SUPERSEDED",
    "ROLLED_BACK",
    "REJECTED",
}
_STRATEGY_SIGNAL_KINDS = {
    "outcome_quality_risk",
    "outcome_correction_pressure",
    "outcome_quality_advantage",
}


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
    if parsed != parsed or parsed in (float("inf"), float("-inf")):
        return fallback
    return parsed


def _clean(value: Any, limit: int = MAX_TEXT_CHARS) -> str:
    return " ".join(str(value or "").replace("\x00", " ").split())[:limit]


def _required(value: Any, name: str, limit: int = MAX_ID_CHARS) -> str:
    text = _clean(value, limit)
    if not text:
        raise ValueError(f"missing_{name}")
    return text


def _stable_id(prefix: str, *parts: Any) -> str:
    raw = "|".join(_clean(part, 400) for part in parts)
    digest = hashlib.sha256(raw.encode("utf-8")).hexdigest()[:20]
    return f"{prefix}-{digest}"


def _bounded_score(value: Any) -> float:
    return min(2.0, max(-2.0, _safe_float(value, 0.0)))


class StrategyRegistry:
    def __init__(self, path: Path = REGISTRY_PATH):
        self.path = path
        self.backup_path = path.with_suffix(path.suffix + ".bak")
        self.lock = threading.RLock()

    @staticmethod
    def _empty() -> dict[str, Any]:
        return {
            "schema_version": SCHEMA_VERSION,
            "identity_id": "",
            "revision": 0,
            "entries": [],
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
        entries = raw.get("entries", [])
        if not isinstance(entries, list):
            return None
        clean_entries = [
            item for item in entries
            if isinstance(item, dict) and item.get("status") in _ALLOWED_STATES
        ][:MAX_ENTRIES]
        return {
            "schema_version": SCHEMA_VERSION,
            "identity_id": _clean(raw.get("identity_id"), MAX_ID_CHARS),
            "revision": max(0, _safe_int(raw.get("revision"), 0)),
            "entries": clean_entries,
            "updated_at": max(0, _safe_int(raw.get("updated_at"), 0)),
        }

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
        identity = _required(identity_id, "identity_id")
        bound = _clean(state.get("identity_id"), MAX_ID_CHARS)
        if bound and bound != identity:
            raise ValueError("strategy_registry_identity_mismatch")
        if not bound:
            state["identity_id"] = identity
            return True
        return False

    @staticmethod
    def _find_by_id(entries: list[dict[str, Any]], strategy_id: str) -> dict[str, Any]:
        clean_id = _required(strategy_id, "strategy_id")
        for entry in entries:
            if _clean(entry.get("strategy_id"), MAX_ID_CHARS) == clean_id:
                return entry
        raise ValueError("strategy_not_found")

    @staticmethod
    def _activation_scope(entry: dict[str, Any]) -> str:
        return _stable_id(
            "scope",
            entry.get("target"),
            entry.get("task_kind"),
            entry.get("brain_profile"),
        )

    @staticmethod
    def _lookup_by_id(items: Any, key: str, value: str) -> dict[str, Any] | None:
        if not isinstance(items, list):
            return None
        for item in items:
            if isinstance(item, dict) and _clean(item.get(key), MAX_ID_CHARS) == value:
                return item
        return None

    @staticmethod
    def _matching_signal(snapshot: dict[str, Any], signal_kind: str) -> dict[str, Any] | None:
        signals = snapshot.get("signals", [])
        if not isinstance(signals, list):
            return None
        for item in signals:
            if isinstance(item, dict) and _clean(item.get("kind"), 120) == signal_kind:
                return item
        return None

    def _normalize_strategy(
        self,
        snapshot: dict[str, Any],
        candidate: dict[str, Any],
        now: int,
    ) -> dict[str, Any]:
        if _clean(candidate.get("kind"), 100) != "STRATEGY_HINT":
            raise ValueError("not_strategy_hint")
        if _clean(candidate.get("status"), 40) != "CANDIDATE":
            raise ValueError("strategy_candidate_status_invalid")
        if _clean(candidate.get("target"), 180) != STRATEGY_TARGET:
            raise ValueError("strategy_target_not_allowed")
        if not bool(candidate.get("sandbox_required", True)):
            raise ValueError("strategy_sandbox_required")
        if bool(candidate.get("automatic_activation", False)):
            raise ValueError("strategy_automatic_activation_forbidden")
        if bool(candidate.get("automatic_promotion", False)):
            raise ValueError("strategy_automatic_promotion_forbidden")
        if bool(candidate.get("production_code_change", False)):
            raise ValueError("strategy_production_code_change_forbidden")

        candidate_id = _required(candidate.get("candidate_id"), "candidate_id")
        experiment_id = _required(candidate.get("experiment_id"), "experiment_id")
        experiment = self._lookup_by_id(
            snapshot.get("experiments", []),
            "experiment_id",
            experiment_id,
        )
        if experiment is None:
            raise ValueError("strategy_experiment_missing")
        if _clean(experiment.get("target"), 180) != STRATEGY_TARGET:
            raise ValueError("strategy_experiment_target_invalid")
        if bool(experiment.get("automatic_execution", False)):
            raise ValueError("strategy_experiment_automatic_forbidden")
        if not bool(experiment.get("sandbox_required", True)):
            raise ValueError("strategy_experiment_sandbox_required")
        if not bool(experiment.get("paired_scenarios_required", True)):
            raise ValueError("strategy_paired_scenarios_required")
        if not bool(experiment.get("frozen_champion_required", True)):
            raise ValueError("strategy_frozen_champion_required")
        if not bool(experiment.get("requires_explicit_promotion_approval", True)):
            raise ValueError("strategy_explicit_promotion_required")

        hypothesis_id = _required(experiment.get("hypothesis_id"), "hypothesis_id")
        hypothesis = self._lookup_by_id(
            snapshot.get("hypotheses", []),
            "hypothesis_id",
            hypothesis_id,
        )
        if hypothesis is None:
            raise ValueError("strategy_hypothesis_missing")
        if bool(hypothesis.get("promoted_as_external_fact", False)):
            raise ValueError("strategy_external_fact_promotion_forbidden")
        if not bool(hypothesis.get("personal_outcome_evidence", False)):
            raise ValueError("strategy_personal_outcome_evidence_required")

        signal_kind = _required(hypothesis.get("signal_kind"), "signal_kind", 120)
        if signal_kind not in _STRATEGY_SIGNAL_KINDS:
            raise ValueError("strategy_signal_kind_not_allowed")
        signal = self._matching_signal(snapshot, signal_kind)
        if signal is None:
            raise ValueError("strategy_signal_missing")

        task_kind = _clean(signal.get("task_kind"), 100) or "brain_chat"
        if task_kind != "brain_chat":
            raise ValueError("strategy_task_kind_not_allowed")
        brain_profile = _required(signal.get("brain_profile"), "brain_profile", 100).lower()
        node_id = _clean(signal.get("node_id"), 120)
        model = _clean(signal.get("model"), 160)
        preferred_node_id = _clean(signal.get("preferred_node_id"), 120)
        preferred_model = _clean(signal.get("preferred_model"), 160)
        comparison_node_id = _clean(signal.get("comparison_node_id"), 120)
        comparison_model = _clean(signal.get("comparison_model"), 160)

        if signal_kind == "outcome_quality_advantage":
            mode = "prefer"
            if not preferred_node_id and not preferred_model:
                raise ValueError("strategy_preferred_backend_missing")
            action_node = preferred_node_id
            action_model = preferred_model
        else:
            mode = "deprioritize"
            if not node_id and not model:
                raise ValueError("strategy_backend_missing")
            action_node = node_id
            action_model = model

        semantic_key = _stable_id(
            "strategy-key",
            STRATEGY_TARGET,
            task_kind,
            brain_profile,
            mode,
            action_node,
            action_model,
            comparison_node_id,
            comparison_model,
        )
        strategy_id = _stable_id("strategy", semantic_key, candidate_id)
        evidence_ids_raw = hypothesis.get("evidence_ids", [])
        evidence_ids = []
        if isinstance(evidence_ids_raw, list):
            for item in evidence_ids_raw:
                cleaned = _clean(item, MAX_ID_CHARS)
                if cleaned and cleaned not in evidence_ids:
                    evidence_ids.append(cleaned)
                if len(evidence_ids) >= MAX_EVIDENCE_IDS:
                    break

        confidence = min(
            1.0,
            max(0.0, _safe_float(hypothesis.get("confidence"), 0.0)),
        )
        outcome_confidence = min(
            1.0,
            max(0.0, _safe_float(signal.get("outcome_confidence"), 0.0)),
        )
        samples = max(0, _safe_int(signal.get("samples"), 0))
        source_revision = max(0, _safe_int(snapshot.get("source_revision"), 0))

        return {
            "strategy_id": strategy_id,
            "semantic_key": semantic_key,
            "version": 1,
            "status": "CANDIDATE",
            "kind": "ROUTING_STRATEGY",
            "target": STRATEGY_TARGET,
            "mode": mode,
            "task_kind": task_kind,
            "brain_profile": brain_profile,
            "node_id": node_id,
            "model": model,
            "preferred_node_id": preferred_node_id,
            "preferred_model": preferred_model,
            "comparison_node_id": comparison_node_id,
            "comparison_model": comparison_model,
            "score": _bounded_score(signal.get("metric")),
            "confidence": confidence,
            "outcome_confidence": outcome_confidence,
            "sample_count": samples,
            "rationale": _clean(candidate.get("rationale")),
            "candidate_id": candidate_id,
            "experiment_id": experiment_id,
            "hypothesis_id": hypothesis_id,
            "signal_kind": signal_kind,
            "evidence_ids": evidence_ids,
            "source_revision": source_revision,
            "source_run_id": _clean(snapshot.get("run_id"), MAX_ID_CHARS),
            "source_generated_at": max(0, _safe_int(snapshot.get("generated_at"), 0)),
            "source_reviewed_at": max(0, _safe_int(snapshot.get("reviewed_at"), 0)),
            "sandbox_required": True,
            "paired_scenarios_required": True,
            "frozen_champion_required": True,
            "explicit_promotion_approval_required": True,
            "explicit_promotion_approved": False,
            "runtime_application_enabled": False,
            "production_code_change": False,
            "automatic_activation": False,
            "automatic_promotion": False,
            "evaluations": [],
            "evaluation_passed": False,
            "evaluation_score": 0.0,
            "evaluation_confidence": 0.0,
            "evaluation_sample_count": 0,
            "replaces_strategy_id": "",
            "superseded_by_strategy_id": "",
            "created_at": now,
            "updated_at": now,
            "promoted_at": 0,
            "rolled_back_at": 0,
        }

    @staticmethod
    def _trim_entries(entries: list[dict[str, Any]]) -> list[dict[str, Any]]:
        if len(entries) <= MAX_ENTRIES:
            return entries
        active = [item for item in entries if item.get("status") == "ACTIVE"]
        active_ids = {str(item.get("strategy_id", "")) for item in active}
        others = [
            item for item in entries
            if str(item.get("strategy_id", "")) not in active_ids
        ]
        others.sort(
            key=lambda item: (
                _safe_int(item.get("updated_at"), 0),
                str(item.get("strategy_id", "")),
            ),
            reverse=True,
        )
        kept = active[:MAX_ENTRIES]
        kept.extend(others[: max(0, MAX_ENTRIES - len(kept))])
        return kept

    def ingest_night_learning(
        self,
        identity_id: str,
        snapshot: dict[str, Any],
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        if not isinstance(snapshot, dict):
            raise ValueError("invalid_night_learning_snapshot")
        if _safe_int(snapshot.get("schema_version"), 0) != SCHEMA_VERSION:
            raise ValueError("incompatible_night_learning_schema")
        if bool(snapshot.get("automatic_experiment_execution", False)):
            raise ValueError("automatic_experiment_execution_forbidden")
        if bool(snapshot.get("automatic_promotion", False)):
            raise ValueError("automatic_promotion_forbidden")
        if bool(snapshot.get("production_code_rewrite", False)):
            raise ValueError("production_code_rewrite_forbidden")
        if bool(snapshot.get("shell_execution", False)):
            raise ValueError("shell_execution_forbidden")
        if bool(snapshot.get("raw_conversation_text_used", False)):
            raise ValueError("raw_conversation_text_forbidden")
        if bool(snapshot.get("user_feedback_promoted_to_external_fact", False)):
            raise ValueError("user_feedback_external_fact_forbidden")

        candidates = snapshot.get("improvement_candidates", [])
        if not isinstance(candidates, list):
            raise ValueError("invalid_strategy_candidates")
        if len(candidates) > 20:
            raise ValueError("too_many_strategy_candidates")

        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        with self.lock:
            state = self._load()
            changed = self._bind_identity(state, identity_id)
            entries = [item for item in state.get("entries", []) if isinstance(item, dict)]
            candidate_ids = {
                _clean(item.get("candidate_id"), MAX_ID_CHARS)
                for item in entries
                if _clean(item.get("candidate_id"), MAX_ID_CHARS)
            }
            inserted: list[str] = []

            for candidate in candidates:
                if not isinstance(candidate, dict):
                    raise ValueError("invalid_strategy_candidate")
                if _clean(candidate.get("kind"), 100) != "STRATEGY_HINT":
                    continue
                normalized = self._normalize_strategy(snapshot, candidate, now)
                if normalized["candidate_id"] in candidate_ids:
                    continue
                versions = [
                    max(1, _safe_int(item.get("version"), 1))
                    for item in entries
                    if item.get("semantic_key") == normalized["semantic_key"]
                ]
                normalized["version"] = max(versions, default=0) + 1

                for previous in entries:
                    if previous.get("semantic_key") != normalized["semantic_key"]:
                        continue
                    if previous.get("status") in {"CANDIDATE", "VALIDATED"}:
                        previous["status"] = "SUPERSEDED"
                        previous["superseded_by_strategy_id"] = normalized["strategy_id"]
                        previous["updated_at"] = now

                entries.append(normalized)
                candidate_ids.add(normalized["candidate_id"])
                inserted.append(normalized["strategy_id"])
                changed = True

            if changed:
                state["entries"] = self._trim_entries(entries)
                state["revision"] = max(0, _safe_int(state.get("revision"), 0)) + 1
                state["updated_at"] = now
                self._save(state)

            return {
                "persisted_count": len(inserted),
                "persisted_strategy_ids": inserted,
                "registry": self._snapshot_from_state(state, now),
            }

    def record_sandbox_evaluation(
        self,
        identity_id: str,
        strategy_id: str,
        *,
        passed: bool,
        score: float,
        confidence: float,
        sample_count: int,
        evaluator: str = "runtime_eval",
        notes: str = "",
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            entries = [item for item in state.get("entries", []) if isinstance(item, dict)]
            entry = self._find_by_id(entries, strategy_id)
            if entry.get("status") not in {"CANDIDATE", "VALIDATED"}:
                raise ValueError("strategy_not_evaluable")
            if not bool(entry.get("sandbox_required", True)):
                raise ValueError("strategy_sandbox_required")

            bounded_confidence = min(1.0, max(0.0, _safe_float(confidence, 0.0)))
            bounded_samples = max(0, min(1_000, _safe_int(sample_count, 0)))
            evaluation = {
                "evaluation_id": _stable_id(
                    "strategy-eval",
                    strategy_id,
                    now,
                    bounded_samples,
                    score,
                    passed,
                ),
                "sandbox": True,
                "paired_scenarios": True,
                "frozen_champion": True,
                "automatic": False,
                "passed": bool(passed),
                "score": _bounded_score(score),
                "confidence": bounded_confidence,
                "sample_count": bounded_samples,
                "evaluator": _clean(evaluator, 120) or "runtime_eval",
                "notes": _clean(notes),
                "recorded_at": now,
            }
            evaluations = [
                item for item in entry.get("evaluations", [])
                if isinstance(item, dict)
            ]
            evaluations.append(evaluation)
            entry["evaluations"] = evaluations[-MAX_EVALUATIONS_PER_ENTRY:]
            entry["evaluation_passed"] = bool(passed)
            entry["evaluation_score"] = evaluation["score"]
            entry["evaluation_confidence"] = bounded_confidence
            entry["evaluation_sample_count"] = bounded_samples
            entry["status"] = (
                "VALIDATED"
                if passed
                and bounded_samples >= MIN_PROMOTION_SAMPLES
                and bounded_confidence >= MIN_PROMOTION_CONFIDENCE
                else "REJECTED" if not passed else "CANDIDATE"
            )
            entry["updated_at"] = now
            state["entries"] = entries
            state["revision"] = max(0, _safe_int(state.get("revision"), 0)) + 1
            state["updated_at"] = now
            self._save(state)
            return dict(entry)

    def promote(
        self,
        identity_id: str,
        strategy_id: str,
        *,
        explicit_approval: bool,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        if not explicit_approval:
            raise ValueError("explicit_strategy_promotion_approval_required")
        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            entries = [item for item in state.get("entries", []) if isinstance(item, dict)]
            entry = self._find_by_id(entries, strategy_id)
            if entry.get("status") != "VALIDATED":
                raise ValueError("strategy_not_validated")
            if not bool(entry.get("evaluation_passed", False)):
                raise ValueError("strategy_evaluation_not_passed")
            if _safe_int(entry.get("evaluation_sample_count"), 0) < MIN_PROMOTION_SAMPLES:
                raise ValueError("strategy_evidence_samples_too_low")
            if _safe_float(entry.get("evaluation_confidence"), 0.0) < MIN_PROMOTION_CONFIDENCE:
                raise ValueError("strategy_evidence_confidence_too_low")
            if not bool(entry.get("explicit_promotion_approval_required", True)):
                raise ValueError("strategy_promotion_policy_invalid")

            scope = self._activation_scope(entry)
            previous_active: dict[str, Any] | None = None
            for other in entries:
                if other is entry or other.get("status") != "ACTIVE":
                    continue
                if self._activation_scope(other) == scope:
                    previous_active = other
                    break

            if previous_active is not None:
                previous_active["status"] = "SUPERSEDED"
                previous_active["superseded_by_strategy_id"] = entry["strategy_id"]
                previous_active["updated_at"] = now
                entry["replaces_strategy_id"] = _clean(
                    previous_active.get("strategy_id"),
                    MAX_ID_CHARS,
                )

            entry["status"] = "ACTIVE"
            entry["explicit_promotion_approved"] = True
            entry["runtime_application_enabled"] = False
            entry["automatic_activation"] = False
            entry["automatic_promotion"] = False
            entry["promoted_at"] = now
            entry["updated_at"] = now
            state["entries"] = entries
            state["revision"] = max(0, _safe_int(state.get("revision"), 0)) + 1
            state["updated_at"] = now
            self._save(state)
            return dict(entry)

    def rollback(
        self,
        identity_id: str,
        strategy_id: str,
        *,
        explicit_approval: bool,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        if not explicit_approval:
            raise ValueError("explicit_strategy_rollback_approval_required")
        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            entries = [item for item in state.get("entries", []) if isinstance(item, dict)]
            entry = self._find_by_id(entries, strategy_id)
            if entry.get("status") != "ACTIVE":
                raise ValueError("strategy_not_active")

            previous_id = _clean(entry.get("replaces_strategy_id"), MAX_ID_CHARS)
            restored_id = ""
            if previous_id:
                previous = self._find_by_id(entries, previous_id)
                if previous.get("status") != "SUPERSEDED":
                    raise ValueError("strategy_rollback_target_invalid")
                previous["status"] = "ACTIVE"
                previous["superseded_by_strategy_id"] = ""
                previous["runtime_application_enabled"] = False
                previous["updated_at"] = now
                restored_id = previous_id

            entry["status"] = "ROLLED_BACK"
            entry["runtime_application_enabled"] = False
            entry["rolled_back_at"] = now
            entry["updated_at"] = now
            state["entries"] = entries
            state["revision"] = max(0, _safe_int(state.get("revision"), 0)) + 1
            state["updated_at"] = now
            self._save(state)
            result = dict(entry)
            result["restored_strategy_id"] = restored_id
            return result

    def snapshot(
        self,
        identity_id: str,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        with self.lock:
            state = self._load()
            self._bind_identity(state, identity_id)
            return self._snapshot_from_state(state, now)

    def _snapshot_from_state(
        self,
        state: dict[str, Any],
        generated_at: int,
    ) -> dict[str, Any]:
        entries = [item for item in state.get("entries", []) if isinstance(item, dict)]
        ordered = sorted(
            entries,
            key=lambda item: (
                _safe_int(item.get("updated_at"), 0),
                str(item.get("strategy_id", "")),
            ),
            reverse=True,
        )
        visible = []
        for item in ordered[:MAX_SNAPSHOT_ENTRIES]:
            clone = dict(item)
            evaluations = [
                dict(value)
                for value in clone.get("evaluations", [])
                if isinstance(value, dict)
            ]
            clone["evaluations"] = evaluations[-3:]
            visible.append(clone)

        return {
            "schema_version": SCHEMA_VERSION,
            "identity_id": _clean(state.get("identity_id"), MAX_ID_CHARS),
            "registry_revision": max(0, _safe_int(state.get("revision"), 0)),
            "generated_at": generated_at,
            "updated_at": max(0, _safe_int(state.get("updated_at"), 0)),
            "entry_count": len(entries),
            "candidate_count": sum(1 for item in entries if item.get("status") == "CANDIDATE"),
            "validated_count": sum(1 for item in entries if item.get("status") == "VALIDATED"),
            "active_count": sum(1 for item in entries if item.get("status") == "ACTIVE"),
            "rejected_count": sum(1 for item in entries if item.get("status") == "REJECTED"),
            "entries": visible,
            "automatic_candidate_persistence": True,
            "automatic_promotion": False,
            "automatic_runtime_application": False,
            "runtime_application_performed": False,
            "production_code_rewrite": False,
            "shell_execution": False,
            "raw_conversation_text_used": False,
            "user_feedback_promoted_to_external_fact": False,
        }
