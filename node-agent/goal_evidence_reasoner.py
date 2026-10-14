"""Conservative offline reasoning over captured official docs + isolated FBX tests.

This module examines already generated artifacts. It makes no HTTP requests,
runs no external programs, never grants evidence approval, and never promotes
knowledge. Every experiment result is labelled locally checked / unattested.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import math
from pathlib import Path
from typing import Any

from official_source_capture import APPROVED

SCHEMA = "jade.goal_evidence_review.v1"
HEX = frozenset("0123456789abcdef")
MAX_RESEARCH_JSON = 100_000
MAX_PROBE_JSON = 12_000
MAX_FBX_BYTES = 2 * 1024 * 1024
MAX_PASSES = 4


class RejectedEvidence(ValueError):
    """Input did not meet the evidence format and provenance requirements."""


def _load_object(path: Path, maximum: int) -> dict[str, Any]:
    return _load_object_bytes(path, maximum)[0]


def _load_object_bytes(path: Path, maximum: int) -> tuple[dict[str, Any], bytes]:
    try:
        with path.open("rb") as handle:
            raw = handle.read(maximum + 1)
        if len(raw) > maximum:
            raise RejectedEvidence("Oversized input artifact")
        value = json.loads(raw.decode("utf-8"))
    except RejectedEvidence:
        raise
    except (OSError, UnicodeError, ValueError) as exc:
        raise RejectedEvidence("Unreadable input artifact") from exc
    if not isinstance(value, dict):
        raise RejectedEvidence("Root must be an object")
    return value, raw


def _digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _is_hash(value: Any) -> bool:
    return (isinstance(value, str) and len(value) == 64
            and all(ch in HEX for ch in value))


def _check(cond: bool, reason: str) -> None:
    if not cond:
        raise RejectedEvidence(reason)


def document_references(report: dict[str, Any]) -> tuple[str, list[dict[str, Any]]]:
    _check(report.get("schema") == "jade.goal_source_research.v1",
           "Invalid research report schema")
    for flag in ("automatic_verification", "automatic_promotion", "contradictions_proven"):
        _check(report.get(flag) is False, "Source report claims unearned approval")
    _check(report.get("tool_execution") is False, "Unexpected claimed tool execution")
    goal = report.get("goal", "")
    _check(isinstance(goal, str) and 0 < len(goal) <= 320, "Unbounded goal")
    sources = report.get("sources")
    _check(isinstance(sources, list) and len(sources) <= 3,
           "Invalid source count")
    records = []
    seen: set[str] = set()
    for source in sources:
        _check(isinstance(source, dict), "Invalid source record")
        key = source.get("source_key")
        _check(isinstance(key, str) and key in APPROVED and key not in seen,
               "Duplicate or unapproved source")
        seen.add(key)
        _check(source.get("source_url") == APPROVED[key].url, "Source URL mismatch")
        _check(_is_hash(source.get("sha256")), "Invalid document hash")
        _check(source.get("inspected") is False
               and source.get("fact_verified") is False
               and source.get("assessment") == "UNASSESSED",
               "Document was marked as verified without inspection")
        _check(source.get("body_persisted") is False
               and source.get("direction") == "UNKNOWN", "Document stance mismatch")
        excerpts = source.get("excerpts")
        _check(isinstance(excerpts, list) and len(excerpts) <= 5,
               "Unexpected excerpt count")
        references = []
        possible_restrictions = []
        excerpt_hashes: set[str] = set()
        block_indices: set[int] = set()
        for block in excerpts:
            _check(isinstance(block, dict), "Invalid excerpt")
            snippet = block.get("excerpt")
            _check(isinstance(snippet, str) and len(snippet) <= 380,
                   "Unexpected excerpt length")
            _check(_digest(snippet.encode("utf-8")) == block.get("excerpt_sha256"),
                   "Snippet digest mismatch")
            _check(block.get("inspected") is False
                   and block.get("stance") == "UNKNOWN"
                   and block.get("verification") == "NOT_VERIFIED"
                   and block.get("untrusted_external_text") is True,
                   "Excerpt claims verification")
            block_id = block.get("block_index")
            _check(type(block_id) is int and 0 <= block_id <= 3500,
                   "Invalid excerpt offset")
            _check(block_id not in block_indices and block["excerpt_sha256"] not in excerpt_hashes,
                   "Duplicate excerpt is not additional evidence")
            block_indices.add(block_id)
            excerpt_hashes.add(block["excerpt_sha256"])
            references.append({
                "block_index": block_id,
                "excerpt_sha256": block["excerpt_sha256"],
                "untrusted": True,
            })
            if block.get("potential_limitation") is True:
                possible_restrictions.append(block["excerpt_sha256"])
        records.append({
            "source_key": key,
            "url": APPROVED[key].url,
            "sha256": source["sha256"],
            "excerpts": references,
            "caveat_excerpt_hashes": possible_restrictions,
            "evidence_stance": "UNASSESSED",
            "inspected": False,
        })
    return _digest(goal.encode("utf-8")), records


def _mesh_signature(rows: Any) -> tuple[tuple[str, int, int, tuple[float, ...]], ...]:
    _check(isinstance(rows, list) and len(rows) == 2, "Unexpected mesh rows")
    result = []
    for obj in rows:
        _check(isinstance(obj, dict), "Invalid mesh record")
        name = obj.get("name", "")
        _check(name in ("SyntheticCanopy", "SyntheticTrunk"), "Unexpected mesh name")
        v, t = obj.get("vertices"), obj.get("triangles")
        _check(type(v) is int and 0 < v < 100_000 and
               type(t) is int and 0 < t < 200_000, "Invalid mesh topology")
        dims = obj.get("dimensions")
        _check(isinstance(dims, list) and len(dims) == 3
               and all(type(x) in (float, int) and math.isfinite(x)
                       and 0 < x <= 500 for x in dims), "Invalid mesh dimensions")
        result.append((name, v, t, tuple(sorted(round(float(x), 4) for x in dims))))
    _check(len({entry[0] for entry in result}) == 2, "Duplicate meshes")
    return tuple(sorted(result))


def inspect_local_probe(report_path: Path, fbx_path: Path) -> dict[str, Any]:
    """Read-only artifact check: not a cryptographic guarantee of Blender execution."""
    report, report_bytes = _load_object_bytes(report_path, MAX_PROBE_JSON)
    _check(report.get("schema") == "jade.local_blender_fbx_probe.v1",
           "Unrecognized probe schema")
    _check(report.get("status") == "PASS", "Failed probe")
    _check(report.get("experiment") ==
           "synthetic FBX roundtrip inside Blender only", "Unexpected experiment")
    version = report.get("blender_version", "")
    _check(isinstance(version, str) and version.startswith("5.2."),
           "Unknown Blender version")
    validators = report.get("validations")
    _check(isinstance(validators, dict)
           and set(validators) == {
               "two_mesh_objects", "vertex_count_preserved",
               "triangles_preserved", "bounding_dimensions_preserved"
           } and all(v is True for v in validators.values()),
           "Probe validators did not all pass")
    before = _mesh_signature(report.get("geometry_before"))
    after = _mesh_signature(report.get("geometry_after"))
    _check(before == after, "FBX geometry differs on roundtrip")
    _check(before == (
        ("SyntheticCanopy", 24, 44, (1.8, 1.8, 2.4)),
        ("SyntheticTrunk", 24, 44, (0.46, 0.46, 2.0)),
    ), "Probe is not the expected isolated synthetic fixture")
    _check(_is_hash(report.get("fbx_sha256")), "Invalid FBX SHA")
    try:
        with fbx_path.open("rb") as handle:
            raw = handle.read(MAX_FBX_BYTES + 1)
    except OSError as exc:
        raise RejectedEvidence("Missing FBX output") from exc
    size = len(raw)
    _check(100 <= size <= MAX_FBX_BYTES, "FBX output outside size limit")
    _check(size == report.get("fbx_bytes"), "FBX size mismatch")
    _check(raw.startswith(b"Kaydara FBX Binary  "), "Not a binary FBX artifact")
    _check(_digest(raw) == report["fbx_sha256"], "FBX content hash mismatch")
    timestamp = report.get("executed_at_ms")
    _check(type(timestamp) is int and timestamp > 0, "Invalid execution date")
    return {
        "test_kind": "BLENDER_LOCAL_SYNTHETIC_FBX_ROUNDTRIP",
        "test_status": "LOCAL_ARTIFACT_CHECKS_PASSED_NOT_INDEPENDENTLY_ATTESTED",
        "scope": "BLENDER_ONLY_NOT_ROBLOX",
        "fbx_sha256": report["fbx_sha256"],
        "report_sha256": _digest(report_bytes),
        "blender_version": version,
        "executed_at_ms": timestamp,
        "mesh_count": 2,
        "local_checks_passed": True,
        "trusted_external_attestation": False,
        "inspected_by_human": False,
    }


def review_goal_evidence(
    research: dict[str, Any],
    *,
    checked_probes: list[dict[str, Any]] = (),
) -> dict[str, Any]:
    goal_hash, sources = document_references(research)
    _check(len(checked_probes) <= MAX_PASSES, "Too many experiments")
    for probe in checked_probes:
        _check(probe.get("scope") == "BLENDER_ONLY_NOT_ROBLOX"
               and probe.get("local_checks_passed") is True
               and probe.get("trusted_external_attestation") is False
               and probe.get("inspected_by_human") is False,
               "Unapproved experiment attestation")
    unique_probes = list({p["fbx_sha256"]: p for p in checked_probes}.values())
    _check(len(unique_probes) == len(checked_probes), "Repeated FBX is not independent replay")
    same_geometry_test_count = len(unique_probes)
    supported_keys = {s["source_key"] for s in sources}
    return {
        "schema": SCHEMA,
        "goal_sha256": goal_hash,
        "scope": "SYNTHETIC_BLENDER_FBX_WORKFLOW",
        "source_references": sources,
        "experiment_artifacts": unique_probes,
        "reasoning": {
            "documentation_found": bool(sources),
            "documentation_interpreted": False,
            "independent_source_consensus_proven": False,
            "contradiction_proven": False,
            "potential_caveat_count": sum(len(s["caveat_excerpt_hashes"]) for s in sources),
            "blender_local_replays_checked": same_geometry_test_count,
            "blender_workflow_tested_locally": same_geometry_test_count > 0,
            "roblox_studio_import_tested": False,
            "roblox_studio_material_fidelity_tested": False,
            "new_knowledge_verified": False,
        },
        "next_experiments": [
            {"id": "ROBLOX_STUDIO_IMPORT_ISOLATED_COPY",
             "requires": ["Roblox Studio test project", "human approval"],
             "status": "NOT_EXECUTED",
             "question": "Does a synthetic FBX import in Roblox Studio with expected geometry?"},
            {"id": "MATERIAL_TEXTURE_FIDELITY_ISOLATED_COPY",
             "requires": ["Roblox Studio import result", "visual comparison"],
             "status": "BLOCKED_BY_DEPENDENCY",
             "question": "Are materials and textures visibly preserved?"},
            {"id": "INDEPENDENT_SOURCE_REVIEW",
             "requires": ["human interpretation of cited source excerpts"],
             "status": "NOT_EXECUTED",
             "question": "Which export settings are supported and contradicted by current docs?"},
        ],
        "night_review_status": "REQUIRES_HUMAN_EVIDENCE_INSPECTION",
        "automatic_promotion": False,
        "automatic_tool_execution": False,
        "evidence_ledger_import_allowed": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Analyze official source references and isolated experiment results")
    parser.add_argument("--research", type=Path, required=True)
    parser.add_argument("--probe", type=Path, action="append", default=[])
    parser.add_argument("--fbx", type=Path, action="append", default=[])
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if len(args.probe) != len(args.fbx) or len(args.probe) > MAX_PASSES:
        parser.error("Supply at most four matching --probe and --fbx pairs")
    report = _load_object(args.research, MAX_RESEARCH_JSON)
    experiments = [inspect_local_probe(p, f) for p, f in zip(args.probe, args.fbx)]
    result = review_goal_evidence(report, checked_probes=experiments)
    destination = args.out.resolve()
    if destination.exists() or not destination.parent.is_dir():
        parser.error("Output already exists or parent directory is missing")
    destination.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "report": str(destination),
        "documentation_sources": len(result["source_references"]),
        "local_replays_checked": len(result["experiment_artifacts"]),
        "roblox_import_tested": False,
        "knowledge_verified": False,
    }))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
