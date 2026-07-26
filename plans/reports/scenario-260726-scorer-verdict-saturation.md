# Scenario saturation — scorer verdict logic

Target: `evaluation/runner/score-phase-07-trace-core.mjs` and the projection
checks it aggregates.

Chosen because it has the largest blast radius in the subsystem. A defect here
does not merely mislead; it decides whether a run is reported as correct, and a
wrong verdict is the worst failure an evaluation system can have.

Mode: saturation. Halted after three consecutive iterations produced no novel
defect. Every scenario was probed against running code.

## Defects found and fixed

### 1. An observed failure was reported as missing evidence — Medium-High

A metric that had already seen a failing check reported `UNAVAILABLE` as soon as
any run contributed no observation, because unavailability was tested before
failure. The counts exposed it: the metric reported 13 of 14 checks passing while
calling itself unavailable, and the verdict moved from `FAIL` to `INCOMPLETE`.

Those two verdicts tell a reader opposite things. `INCOMPLETE` says evidence is
missing and the run should be repeated. `FAIL` says the system was wrong.
Scenario A replays a hundred runs, so one absent projection was enough to
present every real defect in that metric as an evidence gap.

Fixed: an observed failure outranks a missing observation. Absence cannot
un-observe a check that already failed.

### 2. A runless trace produced an artifact its own schema rejected — Low-Medium

Every check is vacuously satisfied over an empty run list, so the verdict was
already the correct `INCOMPLETE`, but `sample_count` required at least one and
the result violated its own contract. The absence of evidence reached a reader as
a broken file.

Fixed: zero is a permitted sample count. This subsystem reports absence
explicitly everywhere else.

### 3. The missing-evidence warning went silent on a failing run — Medium

Introduced by fix 1 and caught by continuing the loop. The warning was gated on
an `INCOMPLETE` verdict, so a run could report `FAIL` with six of eight metrics
unobservable and no warning at all — precisely when a reader most needs to know
how much went unmeasured.

Fixed: the warning follows the metrics that are unavailable, not the verdict they
roll up into.

## Probed and sound

| Dimension | Scenario | Result |
|---|---|---|
| Business logic | p95 far above the scenario latency budget, self-reported as passing | Fails. The budget is enforced, not taken on trust. |
| Business logic | Verdict precedence when both FAIL and UNAVAILABLE metrics exist | FAIL wins, which is correct. |
| Timing | Same trace scored twice, and a cloned trace | Byte-identical results. |
| Scale | 1001 runs against a declared bound of 1000 | Refused by the contract. The bound is intentional and the failure is closed. |
| Compliance | Analysis prose reaching the uploaded artifact | Only references and digests are published. The longest string in a result is 108 characters, and `versions.prompt` is a version label, not prompt text. |
| Data integrity | Duplicate runs in one trace | Reported honestly as one case with correlated trials. |

## Note on the loop

Fix 1 introduced defect 3. A single audit pass would have shipped it. The value
of continuing past the first finding was not thoroughness for its own sake — it
was catching the regression the first fix created.

## Unresolved questions

None.
