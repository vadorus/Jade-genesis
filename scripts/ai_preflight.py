#!/usr/bin/env python3
"""Inventaire GitHub complet en lecture seule. Aucun verrou ni autorisation implicite.

Usage: python scripts/ai_preflight.py --repo OWNER/NAME --base BRANCH
       [--task 123 --writer Codex --reviewer Claude --path scripts/Foo.gd]
       [--resume-pr 123]
Interdit dans un pilote qui interdit gh/réseau : l'orchestrateur exécute hors pilote.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import json
import re
import subprocess
import sys
import unicodedata

PRODUCTS = {'vadorus/addonhardwaregame2d': 'v013/demo-octobre',
            'vadorus/tech-empire-roblox': 'main', 'vadorus/Jade-genesis': 'main'}
AGENTS = ('Claude', 'Codex', 'Antigravity', 'Humain')
ACTIVE_RESERVATIONS = ('Réservée', 'En cours', 'En revue')
RESERVATION_STATES = ACTIVE_RESERVATIONS + ('Terminée', 'Libérée')
# Explicitly approved GitHub identities; no trust based on a free-text IA label.
TRUSTED_ISSUE_AUTHORS = ('vadorus',)
RESERVATION_MARKER = re.compile(r'^\s*\**\s*r[ée]servation[- ]?[ée]tat\s*\**\s*:', re.IGNORECASE)

def command(argv):
    result = subprocess.run(argv, capture_output=True, text=True, encoding='utf-8',
                            timeout=45, check=False)
    if result.returncode:
        raise RuntimeError('Commande de lecture échouée : ' + argv[0])
    return result.stdout.strip()

def api(path, pages=False):
    argv = ['gh', 'api', path]
    if pages:
        argv += ['--paginate', '--slurp']
    payload = json.loads(command(argv))
    return [row for page in payload for row in page] if pages else payload

def field(body, name):
    values = [line[len(name)+1:].strip() for line in (body or '').splitlines()
              if line.startswith(name + ':')]
    return values[0] if len(values) == 1 and values[0] else None

def paths_of(files):
    return {path for item in files for path in (item['filename'], item.get('previous_filename')) if path}

def parse_zones(value):
    """NFC names, no markup/whitespace/globs; directories end with /."""
    if not isinstance(value, str) or not value:
        return None
    zones = [unicodedata.normalize('NFC', x.strip()) for x in value.split(',')]
    for zone in zones:
        parts = zone.rstrip('/').split('/')
        if (not zone or zone.startswith('/') or '\\' in zone or '//' in zone or
                not parts or any(p in ('', '.', '..') for p in parts) or
                not all(ch.isalpha() or ch.isdecimal() or ch in '._-/' for ch in zone)):
            return None
    return zones

def _zone_key(value):
    """Unicode NFC + casefold. Workflows must apply the same rule."""
    return unicodedata.normalize('NFC', value.rstrip('/')).casefold()

def trusted_issue(issue):
    return str((issue.get('user') or {}).get('login') or '').casefold() in TRUSTED_ISSUE_AUTHORS

def reservation_record(issue):
    """Return active reservation, or None. Malformed attempted header is fatal."""
    number, body = issue.get('number', '?'), issue.get('body') or ''
    attempted = [line for line in body.splitlines() if RESERVATION_MARKER.match(line)]
    if not attempted:
        return None
    valid_header = re.fullmatch(
        r'Réservation-état: (Réservée|En cours|En revue|Terminée|Libérée)', attempted[0]
    ) if len(attempted) == 1 else None
    if not valid_header:
        raise ValueError('Réservation illisible : issue #' + str(number))
    raw_zones = field(body, 'Zones')
    parsed = parse_zones(raw_zones) if raw_zones else None
    if raw_zones is not None and not parsed:
        raise ValueError('Réservation illisible : issue #' + str(number))
    if valid_header.group(1) not in ACTIVE_RESERVATIONS:
        return None
    if not parsed:
        raise ValueError('Réservation illisible : issue #' + str(number))
    return {'state': valid_header.group(1), 'zones': parsed}

def _contains_zone(parent, child):
    """A bare path may be a file or folder; match at '/' boundaries only."""
    parent_key, child_key = _zone_key(parent), _zone_key(child)
    return child_key == parent_key or child_key.startswith(parent_key + '/')

def within(path, zones):
    return any(_contains_zone(zone, path) for zone in zones)

def zones_overlap(first, second):
    """Treat ambiguous bare directories as reserved, including descendants."""
    return any(_contains_zone(left, right) or _contains_zone(right, left)
               for left in first for right in second)

def conflicting_reservations(task_number, task_zones, issues):
    """Malformed trusted reservation blocks; untrusted issues are ignored."""
    errors = []
    for other in issues:
        if (other.get('number') == task_number or other.get('state') != 'open'
                or 'pull_request' in other or not trusted_issue(other)):
            continue
        try:
            record = reservation_record(other)
        except ValueError as exc:
            errors.append(str(exc))
            continue
        if record and zones_overlap(task_zones, record['zones']):
            errors.append('Conflit de réservation avec issue #' + str(other['number']))
    return errors

def inspect(args):
    if PRODUCTS.get(args.repo) != args.base:
        raise RuntimeError('Dépôt ou branche produit inattendu.')
    root = command(['git', 'rev-parse', '--show-toplevel'])
    branch = command(['git', 'branch', '--show-current'])
    sha = command(['git', 'rev-parse', 'HEAD'])
    origin = command(['git', 'remote', 'get-url', 'origin'])
    clean_origin = origin.removesuffix('.git').rstrip('/')
    if clean_origin not in ('https://github.com/' + args.repo, 'git@github.com:' + args.repo):
        # Never print an unrecognized remote; it may contain credentials.
        raise RuntimeError('origin ne correspond pas au dépôt attendu.')
    status = command(['git', 'status', '--porcelain'])
    base = api(f'repos/{args.repo}/branches/{args.base.replace("/", "%2F")}')
    prs = api(f'repos/{args.repo}/pulls?state=open&per_page=100', True)
    issues = [x for x in api(f'repos/{args.repo}/issues?state=open&per_page=100', True)
              if 'pull_request' not in x]
    recent = api(f'repos/{args.repo}/commits?sha={args.base.replace("/", "%2F")}&per_page=20')
    closed = api(f'repos/{args.repo}/pulls?state=closed&sort=updated&direction=desc&per_page=30')
    inventories = {}
    for summary in prs:
        pr = api(f'repos/{args.repo}/pulls/{summary["number"]}')
        files = api(f'repos/{args.repo}/pulls/{pr["number"]}/files?per_page=100', True)
        if len(files) != pr['changed_files']:
            raise RuntimeError('Inventaire tronqué : PR #' + str(pr['number']))
        inventories[pr['number']] = {'pr': pr, 'paths': paths_of(files)}
    errors = []
    if args.paths and (not parse_zones(','.join(args.paths)) or any(p.endswith('/') for p in args.paths)):
        errors.append('Fichiers prévus invalides : chemins relatifs exacts sans traversée/glob.')
    if status:
        errors.append('Copie locale modifiée : préserver les changements et isoler le chantier.')
    resumed = inventories.get(args.resume_pr) if args.resume_pr else None
    if args.resume_pr and (not resumed or resumed['pr']['base']['ref'] != args.base or
            resumed['pr']['head']['repo']['full_name'] != args.repo or
            resumed['pr']['head']['sha'] != sha or resumed['pr']['head']['ref'] != branch):
        errors.append('Reprise : la copie doit correspondre exactement à la tête et branche de la PR existante.')
    if args.task:
        if not args.writer or not args.reviewer or args.writer == args.reviewer or not args.paths:
            errors.append('Auteur, relecteur distinct et fichiers prévus obligatoires.')
        issue = api(f'repos/{args.repo}/issues/{args.task}')
        if not trusted_issue(issue):
            errors.append('Auteur non autorisé : issue #' + str(args.task))
        if 'pull_request' in issue or issue['state'] != 'open':
            errors.append('Référence de tâche différente d’une issue ouverte.')
        if field(issue.get('body'), 'Responsable-IA') != args.writer:
            errors.append('Auteur différent de la réservation de l’issue.')
        if field(issue.get('body'), 'Relecteur-IA') != args.reviewer:
            errors.append('Relecteur différent de la réservation de l’issue.')
        try:
            own_record = reservation_record(issue) if trusted_issue(issue) else None
        except ValueError as exc:
            errors.append(str(exc))
            own_record = None
        if not own_record:
            errors.append('Réservation inactive ou absente.')
        zones = own_record['zones'] if own_record else None
        if not zones or any(not within(p, zones) for p in args.paths):
            errors.append('Fichiers prévus hors des zones réservées.')
        if zones:
            errors.extend(conflicting_reservations(args.task, zones, issues))
    if (args.writer or args.reviewer or args.paths) and not args.task:
        errors.append('Sans issue : inventaire seulement, pas de validation de réservation.')
    for n, item in inventories.items():
        if resumed and n == args.resume_pr:
            continue
        if args.task and field(item['pr'].get('body'), 'Tâche') == '#' + str(args.task):
            errors.append('Même tâche déjà traitée par PR #' + str(n))
        overlap = item['paths'].intersection(args.paths or [])
        if overlap:
            errors.append('Chevauchement PR #' + str(n) + ' : ' + ', '.join(sorted(overlap)))
    # Detect current-PR/base/list changes during the multi-request inventory.
    later_prs = api(f'repos/{args.repo}/pulls?state=open&per_page=100', True)
    signature = lambda rows: sorted((p['number'], p['head']['sha'], p['base']['ref'], p.get('updated_at')) for p in rows)
    if signature(later_prs) != signature(prs):
        errors.append('Inventaire des PR modifié pendant le contrôle : relancer.')
    later_issues = [x for x in api(f'repos/{args.repo}/issues?state=open&per_page=100', True)
                    if 'pull_request' not in x]
    issue_signature = lambda rows: sorted((x['number'], x['state'], x.get('updated_at'),
                                           x.get('body')) for x in rows)
    if issue_signature(later_issues) != issue_signature(issues):
        errors.append('Inventaire des réservations modifié pendant le contrôle : relancer.')
    later_base = api(f'repos/{args.repo}/branches/{args.base.replace("/", "%2F")}')
    if later_base['commit']['sha'] != base['commit']['sha']:
        errors.append('Branche produit modifiée pendant le contrôle : relancer.')
    result = {
        'checked_at_utc': datetime.now(timezone.utc).isoformat(), 'repository': args.repo,
        'root': root, 'local_branch': branch, 'local_sha': sha,
        'product_branch': args.base, 'product_sha': base['commit']['sha'],
        'open_prs': [{'number': n, 'title': x['pr']['title'], 'draft': x['pr']['draft'],
                     'base': x['pr']['base']['ref'], 'head_sha': x['pr']['head']['sha'],
                     'paths': sorted(x['paths'])} for n, x in inventories.items()],
        'open_issues': [{'number': x['number'], 'title': x['title'],
                         'writer': field(x.get('body'), 'Responsable-IA'),
                         'zones': field(x.get('body'), 'Zones')} for x in issues],
        'recent_commits': [{'sha': x['sha'], 'subject': x['commit']['message'].splitlines()[0]} for x in recent],
        'recent_merged_prs': [{'number': x['number'], 'merged_at': x['merged_at']} for x in closed if x.get('merged_at')],
        'errors': errors, 'write_authorized': False,
        'notice': 'Inventaire en lecture seule. Aucun verrou atomique, aucune permission de commit/push/fusion.'
    }
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', required=True, choices=PRODUCTS)
    parser.add_argument('--base', required=True)
    parser.add_argument('--task', type=int)
    parser.add_argument('--writer', choices=AGENTS)
    parser.add_argument('--reviewer', choices=AGENTS)
    parser.add_argument('--path', action='append', dest='paths', default=[])
    parser.add_argument('--resume-pr', type=int)
    args = parser.parse_args()
    try:
        result = inspect(args)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 4 if result['errors'] else 0
    except (RuntimeError, OSError, subprocess.TimeoutExpired, ValueError, KeyError, TypeError) as exc:
        print(json.dumps({'errors': ['STOP : inventaire indisponible ou incomplet (' + type(exc).__name__ + ')'],
                          'write_authorized': False}, ensure_ascii=False))
        return 3

if __name__ == '__main__':
    sys.exit(main())
