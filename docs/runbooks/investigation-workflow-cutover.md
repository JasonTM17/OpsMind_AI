# Investigation workflow cutover

Use this gate before changing `OPSMIND_INVESTIGATION_EXECUTION_MODE` from
`inline` to `temporal`.

## Current status

This is a required future cutover procedure, not authorization to enable
Temporal today. V013 now provides a default-off function-only database
reconciler, exact-workflow Describe plus first-history observer, aggregate
metrics, and seven source alert rules. A disposable PostgreSQL real-role
contract passes 55 corrected checks including cleanup, including global exact-three
privileges, the settlement outcome matrix, retention/takeover boundaries, and
four rollback failpoints. A local V012-to-V013 privilege upgrade also passes.
B-017 remains active because the updated wired database gate lacks a
revision-bound run, merged-head Maven/Docker/CI/performance/DR evidence is
missing, and live namespace read-only credential/retention conformance,
`promtool` plus live scrape, and an external Alertmanager delivery receipt do
not exist.

## Preconditions

Temporal admission remains disabled until all B-017 conditions have evidence:

- V012-V013 prove containment on fresh and V001-to-current upgrade paths with
  the real application, dispatcher, reconciler, both resolver owners, and
  migration role. Neither runtime login can bypass its fixed functions.
- Injected settlement failure proves binding/inbox/outbox atomic rollback, and
  predecessor-query `EXPLAIN` plus latency evidence meets an accepted threshold.
- Namespace conformance proves the observer credential can Describe the exact
  workflow and read its first history event while Start, signal-with-start, and
  update-with-start are denied. Verified retention exceeds configured handoff,
  reconciliation, and safety bounds. A Continue-As-New case must prove workflow
  type, task queue, memo digest, and decoded start input from the immutable
  first-run event, not current-execution description fields.
- Pinned `promtool` validates config/rules; a live internal scrape proves only
  allowlisted aggregate labels; an external Alertmanager receiver produces a
  delivery receipt for a synthetic reconciliation alert.
- Exact-head Maven, Docker/Compose, PR-quality, and independent review gates
  pass. Do not run these locally while the storage capacity preflight blocks.

1. Disable new investigation starts at the ingress and application layers.
2. Confirm all platform-api replicas observe the freeze.
3. Connect with an approved migration/administrative database identity that can
   produce the complete inventory. Do not use application/dispatcher credentials
   and do not put credentials in the command history.
4. Capture the command output in the controlled release evidence location.

## Inventory gate

```powershell
bash scripts/operations/run-investigation-workflow-cutover-inventory.sh `
  "$env:DATABASE_URL"
```

Exit code `0` proves zero nonterminal runs without durable workflow bindings.
Exit code `3` means cutover is blocked and the emitted JSON identifies every
row requiring reconciliation. Always use this wrapper rather than invoking the
SQL file with bare `psql`: it preserves the blocked-inventory exit contract on
portable psql versions while propagating actual psql failures.

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
- `OPSMIND_WORKFLOW_RECONCILER_DB_USERNAME=opsmind_workflow_reconciler` with a
  distinct runtime secret;
- a reconciler datasource connection timeout within 250-30,000 ms (`3000`
  default) and pool size within 1-4;
- `OPSMIND_WORKFLOW_RECONCILER_DB_QUERY_TIMEOUT_SECONDS` within 1-30 seconds
  (`1` default), applied to JDBC statements, PostgreSQL socket reads, and JDBC
  transactions; connection acquisition plus query timeout must remain strictly
  inside both the configured settlement margin and lease-minus-settlement
  window;
- `OPSMIND_INVESTIGATION_TEMPORAL_OBSERVER_ENABLED=true` only after read-only
  namespace credential conformance;
- `OPSMIND_WORKFLOW_RECONCILER_ENABLED=true` only after its database identity,
  timing/retention bounds, scrape, and alert delivery are proven.

Only then enable Temporal admission and release the ingress freeze. Runtime
admission also checks for unbound nonterminal rows, so a missed orphan fails
closed with `investigation.workflow-cutover-required`.

The repository cannot satisfy these preconditions by itself. It has no Temporal
Compose service or workflow worker. The configured task queue must first expose
a poller whose identity and build ID match
`OPSMIND_INVESTIGATION_TEMPORAL_WORKER_IDENTITY` and
`OPSMIND_INVESTIGATION_TEMPORAL_WORKER_BUILD_ID`.

The management server binds separately on port `8082`. Checked-in defaults
expose health only. Compose explicitly exposes `health,prometheus` to its
internal Prometheus target; do not publish this port through ingress. Source
rules without a configured Alertmanager receiver do not page an operator.

## Terminal and ambiguous-binding alert/recovery

The source rules are:

- `OpsMindWorkflowReconciliationBlocked`;
- `OpsMindWorkflowReconciliationExhausted`;
- `OpsMindWorkflowReconciliationRetentionIneligible`;
- `OpsMindWorkflowReconciliationLagWarning`;
- `OpsMindWorkflowReconciliationLagCritical`;
- `OpsMindWorkflowReconcilerNotReady`;
- `OpsMindWorkflowReconciliationNoProgress`.

On any critical alert, freeze Temporal admission first. Verify the internal
scrape target and reconciler readiness, preserve leases and canonical evidence,
then diagnose with an approved administrative identity. A missing external
notification receipt is an observability incident; do not treat rule presence
as successful delivery.

`workflow.reconciliation-handoff-age-exceeded` is a blocked uncertainty, not a
not-found result. Keep its binding/outbox `PENDING`; do not retry Start or use
age alone to reject it.

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
call. Preserve bounded `PENDING` and let the separate read-only lane Describe
the exact bound workflow and verify its first history input. If the lane is
blocked or exhausted, keep starts frozen and page through the proven external
path. Until all B-017 evidence passes, Temporal must stay disabled; manual SQL
is not a substitute for exact remote evidence.

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
`OPSMIND_DISPATCHER_ENABLED=false`. Also set
`OPSMIND_INVESTIGATION_TEMPORAL_OBSERVER_ENABLED=false` and
`OPSMIND_WORKFLOW_RECONCILER_ENABLED=false`. V010-V013 remain applied; rollback
is configuration-only. Do not delete pending or rejected bindings, inbox rows,
or outbox rows; they are durable recovery evidence for a forward fix. A
possibly accepted post-RPC handoff remains `PENDING`; it is never made
`REJECTED` solely by local budget exhaustion.
