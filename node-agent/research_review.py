"""Offline review and execution approval validation; never executes a trial.

Only pass aggregate NightLearningLab reports, never personal memory exports.
Approval objects must come from a trusted user interface, not model output.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from typing import Any


def experiment_digest(experiment: dict[str, Any]) -> str:
    encoded = json.dumps(experiment, sort_keys=True, ensure_ascii=False,
                         separators=(",", ":"), allow_nan=False).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def execution_authorized(experiment: dict[str, Any], approval: dict[str, Any],
                         *, now_ms: int, environment: str) -> bool:
    """Fail closed; an execution approval never approves promotion or deployment."""
    if not isinstance(experiment, dict) or not isinstance(approval, dict):
        return False
    if type(now_ms) is not int or now_ms < 0:
        return False
    identifier = experiment.get("experiment_id")
    if not isinstance(identifier, str) or not identifier.strip():
        return False
    try:
        return (
            environment == "bureau-sandbox"
            and experiment.get("automatic_execution") is False
            and approval.get("approved_by") == "user"
            and approval.get("action") == "execute_experiment"
            and approval.get("environment") == environment
            and approval.get("experiment_id") == experiment["experiment_id"]
            and approval.get("experiment_sha256") == experiment_digest(experiment)
            and type(approval.get("issued_at_ms")) is int
            and type(approval.get("expires_at_ms")) is int
            and 0 <= approval["issued_at_ms"] <= now_ms < approval["expires_at_ms"]
            and approval["expires_at_ms"] - approval["issued_at_ms"] <= 86_400_000
        )
    except (TypeError, ValueError, KeyError):
        return False


def _text(value: Any) -> str:
    # Keep external material as plain text, bounded and on a single line.
    return " ".join(str(value or "").split())[:500]


def render_review(report: dict[str, Any]) -> str:
    lines = ["JADE — REVUE LOCALE", "Sources rapportées : contenu non vérifié par cet outil.",
             "Une source ne prouve pas à elle seule une hypothèse.",
             "Aucune expérience exécutée ; aucune promotion autorisée."]
    for item in report.get("research_questions", [])[:3]:
        lines.append("Question : " + _text(item.get("query") or item.get("question") or item.get("question_id")) if isinstance(item, dict) else "Question : " + _text(item))
    for item in report.get("research_evidence", [])[:6]:
        if isinstance(item, dict):
            lines.append("Source : " + _text(item.get("title")))
            lines.append("URL : " + _text(item.get("url")))
            lines.append("Source primaire déclarée : " + ("oui" if item.get("primarySource") is True or item.get("primary_source") is True else "non établie"))
            lines.append("Extrait rapporté : " + _text(item.get("snippet")))
    for item in report.get("research_errors", [])[:6]:
        lines.append("Recherche indisponible : " + _text(item))
    for item in report.get("hypotheses", [])[:4]:
        if isinstance(item, dict):
            lines.append("Hypothèse à tester : " + _text(item.get("statement") or item.get("title") or item.get("hypothesis_id")))
    for item in report.get("experiments", [])[:4]:
        if isinstance(item, dict):
            lines.extend([
                "Expérience : " + _text(item.get("experiment_id")),
                "Hypothèse liée : " + _text(item.get("hypothesis_id")),
                "Proposition : " + _text(item.get("change_hint")),
                "Mesure : " + _text(item.get("primary_metric")),
                "Échantillons par côté : " + _text(item.get("minimum_samples_per_side")) + " à " + _text(item.get("maximum_samples_per_side")),
                "Empreinte : " + experiment_digest(item),
                "Autorisation explicite d'exécution requise : bureau-sandbox, durée maximale 24 h.",
            ])
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Revue hors ligne d'un rapport agrégé Night Learning")
    parser.add_argument("report")
    args = parser.parse_args()
    with open(args.report, encoding="utf-8") as stream:
        report = json.load(stream)
    if not isinstance(report, dict):
        parser.error("Le rapport doit être un objet JSON")
    print(render_review(report), end="")
