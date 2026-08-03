---
phase: 3
title: Verification reconciliation and gate proof
status: in-progress
priority: P1
dependencies:
  - 2
---

# Phase 3: Verification reconciliation and gate proof

## Overview

Review the delta, run available lightweight gates, then use exact-head CI for capacity-gated verification and reconcile Phase 4 claims.

## Requirements

- Review security, authorization, concurrency, idempotency, and no-side-effect assertions.
- Run static contract validation locally; run focused Maven only when capacity preflight permits.
- Push a focused Conventional Commit and require exact-head GitHub checks.
- Update the child plan through CK status commands; update parent Phase 4 wording only to proven facts.
- Merge only when required checks pass; delete merged branch and worktree afterward.

## Verification Matrix

| Gate | Evidence |
|---|---|
| Static contracts | `node scripts/validation/validate-phase-04-incident-contracts.mjs` |
| Focused Java | `IncidentControllerHttpTest` plus closure-related unit tests |
| PostgreSQL boundary | `IncidentHttpPersistenceIntegrationTest` in PostgreSQL trust job |
| Broad quality | PR Quality / Java platform-api |
| Security | CodeQL exact head |
| Cross-service | Cross-service evaluation exact head |

## Implementation Steps

1. Run independent code review and address all P0-P2 findings.
2. Re-run capacity preflight; do not bypass storage guards.
3. Run local static gate and any safe focused checks.
4. Commit, push, open PR, and inspect all exact-head checks including PostgreSQL test execution.
5. Merge, sync main, delete remote/local branch and remove worktree.
6. Reconcile plan status and parent Phase 4 resolution/closure evidence without claiming full Phase 4 completion.

## Success Criteria

- [ ] Review has no unresolved P0-P2 finding.
- [ ] Exact-head required CI is green and PostgreSQL lifecycle test executed, not skipped.
- [ ] Parent Phase 4 states resolution/closure proof accurately; PATCH/owner/alert remain open.
- [ ] PR merged and temporary branch/worktree removed.

## Unresolved Questions

None.
