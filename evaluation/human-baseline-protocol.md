# Human baseline protocol

This document specifies how a human comparator is produced. **No reviewer data
exists.** The gate reports `UNAVAILABLE` and Phase 8 exit stays BLOCK until
sessions run under this protocol.

Nothing here should be read as describing a pilot that happened.

## Why a comparator is required

Every metric currently reported measures the system against its own ground
truth. That answers whether the system did what the scenario said, not whether
it helped anyone. A comparator answers the second question: how long a qualified
operator takes to reach a verified root cause on the same evidence, how often
they abstain, and how often their conclusion is later corrected.

Without it, "the system passes" and "the system is useful" cannot be told apart.

## Reviewer qualification

A reviewer must have carried production on-call responsibility for a distributed
system for at least twelve months, and must not have contributed to OpsMind
scenario authoring, prompt text, thresholds, or ground truth.

Anyone who wrote or reviewed a scenario is disqualified for that scenario's
family. Self-review by the implementing team is not a baseline; it measures
familiarity with the fixture.

## Presentation

The reviewer receives exactly the evidence the system received for that case:
the same metrics, logs, traces, change records, and runbook excerpts, in the
same window. They do not receive the ground truth, the system's conclusion, its
confidence, or any other reviewer's answer.

Cases are presented in an order randomized per reviewer, so position effects do
not accumulate against the same case.

## Timing

Wall clock, from first display of the evidence to submission. The reviewer
records interruptions and the elapsed time is reported with interrupted sessions
flagged rather than silently discarded.

A session that exceeds sixty minutes is recorded as unresolved with the elapsed
time capped. An unresolved session is data, not a failure to record.

## Abstention

Abstaining is a valid, recorded outcome. A reviewer who judges the evidence
insufficient records `abstained: true` with no root cause. This is what
scenario B expects of the system, and a baseline that forbids it would make
abstention look like a system-only behaviour.

## Adjudication

Each case is answered independently by two qualified reviewers who do not see
each other's submissions. When they disagree, a third qualified reviewer, blind
to both the identities and the system output, decides. The adjudicated outcome
is recorded with `adjudicated: true`.

Disagreement is reported, not hidden. A case where qualified operators disagree
is evidence about the case, and a system judged against a contested answer is
being judged against noise.

## What is recorded

One record per reviewer per case, constrained by
`evaluation/schemas/human-baseline-record.schema.json`:

| Field | Meaning |
|---|---|
| `case_id` | The case answered. |
| `reviewer_id` | Pseudonymous. Never a name, employer, or account. |
| `minutes_to_conclusion` | Wall clock, capped at 60. |
| `abstained` | Reviewer judged evidence insufficient. |
| `root_cause_label` | One of the enumerated labels, or null when abstained. |
| `later_corrected` | Conclusion overturned by subsequent evidence. |
| `interrupted` | Session was interrupted. |
| `adjudicated` | Outcome came from third-reviewer adjudication. |

The schema forbids unknown fields and contains **no free-text field**. A
submission cannot carry incident narrative, customer identifiers, or reviewer
commentary into the repository, because there is nowhere to put it.

## Storage

Records live under `OPS_EVALUATION_HUMAN_BASELINE_ROOT`, outside Git, and are
referenced the same way held-out cases are. No record is committed.

## What would close this gate

- At least two qualified reviewers per case across the registered corpus.
- Adjudication for every disagreement.
- Reported comparison as intervals, per
  `evaluation/statistical-analysis-plan.md`, never as a point estimate.

This requires reviewer time. It is not an engineering task and cannot be closed
by writing more code.
