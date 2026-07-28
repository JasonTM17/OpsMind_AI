---
phase: 5
title: "CI documentation and ship"
status: in-progress
effort: "1 day"
---

# Phase 5: CI documentation and ship

## Overview

Land only after exact-head CI and an independent production-readiness review.
Update architecture/runbooks without claiming a live worker or Phase 9 exit.

## Implementation Steps

1. Wire the Phase 9 static validator and focused Java/Temporal/PostgreSQL tests
   by exact class name into the existing hard-coded PostgreSQL job, including
   app/dispatcher datasource env, fresh/upgrade V010, Temporal test environment,
   and fail-on-skipped assertions. Keep Linux/Windows bootstrap, Compose,
   Java SBOM/OSV, dependency-review/license, secret, and cross-service gates intact.
2. Update `docs/system-architecture.md`, `docs/deployment-guide.md`,
   `docs/project-roadmap.md`, `docs/progress.md`, `.env.example`, and relevant
   README sections with:
   - transaction/RPC boundary and deterministic ID;
   - API vs dispatcher database roles;
   - `200` synchronous versus `202 + Location` asynchronous contract;
   - logical cluster/namespace binding and target-change procedure;
   - active-run freeze/inventory/backfill/zero-orphan cutover;
   - default-off/cutover/rollback procedure;
   - metrics/alerts for pending age, poison count, retries, stale leases, and
     Temporal start latency;
   - worker-readiness admission, rejected-handoff recovery, future activity
     reauthorization, explicit “handoff infrastructure only,” and B-013 status.
3. Run narrow tests, full Platform API tests, static validators, secret scan,
   workflow syntax validation, and clean-tree checks. Prefer CI for heavy gates
   because C/D free-space headroom is constrained.
4. Run independent code review for concurrency, error boundaries, API contracts,
   backwards compatibility, RLS/authz, data leaks, and migration upgrades.
5. Fix accepted findings; create a focused conventional commit, push the feature
   branch, open a PR, wait for exact-head required checks, squash merge, verify
   merge-tree equivalence, then delete local/remote branch and worktree.
6. Recheck free space on C/D. Preserve user/session hook logs and unrelated dirty
   files.

## Quality Gates

- Focused Maven tests including Temporal official test environment.
- Fresh and upgrade PostgreSQL migration tests with app/dispatcher roles.
- `node scripts/validation/validate-phase-09-workflow-handoff.mjs`.
- Existing Phase 3/4/7/8 validators and secret/history scan.
- Existing Java CycloneDX/OSV and dependency-review license gates include
  Temporal runtime and testing transitive graphs; Maven convergence/tree is
  captured for review.
- PR Quality and cross-service workflows on the exact feature head.
- Independent review has no unresolved Critical/High findings.

## Risks and Rollback

- CI capacity/disk: avoid local image builds; use existing immutable CI runners.
- Merge with stale base: update from `main`, rerun exact-head checks, and verify
  squash merge tree equals the approved feature tree.
- Runtime rollback is flag-off; database migration is forward-only and retains
  pending handoffs for reconciliation.
- Namespace/target rollback never repoints pending bindings. Freeze admission,
  drain/reconcile the original logical cluster, then migrate through a reviewed
  target-identity procedure.

## Success Criteria

- [ ] Documentation and flags match implemented behavior; no live-worker claim.
- [ ] Exact-head CI and independent review pass.
- [ ] PR is squash-merged; feature branch/worktree are removed safely.
- [ ] Main is clean except explicitly preserved user/session files.
- [ ] Master Phase 9 remains in progress with B-013 visible.
