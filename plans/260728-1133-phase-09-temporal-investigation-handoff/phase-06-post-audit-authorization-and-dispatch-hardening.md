---
title: Post-audit authorization and dispatch hardening
status: in-progress
priority: P1
created: '2026-07-28T17:30:00+07:00'
dependsOn:
  - phase-02-atomic-workflow-start-persistence
  - phase-03-temporal-client-and-one-owner-starter
  - phase-04-crash-window-replay-and-security-evidence
---

# Phase 6 — Post-audit Authorization and Dispatch Hardening

## Context

The independent Phase 9 audit found four P1 production blockers and two P2
reliability defects after the initial V010 implementation. This phase is a
forward-only repair: V010 remains immutable after upgrade proof; any database
change is introduced by V011 and both fresh and upgrade paths are re-proven.

## Requirements

1. Re-authorize an admitted actor inside the durable handoff transaction before
   writes. The full active user, organization, project, membership, role and
   incident authorization path must be locked/read through the existing
   `IncidentAnalysisAuthorizer`; a revocation between request authorization and
   write must leave no run, binding or outbox event.
2. Reject Temporal admission with a safe retryable 503 when the tenant has no
   active `opsmind_dispatcher` service account with the required audience and
   `outbox:dispatch` scope. It must not return `202` for a permanently
   unschedulable start.
3. Add a narrow, SECURITY DEFINER dispatcher preflight in V011. It may return
   a small decision code but may not grant the dispatcher broad reads of
   identity/membership tables. It verifies current binding authorization,
   active eligible dispatcher identity, lease token/liveness, pending status and
   database-clock deadline plus RPC safety margin immediately before RPC.
4. Expired, revoked or ineligible events are terminally rejected/poisoned
   without any Temporal call. The rejection state must remain tenant scoped and
   lease fenced.
5. Treat ambiguous Temporal failures (`UNKNOWN`, `INTERNAL`, `CANCELLED`, and
   status-less wrappers) as retryable under existing deadline/attempt limits so
   deterministic `AlreadyStarted` reconciliation remains reachable. Explicit
   contract/target/authorization failures remain terminal.
6. Use PostgreSQL `clock_timestamp()` for workflow-binding acknowledgement and
   rejection timestamps so application clock skew cannot violate binding
   constraints after a remote start.
7. Claim/process at most one Phase 9 workflow-start lease at a time. Generic
   outbox batching is unchanged.

## Parallel Workstreams and Ownership

### A — admission authorization and eligibility

**Branch:** `fix/phase9-admission-authorization`
**Files owned:**

- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/application/DurableInvestigationExecutionStarter.java`
- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/application/JdbcInvestigationWorkflowHandoffRepository.java`
- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/application/InvestigationExecutionConfiguration.java`
- `services/platform-api/src/test/java/ai/opsmind/platform/investigation/application/DurableInvestigationExecutionStarterTest.java`
- `services/platform-api/src/test/java/ai/opsmind/platform/investigation/application/InvestigationWorkflowHandoffPersistenceIntegrationTest.java`
- New narrowly scoped application-package collaborators and tests only.

**Acceptance:** live in-transaction reauthorization and tenant dispatcher
eligibility are both enforced before initial write; focused unit and PostgreSQL
revoke/missing-account tests pass.

### B — database/dispatcher safety fence

**Branch:** `fix/phase9-dispatch-safety-fence`
**Files owned:**

- `services/platform-api/src/main/resources/db/migration/V011__investigation_workflow_dispatch_safety_fence.sql`
- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/workflow/InvestigationWorkflowDispatchTransactions.java`
- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/workflow/InvestigationWorkflowStartDispatcher.java`
- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/workflow/InvestigationWorkflowStarterProperties.java`
- `services/platform-api/src/test/java/ai/opsmind/platform/investigation/workflow/InvestigationWorkflowStartDispatcherTest.java`
- `services/platform-api/src/test/java/ai/opsmind/platform/investigation/workflow/InvestigationWorkflowDispatcherPersistenceIntegrationTest.java`
- `services/platform-api/src/test/java/ai/opsmind/platform/investigation/workflow/InvestigationWorkflowStarterPropertiesTest.java`
- `scripts/validation/validate-phase-09-workflow-handoff.mjs`
- `scripts/validation/run-phase-04b-migration-upgrade.sh`

**Acceptance:** pre-RPC DB gate blocks revoked/expired/ineligible events before
RPC, timestamps use DB time, one lease is processed per claim, and V011 passes
fresh/upgrade validation.

### C — Temporal ambiguity classification

**Branch:** `fix/phase9-temporal-ambiguity`
**Files owned:**

- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/workflow/TemporalTransportFailureClassifier.java`
- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/workflow/TemporalInvestigationWorkflowClient.java`
- `services/platform-api/src/test/java/ai/opsmind/platform/investigation/workflow/TemporalInvestigationWorkflowClientTest.java`
- `services/platform-api/src/test/java/ai/opsmind/platform/investigation/workflow/TemporalInvestigationWorkflowHistoryLeakTest.java`

**Acceptance:** explicit permanent cases remain terminal; every ambiguous
transport outcome is bounded-retryable and deterministic duplicate
reconciliation remains exact.

## Integration Order

1. Create a reviewable Phase 9 baseline checkpoint without changing V010.
2. Merge A, C, then B onto the integration branch. B is last because it
   consumes final durable state and owns V011/update scripts.
3. Run the mandatory reviewer against the merged head. Repair findings only in
   the integration branch or a new isolated fix branch.
4. Run focused tests, full Maven test suite, PostgreSQL fresh/upgrade proof,
   static validator, secret scan, cross-platform CI and exact-head PR checks.
5. Update runbooks/architecture/deployment/local docs only after the merged
   behavior is proven, then synchronize all Phase 9 plan statuses.

## Risks and Rollback

- V011 is additive/forward-only; rollback disables Temporal mode and leaves
  completed evidence immutable. Do not edit V010 after upgrade proof.
- The dispatcher authorization callable exposes only decision codes and never
  raw identity/membership data. The database owner and grants must be checked
  by the final reviewer.
- No live Temporal namespace/worker is created by this phase. B-013 and the
  master Phase 9 exit remain blocked by their named external evidence.

## Verification Matrix

| Requirement | Proof |
|---|---|
| In-transaction authorization | revocation integration test plus no persisted rows |
| Admission eligibility | missing/inactive dispatcher account test returns 503 |
| Pre-RPC safety | dispatcher unit + PostgreSQL test assert zero client calls |
| Ambiguous response | client matrix + deterministic retry/reconciliation test |
| DB clock | reverse-skew acknowledgement/rejection integration tests |
| Batch safety | controlled multi-item dispatch test with only one claimed item |
| Migration | V001–V011 fresh migration and V001–V009 upgrade, cutover/inventory proof |
| No secret/history leak | Phase 9 validator, secret scanner and history-leak tests |

## Unresolved Questions

- Production Temporal namespace/mTLS/worker topology remains external and is
  intentionally not solved by this hardening phase.
