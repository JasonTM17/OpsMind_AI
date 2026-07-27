# Shared PostgreSQL Fixture Isolation - Investigation Report

## Executive Summary
- **Issue:** PR-quality run `30251872696`, job `89932607734`, PostgreSQL pooled Maven step failed with 4 tests after V009 validation passed.
- **Impact:** `postgres-contract` job blocked. Phase 3 tenant/RLS and outbox recovery proof became non-deterministic when Phase 7 activity/investigation tests ran in the same database/process.
- **Confirmed root cause:** `IncidentActivityTimelineHttpIntegrationTest` mutates shared tenant-wide Phase 3 fixtures (`TENANT_A`, `PROJECT_A`) and leaves rows behind; Surefire then runs Phase 3 tenant/outbox tests against that dirty database. Same class also contains an independent invalid first-page request: it sends `pageToken=1`, which is guaranteed to return `400`.
- **Lowest-risk fix:** 1) fix the invalid first activity-timeline request in `IncidentActivityTimelineHttpIntegrationTest`; 2) remove that class from the pooled Phase 3 Maven matrix in `.github/workflows/pr-quality.yml` and run it in a separate Maven invocation after the pooled Phase 3 checks.

## Follow-up Evidence
- **New exact-SHA run:** `30255117698`
- **Job:** `89943494044`
- **Artifact:** `D:\OpsMind_AI-ci-artifacts-30255117698\phase-03\migration-and-rls.txt`
- **Observed result:**
  - V009 PASS
  - pooled Maven matrix PASS (`17/17`)
  - isolated `IncidentActivityTimelineHttpIntegrationTest` PASS (`3/3`)
  - later `Run alternating-tenant and append-only contract` FAIL with `expected exactly one project in tenant context`
- **Revised conclusion:** on the current exact SHA, the remaining CI break is workflow ordering against the shared `opsmind` database. `OPSMIND_EPHEMERAL_DB=true` does not provision or select an isolated database in `run-phase-03-postgres-contract.sh`; it only gates execution. Because the isolated activity-timeline step still targets `jdbc:postgresql://127.0.0.1:5432/opsmind`, it pollutes the same database the later alternating contract reads. Minimal correction: move the isolated activity-timeline step after `Run alternating-tenant and append-only contract`.

## Symptom
- `IncidentActivityTimelineHttpIntegrationTest.vendorOrdersTiesAndSeesLateTiedInvestigationAppend`
  expected `200`, got `400` at `IncidentActivityTimelineHttpIntegrationTest.java:129`.
- `TenantRlsPoolIntegrationTest.transactionContextDoesNotLeakAcrossPhysicalConnectionReuseAndFailurePaths`
  expected tenant-visible `projects` count `1`, got `2` at `TenantRlsPoolIntegrationTest.java:105`.
- `TransactionalOutboxIntegrationTest.convergesAcrossCommitPublishAcknowledgementRetryOrderingAndPoisonWindows`
  expected first claim batch size `1`, got `6`.
- `OutboxDispatcherWorkloadIntegrationTest.separatesAppendFromTenantBoundLeaseAuthority`
  expected first claimed event `d15a1001-d15a-415a-815a-d15a00000001`, got prior incident event `7dd058c9-6d32-4f6b-8fd0-44efd1c71030`.

## Exact Repro
- Workflow step: `.github/workflows/pr-quality.yml:660-672`
- Env in failing step:
  - `OPSMIND_PHASE3_DB_INTEGRATION=true`
  - `OPSMIND_PHASE7_DB_INTEGRATION=true`
  - `SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/opsmind`
- Maven command:

```bash
mvn --batch-mode --no-transfer-progress \
  -Dmaven.repo.local="$OPS_CACHE_ROOT/maven" \
  -Dtest=TenantRlsPoolIntegrationTest,TransactionalOutboxIntegrationTest,TransactionalInboxIntegrationTest,PlatformUserStatusVerifierIntegrationTest,OutboxDispatcherWorkloadIntegrationTest,InvestigationPersistenceIntegrationTest,InvestigationPersistenceIntegrityIntegrationTest,InvestigationEvidencePersistenceIntegrationTest,InvestigationEvidenceRollbackIntegrationTest,InvestigationEvidenceReplayIntegrationTest,IncidentActivityTimelineHttpIntegrationTest \
  -f services/platform-api/pom.xml test
```

## Timeline
- `09:22:23Z-09:22:33Z` investigation persistence tests run and pass in one Maven JVM.
- `09:22:34Z-09:22:35Z` `IncidentActivityTimelineHttpIntegrationTest` starts.
- `09:22:35Z` first activity-timeline `GET` fails `400` at line 129.
- `09:22:35Z` `TenantRlsPoolIntegrationTest` starts next and sees `2` tenant-visible projects, not `1`.
- `09:22:35Z-09:22:36Z` outbox tests start next and claim incident-created rows left behind by the activity-timeline test.

Follow-up run `30255117698` changed the order and symptom:
- pooled Phase 3/7 Maven step passed without the activity-timeline class included
- isolated activity-timeline Maven step passed
- the later shell contract `scripts/validation/run-phase-03-postgres-contract.sh` failed because it still read the same shared `opsmind` database after the isolated activity-timeline step mutated it

Observed order came from Surefire/JUnit Platform, not the `-Dtest=` list order. `services/platform-api/pom.xml` has no explicit `maven-surefire-plugin` ordering override.

## Evidence Chain

### 1. Shared fixtures are global, static, tenant-wide
- `PostgresTenantFixtures` seeds constant IDs:
  - `TENANT_A`, `PROJECT_A`, `PROJECT_B`, dispatcher accounts at [PostgresTenantFixtures.java](D:/worktrees/incident-activity-timeline-phase2/services/platform-api/src/test/java/ai/opsmind/platform/testing/PostgresTenantFixtures.java:11)
  - seed is `INSERT ... ON CONFLICT` only, no cleanup at [PostgresTenantFixtures.java](D:/worktrees/incident-activity-timeline-phase2/services/platform-api/src/test/java/ai/opsmind/platform/testing/PostgresTenantFixtures.java:23)

### 2. Messaging and tenant tests assume a clean shared tenant
- `TenantRlsPoolIntegrationTest` seeds the global fixtures, then asserts tenant-visible `projects` count is exactly `1` at [TenantRlsPoolIntegrationTest.java](D:/worktrees/incident-activity-timeline-phase2/services/platform-api/src/test/java/ai/opsmind/platform/tenancy/TenantRlsPoolIntegrationTest.java:32) and [TenantRlsPoolIntegrationTest.java](D:/worktrees/incident-activity-timeline-phase2/services/platform-api/src/test/java/ai/opsmind/platform/tenancy/TenantRlsPoolIntegrationTest.java:102).
- `MessagingPostgresTestContext.open()` also only reseeds globals and opens datasources; `close()` only closes pools, no row cleanup at [MessagingPostgresTestContext.java](D:/worktrees/incident-activity-timeline-phase2/services/platform-api/src/test/java/ai/opsmind/platform/messaging/MessagingPostgresTestContext.java:64) and [MessagingPostgresTestContext.java](D:/worktrees/incident-activity-timeline-phase2/services/platform-api/src/test/java/ai/opsmind/platform/messaging/MessagingPostgresTestContext.java:211).

### 3. Activity timeline test writes tenant-wide rows and leaves them behind
- `IncidentActivityTimelineHttpIntegrationTest.@BeforeEach` seeds globals, then inserts a second tenant-A project `aaaaaaa2-...` and membership with `ON CONFLICT` and no cleanup at [IncidentActivityTimelineHttpIntegrationTest.java](D:/worktrees/incident-activity-timeline-phase2/services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentActivityTimelineHttpIntegrationTest.java:82).
- Across its 3 test methods it creates 5 incidents total under `TENANT_A`:
  - 1 incident in `vendorOrdersTiesAndSeesLateTiedInvestigationAppend`
  - 1 incident in `vendorRouteMaintainsHiddenDenialAcrossTenantProjectIncidentAndCursorScopes`
  - 3 incidents in `unionQueryExcludesSameTenantRowsFromOtherProjectsAndIncidents`
- Each incident creation appends an outbox row because they use the real HTTP create path, not a mock.

### 4. The leaked outbox rows match the activity-timeline test exactly
- CI artifact `authz-rls-pool-matrix.txt` shows `TransactionalOutboxIntegrationTest` claimed 6 rows:
  - 5 leaked `INCIDENT_CREATED` rows
  - plus its own deterministic `phase3.event` row
- The 5 leaked rows match the activity-timeline class exactly by `reason` and `projectId`:
  - `authorized`
  - `fixture`
  - `fixture`
  - `fixture`
  - `secret-free-text-...`
- That is the exact create pattern of the 3 activity-timeline methods.
- `OutboxDispatcherWorkloadIntegrationTest` then claims leaked event `7dd058c9-...` instead of its own `EVENT_A`, proving the leak is not theoretical; it changed observable dispatcher order at [OutboxDispatcherWorkloadIntegrationTest.java](D:/worktrees/incident-activity-timeline-phase2/services/platform-api/src/test/java/ai/opsmind/platform/messaging/OutboxDispatcherWorkloadIntegrationTest.java:69).

### 5. The extra tenant-A project explains the RLS count failure exactly
- Phase 3 fixture seed creates one tenant-A project: `PROJECT_A` at [PostgresTenantFixtures.java](D:/worktrees/incident-activity-timeline-phase2/services/platform-api/src/test/java/ai/opsmind/platform/testing/PostgresTenantFixtures.java:57).
- Activity timeline `@BeforeEach` adds a second tenant-A project `SAME_ORG_PROJECT` at [IncidentActivityTimelineHttpIntegrationTest.java](D:/worktrees/incident-activity-timeline-phase2/services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentActivityTimelineHttpIntegrationTest.java:91).
- `TenantRlsPoolIntegrationTest` then sees `2` visible projects instead of `1` at [TenantRlsPoolIntegrationTest.java](D:/worktrees/incident-activity-timeline-phase2/services/platform-api/src/test/java/ai/opsmind/platform/tenancy/TenantRlsPoolIntegrationTest.java:104).

### 6. The timeline `400` is a separate, deterministic test bug
- The failing assertion is line 129 in the activity-timeline test.
- The request immediately before it is:
  - `send("GET", ..., pageToken = "1")` at [IncidentActivityTimelineHttpIntegrationTest.java](D:/worktrees/incident-activity-timeline-phase2/services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentActivityTimelineHttpIntegrationTest.java:126)
- For `GET`, helper `send(...)` hardcodes `?pageSize=1` and appends the last argument as `&pageToken=...` at [IncidentActivityTimelineHttpIntegrationTest.java](D:/worktrees/incident-activity-timeline-phase2/services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentActivityTimelineHttpIntegrationTest.java:365).
- Activity timeline parsing rejects any non-canonical/non-base64 token before SQL executes at [IncidentTimelinePageToken.java](D:/worktrees/incident-activity-timeline-phase2/services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentTimelinePageToken.java:53).
- Independent validation: literal `"1"` is not valid URL-safe base64. That request cannot produce `200`.

### 7. `OPSMIND_EPHEMERAL_DB=true` is only a guard, not a database selector
- `run-phase-03-postgres-contract.sh` checks only:
  - `if [[ "${OPSMIND_EPHEMERAL_DB:-false}" != "true" ]]; then ... exit 2`
  - no database creation, no random DB naming, no cleanup, no schema reset
- The script selects:
  - `db_name="${PGDATABASE:-opsmind}"` at `scripts/validation/run-phase-03-postgres-contract.sh`
- The workflow invokes it as:
  - `OPSMIND_EPHEMERAL_DB=true bash scripts/validation/run-phase-03-postgres-contract.sh`
  - with job-level `PGDATABASE=opsmind` in `.github/workflows/pr-quality.yml`
- New artifact header proves the runtime target:
  - `DatabaseName=opsmind`
  - not a generated disposable DB name
- Therefore the alternating contract is mutating and reading the same shared CI database as the earlier Maven and isolated activity-timeline steps.

### 8. New failure in run `30255117698` is still the same contamination class
- `migration-and-rls.txt` shows:
  - `DatabaseName=opsmind`
  - then `ERROR: expected exactly one project in tenant context`
- That error is raised inside `run-phase-03-postgres-contract.sh` immediately after:
  - `SELECT public.opsmind_set_tenant_context(...)`
  - `SELECT count(*) INTO visible_projects FROM projects;`
  - `IF visible_projects <> 1 THEN RAISE EXCEPTION 'expected exactly one project in tenant context';`
- This is the same assertion shape as `TenantRlsPoolIntegrationTest`: it assumes tenant A has exactly one visible project.
- Since the preceding isolated activity-timeline step targets `jdbc:postgresql://127.0.0.1:5432/opsmind`, and its `@BeforeEach` inserts `SAME_ORG_PROJECT` for tenant A, the later alternating contract reads a polluted shared DB and fails.

## Hypotheses Tested

### Hypothesis A: Hikari / tenant-context leaked transaction state across reused connections
- **Why plausible:** failing test name says “does not leak across physical connection reuse”.
- **Tested by:** reading failure site and call path.
- **Eliminated:** failure occurs on the very first `inTenantTransaction(...)` assertion at `TenantRlsPoolIntegrationTest.java:51` / `:105`, before the timeout path and before cross-tenant reuse assertions. The wrong extra project row already existed in the database.

### Hypothesis B: V009 activity query or index regression broke timeline pagination and phase-3 messaging
- **Why plausible:** V009 ran in same job and the first visible failure was the new timeline test.
- **Tested by:** checking artifact chronology and code path.
- **Eliminated for 3 of 4 failures:** tenant project count and outbox leasing do not depend on V009.
- **Eliminated for the timeline `400`:** `pageToken=1` is rejected by token decoding before the activity query runs.

### Hypothesis C: shared Phase 7 test pollution broke pooled Phase 3 tenant/outbox proofs
- **Why plausible:** workflow co-runs Phase 7 and Phase 3 classes in one Maven invocation against one database.
- **Confirmed:** leaked project/outbox rows exactly match `IncidentActivityTimelineHttpIntegrationTest` writes, and no cleanup exists.

## Root Cause
- **Primary root cause:** `IncidentActivityTimelineHttpIntegrationTest` is not isolation-safe for the pooled PostgreSQL matrix. It mutates shared `TENANT_A` fixture state and outbox state, uses no cleanup, and now runs before Phase 3 tenant/outbox tests in the same database.
- **Secondary root cause:** `IncidentActivityTimelineHttpIntegrationTest` contains an intrinsic invalid request: first-page activity traversal sends `pageToken=1`.

For the current exact SHA proven by run `30255117698`, the remaining CI failure is narrower:
- **Current root cause:** workflow order still runs an isolated but mutating activity-timeline step before `run-phase-03-postgres-contract.sh`, and both hit the same shared `opsmind` database.
- **Current proof:** pooled step passed; isolated timeline passed; later alternating contract failed on tenant-A project cardinality in `migration-and-rls.txt`.

## Why Now
- `IncidentActivityTimelineHttpIntegrationTest` was added by commit `630d98a` (`feat(incidents): add unified activity timeline`).
- That commit also added the class to the pooled PostgreSQL CI matrix at `.github/workflows/pr-quality.yml:670`.
- The pooled step already had `OPSMIND_PHASE7_DB_INTEGRATION=true`; adding this new mutating class exposed the latent cross-suite shared-database assumption in Phase 3 tests.

## Blast Radius
- CI `postgres-contract` / “PostgreSQL trust contracts” job.
- Any local/dev invocation that runs these same classes against one persistent PostgreSQL database.
- Future tests that create incidents, projects, outbox rows, or investigation rows under `TENANT_A` / `PROJECT_A` without per-test cleanup or a disposable database.

## Recommended Fix

### P0 lowest-risk fix preserving existing Phase 3 isolation proof
1. **Fix the invalid activity-timeline request**
   - File: [IncidentActivityTimelineHttpIntegrationTest.java](D:/worktrees/incident-activity-timeline-phase2/services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentActivityTimelineHttpIntegrationTest.java)
   - Change first page fetch to use `sendActivity(..., 1, null)` or equivalent `GET` with no `pageToken`.
   - Reason: this failure is deterministic and independent of fixture leakage.

2. **Stop running `IncidentActivityTimelineHttpIntegrationTest` inside the pooled Phase 3 tenant/outbox matrix**
   - File: [.github/workflows/pr-quality.yml](D:/worktrees/incident-activity-timeline-phase2/.github/workflows/pr-quality.yml)
   - Remove `IncidentActivityTimelineHttpIntegrationTest` from the `authz-rls-pool-matrix` `-Dtest=` list.
   - Run it in a separate Maven invocation.
   - Reason: lowest blast-radius change. Keeps the existing Phase 3 “pooled tenant isolation and messaging recovery” contract intact, while still running the Phase 7 test in CI.

### P0 revised ordering from run `30255117698`
1. Keep the pooled Phase 3/7 Maven matrix before any mutating isolated activity-timeline step.
2. Keep `Run alternating-tenant and append-only contract` immediately after the pooled matrix.
3. Move `Prove incident activity HTTP contract in isolation` after the alternating contract.

Why this is the minimal correction:
- `run-phase-03-postgres-contract.sh` still targets shared `PGDATABASE=opsmind`.
- `OPSMIND_EPHEMERAL_DB=true` does not isolate that script.
- The isolated activity-timeline step is already passing on the new SHA, but it dirties tenant-A fixture state for any later shared-db contract.
- Reordering one workflow step restores determinism without changing test code or script behavior.

### P1 stronger long-term isolation
- Refactor mutating PostgreSQL integration tests to avoid tenant-wide shared state:
  - either disposable database per suite,
  - or explicit cleanup helpers for incidents/outbox/investigation rows,
  - or unique tenant fixtures instead of shared `TENANT_A` / `PROJECT_A`.
- This is higher effort and risk because append-only/guarded tables constrain cleanup.

## Files To Modify For P0
- `.github/workflows/pr-quality.yml`
- `services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentActivityTimelineHttpIntegrationTest.java`

For the current exact SHA follow-up, the minimal change set is only:
- `.github/workflows/pr-quality.yml`

## Regression Verification Plan
1. Re-run the pooled Phase 3 matrix only.
   - Expect:
     - `TenantRlsPoolIntegrationTest` sees exactly 1 project for `TENANT_A`
     - `TransactionalOutboxIntegrationTest` first claim batch size is 1
     - `OutboxDispatcherWorkloadIntegrationTest` first claimed event is `EVENT_A`
2. Run `IncidentActivityTimelineHttpIntegrationTest` in its new dedicated invocation.
   - Expect first-page request `200`, continuation `200`, and union-filter assertions pass.
3. Run `scripts/validation/run-phase-03-postgres-contract.sh` before the isolated activity-timeline step.
   - Expect `migration-and-rls.txt` to end with `Result=PASS`.
4. Keep artifact review on:
   - `artifacts/verification/phase-03/authz-rls-pool-matrix.txt`
   - `artifacts/verification/phase-03/migration-and-rls.txt`
   - `artifacts/verification/phase-04/incident-activity-timeline.txt`
5. Specifically verify:
   - pooled matrix stays green
   - alternating contract runs before isolated activity timeline
   - `migration-and-rls.txt` still shows `DatabaseName=opsmind`, but passes because no prior step polluted that DB
6. Optional hardening:
   - add a CI smoke that runs the pooled Phase 3 matrix twice against the same database to catch future fixture leakage.

## Revised Ordering
1. `Prove pooled tenant isolation and messaging recovery`
2. `Run alternating-tenant and append-only contract`
3. `Prove incident activity HTTP contract in isolation`

## Expected vs Actual
- **Expected:** pooled Phase 3 matrix runs against only seeded base fixtures; tenant A sees one project, outbox tests claim only their own deterministic events, activity timeline first page returns `200`.
- **Actual:** activity-timeline class adds a second tenant-A project and five incident-created outbox rows, then fails itself with invalid `pageToken=1`; later Phase 3 tests observe the leaked rows.

Follow-up exact-SHA actual:
- pooled matrix passed
- isolated activity-timeline step passed
- later alternating contract still failed because it uses the same shared `opsmind` database after that isolated step

## Unresolved Questions
- Team choice only: keep the low-risk CI split, or invest immediately in stronger per-suite database isolation.

Status: DONE
Summary: Original pooled failure was shared-db pollution plus an invalid activity page token. Follow-up run `30255117698` proves the remaining exact-SHA break is workflow order: `run-phase-03-postgres-contract.sh` still uses shared `opsmind`, so the isolated activity-timeline step must run after the alternating contract.
Concerns/Blockers: None for diagnosis. Minimal implementation is a workflow reorder in `.github/workflows/pr-quality.yml`.
