# Jade Genesis

Jade Genesis is an experimental personal AI architecture built around one persistent logical identity that can operate across Android, PC and VPS nodes.

The project is **not** designed around one permanent LLM as Jade's identity or central brain. Local and external language/vision models remain interchangeable cognitive resources. The long-term goal is for Jade to accumulate structured experience, measure outcomes, consolidate useful evidence and acquire reusable bounded capabilities under explicit safety boundaries.

> Current Android product version: **0.1.22** (`versionCode 40`)
>
> Current dependency-free Node Runtime version: **0.1.9** / protocol `jade-genesis-node/0.0.6`
>
> Status: **experimental / personal research project**. The 0.1.21 mechanism for verified procedural acquisition is implemented and tested in CI. On 2026-09-16, protocol V3 completed the live VPS/Pixel chain (hidden exam PASS, real restart, real reuse with Ollama delta 0, negative control): Jade demonstrated persistent acquisition and reuse of one bounded procedure, assembled by typed search from LLM hints. See issue #27 and [`docs/HANDOFF_0.1.21_V3_LIVE_PROOF.md`](docs/HANDOFF_0.1.21_V3_LIVE_PROOF.md).

## What exists today

Working foundations now include:

- persistent Jade identity and local structured memory on Android;
- device profiling, self-model and authenticated PC/VPS nodes;
- bounded task routing and cognitive brain profiles;
- Shared Genesis State with offline/retry behavior;
- Runtime Evaluation and explicit outcome evidence;
- supervised VPS Night Cycle / Night Learning;
- Adaptive Strategy Registry;
- Verifiable Task Ledger with TRAIN, VALIDATION and hidden `SEALED_TEST`;
- `SkillSpec v1` and the bounded `JADE_PROCEDURE_DSL_V1`;
- deterministic restricted procedure execution;
- physically separated production / workshop / archive learning zones;
- immutable sealed case packs and a hash-chained attribution journal;
- verified learned-Skill registration gated by an exact hidden-exam proof;
- bounded Ollama CODE-profile teacher integration;
- measured exact-family Skill reuse before Ollama;
- one deliberately narrow real-traffic family, `normalize_label_v1`;
- Android `/normalize <text>` plumbing to send genuine Pixel traffic to the VPS workshop;
- GitHub Actions that compile/test Android and validate the Node learning boundaries.

## 0.1.21 proof protocol and recorded result

The milestone is not successful merely because a generated Skill reaches `RETAINED`.

Protocol V3 completed this chain on 2026-09-16 for `normalize_label_v1`, as recorded in the [external evidence log](https://github.com/vadorus/Jade-genesis/issues/27#issuecomment-5701705861) and the [live-proof handoff](docs/HANDOFF_0.1.21_V3_LIVE_PROOF.md). This is a dated result for one bounded procedure; it does not establish broader learning or the current health of every deployment.

The causal proof contract remains:

```text
no target Skill
-> real Pixel usage
-> fixed partitions + sealed datasets
-> hashes published before teacher
-> teacher sees TRAIN/VALIDATION only
-> candidate frozen
-> one-shot hidden exam
-> exact verified Skill retained and routed
-> hard Node Runtime restart
-> genuinely new real Pixel input
-> retained Skill selected automatically
-> measured Ollama call delta = 0
-> negative control: deactivate Skill and model calls return
```

That sequence distinguishes “a procedure passed a test” from “Jade acquired a persistent capability that changes future behavior.”

The reproducible protocol is specified in [`docs/LEARNING_TRIAL_POLICY_0.1.21_V3.md`](docs/LEARNING_TRIAL_POLICY_0.1.21_V3.md), with the mechanism in [`docs/SKILL_SYNTHESIS_LOOP_0.1.21.md`](docs/SKILL_SYNTHESIS_LOOP_0.1.21.md). The handoff records the restart, measured reuse, negative control and retained-artifact identifiers without disclosing hidden cases. Stronger claims remain gated on the policy's fresh confirmatory datasets.

## First real family

The first family is intentionally boring and objectively verifiable:

`normalize_label_v1`

It maps a label to the same label trimmed and lower-cased. On Android, only the explicit command:

```text
/normalize <text>
```

is marked `REAL_USER` and allowed to feed this experiment. Ordinary chat is not enrolled.

These commands are routed specifically to the VPS workshop rather than normal generative hardware scoring, so evidence, hidden tests and retained Skills live in the canonical always-on learning environment.

Every five unique real inputs complete one dataset with a partition plan fixed before teaching:

```text
TRAIN, TRAIN, VALIDATION, SEALED_TEST, SEALED_TEST
```

The fifth case seals the dataset. The Pixel then displays only the dataset ID and `sealed_set_sha256`, which can be published externally before the teacher is enabled. Hidden inputs, hidden answers and the private seal nonce remain undisclosed.

At least **three** independently sealed, externally attested and unconsumed datasets are required before the teacher may run. One failed final candidate therefore burns one hidden exam without creating pressure to unseal or recycle it.

## Author / judge separation

The 0.1.21 teacher is a proposal source, not the judge.

`OllamaSkillTeacher` receives visible TRAIN/VALIDATION evidence and can return only:

```text
skill_id
description
domain
body
```

Jade constructs provenance, verifier policy, dependencies and the exact hidden-set commitment itself. The teacher cannot choose activation or verdict.

The Verifiable Task Ledger owns the final exam. Individual `SEALED_TEST` cases cannot be queried through the normal evaluator. The complete hidden set is executed internally and only aggregate PASS/FAIL is returned. The first final candidate consumes that dataset even on failure.

## Three-zone learning environment

0.1.21 separates failure-prone experimentation from production:

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

Production contains route references only, not Skill bodies or hidden expected answers. Workshop state is disposable. Archive contains retained artifacts, verifier evidence and append-only attribution.

The attribution journal is SHA-256 chained. Usage and measured gain are derived from events instead of trusting one mutable counter.

## Restricted execution

Learned procedures remain inside `JADE_PROCEDURE_DSL_V1`. There is no DSL primitive for arbitrary Python, shell/process execution, filesystem, network, imports/eval/exec, environment variables, clock or randomness. Skill-to-Skill dependencies remain disabled.

The runtime enforces AST/value/output limits plus deterministic logical step and cost budgets. This is a restricted interpreter, not an OS/container sandbox for arbitrary native code.

## Measured model bypass

The Node Runtime wraps the actual Ollama model-list path and `/api/chat` path with process-local counters. Exact-family Skill lookup occurs immediately before the profiled Ollama call.

For the formal proof, the post-restart request must show a real `ollama_calls_delta = 0`. Latency is not accepted as a proxy. The negative control must remove the active route and show that a comparable request causes counted model access again.

The current Skill lookup is intentionally placed inside Node `brain_chat` for the cheapest proof. Android has already selected a node and acquired a Resource Lease at that point. Moving Skill dispatch upward into the task router is explicit post-proof debt.

## Night-cycle crank

The existing VPS Night Cycle now has one narrow 0.1.21 Skill-learning phase. It remains inert unless `learning_trial_enabled` is explicitly enabled.

Before a teacher call, it requires Jade's identity, an uncovered exact family, at least three attested sealed datasets, and attestation records matching the exact commitments. It then opens the goal and delegates proposal, visible evaluation, final exam and retention to the existing bounded components.

No arbitrary remote `skill_learn`, `skill_synthesis`, `sealed_skill_exam`, registry-mutation or procedure-execution task is exposed.

## Important limits

Jade must not yet be confused with autonomous general intelligence or unrestricted self-improvement.

- 0.1.21 learns one bounded procedural family, not arbitrary tasks;
- the live hard-restart/reuse proof is recorded for one procedure on 2026-09-16; broader confirmation and generalisation remain unproven;
- fuzzy Skill retrieval and broad confidence-based recall are not implemented;
- Skill-to-Skill composition is disabled;
- learned Skills cannot access shell/filesystem/network primitives;
- model weights are not trained or mutated;
- the Android Evolution Engine remains separate and does not autonomously rewrite production code;
- the phone/VPS integration still depends on available authenticated nodes and cognitive backends.

## Architecture at a glance

```mermaid
flowchart LR
    P[Pixel / Android\nidentity + real user interface] <-->|authenticated tasks| V[VPS Node Runtime\nworkshop + verifier]
    P <-->|bounded shared state| S[Shared Genesis State]
    V <--> S
    E[Real task evidence] --> L[Verifiable Task Ledger]
    L --> T[TRAIN / VALIDATION]
    L --> H[hidden SEALED_TEST]
    T --> M[External teacher\nproposal only]
    M --> K[Restricted SkillSpec candidate]
    K --> R[Procedure Runtime]
    R --> H
    H -->|PASS exact hash| A[Archive Skill Registry]
    A --> Q[Production exact-family route]
    Q -->|reuse before Ollama| V
```

Detailed architecture: [`docs/ARCHITECTURE_OVERVIEW.md`](docs/ARCHITECTURE_OVERVIEW.md).

## Version trajectory

- **0.1.19 — Verifiable Task Ledger + SkillSpec:** deterministic datasets and declarative procedure payload. Implemented.
- **0.1.20 — Restricted Procedure Runtime:** deterministic DSL interpreter, developer Skill Registry and one-shot hidden final exam. Implemented.
- **0.1.20.1 — Cognitive Plumbing Hardening:** durable memory delivery, lifecycle cleanup, Shared State compaction and meaningful Evolution metrics. Implemented on `main`.
- **0.1.21 — Verified Skill Synthesis Loop:** three-zone environment, bounded teacher, dataset reserve/attestation, Night Cycle crank, measured Skill reuse and Pixel real-traffic path. Protocol V3 completed the live restart/reuse proof on 2026-09-16 for one bounded procedure, with `typed_search_with_llm_hints` provenance.
- **Current product — Android 0.1.22 / Node 0.1.9:** the source versions above identify the build. Later development-lot labels are not Android release versions.
- **Development lots 0.1.24–0.1.30:** bounded self-improvement planning, research, competing hypotheses, controlled experiments and explicit-preference learning have been integrated. See the [reasoning model](docs/SELF_IMPROVEMENT_MODEL_0.1.24.md) and [0.1.30 integration](https://github.com/vadorus/Jade-genesis/pull/59). Unit tests and CI validate the implemented mechanisms; they do not establish broad autonomous learning in live personal use.
- **Future work:** confirmatory acquisition trials, richer Skill retrieval and lifecycle validation, followed by controlled composition/generalisation. Skill composition remains disabled.

## Android / Node build versions

Android uses Kotlin/Compose with JDK 17, Gradle 9.6.0, AGP 9.4.0, compile/target SDK 37 and minSdk 31. See [`android/README.md`](android/README.md).

The Node Runtime is dependency-free Python despite the historical directory name `node-agent`. See [`node-agent/README.md`](node-agent/README.md).

Ordinary Android CI builds debug and unsigned release APKs. Stable signing is a separate explicit path; a green ordinary CI check is not evidence of stable signing.

## Repository layout

```text
Jade-genesis/
├── android/
├── node-agent/
├── docs/
├── .github/workflows/
├── .gitignore
└── README.md
```

The Git repository is the canonical source of truth. Generated APKs, logs and evaluation artifacts belong in Actions/release artifacts rather than the source tree.

## License

No software license has been selected yet. The repository is public, but public visibility alone does **not** grant open-source reuse or redistribution rights. A formal license/usage decision remains to be made.

## Project status

Jade Genesis is under active development. Documentation is updated with meaningful milestones. If documentation and code disagree, the exact source commit and verified implementation take precedence.
