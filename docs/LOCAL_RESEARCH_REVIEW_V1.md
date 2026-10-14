# Local research review and exact-plan authorizations

Implemented in the isolated PC copy on 2026-10-12. This module is not deployed to
the personal Android app, a production node or the VPS.

## Open the interface

The default workshop now reviews Jade's general NightLearningLab proposals. See
[`JADE_GENERAL_LEARNING_REVIEW_2026-10-13.md`](JADE_GENERAL_LEARNING_REVIEW_2026-10-13.md).
The specialized artifact workflow described here is retained for explicit legacy tests.

The PC-to-Jade TEST continuation and current validation are documented in
[`RESEARCH_REVIEW_CONTINUATION_2026-10-13.md`](RESEARCH_REVIEW_CONTINUATION_2026-10-13.md).
The earlier verification section below describes the 2026-10-12 lot only.

From the repository root, using an installed Python 3.11+ runtime:

```powershell
python node-agent/local_research_review.py --port 8766
```

Open `http://127.0.0.1:8766`. To expose the optional, synthetic-only Blender
runner, explicitly configure the installed executable:

```powershell
python node-agent/local_research_review.py --port 8766 --blender "C:\Program Files\Blender Foundation\Blender 5.2\blender.exe"
```

The server listens on loopback only. It has no background research, remote node
connection, publication or automatic experiment scheduling. Stop it with Ctrl+C
in its terminal. Opening the interface or authorizing a plan never launches an
experiment.

## Review workflow

1. Examine the goal, capture date, official URLs, cited passages and caveats.
   External passages remain untrusted text; the UI never inserts them as HTML.
2. Examine the two existing FBX artifact records: their hashes, local Blender
   scope and untested Roblox/material/texture outcomes are shown separately.
3. Optionally record your interpretation of a source with your name and reason.
   `SUPPORTS`, `CONTRADICTS` or `UNCERTAIN` is an operator interpretation, not an
   independent source adjudication and not a knowledge-promotion instruction.
4. Read the exact experiment plan. Enter a reviewer name and reason, then check
   the explicit confirmation and press **Autoriser pour 15 minutes**.
5. Either revoke the permission or press **Lancer cet essai** separately.
6. Read the execution history and use **Voir le résultat**. Saved result bytes
   must match the journal's result-file commitment before they can be displayed.

## Implemented runners

| Runner | Effects | Limits |
| --- | --- | --- |
| `OFFLINE_ARTIFACT_RECHECK` | Validates existing source references and FBX files; writes a new review packet | No process or network call; local equality checks do not prove provenance |
| `OFFICIAL_SOURCE_COLLECTION` | Fetches only the fixed, selected official HTTPS URLs; writes a new capture | Goal is not sent to a search provider; excerpts remain unassessed |
| `BLENDER_SYNTHETIC_FBX_ROUNDTRIP` | Optional configured Blender executable; empty factory scene, fixed trunk/canopy fixture, export/reimport | No existing project or user asset input; no Roblox; no independent attestation or OS sandbox |

Each execution writes to a new UUID directory under `capture_local/review_runs/`.
The Blender invocation has no shell, uses a frozen copy of the script bound to
the permission, disables automatic file scripts, records process output locally
and enforces a 60-second timeout. Its executable and script fingerprints are
part of the reviewed plan and are rechecked before launch.

Roblox Studio import, material/texture fidelity and independent counter-review
remain explicitly unavailable. There is no arbitrary command, URL, uploaded
script, project path or process-argument endpoint.

## Authorization contract

`research_authorization.py` persists events in
`capture_local/review_authorizations.json`. Plans bind the action, scope, exact
dossier snapshot, workspace, output policy and applicable network/tool limits.
The local UI additionally requires the current snapshot and plan fingerprints
on both authorization and execution requests.

The journal records `GRANTED`, `REVOKED`, `STARTED`, `COMPLETED`, `FAILED` and
`INSPECTION_RECORDED`. State is derived from the complete checked journal rather
than a mutable `approved` flag. Grants expire after at most 15 minutes, are
one-use and cannot be replayed after restart. A durable `STARTED` event is saved
before the runner's first effect. A crash or a failed completion write leaves an
unknown outcome and a consumed permission; the system never retries it silently.
Revocation applies before starting, not as a cancellation of a running process.

Atomic file replacement, process-local synchronization and an OS file lock
serialize writers. Event sequence, transition and SHA-256 chain checks reject
damaged journals. Capacity must remain for recording execution before it starts.
Persistence failure before `STARTED` prevents invocation.

HTTP reads of private state/results require the local session header. Mutations
also require the exact same-origin header, loopback Host and bounded JSON body.
No CORS access is granted. Responses are not cached, and CSP prohibits external
scripts, frames and image loads. The session token is never printed in logs.

`NightLearningLab.research_official_goal()` now consumes a recorded permission
for `official_collection_plan(goal)`. Legacy `approved=True` raises
`PermissionError`. The goal research command-line entry also requires the
recorded authorization journal and ID. The scheduled Night Cycle does not
invoke either workflow.

## Evidence-reader fixes

The previous reader performed a size check and later read files again, including
a second read for the report hash. Validation and fingerprints now use the same
bounded bytes. Missing files produce a deliberate evidence-rejection error.
Duplicate excerpt hashes or block indices are rejected so one paragraph cannot
inflate a dossier's evidence/caveat count.

## Trust and integration limits

- This is a declared local operator decision, not a cryptographic identity
  signature. The logical journal is atomically replaced, not immutable storage.
  Someone with the same filesystem privileges can rewrite or delete it and
  recompute hashes. Chain checks detect ordinary corruption, not a hostile OS
  administrator or compromised Python process.
- This is a gate on the implemented orchestration paths, not a general Python
  or native-process sandbox. Low-level development helpers and historic manual
  probes remain callable by a developer. Blender still has normal user process
  privileges; the fixed script makes no network calls or user-asset reads.
- The source catalog is still three pinned URLs and lexical selection. Neither
  a hash, a local passing experiment nor an operator stance establishes source
  truth, independent consensus or broad autonomous learning.
- The initial dossier remains the selected local capture plus two probe pairs.
  New run outputs are separately inspectable in the journal; they do not
  silently replace it or become Android evidence.
- There is no new Android UI or PC/Pixel transport in this lot. No result is
  appended to `NightEvidenceLedger`, personal memory or Shared Genesis State.

## Verification on 2026-10-12

- Complete Python suite: **226 tests, zero failures**. Fixtures cover missing,
  changed, expired, revoked and replayed grants; restart and concurrent store
  instances; write failures; journal/result tampering; HTTP origin/Host/body
  checks; bounded paths; mocked-only document collection and Blender process
  success/timeout boundaries.
- Android unit tests actually rerun: **242 tests, zero failures**, Gradle
  `BUILD SUCCESSFUL`. There are no Android changes in this lot and no new APK
  installation or Pixel instrumentation.
- Browser: the actual three-source/two-FBX dossier rendered successfully. A
  separate synthetic workspace exercised invalid form refusal, grant/reload,
  revoke, regrant/offline execution, operator inspection, server restart and
  result viewing. An injected `<img ... onerror=...>` was visible as text and
  produced zero image elements.
- No new real network collection or Blender process was executed. The native
  runner is implemented and checked with a fake process boundary; its end-to-end
  execution through this UI still requires a specific operator permission.
- No commit, push, merge, VPS deployment or personal application change.
