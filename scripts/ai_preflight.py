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

PRODUCTS = {'vadorus/addonhardwaregame2d': 'v013/demo-octobre',
            'vadorus/tech-empire-roblox': 'main', 'vadorus/Jade-genesis': 'main'}
AGENTS = ('Claude', 'Codex', 'Antigravity', 'Humain')

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
    zones = [x.strip() for x in (value or '').split(',')]
    for zone in zones:
        if (not zone or zone.startswith('/') or '\\' in zone or
            re.search(r'[:*?]', zone) or
            any(x in ('', '.', '..') for x in zone.removesuffix('/').split('/'))):
            return None
    return zones

def within(path, zones):
    return any(path.startswith(zone) if zone.endswith('/') else path == zone for zone in zones)

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
        if 'pull_request' in issue or issue['state'] != 'open':
            errors.append('Référence de tâche différente d’une issue ouverte.')
        if field(issue.get('body'), 'Responsable-IA') != args.writer:
            errors.append('Auteur différent de la réservation de l’issue.')
        if field(issue.get('body'), 'Relecteur-IA') != args.reviewer:
            errors.append('Relecteur différent de la réservation de l’issue.')
        if field(issue.get('body'), 'Réservation-état') not in ('Réservée', 'En cours', 'En revue'):
            errors.append('Réservation inactive ou absente.')
        zones = parse_zones(field(issue.get('body'), 'Zones'))
        if not zones or any(not within(p, zones) for p in args.paths):
            errors.append('Fichiers prévus hors des zones réservées.')
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
    except (RuntimeError, OSError, subprocess.TimeoutExpired, ValueError, KeyError) as exc:
        print(json.dumps({'errors': ['STOP : inventaire indisponible ou incomplet (' + type(exc).__name__ + ')'],
                          'write_authorized': False}, ensure_ascii=False))
        return 3

if __name__ == '__main__':
    sys.exit(main())
