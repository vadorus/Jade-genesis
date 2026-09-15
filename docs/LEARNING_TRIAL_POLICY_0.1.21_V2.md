# Jade Genesis 0.1.21 — Learning Trial Policy V2

Protocol ID: `0.1.21-first-acquisition-v2`

This document is the precommitment for the second visible-learning attempt of the first real `normalize_label_v1` acquisition experiment. It exists because protocol V1 ended in `VISIBLE_FAILED` before any candidate was frozen and before any SEALED_TEST case was executed.

## V1 outcome and why V2 is allowed

V1 produced four visible candidates and none passed the complete TRAIN + VALIDATION set. No hidden final examination ran, no dataset was consumed, no SkillSpec was retained and no production route was activated.

The audit identified a teacher-contract defect: the model received operation names but not the exact grammar, arity or semantics of `JADE_PROCEDURE_DSL_V1`, and retry feedback did not contain enough visible diagnostics to explain malformed or incorrect ASTs.

V2 is therefore a new explicit protocol revision, not a fifth candidate under V1.

## What changes in V2

Only the teacher-facing visible-learning contract and its fail-closed liveness guards change:

- request kind becomes `JADE_SKILL_TEACHER_REQUEST_V2`;
- the request includes a machine-readable DSL grammar and operation semantics;
- generic syntax examples are included, but they do not contain the target solution or hidden data;
- failed visible attempts return bounded TRAIN/VALIDATION-only diagnostics;
- the previous proposed body is returned to the teacher so it can correct rather than blindly repeat it;
- visible attempt diagnostics are persisted in workshop goal state for auditability;
- teacher generation is bounded to 512 output tokens to prevent transport-liveness failures caused by runaway generation;
- a persisted `VISIBLE_TESTING` goal observed by a later Night Cycle is treated as an interrupted run and fails closed with no automatic teacher retry, so visible-attempt history and the candidate budget cannot be silently reset.

The Ollama `/api/chat` transport timeout remains **420 seconds**, preserving the transport-only guard that was externally fixed during V1 before its later visible attempts. The shorter 512-token V2 generation cap is the actual new liveness bound; the timeout does not change model choice, prompt evidence, verifier behavior, hidden data, candidate semantics or the one-shot exam rule.

The verifier, hidden partition, seal hashes, partition plan, task oracle, production retention rule, one-shot hidden exam and post-restart causal proof remain unchanged.

## Hidden-data boundary

The teacher may see only TRAIN and VALIDATION cases plus the existing sealed-set commitment and hidden-case count. It must never receive:

- SEALED_TEST inputs;
- SEALED_TEST expected outputs;
- the private seal nonce;
- verifier internals that reveal hidden outcomes;
- production activation authority.

Visible diagnostics may contain expected and actual outputs only for TRAIN/VALIDATION cases because those cases were already teacher-visible.

## Reuse of the already sealed datasets

The three original datasets remain eligible for V2 only because V1 never ran a hidden exam and their sealed commitments remain unchanged.

Before the V2 teacher starts, the runtime must record V2 attribution events referencing the same already-published external evidence for each dataset. This creates a new protocol-scoped attestation ordering without changing any sealed content.

The V1 LearningGoal remains historical and immutable. V2 uses a distinct goal ID: `first-real-normalize-label-v2`.

## Failure policy

The hidden examination is still not a retry oracle.

For protocol `0.1.21-first-acquisition-v2`:

- maximum failed SEALED examinations: **1**;
- one failed hidden exam makes the family trial terminal for V2;
- the failed frozen candidate may not be submitted to a different fresh dataset;
- remaining reserve datasets are not retry budget;
- the Night Cycle must not automatically create another goal after a V2 hidden failure;
- any later attempt requires another explicit protocol revision and new precommitted rules.

A visible-only failure before candidate freeze does not consume SEALED_TEST, but it also does not authorize silent retries under the same protocol after the candidate limit is reached.

If a process or transport interruption leaves the V2 goal persisted as `VISIBLE_TESTING`, a later Night Cycle must return `GOAL_INTERRUPTED_MANUAL_REVIEW` without calling the teacher and without running SEALED_TEST. Recovery from that state is deliberately not automatic.

## Formal success chain

The V2 milestone succeeds only if the complete causal chain succeeds:

1. no active Skill exists for `normalize_label_v1`;
2. the three original datasets remain sealed and unconsumed;
3. V2-scoped structured attestations are present before teacher start;
4. the V2 teacher sees TRAIN/VALIDATION only plus the public DSL contract;
5. one candidate passes all visible cases and becomes frozen;
6. that exact candidate receives the one-shot hidden SEALED_TEST;
7. it passes and the exact SkillSpec is retained and routed;
8. Node Runtime is actually stopped and restarted;
9. a genuinely new real-user `/normalize` request follows the same path;
10. the retained Skill is selected automatically with measured Ollama delta exactly zero;
11. negative control deactivates the route and the equivalent request returns to a counted model call.

`RETAINED` by itself is not enough.

## Post-restart vector

The existing externally precommitted vector remains unchanged:

```json
{"text":"  ÉcLaiR-ÇA_fÊTe42"}
```

Expected result:

```json
{"text":"éclair-ça_fête42"}
```

It must not be sent before successful retention plus a real Node Runtime restart.

## Claim discipline

Before the complete V2 proof succeeds, the correct claim remains:

> 0.1.21 causal-learning mechanism implemented; live persistent acquisition not yet demonstrated.

After the complete proof succeeds, the strongest allowed claim remains:

> Jade demonstrated persistent acquisition and reuse of one bounded procedure.

V2 still does not justify a general task-family mastery claim.
