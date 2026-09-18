# Jade Genesis — Branch Harvester Planner V0

## Purpose

The Branch Harvester is the future source of counterfactual evidence for
Replay Lab.

V0 adds only the **planner**. It identifies which already-observed alternative
nodes would be useful branches for a recorded routing decision.

It does **not** execute those branches yet.

## Selection rules

For one DecisionTrace:

1. keep only alternatives that were eligible;
2. require a finite captured routing score;
3. exclude the recorded primary node;
4. order remaining alternatives using the same score/RAM/CPU ordering;
5. cap the number of branches.

The hard V0 cap is three branches per trace.

## Why execution is not in this step

DecisionTrace deliberately excludes user payloads and outputs.

That privacy boundary means a later executable Branch Harvester must hook into
the live TaskRouter request while the request exists in memory, or use a
separate replay-safe task capsule with an explicit retention policy.

We do not weaken DecisionTrace privacy merely to make replay easier.

## Next executable step

The next implementation should introduce a bounded in-memory
`CounterfactualTaskEnvelope` that is created only for explicitly eligible task
families.

The executor must:

- never change the user-visible primary result;
- run at most a tiny bounded number of alternatives;
- never use paid providers;
- obey ResourceAdmissionController;
- avoid SEALED_TEST and learning verifier paths;
- record branch outcome/latency independently;
- discard the replay envelope after execution unless a retention policy
  explicitly allows storage.

Until that exists, Branch Harvester V0 remains a pure planner.
