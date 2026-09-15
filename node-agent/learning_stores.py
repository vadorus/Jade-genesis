"""Canonical zone-aware learning stores for Jade Genesis 0.1.21.

Low-level stores remain independently testable and accept explicit paths. This
module is the production factory: callers that participate in learning must use
these constructors so workshop, archive and production state cannot silently
collapse back into the legacy flat config directory.

The canonical archive ledger additionally writes an immutable complete case
pack whenever a dataset becomes sealed. The materialized ledger may evolve, but
the sealed evidence behind an exam is preserved under archive/case-packs/.

Production factories deliberately share one re-entrant lock per canonical file
path. Reopening a store must not create an independent lock around the same JSON
file. The archive ledger also uses unique atomic replacement files so concurrent
writers never fight over one fixed `.tmp` pathname.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from case_pack_archive import archive_sealed_case_pack
from learning_environment import (
    ARCHIVE_SKILL_REGISTRY_PATH,
    ARCHIVE_TASK_LEDGER_PATH,
    CONFIG_DIR,
    PRODUCTION_SKILL_ROUTES_PATH,
    WORKSHOP_GOALS_PATH,
    ensure_learning_environment,
)
from shared_store_lock import atomic_copy_file, atomic_write_text, shared_path_lock
from skill_registry import SkillRegistry
from skill_synthesis_loop import LearningGoalStore, SkillSynthesisLoop
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
        atomic_copy_file(source, destination)
        return True


class ArchiveVerifiableTaskLedger(VerifiableTaskLedger):
    """Canonical verifier ledger with shared locking and write-once case packs."""

    def __init__(self, path: Path = ARCHIVE_TASK_LEDGER_PATH):
        super().__init__(path)
        self.lock = shared_path_lock(self.path)

    def _load(self) -> dict[str, Any]:
        if not self.path.exists():
            return self._empty()
        primary = self._decode(self.path)
        if primary is not None:
            return primary
        backup = self._decode(self.backup_path)
        if backup is not None:
            try:
                atomic_copy_file(self.backup_path, self.path)
            except OSError:
                pass
            return backup
        raise RuntimeError("verifiable_task_ledger_corrupt")

    def _save(self, state: dict[str, Any]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        encoded = json.dumps(
            state,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        if self.path.exists() and self._decode(self.path) is not None:
            try:
                atomic_copy_file(self.path, self.backup_path)
            except OSError:
                pass
        atomic_write_text(self.path, encoded)

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


def open_workshop_goals() -> LearningGoalStore:
    ensure_learning_environment()
    _migrate_file_once(LEGACY_LEARNING_GOALS_PATH, WORKSHOP_GOALS_PATH)
    lock = shared_path_lock(WORKSHOP_GOALS_PATH)
    with lock:
        goals = LearningGoalStore(WORKSHOP_GOALS_PATH)
        goals.lock = lock
        return goals


def open_archive_skill_registry() -> SkillRegistry:
    ensure_learning_environment()
    # SkillRegistry owns its own legacy migration because its old file also
    # contained production routes that now need to be split safely. Serialize
    # construction as well because migration itself may write both files.
    registry_lock = shared_path_lock(ARCHIVE_SKILL_REGISTRY_PATH)
    route_lock = shared_path_lock(PRODUCTION_SKILL_ROUTES_PATH)
    with registry_lock:
        with route_lock:
            registry = SkillRegistry()
            registry.lock = registry_lock
            registry.routes.lock = route_lock
            return registry


def open_skill_synthesis_loop() -> SkillSynthesisLoop:
    return SkillSynthesisLoop(
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
        "ledger_unique_atomic_temp": True,
        "legacy_ledger_preserved": LEGACY_TASK_LEDGER_PATH.exists(),
        "legacy_goals_preserved": LEGACY_LEARNING_GOALS_PATH.exists(),
    }
