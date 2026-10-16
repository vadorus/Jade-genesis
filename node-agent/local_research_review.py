"""Loopback-only, explicit-action review of the isolated PC research workspace.

No node/VPS transport and no background work. See docs/LOCAL_RESEARCH_REVIEW_V1.md.
"""
from __future__ import annotations

import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import multiprocessing
from pathlib import Path
import secrets
import subprocess
import threading
from typing import Any
import uuid
from unittest.mock import Mock

from goal_evidence_reasoner import (
    _load_object, MAX_RESEARCH_JSON, inspect_local_probe, review_goal_evidence,
)
from research_authorization import (
    AuthorizationRejected, ResearchAuthorizationStore, canonical, digest,
    official_collection_plan, plan_hash,
)
from official_source_capture import APPROVED
from jade_learning_review import SCHEMA as LEARNING_SCHEMA, export_learning_review, learning_review_state
from shared_store_lock import atomic_write_text
from jade_halt import _GLOBAL_HALT
from network_guard import NetworkGuardViolation, no_python_network

DEFAULT_RESEARCH = "jade_learning_review.json"
DEFAULT_RUNS = ()


def _offline_research_child(conn, workspace: str, research: str,
                            runs: tuple[str, ...], blender: str | None,
                            plan: dict, dossier: dict,
                            script_bytes: bytes | None) -> None:
    """Spawn target: never install the socket monkeypatch in HTTP-server threads."""
    try:
        service = LocalResearchReview(
            Path(workspace), research=research, runs=runs,
            blender=Path(blender) if blender else None,
        )
        with no_python_network():
            result = service._run_inline(plan, dossier, script_bytes)
        conn.send(("OK", result))
    except NetworkGuardViolation as exc:
        conn.send(("NETWORK", exc.operation))
    except BaseException as exc:
        # Do not send file paths, source texts or credentials across the boundary.
        conn.send(("ERROR", type(exc).__name__))
    finally:
        conn.close()


class LocalResearchReview:
    def __init__(self, workspace: Path, *, research: str = DEFAULT_RESEARCH,
                 runs: tuple[str, ...] = DEFAULT_RUNS, blender: Path | None = None):
        self.root = workspace.resolve(strict=True)
        self.capture = self._inside(self.root / "capture_local")
        if not self.capture.is_dir() or not (self.root / "node-agent").is_dir():
            raise ValueError("Expected an isolated Jade research workspace")
        if Path(research).name != research or any(Path(run).name != run for run in runs):
            raise ValueError("Artifact names must be simple local names")
        self.research = self._inside(self.capture / research)
        if len(runs) > 4:
            raise ValueError("Too many probe pairs")
        self.pairs = [(
            self._inside(self.capture / run / "synthetic_canopy_roundtrip_report.json"),
            self._inside(self.capture / run / "synthetic_canopy_roundtrip.fbx"),
        ) for run in runs]
        self.store = ResearchAuthorizationStore(self.capture / "review_authorizations.json")
        self.blender = blender.resolve(strict=True) if blender else None
        self.action_lock = threading.Lock()

    def _inside(self, path: Path) -> Path:
        resolved = path.resolve()
        if not resolved.is_relative_to(self.root):
            raise ValueError("Artifact path escapes isolated workspace")
        return resolved

    def dossier(self) -> dict:
        # Revalidate on every request; consent never survives changed input bytes.
        if not self.research.exists() and self.research.name == DEFAULT_RESEARCH:
            return {"goal": "Revue des améliorations proposées pour Jade", "scope": "JADE_SELF_IMPROVEMENT",
                    "ready": False, "sources": [], "captured_at_ms": None, "workspace": str(self.root),
                    "packet": {"experiment_artifacts": [], "next_experiments": [], "hypotheses": []},
                    "snapshot_sha256": digest({"workspace": str(self.root), "ready": False}),
                    "knowledge_verified": False, "automatic_promotion": False}
        research = _load_object(self._inside(self.research), 300_000)
        if research.get("schema") == LEARNING_SCHEMA:
            return {**learning_review_state(research), "workspace": str(self.root)}
        checked = [inspect_local_probe(self._inside(p), self._inside(f)) for p, f in self.pairs]
        packet = review_goal_evidence(research, checked_probes=checked)
        # Only schema-checked fields are exposed. Source text is rendered as text,
        # never HTML, a command, or an instruction to the experiment runner.
        sources = [{
            **ref, "title": APPROVED[ref["source_key"]].title,
            "excerpts": [{**block, "text": original["excerpt"]}
                         for block, original in zip(ref["excerpts"], source["excerpts"])],
        } for ref, source in zip(packet["source_references"], research["sources"])]
        snapshot = digest({"research": research, "packet": packet})
        return {"goal": research["goal"], "snapshot_sha256": snapshot,
                "captured_at_ms": research.get("created_at_ms"),
                "sources": sources, "packet": packet,
                "knowledge_verified": False, "automatic_promotion": False,
                "workspace": str(self.root)}

    def _tool(self) -> tuple[dict, bytes]:
        if self.blender is None:
            raise AuthorizationRejected("Blender runner is not configured")
        script = self._inside(self.root / "node-agent" / "blender_fbx_roundtrip_probe.py")
        raw = script.read_bytes()
        if len(raw) > 40_000:
            raise ValueError("Unexpected probe script size")
        with self.blender.open("rb") as handle:
            exe_hash = hashlib.file_digest(handle, "sha256").hexdigest()
        return {"executable": str(self.blender), "executable_sha256": exe_hash,
                "script_sha256": hashlib.sha256(raw).hexdigest()}, raw

    def plan(self, action_id: str, dossier: dict | None = None) -> dict:
        if not isinstance(action_id, str):
            raise AuthorizationRejected("Expected an experiment identifier")
        dossier = dossier or self.dossier()
        if not dossier.get("ready", True):
            raise AuthorizationRejected("Importer un compte rendu de Jade avant d'autoriser")
        if dossier.get("scope") == "JADE_SELF_IMPROVEMENT" and action_id != "OFFLINE_ARTIFACT_RECHECK":
            raise AuthorizationRejected("Cette proposition n'a pas de plan d'exécution isolé autorisable")
        if action_id == "OFFICIAL_SOURCE_COLLECTION":
            plan = official_collection_plan(dossier["goal"])
        elif action_id in {"OFFLINE_ARTIFACT_RECHECK", "BLENDER_SYNTHETIC_FBX_ROUNDTRIP"}:
            plan = {"schema": "jade.local_experiment_plan.v1", "action_id": action_id,
                    "scope": "PC_ISOLATED_COPY", "automatic_promotion": False,
                    "network": "NO_NETWORK_CALLS_IN_RUNNER"}
        else:
            raise AuthorizationRejected("No controlled runner for this proposed experiment")
        plan.update({"snapshot_sha256": dossier["snapshot_sha256"],
                     "workspace": str(self.root),
                     "output_root": str(self._inside(self.capture / "review_runs")),
                     "output_policy": "NEW_UNIQUE_DIRECTORY_NO_OVERWRITE"})
        if action_id == "OFFLINE_ARTIFACT_RECHECK":
            plan["effect"] = ("Recheck Jade observation report and proposal references; write a fresh review packet"
                              if dossier.get("scope") == "JADE_SELF_IMPROVEMENT"
                              else "Recheck existing document and FBX artifacts; write a fresh review packet")
        if action_id == "BLENDER_SYNTHETIC_FBX_ROUNDTRIP":
            tool, _ = self._tool()
            plan.update(tool)
            plan.update({"effect": "Generate trunk/canopy, export FBX, reimport in factory-startup Blender",
                         "user_assets_read": False, "roblox_project_opened": False,
                         "timeout_seconds": 60, "os_sandbox": False})
        return plan

    def state(self) -> dict:
        dossier = self.dossier()
        generic = dossier.get("scope") == "JADE_SELF_IMPROVEMENT"
        actions = ("OFFLINE_ARTIFACT_RECHECK",) if generic else ("OFFLINE_ARTIFACT_RECHECK", "OFFICIAL_SOURCE_COLLECTION")
        plans = [self.plan(action, dossier) for action in actions] if dossier.get("ready", True) else []
        if self.blender and not generic:
            plans.append(self.plan("BLENDER_SYNTHETIC_FBX_ROUNDTRIP", dossier))
        events = self.store.events()  # Corruption fails closed for all actions.
        return {**dossier, "plans": [{"plan": p, "plan_sha256": plan_hash(p)} for p in plans],
                "events": events, "audit_integrity": "CHECKED_LOCAL_HASH_CHAIN"}

    def mutate(self, action: str, body: dict) -> dict:
        # Serializes snapshot check and runner selection; clients cannot supply paths,
        # URLs, scripts, output dirs, command args or experiment results.
        with self.action_lock:
            if action == "revoke":
                return self.store.revoke(body.get("authorization_id", ""))
            dossier = self.dossier()
            if body.get("snapshot_sha256") != dossier["snapshot_sha256"]:
                raise AuthorizationRejected("Evidence changed: refresh and review again")
            if action == "import-learning":
                if dossier.get("scope") != "JADE_SELF_IMPROVEMENT":
                    raise AuthorizationRejected("Ouvrir l'atelier Jade général avant d'importer")
                self.store.events()
                report = body.get("report")
                bundle = report if isinstance(report, dict) and report.get("schema") == LEARNING_SCHEMA else export_learning_review(report)
                imported = learning_review_state(bundle)
                if self.dossier()["snapshot_sha256"] != dossier["snapshot_sha256"]:
                    raise AuthorizationRejected("Le dossier a changé pendant l'import")
                if self.research.exists():
                    archive = self._inside(self.capture / "review_imports" / str(uuid.uuid4()))
                    archive.mkdir(parents=True, exist_ok=False)
                    (archive / "previous_dossier.json").write_bytes(self.research.read_bytes())
                atomic_write_text(self._inside(self.research), canonical(bundle) + "\n", mode=0o600)
                return {"snapshot_sha256": imported["snapshot_sha256"], "permission_imported": False}
            if not dossier.get("ready", True):
                raise AuthorizationRejected("Aucun compte rendu de Jade importé")
            if action == "export-mobile":
                if dossier.get("scope") == "JADE_SELF_IMPROVEMENT":
                    self.store.events()
                    bundle = _load_object(self._inside(self.research), 300_000)
                    if learning_review_state(bundle)["snapshot_sha256"] != dossier["snapshot_sha256"]:
                        raise AuthorizationRejected("Le dossier a changé pendant l'export")
                    raw = (canonical(bundle) + "\n").encode("utf-8")
                    if len(raw) > 300_000:
                        raise ValueError("Dossier trop volumineux")
                    return {"filename": "jade-learning-review.json", "dossier_json": raw.decode("utf-8"),
                            "dossier_sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw), "permission_exported": False}
                from night_learning_lab import NightLearningLab
                self.store.events()  # A damaged journal also blocks export.
                bundle = NightLearningLab().export_official_goal_review(
                    self._inside(self.research), [(self._inside(p), self._inside(f)) for p, f in self.pairs])
                if (bundle["review_packet"] != dossier["packet"]
                        or self.dossier()["snapshot_sha256"] != dossier["snapshot_sha256"]):
                    raise AuthorizationRejected("Evidence changed during export: review again")
                # Match displayed passages as well as the packet's references.
                expected = [{"source_key": s["source_key"], "passages": [
                    {"block_index": p["block_index"], "excerpt_sha256": p["excerpt_sha256"],
                     "text": p["text"], "untrusted": True} for p in s["excerpts"]
                ]} for s in dossier["sources"]]
                if bundle["source_texts"] != expected:
                    raise AuthorizationRejected("Displayed passages changed during export")
                raw = (json.dumps(bundle, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
                if len(raw) > 300_000:
                    raise ValueError("Mobile dossier exceeds the bounded import size")
                return {"filename": "jade-research-review.json", "dossier_json": raw.decode("utf-8"),
                        "dossier_sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw),
                        "permission_exported": False}
            if action == "inspect":
                source_hash = body.get("source_sha256")
                if source_hash not in {s["sha256"] for s in dossier["sources"]}:
                    raise AuthorizationRejected("Unknown source fingerprint")
                return self.store.record_inspection(
                    snapshot_sha256=dossier["snapshot_sha256"], source_sha256=source_hash,
                    reviewer=body.get("reviewer"), note=body.get("note"), stance=body.get("stance"))
            plan = self.plan(body.get("action_id"), dossier)
            if body.get("plan_sha256") != plan_hash(plan):
                raise AuthorizationRejected("Experiment plan changed: review again")
            if action == "authorize":
                return self.store.grant(plan, reviewer=body.get("reviewer"), note=body.get("note"),
                                        confirmed=body.get("confirmed"))
            if action != "execute":
                raise ValueError("Unknown action")
            script_bytes = self._tool()[1] if plan["action_id"] == "BLENDER_SYNTHETIC_FBX_ROUNDTRIP" else None
            return self.store.execute(plan, body.get("authorization_id", ""),
                                      lambda: self._run(plan, dossier, script_bytes))

    def result(self, authorization_id: str) -> dict:
        terminal = next((event for event in self.store.events()
                         if event.get("authorization_id") == authorization_id
                         and event["kind"] == "COMPLETED"), None)
        if terminal is None:
            raise AuthorizationRejected("No completed result for this authorization")
        summary = terminal.get("result_summary", {})
        directory = self._inside(Path(summary.get("output_directory", "")))
        if not directory.is_relative_to(self._inside(self.capture / "review_runs")):
            raise AuthorizationRejected("Result is outside isolated experiment outputs")
        path = self._inside(directory / "result.json")
        with path.open("rb") as handle:
            raw = handle.read(150_001)
        if len(raw) > 150_000 or hashlib.sha256(raw).hexdigest() != summary.get("result_file_sha256"):
            raise AuthorizationRejected("Result bytes changed or size exceeded")
        result = json.loads(raw)
        if not isinstance(result, dict):
            raise ValueError("Result must be an object")
        return {"result": result, "summary": summary, "knowledge_verified": False}

    def _run(self, plan: dict, dossier: dict, script_bytes: bytes | None) -> dict:
        if plan.get("network") == "NO_NETWORK_CALLS_IN_RUNNER":
            return self._run_spawned(plan, dossier, script_bytes)
        return self._run_inline(plan, dossier, script_bytes)

    def _run_spawned(self, plan: dict, dossier: dict,
                     script_bytes: bytes | None) -> dict:
        context = multiprocessing.get_context("spawn")
        receive, send = context.Pipe(duplex=False)
        process = context.Process(
            target=_offline_research_child,
            args=(send, str(self.root), self.research.name,
                  tuple(report.parent.name for report, _fbx in self.pairs),
                  str(self.blender) if self.blender else None,
                  plan, dossier, script_bytes),
        )
        try:
            process.start()
            send.close()
            if not receive.poll(75):
                process.terminate()
                process.join(timeout=5)
                raise RuntimeError("offline_runner_timeout")
            status, payload = receive.recv()
            process.join(timeout=5)
            if process.is_alive():
                process.terminate()
                process.join(timeout=5)
                raise RuntimeError("offline_runner_did_not_exit")
            if status == "NETWORK":
                _GLOBAL_HALT.network_violation(
                    runner="local_research_review", operation=str(payload)
                )
                raise RuntimeError("network_guard_violation")
            if status != "OK" or process.exitcode != 0:
                raise RuntimeError("offline_runner_error")
            return payload
        finally:
            receive.close()
            if process.is_alive():
                process.terminate()
                process.join(timeout=5)
            send.close()

    def _run_inline(self, plan: dict, dossier: dict, script_bytes: bytes | None) -> dict:
        destination = self._inside(Path(plan["output_root"]) / str(uuid.uuid4()))
        destination.mkdir(parents=True, exist_ok=False)
        action = plan["action_id"]
        if action == "OFFLINE_ARTIFACT_RECHECK":
            refreshed = self.dossier()
            result = refreshed["packet"]
            if refreshed["snapshot_sha256"] != plan["snapshot_sha256"]:
                raise AuthorizationRejected("Artifacts changed before recheck")
        elif action == "OFFICIAL_SOURCE_COLLECTION":
            from goal_source_research import investigate_goal
            result = investigate_goal(dossier["goal"])
        elif action == "BLENDER_SYNTHETIC_FBX_ROUNDTRIP":
            tool, _ = self._tool()
            if (any(plan[k] != tool[k] for k in tool) or not script_bytes
                    or hashlib.sha256(script_bytes).hexdigest() != plan["script_sha256"]):
                raise AuthorizationRejected("Experiment tool changed before launch")
            script = destination / "authorized_probe.py"
            script.write_bytes(script_bytes)
            with (destination / "execution.log").open("wb") as output:
                process = subprocess.run([
                    str(self.blender), "--background", "--factory-startup", "--disable-autoexec",
                    "--python-exit-code", "2", "--python", str(script), "--", str(destination),
                ], shell=False, cwd=destination, stdin=subprocess.DEVNULL, stdout=output,
                    stderr=subprocess.STDOUT, timeout=60,
                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            if process.returncode:
                raise RuntimeError("Blender synthetic probe failed; inspect isolated execution.log")
            checked = inspect_local_probe(destination / "synthetic_canopy_roundtrip_report.json",
                                          destination / "synthetic_canopy_roundtrip.fbx")
            result = {"experiment": checked, "knowledge_verified": False, "automatic_promotion": False}
        else:
            raise AuthorizationRejected("Unsupported controlled runner")
        report = destination / "result.json"
        report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return {"result": result, "output_directory": str(destination),
                "result_file_sha256": hashlib.sha256(report.read_bytes()).hexdigest(),
                "evaluation_status": ("COLLECTION_PARTIAL_FAILURE" if result.get("errors")
                                      else "UNASSESSED_DOCUMENT_CAPTURE" if action == "OFFICIAL_SOURCE_COLLECTION"
                                      else "LOCAL_CHECKS_PASSED_NOT_INDEPENDENTLY_ATTESTED"),
                "knowledge_verified": False, "automatic_promotion": False}


class ReviewServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, service: LocalResearchReview, port: int = 0):
        self.service = service
        self.csrf = secrets.token_urlsafe(32)
        super().__init__(("127.0.0.1", port), ReviewHandler)
        self.origin = f"http://127.0.0.1:{self.server_port}"


class ReviewHandler(BaseHTTPRequestHandler):
    server: ReviewServer

    def log_message(self, *_args):
        pass  # Goals, excerpts, reviewer names and tokens do not enter console logs.

    def _reply(self, code: int, raw: bytes, mime: str):
        self.send_response(code)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy",
                         "default-src 'self'; script-src 'self'; style-src 'self'; "
                         "connect-src 'self'; img-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        self.end_headers()
        self.wfile.write(raw)

    def _json(self, code: int, value: Any):
        self._reply(code, canonical(value).encode("utf-8"), "application/json; charset=utf-8")

    def _valid_host(self) -> bool:
        return (self.client_address[0] == "127.0.0.1"
                and self.headers.get("Host") == f"127.0.0.1:{self.server.server_port}")

    def do_GET(self):
        if not self._valid_host():
            return self._json(403, {"error": "Loopback host required"})
        try:
            if self.path == "/api/state" or self.path.startswith("/api/result/"):
                # This read also requires the session header, preventing cross-site
                # pages from using this endpoint to read local private evidence.
                if self.headers.get("X-Jade-Review") != self.server.csrf:
                    return self._json(403, {"error": "Session header required"})
                if self.path == "/api/state":
                    return self._json(200, self.server.service.state())
                return self._json(200, self.server.service.result(self.path.removeprefix("/api/result/")))
            names = {"/": "index.html", "/app.js": "app.js", "/style.css": "style.css"}
            if self.path not in names:
                return self._json(404, {"error": "Unknown route"})
            raw = (Path(__file__).parent / "research-review-ui" / names[self.path]).read_bytes()
            mime = {"/": "text/html", "/app.js": "text/javascript", "/style.css": "text/css"}[self.path]
            if self.path == "/":
                raw = raw.replace(b"__SESSION_TOKEN__", self.server.csrf.encode("ascii"))
            self._reply(200, raw, mime + "; charset=utf-8")
        except (ValueError, OSError) as exc:
            self._json(409, {"error": str(exc)[:240]})

    def do_POST(self):
        if (not self._valid_host() or self.headers.get("Origin") != self.server.origin
                or self.headers.get("X-Jade-Review") != self.server.csrf):
            return self._json(403, {"error": "Local session and same origin required"})
        if self.path not in {"/api/authorize", "/api/revoke", "/api/execute", "/api/inspect", "/api/export-mobile", "/api/import-learning"}:
            return self._json(404, {"error": "Unknown action"})
        try:
            if (self.headers.get("Content-Type") != "application/json"
                    or self.headers.get("Transfer-Encoding") is not None):
                raise ValueError("Expected bounded JSON body")
            length = int(self.headers.get("Content-Length", "0"))
            maximum = 300_000 if self.path == "/api/import-learning" else 4096
            if not 0 < length <= maximum:
                raise ValueError("Invalid request size")
            self.connection.settimeout(5)
            body = json.loads(self.rfile.read(length))
            if not isinstance(body, dict):
                raise ValueError("Expected an object")
            self._json(200, self.server.service.mutate(self.path.removeprefix("/api/"), body))
        except (ValueError, OSError, RuntimeError, subprocess.TimeoutExpired) as exc:
            self._json(409, {"error": str(exc)[:240]})


def main():
    parser = argparse.ArgumentParser(description="Local Jade research/evidence review; no automatic execution")
    parser.add_argument("--workspace", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--research", default=DEFAULT_RESEARCH)
    parser.add_argument("--blender", type=Path, help="Explicit local executable; optional synthetic-only runner")
    args = parser.parse_args()
    service = LocalResearchReview(args.workspace, research=args.research, blender=args.blender)
    service.state()  # Refuse to start on damaged evidence or journal.
    server = ReviewServer(service, args.port)
    print(f"Jade local research review: {server.origin} (no automatic execution)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
