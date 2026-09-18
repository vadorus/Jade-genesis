# Jade Genesis — Replay Lab V0

## Current step: DecisionTrace

Replay Lab starts as an **instrument**, not an evolution engine.

Before Jade is allowed to compare or improve orchestration policies, it must be
able to prove what information was available at a decision point, what options
were eligible, what it selected, and what happened afterwards.

## DecisionTrace V0 records

For each TaskRouter routing decision:

- task id and task family;
- required capability;
- workload class;
- JadeConfig id and revision;
- resource mode and routing budget;
- all observed node alternatives;
- eligibility of each alternative;
- routing score for eligible alternatives;
- relevant node hardware/model state;
- selected node;
- routing explanation;
- success/failure;
- actual execution node;
- latency;
- fallback use;
- decision and completion timestamps.

It deliberately **does not record the task payload or output**. Replay routing
does not require user content to prove which node Jade selected.

## What this does not do

DecisionTrace V0 does not:

- replay a task;
- run an alternative branch;
- generate a new routing policy;
- promote a policy;
- mutate JadeConfig;
- access SEALED_TEST;
- install tools;
- call paid APIs.

## Why alternatives include ineligible nodes

A faithful replay instrument must distinguish:

- an option that existed and was eligible;
- an option that existed but was unavailable/incompatible;
- an option that was selected;
- an option that eventually executed because of fallback.

If Jade only stored the winner, future Replay Lab analysis would have no
counterfactual structure.

## Next milestones

```text
DecisionTrace
  -> A/A replay harness
  -> Branch Harvester
  -> manual challenger policy
  -> shadow evaluation
  -> canary
  -> causal rollback/control
  -> only then automated policy proposals
```

The first replay target remains N1 orchestration. N2 Skills, N3 source-code
changes, and N4 model weights stay separate.
