---
phase: 2
title: "Statistical protocol and reporting"
status: pending
priority: P1
dependencies: [1]
effort: "1 day"
---

# Phase 2: Statistical protocol and reporting

## Overview

Fix what counts as a sample, how many are needed, and which interval is
reported, before any held-out case is scored. Then make the scorer report that
interval instead of a bare ratio.

## Requirements

- Functional: a preregistration document states the unit of analysis, the target
  effect, the sample size and how it was derived, the interval method, and the
  stopping rule.
- Functional: the preregistration is digest-bound and the validator asserts the
  digest, so editing it after results exist is detectable.
- Functional: benchmark results carry, per metric, the denominator, the number
  of independent cases behind it, and a two-sided interval.
- Non-functional: correlated repeats are never counted as independent cases. One
  scenario run 100 times contributes one case and 100 trials.

## Architecture

`evaluation/statistical-analysis-plan.md` is the preregistration. Its SHA-256 is
recorded in `evaluation/benchmark-manifest.yaml` and asserted by the Phase 8
validator, so the document and the manifest move together or the gate fails.

The unit of analysis is the *case*, not the run. A benchmark result gains a
`sampling` block: `{cases, trials, trials_per_case, independent}`. Metric entries
gain `interval: {method, lower, upper, level}`.

Intervals use the Wilson score interval for proportions, which stays inside
`[0, 1]` at small denominators where the normal approximation does not. With
three cases the interval is wide; that is the honest result and the reason the
gate stays BLOCK.

## Related Code Files

- Create: `evaluation/statistical-analysis-plan.md`
- Create: `evaluation/runner/sampling-intervals.mjs`
- Create: `evaluation/runner/sampling-intervals.test.mjs`
- Modify: `evaluation/schemas/benchmark-result.schema.json`
- Modify: `evaluation/runner/score-phase-07-trace-core.mjs`
- Modify: `evaluation/runner/score-phase-07-trace.test.mjs`
- Modify: `evaluation/benchmark-manifest.yaml`
- Modify: `scripts/validation/validate-phase-08-evaluation-foundation.mjs`
- Modify: `docs/evaluation-strategy.md`

## Implementation Steps

1. Write the preregistration: unit of analysis, target effect, derived sample
   size, Wilson interval at 95 percent, stopping rule, and an explicit statement
   that correlated repeats do not increase the case count.
2. Implement `sampling-intervals.mjs` with the Wilson interval and a case/trial
   accounting helper. Cover the boundaries: zero successes, all successes,
   denominator one, and denominator zero returning unavailable rather than a
   division result.
3. Extend the result schema with `sampling` and per-metric `interval`, keeping
   `additionalProperties: false`.
4. Emit both from the scorer. Derive `cases` from distinct scenario ids present,
   not from run count.
5. Record the preregistration digest in the benchmark manifest and assert it in
   the validator.
6. State in `docs/evaluation-strategy.md` what the current interval width means
   and why it keeps the exit gate closed.

## Success Criteria

- [ ] Wilson interval matches published values for known inputs and never leaves
      `[0, 1]`; a zero denominator reports unavailable rather than `NaN`.
- [ ] A benchmark result reports `cases`, `trials`, and a two-sided interval per
      scored metric, and the schema rejects a result missing them.
- [ ] Scenario A at 100 warm runs reports one case and 100 trials, not 100 cases.
- [ ] Editing the preregistration without updating the manifest digest fails the
      validator.
- [ ] Existing scenario scoring stays `PASS`; only the reported shape grows.

## Risk Assessment

The intervals will look bad: three cases cannot support a tight bound. The
failure mode is cosmetic pressure to widen the denominator by counting warm runs
as cases. The `independent` flag and the validator assertion exist to make that
change loud rather than quiet.

Changing the result schema affects every consumer of `benchmark-result.schema.json`.
The change is additive and the required fields are populated by the same scorer
that writes the file, so a stale consumer sees new fields rather than missing
ones.
