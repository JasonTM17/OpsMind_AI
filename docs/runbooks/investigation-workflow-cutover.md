# Investigation workflow cutover

Use this gate before changing `OPSMIND_INVESTIGATION_EXECUTION_MODE` from
`inline` to `temporal`.

## Current status

This is a required future cutover procedure, not authorization to enable
Temporal today. V012 source removes V011's inherited direct-DML bypass for
canonical workflow-start state, and focused static/rollback-only PostgreSQL
probes pass. B-017 still blocks admission because full fresh/upgrade real-role
and atomicity proof plus the separately authorized read-only reconciliation/
alert lane described below do not yet exist.

## Preconditions

Temporal admission remains disabled until all B-017 conditions have evidence:

- V012 proves containment on fresh and V001-to-current upgrade paths with the
  real application, dispatcher, resolver, and migration roles; the dispatcher
  cannot bypass the intended capability-only binding/inbox/outbox path.
- Injected settlement failure proves binding/inbox/outbox atomic rollback, and
  predecessor-query `EXPLAIN` plus latency evidence meets an accepted threshold.
- A separately authorized reconciliation lane can Describe exactly the bound
  workflow and read its first history input, but cannot Start a workflow. Its
  failure path keeps the binding `PENDING` and emits a bounded aging/alert
  signal.

1. Disable new investigation starts at the ingress and application layers.
2. Confirm all platform-api replicas observe the freeze.
3. Connect with an approved migration/administrative database identity that can
   produce the complete inventory. Do not use application/dispatcher credentials
   and do not put credentials in the command history.
4. Capture the command output in the controlled release evidence location.

## Inventory gate

```powershell
psql "$env:DATABASE_URL" `
  -f scripts/operations/investigation-workflow-cutover-inventory.sql
```

Exit code `0` proves zero nonterminal runs without durable workflow bindings.
Exit code `3` means cutover is blocked and the emitted JSON identifies every
row requiring reconciliation.

The command deliberately performs no automatic backfill. Legacy inline starts
did not persist the canonical request digest or authorization snapshot revision,
so recreating those values would be guesswork. A reducer snapshot that looks
initial is not sufficient authority to start external work.

For each reported row, an operator must either:

- let the inline run converge to a terminal state before the freeze;
- terminate it through the supported incident/investigation workflow; or
- use a separately reviewed, auditable recovery procedure that proves the
  original request identity and authorization snapshot.

Never insert a binding or workflow-start outbox row manually. V010 database
triggers reject forged or incomplete handoffs.

## Enablement

After the inventory exits `0`, keep starts frozen while validating B-017 and:

- the dedicated dispatcher database identity;
- the Temporal logical cluster, namespace, workflow type, and task queue;
- compatible worker readiness;
- the workflow-start dispatcher;
- the no-Start, exact-workflow reconciliation lane and its bounded
  `PENDING`/alert behavior.

Only then enable Temporal admission and release the ingress freeze. Runtime
admission also checks for unbound nonterminal rows, so a missed orphan fails
closed with `investigation.workflow-cutover-required`.

The repository cannot satisfy these preconditions by itself. It has no Temporal
Compose service or workflow worker. The configured task queue must first expose
a poller whose identity and build ID match
`OPSMIND_INVESTIGATION_TEMPORAL_WORKER_IDENTITY` and
`OPSMIND_INVESTIGATION_TEMPORAL_WORKER_BUILD_ID`.

## Terminal and ambiguous-binding alert/recovery

Treat any `REJECTED` binding as an operator alert, not proof that no remote
workflow exists. Query with an approved administrative identity that can
produce a complete cross-tenant view; forced RLS makes application/dispatcher
results partial or empty:

```sql
SELECT count(*) AS rejected_workflow_starts
FROM investigation_workflow_bindings
WHERE status = 'REJECTED';
```

For diagnosis, inspect only bounded identities and error codes; do not select
outbox payload bytes:

```sql
SELECT binding.organization_id,
       binding.run_id,
       binding.rejection_code,
       binding.rejected_at,
       event.poisoned_at,
       event.last_error AS outbox_error,
       inbox.status AS inbox_status,
       inbox.last_error AS inbox_error
FROM investigation_workflow_bindings binding
LEFT JOIN outbox_events event
  ON event.organization_id = binding.organization_id
 AND event.event_id = binding.start_event_id
LEFT JOIN inbox_events inbox
  ON inbox.organization_id = binding.organization_id
 AND inbox.event_id = binding.start_event_id
 AND inbox.consumer = 'investigation-workflow-starter-v1'
WHERE binding.status = 'REJECTED'
ORDER BY binding.rejected_at DESC, binding.organization_id, binding.run_id
LIMIT 100;
```

A known, evidence-backed terminal binding must have a poisoned outbox row and
poisoned inbox row with the same bounded rejection code. The following query
must return zero rows; any result is an integrity incident and blocks rollout:

```sql
SELECT binding.organization_id, binding.run_id, binding.rejection_code
FROM investigation_workflow_bindings binding
LEFT JOIN outbox_events event
  ON event.organization_id = binding.organization_id
 AND event.event_id = binding.start_event_id
LEFT JOIN inbox_events inbox
  ON inbox.organization_id = binding.organization_id
 AND inbox.event_id = binding.start_event_id
 AND inbox.consumer = 'investigation-workflow-starter-v1'
WHERE binding.status = 'REJECTED'
  AND (
    event.event_id IS NULL
    OR event.published_at IS NOT NULL
    OR event.poisoned_at IS NULL
    OR event.last_error IS DISTINCT FROM binding.rejection_code
    OR inbox.status IS DISTINCT FROM 'poisoned'
    OR inbox.last_error IS DISTINCT FROM binding.rejection_code
  );
```

### Retryable post-RPC ambiguity

If a retryable Temporal result followed an RPC that may have been accepted,
local attempt, age, or deadline exhaustion does not establish remote rejection.
Do not convert that uncertainty to `REJECTED`, poison it, or issue another Start
call. Preserve bounded `PENDING`, page through the configured alert path, and
use only the separately authorized read-only lane to Describe the exact bound
workflow and verify its first history input. Until B-017 is proven, Temporal
must stay disabled; this runbook has no manual substitute for that lane.

Do not reset an existing `REJECTED` binding to `PENDING`, delete evidence, or
manufacture a new outbox row. V010 intentionally prevents manual reopening of
the binding transition. If diagnosis finds that a possibly accepted RPC was
involved, preserve the binding/inbox/outbox evidence, freeze affected starts,
and use a separately reviewed forward recovery that proves exact request and
execution identity. The safe forward behavior is `PENDING` until that proof is
available.

## Rollback

Freeze starts, restore `OPSMIND_INVESTIGATION_EXECUTION_MODE=inline`, and set
`OPSMIND_INVESTIGATION_TEMPORAL_CLIENT_ENABLED=false`,
`OPSMIND_INVESTIGATION_WORKFLOW_STARTER_ENABLED=false`, and
`OPSMIND_DISPATCHER_ENABLED=false`. V010-V012 remain applied; rollback is
configuration-only. Do not delete pending or rejected bindings, inbox rows, or
outbox rows; they are durable recovery evidence for a forward fix. A possibly
accepted post-RPC handoff remains `PENDING` and reconciliation-required; it is
never made `REJECTED` solely by local budget exhaustion.
