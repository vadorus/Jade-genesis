"""Explicit offline export for Jade's native Android research review screen."""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
from pathlib import Path

from goal_evidence_reasoner import (
    _load_object, MAX_RESEARCH_JSON, MAX_FBX_BYTES, MAX_PROBE_JSON,
    inspect_local_probe, review_goal_evidence, RejectedEvidence,
)


def export_mobile_dossier(research_path: Path, pairs: list[tuple[Path, Path]]) -> dict:
    research = _load_object(research_path, MAX_RESEARCH_JSON)
    if len(pairs) > 4:
        raise RejectedEvidence("Too many local probes")
    checked = [inspect_local_probe(p, f) for p, f in pairs]
    packet = review_goal_evidence(research, checked_probes=checked)
    artifacts = []
    for (report_path, fbx_path), ref in zip(pairs, checked):
        with report_path.open("rb") as handle:
            report = handle.read(MAX_PROBE_JSON + 1)
        with fbx_path.open("rb") as handle:
            fbx = handle.read(MAX_FBX_BYTES + 1)
        if (hashlib.sha256(report).hexdigest() != ref["report_sha256"]
                or hashlib.sha256(fbx).hexdigest() != ref["fbx_sha256"]):
            raise RejectedEvidence("Artifact changed during mobile export")
        artifacts.append({"report_base64": base64.b64encode(report).decode("ascii"),
                          "fbx_base64": base64.b64encode(fbx).decode("ascii")})
    return {
        "schema": "jade.mobile_research_dossier.v1", "goal": research["goal"],
        "review_packet": packet,
        "source_texts": [{"source_key": s["source_key"], "passages": [
            {"block_index": p["block_index"], "excerpt_sha256": p["excerpt_sha256"],
             "text": p["excerpt"], "untrusted": True} for p in s["excerpts"]
        ]} for s in research["sources"]],
        "local_artifacts": artifacts, "verified": False, "action_authorized": False,
        "automatic_promotion": False,
    }


def main():
    parser = argparse.ArgumentParser(description="Offline Android review dossier export; no permission exported")
    parser.add_argument("--research", type=Path, required=True)
    parser.add_argument("--probe", type=Path, action="append", default=[])
    parser.add_argument("--fbx", type=Path, action="append", default=[])
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if len(args.probe) != len(args.fbx):
        parser.error("Matching probe and FBX pairs required")
    bundle = export_mobile_dossier(args.research, list(zip(args.probe, args.fbx)))
    data = (json.dumps(bundle, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
    if len(data) > 300_000:
        parser.error("Mobile dossier exceeds the bounded import size")
    with args.out.open("xb") as output:
        output.write(data)
    print(json.dumps({"bytes": len(data), "sources": len(bundle["source_texts"]),
                      "artifacts": len(bundle["local_artifacts"]), "permission_exported": False}))


if __name__ == "__main__":
    main()
