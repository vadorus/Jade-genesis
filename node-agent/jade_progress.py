"""Read-only, bounded progress report over existing Jade node-agent stores.

Never instantiate a store class here: several existing _load() methods migrate or
restore files and are unsuitable for read-only reporting.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
DAY_MS = 86_400_000
MAX_DAYS = 365
MAX_JSONL_RECORDS = 100_000
MAX_JSONL_LINE_BYTES = 2_000_000

SOURCES = {
    "goals": "workshop/skill-learning-goals.json",
    "ledger": "archive/verifiable-task-ledger.json",
    "attribution": "archive/skill-attribution.jsonl",
    "strategies": "adaptive-strategy-registry.json",
    "cycles": "night-cycle-supervisor.json",
    "halt_state": "jade-halt.json",
    "halt_events": "jade-halt.jsonl",
}
PHASES = frozenset({
    "SHARED_STATE_BEFORE", "MEMORY_REVIEW", "RUNTIME_EVAL_REVIEW",
    "EVOLUTION_REVIEW", "TARGETED_RESEARCH", "HYPOTHESIS_PLANNING",
    "EXPERIMENT_PLANNING", "IMPROVEMENT_CANDIDATES",
    "MAINTENANCE_LEARNING", "SHARED_STATE_AFTER",
})
METRICS = {
    "skills": ("goals_opened", "candidates_proposed", "sealed_passed",
               "sealed_failed", "skills_retained", "teacher_calls"),
    "strategies": ("candidates", "validated", "activated", "retired",
                   "rolled_back"),
    "cycles": ("launched", "succeeded", "partial", "skipped",
               "interrupted_by_halt"),
    "security": ("halt_transitions", "network_guard_violations"),
}


def _stamp(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        return None
    return value


def _in_window(value: Any, start: int, end: int) -> bool:
    stamp = _stamp(value)
    return stamp is not None and start <= stamp < end


def _read_json(root: Path, key: str, field: str | None,
               warnings: set[str]) -> dict[str, Any] | None:
    name = SOURCES[key]
    path = root / name
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        warnings.add("missing:" + name)
        return None
    except (OSError, UnicodeError, ValueError):
        warnings.add("unreadable:" + name)
        return None
    if not isinstance(raw, dict) or raw.get("schema_version") != 1:
        warnings.add("invalid_schema:" + name)
        return None
    if field is not None and not isinstance(raw.get(field), dict if key in {"goals", "ledger"} else list):
        warnings.add("invalid_shape:" + name)
        return None
    return raw


def _read_jsonl(root: Path, key: str,
                warnings: set[str]) -> list[dict[str, Any]] | None:
    name = SOURCES[key]
    path = root / name
    entries: list[dict[str, Any]] = []
    try:
        with path.open("rb") as stream:
            for index, line in enumerate(stream):
                if index >= MAX_JSONL_RECORDS or len(line) > MAX_JSONL_LINE_BYTES:
                    warnings.add("too_large:" + name)
                    return None
                if not line.strip():
                    continue
                record = json.loads(line.decode("utf-8"))
                if not isinstance(record, dict):
                    raise ValueError("record_not_object")
                entries.append(record)
    except FileNotFoundError:
        warnings.add("missing:" + name)
        return None
    except (OSError, UnicodeError, ValueError):
        warnings.add("unreadable:" + name)
        return None
    return entries


def _goal_attempts(goals: dict[str, Any], start: int,
                   end: int) -> int | None:
    """Count documented attempts only if the entire observable goal fits.

    The goal store has no per-attempt timestamps. For goals straddling a
    boundary, never invent a weekly candidate/teacher count.
    """
    count = 0
    for goal in goals.values():
        if not isinstance(goal, dict):
            return None
        created = _stamp(goal.get("created_at"))
        updated = _stamp(goal.get("updated_at"))
        attempts = goal.get("candidate_count")
        if created is None or updated is None or not isinstance(attempts, int):
            return None
        if attempts < 0 or isinstance(attempts, bool):
            return None
        if updated < start or created >= end:
            continue
        if created < start or updated >= end:
            return None
        count += attempts
    return count


def _metrics(root_data: dict[str, Any], start: int,
             end: int, warnings: set[str]) -> dict[str, Any]:
    output: dict[str, Any] = {
        section: {name: None for name in keys}
        for section, keys in METRICS.items()
    }
    goals = root_data["goals"]
    if goals is not None:
        records = goals["goals"]
        if all(isinstance(v, dict) for v in records.values()):
            output["skills"]["goals_opened"] = sum(
                _in_window(item.get("created_at"), start, end)
                for item in records.values()
            )
            total = _goal_attempts(records, start, end)
            output["skills"]["candidates_proposed"] = total
            # Each persisted candidate_count is one completed teacher attempt.
            # Failed or unpersisted teacher calls cannot be reconstructed.
            output["skills"]["teacher_calls"] = total
            if total is None:
                warnings.add("weekly_teacher_attempts_not_reconstructible")
            elif total > 0:
                warnings.add("teacher_calls_persisted_lower_bound")
        else:
            warnings.add("invalid_shape:" + SOURCES["goals"])

    ledger = root_data["ledger"]
    if ledger is not None:
        values = ledger["datasets"]
        if isinstance(values, dict) and all(isinstance(v, dict) for v in values.values()):
            exams = [
                dataset.get("sealed_exam") for dataset in values.values()
                if isinstance(dataset.get("sealed_exam"), dict)
            ]
            if all(_stamp(exam.get("evaluated_at")) is not None
                   and isinstance(exam.get("verdict"), bool) for exam in exams):
                within = [item for item in exams
                          if _in_window(item["evaluated_at"], start, end)]
                output["skills"]["sealed_passed"] = sum(
                    exam["verdict"] is True for exam in within
                )
                output["skills"]["sealed_failed"] = sum(
                    exam["verdict"] is False for exam in within
                )
            else:
                warnings.add("incomplete_exam_metadata")
        else:
            warnings.add("invalid_shape:" + SOURCES["ledger"])

    attribution = root_data["attribution"]
    if attribution is not None:
        if all(_stamp(row.get("occurred_at")) is not None
               and isinstance(row.get("event_kind"), str) for row in attribution):
            output["skills"]["skills_retained"] = sum(
                row["event_kind"] == "skill_retained"
                and _in_window(row["occurred_at"], start, end)
                for row in attribution
            )
        else:
            warnings.add("incomplete_attribution_metadata")

    strategies = root_data["strategies"]
    if strategies is not None:
        history = strategies["history"]
        if isinstance(history, list) and all(
            isinstance(row, dict) and _stamp(row.get("at")) is not None
            and isinstance(row.get("action"), str)
            for row in history
        ):
            events = [row for row in history if _in_window(row["at"], start, end)]
            for action, field in (
                ("INGESTED", "candidates"), ("VALIDATED", "validated"),
                ("PROMOTED", "activated"), ("ROLLED_BACK", "rolled_back")
            ):
                output["strategies"][field] = sum(
                    row["action"] == action for row in events
                )
            # Promotion automatically retires the prior active strategy.
            output["strategies"]["retired"] = sum(
                row["action"] == "PROMOTED"
                and bool(row.get("previous_active_strategy_id"))
                for row in events
            )
            warnings.add("strategy_history_bounded")
        else:
            warnings.add("incomplete_strategy_history")

    cycles = root_data["cycles"]
    if cycles is not None:
        runs = cycles["runs"]
        if isinstance(runs, list) and all(
            isinstance(row, dict) and _stamp(row.get("started_at")) is not None
            and isinstance(row.get("status"), str) for row in runs
        ):
            period = [row for row in runs if _in_window(row["started_at"], start, end)]
            output["cycles"]["launched"] = sum(
                row["status"] in {"SUCCESS", "PARTIAL", "FAILED"} for row in period
            )
            output["cycles"]["succeeded"] = sum(
                row["status"] == "SUCCESS" for row in period
            )
            output["cycles"]["partial"] = sum(
                row["status"] == "PARTIAL" for row in period
            )
            # SKIPPED is not saved by the current base night-cycle writer:
            # this count covers only explicit SKIPPED records if any.
            output["cycles"]["skipped"] = sum(
                row["status"] == "SKIPPED" for row in period
            )
            failed: dict[str, int] = {}
            for run in period:
                steps = run.get("steps", [])
                if not isinstance(steps, list):
                    warnings.add("incomplete_cycle_steps")
                    continue
                for step in steps:
                    if not isinstance(step, dict) or step.get("success") is not False:
                        continue
                    phase = step.get("phase")
                    if isinstance(phase, str) and phase in PHASES:
                        failed[phase] = failed.get(phase, 0) + 1
            output["cycles"]["failed_phases"] = dict(sorted(failed.items()))
            warnings.add("cycle_skips_not_durably_recorded")
            warnings.add("cycle_history_bounded")
        else:
            warnings.add("incomplete_cycle_history")

    halt_events = root_data["halt_events"]
    if halt_events is not None:
        if all(_stamp(row.get("at_ms")) is not None
               and isinstance(row.get("kind"), str) for row in halt_events):
            period = [row for row in halt_events if _in_window(row["at_ms"], start, end)]
            output["security"]["halt_transitions"] = sum(
                row["kind"] == "halt_transition" for row in period
            )
            output["security"]["network_guard_violations"] = sum(
                row["kind"] == "network_guard_violation" for row in period
            )
            output["cycles"]["interrupted_by_halt"] = sum(
                row["kind"] == "halt_interrupted"
                and row.get("loop") == "adaptive_vps_night_cycle"
                for row in period
            )
            warnings.add("halt_events_are_not_unique_cycle_ids")
        else:
            warnings.add("incomplete_halt_events")
    return output


def generate_report(config_dir: Path | str | None = None, *,
                    days: int = 7, now_ms: int | None = None) -> dict[str, Any]:
    if isinstance(days, bool) or not isinstance(days, int) or not 1 <= days <= MAX_DAYS:
        raise ValueError("days_out_of_range")
    now = int(time.time() * 1000) if now_ms is None else now_ms
    if _stamp(now) is None:
        raise ValueError("now_ms_invalid")
    root = Path(config_dir) if config_dir is not None else Path(
        os.environ.get("JADE_GENESIS_CONFIG_DIR", str(Path.home() / ".jade-genesis"))
    )
    warnings: set[str] = set()
    data = {
        "goals": _read_json(root, "goals", "goals", warnings),
        "ledger": _read_json(root, "ledger", "datasets", warnings),
        "attribution": _read_jsonl(root, "attribution", warnings),
        "strategies": _read_json(root, "strategies", "history", warnings),
        "cycles": _read_json(root, "cycles", "runs", warnings),
        "halt_state": _read_json(root, "halt_state", None, warnings),
        "halt_events": _read_jsonl(root, "halt_events", warnings),
    }
    state = data["halt_state"]
    if state is not None and state.get("state") not in {"RUNNING", "HALTED"}:
        warnings.add("invalid_halt_state")
        state = None
    size = days * DAY_MS
    previous = _metrics(data, now - 2 * size, now - size, warnings)
    current = _metrics(data, now - size, now, warnings)
    delta: dict[str, Any] = {}
    for section, names in METRICS.items():
        delta[section] = {}
        for name in names:
            a, b = current[section][name], previous[section][name]
            delta[section][name] = a - b if isinstance(a, int) and isinstance(b, int) else None
    return {
        "schema_version": SCHEMA_VERSION,
        "generated_at_ms": now,
        "days": days,
        "current": {
            "start_ms": now - size, "end_ms": now, "metrics": current
        },
        "previous": {
            "start_ms": now - 2 * size, "end_ms": now - size, "metrics": previous
        },
        "delta": delta,
        "halt_state": state["state"] if state is not None else None,
        "warnings": sorted(warnings),
    }


def format_text(report: dict[str, Any]) -> str:
    lines = [
        "Jade Genesis — progression (lecture seule, V0)",
        "Période : {} jour(s) | HALT actuel : {}".format(
            report["days"], report["halt_state"] or "inconnu"
        ),
    ]
    current = report["current"]["metrics"]
    previous = report["previous"]["metrics"]
    for section, names in METRICS.items():
        lines.append("")
        lines.append(section.upper())
        for name in names:
            a, b, d = current[section][name], previous[section][name], report["delta"][section][name]
            def pretty(value: Any) -> str:
                return "inconnu" if value is None else str(value)
            lines.append("  {} : {} | avant {} | delta {}".format(
                name, pretty(a), pretty(b), pretty(d)
            ))
    phases = current["cycles"].get("failed_phases")
    if phases is not None:
        lines.append("Phases en échec : " + (
            ", ".join("{}={}".format(k, v) for k, v in phases.items())
            if phases else "aucune dans les cycles journalisés"
        ))
    if report["warnings"]:
        lines.append("")
        lines.append("Limites : " + ", ".join(report["warnings"]))
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    report_cmd = sub.add_parser("report", help="Count read-only historical evidence.")
    report_cmd.add_argument("--days", type=int, default=7)
    report_cmd.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    if args.action != "report" or not 1 <= args.days <= MAX_DAYS:
        parser.error("--days must be between 1 and 365")
    result = generate_report(days=args.days)
    if args.json:
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    else:
        print(format_text(result))
    return 0


if __name__ == "__main__":
    sys.exit(main())
