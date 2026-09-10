# Jade Genesis 0.1.20 — Restricted Procedure Runtime

## Purpose

0.1.20 adds the first real execution substrate for `SkillSpec` while deliberately stopping short of autonomous skill synthesis.

The milestone must prove this causal path:

```text
closed SkillSpec
-> explicit registration
-> explicit activation
-> exact task-family selection
-> restricted deterministic execution
-> machine-verifiable result
-> persistence across restart
```

The executable artifact is a bounded declarative AST, not Python, shell or arbitrary generated code.

## What changed from 0.1.19

0.1.19 introduced:

- Verifiable Task Ledger;
- TRAIN / VALIDATION / SEALED_TEST partitions;
- salted hidden-set commitment;
- SkillSpec v1 and `JADE_PROCEDURE_DSL_V1` AST validation;
- no interpreter.

0.1.20 adds:

- `procedure_runtime.py` — deterministic DSL interpreter;
- `skill_registry.py` — persistent developer-only skill registry;
- explicit approved activation;
- exact task-family selection and local reuse;
- strengthened SkillSpec rules with dependencies disabled;
- hardened final SEALED_TEST exam that cannot be used as an interactive per-case oracle;
- Node Runtime health/runtime exposure for the new capabilities;
- Android product bump to `0.1.20` / `versionCode 37`.

## Security model

### Executable provenance

0.1.20 executes only SkillSpecs with provenance:

```text
DEVELOPER
```

The following remain non-executable:

```text
EXTERNAL_TEACHER
FUTURE_SYNTHESIS
MIGRATED
```

This is intentional. Generated/untrusted candidates will not become executable until the synthesis/verifier/promotion boundary is implemented and tested.

### No Skill-to-Skill dependencies

0.1.20 sets the allowed dependency count to zero.

A SkillSpec must therefore be a closed AST. This avoids introducing recursion, dependency cycles, transitive permissions and uncontrolled budget multiplication before the single-skill execution model is proven.

### No ambient authority

The DSL has no operation for:

- filesystem access;
- network access;
- shell/process execution;
- environment variables;
- imports;
- dynamic eval/exec;
- reflection;
- clock/time;
- randomness;
- arbitrary Python/native code.

The procedure interpreter receives only bounded JSON-compatible input and returns bounded JSON-compatible output.

### In-process limitation

The Procedure Runtime is a restricted interpreter implemented in Python. It is **not** an OS/container sandbox and must not be described as one.

Its safety comes from a capability-minimal language and strict validation, not from executing hostile native code inside a process jail.

## DSL v1 operations

The executable subset contains deterministic operations over bounded JSON values:

- `input`
- `literal`
- `get`
- `object`
- `array`
- `concat`
- `lower`
- `upper`
- `trim`
- `replace`
- `split`
- `join`
- `length`
- `add`
- `subtract`
- `multiply`
- `divide`
- `modulo`
- `equals`
- `lt`
- `lte`
- `gt`
- `gte`
- `not`
- `and`
- `or`
- `if`

There are no loops or recursion in 0.1.20.

An earlier placeholder `pipeline` operation was removed rather than leaving an allowed operation without formally implemented semantics.

## Resource limits

Execution is bounded independently of wall-clock timeout.

Current runtime limits include:

- maximum logical steps;
- maximum logical cost units;
- maximum JSON/value depth;
- maximum string size;
- maximum collection size;
- maximum serialized JSON output size.

Operations are charged logical cost based partly on processed value size, so manipulating a large string/collection costs more than a trivial scalar operation.

The interpreter fails closed when a limit is exceeded.

## Type and arithmetic safety

The runtime validates:

- input contract;
- output contract;
- supported JSON value types;
- required object fields;
- valid array indexes;
- valid object keys;
- string-only operations;
- boolean-only boolean operators;
- numeric-only arithmetic/comparison where required;
- division/modulo by zero;
- finite floating-point results.

Invalid execution produces a stable Procedure Runtime error rather than silently coercing arbitrary Python values.

## Skill Registry

`skill_registry.py` is intentionally separate from the Adaptive Strategy Registry.

A strategy is tuning/governance metadata. A SkillSpec is an executable capability payload.

The 0.1.20 Skill Registry:

- is identity-bound;
- persists atomically with backup recovery;
- accepts only `DEVELOPER` SkillSpecs;
- does not activate a registered skill automatically;
- requires explicit approval to activate/deactivate;
- keeps one active skill per exact `task_family`;
- validates stored hashes before execution;
- selects only by exact task family;
- performs no fuzzy/semantic retrieval;
- makes no LLM call;
- delegates execution to the restricted Procedure Runtime.

This creates a measurable persistence/reuse path without pretending that autonomous learning already exists.

## SEALED_TEST anti-oracle hardening

### Problem in 0.1.19

The original per-case evaluator could return a boolean verdict for a sealed case. Even without revealing the expected output, repeated candidate submissions could turn that bit into an optimization/training signal.

### 0.1.20 rule

`SEALED_TEST` is a final exam, not a teacher.

The normal `evaluate_case()` path now rejects sealed cases entirely.

The dedicated final exam path:

1. requires the dataset to be sealed first;
2. requires the candidate SkillSpec's `sealed_set_sha256` to match the exact hidden-set commitment;
3. freezes candidate identity by `spec_sha256`;
4. runs all sealed cases internally;
5. stores the internal aggregate result;
6. publicly returns only aggregate `PASS/FAIL` and commitment/candidate identifiers;
7. does not expose per-case verdicts, expected values or nonce;
8. permanently consumes that sealed dataset for the first candidate;
9. rejects a different candidate against the consumed dataset;
10. treats replay of the exact same candidate as idempotent and returns the stored result without re-executing hidden cases.

A failed candidate consumes the dataset too. This prevents iterative hill-climbing against the same hidden set.

## Remaining SEALED_TEST limitation

The verifier and hidden cases still live in the same local Python/runtime trust domain and the hidden cases are physically present in the ledger JSON state.

Therefore 0.1.21 must ensure that a future teacher/generator receives only the learning-visible API/view and cannot read the ledger file directly.

A stronger future boundary may use a separate verifier process/service or credential/filesystem boundary. 0.1.20 does not claim that isolation yet.

## Runtime exposure

The Node Runtime advertises:

- `procedure_runtime_v1`
- `skill_registry_v1`

through VPS health/runtime telemetry.

It deliberately does **not** add `procedure_execute` or registry mutation to the remote allow-listed task API.

This means the capability exists locally without creating a generic authenticated remote code/procedure execution surface.

## What 0.1.20 proves

If tests and CI pass, 0.1.20 can legitimately claim:

- a SkillSpec can contain an actual executable declarative payload;
- the payload can be executed deterministically under explicit resource limits;
- the executable language has no general host-system primitives;
- developer-authored skills can be persisted, explicitly activated, selected by exact family and reused after registry reload;
- hidden final evaluation cannot be queried case-by-case or retried with modified candidates against the same sealed dataset.

## What 0.1.20 does NOT prove

0.1.20 does not prove:

- autonomous gap detection;
- autonomous LearningGoal creation;
- LLM-generated executable skill acceptance;
- autonomous candidate correction/promotion;
- broad semantic skill recall;
- Skill-to-Skill composition;
- generalisation beyond the tested task family;
- self-modifying model weights;
- AGI/general autonomous learning.

## 0.1.21 acceptance experiment

The next milestone should be falsifiable.

A strong test is:

1. create a deterministic task family after the synthesis system exists;
2. verify no active skill can solve it initially;
3. expose only TRAIN/VALIDATION examples to the teacher/generator;
4. keep SEALED_TEST hidden from that path;
5. let an external model propose one or more SkillSpec candidates;
6. execute candidates only inside the restricted procedure runtime;
7. use visible validation for iteration;
8. freeze one final candidate by `spec_sha256`;
9. consume a fresh SEALED_TEST dataset exactly once;
10. retain/promote only if policy passes;
11. stop Jade completely and clear volatile process state;
12. restart Jade;
13. present new inputs from the same task family;
14. prove Jade retrieves and executes the retained skill without asking the teaching LLM to solve the task again.

Only then should the project claim its first measured acquisition of a new executable skill.

## Related files

- `node-agent/skill_spec.py`
- `node-agent/procedure_runtime.py`
- `node-agent/skill_registry.py`
- `node-agent/verifiable_task_ledger.py`
- `node-agent/test_skill_spec.py`
- `node-agent/test_procedure_runtime.py`
- `node-agent/test_skill_registry.py`
- `node-agent/test_verifiable_task_ledger.py`
- `.github/workflows/jade-node-runtime-ci.yml`
- `android/app/build.gradle.kts`

## Documentation rule

From 0.1.20 onward, the root/component README files and milestone documentation should be updated before a version is considered closed. Documentation must describe implemented behavior and explicitly distinguish future behavior from current capability.
