# Dispatch V011 Production-Readiness Review

## Code Review Summary

### Scope

- Baseline: af45504 versus the uncommitted Phase 6/B worktree.
- Changed tracked files: 10 files, +309/-58 LOC. Untracked additions reviewed: V011 (169 LOC) and InvestigationWorkflowDispatchPreflightDecision (36 LOC).
- Focus: V011 SQL execution privilege/RLS, dispatcher transaction boundaries, service-account revocation, Temporal RPC fencing, migration/validation evidence, and documented runtime configuration.
- Scout findings: V002 tenant context is an active-account capability gate; V010 scheduling also excludes inactive accounts; binding identity fields are immutable; dispatcher has tenant-wide direct table grants once a tenant GUC is set. No new N+1 query was found: workflow claims are constrained to one item.
- Evidence run: git diff --check af45504 passed; node scripts/validation/validate-phase-09-workflow-handoff.mjs reported Errors=0 and WorkflowHandoffResult=PASS. Maven/PostgreSQL tests were not run because the assigned worktree is under the storage-capacity guard.

### Overall Assessment

Block merge. The static validator passes because it checks marker strings, not PostgreSQL execution. V011 cannot execute its preflight on a normal dispatcher role due to a missing owner privilege. After that is fixed, the ineligible-service-account branch still cannot run: every preflight and terminal settlement starts by requiring the service account to be active. This leaves pending starts leased or permanently unscheduled rather than tenant-scoped terminal poison.

The requested event-specific SECURITY DEFINER settlement design is necessary, but it does not by itself close the authorization-change-to-Temporal-RPC race. That needs an explicit product/security decision and a durable target-side fence if revocation must prevent work after the preflight snapshot.

### Critical Issues

#### [Critical] V011 grants its function owner no SELECT privilege on aggregate_type, so every preflight errors

V011 makes opsmind_context_resolver the SECURITY DEFINER function owner at services/platform-api/src/main/resources/db/migration/V011__investigation_workflow_dispatch_safety_fence.sql:155-157. Its only new outbox column grant at :9-12 omits aggregate_type, but the function evaluates event_row.aggregate_type at :79. The baseline grants no SELECT on outbox_events to this role (V001 only grants identity tables at V001__identity_tenant_foundation.sql:400-401); a repository-wide grant search found no prior resolver grant.

PostgreSQL checks the function owner's column privileges, so this query fails with a permission error instead of returning any bounded decision code. The error escapes the dispatcher preflight at InvestigationWorkflowDispatchTransactions.java:86-97, then the runner only logs its exception class at InvestigationWorkflowStarterRunner.java:55-63. Every claimed workflow event is left pending until lease expiry and will repeat.

Fix V011 before it lands: grant the owner SELECT on aggregate_type as well as every other referenced outbox column. Add a real PostgreSQL role test which connects as opsmind_dispatcher, binds a tenant, calls the function for an eligible canonical event, and proves it returns workflow.preflight-allowed. Test the inverse separately: the dispatcher must still have no direct identity/membership reads and PUBLIC must not execute the function.

#### [Critical] The service-account-ineligible terminal path is unreachable and leaves events unserviceable

Every dispatcher operation is wrapped in InvestigationWorkflowDispatchTransactions.inTenant at :226-230. That invokes OutboxDispatcherTenantContextSql.apply, which calls opsmind_set_dispatcher_tenant_context at OutboxDispatcherTenantContextSql.java:23-32. V002 refuses this call unless the organization and matching dispatcher service account are active at V002__outbox_dispatcher_workload.sql:141-156.

Consequences after a claim followed by account suspension:

1. preflight at InvestigationWorkflowDispatchTransactions.java:86 fails in tenant binding before V011 can return workflow.dispatcher-ineligible (:101-114).
2. Even if preflight were made callable, the terminal branch calls rejectIfLive at InvestigationWorkflowStartDispatcher.java:61-63. That independently calls leaseIsLive (:131-134), then reject; both again start a fresh inTenant transaction and fail for the same reason.
3. Events that were never claimed cannot recover later: V010's tenant selector only returns tenants with an active dispatcher account at V010__investigation_workflow_start_handoff.sql:411-416.

The integration test claims the contrary. InvestigationWorkflowDispatcherPersistenceIntegrationTest.java:358-372 suspends the only dispatcher account, then expects preflight to return DISPATCHER_INELIGIBLE. It will throw from V002 tenant binding before calling V011. This violates Phase 6 requirement 4 and its B acceptance criterion: ineligible starts are neither terminally rejected nor poisoned, and no active scheduler path remains to process them.

Do not weaken opsmind_set_dispatcher_tenant_context to accept an inactive account or an exact lease. That function sets only opsmind.tenant_id (V002:158-160); with that GUC, the dispatcher already has direct select/update capability across the tenant's outbox, inbox, and bindings (V002:184-190 and V010:465-474). It would convert possession of one lease into a tenant-wide capability.

Use an event-specific SECURITY DEFINER settlement surface instead:

- Preferred owner: a new bootstrap-provisioned, NOLOGIN/NOINHERIT/NOBYPASSRLS role such as opsmind_workflow_dispatch_settler. Give it only the columns and RLS policies needed by these functions. Do not add update grants to opsmind_dispatcher.
- Every callable requires session_user = 'opsmind_dispatcher', static search_path, canonical organization/event/binding identity, and an exact lease token where a live lease is required. Return only bounded codes; never return identity rows.
- A live-lease terminal function atomically rechecks the terminal condition and updates binding, inbox, and outbox with database timestamps in one transaction. Remove the separate leaseIsLive then reject sequence.
- A started-settlement function accepts only the exact live lease and atomically records the already-completed Temporal start. It must remain callable after the account was suspended; otherwise a remote success becomes an orphaned PENDING binding.
- Add a distinct bounded terminalizer for PENDING canonical events with no eligible dispatcher account and no live lease. It must lock/select candidates narrowly and atomically poison them. This handles the unclaimed-or-expired case without allowing an old lease holder to steal a current owner.
- Keep generic claim and normal tenant context strict: they should require the active service account.

If V011 has already been applied, put this in a forward V012 migration; otherwise correct V011 and bootstrap role provisioning together.

#### [Critical] Preflight is only a committed snapshot; authorization can be revoked before Temporal starts

preflight commits inside its own TransactionTemplate (InvestigationWorkflowDispatchTransactions.java:86 and :226-230). The dispatcher explicitly ensures no database transaction remains at InvestigationWorkflowStartDispatcher.java:67-71 before calling Temporal. There is no persisted permit, lock, version check, or target-side revalidation between the authorization reads in V011:116-150 and the RPC.

A membership, user, incident-version, organization, or service-account change can commit after preflight returns ALLOW and before workflowClient.start. The code then starts a workflow using stale authority. This is a trust-boundary race, not a test timing nit.

Do not hold the SQL transaction open across Temporal merely to hide the race; the code correctly forbids external RPC in a database transaction. Decide the required invariant:

- If Phase 6 means best-effort pre-RPC validation, document the remaining race and add a concurrency test proving its bounded behavior.
- If revocation must prevent any privileged workflow work after the change, persist an authorization/start-fence version and have the Temporal worker revalidate it against the database before executing sensitive work. Revocation must invalidate that fence.
- If the business requirement is literally no Temporal execution may be created after revocation, a database read and remote RPC cannot be made atomic without a cross-system coordination/locking design. That is a product/security decision, not a Java catch block.

### High Priority

#### [High] A barely live lease can still enter a full RPC and lose its acknowledgement

V011 considers a lease valid whenever lease_expires_at > db_now at V011:81-82. It does not require the remaining database-clock time to cover the RPC timeout plus safety margin. InvestigationWorkflowStarterProperties.validateRpcEnvelope only proves that the full RPC envelope fits in the original configured lease at InvestigationWorkflowStarterProperties.java:48-55; it says nothing about a worker paused after claim.

Example: a 30-second lease is claimed, paused for 29 seconds, then preflight passes with one millisecond remaining. The Temporal RPC may run for five seconds, be accepted, and fail the exact live-lease acknowledgement. The deterministic workflow ID may eventually reconcile it, but the binding/outbox is temporarily orphaned and the safety fence did not protect the remote call.

Pass a bounded required RPC window to the database and require lease_expires_at > clock_timestamp() + required_window, or atomically reserve/extend the exact lease before the RPC. Add an integration boundary case with remaining lease below that window and assert no Temporal invocation, no binding transition, and no inbox/outbox mutation.

#### [High] The checked-in example configuration makes the enforced one-lease invariant fail at runtime

application.yaml defaults OPSMIND_INVESTIGATION_WORKFLOW_STARTER_BATCH_SIZE to 1 at :95 and InvestigationWorkflowStarterProperties.validate rejects every value other than 1 at :43-45. .env.example still exports the same variable as 10 at :157.

An operator following the example and enabling the starter will hit validation on every scheduled run instead of processing work. This is a backwards-configuration break that static validation misses because validate-phase-09-workflow-handoff.mjs:98-104 checks only the presence of unrelated example variables.

Change the example to 1 and add a configuration regression test or static assertion that the documented value agrees with the enforced policy.

#### [High] The new PostgreSQL tests are false evidence for two required fences

The deadline test mutates an immutable binding field:

- InvestigationWorkflowDispatcherPersistenceIntegrationTest.java:319-324 updates deadline_at using the admin connection.
- V010's write trigger rejects updates from any session_user other than opsmind_dispatcher at V010:207-210 and independently rejects a changed deadline_at at :225-229.

That test cannot reach dispatch under the stated schema contract. Create the handoff with a near database deadline instead, or insert the fixture through its legitimate creation path, then assert zero client calls and the full binding/inbox/outbox terminal state.

The suspended-account test at InvestigationWorkflowDispatcherPersistenceIntegrationTest.java:358-372 is also invalid for the reason described in the critical terminal-path finding. Replace it after the settlement API exists with:

1. active account -> claim -> suspend -> dispatch; assert no RPC and atomic REJECTED/poisoned state;
2. suspend before any claim; run the new bounded terminalizer and assert the same terminal state;
3. suspend after remote success but before acknowledgement; assert exact-lease started settlement succeeds or deterministic reconciliation is deliberately selected;
4. wrong token and expired token cannot settle any state.

The upgrade script only checks that the function exists at scripts/validation/run-phase-04b-migration-upgrade.sh:530-541. The static validator only searches marker strings at validate-phase-09-workflow-handoff.mjs:49-61 and :181-195. Neither exercises function ownership, column grants, RLS, caller identity, or a returned decision. Add fresh and V001-V009 upgrade execution tests with the actual dispatcher login.

### Medium Priority

#### [Medium] Reusing the generic context resolver expands a broad privileged surface

V011 adds cross-tenant SELECT policies using true for incidents, service_accounts, outbox_events, and investigation_workflow_bindings at V011:18-33, then makes the generic opsmind_context_resolver the function owner at :155-157. The role is correctly a NOLOGIN/NOINHERIT/NOBYPASSRLS role (V001:19-31), and the function returns bounded codes, so this is not a direct dispatcher data leak today. It is still a new broad capability on a generic owner shared by context-resolution functions.

Use a purpose-specific owner for the mutating settlement API. Its grants, policies, and owned functions should be limited to the workflow-start state machine. This reduces the blast radius of future SECURITY DEFINER functions and keeps the Phase 6 promise of narrow dispatcher access reviewable.

#### [Medium] Acknowledgement clock test does not prove the acknowledged binding timestamp is database-owned

InvestigationWorkflowDispatchTransactions uses clock_timestamp() for temporal_started_at at :131-143, which is the correct implementation direction. But databaseClockRatherThanCallerClockFencesAcknowledgement at InvestigationWorkflowDispatcherPersistenceIntegrationTest.java:171-197 asserts only status and published_at. It passes an application timestamp years ahead without checking temporal_started_at or updated_at, so reverting the binding assignment to the caller time would still pass.

Assert the binding timestamps are derived from the database clock and not equal to/after the skewed caller value. Keep the existing rejection timestamp assertion as a separate proof.

### Edge Cases Found by Scout

- Account suspension after claim: current inTenant guard prevents preflight, liveness check, retry release, reject, and acknowledgement.
- Account suspension before claim: V010's ready-tenant selector omits the tenant forever; a lease-scoped fallback cannot discover it.
- Lease expires between leaseIsLive and reject: those are separate transactions at InvestigationWorkflowStartDispatcher.java:131-134, so a terminal path can throw after its preliminary liveness check. Fold liveness and settlement into one atomic function.
- Lease expires after a successful preflight but during the RPC: current logic only checks existence, not remaining RPC window.
- Actor/membership revocation after preflight: the remote call observes an authorization snapshot that may already be stale.
- No N+1 database loop was introduced by this diff; batchSize == 1 is correctly enforced in code, subject to the stale example configuration.

### Positive Observations

- The function limits input shape and safety margin at V011:48-64, fixes search_path at :44, uses database clock, revokes PUBLIC execution, and returns bounded decision codes. Those controls are worth preserving in the replacement API.
- Binding acknowledgement and rejection now use clock_timestamp() within their database transaction at InvestigationWorkflowDispatchTransactions.java:131-143 and :185-197.
- The dispatcher keeps the Temporal RPC outside a database transaction, which must remain true.

### Recommended Actions

1. Correct the missing aggregate_type grant and prove V011 execution under the real dispatcher login before any merge.
2. Replace context-bound post-claim settlement with the narrow event-specific SECURITY DEFINER API described above. Do not loosen tenant context.
3. Add an inactive-account/no-live-lease terminalizer so old PENDING starts cannot remain unscheduled forever.
4. Decide and implement the durable authorization fence required across the preflight-to-Temporal gap; record the accepted semantics explicitly.
5. Enforce a database-clock remaining lease window before RPC, not only the original configuration inequality.
6. Repair .env.example and add regression coverage for batchSize == 1.
7. Replace impossible integration fixtures; add real function privilege/RLS/caller tests to fresh and upgrade validation.
8. Do not mark Phase 6/B or the Phase 9 hardening plan complete until PostgreSQL fresh/upgrade and focused dispatcher tests execute successfully.

### Plan Follow-ups

- Requirement 3: partial only. A narrow callable exists syntactically, but C1 prevents execution and C2 makes its ineligible decision unreachable.
- Requirement 4: not met. Ineligible events are not terminalized; unclaimed suspended-account events are never scheduled.
- Requirement 6: implementation changes binding timestamps to database time, but acknowledgement proof is incomplete.
- Requirement 7: implementation constrains claims to one lease, but .env.example configures an invalid value.
- Fresh/upgrade acceptance: not proven. Existing scripts demonstrate function presence and source markers only.

### Metrics

- Type coverage: not measured.
- Test coverage: not measured; focused Maven/PostgreSQL suite not run under the workspace storage guard.
- Linting issues: not measured.
- Static validator: 0 errors, but marker-only and insufficient for these defects.
- Whitespace check: git diff --check af45504 passed.

### Unresolved Questions

1. Does the product require preventing any Temporal execution creation after a revocation, or only preventing privileged workflow work? The correct cross-system fence differs.
2. Which release owns provisioning and validating a new dedicated settlement role: this still-unmerged V011 change, or a forward migration/bootstrap change after V011 is applied?
