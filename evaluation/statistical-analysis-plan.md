# Statistical analysis plan

This document is a preregistration. It fixes what counts as an observation, how
many are needed, and how uncertainty is reported, before any held-out case is
scored. Its SHA-256 is recorded in `evaluation/benchmark-manifest.yaml` and
asserted by `scripts/validation/validate-phase-08-evaluation-foundation.mjs`, so
a later edit that is not accompanied by a manifest update fails the gate.

Editing this document after results are seen does not become acceptable by
updating the digest alongside it. The digest makes the edit visible; the reviewer
decides whether it was legitimate.

## Unit of analysis

The unit is the **case**: one incident scenario with its own ground truth.

Replaying a case does not create a new one. A hundred warm runs of scenario A
supply a hundred correlated trials of a single case, and are reported as
`cases: 1, trials: 100, independent: false`. A metric ratio computed across
those trials describes the stability of one case, not accuracy across incidents.

This is why the current evidence cannot support a quality claim: three authored
cases are three observations, however many times each is replayed.

## What is currently measured

`sample_count` counts runs. `sampling.cases` counts distinct scenarios.
`sampling.independent` is true only when each case contributed exactly one
trial. Any statement about accuracy must quote `cases`, never `sample_count`.

## Target effect and sample size

The release claim in `docs/evaluation-strategy.md` is structured-output validity
at or above 99 percent. Treating a case as a Bernoulli trial, detecting a true
rate of 0.99 with a two-sided 95 percent interval no wider than 2 percentage
points requires on the order of 400 independent cases:

```
n ≈ z² · p(1 − p) / e²  =  1.96² × 0.99 × 0.01 / 0.01²  ≈  380
```

Rounded up to 400 to leave margin for exclusions. This is the derivation, not a
promise that 400 cases exist. The held-out corpus currently holds zero.

Interim reporting at smaller corpus sizes is expected and legitimate provided
the interval is reported with it. An interim result is never a release claim.

## Interval method

Two-sided Wilson score interval at the 95 percent level.

The normal approximation is rejected: at the denominators reported here it
produces bounds outside `[0, 1]`, and it collapses to zero width when every
trial succeeds, which would report three successes out of three as certainty.
Wilson stays inside `[0, 1]` and keeps width at the extremes. For 3 of 3 it
reports approximately `[0.44, 1.00]`, which is the honest statement that three
observations constrain very little.

A metric with no trials reports `lower: null, upper: null` and a reason. It is
never reported as zero.

## Stopping rule

Scoring runs over the full registered corpus. There is no interim look that can
stop collection early, because there is no adaptive decision to protect: the
corpus is fixed before a scoring run and every registered case is scored.

If the corpus grows, previously reported intervals are not retroactively
narrowed. A new corpus version produces a new report.

## Exclusions

A case tagged `quarantined` in `evaluation/held-out/manifest.yaml` is excluded
from scoring and remains registered, so a suspected leak stays visible rather
than disappearing from the record. Exclusions are counted and reported.

## Multiple comparisons

Eight metrics are reported per scenario. No single metric is designated the
primary endpoint, and no metric is promoted to a headline after seeing results.
When a claim is made about a specific metric across the corpus, the family-wise
error rate is controlled by Holm correction over the eight metrics.

## What would close the gate

- A held-out corpus of independent cases, sized against the derivation above.
- Reported intervals rather than point estimates, which the scorer now emits.
- A human comparator produced under `evaluation/human-baseline-protocol.md`.

The first and third do not exist. Phase 8 exit stays BLOCK until they do.
