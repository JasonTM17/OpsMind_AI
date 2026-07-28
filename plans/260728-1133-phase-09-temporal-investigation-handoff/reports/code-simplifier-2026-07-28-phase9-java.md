# Phase 9 Java simplification

Date: 2026-07-28

## Scope

Reviewed all assigned Phase 9 production Java files against
`659ba823a1dd8bc867a6fe9cca5187f475dec979`. Changed only three files where
readability improved without contract or behavior changes:

- `JdbcInvestigationRunStore.java`
- `InvestigationWorkflowDispatchTransactions.java`
- `TemporalInvestigationWorkflowClient.java`

No tests, migrations, configuration, plans, POM files, scripts, or other
production files changed by this pass.

## Simplifications

- Initial run insert now uses an explicit guard clause before event-ledger
  append. Active-transaction check, conflict result, insert SQL, and atomic
  ledger append unchanged.
- Workflow acknowledgement and rejection reuse one immutable event local.
  Tenant context, inbox ordering, binding update, lease-token/expiry fencing,
  and outbox acknowledgement order unchanged.
- Existing Temporal execution reconciliation now names the full
  `matchesExistingExecution` predicate. Check order and short-circuit behavior
  unchanged: workflow type, task queue, memo digest, workflow/run identity, then
  exact first-history input.

Left remaining owned code unchanged. Further extraction would add abstraction
without reducing domain complexity. `JdbcInvestigationRunStore` remains slightly
over the 200-line guideline because splitting its persistence lifecycle would
obscure the package-scoped initial-writer transaction boundary.

## Invariant audit

- Database transaction remains committed before Temporal RPC.
- Request/payload digest and exact start-history verification preserved.
- Lease token plus live-expiry fencing SQL unchanged.
- Dispatcher secondary datasource bean names and `defaultCandidate = false`
  unchanged.
- `InvestigationInitialRunWriter` remains package-scoped.
- Default-off conditional configuration unchanged.
- No public signature, event schema, error code, SQL predicate, or retry policy
  changed.

## Verification

- `mvn -f services/platform-api/pom.xml -DskipTests compile` — PASS.
- Focused workflow suite — PASS, 17 tests, 0 failures/errors/skips:
  envelope factory, event codec, starter properties/runner/dispatcher,
  Temporal admission/client, and history-leak coverage.
- `git diff --check` on changed files — PASS.
- Owned-file trailing-whitespace scan — PASS.

## Concerns / blockers

- PostgreSQL-gated Phase 9 handoff/dispatcher integration tests not rerun;
  `OPSMIND_PHASE9_DB_INTEGRATION` environment not enabled.
- Local Maven used Java 24.0.2 while repository pin is Java 21. Focused compile
  and tests passed, but exact Java 21 CI remains authoritative.

## Unresolved questions

None.

Status: DONE_WITH_CONCERNS

Summary: Narrow behavior-preserving simplification completed in three owned
Java files; compile and 17 focused tests pass.

Concerns/Blockers: PostgreSQL integration gate and exact Java 21 toolchain not
available in this local pass.
