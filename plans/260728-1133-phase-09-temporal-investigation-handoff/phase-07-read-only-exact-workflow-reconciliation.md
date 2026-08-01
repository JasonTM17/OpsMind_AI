---
title: Read-only exact workflow reconciliation
status: in-progress
priority: P1
created: '2026-07-28T22:10:00+07:00'
dependsOn:
  - phase-06-post-audit-authorization-and-dispatch-hardening
---

# Phase 7 — Read-only Exact Workflow Reconciliation

## Goal

Close B-017 without granting another start path. A default-off reconciler in
the Platform API artifact must converge an exact previously claimed workflow
start by reading Temporal only, then using narrow database capabilities to:

- record a proven exact execution as `STARTED`;
- release a proven-absent row to a newly eligible normal starter;
- reject only after two qualified absence observations while the normal
  dispatcher remains ineligible;
- reject a proven contract collision;
- retain every transient, unverifiable, retention-ineligible, or exhausted row
  as `PENDING` and make it alertable.

No DeepSeek/provider secret, workflow body, evidence body, bearer token, or
tenant-sensitive identifier may enter metrics, logs, Git, or plan evidence.

## Reviewed design decisions

1. Use a separate LOGIN role, `opsmind_workflow_reconciler`, and a separate
   NOLOGIN function owner, `opsmind_workflow_reconciliation_resolver`.
   Reusing `opsmind_dispatch_resolver` was considered and rejected: that owner
   already holds normal dispatch capabilities, so reuse would combine start
   settlement and reconciliation authority.
2. The runtime login receives `EXECUTE` only on fixed reconciliation functions.
   It receives no tenant-context setter and no direct table, sequence, schema,
   identity, binding, inbox, or outbox DML.
3. Claim globally at most one row with `FOR UPDATE SKIP LOCKED`, database time,
   stable `(occurred_at, event_id)` ordering, and the existing outbox lease.
   Reconciliation reads never increment `outbox_events.attempts`.
4. A row is eligible only when its canonical start was previously claimed,
   remains unpublished/unpoisoned with a `PENDING` binding, has no live lease,
   is due, and either:
   - is parked with `workflow.reconciliation-required`; or
   - has no currently eligible normal dispatcher account.
5. Store reconciliation attempt/absence state in inbox consumer
   `investigation-workflow-reconciler-v1`. The first qualified `NOT_FOUND`
   stores a candidate timestamp. A second sample must occur after the
   configured confirmation delay and inside the proven retention envelope.
6. If a normal dispatcher becomes eligible before absence settlement, reset
   the reconciliation inbox epoch, clear the reconciliation marker/lease, and
   return the row to normal dispatch. Deterministic workflow ID and normal
   authorization remain the only path allowed to make another start call.
7. Use a Java port named `InvestigationWorkflowObserver` with
   `observeExactWorkflow(...)` only. It must not extend, inject, wrap, or expose
   `InvestigationWorkflowClient`, `WorkflowClient`, workflow stubs, workflow
   options, signal, update, or start methods.
8. Implement observation through the SDK 1.35.0 service API:
   `DescribeWorkflowExecution` by workflow ID only, then
   `GetWorkflowExecutionHistory` pinned to `first_run_id`, page size one,
   `wait_new_event=false`, and all-event filter. Verify the immutable first
   `WORKFLOW_EXECUTION_STARTED` event, type, task queue, memo digest, workflow
   identity, chain identity, and decoded start input. For Continue-As-New,
   type/queue/memo/input must come from the first-run event, never mutable
   current-execution description fields.
9. Use a distinct Temporal service-stub bean and deployment credential. Source
   separation is necessary but insufficient: production enablement also
   requires an environment conformance test proving that Start, signal/start,
   and update/start are denied.
10. Do not fail the API pod's liveness/readiness because Temporal is down or a
    backlog exists. Expose a dedicated reconciliation cutover/readiness signal
    and page on backlog/blockers; keep Temporal admission fail-closed.

## Database contract

### Migration and roles

Add forward-only migration
`V013__investigation_workflow_exact_reconciliation.sql`. V010-V012 remain
immutable.

Bootstrap must create and validate:

- `opsmind_workflow_reconciler`: LOGIN, NOSUPERUSER, NOCREATEDB,
  NOCREATEROLE, NOINHERIT, NOREPLICATION, NOBYPASSRLS;
- `opsmind_workflow_reconciliation_resolver`: NOLOGIN with the same unsafe
  capabilities disabled;
- no role membership in either direction for either protected role.

The migration grants only the owner columns needed by its functions, revokes
PUBLIC, and grants the runtime login only function execution.

### Claim

```sql
opsmind_claim_investigation_workflow_reconciliation(
  p_lease_token uuid,
  p_lease_duration_ms bigint,
  p_maximum_attempts integer,
  p_maximum_age_ms bigint
)
```

The function returns zero or one row containing:

- canonical event envelope and payload digest;
- immutable binding target and start-payload digest;
- lease token/expiry;
- reconciliation attempt, first-received time, prior safe code, and prior
  observation time.

It atomically creates/increments the reconciler inbox, leases the outbox event,
and never increments normal start attempts. Rows with terminal reconciler inbox
state are excluded. Bounds must be validated in SQL.

### Settlement

```sql
opsmind_settle_investigation_workflow_reconciliation(
  p_organization_id uuid,
  p_event_id uuid,
  p_lease_token uuid,
  p_outcome varchar,
  p_temporal_first_run_id varchar,
  p_error_code varchar,
  p_retry_delay_ms bigint,
  p_absence_confirmation_delay_ms bigint,
  p_maximum_verifiable_age_ms bigint
) RETURNS varchar
```

The function captures one `clock_timestamp()`, locks and revalidates the exact
event, binding, starter inbox, reconciler inbox, organization, and dispatcher
account, and accepts only this outcome allowlist:

| Outcome | Required evidence | Atomic result |
|---|---|---|
| `MATCH` | Nonblank first run ID | Binding `STARTED`; publish event; process both inboxes |
| `ABSENT` first sample | Qualified recent `NOT_FOUND`, dispatcher ineligible | Keep `PENDING`; store candidate; schedule confirmation |
| `ABSENT` with eligible dispatcher | Qualified `NOT_FOUND` | Reset reconciliation epoch; clear marker/lease; make starter due |
| `ABSENT` second sample | Candidate old enough, within retention, dispatcher still ineligible | Binding `REJECTED`; poison event/starter inbox; process reconciler inbox |
| `MISMATCH` | Existing exact ID but contract differs | Binding `REJECTED`; collision code; poison event/starter inbox |
| `RETRY` | Allowlisted transient read failure | Keep `PENDING`; bounded next attempt |
| `BLOCKED` | Permission/config/decode/history/retention/exhaustion uncertainty | Keep binding/outbox `PENDING`; poison only reconciler inbox; alert |

Wrong identity, event, token, expired lease, changed binding, terminal row, or
stolen lease returns `workflow.reconciliation-lease-lost` and mutates nothing.
One `NOT_FOUND` can never reject. Uncertainty can never poison the outbox or
change the binding to `REJECTED`.

### Aggregate status

Add a bounded aggregate-only function:

```sql
opsmind_get_investigation_workflow_reconciliation_status()
```

It returns ready, pending, blocked, retention-ineligible, and oldest-pending-age
values without tenant, run, event, workflow, payload, or error text.

## Java/runtime contract

### Required components

- reconciler datasource properties/configuration with exact username
  `opsmind_workflow_reconciler`;
- reconciler properties with validated poll, lease, RPC, settlement margin,
  attempt/age, retry, absence-confirmation, retention, and safety bounds;
- separate Temporal observer service-stub configuration;
- immutable reconciliation envelope/result types and row mapper;
- claim/settlement transaction adapter;
- `InvestigationWorkflowObserver`;
- service-API-only Temporal observer implementation;
- one-item dispatcher and scheduler;
- aggregate-only metrics/readiness adapter.

Default timing:

- poll 1 second;
- claim batch exactly one;
- lease 30 seconds;
- RPC timeout 5 seconds;
- settlement margin 5 seconds;
- database query/socket/transaction timeout 1 second;
- maximum eight read attempts;
- automated age one hour;
- retry backoff 1, 2, 4, 8, 16, 32, then 60 seconds;
- absence confirmation at least two RPC timeouts;
- configured retention minus safety margin must exceed the maximum possible
  handoff plus reconciliation age.

Validation must enforce:

```text
2 * rpc timeout + settlement margin < lease
connection acquisition timeout + query timeout < settlement margin
connection acquisition timeout + query timeout < lease - settlement margin
maximum handoff age + reconciliation age + retention safety margin
  < namespace workflow-execution retention
```

The dispatcher must release the DB transaction before both Temporal RPCs.
Safe exception taxonomy:

- `NOT_FOUND` from describe: `ABSENT`;
- exact match: `MATCH(firstRunId)`;
- type/task-queue/memo/input/chain difference: `MISMATCH`;
- `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`, `ABORTED`,
  `UNKNOWN`, `INTERNAL`, `CANCELLED`, status-less timeout/I/O:
  retryable read failure;
- `PERMISSION_DENIED`, configuration mismatch, malformed/missing history,
  decode failure, or history disappearing after describe: blocked,
  never absent.
- maximum handoff age exceeded before observation:
  `workflow.reconciliation-handoff-age-exceeded`, blocked with canonical
  binding/outbox still `PENDING`.

## Observability contract

No tenant-sensitive labels. Required series:

- gauges:
  `opsmind_workflow_reconciliation_ready`,
  `opsmind_workflow_reconciliation_pending`,
  `opsmind_workflow_reconciliation_oldest_pending_age_seconds`,
  `opsmind_workflow_reconciliation_blocked`,
  `opsmind_workflow_reconciliation_retention_ineligible`;
- counter:
  `opsmind_workflow_reconciliation_outcomes_total{outcome=...}` with the fixed
  low-cardinality set `match`, `absence_candidate`, `verified_absence`,
  `released`, `mismatch`, `retry`, `blocked`, `lease_lost`, `exhausted`;
- timers:
  `opsmind_workflow_reconciliation_observation_duration_seconds{operation=...}`
  and `opsmind_workflow_reconciliation_convergence_duration_seconds{result=...}`.

Alerts:

- critical for any blocked, exhausted, or retention-ineligible row;
- warning when oldest pending is over 30 seconds for 2 minutes;
- critical when oldest pending is over 5 minutes for 5 minutes;
- reconciler not ready for 2 minutes while Temporal mode is enabled;
- no claim/outcome progress for 5 minutes while pending is nonzero.

Prometheus exposure/scraping must remain network-scoped and must not expose
health details or application endpoints.

## Parallel workstreams and exclusive ownership

### A — database capability and role proof

**Branch:** `feature/phase9-reconciliation-database`

Owns:

- `services/platform-api/src/main/resources/db/migration/V013__investigation_workflow_exact_reconciliation.sql`
- `services/platform-api/src/main/resources/db/bootstrap/001-create-runtime-role.sh`
- `compose.yaml`
- database/migration contract tests for V013
- `scripts/validation/run-phase-04b-migration-upgrade.sh`
- `scripts/validation/validate-phase-09-workflow-handoff.mjs`

Must not modify Java runtime/config, Prometheus rules, docs, or plan files.

### B — observe-only runtime

**Branch:** `feature/phase9-reconciliation-runtime`

Owns:

- new reconciliation classes under
  `services/platform-api/src/main/java/ai/opsmind/platform/investigation/workflow/`
- matching unit/integration-contract tests under the same test package
- new reconciler datasource classes/tests under
  `services/platform-api/src/main/java/ai/opsmind/platform/messaging/`
- `services/platform-api/src/main/resources/application.yaml`
- `.env.example`
- `services/platform-api/pom.xml`
- `services/platform-api/src/main/java/ai/opsmind/platform/identity/SecurityConfiguration.java`
- directly corresponding security/bootstrap tests

Must not modify migrations, role bootstrap, compose, Prometheus rules, docs, or
plan files.

### C — metrics, alerts, and operator proof

**Branch:** `feature/phase9-reconciliation-observability`

Owns:

- `deploy/prometheus/prometheus.yml`
- `deploy/prometheus/opsmind-recording-rules.yml`
- new alert-rule files under `deploy/prometheus/`
- focused validation tests/scripts that do not overlap workstream A
- a report under this plan's `reports/`

Must not modify Java, SQL migrations, bootstrap, compose, docs, or plan files.

The controller owns plan/docs integration and resolves any cross-workstream
configuration join after the three branches are reviewed.

## Verification

### Lightweight while storage guard blocks

- [x] `git diff --check`;
- [x] Java main/test compile surface against locally cached Temporal 1.35.0
  classes (`javac`, not Maven);
- [x] Phase 9 workflow and observability source/static validators;
- [x] shell and YAML syntax;
- [x] changed-file secret scan;
- [x] independent workstream review with concurrency, authorization, data-leak, error,
  compatibility, and no-start checks.
- [x] disposable PostgreSQL V001-V013 real-role reconciliation contract:
  55 PASS markers including cleanup prove direct/PUBLIC denial, the global exact-three
  executable set, membership-drift denial, match, two-sample absence,
  reactivation, mismatch, retry, blocked/retention/exhaustion `PENDING`, lease
  fencing/takeover, cross-tenant denial, and four settlement rollback
  failpoints.
- [x] local V012-to-V013 upgrade proves the exact-three executable set and
  PUBLIC trigger-function denial.
- [x] PR Quality wires the reconciliation contract and V006-to-V013
  upgrade/recovery checks.
- [x] launcher/env fixed-role, pairwise-secret, disabled-default, and bounded
  reconciler datasource connection/query timeout source contracts; query
  timeout reaches JDBC statement, PostgreSQL socket, and transaction defaults
  and connection-plus-query time is startup-validated against
  settlement/lease safety margins.

### Mandatory before B-017 closes

- [x] focused and full Maven tests on exact main commit `8092b38`;
- [x] revision-bound CI execution of the wired fresh V001-V013,
  V006-to-V013 upgrade/recovery, real-role, and four-failpoint atomicity gates;
- [ ] query-plan/latency, DR, and production database proof;
- [x] exact-head runtime tests for matching, continued-as-new, closed, not-found
  double sample, reactivation, mismatch, transient, permission, corrupt payload,
  missing history, retention, exhaustion, and lease loss;
- [ ] Temporal authorization conformance proving read RPC success and every
  start/signal/update-with-start mutation denied;
- [ ] namespace retention conformance against configured timing bounds;
- [x] pinned `promtool` rule/config validation;
- [ ] non-vacuous live scrape of real reconciliation series showing only bounded
  labels;
- [ ] configured external Alertmanager receiver and end-to-end page-delivery
  receipt;
- [x] exact-main Docker/Compose and full PR Quality run `30699950577`;
- [ ] independent final production-readiness review after Phase 8 integration.

## Acceptance criteria

1. Previously claimed ambiguity converges without any reconciliation code path
   being able to call Start, signal, update, or query.
2. Exact match stores the first run ID and converges atomically even after
   closure or continue-as-new.
3. One absence sample never rejects; two qualified samples reject only while
   the dispatcher remains ineligible and the history-retention proof is valid.
4. Reactivation returns work to the normal authorized starter without losing
   future reconciliation ability.
5. Mismatch rejects the local binding but never mutates the foreign workflow.
6. Every uncertainty remains binding/outbox `PENDING`; blocked/exhausted states
   are visible and page an operator.
7. Reconciliation attempts never increment normal start attempts.
8. Runtime login has function execution only, no direct data access, tenant
   context, role membership, or ownership escape.
9. Metrics/logs/configuration contain no tenant, workflow, payload, credential,
   or user identifiers.
10. Temporal mode and roadmap G4 remain disabled until all mandatory evidence
    passes on the exact merged head.

## Out of scope

- starting, restarting, resuming, cancelling, terminating, signalling, querying,
  or updating a workflow from the reconciliation lane;
- Temporal worker implementation or live namespace/credential provisioning;
- generic outbox reconciliation;
- manual force-start or automatic re-arm of a blocked row;
- B-013 pilot/calibration, master Phase 9 exit, production release, Docker Hub
  publication, or GitHub release/package changes.

## Rollback

Disable both reconciler scheduler and Temporal observer. Do not roll back or
edit V013 after application. Existing `PENDING` evidence stays intact. A future
forward migration may change capabilities; manual SQL must not terminalize an
uncertain row without exact target-side evidence.

## Capacity gate

As of 2026-08-01, C has 11.21 GiB free and D has 23.24 GiB free. Work may
continue only while every heavy operation rechecks and preserves C at or above
10 GiB and D at or above 20 GiB. Prefer revision-bound CI for heavy validation;
stop before dependency downloads or Docker builds when either guard fails.

## Unresolved questions

- Production Temporal namespace retention and read-only credential enforcement
  remain deployment-owner evidence, not source assumptions.
- The repository has seven alert rules but no Alertmanager receiver or proven
  external notification path. Source rules do not satisfy page delivery.
- The product owner must later define the break-glass re-arm procedure for a
  blocked row; automatic re-arm is intentionally excluded.
