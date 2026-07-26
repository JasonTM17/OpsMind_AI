---
title: Phase 8B Production-Path Evaluation Evidence
description: >-
  Harvest strict, digest-bound evaluation evidence from the real Phase 7
  persistence path and add deterministic abstention/conflict smoke scenarios.
status: in-progress
priority: P1
branch: main
tags:
  - feature
  - backend
  - database
  - critical
blockedBy: []
blocks: []
created: '2026-07-26T03:08:59.977Z'
createdBy: 'ck:plan'
source: skill
---

# Phase 8B Production-Path Evaluation Evidence

## Overview

Phase 8B closes the false-evidence gap left by Phase 8A. The existing
cross-service gate will project accepted normalized analysis, investigation
timeline metadata, durable evidence metadata, and Tool Gateway receipt/audit
proof from its disposable PostgreSQL database into the trace before scoring.
The projection is synthetic-only, strict, bounded, digest-bound, and excludes
prompts, provider reasoning, credentials, raw connector bodies, and customer
telemetry.

The same production path gains Scenario B (`ABSTAINED`) and Scenario C
(digest-bound conflicting evidence with cautious confidence). A new additive
migration makes every Platform-accepted normalized response durable and
unambiguous. This checkpoint does not claim held-out accuracy, human benefit,
live-provider conformance, G4, or Phase 8 completion.

## Phases

| Phase | Name | Status |
|-------|------|--------|
| 1 | [Evaluation Evidence Contract](./phase-01-evaluation-evidence-contract.md) | In Progress — implementation/local contract proof present; fresh database integration pending |
| 2 | [Cross-Service Artifact Harvesting](./phase-02-cross-service-artifact-harvesting.md) | In Progress — least-privilege export/projector wiring present; fresh harness proof pending |
| 3 | [Abstention and Conflict Scenarios](./phase-03-abstention-and-conflict-scenarios.md) | In Progress — A/B/C contracts and local tests present; production-path CI pending |
| 4 | [Verification and Delivery](./phase-04-verification-and-delivery.md) | In Progress — local lightweight gates pass; blocking review and CI pending |

## Dependencies

- Consumes already-delivered Phase 7 cross-service and Phase 8A scorer
  checkpoints; their broader plans need not complete first.
- Reuses V004/V006/V007, Tool Gateway V001 receipts/audit, and the immutable
  investigation intent catalog.
- Adds rolling-compatible Platform V008 expand and Tool Gateway V002
  provenance migrations; the Platform contract step waits for old-writer drain.
  No evaluation HTTP endpoint.

## Acceptance Boundary

- Every scored run has exact timeline, evidence, normalized-analysis and
  tool-receipt references with canonical SHA-256 digests.
- Scenario A remains compatible; canonical A/B/C fixtures all score `PASS`.
- Missing/tampered projection rows, identity drift, unexpected fields,
  untrusted analysis, receipt drift, unsafe action, false abstention, ignored
  conflict, excessive confidence, split audit/receipt/evidence digests, or
  undercounted multi-round cost fails closed.
- Fresh same-job binaries execute A/B/C in CI; executable digests and source
  revision bind the evidence. `PhaseExit=BLOCK` remains honest.

## Validation Log

- Fact checked: current trace producer, finalizer, V006/V007, Tool Gateway V001,
  evaluator schemas/scorer/tests, launchers and CI gate all exist.
- Contract consumers: evaluation CLI, both launchers, Phase 8 validator,
  repository-layout validator, CI foundation job, and Phase 7 trace validator.
- Design choice: disposable-database projection over internal API or production
  schema expansion; see `../reports/researcher-260726-phase8b-evaluation-design.md`.
- Local proof: independent earlier tester 28/28 Node tests; current remediation
  rerun 33/33 Node and 40/40 targeted Python tests, 50 shuffled semantic-order
  trials, and the path-safety test executed under PowerShell 7 covering
  handle-derived exit status, concurrent capture and standard input; Phase 8
  validator
  `Implemented=3 CanonicalResults=3 Errors=0 CheckpointResult=PASS
  PhaseExit=BLOCK`; repository layout, actionlint, and project secret scan PASS.
- Production-path proof: revision-bound run `30199870220` on commit `963ab8d` is
  terminal green. Fresh disposable Docker/PostgreSQL Scenario A at 100 warm runs,
  B, and C each score `EvaluationVerdict=PASS` with all eight metrics passing;
  the uploaded artifact binds `GitHead=963ab8d`, `GitTree=0`, and service JAR,
  manifest, export SQL, and projector digests.
- Pending proof: independent blocking-review closure. Phase 8 exit stays BLOCK on
  the held-out corpus and human baseline, which this checkpoint does not claim.
- Gate-label question: the parent A-Z plan names Phase 8 exit `G4`, while
  `docs/project-roadmap.md` uses `G4` for durable workflow and `G7` for
  product/evaluation. Parent A-Z `G4` is canonical here until the taxonomy is
  explicitly reconciled.

## Red Team Review

- Rejected migration-free abstention proof: V006 discards the accepted abstain
  response. Add an immutable accepted-response event binding instead.
- Rejected authored-only B/C: both scenarios must run through Platform, AI
  Runtime, PostgreSQL, Tool Gateway where applicable, and operator projection.
- Replace migration-owner export with disposable NOBYPASSRLS evaluator roles,
  allowlisted views, read-only transactions and exact tenant/run scope.
- Bind connector ID/profile/manifest bytes, AI invocation, response, evidence,
  counter-evidence mapping, executed binary digests and Git revision.
- Reject duplicate JSON keys and unsafe free-text values; separate raw-byte and
  domain-separated canonical digests; bound SQL rows/bytes before materializing.
- Delete transient raw export on success and failure; retain only redacted
  diagnostics. Publish only through reparse-safe managed paths.
- CI must build and execute the multi-scenario harness, not only static scorer
  tests.

## Whole-Plan Consistency Sweep

Phase 1 owns durable acceptance and strict contracts. Phase 2 owns least-
privilege export and artifact safety. Phase 3 owns real A/B/C producers and
scoring. Phase 4 owns same-job integration, independent verification, docs and
delivery. No phase may claim production-path evidence from authored fixtures.
