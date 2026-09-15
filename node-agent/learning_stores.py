"""Canonical zone-aware learning stores for Jade Genesis 0.1.21.

Low-level stores remain independently testable and accept explicit paths. This
module is the production factory: callers that participate in learning must use
these constructors so workshop, archive and production state cannot silently
collapse back into the legacy flat config directory.

The canonical archive ledger additionally writes an immutable complete case
pack whenever a dataset becomes sealed. The materialized ledger may evolve, but
the sealed evidence behind an exam is preserved under archive/case-packs/.

Production factories deliberately share one re-entrant lock per canonical file
path. Proof-critical reads fail closed: neither a transient OSError nor readable
corruption may silently restore revision N-1 from a backup. Backups are retained
for explicit operator recovery only.
"""

from __future__ import annotations

import shutil
from pathlib import Path
from typing import Any

import skill_registry as _skill_registry_module
from case_pack_archive import archive_sealed_case_pack
from learning_environment import (
    ARCHIVE_ATTRIBUTION_LOG_PATH,
    ARCHIVE_SKILL_REGISTRY_PATH,
    ARCHIVE_TASK_LEDGER_PATH,
    CONFIG_DIR,
    PRODUCTION_SKILL_ROUTES_PATH,
    WORKSHOP_GOALS_PATH,
    ensure_learning_environment,
)
from proof_store_hardening import (
    HardenedLearningGoalStore,
    HardenedSkillRegistry,
    secure_json_save,
    strict_json_load,
)
from resilient_skill_synthesis import ResilientSkillSynthesisLoop
from shared_store_lock import (
    atomic_copy_file,
    ensure_private_store_path,
    shared_path_lock,
)
from verifiable_task_ledger import VerifiableTaskLedger

LEGACY_TASK_LEDGER_PATH = CONFIG_DIR / "verifiable-task-ledger.json"
LEGACY_LEARNING_GOALS_PATH = CONFIG_DIR / "skill-learning-goals.json"


def _migrate_file_once(source: Path, destination: Path) -> bool:
    """Copy legacy state into its zone once, never delete the legacy source."""

    lock = shared_path_lock(destination)
    with lock:
        if destination.exists() or not source.exists():
            return False
        destination.parent.mkdir(parents=True, exist_ok=True)
        atomic_copy_file(source, destination, mode=0o600)
        ensure_private_store_path(destination)
        return True


class ArchiveVerifiableTaskLedger(VerifiableTaskLedger):
    """Canonical verifier ledger with shared locking and fail-closed reads."""

    def __init__(self, path: Path = ARCHIVE_TASK_LEDGER_PATH):
        super().__init__(path)
        self.lock = shared_path_lock(self.path)
        ensure_private_store_path(self.path)

    @staticmethod
    def _valid_state(raw: dict[str, Any]) -> bool:
        return (
            raw.get("schema_version") == 1
            and isinstance(raw.get("datasets", {}), dict)
            and isinstance(raw.get("attempts", []), list)
        )

    def _load(self) -> dict[str, Any]:
        return strict_json_load(
            self.path,
            empty_factory=self._empty,
            validator=self._valid_state,
            error_prefix="verifiable_task_ledger",
        )

    def _save(self, state: dict[str, Any]) -> None:
        secure_json_save(self.path, self.backup_path, state)

    def seal_dataset(
        self,
        identity_id: str,
        dataset_id: str,
        now_ms: int | None = None,
    ) -> dict[str, Any]:
        # Keep sealing and immutable case-pack materialization under the same
        # canonical ledger lock. The RLock is re-entrant through super().
        with self.lock:
            manifest = super().seal_dataset(identity_id, dataset_id, now_ms=now_ms)
            pack = archive_sealed_case_pack(self, identity_id, dataset_id)
            return {
                **manifest,
                "case_pack_sha256": pack["case_pack_sha256"],
                "case_pack_immutable": True,
                "case_pack_path_exposed_to_teacher": False,
            }


def open_archive_ledger() -> ArchiveVerifiableTaskLedger:
    ensure_learning_environment()
    _migrate_file_once(LEGACY_TASK_LEDGER_PATH, ARCHIVE_TASK_LEDGER_PATH)
    return ArchiveVerifiableTaskLedger(ARCHIVE_TASK_LEDGER_PATH)


def open_workshop_goals() -> HardenedLearningGoalStore:
    ensure_learning_environment()
    _migrate_file_once(LEGACY_LEARNING_GOALS_PATH, WORKSHOP_GOALS_PATH)
    return HardenedLearningGoalStore(WORKSHOP_GOALS_PATH)


def open_archive_skill_registry() -> HardenedSkillRegistry:
    ensure_learning_environment()
    registry = HardenedSkillRegistry(
        ARCHIVE_SKILL_REGISTRY_PATH,
        route_path=PRODUCTION_SKILL_ROUTES_PATH,
        attribution_path=ARCHIVE_ATTRIBUTION_LOG_PATH,
    )
    # skill_registry_status() owns a module-global instance created during its
    # import. Point it at the same hardened implementation so /health cannot
    # bypass shared locking or fail-closed read semantics.
    _skill_registry_module._GLOBAL_SKILL_REGISTRY = registry
    return registry


def open_skill_synthesis_loop() -> ResilientSkillSynthesisLoop:
    return ResilientSkillSynthesisLoop(
        open_archive_ledger(),
        open_archive_skill_registry(),
        open_workshop_goals(),
    )


def zone_store_status() -> dict[str, Any]:
    ensure_learning_environment()
    ledger = open_archive_ledger()
    goals = open_workshop_goals()
    registry = open_archive_skill_registry()
    return {
        "ledger_path": str(ledger.path),
        "goals_path": str(goals.path),
        "skill_archive_path": str(registry.path),
        "production_route_path": str(registry.routes.path),
        "ledger_in_archive": ledger.path == ARCHIVE_TASK_LEDGER_PATH,
        "goals_in_workshop": goals.path == WORKSHOP_GOALS_PATH,
        "skill_archive_separate_from_production": registry.path != registry.routes.path,
        "sealed_case_pack_write_once": True,
        "shared_lock_per_canonical_path": True,
        "proof_store_reads_fail_closed": True,
        "automatic_backup_rollback": False,
        "ledger_unique_atomic_temp": True,
        "proof_store_private_mode": True,
        "frozen_candidate_persisted_before_exam": True,
        "registry_preflight_before_exam": True,
        "legacy_ledger_preserved": LEGACY_TASK_LEDGER_PATH.exists(),
        "legacy_goals_preserved": LEGACY_LEARNING_GOALS_PATH.exists(),
    }
