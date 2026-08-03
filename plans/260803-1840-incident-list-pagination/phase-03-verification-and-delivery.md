---
phase: 3
title: Verification and Delivery
status: completed
priority: P1
dependencies:
  - 2
---

# Phase 3: Verification and Delivery

## Overview

Prove contract, authorization, query boundedness, regression safety, and exact-revision delivery.

## Requirements

- Run the narrowest contract/unit/HTTP checks first, then the shared Platform suite.
- Exercise fresh disposable PostgreSQL/RLS and index-plan assertions when capacity permits.
- Prove fresh V001-V016 and V015-to-V016 upgrades without changing historical checksums.
- Use remote CI as authoritative evidence when workstation capacity blocks heavy gates.
- Complete independent code review before commit/PR merge.

## Related Code Files

- Modify: `scripts/validation/run-phase-04-domain-tests.ps1`
- Modify: `scripts/validation/run-phase-04-local-postgres-contract.ps1`
- Run, do not modify: `scripts/validation/validate-phase-04-incident-contracts.mjs`
- Run, do not modify: `services/platform-api/src/test/java/ai/opsmind/platform/persistence/MigrationContractTest.java`
- Run, do not modify: `services/platform-api/src/test/java/ai/opsmind/platform/persistence/FlywayV016RecoveryHarnessTest.java`
- Run, do not modify: `services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentListPlanAssertionsTest.java`
- Modify when evidence lands: parent Phase 4 plan and `docs/progress.md`
- Create: `reports/implementation-report.md` and `reports/code-review.md`

## Implementation Steps

1. Run static contract validation and focused cursor/controller/query tests.
2. Run full Platform Maven verify; run disposable PostgreSQL gate after fresh capacity preflight.
3. Run representative `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` assertions for
   filtered and unfiltered strict tuple predicates and both V016 indexes.
4. Run mandatory tester and code-reviewer passes; resolve all blocking findings.
5. Sync all three phase checklists from evidence, commit focused slices, push PR,
   wait for revision-bound PR Quality/cross-service/CodeQL, then merge only if green.

## Success Criteria

- [x] Static validator, focused tests, and full available Platform suite pass.
- [x] Fresh/upgrade PostgreSQL evidence proves RLS isolation, historical migration
  checksum stability, deterministic unchanged-dataset traversal, and no writes.
- [x] V016 non-transactional concurrent DDL, valid/ready catalog state,
  failed-build recovery, and rollout-before-runtime checks pass.
- [x] Empty, one-item, exact-size, size+1, final-page, all-status, timestamp-tie,
  arbitrary-boundary, concurrent-update, and sanitized database-failure cases pass.
- [x] Both filtered and unfiltered plans use bounded V016-compatible access paths.
- [x] Reviewer reports no critical/high correctness, security, or compatibility issue.
- [x] Exact head SHA passes required remote checks before merge; GitHub PR #58
  remains the authoritative revision-bound admission record.
- [x] Docs claim only incident-list progress; Phase 4 and external blockers remain explicit.

## Risk Assessment

Current C:/D: capacity may block heavy local gates. Never lower thresholds or label
static/unit output as database proof; push a clean revision and use remote CI.
