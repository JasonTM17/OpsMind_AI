---
title: "Phase 8 Evaluation Baseline Completion"
description: >-
  Close the Phase 8 exit gates that remain after production-path A/B/C evidence:
  held-out corpus governance, the ten-family specification, a preregistered
  statistical protocol with interval reporting, and a human-baseline protocol.
status: pending
priority: P1
branch: "main"
tags:
  - evaluation
  - governance
  - critical
blockedBy: []
blocks: []
created: "2026-07-26T12:01:23.156Z"
createdBy: "ck:plan"
source: skill
---

# Phase 8 Evaluation Baseline Completion

## Overview

Phase 8B proved that three deterministic scenarios run through the real
Platform, AI Runtime, Tool Gateway, and PostgreSQL path and score `PASS` on all
eight metrics in revision-bound CI. That is mechanical evidence about one
authored scenario each. Phase 8 exit additionally requires evidence about
*quality*: cases the system was not built against, a sample size chosen before
looking at results, intervals rather than point estimates, and a human
comparator.

This plan closes the gates that can be closed by engineering and states plainly
which gate cannot. Human-baseline *data* requires qualified reviewers; this plan
delivers the protocol and the ingestion path for their output, not fabricated
results.

## Scope Decision: no separate simulator service

The parent phase file lists `services/incident-simulator/` with a Python runner
and Compose fragment. That inventory records the original target and the
delivery pivoted: scenarios now execute against the real services through
`scripts/validation/cross-service/run-cross-service-verification.ps1`, which is
stronger evidence than a simulator process would produce.

Building the simulator now would duplicate scenario definition, fixture
emission, and reset logic that the harness and `evaluation/scenarios/` already
own, and would create a second place where a scenario can drift from the
contract it is scored against. This plan therefore does not create
`services/incident-simulator/`, and Phase 1 records that decision in the parent
phase file so the inventory stops reading as outstanding work.

## Phases

| Phase | Name | Status |
|-------|------|--------|
| 1 | [Held-out corpus governance](./phase-01-held-out-corpus-and-family-specification.md) | Pending |
| 2 | [Statistical protocol and reporting](./phase-02-statistical-protocol-and-reporting.md) | Pending |
| 3 | [Human baseline protocol and delivery](./phase-03-human-baseline-protocol-and-delivery.md) | Pending |

## Dependencies

- Consumes the Phase 8B checkpoint in
  `plans/260726-1004-phase-08b-production-path-evaluation/`, which is delivered
  apart from its blocking review.
- Reuses `evaluation/schemas/`, the scorer, and the cross-service harness. No
  new service, migration, or HTTP endpoint.
- Phase 9 depends on this plan only for the thresholds it freezes; it does not
  wait for human-baseline data.

## Acceptance Boundary

- Held-out cases are referenced by digest and never committed as payloads.
- Sample size, effect size, and interval method are fixed before any held-out
  case is scored, and the preregistration is digest-bound so a later edit is
  detectable.
- Reported quality carries an interval and an explicit correlated-repeat caveat;
  no percentage is reported over a denominator of three authored scenarios.
- The ten families already specified in `evaluation/benchmark-manifest.yaml`
  stay untouched; Phase 16 still owns the seven reserved ones.
- The human-baseline gate stays open and visibly attributed to missing reviewer
  data rather than being quietly dropped or filled with synthetic answers.

## Validation Log

- Fact corrected: an earlier revision of this log claimed
  `evaluation/benchmark-manifest.yaml` was missing. It exists, carries JSON under
  a `.yaml` name, already specifies ten families as `SIM-01` through `SIM-10`
  with three implemented and seven reserved, and is already schema-validated by
  the Phase 8 validator. The ten-family specification gate is therefore met and
  is out of scope here.
- Fact checked: `evaluation/scenarios/` holds three ground truths.
  `evaluation/held-out/`, `evaluation/human-baseline-protocol.md`, and
  `evaluation/statistical-analysis-plan.md` do not exist.
- Fact checked: no module reads a held-out corpus; the only occurrences of the
  term are prose in `docs/evaluation-strategy.md`, `evaluation/README.md`, and
  the scorer warning that disclaims held-out quality.
- Fact checked: `services/` contains `ai-runtime`, `platform-api`, and
  `tool-gateway` only; no simulator service was ever created.
- Fact checked: run `30200584275` on `134d63c` scores A at 100 samples and B/C
  at 1 sample each, so the current denominator is authored scenarios, not
  independent cases.
- Constraint: local heavy execution stays blocked by the storage floors, so
  every gate in this plan must be provable by unit tests, validators, and CI.

## Red Team Review

- Reject any percentage computed over correlated repeats of one scenario. 100
  warm runs of scenario A are 100 trials of one case, not 100 cases.
- Reject a preregistration that can be edited after results are seen; bind it by
  digest and assert that digest in the validator.
- Reject committing held-out payloads to satisfy a manifest; the manifest must
  reference content by digest and fail closed when a payload is missing.
- Reject a human-baseline document that implies a pilot happened. It must state
  that no reviewer data exists and what would make the gate closable.
- Reject re-specifying the ten families. They already exist and are validated;
  rewriting them would be churn, and the seven reserved ones belong to Phase 16
  where an implementer can state the discriminating behaviour against real code.
