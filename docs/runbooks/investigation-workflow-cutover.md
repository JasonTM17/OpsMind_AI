# Investigation workflow cutover

Use this gate before changing `OPSMIND_INVESTIGATION_EXECUTION_MODE` from
`inline` to `temporal`.

## Preconditions

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

After the inventory exits `0`, keep starts frozen while enabling and validating:

- the dedicated dispatcher database identity;
- the Temporal logical cluster, namespace, workflow type, and task queue;
- compatible worker readiness;
- the workflow-start dispatcher.

Only then enable Temporal admission and release the ingress freeze. Runtime
admission also checks for unbound nonterminal rows, so a missed orphan fails
closed with `investigation.workflow-cutover-required`.

The repository cannot satisfy these preconditions by itself. It has no Temporal
Compose service or workflow worker. The configured task queue must first expose
a poller whose identity and build ID match
`OPSMIND_INVESTIGATION_TEMPORAL_WORKER_IDENTITY` and
`OPSMIND_INVESTIGATION_TEMPORAL_WORKER_BUILD_ID`.

## Rejected-binding alert and recovery

Treat any `REJECTED` binding as an operator alert. Query with an approved
administrative identity that can produce a complete cross-tenant view; forced
RLS makes application/dispatcher results partial or empty:

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

A rejected binding must have a poisoned outbox row and poisoned inbox row with
the same bounded rejection code. The following query must return zero rows;
any result is an integrity incident and blocks rollout:

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

Do not reset `REJECTED` to `PENDING`, delete its evidence, or manufacture a new
outbox row. V010 intentionally makes the binding transition terminal. Freeze
affected starts, preserve the binding/inbox/outbox rows, determine whether an
external execution exists, and use a separately reviewed forward recovery that
proves exact request and execution identity.

## Rollback

Freeze starts, restore `OPSMIND_INVESTIGATION_EXECUTION_MODE=inline`, and set
`OPSMIND_INVESTIGATION_TEMPORAL_CLIENT_ENABLED=false`,
`OPSMIND_INVESTIGATION_WORKFLOW_STARTER_ENABLED=false`, and
`OPSMIND_DISPATCHER_ENABLED=false`. V010 remains applied. Do not delete pending
or rejected bindings, inbox rows, or outbox rows; they are durable recovery
evidence for a forward fix.
