"""Conservative multi-agent work planner. No writes to GitHub or agent execution.

Use --github to refresh live reservation metadata before preparing assignments.
Quotas and agent availability are declared explicitly, never inferred.
"""
from __future__ import annotations

import argparse
import hashlib
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
    if not re.search(r"^\s*\**\s*r[ée]servation[- ]?[ée]tat\s*\**\s*:", body, flags=re.IGNORECASE | re.MULTILINE):
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


def issue_field(body: str, name: str) -> str | None:
    values = [line[len(name) + 2:] for line in (body or "").splitlines()
              if line.startswith(name + ": ")]
    return values[0] if len(values) == 1 and values[0] else None


def approval_fingerprint(body: str, zones: list[str]) -> str:
    """Hash of the exact issue body AND canonical zones, recorded by its owner."""
    payload = json.dumps({"body": body, "zones": [zone_key(z) for z in zones]},
                         ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def verified_approval(issue: dict, zones: list[str], comments: list[dict]) -> bool:
    """Accept only direct owner comments bound to the current issue body and zones.

    The 12-hex phone form binds 48 bits of the SHA-256 digest. The legacy
    two-line full SHA-256 form remains supported for existing owner approvals.
    Comments posted through GitHub Apps are never accepted, even as OWNER.
    """
    expected = approval_fingerprint(issue.get("body") or "", zones)
    for comment in comments:
        if ((comment.get("user") or {}).get("login") != "vadorus" or
                comment.get("author_association") != "OWNER" or
                comment.get("performed_via_github_app") is not None):
            continue
        body = comment.get("body") or ""
        short = re.fullmatch(r"/autoriser ([0-9a-f]{12})", body)
        full = re.fullmatch(r"/autoriser\r?\nsha256:([0-9a-f]{64})\s*", body)
        if ((short and short.group(1) == expected[:12]) or
                (full and full.group(1) == expected)):
            return True
    return False


def github_claims(repositories: list[str], trusted_users: list[str], token: str | None = None,
                  fetch=None, tasks: list[dict] | None = None) -> list[dict]:
    """Read verified authorizations, issue reservations and exact PR file inventories.

    All reads must succeed; no GitHub writes. Missing API token always fails closed.
    The binding is a dated owner-authored command including the exact body/zone hash.
    """
    if not token:
        raise PlanningError("GITHUB_TOKEN required for authoritative GitHub mode")
    trusted = {u.casefold() for u in trusted_users} | {"vadorus"}
    claims = []
    tasks = tasks or []
    for repo in sorted(set(repositories)):
        repo_key(repo)
        owner = repo.split("/")[0].casefold()
        opened_issues = {}
        for issue in api_pages(repo, "issues?state=open", token, fetch):
            if "pull_request" in issue:
                continue
            number = issue["number"]
            opened_issues[number] = issue
            author = str((issue.get("user") or {}).get("login") or "").casefold()
            if author not in trusted | {owner}:
                continue
            try:
                record = issue_fields(issue.get("body") or "")
            except PlanningError as exc:
                raise PlanningError(f"Trusted issue #{number} in {repo}: {exc}") from exc
            if record:
                claims.append({"repo": repo, "task_id": f"issue:{number}",
                               "zones": record[1], "kind": "issue", "issue_number": number,
                               "issue_writer": issue_field(issue.get("body") or "", "Responsable-IA"),
                               "authorized": False})
        for task in tasks:
            if repo_key(task.get("repo")) != repo_key(repo):
                continue
            match = re.fullmatch(r"#([1-9]\d*)", task["id"])
            if not match:
                raise PlanningError("GitHub task must reference an issue: " + task["id"])
            issue_no = int(match.group(1))
            issue = opened_issues.get(issue_no)
            if not issue or "pull_request" in issue:
                raise PlanningError("Missing open task issue: " + task["id"])
            if str((issue.get("user") or {}).get("login") or "").casefold() not in trusted | {owner}:
                raise PlanningError("Untrusted task issue: " + task["id"])
            record = issue_fields(issue.get("body") or "")
            if not record:
                raise PlanningError("Task issue has no active reservation: " + task["id"])
            # The scope named by GitHub must cover the proposed task.
            if not all(any(zone_key(x) == zone_key(z) or
                           zone_key(x).startswith(zone_key(z) + "/")
                           for z in record[1]) for x in task["zones"]):
                raise PlanningError("Task outside issue scope: " + task["id"])
            comments = api_pages(repo, f"issues/{issue_no}/comments", token, fetch)
            approved = verified_approval(issue, record[1], comments)
            entry = next((claim for claim in claims
                          if claim["task_id"] == f"issue:{issue_no}"), None)
            if entry is None:
                raise PlanningError("Task reservation missing from claims")
            entry["authorized"] = approved
        for summary in api_pages(repo, "pulls?state=open", token, fetch):
            number = summary["number"]
            # pulls.list omits changed_files: fetch each PR individually.
            meta_url = f"https://api.github.com/repos/{repo}/pulls/{number}"
            headers = {"Accept": "application/vnd.github+json",
                       "User-Agent": "jade-orchestration-read-only",
                       "X-GitHub-Api-Version": "2022-11-28",
                       "Authorization": "Bearer " + token}
            if fetch is None:
                with urlopen(Request(meta_url, headers=headers), timeout=15) as response:
                    detailed = json.load(response)
            else:
                detailed = fetch(meta_url, headers)
            if (not isinstance(detailed, dict) or detailed.get("number") != number or
                    type(detailed.get("changed_files")) is not int or
                    detailed["changed_files"] < 0):
                raise PlanningError(f"Missing changed_files in PR #{number} of {repo}")
            listed = api_pages(repo, f"pulls/{number}/files", token, fetch)
            if len(listed) != detailed["changed_files"] or len(listed) >= 3000:
                raise PlanningError(f"Incomplete file inventory: {repo} PR #{number}")
            files = list({p for x in listed
                          for p in (x.get("filename"), x.get("previous_filename")) if p})
            if not files:
                raise PlanningError(f"Unusable PR file inventory: {repo} PR #{number}")
            body = detailed.get("body") or ""
            task_link = issue_field(body, "Tâche")
            author_label = issue_field(body, "Auteur-IA")
            linked_issue = None
            if task_link and re.fullmatch(r"#[1-9]\d*", task_link):
                linked_issue = opened_issues.get(int(task_link[1:]))
            assigned = issue_field((linked_issue or {}).get("body") or "", "Responsable-IA")
            branch = (detailed.get("head") or {}).get("ref") or ""
            head_repo = ((detailed.get("head") or {}).get("repo") or {}).get("full_name")
            prefixes = {"Claude": "claude/", "Codex": "codex/", "Antigravity": "antigravity/"}
            valid_self = bool(linked_issue and task_link and assigned and
                              author_label == assigned and
                              branch.startswith(prefixes.get(assigned, "\0")) and
                              head_repo and repo_key(head_repo) == repo_key(repo) and
                              str(((linked_issue or {}).get("user") or {}).get("login") or "").casefold() in trusted)
            claims.append({"repo": repo, "task_id": f"pr:{number}", "zones": files,
                           "kind": "pr", "linked_task": task_link,
                           "writer": author_label, "verified_self": valid_self})
    return claims


def safe_fallback_review(task: dict) -> bool:
    """Allow Gemini review only for explicitly whitelisted, narrow file zones.

    Never allow a broad docs/ or tests/ reservation that could cover restricted
    policy files. All zones must be inside docs/ or tests/ and respect exclusions.
    """
    if task["kind"] not in ("documentation", "testing"):
        return False
    zones = task.get("zones")
    if not isinstance(zones, list) or not zones:
        return False
    for value in zones:
        try:
            zone = zone_key(value)
        except PlanningError:
            return False
        if zone.startswith("docs/"):
            # AI coordination policy documents are sensitive at any depth.
            if re.fullmatch(r"ai_.*\.md", zone.rsplit("/", 1)[-1]):
                return False
        elif zone.startswith("tests/"):
            if zone == "tests/test_ai_preflight.py":
                return False
        else:
            return False
        # Reject a bare parent scope, even with a trailing slash: it would
        # include the excluded files. Any orchestration/ zone is also rejected.
        if zone in ("docs", "tests", "orchestration"):
            return False
    return True


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
        github_mode = external_claims is not None
        if github_mode:
            # JSON authorized=true has NO standing when reading GitHub.
            owner_claim = next((c for c in external_claims
                                if c.get("kind") == "issue"
                                and repo_key(c["repo"]) == repo_key(task["repo"])
                                and c["task_id"] == f"issue:{tid.lstrip('#')}"), None)
            if not owner_claim or owner_claim.get("authorized") is not True:
                blocked("github_authorization_missing_or_stale")
                continue
        elif task.get("authorized") is not True:
            blocked("requires_human_authorization")
            continue
        deps = task.get("dependencies", [])
        if not isinstance(deps, list) or any(d not in index or index[d].get("status") != "done" for d in deps):
            blocked("dependencies_unmet")
            continue
        linked_prs = [c for c in claims if c.get("kind") == "pr"
                      and repo_key(c["repo"]) == repo_key(task["repo"])
                      and c.get("linked_task") == tid]
        if len(linked_prs) > 1:
            blocked("duplicate_pr_for_task", sorted(c["task_id"] for c in linked_prs))
            continue
        own_pr = linked_prs[0] if len(linked_prs) == 1 and linked_prs[0].get("verified_self") else None
        if own_pr and task.get("assigned_writer") and own_pr.get("writer") != task["assigned_writer"]:
            own_pr = None
        conflicts = [c["task_id"] for c in claims
                     if repo_key(c["repo"]) == repo_key(task["repo"])
                     and c.get("task_id") not in (tid, f"issue:{tid.lstrip('#')}")
                     and c is not own_pr and overlaps(task["zones"], c["zones"])]
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
        if (reviewer == "Claude" and
                (remaining.get("Claude") is None or remaining["Claude"] < 1) and
                safe_fallback_review(task)):
            reviewer = "Antigravity"
            candidates = ["Codex"]  # Gemini cannot review her own changes.
        writer = next((name for name in candidates
                       if name != reviewer and remaining.get(name) is not None
                       and remaining[name] >= task["estimated_units"]), None)
        if not writer:
            blocked("no_writer_with_confirmed_quota")
            continue
        if remaining.get(reviewer) is None or remaining[reviewer] < 1:
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
    parser.add_argument("--prepare-approval", type=int, metavar="ISSUE_NUMBER",
                        help="Read an issue and print a body-bound /autoriser command; no write")
    options = parser.parse_args(argv)
    try:
        state = json.loads(options.state.read_text(encoding="utf-8"))
        if options.prepare_approval:
            token = os.getenv("GITHUB_TOKEN")
            if not token:
                raise PlanningError("GITHUB_TOKEN required")
            matches = [task for task in state["tasks"]
                       if task.get("id") == "#" + str(options.prepare_approval)]
            if len(matches) != 1:
                raise PlanningError("Task issue not present exactly once in registry")
            repo = matches[0]["repo"]
            issues = api_pages(repo, "issues?state=open", token)
            issue = next((x for x in issues if x.get("number") == options.prepare_approval
                          and "pull_request" not in x), None)
            if not issue or (issue.get("user") or {}).get("login") != "vadorus":
                raise PlanningError("No open owner-authored issue with this number")
            reservation = issue_fields(issue.get("body") or "")
            if not reservation:
                raise PlanningError("No active reservation on issue")
            print("/autoriser " + approval_fingerprint(issue["body"], reservation[1])[:12])
            return 0
        external = None
        if options.github:
            external = github_claims(sorted({x["repo"] for x in state["tasks"]}),
                                     state.get("trusted_issue_authors", []), os.getenv("GITHUB_TOKEN"),
                                     tasks=state["tasks"])
        output = plan(state, external)
        output["snapshot"] = "github_live_metadata" if options.github else "simulation_no_github_snapshot"
        print(json.dumps(output, ensure_ascii=False, indent=2))
        return 0
    except (PlanningError, OSError, KeyError, ValueError) as exc:
        print(json.dumps({"status": "blocked", "error": str(exc), "no_github_writes": True}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
