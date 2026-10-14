#!/usr/bin/env python3
"""Contrôle de coordination IA en lecture seule, depuis la copie locale d'un dépôt.

Usage : python scripts/ai_preflight.py
Nécessite git et facultativement GitHub CLI 'gh' authentifié.
NE PAS exécuter dans le pilote Agents API Godot (AGENTS.md y interdit gh).
Si l'inventaire distant est indisponible, ne démarrer aucune implémentation.
"""
from __future__ import annotations

import shutil
import subprocess
import sys


def run(label: str, argv: list[str]) -> bool:
    try:
        result = subprocess.run(argv, capture_output=True, text=True,
                                timeout=30, check=False)
    except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
        print(f"\n{label}: IMPOSSIBLE ({exc})")
        return False
    print(f"\n{label}: {'OK' if result.returncode == 0 else 'ÉCHEC'}")
    output = (result.stdout or result.stderr).strip()
    print(output[:15000] if output else "(aucune donnée)")
    return result.returncode == 0


def main() -> int:
    print("PRÉCONTRÔLE MULTI-IA — commandes en lecture seule")
    if not run("Dépôt local", ["git", "rev-parse", "--show-toplevel"]):
        return 2
    for label, command in [
        ("Branche actuelle", ["git", "branch", "--show-current"]),
        ("Remote origin", ["git", "remote", "get-url", "origin"]),
        ("Modifications locales", ["git", "status", "--short"]),
        ("Branches connues", ["git", "branch", "-a"]),
    ]:
        run(label, command)
    if not shutil.which("gh"):
        print("\nSTOP : gh absent, PR et issues GitHub non vérifiées.")
        return 3
    # L'authentification est vérifiée sans afficher de données sensibles.
    try:
        auth = subprocess.run(["gh", "auth", "status"], capture_output=True,
                              text=True, timeout=30, check=False)
    except (FileNotFoundError, subprocess.TimeoutExpired):
        print("\nSTOP : statut d'authentification GitHub inaccessible.")
        return 3
    if auth.returncode != 0:
        print("\nSTOP : GitHub CLI non authentifié.")
        return 3
    prs = run("PR ouvertes et brouillons",
              ["gh", "pr", "list", "--state", "open", "--limit", "100",
               "--json", "number,title,headRefName,baseRefName,isDraft,updatedAt,url"])
    issues = run("Issues ouvertes",
                 ["gh", "issue", "list", "--state", "open", "--limit", "100",
                  "--json", "number,title,assignees,updatedAt,url"])
    if not (prs and issues):
        print("\nSTOP : vérification GitHub incomplète.")
        return 3
    print("\nInventaire terminé. Lire les PR concernées et demander une attribution avant toute écriture.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
