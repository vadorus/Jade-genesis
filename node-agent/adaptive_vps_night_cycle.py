"""0.1.18 adaptive layer for the bounded VPS Night Cycle.

This wrapper preserves the proven 0.1.17 supervisor and adds one post-cycle
operation: safe STRATEGY_HINT candidates are persisted in Jade's own adaptive
strategy registry. Persisting evidence is not promotion. No strategy becomes
active unless a separate explicit approval calls the registry promotion API.
"""

from __future__ import annotations

import json
import threading
from typing import Any, Callable

from adaptive_strategy_registry import (
    AdaptiveStrategyRegistry,
    _GLOBAL_REGISTRY,
    adaptive_strategy_registry_status,
)
from vps_night_cycle import (
    NightCycleJournal,
    VpsNightCycleSupervisor,
    _latest_entity,
    _payload,
    _safe_int,
)
from shared_genesis_state import SharedGenesisStateStore, _GLOBAL_STORE

SCHEMA_VERSION = 1
MAX_SHARED_STRATEGIES = 20
LogFunction = Callable[..., None]


class AdaptiveVpsNightCycleSupervisor(VpsNightCycleSupervisor):
    """Preserve base Night Cycle behavior and persist bounded strategy evidence."""

    def __init__(
        self,
        config: dict[str, Any],
        state_store: SharedGenesisStateStore = _GLOBAL_STORE,
        journal: NightCycleJournal | None = None,
        logger: LogFunction | None = None,
        learning_lab=None,
        strategy_registry: AdaptiveStrategyRegistry = _GLOBAL_REGISTRY,
    ):
        super().__init__(
            config=config,
            state_store=state_store,
            journal=journal,
            logger=logger,
            learning_lab=learning_lab,
        )
        self.strategy_registry = strategy_registry
        self._last_registry_result: dict[str, Any] = {}

    def status(self) -> dict[str, Any]:
        result = super().status()
        registry = self.strategy_registry.status()
        result["mode"] = "bounded_learning_lab+adaptive_strategy_registry"
        result["adaptive_strategy_registry"] = registry
        result["strategy_registry_revision"] = _safe_int(
            registry.get("registry_revision"),
            0,
        )
        result["strategy_count"] = _safe_int(registry.get("strategy_count"), 0)
        result["active_strategy_count"] = _safe_int(
            registry.get("active_strategy_count"),
            0,
        )
        result["strategy_persistence_automatic"] = True
        result["strategy_activation_automatic"] = False
        result["strategy_promotion_automatic"] = False
        result["strategy_model_weight_mutation"] = False
        return result

    def run_once(
        self,
        force: bool = False,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        result = super().run_once(force=force, now_ms=now_ms)
        if result.get("status") not in {"SUCCESS", "PARTIAL"}:
            return result
        if not str(result.get("run_id", "")).strip():
            return result

        try:
            registry_result = self._persist_latest_strategy_candidates(result)
            self._last_registry_result = registry_result
            result.update({
                "strategy_registry_status": "OK",
                "strategy_registry_revision": registry_result["registry_revision"],
                "strategy_count": registry_result["strategy_count"],
                "strategy_candidates_accepted": registry_result["accepted"],
                "strategy_candidates_created": registry_result["created"],
                "strategy_candidates_reinforced": registry_result["reinforced"],
                "strategy_activation_performed": False,
                "strategy_promotion_performed": False,
            })
        except Exception as exc:
            self._last_registry_result = {
                "status": "ERROR",
                "error": type(exc).__name__,
            }
            result.update({
                "strategy_registry_status": "ERROR",
                "strategy_registry_error": type(exc).__name__,
                "strategy_activation_performed": False,
                "strategy_promotion_performed": False,
            })
            self._log(
                "WARN",
                "adaptive_strategy_registry_failed",
                "Le cycle principal est terminé mais la persistance du registre adaptatif a échoué.",
                error=type(exc).__name__,
                promotion_performed=False,
            )
        return result

    def _persist_latest_strategy_candidates(
        self,
        cycle_result: dict[str, Any],
    ) -> dict[str, Any]:
        view = self.state_store.supervision_view()
        identity_id = str(view.get("identity_id", "")).strip()
        if not identity_id:
            raise ValueError("shared_state_identity_unbound")

        learning_event = _latest_entity(view, "vps_learning_snapshot")
        learning = _payload(learning_event)
        candidates = learning.get("improvement_candidates", [])
        if not isinstance(candidates, list):
            candidates = []
        source_revision = max(
            0,
            _safe_int(learning.get("source_revision"), _safe_int(view.get("revision"), 0)),
        )
        completed_at = max(0, _safe_int(cycle_result.get("completed_at"), 0))

        registry_result = self.strategy_registry.ingest_candidates(
            identity_id=identity_id,
            candidates=[item for item in candidates if isinstance(item, dict)],
            source_revision=source_revision,
            now_ms=completed_at,
        )
        snapshot = self.strategy_registry.snapshot(identity_id)
        public_entries = []
        for entry in snapshot.get("entries", [])[:MAX_SHARED_STRATEGIES]:
            if not isinstance(entry, dict):
                continue
            public_entries.append({
                "strategy_id": str(entry.get("strategy_id", ""))[:160],
                "kind": str(entry.get("kind", ""))[:80],
                "status": str(entry.get("status", ""))[:40],
                "title": str(entry.get("title", ""))[:180],
                "target": str(entry.get("target", ""))[:160],
                "scope": entry.get("scope", {}) if isinstance(entry.get("scope"), dict) else {},
                "score": entry.get("score", 0.0),
                "confidence": entry.get("confidence", 0.0),
                "evidence_count": _safe_int(entry.get("evidence_count"), 0),
                "sandbox_samples": _safe_int(entry.get("sandbox_samples"), 0),
                "protected_failures": _safe_int(entry.get("protected_failures"), 0),
                "automatic_activation": False,
                "automatic_promotion": False,
            })

        shared_snapshot = {
            "schema_version": SCHEMA_VERSION,
            "snapshot_kind": "adaptive_strategy_registry",
            "run_id": str(cycle_result.get("run_id", ""))[:160],
            "reviewed_at": completed_at,
            "registry_revision": snapshot["registry_revision"],
            "strategy_count": snapshot["strategy_count"],
            "candidate_strategy_count": snapshot["candidate_strategy_count"],
            "testing_strategy_count": snapshot["testing_strategy_count"],
            "validated_strategy_count": snapshot["validated_strategy_count"],
            "active_strategy_count": snapshot["active_strategy_count"],
            "entries": public_entries,
            "raw_conversation_text_stored": False,
            "automatic_activation": False,
            "automatic_promotion": False,
            "production_code_rewrite": False,
            "model_weight_mutation": False,
            "shell_execution": False,
        }
        self.state_store.append_replica_event(
            identity_id=identity_id,
            replica_id=str(self.config.get("node_id", "vps-supervisor")),
            kind="vps_maintenance_snapshot",
            entity_id="adaptive-strategy-registry",
            payload=json.dumps(
                shared_snapshot,
                ensure_ascii=False,
                separators=(",", ":"),
            ),
            created_at=completed_at,
        )
        return registry_result


_SUPERVISOR_LOCK = threading.Lock()
_SUPERVISOR: AdaptiveVpsNightCycleSupervisor | None = None


def start_supervisor(config: dict[str, Any], logger: LogFunction | None = None) -> None:
    global _SUPERVISOR
    with _SUPERVISOR_LOCK:
        if _SUPERVISOR is None:
            _SUPERVISOR = AdaptiveVpsNightCycleSupervisor(config=config, logger=logger)
        _SUPERVISOR.start()


def stop_supervisor() -> None:
    global _SUPERVISOR
    with _SUPERVISOR_LOCK:
        supervisor = _SUPERVISOR
        _SUPERVISOR = None
    if supervisor is not None:
        supervisor.stop()


def supervisor_status(config: dict[str, Any]) -> dict[str, Any]:
    with _SUPERVISOR_LOCK:
        if _SUPERVISOR is not None:
            return _SUPERVISOR.status()
    registry = adaptive_strategy_registry_status()
    return {
        "schema_version": SCHEMA_VERSION,
        "enabled": str(config.get("node_kind", "")).upper() == "VPS",
        "running": False,
        "mode": "bounded_learning_lab+adaptive_strategy_registry",
        "external_research_bounded": True,
        "experiment_automatic": False,
        "promotion_automatic": False,
        "arbitrary_shell": False,
        "adaptive_strategy_registry": registry,
        "strategy_registry_revision": _safe_int(registry.get("registry_revision"), 0),
        "strategy_count": _safe_int(registry.get("strategy_count"), 0),
        "active_strategy_count": _safe_int(registry.get("active_strategy_count"), 0),
        "strategy_persistence_automatic": True,
        "strategy_activation_automatic": False,
        "strategy_promotion_automatic": False,
        "strategy_model_weight_mutation": False,
    }
