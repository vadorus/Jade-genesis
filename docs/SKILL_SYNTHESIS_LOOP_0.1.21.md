# Jade Genesis 0.1.21 — Verified Skill Synthesis Loop

0.1.21 is the first milestone intended to prove that Jade can acquire a bounded deterministic procedure, retain it, survive a runtime restart and reuse it on a genuinely new matching request without asking a language model to solve that request again.

This is a procedural-learning experiment, not a claim of AGI, model self-training or unrestricted autonomous code generation.

## Success criterion

The milestone is not considered proven merely because a LearningGoal reaches `RETAINED`.

The formal live proof requires this complete causal chain:

1. the target task family has no active Skill;
2. real user traffic creates multiple independently sealed datasets using a partition plan fixed before any teacher call;
3. each sealed-set SHA-256 is recorded in evidence outside the learning workshop before the teacher starts;
4. the external teacher sees TRAIN and VALIDATION only;
5. one frozen SkillSpec passes the one-shot hidden SEALED_TEST;
6. the exact verified SkillSpec is archived and its production route is activated;
7. the Node Runtime process is stopped and started again;
8. a new `REAL_USER` input that did not occur in any learning case reaches the same functional path;
9. Jade automatically selects the retained Skill;
10. the measured Ollama model-list/chat counter delta for that request is exactly zero;
11. negative control: deactivating the Skill route makes an equivalent request fall back to the model and increase the Ollama counter.

Only after this live sequence is captured should 0.1.21 be described as the first demonstrated persistent procedural acquisition.

## First task family

The first family is deliberately boring and mechanically verifiable:

`normalize_label_v1`

Input:

```json
{"text":"  ExAmPle  "}
```

Expected output:

```json
{"text":"example"}
```

The intended procedure is equivalent to trim + lowercase, but the teacher must infer/propose the DSL body from visible examples. The developer does not write the final learned SkillSpec body into the production registry.

On Android, only the explicit command:

```text
/normalize <text>
```

is tagged as first-family learning traffic. Ordinary Jade conversation is not enrolled in this experiment.

## Real-data collection and fixed partitioning

Every unique real `/normalize` request can contribute one case while the family remains uncovered. The case source is `real_production_brain_chat`.

Each dataset contains exactly five positions whose partitions are fixed before teaching:

```text
case 1 -> TRAIN
case 2 -> TRAIN
case 3 -> VALIDATION
case 4 -> SEALED_TEST
case 5 -> SEALED_TEST
```

The fifth case seals the dataset immediately. The hidden cases are not exposed through `learning_view()`.

0.1.21 requires at least three independently sealed, attested, unconsumed datasets before a LearningGoal may be opened. A failed final exam burns only the selected dataset; the reserve exists so failure never creates pressure to unseal or reuse a hidden exam.

## Seal attestation

The teacher may not start until the selected dataset already has a matching seal attestation in the append-only attribution journal.

The attestation contains the exact `sealed_set_sha256`, dataset identity, seal timestamp and an opaque `external_publication_ref`. The reference must begin with `external:` and, for the formal live experiment, must identify evidence outside Jade's workshop/runtime state, such as a GitHub issue/comment or independent operator runbook entry.

The code can verify ordering and hash equality. It cannot cryptographically prove that a human-supplied `external:` reference truly points to an independent system; the live experiment must therefore preserve that external evidence separately.

## Teacher boundary

`OllamaSkillTeacher` uses an installed CODE-profile Ollama model. It receives `JADE_SKILL_TEACHER_REQUEST_V1` and may return only:

```text
skill_id
description
domain
body
```

It cannot supply or override:

```text
provenance
dependencies
evaluation_policy
sealed_set_sha256
activation
verdict
```

Jade constructs those fields itself. Any forbidden teacher response field is rejected.

The teacher request carries TRAIN/VALIDATION cases plus only the hidden-set commitment and count. It explicitly declares that hidden inputs, hidden answers and the seal nonce are not exposed.

## Judge boundary

The Verifiable Task Ledger is the judge. `SkillSynthesisLoop` cannot query individual SEALED_TEST cases.

The first candidate that passes all visible TRAIN/VALIDATION cases is frozen. The ledger then executes the whole hidden set internally and returns aggregate PASS/FAIL only. The first final candidate consumes that sealed dataset whether it passes or fails. A different candidate cannot retry against the same hidden set.

## Three physical zones

0.1.21 separates persistent learning state under the Jade configuration directory:

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

Production stores active routing references only and contains no Skill bodies or hidden expected answers. Workshop state is disposable. Archive stores retained SkillSpecs, verifier evidence, immutable case packs and the hash-chained attribution journal.

Legacy flat files are migrated one-way into canonical zone-aware stores without deleting the old source during migration.

## Attribution

The archive attribution journal is append-only and hash chained. Events can record:

- sealed dataset creation and external attestation;
- teacher start;
- verified Skill registration/activation;
- real Skill reuse;
- measured gain metadata.

Usage count is derived from events rather than trusted as one mutable counter.

## Ollama call measurement

The Node Runtime wraps the actual Ollama model-list function and `/api/chat` request path. Runtime status exposes:

```text
model_list_calls
chat_calls
total_calls
```

The final reuse proof compares the counter immediately before and after the real post-restart request. A zero delta is required. Latency is not accepted as evidence that the model was bypassed.

## Current dispatch layer and known debt

For the 0.1.21 proof, exact-family Skill lookup happens in the Node `brain_chat` wrapper immediately before the profiled Ollama path.

This is intentionally the cheapest integration point for the experiment, but it is not the final architectural layer. By this stage Android has already selected a node and acquired a Resource Lease. After the causal proof succeeds, Skill dispatch should move upward into the task/router layer so an already-known local procedure can be selected before choosing expensive cognitive compute.

That routing refactor is explicitly deferred until after the first acquisition proof so it cannot obscure the learning experiment.

## Night-cycle crank

The VPS Night Cycle contains a narrow first-family post-cycle phase. It stays inert unless `learning_trial_enabled` is explicitly enabled.

Before invoking the teacher it requires:

- the Jade identity to be bound in Shared Genesis State;
- no existing active Skill for `normalize_label_v1`;
- at least three sealed, externally attested and unconsumed datasets;
- configured attestation records matching the exact sealed hashes.

The phase then opens the LearningGoal, invokes the bounded Ollama teacher and delegates visible evaluation/final examination/retention to the existing synthesis and ledger components. Learning failure is reported without invalidating the already completed main Night Cycle.

No `skill_learn`, `skill_synthesis`, `sealed_skill_exam` or arbitrary procedure-execution task is exposed remotely.

## What tests prove today

Repository CI proves the mechanics with isolated stores and fake teachers/models:

- hidden data is not sent to the teacher;
- forbidden teacher fields are rejected;
- datasets are immutable after sealing;
- case packs and attribution-chain tampering are detected;
- three attested datasets are required before teacher start;
- attestation precedes teacher start;
- failed hidden exams consume the dataset without retention;
- successful hidden exams permit exact-hash retention;
- persistent registries survive fresh object construction;
- exact-family Skill execution can bypass Ollama with measured counter delta zero;
- missing/deactivated routes fall back to a counted model path.

These tests establish the mechanism. They do not replace the required hard process restart and genuine post-restart Pixel/VPS traffic proof.

## What 0.1.21 does not claim

0.1.21 does not provide unrestricted autonomous learning, arbitrary self-modification, shell access, filesystem/network primitives inside learned Skills, model-weight training, fuzzy Skill retrieval, Skill-to-Skill composition, or general reasoning acquisition.

The learned artifact remains a bounded `JADE_PROCEDURE_DSL_V1` procedure operating under the restricted deterministic runtime. Broader recall quality, lifecycle/rollback policy and composition belong to later milestones only after this first causal proof succeeds.
