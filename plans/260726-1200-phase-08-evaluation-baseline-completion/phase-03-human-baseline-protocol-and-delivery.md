---
phase: 3
title: Human baseline protocol and delivery
status: completed
priority: P2
dependencies:
  - 1
  - 2
effort: 0.5 day plus review
---

# Phase 3: Human baseline protocol and delivery

## Overview

Specify how a qualified reviewer produces a comparator, how their output is
ingested and redacted, and what the gate needs in order to close. Deliver the
protocol and the ingestion path; do not produce reviewer data.

## Requirements

- Functional: a protocol states reviewer qualification, task presentation,
  timing method, adjudication of disagreement, and what is recorded per case.
- Functional: an ingestion contract validates a submitted baseline record and
  refuses free text that could carry incident content or personal data.
- Functional: the gate reports `UNAVAILABLE` with the reason "no reviewer data"
  rather than passing or being omitted.
- Non-functional: no reviewer identity, employer, or raw incident narrative is
  committed. Records carry a pseudonymous reviewer id.

## Architecture

`evaluation/human-baseline-protocol.md` is the procedure. A companion schema
constrains what a completed session may contain: case id, pseudonymous reviewer
id, minutes to a verified root cause, whether they abstained, whether their
conclusion was later corrected, and adjudication outcome. The schema forbids
free-text narrative fields, so a submission cannot smuggle incident content into
the repository.

Ingestion mirrors the held-out corpus: records live under a configured root
outside Git and are referenced by digest. With no records configured, the gate
is `UNAVAILABLE`, which is the state this plan expects to ship in.

## Related Code Files

- Create: `evaluation/human-baseline-protocol.md`
- Create: `evaluation/schemas/human-baseline-record.schema.json`
- Create: `evaluation/runner/human-baseline.mjs`
- Create: `evaluation/runner/human-baseline.test.mjs`
- Modify: `evaluation/benchmark-manifest.yaml`
- Modify: `scripts/validation/validate-phase-08-evaluation-foundation.mjs`
- Modify: `docs/evaluation-strategy.md`
- Modify: `docs/project-roadmap.md`
- Modify: `docs/progress.md`
- Modify: `README.md`
- Modify: `plans/260719-1747-opsmind-ai-production-platform/phase-08-simulator-and-evaluation-baseline.md`

## Implementation Steps

1. Write the protocol: qualification bar, per-case presentation identical to
   what the system receives, wall-clock timing, blind adjudication by a second
   reviewer, and the recorded fields.
2. Write the record schema with `additionalProperties: false` and no free-text
   field.
3. Implement ingestion and the unavailable path; cover a valid record, a record
   with an unexpected field, a record with a narrative field, and no records
   configured.
4. Wire the gate into the validator so the human-baseline status is reported
   every run instead of being absent.
5. Update the docs and the parent phase file to state which Phase 8 gates now
   close, which stay open, and that the open one needs reviewer time rather than
   engineering.

## Success Criteria

- [x] The protocol states qualification, presentation, timing, adjudication, and
      recorded fields without ambiguity about who decides a tie.
- [x] The schema rejects unknown and narrative fields; tests prove both.
- [x] With no records configured the gate reports `UNAVAILABLE` with the reason,
      and never `PASS`.
- [x] Docs and the parent phase file state the remaining gate and its owner.
- [x] Full local gate set and revision-bound CI pass; `PhaseExit` stays BLOCK.

## Risk Assessment

The temptation at this gate is to let the team self-review and call it a
baseline. The protocol's qualification bar and blind adjudication exist to make
that substitution visible, and the schema's lack of a narrative field means a
weak submission cannot be dressed up as a strong one.

Declaring Phase 8 complete because the engineering is complete would be the
exact failure this project's invariants forbid. The gate stays BLOCK until
reviewer data exists.
