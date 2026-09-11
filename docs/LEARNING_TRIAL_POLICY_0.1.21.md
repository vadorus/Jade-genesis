# Jade Genesis 0.1.21 — Learning Trial Policy

This document is the precommitted decision policy for the first real `normalize_label_v1` acquisition trial. It exists to prevent hidden-test outcomes from changing the rules after the experiment has started.

## What counts as the 0.1.21 acquisition proof

The 0.1.21 milestone may be described as a demonstrated persistent procedural acquisition only if the complete live chain succeeds:

1. real Pixel `/normalize ...` traffic creates the learning cases;
2. three five-case datasets are independently sealed before the teacher runs;
3. every external seal publication includes the dataset ID, `sealed_set_sha256`, total case count and full partition plan;
4. all three structured attestations exist before the gap opens;
5. the teacher sees TRAIN and VALIDATION only;
6. one frozen candidate receives one hidden final examination;
7. the candidate passes and is retained;
8. the Node Runtime process is actually stopped and restarted;
9. a genuinely new real-user input follows the same functional path;
10. the retained Skill is selected automatically and the measured Ollama-call delta is exactly zero;
11. the negative control deactivates the route and an equivalent request returns to a counted model call.

`RETAINED` alone is not the success criterion.

## External evidence log

The public, timestamped evidence log for this live trial is GitHub issue **#27**:

`https://github.com/vadorus/Jade-genesis/issues/27`

It may publish commitments and structural metadata, but it must never publish hidden SEALED_TEST inputs/answers, the private seal nonce, pairing tokens, private keys or signing material.

## External seal publication format

For the first family, each external publication must contain all of the following before the teacher is allowed to run:

```json
{
  "dataset_id": "normalize-label-real-0001",
  "sealed_set_sha256": "<64 hex chars>",
  "case_count": 5,
  "partition_plan": [
    "TRAIN",
    "TRAIN",
    "VALIDATION",
    "SEALED_TEST",
    "SEALED_TEST"
  ]
}
```

The runtime also records an `external_publication_ref` pointing to evidence outside the workshop state. Hash-only attestations do not qualify for the formal trial.

This locks not only the hidden-content commitment but also the declared size and split used by the experiment.

## Failure policy — fixed before launch

The hidden examination is not a retry oracle.

For protocol `0.1.21-first-acquisition-v1`:

- maximum failed SEALED examinations: **1**;
- one failed hidden exam makes the family trial **terminal for this protocol**;
- the frozen candidate that failed may **not** be submitted to another fresh dataset;
- the remaining reserve datasets are **not retry budget**;
- the Night Cycle must not automatically create another goal after that failure;
- a later attempt would require a new explicit protocol revision, new precommitted rules and fresh evidence collected under that new protocol.

This means the decision is not made after seeing whether the first hidden exam passed or failed.

A terminal failure means only: “the family was not learned under this specific 0.1.21 trial.” It does not claim that the task is impossible in principle.

## Why three datasets are required before the teacher

The reserve exists to prevent pressure to reopen or reuse a hidden set after failure. It does not grant three chances to pass.

The selected acquisition dataset is consumed by the first frozen candidate. If it fails, the trial ends even though two fresh datasets may still exist.

## Small-exam limitation

The acquisition dataset contains only two hidden cases. Passing those two cases is enough to continue the 0.1.21 causal-acquisition demonstration, but it is **not** enough to claim robust mastery of label normalization.

Therefore the allowed claim levels are different:

- after the live acquisition/restart/reuse proof: **“Jade demonstrated persistent acquisition and reuse of one bounded procedure.”**
- not yet allowed: **“Jade reliably knows this task family.”**

The first family is intentionally trivial. Its oracle is Python `text.strip().lower()` and the restricted DSL already exposes `trim` and `lower` primitives with the same Python semantics. A successful first trial therefore validates the causal plumbing — data collection, bounded synthesis, hidden exam, retention, restart and measured reuse — but does **not** demonstrate that the teacher can discover a non-trivial algorithm. A later family must test a composition whose solution is not named directly by the task statement.

## Precommitted post-restart proof vector

The initial launch marker used a payload with trailing spaces. Before any live dataset attestation and before any teacher call, that vector was superseded in GitHub issue #27 because the Android 0.1.21 chat UI trims the whole message before dispatch. A trailing-space payload therefore cannot be reproduced exactly through the real Pixel UI.

The corrected real-user input is fixed as:

```json
{"text":"  ÉcLaiR-ÇA_fÊTe42"}
```

Expected deterministic output:

```json
{"text":"éclair-ça_fête42"}
```

This vector deliberately combines leading whitespace, internal mixed case, accented letters, punctuation and digits while remaining exactly representable through the shipping Pixel UI.

This precommitment does **not** replace the runtime novelty check. The live proof remains eligible only when `novel_vs_learning_cases is True` and the measured Ollama-call delta is exactly `0`. If the exact canonical input was already present in the learning cases, the proof fails closed and this vector cannot be silently replaced after restart.

The corrected vector and expected result were timestamped externally in GitHub issue #27 before any live dataset attestation. The runtime protocol identifier remains `0.1.21-first-acquisition-v1`; no learning algorithm, verifier, partition plan, failure policy, APK or Node Runtime behavior changed as part of this pre-data correction.

## Post-demo confirmation policy

After the acquisition demo, robustness must be tested with the **exact same frozen SkillSpec hash**. No teacher call, no resynthesis and no body modification are allowed during this confirmation.

For `normalize_label_v1`, the precommitted next threshold is:

- collect **4 additional fresh sealed datasets** from later real traffic;
- each fresh dataset keeps the same fixed five-case partition plan;
- run the exact same frozen SkillSpec against all four fresh hidden sets;
- require all four fresh sealed exams to pass;
- this adds 8 fresh hidden cases, for 10 hidden cases total when including the original acquisition exam.

Ten all-pass hidden cases are still not a formal statistical proof because real inputs are not guaranteed independent or identically distributed. The threshold is a stronger robustness check, not a license to overclaim general competence.

The current 0.1.21 implementation precommits this confirmation policy but does not yet implement a complete multi-dataset re-examination path for an already retained exact SkillSpec hash. That follow-up must not be reported as completed until such a path exists and is exercised.

## Runtime-version compatibility

Android 0.1.21 expects Node Runtime **0.1.8**. The Android runtime manager must therefore compare connected nodes against `0.1.8`; older expectations such as `0.1.6` are stale and would keep the update indicator permanently active.

## Claim discipline

Before the live proof succeeds, the correct status is:

> 0.1.21 causal-learning mechanism implemented and tested in CI; live persistent acquisition not yet demonstrated.

After the live proof succeeds but before the post-demo confirmation:

> First persistent bounded procedural acquisition demonstrated; task-family robustness not yet established.

Only later evidence may justify a stronger task-family reliability claim.
