"""Bounded VPS supervision for Jade Genesis Night Cycle.

The supervisor works only from the durable Shared Genesis State replica. It
reviews synchronized memory, Runtime Eval and Evolution summaries, then writes
a report back to the same event stream. It cannot promote a candidate, change
SafetyPolicy, execute a shell command or reach into the Pixel.
"""

from __future__ import annotations

import json
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Callable

from shared_genesis_state import CONFIG_DIR, SharedGenesisStateStore, _GLOBAL_STORE

SCHEMA_VERSION = 1
MAX_RUNS = 30
MAX_STEPS = 12
MAX_STEP_SUMMARY_CHARS = 500
MAX_REVIEW_ITEMS = 40
MIN_CYCLE_INTERVAL_MS = 20 * 60 * 60 * 1_000
PIXEL_INACTIVE_AFTER_MS = 2 * 60 * 60 * 1_000
CHECK_INTERVAL_SECONDS = 15 * 60
JOURNAL_PATH = CONFIG_DIR / "night-cycle-supervisor.json"

LogFunction = Callable[..., None]


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


def _payload(event: dict[str, Any] | None) -> dict[str, Any]:
    if not event:
        return {}
    try:
        parsed = json.loads(str(event.get("payload", "")))
    except json.JSONDecodeError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _latest_entity(view: dict[str, Any], kind: str) -> dict[str, Any] | None:
    candidates = [
        item
        for item in view.get("entities", [])
        if isinstance(item, dict) and item.get("kind") == kind
    ]
    if not candidates:
        return None
    return max(
        candidates,
        key=lambda item: (
            _safe_int(item.get("created_at"), 0),
            str(item.get("event_id", "")),
        ),
    )


def _last_shared_protected_completed_at(view: dict[str, Any]) -> int:
    completed_at = 0
    for item in view.get("entities", []):
        if not isinstance(item, dict) or item.get("kind") != "vps_night_cycle_report":
            continue
        report = _payload(item)
        if report.get("status") not in {"SUCCESS", "PARTIAL"}:
            continue
        completed_at = max(
            completed_at,
            _safe_int(report.get("completed_at"), 0),
        )
    return completed_at


class NightCycleJournal:
    def __init__(self, path: Path = JOURNAL_PATH):
        self.path = path
        self.backup_path = path.with_suffix(path.suffix + ".bak")
        self.lock = threading.RLock()

    def _decode(self, path: Path) -> list[dict[str, Any]] | None:
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return None
        if not isinstance(raw, dict) or raw.get("schema_version") != SCHEMA_VERSION:
            return None
        runs = raw.get("runs", [])
        if not isinstance(runs, list):
            return None
        return [item for item in runs if isinstance(item, dict)][:MAX_RUNS]

    def load(self) -> list[dict[str, Any]]:
        with self.lock:
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
            return []

    def save(self, run: dict[str, Any]) -> None:
        with self.lock:
            runs = [
                item for item in self.load()
                if str(item.get("run_id", "")) != str(run.get("run_id", ""))
            ]
            runs.insert(0, dict(run))
            encoded = json.dumps(
                {
                    "schema_version": SCHEMA_VERSION,
                    "runs": runs[:MAX_RUNS],
                },
                ensure_ascii=False,
                separators=(",", ":"),
                sort_keys=True,
            )
            self.path.parent.mkdir(parents=True, exist_ok=True)
            temp = self.path.with_suffix(self.path.suffix + ".tmp")
            temp.write_text(encoded, encoding="utf-8")
            if self.path.exists() and self._decode(self.path) is not None:
                try:
                    self.backup_path.write_bytes(self.path.read_bytes())
                except OSError:
                    pass
            temp.replace(self.path)

    def last_protected_completed_at(self) -> int:
        for run in self.load():
            if run.get("status") in {"SUCCESS", "PARTIAL"}:
                return max(0, _safe_int(run.get("completed_at"), 0))
        return 0


class VpsNightCyclePolicy:
    @staticmethod
    def decision(
        view: dict[str, Any],
        last_protected_completed_at: int,
        now_ms: int,
        force: bool = False,
    ) -> tuple[bool, str]:
        if not str(view.get("identity_id", "")).strip():
            return False, "shared_state_identity_unbound"

        phone = _latest_entity(view, "phone_node_snapshot")
        if phone is None:
            return False, "phone_presence_unknown"
        phone_payload = _payload(phone)
        observed_at = max(
            _safe_int(phone_payload.get("observed_at"), 0),
            _safe_int(phone_payload.get("captured_at"), 0),
            _safe_int(phone.get("created_at"), 0),
        )
        if observed_at <= 0:
            return False, "phone_presence_invalid"

        if not force and now_ms - observed_at < PIXEL_INACTIVE_AFTER_MS:
            return False, "pixel_active"
        if not force and last_protected_completed_at > 0:
            elapsed = now_ms - last_protected_completed_at
            if elapsed < MIN_CYCLE_INTERVAL_MS:
                return False, "cadence_guard"
        return True, "eligible"


class VpsNightCycleSupervisor:
    def __init__(
        self,
        config: dict[str, Any],
        state_store: SharedGenesisStateStore = _GLOBAL_STORE,
        journal: NightCycleJournal | None = None,
        logger: LogFunction | None = None,
    ):
        self.config = dict(config)
        self.state_store = state_store
        self.journal = journal or NightCycleJournal()
        self.logger = logger
        self._run_lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._last_attempt: dict[str, Any] = {}

    @property
    def enabled(self) -> bool:
        return str(self.config.get("node_kind", "")).upper() == "VPS"

    def start(self) -> None:
        if not self.enabled or (self._thread and self._thread.is_alive()):
            return
        self._stop.clear()
        self._thread = threading.Thread(
            target=self._loop,
            daemon=True,
            name="jade-vps-night-cycle",
        )
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        thread = self._thread
        if thread and thread.is_alive():
            thread.join(timeout=2.0)

    def status(self) -> dict[str, Any]:
        recent = self.journal.load()
        latest = recent[0] if recent else {}
        return {
            "schema_version": SCHEMA_VERSION,
            "enabled": self.enabled,
            "running": bool(self._thread and self._thread.is_alive()),
            "mode": "shared_state_review_only",
            "pixel_inactive_after_ms": PIXEL_INACTIVE_AFTER_MS,
            "minimum_cycle_interval_ms": MIN_CYCLE_INTERVAL_MS,
            "last_attempt_status": str(self._last_attempt.get("status", "")),
            "last_attempt_reason": str(self._last_attempt.get("reason", "")),
            "last_attempt_at": _safe_int(self._last_attempt.get("at"), 0),
            "last_run_id": str(latest.get("run_id", "")),
            "last_run_status": str(latest.get("status", "")),
            "last_completed_at": _safe_int(latest.get("completed_at"), 0),
            "promotion_automatic": False,
            "arbitrary_shell": False,
        }

    def run_once(
        self,
        force: bool = False,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        now = _now_ms() if now_ms is None else max(0, int(now_ms))
        if not self.enabled:
            return self._skipped(now, "requires_vps")
        if not self._run_lock.acquire(blocking=False):
            return self._skipped(now, "already_running")
        try:
            view = self.state_store.supervision_view()
            last_protected = max(
                self.journal.last_protected_completed_at(),
                _last_shared_protected_completed_at(view),
            )
            allowed, reason = VpsNightCyclePolicy.decision(
                view=view,
                last_protected_completed_at=last_protected,
                now_ms=now,
                force=force,
            )
            if not allowed:
                return self._skipped(now, reason)
            return self._execute(view, now)
        finally:
            self._run_lock.release()

    def _execute(self, view: dict[str, Any], started_at: int) -> dict[str, Any]:
        run_id = f"vps-night-{uuid.uuid4()}"
        steps: list[dict[str, Any]] = []

        steps.append(self._step(
            "SHARED_STATE_BEFORE",
            True,
            f"Réplique vérifiée à la révision {_safe_int(view.get('revision'), 0)}; "
            f"{_safe_int(view.get('event_count'), 0)} événement(s) durable(s).",
        ))

        memory = _payload(_latest_entity(view, "memory_cursor"))
        memory_ok = bool(memory)
        steps.append(self._step(
            "MEMORY_REVIEW",
            memory_ok,
            (
                "Curseur Memory v2 revu; maintenance destructive interdite sur la réplique."
                if memory_ok else
                "Aucun curseur Memory v2 synchronisé; la mémoire du Pixel reste inchangée."
            ),
        ))

        runtime = _payload(_latest_entity(view, "runtime_eval_snapshot"))
        runtime_ok = bool(runtime)
        observation_count = max(0, _safe_int(runtime.get("observation_count"), 0))
        runtime_score = min(100.0, max(0.0, _safe_float(runtime.get("score"), 0.0)))
        runtime_confidence = min(1.0, max(0.0, _safe_float(runtime.get("confidence"), 0.0)))
        steps.append(self._step(
            "RUNTIME_EVAL_REVIEW",
            runtime_ok,
            (
                f"Runtime Eval revu: {observation_count} observation(s), "
                f"score {runtime_score:.1f}/100, confiance {runtime_confidence * 100.0:.1f} %."
                if runtime_ok else
                "Aucun instantané Runtime Eval synchronisé; aucune conclusion inventée."
            ),
        ))

        evolution = _payload(_latest_entity(view, "evolution_snapshot"))
        evolution_ok = bool(evolution)
        candidates = evolution.get("candidates", [])
        if not isinstance(candidates, list):
            candidates = []
            evolution_ok = False
        candidates = [item for item in candidates if isinstance(item, dict)][:MAX_REVIEW_ITEMS]
        validated = sum(1 for item in candidates if item.get("status") == "VALIDATED")
        testing = sum(1 for item in candidates if item.get("status") == "TESTING")
        proposed = sum(1 for item in candidates if item.get("status") == "PROPOSED")
        steps.append(self._step(
            "EVOLUTION_REVIEW",
            evolution_ok,
            (
                f"Evolution Engine revu: {len(candidates)} candidat(s), {proposed} proposé(s), "
                f"{testing} en test, {validated} validé(s) en attente d'approbation. "
                "Aucune promotion automatique."
                if evolution_ok else
                "Aucun instantané Evolution Engine synchronisé; aucune promotion automatique."
            ),
        ))

        insights: list[str] = []
        if runtime_ok and runtime_confidence < 0.75:
            insights.append("collect_more_runtime_evidence")
        if validated > 0:
            insights.append("await_explicit_user_approval")
        if not memory_ok:
            insights.append("await_memory_cursor_sync")
        if not insights:
            insights.append("continue_bounded_observation")
        steps.append(self._step(
            "MAINTENANCE_LEARNING",
            True,
            "Recommandations bornées enregistrées: " + ", ".join(insights) + ".",
        ))

        status = "SUCCESS" if all(step["success"] for step in steps) else "PARTIAL"
        completed_at = max(started_at, _now_ms())
        report = {
            "schema_version": SCHEMA_VERSION,
            "run_id": run_id,
            "status": status,
            "started_at": started_at,
            "completed_at": completed_at,
            "source_revision": _safe_int(view.get("revision"), 0),
            "runtime_observation_count": observation_count,
            "runtime_score": runtime_score,
            "runtime_confidence": runtime_confidence,
            "evolution_candidate_count": len(candidates),
            "evolution_validated_count": validated,
            "insights": insights,
            "promotion_performed": False,
            "shell_execution_performed": False,
            "steps": steps[:MAX_STEPS],
        }

        try:
            maintenance_snapshot = {
                "schema_version": SCHEMA_VERSION,
                "run_id": run_id,
                "source_revision": report["source_revision"],
                "reviewed_at": completed_at,
                "memory_cursor_available": memory_ok,
                "runtime_observation_count": observation_count,
                "runtime_score": runtime_score,
                "runtime_confidence": runtime_confidence,
                "evolution_candidate_count": len(candidates),
                "evolution_validated_count": validated,
                "insights": insights,
                "promotion_performed": False,
                "shell_execution_performed": False,
            }
            self.state_store.append_replica_event(
                identity_id=str(view["identity_id"]),
                replica_id=str(self.config.get("node_id", "vps-supervisor")),
                kind="vps_maintenance_snapshot",
                entity_id="current",
                payload=json.dumps(
                    maintenance_snapshot,
                    ensure_ascii=False,
                    separators=(",", ":"),
                ),
                created_at=completed_at,
            )
            self.state_store.append_replica_event(
                identity_id=str(view["identity_id"]),
                replica_id=str(self.config.get("node_id", "vps-supervisor")),
                kind="vps_night_cycle_report",
                entity_id=run_id,
                payload=json.dumps(report, ensure_ascii=False, separators=(",", ":")),
                created_at=completed_at,
            )
            steps.append(self._step(
                "SHARED_STATE_AFTER",
                True,
                "Maintenance et rapport du cycle VPS ajoutés à Shared Genesis State pour la prochaine synchronisation du Pixel.",
            ))
        except Exception as exc:
            status = "PARTIAL"
            report["status"] = status
            steps.append(self._step(
                "SHARED_STATE_AFTER",
                False,
                f"Rapport Shared State non publié: {type(exc).__name__}.",
            ))

        report["steps"] = steps[:MAX_STEPS]
        report["status"] = status
        self.journal.save(report)
        self._last_attempt = {"status": status, "reason": "completed", "at": report["completed_at"]}
        self._log(
            "INFO" if status == "SUCCESS" else "WARN",
            "vps_night_cycle_complete",
            f"Cycle VPS {status.lower()}.",
            run_id=run_id,
            source_revision=report["source_revision"],
            promotion_performed=False,
            shell_execution_performed=False,
        )
        return report

    def _skipped(self, now: int, reason: str) -> dict[str, Any]:
        result = {
            "schema_version": SCHEMA_VERSION,
            "run_id": "",
            "status": "SKIPPED",
            "reason": reason,
            "started_at": now,
            "completed_at": now,
            "promotion_performed": False,
            "shell_execution_performed": False,
            "steps": [],
        }
        self._last_attempt = {"status": "SKIPPED", "reason": reason, "at": now}
        return result

    @staticmethod
    def _step(phase: str, success: bool, summary: str) -> dict[str, Any]:
        return {
            "phase": phase,
            "success": bool(success),
            "summary": str(summary).replace("\r", " ").replace("\n", " ")[:MAX_STEP_SUMMARY_CHARS],
        }

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                self.run_once()
            except Exception as exc:
                self._last_attempt = {
                    "status": "FAILED",
                    "reason": type(exc).__name__,
                    "at": _now_ms(),
                }
                self._log(
                    "ERROR",
                    "vps_night_cycle_failed",
                    "Le superviseur Night Cycle VPS a échoué sans arrêter le runtime.",
                    error=type(exc).__name__,
                )
            self._stop.wait(CHECK_INTERVAL_SECONDS)

    def _log(self, level: str, event: str, message: str, **metadata: Any) -> None:
        if self.logger is not None:
            self.logger(level, event, message, **metadata)


_SUPERVISOR_LOCK = threading.Lock()
_SUPERVISOR: VpsNightCycleSupervisor | None = None


def start_supervisor(config: dict[str, Any], logger: LogFunction | None = None) -> None:
    global _SUPERVISOR
    with _SUPERVISOR_LOCK:
        if _SUPERVISOR is None:
            _SUPERVISOR = VpsNightCycleSupervisor(config=config, logger=logger)
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
    return {
        "schema_version": SCHEMA_VERSION,
        "enabled": str(config.get("node_kind", "")).upper() == "VPS",
        "running": False,
        "mode": "shared_state_review_only",
        "promotion_automatic": False,
        "arbitrary_shell": False,
    }
