"""Canonical zone-aware learning stores for Jade Genesis 0.1.21.

Low-level stores remain independently testable and accept explicit paths. This
module is the production factory: callers that participate in learning must use
these constructors so workshop, archive and production state cannot silently
collapse back into the legacy flat config directory.
"""

from __future__ import annotations

import shutil
from pathlib import Path
from typing import Any

from learning_environment import (
    ARCHIVE_TASK_LEDGER_PATH,
    CONFIG_DIR,
    WORKSHOP_GOALS_PATH,
    ensure_learning_environment,
)
from skill_registry import SkillRegistry
from skill_synthesis_loop import LearningGoalStore, SkillSynthesisLoop
from verifiable_task_ledger import VerifiableTaskLedger

LEGACY_TASK_LEDGER_PATH = CONFIG_DIR / "verifiable-task-ledger.json"
LEGACY_LEARNING_GOALS_PATH = CONFIG_DIR / "skill-learning-goals.json"


def _migrate_file_once(source: Path, destination: Path) -> bool:
    """Copy legacy state into its zone once, never delete the legacy source."""

    if destination.exists() or not source.exists():
        return False
    destination.parent.mkdir(parents=True, exist_ok=True)
    temp = destination.with_suffix(destination.suffix + ".migrating")
    shutil.copy2(source, temp)
    temp.replace(destination)
    return True


def open_archive_ledger() -> VerifiableTaskLedger:
    ensure_learning_environment()
    _migrate_file_once(LEGACY_TASK_LEDGER_PATH, ARCHIVE_TASK_LEDGER_PATH)
    return VerifiableTaskLedger(ARCHIVE_TASK_LEDGER_PATH)


def open_workshop_goals() -> LearningGoalStore:
    ensure_learning_environment()
    _migrate_file_once(LEGACY_LEARNING_GOALS_PATH, WORKSHOP_GOALS_PATH)
    return LearningGoalStore(WORKSHOP_GOALS_PATH)


def open_archive_skill_registry() -> SkillRegistry:
    ensure_learning_environment()
    # SkillRegistry owns its own legacy migration because its old file also
    # contained production routes that now need to be split safely.
    return SkillRegistry()


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
        "legacy_ledger_preserved": LEGACY_TASK_LEDGER_PATH.exists(),
        "legacy_goals_preserved": LEGACY_LEARNING_GOALS_PATH.exists(),
    }
