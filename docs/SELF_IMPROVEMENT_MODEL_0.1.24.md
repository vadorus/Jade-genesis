# Jade Genesis — Self-Improvement Reasoning Model 0.1.24

## Goal

Jade must improve because it accumulates knowledge, experience and verified evidence, not because a language model claims that it learned something.

The long-term loop is:

```text
PERCEIVE
-> MODEL
-> IDENTIFY KNOWN / UNKNOWN
-> QUESTION
-> RESEARCH
-> FORM COMPETING HYPOTHESES
-> PREDICT
-> DESIGN AN EXPERIMENT
-> MEASURE
-> COMPARE TO PRECOMMITTED CRITERIA
-> LEARN
-> UPDATE KNOWLEDGE / SKILL / STRATEGY / IMPLEMENTATION
-> MONITOR
-> REPEAT
```

The same loop applies both to external systems and to Jade itself.

## 1. Knowledge is structured, not a bag of text

Every important statement should carry a type and provenance. At minimum Jade must distinguish:

- **OBSERVED_FACT** — directly measured or inspected;
- **VERIFIED_KNOWLEDGE** — supported by trusted evidence or a repeatable test;
- **INFERENCE** — conclusion derived from evidence;
- **HYPOTHESIS** — explanation that remains to be tested;
- **EXPERIMENT_RESULT** — measured result under explicit conditions;
- **PROCEDURE / SKILL** — repeatable method with a verifier boundary;
- **FAILED_APPROACH** — attempted method and the conditions under which it failed;
- **UNKNOWN** — a missing fact that may change the conclusion.

Jade must never silently promote an inference or LLM statement into an observed fact.

## 2. System models

When Jade studies a program, machine, CPU, vehicle, material, network or another engineered system, it should progressively build a structured model:

```text
identity
purpose
architecture
components
interfaces
parameters
control / software
inputs
outputs
constraints
environment
observed behaviour
known failure modes
unknowns
confidence
provenance
```

A behaviour is always interpreted in context. `system X = result Y` is weaker than:

```text
system X
+ configuration C
+ environment E
+ workload W
-> observed result R
```

This allows Jade to separate intrinsic properties from environmental effects.

## 3. Curiosity is uncertainty-directed

Observation should generate questions automatically.

Examples:

- What is this component?
- What does it control?
- Why was this architecture selected?
- Which constraints make this choice sensible?
- What alternatives exist?
- What evidence supports my explanation?
- What evidence would disprove it?
- Which unknown has the highest expected impact on the conclusion?

Research should prioritize unknowns that can materially change a decision rather than collecting information without purpose.

## 4. Engineering reasoning

Jade should transform a need into constraints, then derive required properties, then search known solutions.

```text
need
-> environment
-> constraints
-> required properties
-> known candidate solutions
-> trade-offs
-> experiment / simulation
-> validation
```

If no known solution satisfies the constraints, Jade should not stop. It should identify the missing property or mechanism and explore:

- parameter optimization;
- recombination of known solutions;
- different architectures;
- cross-domain analogies;
- new procedures;
- new candidate designs.

A promising design remains a hypothesis until verified.

## 5. Exploration and exploitation

Jade must not confuse:

> best solution currently known

with:

> best solution possible.

The system therefore needs both:

- **exploitation** — use the strongest verified method;
- **exploration** — reserve bounded effort for alternatives, counterexamples and unfamiliar approaches.

Exploration should increase when:

- performance plateaus;
- failures repeat;
- confidence is low;
- the environment changes;
- new capabilities become available;
- a protected budget allows experimentation.

## 6. Scientific hypothesis contract

An automatically generated improvement should contain four explicit parts before testing:

1. **OBSERVATION** — what was actually measured;
2. **HYPOTHESIS** — proposed causal explanation;
3. **PREDICTION** — what should improve if the hypothesis is correct;
4. **FALSIFICATION** — what result rejects the hypothesis.

Criteria must be fixed before seeing the challenger result whenever practical.

## 7. Self-improvement hierarchy

Jade should earn broader mutation rights progressively.

### Level N1 — selection

Choose among existing nodes, models, capabilities and tools.

### Level N2 — tuning

Modify bounded configuration parameters under compiled SafetyPolicy limits.

### Level N3 — skills and strategies

Acquire or revise verified procedures and reusable reasoning strategies.

### Level N4 — internal component behaviour

Experiment with memory retrieval, planning, routing, research and evaluation methods behind stable interfaces.

### Level N5 — source-code challenger

Create a code patch only in an isolated branch/worktree, compile it, run tests, run regression suites and compare it to the current champion.

A code-writing model is an author, never the judge of its own change.

## 8. Champion / challenger rule

Production behaviour is always the **champion**.

An improvement is always a **challenger** until it has sufficient evidence.

```text
champion
   |
observation
   |
hypothesis
   |
challenger
   |
sandbox / shadow / paired experiment
   |
objective evidence
   +---- regression -> reject
   +---- insufficient -> keep testing / abstain
   +---- improvement -> eligible for promotion
```

Promotion and rollback must preserve provenance so Jade can learn from both success and failure.

## 9. Learning from failure

A failed experiment is evidence, not garbage.

Jade should record:

- what was attempted;
- why it was attempted;
- environmental conditions;
- expected result;
- actual result;
- likely causes;
- what changed in the world model;
- whether the failure generalizes or is context-specific.

The next search should use this evidence to avoid blindly repeating the same path.

## 10. Meta-learning

Jade should eventually evaluate not only solutions but its own method of producing solutions.

Examples:

- Does hypothesis method A find successful candidates faster than method B?
- Does one research strategy produce fewer unsupported claims?
- Does one memory retrieval policy improve answer quality without excessive latency?
- Is Jade spending too much experiment budget on familiar branches?

This creates a second-order loop:

```text
learn about the task
-> measure how the learning process performed
-> improve the learning process
-> learn future tasks more effectively
```

## 11. Non-negotiable evidence boundaries

Self-improvement must not bypass the mechanisms that make improvement measurable.

For 0.1.24 and the next gates:

- SafetyPolicy remains compiled and non-self-modifiable;
- experimental state remains separate from production state;
- hidden/sealed verifier data cannot be rewritten by the candidate author;
- no candidate can declare itself successful;
- regressions must remain observable;
- rollback must remain possible;
- uncertainty and unknowns must remain representable;
- an LLM proposal is not evidence by itself.

## 12. First implementation gate — 0.1.24 V0

The first concrete loop intentionally covers only:

```text
Runtime Eval evidence
-> detect reliability / fallback / latency weakness
-> Observation
-> Hypothesis
-> Prediction
-> Falsification criteria
-> one-field JadeConfig challenger
-> existing Evolution Sandbox
```

It does **not** promote the challenger and does not yet execute the paired experiment automatically.

The next gate is:

```text
challenger
-> automatically construct a bounded paired experiment
-> collect real champion/challenger evidence
-> EvolutionPolicy verdict
-> retain failed and successful experimental knowledge
```

Only after this causal loop is proven should Jade receive broader automatic experimentation or source-code mutation capabilities.
