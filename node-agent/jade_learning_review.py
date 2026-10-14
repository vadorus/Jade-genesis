"""Offline review bridge for Jade's own NightLearningLab proposals.

Reports and hypothesis text are data. No proposed target becomes an executable
action, and no imported flag can authorize execution or memory promotion.
"""
from __future__ import annotations

import base64
import hashlib
import json
from urllib.parse import urlsplit

from research_authorization import digest

SCHEMA = "jade.mobile_learning_review.v1"
MAX_BYTES = 300_000
REPORT_FLAGS = ("automatic_experiment_execution", "automatic_promotion",
                "production_code_rewrite", "shell_execution",
                "raw_conversation_text_used", "user_feedback_promoted_to_external_fact")


def _text(row, key, maximum=500):
    value = row.get(key)
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        raise ValueError(f"Invalid Jade report field: {key}")
    return value


def _rows(report, key, maximum):
    rows = report.get(key)
    if not isinstance(rows, list) or len(rows) > maximum or any(not isinstance(row, dict) for row in rows):
        raise ValueError(f"Invalid Jade report collection: {key}")
    return rows


def _ids(rows, key):
    ids = [_text(row, key, 160) for row in rows]
    if len(set(ids)) != len(ids):
        raise ValueError("Repeated Jade proposal identity")
    return set(ids)


def validate_report(report: dict) -> dict:
    if not isinstance(report, dict) or type(report.get("schema_version")) is not int or report.get("schema_version") != 1:
        raise ValueError("Expected a NightLearningLab report")
    if any(report.get(flag) is not False for flag in REPORT_FLAGS):
        raise ValueError("A Jade report cannot authorize execution or promotion")
    encoded = json.dumps(report, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    if len(encoded) > 64_000 or len(encoded.encode("utf-8")) > 150_000:
        raise ValueError("Jade report exceeds the shared bounded import size")
    for key in ("source_revision", "generated_at", "runtime_observation_count", "outcome_feedback_count"):
        if type(report.get(key)) is not int or report[key] < 0:
            raise ValueError(f"Invalid Jade observation field: {key}")
        if key in ("runtime_observation_count", "outcome_feedback_count") and report[key] > 2_147_483_647:
            raise ValueError("Observation count exceeds mobile integer bounds")
    for key, low, high in (("runtime_confidence", 0, 1), ("overall_outcome_quality", -1, 1)):
        if type(report.get(key)) not in (int, float) or not low <= report[key] <= high:
            raise ValueError(f"Invalid Jade observation field: {key}")
    questions = _rows(report, "research_questions", 3)
    evidence = _rows(report, "research_evidence", 6)
    hypotheses = _rows(report, "hypotheses", 4)
    experiments = _rows(report, "experiments", 4)
    question_ids = _ids(questions, "question_id")
    _ids(evidence, "evidence_id")
    hypothesis_ids = _ids(hypotheses, "hypothesis_id")
    _ids(experiments, "experiment_id")
    for question in questions:
        _text(question, "query")
    for item in evidence:
        url = urlsplit(_text(item, "url"))
        if url.scheme != "https" or not url.hostname or url.username or url.password:
            raise ValueError("Expected a plain HTTPS evidence reference")
        _text(item, "title", 220); _text(item, "snippet")
        if item.get("question_id") not in question_ids:
            raise ValueError("Evidence refers to an absent question")
    for item in hypotheses:
        _text(item, "statement"); _text(item, "falsifiable_metric", 180); _text(item, "success_criterion")
        if item.get("promoted_as_external_fact") is not False:
            raise ValueError("A hypothesis is not a verified external fact")
    for item in experiments:
        if item.get("hypothesis_id") not in hypothesis_ids:
            raise ValueError("Experiment refers to an absent hypothesis")
        if (item.get("automatic_execution") is not False
                or item.get("requires_explicit_promotion_approval") is not True
                or item.get("paired_scenarios_required") is not True
                or item.get("frozen_champion_required") is not True
                or type(item.get("sandbox_required")) is not bool):
            raise ValueError("An imported proposal cannot remove Jade's experiment boundaries")
        _text(item, "target", 180); _text(item, "primary_metric", 180); _text(item, "change_hint")
        minimum, maximum = item.get("minimum_samples_per_side"), item.get("maximum_samples_per_side")
        if type(minimum) is not int or type(maximum) is not int or not 1 <= minimum <= maximum <= 100:
            raise ValueError("Invalid Jade paired experiment sample limits")
    return report


def export_learning_review(report: dict, *, goal: str = "Évaluer les améliorations proposées pour Jade") -> dict:
    validate_report(report)
    if not isinstance(goal, str) or not 1 <= len(goal.strip()) <= 320:
        raise ValueError("Invalid Jade review goal")
    raw = json.dumps(report, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
    bundle = {"schema": SCHEMA, "goal": goal.strip(), "night_report_base64": base64.b64encode(raw).decode("ascii"),
              "night_report_sha256": hashlib.sha256(raw).hexdigest(), "verified": False,
              "action_authorized": False, "automatic_promotion": False}
    if len(json.dumps(bundle, ensure_ascii=False).encode("utf-8")) > MAX_BYTES:
        raise ValueError("Jade review exceeds the bounded mobile import size")
    return bundle


def learning_review_state(bundle: dict) -> dict:
    required = {"schema", "goal", "night_report_base64", "night_report_sha256",
                "verified", "action_authorized", "automatic_promotion"}
    if not isinstance(bundle, dict) or set(bundle) != required or bundle["schema"] != SCHEMA:
        raise ValueError("Invalid Jade learning review dossier")
    if any(bundle[key] is not False for key in ("verified", "action_authorized", "automatic_promotion")):
        raise ValueError("An imported dossier cannot grant permission")
    goal = _text(bundle, "goal", 320)
    encoded = _text(bundle, "night_report_base64", MAX_BYTES)
    raw = base64.b64decode(encoded, validate=True)
    if len(raw) > 150_000 or hashlib.sha256(raw).hexdigest() != bundle["night_report_sha256"]:
        raise ValueError("Jade report bytes changed or exceeded the size limit")
    report = validate_report(json.loads(raw))
    sources = []
    def source(key, title, text, url="", origin="LOCAL_JADE_REPORT"):
        sha = hashlib.sha256(text.encode("utf-8")).hexdigest()
        sources.append({"source_key": key, "title": title, "url": url, "sha256": sha,
                        "origin": origin, "caveat_excerpt_hashes": [],
                        "excerpts": [{"block_index": 0, "excerpt_sha256": sha, "text": text, "untrusted": True}]})
    source("jade_observations", "Observations déclarées de Jade",
           f"Observations : {report['runtime_observation_count']}. Confiance : {report['runtime_confidence']}. "
           f"Retours de qualité : {report['outcome_feedback_count']}. Qualité globale : {report['overall_outcome_quality']}.")
    for index, item in enumerate(report["hypotheses"]):
        source("jade_hypothesis_" + str(index), "Hypothèse de Jade " + str(index + 1), item["statement"])
    for index, item in enumerate(report["research_evidence"]):
        source("jade_external_" + str(index), item["title"], item["snippet"], item["url"], "UNASSESSED_EXTERNAL_SNIPPET")
    by_hypothesis = {item["hypothesis_id"]: item for item in report["hypotheses"]}
    proposals = [{"id": item["experiment_id"], "status": "PROPOSED_REQUIRES_EXPLICIT_SANDBOX_PLAN",
                  "title": item["change_hint"], "target": item["target"], "primary_metric": item["primary_metric"],
                  "success_criterion": by_hypothesis[item["hypothesis_id"]]["success_criterion"],
                  "hypothesis_id": item["hypothesis_id"], "minimum_samples_per_side": item["minimum_samples_per_side"],
                  "maximum_samples_per_side": item["maximum_samples_per_side"], "sandbox_required": item["sandbox_required"]}
                 for item in report["experiments"]]
    packet = {"schema": "jade.night_research_proposal.v2", "scope": "JADE_SELF_IMPROVEMENT",
              "goal_sha256": hashlib.sha256(goal.encode("utf-8")).hexdigest(), "report_sha256": bundle["night_report_sha256"],
              "source_count": len(sources), "hypothesis_count": len(report["hypotheses"]), "next_experiments": proposals,
              "night_review_status": "REQUIRES_HUMAN_EVIDENCE_INSPECTION", "automatic_promotion": False,
              "automatic_tool_execution": False, "evidence_ledger_import_allowed": False,
              "experiment_artifacts": [], "hypotheses": report["hypotheses"]}
    return {"goal": goal, "scope": "JADE_SELF_IMPROVEMENT", "ready": True, "sources": sources,
            "packet": packet, "captured_at_ms": report["generated_at"], "snapshot_sha256": digest(bundle),
            "knowledge_verified": False, "automatic_promotion": False}
