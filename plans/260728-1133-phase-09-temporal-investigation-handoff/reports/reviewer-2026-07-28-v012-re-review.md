---
title: Phase 9 V012 dispatch-exclusivity re-review
date: 2026-07-28
scope: 37fc284 merged as 9fd1d82
status: done_with_concerns
superseded_by: reviewer-2026-07-28-legacy-ambiguity-followup.md
---

# Phase 9 V012 Dispatch-Exclusivity Re-review

> Historical V012 review before legacy-marker and pre-preflight payload edge
> cases were found. Their remediation and final disposition are recorded in
> `reviewer-2026-07-28-legacy-ambiguity-followup.md`.

## Scope

- Reviewed V012 migration, dedicated workflow-start claim, generic outbox
  ordering interaction, Temporal ambiguity handling, role membership guards,
  upgrade/static validators, and focused tests.
- Source commit: `37fc284`.
- Integration merge: `9fd1d82`.
- Historical V011 blockers: direct dispatcher DML bypass and false terminal
  rejection after an ambiguous post-RPC result.

## Critical and High Findings

None remain in the reviewed source.

### V011 direct-DML bypass: closed in source

V012:

- revokes dispatcher binding/inbox table and column authority;
- hides canonical `investigation.workflow-start.requested` outbox rows from
  generic dispatcher `SELECT`/`UPDATE`;
- grants dispatcher execution of a one-item, exact-event
  `SECURITY DEFINER` claim owned by `opsmind_dispatch_resolver`;
- rejects unsafe protected-role memberships in both directions.

The V012 migration applied successfully inside a transaction against the
existing V011 diagnostic database and was rolled back. A real dispatcher
session probe observed zero canonical workflow-start rows while still observing
a same-aggregate successor. A rogue role granted resolver membership caused
V012 to reject the migration and the transaction rolled back cleanly.

### Ambiguous Temporal terminalization: closed in source

Post-RPC ambiguous outcomes use `workflow.temporal-outcome-ambiguous`. The
dispatcher retries only inside the configured attempt, age, deadline,
authorization, and lease bounds. Exhaustion parks the canonical event as
`PENDING` with `workflow.reconciliation-required`; normal claim selection
excludes the parked row. No terminal `REJECTED` fact or second Start RPC is
created solely from local budget exhaustion.

Missing or blank execution/run identity and incomplete `AlreadyStarted`
metadata remain outcome-uncertain. A verified identity mismatch remains a
permanent collision.

### Hidden-predecessor ordering regression: closed in source

Restrictive RLS hides the canonical workflow-start row from the generic
dispatcher, which would otherwise let a visible same-aggregate successor skip
its unpublished predecessor. V012 adds the resolver-owned
`opsmind_has_unpublished_outbox_predecessor(...)` predicate and the generic
claimer calls it. A transactional probe inserted a visible sequence-two row
behind a hidden sequence-one workflow row and observed the successor remain
blocked.

## Remaining Medium-Risk Evidence Gaps

1. The migration contract test statically checks protected role memberships in
   both directions, and a manual transactional rogue-membership probe passed.
   The fresh/upgrade runtime harness still needs an automated negative
   membership case.
2. The resolver predecessor predicate executes per candidate. Existing indexes
   support its lookup, but no focused `EXPLAIN (ANALYZE, BUFFERS)` or latency
   threshold proves acceptable generic outbox claim cost.
3. Full fresh and V001-to-current upgrade execution, real-role
   denial/success matrices, late-settlement rollback injection, Maven focused
   tests, and exact-head CI are not executed on this integration head.
4. `workflow.reconciliation-required` is a safe hold, not convergence. B-017
   remains active until a separate read-only exact-workflow
   Describe/first-history lane and bounded aging/alerts exist.

## Verification

| Check | Result | Limit |
|---|---|---|
| `git diff --check HEAD^ HEAD` | PASS | Source formatting only |
| Upgrade shell syntax | PASS | Syntax only |
| Phase 9 static validator | PASS, 0 errors, V010/V011/V012, 7 required test files | Marker/contract gate |
| V012 apply inside transaction | PASS then ROLLBACK | Existing V011 diagnostic database, not full Flyway upgrade |
| Dispatcher visibility/predecessor probe | PASS then ROLLBACK | Focused SQL probe |
| Unsafe membership rejection probe | PASS then ROLLBACK | Focused SQL probe |
| Maven/Flyway full runtime suite | NOT RUN | D: capacity guard below 20 GiB |
| Exact-head CI | NOT RUN | Branch not pushed |

## Release Decision

Source merge into the Phase 9 integration branch is acceptable. Merge into
`main`, Temporal admission, roadmap G4, and production-release claims remain
blocked by the runtime evidence and reconciliation gaps above.

## Unresolved Questions

1. Which production component owns the no-Start reconciliation credential,
   bounded aging alert, and operator escalation?
2. What latency threshold will gate the predecessor predicate under realistic
   outbox cardinality and contention?

Status: DONE_WITH_CONCERNS

Summary: V012 closes the reviewed V011 Critical/High source defects; release
proof and the separate reconciliation owner remain incomplete.

Concerns/Blockers: Do not enable Temporal or merge to main until B-017 runtime,
atomicity, performance, and reconciliation evidence is complete.
