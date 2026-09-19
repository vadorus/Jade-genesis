# Jade Genesis — Manual Canary Recommendation V0

## Purpose

This milestone turns repeated, verified capability measurements into a
**recommendation for a manual canary trial**.

It does not change routing and does not promote a challenger. Pixel end-to-end timing is displayed separately and is not used as the canary decision metric.

## Eligibility gate

A challenger is eligible for a manual canary only when all of the following
are true:

- at least 6 paired rounds completed, with an even round count;
- every round is machine-verified on both nodes;
- every round is latency-comparable;
- the challenger is faster on at least 80% of rounds;
- challenger median **node-execution** time improves by at least 10%.

If the challenger verifies fewer rounds than the incumbent, the result is a
verification regression and the canary is blocked.

## Output states

- `ELIGIBLE_FOR_MANUAL_CANARY`
- `INCONCLUSIVE`
- `INSUFFICIENT_EVIDENCE`
- `CHALLENGER_VERIFICATION_REGRESSION`

Every result keeps:

```text
requiresExplicitApproval = true
automaticPromotionAllowed = false
```

## JadeCore surface

```text
recommendManualFfmpegCanary(
    incumbentNodeId,
    challengerNodeId,
    rounds = 6
)
```

This call first executes the existing repeated bounded FFmpeg pair, then
evaluates the report.

## Boundary

V0 cannot:

- change TaskRouter policy;
- change Capability Registry;
- persist a routing preference;
- write FFmpeg measurement runs into the production routing history or Runtime Eval;
- promote a challenger;
- install software;
- use paid APIs;
- modify source code or model weights.

## Next gate

A future canary executor may temporarily route only a tiny bounded fraction of
one capability family to an approved challenger, with explicit approval,
rollback and control evidence.
