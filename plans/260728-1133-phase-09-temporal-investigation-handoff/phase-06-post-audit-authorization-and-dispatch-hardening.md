---
title: Post-audit authorization and dispatch hardening
status: completed
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
reliability defects after the initial V010 implementation. V011 adds a
forward-only safety fence, but it still leaves inherited dispatcher direct DML
on workflow bindings, inbox rows, and outbox rows. It cannot qualify Temporal
admission. V010/V011 are immutable migrations; V012 is the required forward
remediation and both fresh and upgrade paths still need real-role proof.

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
3. Keep the narrow V011 SECURITY DEFINER dispatcher preflight: it may return a
   small decision code but may not grant the dispatcher broad reads of
   identity/membership tables. It verifies current binding authorization,
   active eligible dispatcher identity, lease token/liveness, pending status and
   database-clock deadline plus RPC safety margin immediately before RPC.
4. Expired, revoked, or ineligible events detected before any Temporal call are
   terminally rejected/poisoned with tenant-scoped, lease-fenced evidence. This
   is never a rule for a potentially accepted post-RPC outcome.
5. Treat ambiguous Temporal failures (`UNKNOWN`, `INTERNAL`, `CANCELLED`, and
   status-less wrappers) as retryable while bounded local budget remains, so
   deterministic `AlreadyStarted` reconciliation remains reachable. If an RPC
   may have been accepted, local attempt/age/deadline exhaustion must retain
   `PENDING` and require exact-workflow reconciliation; it is not remote
   rejection evidence. Explicit failures known before an RPC remain terminal.
6. Use PostgreSQL `clock_timestamp()` for workflow-binding acknowledgement and
   evidence-backed terminal timestamps so application clock skew cannot violate
   binding constraints after a remote start.
7. Claim/process at most one Phase 9 workflow-start lease at a time. Generic
   outbox batching is unchanged.
8. V012 must remove real dispatcher-role direct DML on the Phase 9 binding,
   inbox, and outbox paths in favor of narrow capability functions. A separately
   authorized read-only lane must Describe the exact workflow and inspect its
   first history input without Start authority; its unavailable/inconclusive path
   must keep `PENDING` and emit bounded alerts.

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

**Acceptance:** the V011 pre-RPC DB gate blocks revoked/expired/ineligible
events before RPC, timestamps use DB time, and one lease is processed per claim.
 V012 additionally has exact-main real-role capability-containment proof; V013
 supplies the separately authorized no-Start reconciliation lane. Live provider
 authorization remains a Phase 7/B-017 deployment proof, not a Phase 6 source gap.

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

- V011 is additive/forward-only but is not a Temporal-admission boundary;
  rollback disables Temporal mode and leaves completed evidence immutable. Do
  not edit V010/V011; apply V012 as the forward fix.
- The dispatcher authorization callable exposes only decision codes and never
  raw identity/membership data. The database owner and grants must be checked
  by the final reviewer. Existing direct DML must be removed/contained before
  enablement, not accepted because a callable also exists.
- A retryable post-RPC result may already be accepted remotely. Local budget
  exhaustion must alert and preserve `PENDING`; terminalizing it without exact
  read-only reconciliation would create an unsafe false-negative record.
- No live Temporal namespace/worker is created by this phase. B-013 and the
  master Phase 9 exit remain blocked by their named external evidence.

## Verification Matrix

| Requirement | Proof |
|---|---|
| In-transaction authorization | revocation integration test plus no persisted rows |
| Admission eligibility | missing/inactive dispatcher account test returns 503 |
| Pre-RPC safety | dispatcher unit + PostgreSQL test assert zero client calls |
| Ambiguous response | client matrix plus exact-workflow `PENDING`/reconciliation test; no terminal state from local exhaustion |
| DB clock | reverse-skew acknowledgement/rejection integration tests |
| Batch safety | controlled multi-item dispatch test with only one claimed item |
| Migration | V001–V012 fresh migration and V001–V011 upgrade once V012 lands; real-role containment and cutover/inventory proof |
| No secret/history leak | Phase 9 validator, secret scanner and history-leak tests |
| Runtime/upgrade execution | Exact-main run `30699950577` passed Platform API Maven verify, V006-to-V013 upgrade/recovery, real-role, and 32 focused Phase 9 PostgreSQL tests on `8092b38` |

## Unresolved Questions

- Production Temporal namespace/mTLS/worker topology remains external and is
  intentionally not solved by this hardening phase.
- B-017 remains unresolved for live Temporal authorization/retention,
  production performance/DR, bounded-label scrape, external paging receipt,
  compatible worker proof, and final production-readiness review.
