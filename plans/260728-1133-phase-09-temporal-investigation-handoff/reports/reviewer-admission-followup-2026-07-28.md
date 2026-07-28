## Code Review Summary

### Scope

- Files: 11 pending Java files on `fix/phase9-admission-authorization`
- Delta: 286 tracked additions, 125 tracked deletions, 304 new-file nonblank lines
- Focus: Phase 6 workstream A admission authorization, dispatcher eligibility, transaction/lock ordering, bean activation, compatibility, and concurrency proof
- Scout findings: direct app-role row lock, stale SQL mocks, scheduler-dependent lock test, removal of the pre-authorized-evidence overload, and cross-workstream caller impact

### Overall Assessment

**BLOCK.** Fresh operator authorization is now called from the durable handoff
transaction, and the unsafe overload accepting caller-supplied
`AuthorizedIncidentAnalysisEvidence` has been removed. However, the dispatcher
eligibility lock cannot execute under the production `opsmind_app` grants.
Every new Temporal admission reaches a PostgreSQL permission error and is
returned as the generic retryable 503.

The new concurrency test can also pass without proving that PostgreSQL actually
blocked the revocation statement. Maven/Docker were not run because the task
explicitly prohibited them under the D: free-space gate.

### Critical Issues

#### 1. `opsmind_app` cannot execute the new locking dispatcher lookup

- Location:
  - `services/platform-api/src/main/java/ai/opsmind/platform/investigation/application/InvestigationWorkflowAdmissionPreflight.java:71-83`
  - `services/platform-api/src/main/resources/db/migration/V001__identity_tenant_foundation.sql:405-406`
  - `services/platform-api/src/main/resources/db/migration/V001__identity_tenant_foundation.sql:417-418`
  - `services/platform-api/src/main/resources/db/migration/V003__incident_control_plane.sql:154-159`
- Problem: admission executes `SELECT id FROM service_accounts ... FOR SHARE`
  through the application `JdbcTemplate`. PostgreSQL row-locking selects require
  update privilege. `opsmind_app` receives `SELECT` on `service_accounts`, while
  insert/update/delete are explicitly revoked. V003 already documents this
  exact privilege requirement and uses a narrow SECURITY DEFINER resolver role
  with `UPDATE (status)` solely to lock authorization rows.
- Trigger: any new Temporal start with the normal `opsmind_app` datasource.
- Impact: PostgreSQL denies the `FOR SHARE`; line 88 catches the
  `DataAccessException`; API returns
  `investigation.workflow-dispatcher-unavailable` even when a valid dispatcher
  account exists. Temporal admission is completely unavailable.
- Required fix: put the lock/read behind a narrow SECURITY DEFINER function
  owned by a non-login resolver that has only the column authority required for
  the row lock, return only a bounded decision/identifier, and grant the app
  role EXECUTE. Coordinate this with workstream B/V011. Do not grant general
  service-account update authority to `opsmind_app`, and do not fall back to an
  unlocked `SELECT EXISTS`, which would restore the revocation race.
- Adversarial verdict: **Accept — merge blocker.**

### High Priority

#### 2. The lock-concurrency test can pass without a database lock

- Location:
  `services/platform-api/src/test/java/ai/opsmind/platform/investigation/application/InvestigationWorkflowHandoffPersistenceIntegrationTest.java:320-355`,
  especially `:332-343`
- Problem: the worker counts down `updateStarted` immediately before calling
  `admin.update`, then the test treats a 250 ms `Future.get` timeout as proof
  that `FOR SHARE` blocked the update. The worker can be descheduled after
  `countDown()` or spend the interval acquiring a connection. The timeout then
  passes even if the SQL has no row lock at all. Slow CI therefore creates a
  security-control phantom test.
- Impact: a regression that removes or weakens the dispatcher-account lock can
  still pass. The test is also timing-sensitive across runners.
- Required fix: observe database state, not thread timing. Use a separate
  transaction with bounded `lock_timeout` and assert PostgreSQL SQLSTATE
  `55P03`, or poll `pg_stat_activity`/`pg_locks` until the update is confirmed
  waiting on the target row before releasing the admission transaction. Keep a
  generous overall test deadline only as a hang guard.
- Adversarial verdict: **Accept — authorization-race proof is unreliable.**

### Medium Priority

#### 3. Workstream A changes files assigned to workstream B/unlisted ownership

- Location:
  - deleted:
    `services/platform-api/src/main/java/ai/opsmind/platform/investigation/workflow/InvestigationWorkflowHandoffRepository.java`
  - modified:
    `services/platform-api/src/test/java/ai/opsmind/platform/investigation/workflow/InvestigationWorkflowDispatcherPersistenceIntegrationTest.java:21`,
    `:63`, `:316-337`
  - plan:
    `plans/260728-1133-phase-09-temporal-investigation-handoff/phase-06-post-audit-authorization-and-dispatch-hardening.md`
- Problem: Phase 6 assigns the dispatcher persistence test to workstream B and
  does not list the workflow-package interface under workstream A. Removing the
  unsafe overload requires all callers to migrate, so the cross-stream change
  is understandable, but integration order now has an ownership/conflict point.
- Impact: a mechanical A/C/B merge can overwrite or conflict with B's dispatcher
  test changes. The deleted public Java type is also a source-breaking change,
  though it is an unlanded Phase 9 internal contract and all current in-repo
  callers have been migrated.
- Required fix: integration lead explicitly reconcile these two files after B,
  or reassign their ownership before merging. Do not restore the legacy
  pre-authorized-evidence overload merely to avoid the conflict.

### Low Priority

#### 4. Admission beans activate more broadly than the feature configuration

- Location:
  - `InvestigationWorkflowAdmissionPreflight.java:19-24`
  - `JdbcInvestigationWorkflowHandoffRepository.java:28-34`
  - parent execution configuration:
    `InvestigationExecutionConfiguration.java:13-20`
- Problem: the new preflight and durable repository require persistence,
  PostgreSQL store, and Temporal mode, but not
  `opsmind.investigation.enabled=true`. The starter itself is correctly guarded
  by the parent configuration.
- Impact: setting Temporal/store properties while the investigation feature is
  disabled still constructs the durable admission persistence surface. No
  externally reachable operation was found, so this is lifecycle/configuration
  drift rather than an authorization bypass.
- Recommended fix: align the component conditions with the parent feature flag,
  or document and test why these beans intentionally exist while the feature is
  disabled.

### Edge Cases Found by Scout

- Fresh authorization executes inside the outer handoff `TransactionTemplate`.
  `IncidentAnalysisAuthorizer` uses default REQUIRED propagation, and
  `opsmind_resolve_incident_access` locks the active user, organization,
  organization membership, project, and project membership rows with
  `FOR SHARE`.
- Lock order in this change is authorization tuple, then dispatcher service
  account, then cutover/run/binding/outbox work. No inverse order was found in
  the reviewed application path. The dispatcher-account lock itself is
  currently unusable because of finding 1.
- `loadExisting` now re-authorizes the principal before returning an idempotent
  retry, so a revoked actor cannot use the previously captured incident
  evidence to read the run.
- The old public handoff interface and its
  `createOrLoad(Start, AuthorizedIncidentAnalysisEvidence)` bypass have been
  deleted. `rg` found no remaining in-repo references to that interface.
- Dispatcher eligibility remains one indexed bounded lookup; no N+1 or
  unbounded query loop was introduced.
- Eligibility failure exposes a bounded code/message. No service-account row,
  credential reference, stack trace, or identity detail is returned by this
  code path.

### Positive Observations

- Removing the legacy overload materially closes the caller-supplied
  authorization-evidence bypass.
- Fresh authorization and durable writes share the outer transaction; failure
  rolls back the new run, event, binding, and outbox rows.
- The Temporal starter remains fail-closed when the starter bean or worker
  readiness admission is unavailable.

### Recommended Actions

1. Replace the direct app-role `FOR SHARE` with a narrow executable database
   resolver in V011 and preserve the row lock through durable commit.
2. Replace the 250 ms scheduling assertion with a database-observed lock proof.
3. Reconcile the two cross-owned workflow files when merging workstream B.
4. Update the focused preflight tests if V011 changes the query to a resolver
   function; the current UUID/empty-result mocks match the current Java query.
5. After the D: gate clears, run the focused unit class and the Phase 9
   PostgreSQL admission selector under the real `opsmind_app` role; then run the
   broader Phase 9 gates.

### Metrics

- Type coverage: not measured
- Test coverage: not measured
- Tests: not run by instruction; static inspection proves the focused SQL mocks
  do not match production
- Linting issues: not measured
- `git diff --check`: pass, 0 whitespace errors
- N+1/query efficiency: no new unbounded query path found

### Plan Follow-up

- Requirement 1, in-transaction operator reauthorization: implemented in
  source; PostgreSQL revocation proof remains incomplete.
- Requirement 2, eligible dispatcher admission: logic present but not executable
  with production grants; **not complete**.
- Workstream A acceptance: **not met** until findings 1-2 are fixed and the
  PostgreSQL gate proves them under `opsmind_app`.
- No plan/task state was modified by this review.

### Unresolved Questions

- Will workstream B's V011 own the narrow admission-lock resolver as well as the
  pre-RPC dispatcher fence? Without that coordination, workstream A cannot both
  lock `service_accounts` and preserve the app role's read-only trust boundary.

## Fix Verification — Coordinated Workstreams A and B

### Verification Scope

- Workstream A:
  `D:\worktrees\OpsMind_AI-phase9-admission-authorization`
- Workstream B:
  `D:\worktrees\OpsMind_AI-phase9-dispatch-safety-fence`
- Verification: static only; no Maven, PostgreSQL, or Docker execution under the
  D: free-space gate

### Verdict

**BLOCK remains.** The SECURITY DEFINER boundary, ownership, RLS policies,
runtime grants, Java call, and database-observed concurrency probe are shaped
correctly. V011 nevertheless omits `service_accounts.id` from the definer
owner's column-level SELECT grant, while the function selects and returns that
column. The replacement function will still fail under
`opsmind_context_resolver`.

### Critical Fix Finding

#### V011 definer cannot read the account identifier it returns

- Location:
  - grant:
    `services/platform-api/src/main/resources/db/migration/V011__investigation_workflow_dispatch_safety_fence.sql:9-10`
  - use:
    `services/platform-api/src/main/resources/db/migration/V011__investigation_workflow_dispatch_safety_fence.sql:61-76`
- Evidence:
  - V011 grants the context resolver SELECT only on
    `organization_id`, `status`, `allowed_audiences`, `allowed_scopes`, and
    `database_principal`.
  - The definer executes `SELECT account_row.id INTO
    eligible_account_id` and returns that UUID.
  - No earlier migration grants `opsmind_context_resolver` SELECT on
    `service_accounts`; repository-wide grant search finds only V011 for this
    role/table pair.
- Impact: `public.opsmind_lock_eligible_investigation_dispatcher(?)` fails with
  permission denied when an otherwise valid admission invokes it. Java maps the
  `DataAccessException` to
  `investigation.workflow-dispatcher-unavailable`, so new Temporal starts remain
  unavailable.
- Required fix: add `id` to the V011 column-level SELECT grant for
  `opsmind_context_resolver`. Do not change the app role's table privileges.

### Resolved Findings

#### Direct app-role row lock: design resolved after the missing column grant

- A now calls only
  `SELECT public.opsmind_lock_eligible_investigation_dispatcher(?)`
  (`InvestigationWorkflowAdmissionPreflight.java:67-72`).
- V011 makes the function SECURITY DEFINER and VOLATILE, pins the search path,
  assigns ownership to `opsmind_context_resolver`, revokes PUBLIC, and grants
  only EXECUTE to `opsmind_app`
  (`V011__investigation_workflow_dispatch_safety_fence.sql:42-80`,
  `:637-651`).
- The fixed function checks `session_user = 'opsmind_app'`, accepts only one
  organization UUID, and exposes only an eligible account UUID or NULL. It
  neither returns credential metadata nor permits arbitrary SQL.

#### RLS and row-lock privileges: viable after the missing column grant

- `opsmind_context_resolver` is preflight-validated as non-login,
  non-superuser, non-bypass, and non-inheriting in V001.
- V011 grants only `UPDATE (status)` on `service_accounts`, the minimum
  PostgreSQL privilege needed by `FOR SHARE`; `opsmind_app` receives no UPDATE
  authority.
- V003 already gives the resolver `UPDATE (status)` on `organizations`, so
  `FOR SHARE OF organization_row` is authorized.
- V001's organization policy explicitly admits the context resolver; V011 adds
  a resolver-only permissive SELECT policy for `service_accounts`. Forced RLS
  therefore remains active without hiding the selected rows.
- No role membership grant or runtime `SET ROLE` path to
  `opsmind_context_resolver` was found. Expanding the non-login function owner's
  column privilege does not broaden direct runtime mutation authority.

#### Transaction lock lifetime: resolved

- `JdbcInvestigationWorkflowHandoffRepository.createOrLoad` opens the outer
  `TransactionTemplate` before calling the admission preflight
  (`JdbcInvestigationWorkflowHandoffRepository.java:96-106`).
- `IncidentAnalysisAuthorizer` uses default REQUIRED propagation, so its
  authorization locks, the definer function's organization/account row locks,
  and the durable run/binding/outbox writes use the same transaction-bound JDBC
  connection.
- PostgreSQL row locks acquired inside a SECURITY DEFINER function belong to the
  calling transaction, not to the function scope. They remain held until the
  outer handoff transaction commits or rolls back.

#### Timing-only concurrency test: resolved

- The integration test now uses a separate JDBC transaction, applies
  `SET LOCAL lock_timeout = '250ms'`, attempts the dispatcher status update, and
  asserts SQLSTATE `55P03`
  (`InvestigationWorkflowHandoffPersistenceIntegrationTest.java:332-364`).
- The five-second `Future.get` is now only a hang guard. A delayed worker cannot
  manufacture success: it must execute the conflicting UPDATE and return the
  PostgreSQL lock-timeout code.

#### Java/unit contract consistency: resolved

- Production and unit tests use the exact same qualified function call and UUID
  result contract
  (`InvestigationWorkflowAdmissionPreflight.java:67-72`;
  `InvestigationWorkflowAdmissionPreflightTest.java:45-65`, `:82-93`,
  `:112-124`, `:149-150`).
- The no-account path correctly models a single SQL row containing NULL, which
  `JdbcTemplate.queryForObject` maps to a null UUID; Java converts it to the
  bounded retryable 503.
- Existing handoff reads reauthorize the operator but intentionally do not
  require current dispatcher availability; the unit test verifies no dispatcher
  query on that path.

### Remaining Non-blocking Items

- Workstream A still deletes the old workflow-package interface and edits the
  dispatcher persistence test assigned to workstream B. Integration must
  reconcile those files; restoring the unsafe pre-authorized-evidence overload
  is not acceptable.
- Admission component activation remains broader than the parent
  `opsmind.investigation.enabled` condition, as recorded in the initial review.
- The lightweight Phase 9 validator currently exits 1 because it expects
  `suspendedAccountConvertsRetrySettlementIntoTerminalRejection`, while the B
  test contains `suspendedAccountPreservesAmbiguousRetryForReconciliation`
  (`validate-phase-09-workflow-handoff.mjs:236`;
  `InvestigationWorkflowDispatcherPersistenceIntegrationTest.java:454`).
  This is outside the admission-lock repair, but the merged static gate must be
  reconciled before landing.

### Fix Verification Actions

1. Add `id` to the resolver's V011 `service_accounts` SELECT column grant.
2. Add a PostgreSQL assertion that `opsmind_app` can execute the admission
   function, cannot directly UPDATE `service_accounts`, and a non-app session
   receives SQLSTATE `42501`.
3. Reconcile the validator's suspended-account retry expectation with the
   implemented dispatcher semantics.
4. After the storage gate clears, run the merged A+B fresh and upgrade migration
   tests plus the admission lock integration test under the real roles.

### Fix Verification Unresolved Questions

None. The remaining blocker is a concrete missing column privilege, not a design
decision.

## Final Lightweight Verification

The coordinated admission fixes now pass static review.

- V011 grants `opsmind_context_resolver` SELECT on the exact
  `service_accounts` columns used by the function, including `id`
  (`V011__investigation_workflow_dispatch_safety_fence.sql:9-12`).
- `UPDATE (status)` remains confined to the non-login resolver; `opsmind_app`
  receives only EXECUTE on the fixed SECURITY DEFINER function. PUBLIC remains
  revoked. No runtime table privilege broadening was introduced.
- The function owner, RLS policies, pinned search path, session-user check, and
  organization/account `FOR SHARE` locks remain internally consistent.
- A's Java query and unit-test SQL match the final function contract. The
  separate-connection lock-timeout probe still asserts SQLSTATE `55P03`.
- The legacy pre-authorized-evidence overload remains absent.
- `node scripts/validation/validate-phase-09-workflow-handoff.mjs`: PASS,
  `Errors=0`.
- `git diff --check`: PASS in both A and B worktrees.

No static admission blocker remains. Maven/PostgreSQL/Docker execution was not
performed under the explicit D: storage restriction; exact-role fresh/upgrade
runtime evidence remains required before landing.
