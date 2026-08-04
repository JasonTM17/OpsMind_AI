---
phase: 3
title: PostgreSQL proof and gate reconciliation
status: pending
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

- [ ] Focused unit/controller/HTTP tests pass.
- [ ] Fresh and upgrade PostgreSQL paths pass with no skipped PATCH tests.
- [ ] Full Platform Maven suite and static validators pass.
- [ ] Exact-head PR checks pass before merge.
- [ ] Docs retain all external Phase 4 blockers and do not claim G2 completion.

## Risks

- Local capacity may block heavy Docker/Maven runs. Preserve that limitation and
  require exact-head CI rather than deleting caches or fabricating evidence.
