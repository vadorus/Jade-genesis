#!/usr/bin/env python3
"""GitHub-first coordination MVP. No external agent is launched by this module.

The canonical durable state is orchestration/state.json on a GitHub branch.
Only the opt-in --apply path can modify that file, via a content-SHA check.
"""
from __future__ import annotations

import argparse
import base64
import copy
import json
import os
import re
import sys
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

STATE_PATH = "orchestration/state.json"
ACTIVE = {"reserved", "in_progress", "review"}
VALID = ACTIVE | {"queued", "blocked", "done", "cancelled"}
KNOWN_REPOS = {
    "vadorus/Jade-genesis",
    "vadorus/addonhardwaregame2d",
    "vadorus/tech-empire-roblox",
}


class CoordinationError(ValueError):
    pass


class StaleState(CoordinationError):
    pass


def norm_path(path: str) -> str:
    """Canonical, conservative relative path; a bare path covers descendants."""
    if not isinstance(path, str) or not path or path != path.strip():
        raise CoordinationError("Invalid path")
    p = unicodedata.normalize("NFKC", path).casefold().rstrip("/")
    if not p or p.startswith("/") or "\\" in p or "//" in p:
        raise CoordinationError("Unsafe path")
    if any(x in (".", "..") for x in p.split("/")):
        raise CoordinationError("Traversal path")
    if not all(ch.isalnum() or ch in "._-/" for ch in p):
        raise CoordinationError("Unsupported character in path")
    return p


def overlaps(a: str, b: str) -> bool:
    x, y = norm_path(a), norm_path(b)
    return x == y or x.startswith(y + "/") or y.startswith(x + "/")


def zones_overlap(first: list[str], second: list[str]) -> bool:
    return any(overlaps(a, b) for a in first for b in second)


def validate(state: dict) -> None:
    if state.get("schema") != 1 or not isinstance(state.get("agents"), dict):
        raise CoordinationError("Invalid state schema/agents")
    tasks = state.get("tasks")
    if not isinstance(tasks, list):
        raise CoordinationError("Missing tasks")
    ids: set[str] = set()
    for name, agent in state["agents"].items():
        if not re.fullmatch(r"[a-z][a-z0-9_-]*", name):
            raise CoordinationError("Invalid agent identifier")
        if not isinstance(agent.get("available"), bool):
            raise CoordinationError("Unknown availability")
        for field in ("slots", "quota_tasks"):
            if type(agent.get(field)) is not int or agent[field] < 0:
                raise CoordinationError("Invalid agent budget")
        if not isinstance(agent.get("skills"), list):
            raise CoordinationError("Missing agent skills")
    for task in tasks:
        ident = task.get("id")
        if not isinstance(ident, str) or not ident or ident in ids:
            raise CoordinationError("Duplicate or invalid task")
        ids.add(ident)
        if task.get("repo") not in KNOWN_REPOS:
            raise CoordinationError("Unknown repository")
        if task.get("status") not in VALID:
            raise CoordinationError("Unknown task status")
        if not isinstance(task.get("zones"), list) or not task["zones"]:
            raise CoordinationError("Task has no reservation zones")
        for zone in task["zones"]:
            norm_path(zone)
        if not isinstance(task.get("depends_on", []), list):
            raise CoordinationError("Invalid dependencies")
        if type(task.get("priority", 0)) is not int:
            raise CoordinationError("Invalid priority")
        if not isinstance(task.get("skill"), str) or not task["skill"]:
            raise CoordinationError("Missing required skill")
        if task["status"] in ACTIVE:
            if task.get("writer") not in state["agents"] or task.get("reviewer") not in state["agents"]:
                raise CoordinationError("Reserved task has unknown actors")
            if task["writer"] == task["reviewer"]:
                raise CoordinationError("Writer must differ from reviewer")
    for task in tasks:
        for dep in task.get("depends_on", []):
            if dep not in ids or dep == task["id"]:
                raise CoordinationError("Unknown/self dependency")
    if not isinstance(state.get("history"), list):
        raise CoordinationError("Missing history")


def plan(state: dict) -> dict:
    """Pure deterministic allocator: no network, writes, or agent execution."""
    validate(state)
    tasks = state["tasks"]
    by_id = {t["id"]: t for t in tasks}
    assignments: list[dict] = []
    blocked: list[dict] = []
    agents = state["agents"]
    free_slots = {name: a["slots"] for name, a in agents.items()}
    free_quota = {name: a["quota_tasks"] for name, a in agents.items()}
    for t in tasks:
        if t["status"] in ACTIVE:
            free_slots[t["writer"]] = max(0, free_slots[t["writer"]] - 1)
    reserved = [(t["repo"], t["zones"], t["id"]) for t in tasks if t["status"] in ACTIVE]
    # Claude is the independent technical reviewer; it must have declared quota.
    reviewer = agents.get("claude")
    ordered = sorted((t for t in tasks if t["status"] == "queued"),
                     key=lambda t: (-t.get("priority", 0), t["id"]))
    for task in ordered:
        ident = task["id"]
        unfinished = [d for d in task.get("depends_on", []) if by_id[d]["status"] != "done"]
        if unfinished:
            blocked.append({"task": ident, "reason": "dependencies", "details": unfinished})
            continue
        collisions = [i for repo, zones, i in reserved
                      if repo == task["repo"] and zones_overlap(zones, task["zones"])]
        if collisions:
            blocked.append({"task": ident, "reason": "reservation_conflict", "details": collisions})
            continue
        if not reviewer or not reviewer["available"] or free_quota["claude"] <= 0:
            blocked.append({"task": ident, "reason": "reviewer_unavailable"})
            continue
        choices = [
            name for name, a in agents.items()
            if name != "claude" and a["available"]
            and task["skill"] in a["skills"]
            and free_slots[name] > 0 and free_quota[name] > 0
        ]
        if not choices:
            blocked.append({"task": ident, "reason": "no_worker_capacity"})
            continue
        writer = sorted(choices, key=lambda a: (-free_slots[a], -free_quota[a], a))[0]
        assignments.append({"task": ident, "writer": writer, "reviewer": "claude",
                            "repo": task["repo"], "zones": task["zones"]})
        reserved.append((task["repo"], task["zones"], ident))
        free_slots[writer] -= 1
        free_quota[writer] -= 1
        free_quota["claude"] -= 1
    return {"assignments": assignments, "blocked": blocked}


def apply_plan(state: dict, proposal: dict, *, timestamp: str | None = None) -> dict:
    """Build the next version; caller must use GitHub blob-SHA CAS to persist it."""
    validate(state)
    result = copy.deepcopy(state)
    by_id = {t["id"]: t for t in result["tasks"]}
    when = timestamp or datetime.now(timezone.utc).isoformat()
    for item in proposal["assignments"]:
        task = by_id[item["task"]]
        if task["status"] != "queued":
            raise CoordinationError("Task already assigned")
        task.update(status="reserved", writer=item["writer"], reviewer=item["reviewer"])
        result["history"].append({"at": when, "event": "reserved", "task": item["task"],
                                  "writer": item["writer"], "reviewer": item["reviewer"]})
    result["revision"] = result.get("revision", 0) + 1
    validate(result)
    return result


class GitHubStore:
    """One versioned GitHub contents file; no local durable source of truth."""

    def __init__(self, repo: str, branch: str, token: str | None):
        if repo not in KNOWN_REPOS:
            raise CoordinationError("Repository not approved")
        if not branch or not re.fullmatch(r"[\w./-]+", branch):
            raise CoordinationError("Invalid branch")
        self.url = ("https://api.github.com/repos/" + repo + "/contents/" +
                    STATE_PATH + "?ref=" + urllib.parse.quote(branch, safe=""))
        self.put_url = "https://api.github.com/repos/" + repo + "/contents/" + STATE_PATH
        self.branch = branch
        self.token = token

    def request(self, url: str, *, method: str = "GET", data: dict | None = None) -> dict:
        headers = {"Accept": "application/vnd.github+json",
                   "X-GitHub-Api-Version": "2022-11-28",
                   "User-Agent": "jade-multi-ai-coordination-mvp"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        payload = json.dumps(data).encode("utf-8") if data is not None else None
        if payload is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(url, headers=headers, data=payload, method=method)
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return json.load(response)
        except urllib.error.HTTPError as exc:
            if exc.code in (409, 412, 422):
                raise StaleState("Concurrent state update or invalid commit; reload and retry") from exc
            raise CoordinationError("GitHub API HTTP " + str(exc.code)) from exc

    def read(self) -> tuple[dict, str]:
        item = self.request(self.url)
        content = json.loads(base64.b64decode(item["content"]).decode("utf-8"))
        validate(content)
        return content, item["sha"]

    def save(self, state: dict, old_sha: str) -> None:
        if not self.token or os.environ.get("ORCH_ALLOW_GITHUB_WRITES") != "1":
            raise CoordinationError("Writes disabled; explicit token and opt-in required")
        validate(state)
        raw = (json.dumps(state, indent=2, ensure_ascii=False, sort_keys=True) + "\n").encode("utf-8")
        self.request(self.put_url, method="PUT", data={
            "branch": self.branch, "sha": old_sha,
            "message": "coordination: reserve tasks (Agent-IA: Codex)",
            "content": base64.b64encode(raw).decode("ascii"),
        })


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="GitHub-first multi-agent plan (no AI execution)")
    parser.add_argument("--repo", default="vadorus/Jade-genesis")
    parser.add_argument("--branch", default="main")
    parser.add_argument("--apply", action="store_true",
                        help="Update canonical state in GitHub with explicit opt-in")
    args = parser.parse_args(argv)
    if args.apply and os.environ.get("ORCH_ALLOW_GITHUB_WRITES") != "1":
        parser.error("--apply requires ORCH_ALLOW_GITHUB_WRITES=1")
    store = GitHubStore(args.repo, args.branch, os.environ.get("GITHUB_TOKEN"))
    state, sha = store.read()
    proposal = plan(state)
    if args.apply and proposal["assignments"]:
        store.save(apply_plan(state, proposal), sha)
    print(json.dumps({"source": "GitHub", "branch": args.branch,
                      "revision": state.get("revision", 0), **proposal},
                     ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except CoordinationError as error:
        print("Coordination blocked: " + str(error), file=sys.stderr)
        sys.exit(2)
