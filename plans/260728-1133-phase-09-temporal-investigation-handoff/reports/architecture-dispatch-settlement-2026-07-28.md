---
title: Dispatcher lease settlement after tenant-account deactivation
date: 2026-07-28
scope: Phase 9 V011 architecture decision
status: recommended
---

# Dispatcher lease settlement after tenant-account deactivation

## Summary

Recommend **event-specific SECURITY DEFINER preflight plus atomic settlement**.

An active tenant `opsmind_dispatcher` account must authorize a *new claim* and
a *new Temporal RPC*. It must not be required to close an already claimed,
still-live lease. That lease is a bounded, event-specific completion capability;
it can safely record either a no-RPC rejection or an RPC result, but it must
never authorize a new RPC after the database observes account ineligibility.

This is the simplest viable design that preserves forced RLS and least
privilege. It changes neither V010's immutable binding shape nor generic
outbox dispatch.

## Verified current state

- `V002__outbox_dispatcher_workload.sql:116-158` makes every dispatcher
  tenant context depend on an active, scoped tenant service account.
- The workflow dispatcher claims in one tenant transaction, then processes the
  claimed row in later transactions. Every later method currently calls
  `inTenant(...)`, which invokes that V002 setter:
  `InvestigationWorkflowDispatchTransactions.java:60-229`.
- V011 preflight additionally requires `opsmind_current_tenant_id()` to equal
  its input (`V011...sql:62-65`). A service-account deactivation between claim
  and preflight therefore throws before it can return
  `workflow.dispatcher-ineligible`.
- A deactivation after preflight has the same result for `leaseIsLive`,
  `acknowledgeStarted`, `releaseRetry`, and `reject`: the binding/inbox/outbox
  transaction cannot start. A successful Temporal RPC can then be left with a
  `PENDING` binding and unpublished outbox row.
- The existing binding/inbox/outbox write sequence is atomic *when it can enter
  `inTenant`*: it claims/updates the inbox, transitions the binding, then
  publishes or poisons the outbox in one transaction. The defect is admission
  to that transaction, not its rollback behavior.
- V003 already grants the read-only `opsmind_context_resolver` the needed
  `projects`/`project_memberships` access and RLS policies; V011 adds the
  remaining incident/service-account/outbox/binding reads. Removing the V011
  tenant-context dependency does not require granting the dispatcher direct
  identity reads.

## Assumption challenged

The incorrect assumption is that a service account must remain active to
**settle** work it already authorized at claim time. That conflates admission
with convergence. It creates a liveness failure precisely when the safety
fence correctly decides an event is ineligible.

The inverse is also unsafe: an old lease must not become a tenant-wide bypass
or permit a new Temporal call after deactivation. Completion authority must be
limited to one event, one unexpired lease token, and a fixed state transition.

No database design can atomically combine a remote Temporal RPC with PostgreSQL
revocation. A revocation immediately after an allowed preflight can race the
RPC. The required response is deterministic workflow identity, exact lease
settlement, and worker-side reauthorization before external work—not a longer
database transaction around the RPC.

## Options considered

Estimates are implementation-scale estimates, not measured LOC.

| Option | Estimated change | DB round trips after claim | Authority surface | Reliability / second-order effect | Verdict |
|---|---:|---:|---|---|---|
| 1. Lease-scoped tenant context | ~100-160 SQL + ~40-70 Java LOC | Same as current | Any holder of a live lease can set `opsmind.tenant_id` and receive all normal dispatcher RLS access for that tenant transaction | Reuses existing atomic code, but turns a one-event lease into tenant-wide outbox/binding/inbox authority; token leakage or a future raw query widens cross-tenant blast radius | Reject |
| 2. Event-specific SECURITY DEFINER preflight + settlement | ~180-300 SQL + ~80-140 Java LOC | 1 preflight + 1 settlement; terminal rejection removes current `leaseIsLive` TOCTOU round trip | Exact organization, workflow-start event, matching unexpired lease, fixed outcome only | Preserves convergence after deactivation without setting tenant context. Requires explicit resolver grants/RLS policies and focused SQL tests | **Select** |
| 3. New claimed/abandoned workflow state machine | ~300-500 SQL + ~150-250 Java LOC plus API/read-model work | At least one extra transition | Still needs a definer or privileged sweeper after account deactivation | Adds recovery states, backfill/visibility rules, and new races; does not solve the RLS barrier by itself | Reject for Phase 9 |

### Why option 1 is not acceptable

It is the smallest patch, not the smallest safe system. A
`opsmind_set_dispatcher_lease_context(tenant, event, token)` helper could prove
the lease and then set normal tenant context, allowing current Java writes to
work. But normal context gives the dispatcher all of its tenant-scoped table
grants, not just the event it claimed. That is a capability escalation from
one opaque lease to a tenant-wide database context. It also makes every future
dispatcher query part of this revocation exception.

### Why option 3 is premature

A `CLAIMED`/`ABANDONED` state would be useful only if Phase 9 needed explicit
long-lived dispatch recovery, handoff ownership transfer, or administrative
cancellation semantics. The current one-lease, bounded RPC path does not.
Without an event-specific privileged transition, the new state still strands
after the account is inactive; with one, it duplicates option 2 and adds
states all readers and operators must understand.

## Recommended design

### Boundary model

1. **Claim remains unchanged.** `claim(...)` continues to run under V002
   tenant context. No active service account means no new lease.
2. **Preflight is context-free but event-scoped.** It runs as the existing
   read-only `opsmind_context_resolver`, checks `session_user`, and returns a
   bounded code. It does not call or require `opsmind_current_tenant_id()`.
3. **Settlement is context-free and event-scoped.** A new definer function
   owns all terminal/retry mutation for this workflow-start event. It checks
   the exact live lease itself and updates binding, inbox, and outbox in the
   same database statement/transaction.
4. **No implicit fall-through.** A lost, expired, or mismatched lease returns
   `workflow.lease-lost` and changes nothing. It is never converted into
   broad tenant access or an unfenced cleanup.

### Function contracts

Keep and revise the current preflight function:

```sql
opsmind_preflight_investigation_workflow_start(
  p_organization_id uuid,
  p_event_id uuid,
  p_lease_token uuid,
  p_rpc_safety_margin_ms bigint
) returns varchar(128)
```

Owner: `opsmind_context_resolver`; EXECUTE: `opsmind_dispatcher` only.

Required behavior:

- require `session_user = 'opsmind_dispatcher'`; validate non-null identity
  and the existing bounded safety margin;
- do not require or set tenant context;
- return only: `workflow.preflight-allowed`, `workflow.lease-lost`,
  `workflow.deadline-exhausted`, `workflow.dispatcher-ineligible`, or
  `workflow.authorization-revoked`;
- use database time and current binding/actor/project/incident/account state;
- expose no identities, payloads, credentials, or membership fields.

Add one public, workflow-start-only settlement function:

```sql
opsmind_settle_investigation_workflow_start(
  p_organization_id uuid,
  p_event_id uuid,
  p_lease_token uuid,
  p_outcome varchar(16),       -- STARTED | RETRY | REJECTED
  p_temporal_run_id varchar(255),
  p_error_code varchar(128),
  p_retry_delay_ms bigint
) returns varchar(128)
```

Owner: existing NOLOGIN `opsmind_dispatch_resolver`; EXECUTE:
`opsmind_dispatcher` only. Do **not** add write authority to
`opsmind_context_resolver`.

Common guards, before any mutation:

- require dispatcher `session_user`, non-null identity/token, one of the three
  fixed outcomes, and a safe bounded error/retry value;
- lock the exact outbox row first, then its binding and inbox row in a stable
  order;
- require workflow-start event type/schema/aggregate metadata, matching
  binding/start event, `PENDING` binding, unpublished/unpoisoned event,
  matching token, and `lease_expires_at > clock_timestamp()`;
- otherwise return `workflow.lease-lost` with no writes.

Outcome rules:

| Outcome | Required inputs | Atomic result |
|---|---|---|
| `STARTED` | Nonblank bounded Temporal run id; no error/retry data | Binding `PENDING -> STARTED`, database-clock `temporal_started_at`/`updated_at`; inbox claimed/processed; outbox published and lease cleared |
| `REJECTED` | Safe `workflow.*` rejection code; no Temporal id/retry data | Binding `PENDING -> REJECTED`, database-clock rejection fields; inbox claimed/poisoned; outbox poisoned and lease cleared |
| `RETRY` | Safe retryable code and bounded positive delay | Leave binding `PENDING`; clear lease and schedule `clock_timestamp() + delay`. If the dispatcher account is now ineligible, convert this to the same atomic `REJECTED/workflow.dispatcher-ineligible` result rather than releasing a permanently unschedulable event |

The function returns only a bounded settlement result, for example
`workflow.started`, `workflow.retry-scheduled`, `workflow.rejected`, or
`workflow.lease-lost`. Use `clock_timestamp()` captured once per call for all
write timestamps. Do not accept a caller-provided publication, rejection, or
binding timestamp.

The fixed function body is the capability firewall. It must use a locked,
fully qualified static query with `SET search_path = pg_catalog, public,
pg_temp`; no dynamic SQL; all PUBLIC grants revoked.

### RLS and privilege design

- Retain V011 preflight ownership by **read-only**
  `opsmind_context_resolver`. V003/V011 already establish the needed
  cross-tenant, function-only read path.
- For the settlement owner, add only column-level grants needed for its fixed
  SQL: event/binding inspection, binding transition columns, inbox
  claim/process/poison columns, and outbox publication/failure/lease columns.
- Add `FOR SELECT`/`UPDATE`/`INSERT` RLS policies only for
  `opsmind_dispatch_resolver` on `outbox_events`,
  `investigation_workflow_bindings`, and `inbox_events`. The role is NOLOGIN,
  non-owner, no-inherit, and no-bypass under V002; it cannot be used directly.
- If retry eligibility is checked in settlement, grant that resolver only the
  service-account/organization columns needed for that predicate; V002 already
  has the intended resolver pattern.
- Do not set `opsmind.tenant_id`, `opsmind.workload_id`, or actor context in
  either function. This avoids pooled-context leakage and proves the stale
  lease has no general tenant capability.

### Java integration touch points

`InvestigationWorkflowDispatchTransactions`:

1. Keep `claim(...)` and its `inTenant(...)` wrapper unchanged.
2. Change `preflight(...)` to call the definer in a dispatcher transaction
   **without** `tenantContext.apply(...)`.
3. Replace `leaseIsLive`, `acknowledgeStarted`, `releaseRetry`, and `reject`
   for workflow-start events with one typed `settle(...)` call. Do not retain a
   separate check-then-write path; settlement owns lease validation and removes
   that TOCTOU window.
4. Keep generic `TransactionalOutboxLeaseStore` and
   `OutboxDispatcherTenantContextSql` unchanged for ordinary events.

`InvestigationWorkflowStartDispatcher`:

1. On a non-allow preflight code, call `settle(REJECTED, code)` and make no
   Temporal call. Treat a `lease-lost` settlement result as unhandled, not an
   error to overwrite.
2. On successful Temporal start, call `settle(STARTED, temporalRunId)` even if
   the account became inactive after the RPC. This records an existing external
   fact; it does not authorize a new RPC.
3. On retryable failure, call `settle(RETRY, code, retryDelay)`. The function
   terminally rejects if current service-account eligibility disappeared.
4. On permanent decode/Temporal failure, call `settle(REJECTED, code)`.

No Temporal RPC may be put inside the settlement transaction.

## Compatibility and migration handling

- V010 stays untouched. Its trigger accepts the settlement update because it
  checks `session_user = 'opsmind_dispatcher'`; the definer changes
  `current_user`, not `session_user`.
- There is no data rewrite, new public API, or generic outbox behavior change.
- V011 is currently an untracked worktree migration. Revise it there before
  it is applied anywhere. If any environment has already applied V011, leave
  its checksum immutable and add a forward V012 instead.
- Existing deterministic Temporal workflow IDs and `AlreadyStarted`
  reconciliation remain the recovery mechanism for post-RPC/pre-settlement
  crashes.

## Validation criteria

Run after capacity is available; no storage-heavy tests were run for this
decision.

1. Claim under an active account, suspend it, then preflight: receive
   `workflow.dispatcher-ineligible`, no exception, no Temporal invocation.
2. Settle that live lease as `REJECTED`: binding, inbox, and outbox reach their
   terminal states atomically with database-clock timestamps.
3. Allow preflight, simulate successful Temporal start, then suspend the
   account before settlement: `STARTED` settlement succeeds and publishes once.
4. Allow preflight, simulate retryable RPC failure, then suspend the account:
   settlement becomes terminal `workflow.dispatcher-ineligible`, not a released
   PENDING row.
5. Wrong tenant, event, token, event type, expired token, already settled row,
   or a stolen lease returns `workflow.lease-lost` and mutates nothing.
6. Assert `opsmind_dispatcher` still cannot directly read identity/membership
   tables or set stale tenant context; assert no tenant context is present in a
   direct preflight/settlement connection.
7. Inject a failure after each settlement sub-step and prove full rollback of
   binding, inbox, and outbox; then run fresh V001-V011 and upgrade-path proof.
8. Preserve existing active-account claim, authorization revocation, deadline,
   one-lease, ambiguous-Temporal, and cross-tenant RLS tests.

## Decision

Adopt **option 2: event-specific SECURITY DEFINER preflight plus atomic
settlement**. It is deliberately more explicit than a lease-scoped tenant
context because Phase 9's requirement is one-event convergence, not a general
post-revocation tenant session. It is substantially smaller and safer than a
new state machine.

## Unresolved questions

- A lease that has already expired or been stolen must not be settled by this
  path. If a tenant account is permanently disabled while such `PENDING` rows
  exist, a separately authorized administrative deprovision/reconciliation
  policy is required; this function must not silently bypass the expired lease.
- Confirm whether V011 has been applied outside this untracked worktree before
  choosing V011 amendment versus V012.
