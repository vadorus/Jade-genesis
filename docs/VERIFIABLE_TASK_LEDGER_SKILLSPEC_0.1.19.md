# Jade Genesis 0.1.19 — Verifiable Task Ledger + SkillSpec

## Why this version exists

0.1.18 built durable governance for adaptive strategy evidence. It did not prove
that Jade can acquire a new behavioral capability: strategy entries still carry
metadata rather than an executable behavior, their sandbox scores are supplied
by callers, and the live runtime does not execute an active learned strategy.

0.1.19 deliberately does not hide that gap. It builds the measurement and
contract layer required before executable skill learning can be claimed.

The target progression is now:

`experience -> measurable gap -> verifiable examples -> SkillSpec -> restricted procedure runtime -> candidate execution -> sealed evaluation -> persistent reuse`

Only the first four pieces are prepared in 0.1.19. Learned-skill execution is
explicitly disabled.

## Verifiable Task Ledger

`node-agent/verifiable_task_ledger.py` introduces an identity-bound, atomic,
dependency-free store for deterministic task cases.

Each dataset has:

- a stable dataset id;
- a task family;
- verifier `exact_json_v1`;
- bounded task cases;
- TRAIN, VALIDATION and SEALED_TEST partitions;
- a SHA-256 commitment for the sealed partition;
- bounded evaluation attempts and verdicts.

### TRAIN and VALIDATION

`learning_view()` may expose input and expected output for TRAIN and VALIDATION
cases. These are evidence that a future synthesis system may legitimately learn
from.

### SEALED_TEST

SEALED_TEST exists to prevent Jade, a teacher model, or a synthesis component
from simply optimizing against the answers used to claim success.

Before any sealed case can be evaluated:

1. at least one SEALED_TEST case must exist;
2. `seal_dataset()` canonicalizes the hidden cases;
3. SHA-256 is recorded as `sealed_set_sha256`;
4. the whole dataset becomes immutable.

The commitment covers dataset identity, task family, verifier, hidden case ids,
inputs and expected outputs in deterministic order.

After sealing, cases cannot be added or changed. A changed experiment requires a
new dataset id/version and therefore a new commitment.

### Hidden-answer boundary

`learning_view()` never returns SEALED_TEST cases.

`sealed_manifest()` exposes only metadata such as count, verifier and SHA-256;
it does not expose hidden inputs or answers.

`evaluate_case()` compares an output with the hidden expected output internally.
For SEALED_TEST it returns the verdict and the submitted output digest, not the
expected value or its digest.

The initial verifier is deliberately simple: canonical exact JSON equality.
There is no LLM judge in the proof loop.

## The ledger is not conversation memory

The Task Ledger is for bounded structured benchmark/task data, not raw chats.
Known conversation-shaped keys such as raw conversation text, messages and chat
history are rejected. It does not replace Memory v2 or Shared Genesis State.

## SkillSpec v1

`node-agent/skill_spec.py` defines the contract for the future learned procedural
payload.

A normalized SkillSpec carries:

- `skill_id` and `skill_version`;
- task family and domain;
- bounded input/output contracts;
- `body_kind = JADE_PROCEDURE_DSL_V1`;
- a declarative AST body;
- dependency skill ids;
- provenance;
- sealed-evaluation policy;
- `body_sha256` and `spec_sha256`.

This fixes an important limitation of the 0.1.18 strategy registry: a future
skill has an actual behavioral payload contract rather than only a title and
rationale.

## DSL boundary prepared, interpreter intentionally absent

The SkillSpec body is a strict AST. It currently allow-lists deterministic
operations for future structured transformations, including basic literals,
field access, object/array construction, string transforms, arithmetic,
comparisons, boolean logic, conditionals and pipelines.

0.1.19 does **not** implement semantics for those operations and does **not**
execute the AST.

Every normalized SkillSpec reports:

- `execution_enabled = false`;
- `network_allowed = false`;
- `filesystem_allowed = false`;
- `shell_allowed = false`;
- `arbitrary_code_allowed = false`;
- `model_weight_mutation = false`.

Python, shell/process execution, network and filesystem capabilities are not
valid SkillSpec body kinds or AST operations.

This is intentional. 0.1.20 must implement a small deterministic interpreter
rather than passing generated Python to a subprocess and calling it a sandbox.

## Provenance

SkillSpec provenance distinguishes the creator/source of a candidate from Jade's
identity. Supported source classes are bounded categories such as developer,
external teacher, future synthesis and migrated artifacts.

An external LLM can therefore become a future teacher/proposer without becoming
Jade's persistent identity.

## What 0.1.19 proves

0.1.19 can prove that Jade's architecture now has:

1. machine-verifiable task evidence;
2. a hidden test partition committed before evaluation;
3. deterministic machine verdicts without an LLM judge;
4. a strict contract for a future executable learned payload;
5. stable content hashes for that payload;
6. explicit runtime telemetry stating that execution is still disabled.

It does **not** prove that Jade learned a skill.

## Next versions

### 0.1.20 — Restricted Procedure Runtime

Implement the exact semantics of `JADE_PROCEDURE_DSL_V1` in a deterministic,
bounded interpreter. Manually-authored SkillSpecs will be used first to prove:

`SkillSpec -> selection -> execution -> task verdict`

No arbitrary Python, shell, network or filesystem access.

### 0.1.21 — Skill Synthesis Loop

Connect measurable gaps to a teacher/proposer:

`gap -> LearningGoal -> candidate SkillSpec -> restricted execution -> TRAIN/VALIDATION correction -> SEALED_TEST -> retention/rejection`

This is the first version that should be allowed to claim actual skill
acquisition if the experiment succeeds.

### 0.1.22 — Recall and autonomous reuse

Persist validated skills and prove that after a complete Jade restart a matching
task recalls the learned skill, executes it locally, remains correct, and avoids
an LLM call where the skill is sufficient.

### 0.1.23 — Composition and generalisation

Allow validated skills to depend on and compose other validated skills, measure
coverage, detect redundant special cases and keep acquisition provenance.

## First decisive experiment

The later synthesis proof should use a deterministic transformation family whose
rule is withheld from Jade. Examples are split into TRAIN, VALIDATION and a
SEALED_TEST set whose SHA-256 commitment is produced before synthesis.

Success must require all of the following:

- Jade did not already contain the target skill;
- the user/developer did not write the learned skill body;
- a candidate SkillSpec was produced from allowed evidence;
- the restricted runtime, not an LLM judge, determined outputs;
- the sealed evaluator met the predefined threshold;
- the skill persisted;
- after a full restart Jade recalled and reused it on new matching inputs;
- those successful reused instances required no teacher/LLM call.

Until that experiment exists and passes, Jade Genesis should describe these
components as learning infrastructure rather than demonstrated autonomous skill
learning.

## Android version

- `versionName = 0.1.19`
- `versionCode = 36`

The Node Runtime wire version stays `0.1.6` / `jade-genesis-node/0.0.6`; no wire
protocol break is needed for this local VPS learning substrate.
