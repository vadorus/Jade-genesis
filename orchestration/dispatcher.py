"""Conservative multi-agent work planner. No writes to GitHub or agent execution.

Use --github to refresh live reservation metadata before preparing assignments.
Quotas and agent availability are declared explicitly, never inferred.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import unicodedata
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

AGENTS = ("Claude", "Codex", "Antigravity")
ACTIVE = ("Réservée", "En cours", "En revue")
KINDS = ("architecture", "security", "implementation", "testing", "documentation")


class PlanningError(ValueError):
    pass


def zone_key(value: str) -> str:
    if not isinstance(value, str) or not value or value.startswith("/") or "\\" in value or ":" in value or "*" in value or "?" in value:
        raise PlanningError(f"Invalid zone: {value!r}")
    value = unicodedata.normalize("NFC", value)
    if not re.fullmatch(r"[\w./-]+/?", value, re.UNICODE) or any(p in ("", ".", "..") for p in value.rstrip("/").split("/")):
        raise PlanningError(f"Invalid zone: {value!r}")
    return unicodedata.normalize("NFC", value.rstrip("/")).casefold()


def overlaps(a: list[str], b: list[str]) -> bool:
    return any(x == y or x.startswith(y + "/") or y.startswith(x + "/")
               for x in map(zone_key, a) for y in map(zone_key, b))


def paths(value: object) -> list[str]:
    if not isinstance(value, list) or not value:
        raise PlanningError("Zones must be a nonempty list")
    for zone in value:
        zone_key(zone)
    return value


def repo_key(value: object) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", value):
        raise PlanningError(f"Invalid repository: {value!r}")
    return value.casefold()


def api_pages(repo: str, endpoint: str, token: str | None, fetch=None) -> list[dict]:
    if fetch is None:
        def fetch(url, headers):
            with urlopen(Request(url, headers=headers), timeout=15) as response:
                return json.load(response)
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "jade-orchestration-read-only", "X-GitHub-Api-Version": "2022-11-28"}
    if token:
        headers["Authorization"] = "Bearer " + token
    result = []
    for page in range(1, 32):
        separator = "&" if "?" in endpoint else "?"
        url = f"https://api.github.com/repos/{repo}/{endpoint}{separator}{urlencode({'per_page': 100, 'page': page})}"
        items = fetch(url, headers)
        if not isinstance(items, list):
            raise PlanningError(f"Invalid/incomplete GitHub response: {endpoint}")
        result.extend(items)
        if len(items) < 100:
            return result
    raise PlanningError(f"GitHub pagination limit reached: {endpoint}")


def issue_fields(body: str) -> tuple[str, list[str]] | None:
    # Recognize imperfect attempts conservatively; a trusted malformed reservation halts planning.
    body = unicodedata.normalize("NFC", body)
    if not re.search(r"r[ée]servation[- ]?[ée]tat", body, flags=re.IGNORECASE):
        return None
    matches = re.findall(r"^Réservation-état:\s*(.+?)\s*$", body, re.MULTILINE)
    if len(matches) != 1 or matches[0] not in ACTIVE + ("Terminée", "Libérée"):
        raise PlanningError("Malformed reservation state")
    if matches[0] not in ACTIVE:
        return None
    zones = re.findall(r"^Zones:\s*(.+?)\s*$", body, re.MULTILINE)
    if len(zones) != 1:
        raise PlanningError("Malformed reservation zones")
    scopes = [z.strip() for z in zones[0].split(",")]
    paths(scopes)
    return matches[0], scopes


def github_claims(repositories: list[str], trusted_users: list[str], token: str | None = None, fetch=None) -> list[dict]:
    """Read open issue claims and PR files. Fail closed on errors/incomplete snapshots."""
    trusted = {u.casefold() for u in trusted_users}
    claims = []
    for repo in sorted(set(repositories)):
        repo_key(repo)
        owner = repo.split("/")[0].casefold()
        for issue in api_pages(repo, "issues?state=open", token, fetch):
            if "pull_request" in issue:
                continue
            author = str((issue.get("user") or {}).get("login") or "").casefold()
            # Untrusted accounts cannot reserve files by creating public issues.
            if author not in trusted | {owner}:
                continue
            try:
                record = issue_fields(issue.get("body") or "")
            except PlanningError as exc:
                raise PlanningError(f"Trusted issue #{issue.get('number')} in {repo}: {exc}") from exc
            if record:
                claims.append({"repo": repo, "task_id": f"issue:{issue['number']}", "zones": record[1]})
        for pr in api_pages(repo, "pulls?state=open", token, fetch):
            number = pr["number"]
            listed = api_pages(repo, f"pulls/{number}/files", token, fetch)
            if len(listed) != pr.get("changed_files"):
                # Pulls.list may omit changed_files; fetch the exact PR if needed via /pulls/<n>.
                if pr.get("changed_files") is not None:
                    raise PlanningError(f"Incomplete file inventory: {repo} PR #{number}")
            files = list({p for x in listed for p in (x.get("filename"), x.get("previous_filename")) if p})
            if not files or len(listed) >= 3000:
                raise PlanningError(f"Unusable PR file inventory: {repo} PR #{number}")
            claims.append({"repo": repo, "task_id": f"pr:{number}", "zones": files})
    return claims


def plan(state: dict, external_claims: list[dict] | None = None) -> dict:
    tasks = state.get("tasks", [])
    profiles = state.get("agents", [])
    if not isinstance(tasks, list) or not isinstance(profiles, list):
        raise PlanningError("Tasks and agents must be lists")
    agents = {}
    for entry in profiles:
        ident = entry.get("id")
        if ident not in AGENTS or ident in agents:
            raise PlanningError(f"Invalid or duplicate agent: {ident!r}")
        quota = entry.get("quota_units")
        if quota is not None and (type(quota) is not int or quota < 0):
            raise PlanningError(f"Invalid quota for {ident}")
        if entry.get("status") not in ("available", "busy", "offline", "quota_exhausted"):
            raise PlanningError(f"Invalid status for {ident}")
        agents[ident] = dict(entry)
    remaining = {a: (v["quota_units"] if v["status"] == "available" else None) for a, v in agents.items()}
    index = {}
    for task in tasks:
        tid = task.get("id")
        if not isinstance(tid, str) or not tid or tid in index:
            raise PlanningError(f"Invalid or duplicate task: {tid!r}")
        repo_key(task.get("repo"))
        paths(task.get("zones"))
        if task.get("kind") not in KINDS:
            raise PlanningError(f"Unknown task kind: {tid}")
        if type(task.get("estimated_units")) is not int or task["estimated_units"] < 1:
            raise PlanningError(f"Invalid estimate: {tid}")
        if type(task.get("priority", 50)) is not int or not 0 <= task.get("priority", 50) <= 100:
            raise PlanningError(f"Invalid priority: {tid}")
        index[tid] = task
    claims = list(state.get("claims", [])) + list(external_claims or [])
    for claim in claims:
        repo_key(claim.get("repo"))
        paths(claim.get("zones"))
    output = {"status": "proposal_only", "assignments": [], "blocked": [], "remaining_quota": remaining,
              "no_agents_started": True, "no_github_writes": True}
    for task in sorted(tasks, key=lambda t: (-t.get("priority", 50), t["id"])):
        tid = task["id"]
        def blocked(reason, details=None):
            output["blocked"].append({"task_id": tid, "reason": reason, **({"details": details} if details else {})})
        if task.get("status", "queued") != "queued":
            blocked("not_queued")
            continue
        if task.get("authorized") is not True:
            blocked("requires_human_authorization")
            continue
        deps = task.get("dependencies", [])
        if not isinstance(deps, list) or any(d not in index or index[d].get("status") != "done" for d in deps):
            blocked("dependencies_unmet")
            continue
        conflicts = [c["task_id"] for c in claims if repo_key(c["repo"]) == repo_key(task["repo"])
                     and c.get("task_id") not in (tid, f"issue:{tid.lstrip('#')}")
                     and overlaps(task["zones"], c["zones"])]
        if conflicts:
            blocked("reserved_zone_conflict", sorted(set(conflicts)))
            continue
        if task["kind"] in ("architecture", "security"):
            candidates, reviewer = ["Claude"], "Codex"
        else:
            candidates, reviewer = ["Codex", "Antigravity"], "Claude"
            if task.get("preferred_writer") in candidates:
                candidates.remove(task["preferred_writer"])
                candidates.insert(0, task["preferred_writer"])
        writer = next((a for a in candidates if remaining.get(a) is not None and remaining[a] >= task["estimated_units"]), None)
        if not writer:
            blocked("no_writer_with_confirmed_quota")
            continue
        if reviewer == writer or remaining.get(reviewer) is None or remaining[reviewer] < 1:
            blocked("reviewer_unavailable_or_quota_unknown")
            continue
        remaining[writer] -= task["estimated_units"]
        remaining[reviewer] -= 1
        claims.append({"task_id": tid, "repo": task["repo"], "zones": task["zones"]})
        output["assignments"].append({"task_id": tid, "repo": task["repo"], "writer": writer,
                                      "reviewer": reviewer, "zones": task["zones"],
                                      "estimated_units": task["estimated_units"],
                                      "next_action": "Review proposed reservation and hand off in agent client; no agent was launched."})
    return output


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", required=True, type=Path)
    parser.add_argument("--github", action="store_true", help="Read GitHub issues and PR files (no writes)")
    options = parser.parse_args(argv)
    try:
        state = json.loads(options.state.read_text(encoding="utf-8"))
        external = None
        if options.github:
            external = github_claims(sorted({x["repo"] for x in state["tasks"]}),
                                     state.get("trusted_issue_authors", []), os.getenv("GITHUB_TOKEN"))
        output = plan(state, external)
        output["snapshot"] = "github_live_metadata" if options.github else "simulation_no_github_snapshot"
        print(json.dumps(output, ensure_ascii=False, indent=2))
        return 0
    except (PlanningError, OSError, KeyError, ValueError) as exc:
        print(json.dumps({"status": "blocked", "error": str(exc), "no_github_writes": True}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
