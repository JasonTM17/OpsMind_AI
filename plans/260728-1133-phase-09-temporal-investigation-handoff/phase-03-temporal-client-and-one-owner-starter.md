---
phase: 3
title: "Temporal client and one-owner starter"
status: in-progress
effort: "2 days"
---

# Phase 3: Temporal client and one-owner starter

## Overview

Add a core Temporal client adapter and a separately enabled starter loop. The
database lease is the sole delivery owner; Temporal's deterministic workflow ID
is the second idempotency fence.

## Implementation Steps

### Tests first

1. Add unit tests for configuration bounds, deterministic workflow IDs, safe
   exception mapping, event schema/type validation, retry classification, and
   jitter-free bounded test backoff.
2. Use `io.temporal:temporal-testing:1.35.0` to prove:
   - first start returns `STARTED`;
   - the same target/workflow ID/type/input digest reconciles `AlreadyStarted`;
   - a conflicting workflow contract is rejected;
   - no SDK/server error text leaks through API or persisted safe error codes.
3. Add dispatcher integration tests for separate-role datasource identity,
   event-scoped tenant enumeration fairness, inbox/sequence policy, claim
   fencing, expired leases, duplicate delivery, foreign tenant, retry
   exhaustion, terminal rejection, and atomic inbox/binding/outbox ack.

### Production changes

4. Pin `io.temporal:temporal-sdk:1.35.0` and test-scope
   `io.temporal:temporal-testing:1.35.0` in `services/platform-api/pom.xml`.
5. Add manual, property-gated `WorkflowServiceStubs`/`WorkflowClient`
   configuration. Map the persisted logical cluster ID to one configured target;
   validate namespace, task queue, TLS intent, RPC timeout, lease margin,
   maximum attempts/age, and batch limits before any connection attempt.
   Reject a binding whose persisted cluster/namespace differs from configuration.
6. Add `InvestigationWorkflowClient` and
   `TemporalInvestigationWorkflowClient`. Use `WorkflowClient.start` with
   workflow-ID reuse rejected. On `AlreadyStarted`, inspect the existing
   execution's namespace, type, Temporal run ID, and first-start input digest
   before reconciliation; unverifiable/mismatched execution is permanent
   conflict. Treat bounded transport/unavailable failures as retryable.
7. Extend `OutboxLeaseRepository` with an event-type-filtered claim without
   breaking existing callers. The starter must never claim or poison unrelated
   outbox traffic.
8. Add a named, secondary dispatcher `DataSource`, `JdbcTemplate`, and
   `PlatformTransactionManager` authenticated with dispatcher-only
   configuration. Qualify scheduler/claim/inbox/ack beans; startup must prove
   `session_user=opsmind_dispatcher`, while API persistence stays
   `session_user=opsmind_app`. Never grant dispatcher credentials to browser/API
   request code.
9. Add `InvestigationWorkflowStartDispatcher`:
   - claim in a short dispatcher-role transaction;
   - close the transaction before the Temporal RPC;
   - revalidate live lease/binding immediately before RPC; deterministic ID is
     dedupe, not authorization;
   - start/reconcile outside the transaction;
   - claim/process the generic inbox consumer `investigation-workflow-starter-v1`
     with explicit sequence-1/no-gap policy;
   - acknowledge inbox, binding, and outbox together only when lease token
     matches and `lease_expires_at > transaction_timestamp()`;
   - release retryable failures with bounded backoff only below max attempts,
     max age, and investigation deadline; otherwise atomically set binding
     `REJECTED`, poison with a bounded safe code, and alert.
10. Add a conditional scheduled runner using the V010 event-scoped tenant
    function and per-tenant bounded batches. It is disabled by default and
    requires dispatcher, starter, Temporal, and worker-readiness flags.
11. Add a fail-closed admission/readiness probe. Temporal mode cannot accept a
    start until the bound namespace/task queue reports a compatible worker
    poller/build identity. Loss of readiness closes new admission; existing
    durable handoffs remain reconcilable.

## File Inventory

- Modify messaging `OutboxLeaseRepository` and
  `TransactionalOutboxLeaseStore`, `OutboxDispatcherTenantScheduler`,
  `TransactionalInboxRepository`, and dispatcher configuration plus tests.
- Create Temporal/client/dispatcher/configuration classes under
  `investigation/workflow/`.
- Modify `services/platform-api/pom.xml`, `application.yaml`, `.env.example`,
  and configuration tests.

## Runtime Rules

- No connector/model RPC while a database transaction is open.
- A lost lease can never acknowledge or mutate the binding.
- `AlreadyStarted` is success only for expected target/namespace/workflow
  ID/type/input digest. Reject workflow-ID reuse and never use unrestricted
  `USE_EXISTING`.
- The starter does not register a production investigation worker in this
  slice. Runtime admission—not documentation alone—prevents enablement without
  a compatible worker.
- A delayed stale owner may reach Temporal, but cannot create a second workflow
  ID or acknowledge; the future worker must revalidate binding generation and
  current authorization before every external activity.

## Risks and Rollback

- Temporal/Jackson dependency conflict: run dependency convergence and full Java
  tests; keep SDK internals behind the adapter.
- Dispatcher starvation: filter by event type and bound tenants/batches.
- Rollback: disable the starter flag. Existing pending rows/outbox events remain
  durable for a forward fix; do not delete or rewrite them.

## Success Criteria

- [ ] Every valid crash window converges to one logical Temporal start.
- [ ] Unrelated outbox events are never claimed by this dispatcher.
- [ ] External RPC never occurs inside a database transaction.
- [ ] Starter remains disabled by default and fails closed when misconfigured.
- [ ] API/app and dispatcher SQL execute through distinct proven database roles.
- [ ] Retry exhaustion and permanent invalid events become visible `REJECTED`,
  never silent PENDING backlog.
