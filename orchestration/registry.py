"""GitHub-backed orchestration registry. Dry-run by default; no agent launches.

All state transitions create one commit based on the previously observed HEAD.
GitHub's non-forced ref update atomically rejects competing sibling commits.
The registry branch must be independently initialized as an EMPTY ORPHAN ROOT.
"""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import PurePosixPath
import re
import sys
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen
from uuid import uuid4

from dispatcher import (PlanningError, issue_field, issue_fields, overlaps,
                        repo_key, verified_approval, zone_key)

STATE_REPO = "vadorus/Jade-genesis"
STATE_BRANCH = "orchestration/state"
STATE_PREFIX = "registry/"
TASK_STATES = frozenset({
    "queued", "claimed", "in_progress", "review", "interrupted", "done", "cancelled"
})
ACTIVE_STATES = frozenset({"claimed", "in_progress", "review"})
LEASE_STATES = frozenset({"claimed", "in_progress"})
AGENTS = frozenset({"Codex", "Claude", "Antigravity", "Gemini"})
LEASE_HOURS = 2
HEARTBEAT_HOURS = 6


class RegistryError(RuntimeError):
    pass


class ConcurrentWrite(RegistryError):
    pass


def now_utc():
    return datetime.now(timezone.utc)


def timestamp(value):
    return value.astimezone(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")


def parse_time(value):
    if not isinstance(value, str):
        raise RegistryError("Horodatage manquant ou invalide.")
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            raise ValueError("naive date")
        return dt.astimezone(timezone.utc)
    except ValueError as exc:
        raise RegistryError("Horodatage invalide.") from exc


def validate_path(path, branch=STATE_BRANCH):
    """Fail before any write, including dry-run."""
    if branch != STATE_BRANCH:
        raise RegistryError("Écriture interdite hors orchestration/state.")
    if not isinstance(path, str) or not path.startswith(STATE_PREFIX):
        raise RegistryError("Écriture interdite hors registry/.")
    parts = PurePosixPath(path).parts
    if (path.startswith("/") or "\\" in path or not path.endswith(".json")
            or any(p in ("", ".", "..") for p in path.split("/"))
            or len(parts) < 3 or parts[1] not in ("tasks", "agents", "history")):
        raise RegistryError("Chemin de registre non autorisé : " + str(path))
    return path


def task_key(repo, issue):
    repo_key(repo)
    if not isinstance(issue, int) or isinstance(issue, bool) or issue < 1:
        raise RegistryError("Numéro d'issue invalide.")
    return repo.replace("/", "__").casefold() + "__" + str(issue)


def task_path(repo, issue):
    return validate_path("registry/tasks/" + task_key(repo, issue) + ".json")


def agent_path(agent):
    if agent not in AGENTS:
        raise RegistryError("Agent inconnu.")
    return validate_path("registry/agents/" + agent.lower() + ".json")


def scope_overlap(left, right):
    return overlaps(left, right)


class GitHub:
    """Only HTTPS REST endpoints on api.github.com; injectable request for tests."""

    def __init__(self, token=None, request=None):
        self.token = token
        self.request_override = request

    def call(self, method, endpoint, payload=None):
        if self.request_override is not None:
            return self.request_override(method, endpoint, payload)
        url = "https://api.github.com/" + endpoint.lstrip("/")
        headers = {
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "orch-registry/0.1",
        }
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        if method != "GET" and not self.token:
            raise RegistryError("GITHUB_TOKEN requis pour toute écriture.")
        body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
        if body is not None:
            headers["Content-Type"] = "application/json"
        try:
            with urlopen(Request(url, data=body, headers=headers, method=method), timeout=20) as res:
                raw = res.read()
                return json.loads(raw) if raw else {}
        except HTTPError as exc:
            if method == "PATCH" and exc.code in (409, 422):
                raise ConcurrentWrite("La branche a avancé : relecture obligatoire.") from exc
            raise RegistryError("GitHub HTTP " + str(exc.code) + " sur " + endpoint) from exc

    def pages(self, repo, endpoint):
        items = []
        for page in range(1, 32):
            sep = "&" if "?" in endpoint else "?"
            result = self.call("GET", "repos/" + repo + "/" + endpoint + sep +
                               "per_page=100&page=" + str(page))
            if not isinstance(result, list):
                raise RegistryError("Réponse paginée GitHub illisible.")
            items.extend(result)
            if len(result) < 100:
                return items
        raise RegistryError("Pagination GitHub incomplète : opération bloquée.")


class Snapshot:
    def __init__(self, head, tree_sha, entries, data):
        self.head = head
        self.tree_sha = tree_sha
        self.entries = entries
        self.data = data


class Registry:
    def __init__(self, api, apply=False, clock=now_utc, retries=3):
        self.api = api
        self.apply = apply
        self.clock = clock
        self.retries = retries

    def load(self):
        base = "repos/" + STATE_REPO + "/"
        ref = self.api.call("GET", base + "git/ref/heads/" + STATE_BRANCH)
        head = ref["object"]["sha"]
        commit = self.api.call("GET", base + "git/commits/" + head)
        tree_sha = commit["tree"]["sha"]
        tree = self.api.call("GET", base + "git/trees/" + tree_sha + "?recursive=1")
        if tree.get("truncated") or not isinstance(tree.get("tree"), list):
            raise RegistryError("Inventaire Git tronqué : abandon.")
        entries, data = {}, {}
        for entry in tree["tree"]:
            path = entry["path"]
            if entry["type"] == "tree":
                # The real GitHub recursive tree API includes directory nodes.
                # Only the expected registry directories are admissible.
                valid_dir = (path in ("registry", "registry/tasks", "registry/agents",
                                      "registry/history") or
                             (path.startswith("registry/history/") and
                              len(path.split("/")) == 3 and
                              re.fullmatch(r"[A-Za-z0-9_.-]+", path.split("/")[2]) and
                              path.split("/")[2] not in (".", "..")))
                if not valid_dir:
                    raise RegistryError("Répertoire non autorisé dans la branche état : " + path)
                continue
            if not path.startswith(STATE_PREFIX):
                raise RegistryError("Branche état contaminée par un fichier hors registry/.")
            if entry["type"] != "blob" or entry.get("mode", "100644") != "100644":
                raise RegistryError("Objet non régulier dans le registre.")
            # The single README is the explicitly authorized orphan-root seed.
            if path == "registry/README.md":
                continue
            validate_path(path)
            entries[path] = entry["sha"]
            blob = self.api.call("GET", base + "git/blobs/" + entry["sha"])
            if blob.get("encoding") != "base64":
                raise RegistryError("Encodage de blob inconnu.")
            data[path] = json.loads(base64.b64decode(blob["content"]).decode("utf-8"))
        return Snapshot(head, tree_sha, entries, data)

    def _event(self, action, task, agent, when, identifier=None):
        ident = identifier or uuid4().hex[:12]
        stamp = when.strftime("%Y%m%dT%H%M%S%fZ")
        name = re.sub(r"[^A-Za-z0-9_-]", "_", agent)
        path = validate_path("registry/history/" + task + "/" + stamp + "-" + name + "-" + ident + ".json")
        return path, {
            "action": action, "task": task, "agent": agent,
            "at": timestamp(when), "schema": 1
        }

    def transact(self, action, actor, mutate):
        """Callback re-executes after conflict. Never reuse a stale decision."""
        if self.apply and not self.api.token and self.api.request_override is None:
            raise RegistryError("GITHUB_TOKEN requis pour --apply.")
        for index in range(max(1, self.retries)):
            snap = self.load()
            when = self.clock()
            changes, outcome = mutate(snap.data, when)
            if not changes:
                raise RegistryError("Transition vide ou déjà appliquée.")
            for path in changes:
                validate_path(path)
                if path.startswith("registry/history/") and path in snap.data:
                    raise RegistryError("Historique immuable : événement déjà présent.")
            if not self.apply:
                return {"status": "proposal_only", "action": action,
                        "changes": sorted(changes), "base_sha": snap.head,
                        "no_github_writes": True, "result": outcome}
            base = "repos/" + STATE_REPO + "/"
            elements = [{"path": path, "mode": "100644", "type": "blob",
                         "content": json.dumps(value, ensure_ascii=False, sort_keys=True) + "\n"}
                        for path, value in sorted(changes.items())]
            tree = self.api.call("POST", base + "git/trees", {
                "base_tree": snap.tree_sha, "tree": elements
            })
            commit = self.api.call("POST", base + "git/commits", {
                "tree": tree["sha"], "parents": [snap.head],
                "message": "orch-registry: " + action + " by " + actor +
                           "\n\nAgent-IA: " + (actor if actor in AGENTS else "Codex")
            })
            try:
                # GitHub's non-forced fast-forward is the serialization point:
                # two siblings with the same parent cannot both fast-forward.
                self.api.call("PATCH", base + "git/refs/heads/" + STATE_BRANCH, {
                    "sha": commit["sha"], "force": False
                })
                return {"status": "applied", "head_sha": commit["sha"],
                        "action": action, "result": outcome}
            except ConcurrentWrite:
                if index + 1 >= self.retries:
                    raise
                continue
        raise ConcurrentWrite("Trop de conflits Git.")

    def status(self):
        snap = self.load()
        now = self.clock()
        tasks = [v for k, v in snap.data.items() if k.startswith("registry/tasks/")]
        agents = [v for k, v in snap.data.items() if k.startswith("registry/agents/")]
        for agent in agents:
            last = agent.get("heartbeat_at")
            if not last or now - parse_time(last) > timedelta(hours=HEARTBEAT_HOURS):
                agent["effective_status"] = "offline"
            else:
                agent["effective_status"] = agent["status"]
        return {"head_sha": snap.head, "taches": tasks, "agents": agents}

    def heartbeat(self, agent, status, quota):
        if agent not in AGENTS or status not in ("available", "busy", "offline", "quota_exhausted"):
            raise RegistryError("Agent ou statut invalide.")
        if type(quota) is not int or quota < 0:
            raise RegistryError("Quota déclaré invalide.")
        def mutate(data, now):
            path = agent_path(agent)
            old = data.get(path) or {}
            value = {"schema": 1, "agent": agent, "status": status,
                     "quota_declared": quota, "heartbeat_at": timestamp(now),
                     "current_task": old.get("current_task")}
            event_path, event = self._event("heartbeat", agent, agent, now)
            return {path: value, event_path: event}, {"agent": agent, "status": status}
        return self.transact("heartbeat", agent, mutate)

    def _issue_and_claims(self, repo, number, zones):
        # All checks repeat inside the transaction's callback, including retries.
        issue = self.api.call("GET", "repos/" + repo + "/issues/" + str(number))
        if issue.get("state") != "open" or issue.get("pull_request"):
            raise RegistryError("Issue absente, fermée ou non recevable.")
        if str((issue.get("user") or {}).get("login") or "").lower() != "vadorus":
            raise RegistryError("Auteur d'issue non fiable.")
        record = issue_fields(issue.get("body") or "")
        if record is None:
            raise RegistryError("Réservation d'issue non active.")
        if not all(any(zone_key(x) == zone_key(y) or
                       zone_key(x).startswith(zone_key(y) + "/") for y in record[1]) for x in zones):
            raise RegistryError("Zones hors du périmètre de l'issue.")
        comments = self.api.pages(repo, "issues/" + str(number) + "/comments")
        if not verified_approval(issue, record[1], comments):
            raise RegistryError("Autorisation humaine GitHub absente ou périmée.")
        conflicts = []
        for other in self.api.pages(repo, "issues?state=open"):
            if "pull_request" in other or other.get("number") == number:
                continue
            if str((other.get("user") or {}).get("login") or "").lower() != "vadorus":
                continue
            other_record = issue_fields(other.get("body") or "")
            if other_record and scope_overlap(zones, other_record[1]):
                conflicts.append("issue #" + str(other["number"]))
        task_prs = []
        for summary in self.api.pages(repo, "pulls?state=open"):
            n = summary["number"]
            detail = self.api.call("GET", "repos/" + repo + "/pulls/" + str(n))
            count = detail.get("changed_files")
            if type(count) is not int or count < 0 or count >= 3000:
                raise RegistryError("Inventaire de PR non vérifiable.")
            files = self.api.pages(repo, "pulls/" + str(n) + "/files")
            if len(files) != count:
                raise RegistryError("Inventaire de fichiers de PR incomplet.")
            linked = issue_field(detail.get("body") or "", "Tâche")
            if linked == "#" + str(number):
                task_prs.append(n)
            names = [v for f in files
                     for v in (f.get("filename"), f.get("previous_filename")) if v]
            if names and scope_overlap(zones, names):
                assigned = issue_field(issue.get("body") or "", "Responsable-IA")
                head = detail.get("head") or {}
                self_pr = (linked == "#" + str(number) and
                           issue_field(detail.get("body") or "", "Auteur-IA") == assigned and
                           head.get("ref", "").startswith((assigned or "?").lower() + "/") and
                           (head.get("repo") or {}).get("full_name", "").casefold() == repo.casefold())
                if not self_pr:
                    conflicts.append("PR #" + str(n))
        if len(task_prs) > 1:
            raise RegistryError("duplicate_pr_for_task")
        if conflicts:
            raise RegistryError("Chevauchement GitHub : " + ", ".join(conflicts))
        return issue

    def claim(self, repo, number, agent, branch_name=None):
        repo_key(repo)
        if agent not in AGENTS:
            raise RegistryError("Agent inconnu.")
        path = task_path(repo, number)
        ident = task_key(repo, number)
        if branch_name is not None and (not re.fullmatch(r"[a-z0-9][a-z0-9._/-]{2,120}", branch_name)
                                         or ".." in branch_name or branch_name.startswith("/")):
            raise RegistryError("Nom de branche de travail invalide.")
        def mutate(data, now):
            existing = data.get(path)
            if existing and existing.get("state") not in ("queued", "interrupted"):
                raise RegistryError("Tâche déjà réservée : " + existing.get("state", "inconnu"))
            if existing:
                zones = existing["zones"]
            else:
                raw = self.api.call("GET", "repos/" + repo + "/issues/" + str(number))
                record = issue_fields(raw.get("body") or "")
                if not record:
                    raise RegistryError("Issue sans réservation active.")
                zones = record[1]
            for key, other in data.items():
                if key == path or not key.startswith("registry/tasks/"):
                    continue
                if other.get("state") in ACTIVE_STATES and scope_overlap(zones, other["zones"]):
                    raise RegistryError("Réservation concurrente : " + key)
            issue = self._issue_and_claims(repo, number, zones)
            writer = issue_field(issue.get("body") or "", "Responsable-IA")
            reviewer = issue_field(issue.get("body") or "", "Relecteur-IA")
            if writer != agent or not reviewer or reviewer == agent:
                raise RegistryError("Auteur ou relecteur non conforme.")
            agent_data = data.get(agent_path(agent))
            if not agent_data:
                raise RegistryError("Heartbeat manquant.")
            if (agent_data.get("status") != "available" or
                    now - parse_time(agent_data["heartbeat_at"]) > timedelta(hours=HEARTBEAT_HOURS) or
                    agent_data.get("quota_declared", 0) < 1 or
                    agent_data.get("current_task") not in (None, ident)):
                raise RegistryError("Agent indisponible, hors ligne ou quota insuffisant.")
            current = existing or {}
            value = {
                "schema": 1, "repo": repo, "issue": number, "zones": zones,
                "state": "claimed", "writer": agent, "reviewer": reviewer,
                "branch": current.get("branch") or branch_name,
                "base_sha": current.get("base_sha"),
                "head_sha": current.get("head_sha"),
                "lease_until": timestamp(now + timedelta(hours=LEASE_HOURS)),
                "attempt": current.get("attempt", 0) + 1,
                "updated_at": timestamp(now), "updated_by": agent
            }
            worker = dict(agent_data, current_task=ident)
            ep, ev = self._event("claim", ident, agent, now)
            return {path: value, agent_path(agent): worker, ep: ev}, value
        return self.transact("claim", agent, mutate)

    def task_change(self, action, repo, number, agent, head_sha=None):
        path = task_path(repo, number)
        ident = task_key(repo, number)
        def mutate(data, now):
            previous = data.get(path)
            if not previous:
                raise RegistryError("Tâche inconnue.")
            if previous.get("writer") != agent:
                raise RegistryError("Seul l'auteur réservé peut modifier la tâche.")
            if previous["state"] not in ACTIVE_STATES:
                raise RegistryError("Tâche non active.")
            if previous["state"] in LEASE_STATES and (
                    not previous.get("lease_until") or
                    parse_time(previous["lease_until"]) <= now):
                raise RegistryError("Bail expiré : utiliser recover avant toute nouvelle réservation.")
            if previous["state"] == "review" and action != "release":
                raise RegistryError("Tâche en revue : pas de renouvellement ni seconde passation.")
            if action == "renew":
                state = previous["state"]
            elif action == "release":
                state = "queued"
            else:
                if not isinstance(head_sha, str) or not re.fullmatch(r"[0-9a-f]{40}", head_sha):
                    raise RegistryError("handoff exige un head_sha Git valide.")
                state = "review"
            updated = dict(previous, state=state, updated_at=timestamp(now),
                           updated_by=agent)
            updated["lease_until"] = (timestamp(now + timedelta(hours=LEASE_HOURS))
                                       if state in LEASE_STATES else None)
            if action == "handoff":
                updated["head_sha"] = head_sha
            changes = {path: updated}
            if action == "release":
                a = data.get(agent_path(agent))
                if a:
                    changes[agent_path(agent)] = dict(a, current_task=None)
            ep, ev = self._event(action, ident, agent, now)
            changes[ep] = ev
            return changes, updated
        return self.transact(action, agent, mutate)

    def recover(self):
        def mutate(data, now):
            changed = {}
            recovered = []
            for path, original in sorted(data.items()):
                if not path.startswith("registry/tasks/") or original.get("state") not in LEASE_STATES:
                    continue
                lease = original.get("lease_until")
                if not lease or parse_time(lease) >= now:
                    continue
                ident = task_key(original["repo"], original["issue"])
                writer = original.get("writer") or "Codex"
                changed[path] = dict(original, state="interrupted", lease_until=None,
                                     updated_at=timestamp(now), updated_by="recover")
                a = data.get(agent_path(writer))
                if a and a.get("current_task") == ident:
                    changed[agent_path(writer)] = dict(a, current_task=None)
                ep, ev = self._event("recover", ident, "recover", now)
                changed[ep] = ev
                recovered.append(ident)
                # One expired task is one transition and therefore one Git commit.
                break
            return changed, recovered
        return self.transact("recover", "Codex", mutate)


def parse_task(value, fallback_repo=STATE_REPO):
    match = re.fullmatch(r"(?:(?P<repo>[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+))?#(?P<n>[1-9]\d*)", value)
    if not match:
        raise RegistryError("Tâche attendue : #94 ou owner/repo#94.")
    return match.group("repo") or fallback_repo, int(match.group("n"))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Exécuter une transition autorisée sur GitHub")
    parser.add_argument("--repo", default=STATE_REPO, help="Dépôt de tâche par défaut")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("status")
    p = sub.add_parser("heartbeat")
    p.add_argument("agent", choices=sorted(AGENTS))
    p.add_argument("--status", required=True, choices=("available", "busy", "offline", "quota_exhausted"))
    p.add_argument("--quota", type=int, required=True)
    p = sub.add_parser("claim")
    p.add_argument("task")
    p.add_argument("agent", choices=sorted(AGENTS))
    p.add_argument("--branch", help="Branche de travail à conserver lors des reprises")
    for name in ("renew", "release", "handoff"):
        p = sub.add_parser(name)
        p.add_argument("task")
        p.add_argument("--agent", required=True, choices=sorted(AGENTS))
        if name == "handoff":
            p.add_argument("--head-sha", required=True)
    sub.add_parser("recover")
    options = parser.parse_args(argv)
    token = os.environ.get("GITHUB_TOKEN")
    try:
        if options.apply and not token:
            raise RegistryError("GITHUB_TOKEN requis avec --apply.")
        reg = Registry(GitHub(token), apply=options.apply)
        if options.command == "status":
            data = reg.status()
            print("Registre GitHub :", data["head_sha"][:12])
            for v in data["taches"]:
                print("Tâche", v["repo"] + "#" + str(v["issue"]), ":", v["state"], "—", v.get("writer") or "libre")
            for v in data["agents"]:
                print("Agent", v["agent"], ":", v["effective_status"], "— quota déclaré", v["quota_declared"])
            return 0
        if options.command == "heartbeat":
            result = reg.heartbeat(options.agent, options.status, options.quota)
        elif options.command == "recover":
            result = reg.recover()
        else:
            repo, issue = parse_task(options.task, options.repo)
            if options.command == "claim":
                result = reg.claim(repo, issue, options.agent, options.branch)
            else:
                result = reg.task_change(options.command, repo, issue, options.agent,
                                         getattr(options, "head_sha", None))
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (RegistryError, PlanningError, KeyError, ValueError) as exc:
        print("Opération bloquée : " + str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
