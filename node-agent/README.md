# Jade Genesis — Distributed Node Runtime 0.1.8

The dependency-free Python Node Runtime connects PC/VPS compute to the same persistent Jade Genesis identity while keeping the compatible wire protocol `jade-genesis-node/0.0.6`.

Current Android product version on this branch is **0.1.21** (`versionCode 39`). Node and Android versions are independent.

## Start

From `node-agent/`:

```bash
python3 jade_node_agent.py --node-kind VPS
```

On Windows:

```powershell
py jade_node_agent.py
```

Runtime configuration/state is stored under `~/.jade-genesis/` (or the Windows user equivalent). Pairing tokens and private runtime state must never be committed.

## Remote surface

Authenticated endpoints include health/runtime/diagnostics and bounded task execution. Existing allow-listed work includes `genesis_probe`, `text_analysis`, `memory_consolidation`, `brain_chat`, `screen_analyze`, `vision_analyze` and, on VPS, `shared_state_sync`.

0.1.21 deliberately does **not** expose arbitrary remote tasks for:

```text
night_learning_lab
procedure_execute
skill_registry_mutate
skill_synthesis
skill_learn
sealed_skill_exam
shell/system execution
```

The first Skill-learning loop is turned only from the supervised VPS Night Cycle after its prerequisites are satisfied.

## Cognitive resources

Local language models remain interchangeable cognitive resources rather than Jade's identity. Runtime profiles include FAST, GENERAL, REASONING, CODE and CRITIC. Selection can consider installed Ollama models and available GPU VRAM without downloading models, mutating weights or escalating privileges.

## 0.1.21 three-zone learning environment

Canonical learning state is physically separated beneath the Jade configuration directory:

```text
production/
  skill-routes.json

workshop/
  skill-learning-goals.json
  candidates/

archive/
  skill-registry.json
  verifiable-task-ledger.json
  case-packs/
  skill-attribution.jsonl
```

Production stores only active route references. Skill bodies and verifier evidence live in archive. Workshop state is disposable. Legacy flat files are migrated into canonical stores without deleting the legacy source during migration.

`skill-attribution.jsonl` is append-only and hash chained. Usage is derived from recorded events rather than trusted as a mutable counter.

## Verifiable Task Ledger / case packs

The Ledger owns deterministic `TRAIN`, `VALIDATION` and hidden `SEALED_TEST` cases.

`SEALED_TEST` remains a one-shot final exam:

- individual hidden cases cannot be queried interactively;
- the hidden set is committed with a private random nonce;
- a frozen SkillSpec references the exact sealed commitment;
- the whole hidden set is evaluated internally;
- public feedback is aggregate PASS/FAIL only;
- the first final candidate consumes the dataset even on failure;
- another candidate cannot retry against that hidden set;
- replay of the exact same candidate is idempotent.

When the canonical 0.1.21 Ledger seals a dataset, it also writes an immutable archive case pack. Existing packs are verified rather than overwritten; tampering is detected.

## Restricted Procedure Runtime

`procedure_runtime.py` executes bounded `JADE_PROCEDURE_DSL_V1` procedures. The language has no primitive for filesystem, network, shell/process, imports/eval/exec, environment, clock, randomness or arbitrary native/Python code. Skill-to-Skill dependencies remain disabled.

The general runtime does not simply trust generated/external-teacher procedures. 0.1.21 permits such an artifact to execute in two tightly controlled contexts only: verifier-owned visible/final evaluation and a persistent registry route whose exact SkillSpec hash already passed the sealed exam.

## Verified Skill Registry

`skill_registry.py` now separates archive from production routing.

Developer Skills retain explicit approval. Learned `EXTERNAL_TEACHER` / `FUTURE_SYNTHESIS` Skills can enter the archive only when the Ledger proves that the exact frozen `spec_sha256` passed the matching hidden exam. Activation of learned procedures is limited to that verified gate.

Selection remains exact by `task_family`; no fuzzy LLM selection or Skill-to-Skill dependency is used in 0.1.21.

## Skill Synthesis Loop

`skill_synthesis_loop.py` provides the causal acquisition core:

```text
missing exact-family Skill
-> LearningGoal
-> teacher proposal
-> visible TRAIN/VALIDATION execution
-> first all-visible-passing candidate frozen
-> one-shot Ledger SEALED_TEST
-> exact-hash verified retention
-> production route
```

The teacher controls only descriptive metadata and the restricted DSL body. Jade owns provenance, evaluation policy, hidden commitment and activation.

The teacher never receives SEALED_TEST inputs/answers or the private seal nonce.

## Ollama teacher

`ollama_skill_teacher.py` uses an installed CODE-profile Ollama model and accepts only `JADE_SKILL_TEACHER_REQUEST_V1`. The model may return only:

```text
skill_id
description
domain
body
```

Attempts to return provenance, verifier policy, activation or other forbidden fields fail closed.

## First real learning family

0.1.21 intentionally starts with one boring deterministic family:

`normalize_label_v1`

The Android command `/normalize <text>` is tagged as explicit `REAL_USER` learning traffic and is routed to the VPS workshop. Each unique request can contribute one case until the family is learned.

Dataset partition positions are fixed before the teacher:

```text
1 TRAIN
2 TRAIN
3 VALIDATION
4 SEALED_TEST
5 SEALED_TEST
```

Five cases seal one dataset. At least **three** independently sealed, externally attested and unconsumed datasets are required before the teacher can start.

A failed hidden exam therefore burns one dataset without forcing hidden-set reuse or unsealing.

## External seal attestation

`learning_trial_protocol.py` requires each learning dataset's exact `sealed_set_sha256` to have an attribution record referencing evidence outside the learning workshop before a teacher call.

The software verifies seal/hash/time ordering and requires an `external:` reference. It cannot cryptographically prove that a human-supplied external reference really points to an independent service; the formal trial must preserve that GitHub/runbook evidence separately.

## Night-cycle crank

`skill_learning_cycle.py` connects the first family to the existing VPS Night Cycle. It remains inert unless `learning_trial_enabled` is explicitly enabled.

The cycle checks exact-family coverage, ingests configured external attestations, requires a three-dataset reserve, opens the LearningGoal, invokes the bounded CODE teacher, and delegates visible/final evaluation plus retention to the existing synthesis/Ledger components.

Skill-learning failure is reported separately and does not invalidate work already completed by the main Night Cycle.

## Measured reuse before Ollama

The Node wrapper counts actual Ollama model-list calls and `/api/chat` calls. For a structured exact-family `brain_chat` request it checks the production Skill route immediately before the profiled Ollama path.

A matching verified Skill can therefore return a deterministic result with measured `ollama_calls_delta = 0`. Missing/deactivated routes fall through to the normal counted model path.

This is intentionally a proof-stage integration point. Android has already selected a node and acquired a Resource Lease by then. Moving Skill dispatch upward into the task router is explicit post-proof debt.

## Formal live proof still required

Repository CI proves the mechanism with isolated stores and fake teacher/model boundaries. 0.1.21 is not considered a demonstrated persistent acquisition until the live VPS/Pixel experiment performs:

```text
real traffic -> sealed+externally attested datasets -> teacher -> hidden exam pass
-> retained route -> hard Node process restart -> genuinely new real input
-> same production path selects Skill -> Ollama delta 0
-> deactivate route -> comparable request returns to counted model path
```

See `../docs/SKILL_SYNTHESIS_LOOP_0.1.21.md`.

## Safety/source control

Do not commit pairing tokens, private keys, signing material, private seal nonces, hidden case contents or local runtime-state files. Public sealed-set commitments are safe to publish for the experiment; hidden cases remain verifier-owned.
