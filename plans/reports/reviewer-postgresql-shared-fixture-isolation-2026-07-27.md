# PostgreSQL Shared-Fixture Isolation Review

## Code Review Summary

### Scope

- Base: `b481122b9699bbd45d69fb4e04bf313a8acda097`
- Reviewed pending files:
  - `.github/workflows/pr-quality.yml`
  - `services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentActivityTimelineHttpIntegrationTest.java`
- Diff: 2 files, +20/-4
- Context: `plans/reports/debugger-postgresql-shared-fixture-isolation-2026-07-27.md`
- Focus: shared PostgreSQL fixture isolation patch only

### Overall Assessment

**PASS — no P0-P3 findings in the refreshed diff.**

The patch corrects the intrinsic invalid-token request and isolates the mutating
timeline suite without weakening either CI gate. The controller also added the
timeline log to the always-uploaded PostgreSQL artifact set during review,
resolving the initial evidence-retention gap.

### Spec Compliance

| Requirement | Result | Evidence |
|---|---|---|
| Correct invalid first-page token | PASS | `IncidentActivityTimelineHttpIntegrationTest.java:126-132` uses `sendActivity(..., 1, null)`, retaining page size and media type while omitting the invalid literal `pageToken=1` |
| Preserve pooled Phase 3 coverage | PASS | `.github/workflows/pr-quality.yml:659-672` retains all 10 original pooled classes; source count is 17 `@Test` methods |
| Keep timeline suite blocking | PASS | `.github/workflows/pr-quality.yml:673-685` is a normal sequential step with `set -euo pipefail`, exact `-Dtest`, and no `continue-on-error` |
| Run all timeline tests | PASS | Exact class contains 3 `@Test` methods; supplied fresh-database evidence reports 3/3 PASS |
| Correct environment | PASS | Timeline step sets `OPSMIND_PHASE7_DB_INTEGRATION=true` and the shared PostgreSQL URL; job-level app/admin credentials remain available |
| Correct evidence ordering/retention | PASS | Pooled step precedes timeline, then alternating-tenant proof, then `if: always()` upload; timeline log is listed at `.github/workflows/pr-quality.yml:702` |

### Findings

- P0 Critical: none
- P1 High: none
- P2 Medium: none
- P3 Low: none

### Scout and Root-Cause Verification

- `IncidentActivityTimelineHttpIntegrationTest` upserts a second project under
  shared `TENANT_A` and creates five incidents through the real HTTP path. It has
  no cleanup, so project and outbox rows survive class execution.
- `PostgresTenantFixtures.seed(...)` is insert/upsert-only. It does not restore a
  clean tenant.
- `TenantRlsPoolIntegrationTest` requires exactly one visible project.
  `MessagingPostgresTestContext.close()` closes pools only; outbox rows remain.
- Therefore the RCA's extra-project and prior-outbox failures follow directly
  from shared persisted state. Separating the timeline class after the pooled
  Phase 3 invocation removes that ordering dependency while retaining the
  timeline proof.
- Subsequent alternating-tenant proof uses its own ephemeral database harness,
  so timeline residue in the job's primary database does not contaminate it.

### False-Green and Error-Boundary Review

- Pooled command still targets the exact original 10 classes. Programmatic source
  count: 17 tests. Only the three-test timeline class moved.
- Timeline class cannot silently skip: its enabling environment variable is set
  to exact lowercase `true`.
- Maven failure propagates through `tee` because the step enables `pipefail`.
- Timeline runs as a separate required step; failure blocks the job.
- `actions/upload-artifact` runs with `if: always()` after both database proof
  steps and now includes `phase-04/incident-activity-timeline.txt`.
- The added assertion description logs only the HTTP response body on failure.
  This suite uses synthetic fixture content and does not print bearer tokens,
  request headers, database credentials, or tenant secrets. No new data-exposure
  finding.
- No production, migration, authorization, API, or database-schema contract
  changed. No query, concurrency, N+1, or runtime error-propagation path changed.

### Adversarial Review

- Attempt: timeline test accidentally becomes optional. Rejected: ordinary step,
  exact enablement env, exact test selector, and pipeline failure propagation.
- Attempt: pooled proof loses original coverage. Rejected: current list equals
  the base list minus only `IncidentActivityTimelineHttpIntegrationTest`;
  remaining classes contain all original 17 test methods.
- Attempt: failed timeline evidence disappears. Rejected after refreshed diff:
  `tee` writes the failure stream and always-upload now includes the file.
- Attempt: request fix weakens protocol assertions. Rejected: `sendActivity`
  preserves `pageSize=1`, `Accept`, GET, timeout, auth, and endpoint; only the
  invalid cursor is removed.
- Attempt: suite split merely hides cross-test pollution. Rejected for the scoped
  CI contract: pooled tests execute before the mutating suite. Long-term
  per-suite fixture isolation remains architectural hardening, not required for
  this lowest-risk repair.

### Verification

- Supplied fresh PostgreSQL execution: pooled suite 17/17 PASS
- Supplied fresh PostgreSQL execution: timeline suite 3/3 PASS
- Fresh actionlint 1.7.12: PASS
- Fresh repository layout validator: `Errors=0`, `Result=PASS`
- Fresh `git diff --check HEAD`: PASS
- Programmatic workflow check:
  - original pooled list preserved: PASS
  - pooled test count 17: PASS
  - timeline test count 3: PASS
  - pooled → timeline → alternating → upload order: PASS
  - timeline artifact retention: PASS
- Additional Phase 7 phase-exit validator was run and returned its expected
  repository-state `BLOCK` because the external cross-service trace report is
  absent; its scoped source checks reported `Errors=0` and
  `CheckpointResult=PASS`. This is not caused by the pending diff.

### Metrics

- Review findings: P0=0, P1=0, P2=0, P3=0
- Pooled integration methods retained: 17/17
- Timeline integration methods retained: 3/3
- Workflow/static validation errors: 0

### Recommended Action

Land only after the refreshed diff and supplied 17/17 plus 3/3 PostgreSQL logs
are preserved with the change. No additional patch is required for this scope.

### Unresolved Questions

- None.

Status: DONE

Summary: PASS; invalid token fixed, original pooled 17-test gate preserved, and
the isolated blocking three-test timeline invocation now retains its artifact.

Concerns/Blockers: None in scoped patch. Per-suite disposable database isolation
remains optional long-term hardening.
