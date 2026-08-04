---
phase: 3
title: PostgreSQL proof and gate reconciliation
status: completed
priority: P1
dependencies: [2]
---

# Phase 3: PostgreSQL proof and gate reconciliation

## Context Links

- [Plan](./plan.md)
- [Phase 2](./phase-02-transactional-command-and-persistence.md)

## Requirements

- Prove active/inactive/foreign owner cases against disposable PostgreSQL.
- Prove stale/concurrent/replay/rollback behavior and immutable append effects.
- Extend static and CI gates without weakening capacity or secret checks.
- Reconcile parent Phase 4/docs only to evidence actually produced.

## Files

- Modify: `services/platform-api/src/test/java/ai/opsmind/platform/incident/**`
- Modify: `scripts/validation/run-phase-04*-postgres-contract*`
- Modify: `scripts/validation/validate-phase-04-incident-contracts.mjs`
- Modify if CI coverage needs wiring: `.github/workflows/pr-quality.yml`
- Modify after proof: parent Phase 4 and relevant `docs/**`

## Success Criteria

- [x] Focused unit/controller/HTTP tests pass.
- [x] Fresh and upgrade PostgreSQL paths pass with no skipped PATCH tests.
- [x] Full Platform Maven suite and static validators pass.
- [x] Exact-head PR checks pass before merge.
- [x] Docs retain all external Phase 4 blockers and do not claim G2 completion.

## Evidence recorded

- Static incident-contract validator: PASS (0 errors; 25 schemas, 40 fixtures).
- Full platform Maven suite: 533 tests, 0 failures, 0 errors, 67 skips.
- Local PostgreSQL gate remains unavailable because host storage is below the
  repository safety thresholds; exact-head CI is authoritative for fresh and
  upgrade migration proof.

## Risks

- Local capacity may block heavy Docker/Maven runs. Preserve that limitation and
  require exact-head CI rather than deleting caches or fabricating evidence.
