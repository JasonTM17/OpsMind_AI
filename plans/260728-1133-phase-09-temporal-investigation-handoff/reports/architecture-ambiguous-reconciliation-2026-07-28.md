---
type: brainstorm
title: Exact-workflow reconciliation after ambiguous Temporal start
date: 2026-07-28
scope: Phase 9 post-audit dispatch hardening
status: recommended
---

# Exact-workflow reconciliation after ambiguous Temporal start

## Summary

Recommend a **read-only exact-workflow reconciliation lane in the existing
Platform API deployable, with separate database and Temporal authority from the
starter**.

The lane may lease only a canonical workflow-start outbox event that was
previously claimed, remains `PENDING`, is unpublished/unpoisoned, and currently
has no eligible tenant dispatcher. It may call Temporal
`DescribeWorkflowExecution` and `GetWorkflowExecutionHistory` only. It cannot
call any start, signal-with-start, or update-with-start API. It atomically
settles a verified match as `STARTED`, a twice-confirmed recent absence as
`REJECTED`, and a proven contract collision as `REJECTED`. Every unavailable,
permission, retention, malformed-history, or exhausted-retry result leaves the
binding `PENDING` and raises an operational blocker.

This is the smallest production-safe option: no new deployable and no new
workflow-binding status, while authority, retry accounting, and external truth
remain explicit.

## Problem-first framing

### Proposed mechanism

The immediate request is an exact-workflow reconciliation path for a
previously claimed Temporal start after the tenant dispatcher becomes
inactive.

### Underlying problem

PostgreSQL cannot tell whether an ambiguous remote start reached Temporal.
After the account becomes inactive:

- normal tenant enumeration and claim cannot select the row;
- another `StartWorkflowExecution` request would be a new external action
  without current dispatch authority;
- `REJECTED` would assert an external fact not yet established;
- doing nothing leaves the binding and outbox permanently `PENDING`.

The real requirement is therefore: **observe one immutable, precommitted
Temporal identity and converge local truth without gaining authority to create
an execution**.

### Stakeholders and required outcome

| Stakeholder | Required property |
|---|---|
| Tenant/operator | No duplicate or unauthorized investigation start |
| Platform/DB | Binding, inbox, and outbox converge atomically |
| Temporal | Observation targets one namespace/workflow chain only |
| Security | Account deactivation still blocks every new start |
| Operations | Uncertainty becomes an alertable state, never false rejection |
| Developers | Reuse current outbox lease and settlement invariants |

### Verified current state

- V010 binds a unique `(temporal_cluster_id, temporal_namespace, workflow_id)`
  and enforces deterministic
  `opsmind-investigation/{organization_id}/{run_id}` workflow IDs.
- Normal ready-tenant selection joins an active organization and active
  `opsmind_dispatcher` account with the required audience/scope.
- A normal outbox claim increments `outbox_events.attempts`, installs one lease
  token, and commits before Temporal RPC.
- V011 terminalizes only `attempts = 0` rows. Its comment correctly says every
  prior claim needs explicit reconciliation.
- Current V011 allows confirmed `STARTED` settlement after account suspension
  and preserves ambiguous `RETRY`; both are correct.
- Current Java reconciliation is coupled to `start()`: only
  `WorkflowExecutionAlreadyStarted` enters describe/history verification.
- Starter defaults are 30-second lease, 5-second RPC timeout, 5-second safety
  margin, eight attempts, exponential 1-second to 60-second backoff, and
  one-item claims.
- The follow-up review proves the remaining gap: a prior claim plus expired
  lease and inactive account has no scheduler or settlement owner.

### Assumption challenged

**Do not reuse the normal dispatcher claim/start path for reconciliation.**
That path grants authority to initiate work. Deterministic workflow IDs reduce
duplicate risk but do not make a fresh start request authorized.

The inverse assumption is also wrong: `attempts > 0` does not prove Temporal
received the request. It proves only that the RPC boundary may have been
crossed. Target-side evidence is still required.

### Scope boundary

In scope:

- exact workflow-start handoff reconciliation;
- inactive tenant dispatcher after any prior claim;
- DB selection, fencing, observation, settlement, bounded retry, and alerting.

Out of scope:

- workflow restart/resume after a verified execution has begun;
- worker implementation and worker-side authorization;
- generic outbox reconciliation;
- Temporal namespace/credential provisioning;
- operator cancellation or forced start;
- legacy binding-less run repair.

## Approaches evaluated

Estimates assume existing Phase 9 code and tests; they are engineering ranges,
not measured delivery commitments.

| Option | Delivery | RPCs per row | New runtime surface | Failure latency | Maintainability | Second-order effect | Verdict |
|---|---:|---:|---|---:|---|---|---|
| 1. Reuse deterministic `start()` | 0.5-1 day | 1 start, then 0-2 reads on duplicate | None | 5-30 s | Low code, invalid authority model | Account revocation no longer prevents new execution; an ID policy becomes an authorization mechanism | Reject |
| 2. Same deployable, separate exact read-only lane | 3-5 days | 1 describe + 1 bounded first-history read; two describe samples for absence | New scheduler, narrow DB principal/functions, read-only Temporal credential/facade | Match normally 2 network RTTs; bounded default under 30 s lease | Moderate, one deployable | Temporal retention and read authorization become explicit correctness dependencies | **Recommend** |
| 3. Standalone reconciliation service | 1-2 weeks | Same as option 2 | New service, deployment, health, secret, SLO, runbook | Similar RPC latency plus queue handoff | Highest | Strongest blast-radius isolation, but creates operational ownership before more than one effect type needs it | Defer |

Manual operator reconciliation is a break-glass fallback, not a production
lane. Its implementation is small, but convergence latency becomes hours,
evidence quality varies, and recurring stale rows become operational toil.

## Recommended decision

Adopt **option 2**:

1. Keep the normal starter and its active tenant dispatcher requirement.
2. Add an opt-in reconciler scheduler inside the Platform API artifact.
3. Give it a distinct DB login such as `opsmind_workflow_reconciler`, with
   `EXECUTE` only on exact claim/settlement functions and no direct table
   grants.
4. Give its Temporal client a distinct read-only namespace credential. Prove
   in environment conformance that `StartWorkflowExecution`,
   `SignalWithStartWorkflowExecution`, and update-with-start are denied.
5. Expose a Java port with `observeExactWorkflow(...)` only. Do not inject the
   existing `InvestigationWorkflowClient`, because that interface exposes
   `start(...)`.
6. Reuse the current outbox lease fields for mutual exclusion. Keep
   reconciliation attempts in a distinct inbox consumer so read attempts do
   not consume or inflate start-attempt policy.

This partially supersedes
`architecture-dispatch-settlement-2026-07-28.md`: a post-RPC ambiguous `RETRY`
must **not** become `REJECTED` merely because the dispatcher is now
ineligible. Its event-scoped preflight/settlement design remains valid for
known outcomes.

## Exact DB lane

### Authority

Use a NOLOGIN owner for fixed SQL and a dedicated runtime login:

- owner: narrow reconciliation function owner, `NOLOGIN`, `NOINHERIT`,
  `NOBYPASSRLS`;
- caller: `opsmind_workflow_reconciler`;
- caller has no tenant-context setter and no direct identity, binding, inbox,
  or outbox reads/writes;
- functions require exact `session_user`, static fully qualified SQL, fixed
  `search_path`, bounded inputs/results, and PUBLIC revocation.

Do not model this principal as an active tenant dispatcher service account.
Reconciliation is platform convergence authority, not tenant authority to
start work.

### Claim selection

One SECURITY DEFINER claim function performs one transaction and returns at
most one immutable reconciliation envelope. Select with database time,
`FOR UPDATE ... SKIP LOCKED`, stable `(occurred_at, event_id)` order, and all
of:

- binding matches event organization/run/start event and is `PENDING`;
- event is canonical
  `investigation.workflow-start.requested` schema `1`, aggregate sequence `1`;
- event is unpublished and unpoisoned;
- `outbox_events.attempts > 0`;
- event lease is absent or expired;
- `next_attempt_at <= clock_timestamp()`;
- no active eligible tenant dispatcher account currently exists;
- reconciliation inbox consumer
  `investigation-workflow-reconciler-v1` is absent or `received`;
- reconciliation attempts and age remain within configured bounds.

The claim atomically:

- installs the existing outbox `lease_token`/`lease_expires_at`;
- inserts or increments the reconciliation inbox attempt;
- does **not** increment `outbox_events.attempts`;
- returns only exact binding target fields, canonical payload/digest, prior
  reconciliation code, lease identity/expiry, and reconciliation attempt.

Using the same outbox lease prevents a reactivated normal dispatcher and the
reconciler from owning the row concurrently. Separate inbox accounting avoids
normal starter exhaustion caused by read-only probes.

### Reactivation race

Selection checks account ineligibility, but account state can change before
settlement:

- `MATCH` always settles `STARTED`; it records an external fact.
- `ABSENT` rechecks account eligibility inside settlement.
- If an eligible dispatcher has returned, release the lease immediately for
  the normal authorized path instead of rejecting.
- If the dispatcher is still ineligible and absence is fully confirmed,
  settle `REJECTED`.

### Never-claimed versus previously-claimed

| State | What is known | Allowed action |
|---|---|---|
| `attempts = 0`, no lease | No dispatcher claim occurred; Temporal RPC could not have been made by this handoff | Existing terminalizer may reject under the approved inactive-account policy. No Temporal read required |
| `attempts > 0`, live lease | One dispatcher still owns completion | Existing preflight/settlement path owns it; reconciler must not steal |
| `attempts > 0`, absent/expired lease | RPC may or may not have escaped | Exact read-only reconciliation only; never blind reject or start |
| published/poisoned or binding terminal | Already settled | No-op; claim must exclude |

This distinction is deliberately conservative. A claim that crashed before
decode/preflight may not have reached Temporal, but it is safe to observe it.
Treating it as never-claimed is not safe.

## Temporal observation semantics

### API sequence

Use the Java SDK's service stubs or an equivalent bounded wrapper:

1. Validate configured logical cluster and namespace against immutable binding
   fields before any RPC.
2. Call `DescribeWorkflowExecution` for the deterministic `workflow_id` with
   no run ID. This is read-only and returns current execution description or
   `NOT_FOUND`; `WorkflowStub.describe()` is the SDK convenience equivalent
   and documents `WorkflowNotFoundException` when no execution exists.
3. From `workflow_execution_info`, require the same workflow ID, expected
   workflow type and task queue, and a nonblank `first_run_id`.
4. Call `GetWorkflowExecutionHistory` pinned to
   `(workflow_id, first_run_id)`, with:
   `maximum_page_size = 1`, `wait_new_event = false`,
   `HISTORY_EVENT_FILTER_TYPE_ALL_EVENT`, and no visibility/list query.
5. Require event 1 to be `WORKFLOW_EXECUTION_STARTED`. Verify its workflow ID,
   workflow type, task queue, `first_execution_run_id`, memo
   `opsmind_start_payload_digest`, and decoded
   `InvestigationWorkflowStartRequest` against the canonical DB envelope.
6. Return the **first run ID** for settlement, not necessarily the currently
   described run ID. Temporal continue-as-new, retry, reset, and cron form an
   execution chain; the binding records the initially accepted run.

The Temporal API defines the first history event as
`WorkflowExecutionStarted`; its attributes contain workflow ID/type, task
queue, input, memo, original run ID, and first execution run ID. The
description's `WorkflowExecutionInfo.first_run_id` identifies the first run in
a continue-as-new/retry/reset/cron chain.

### Do not use Workflow Query

Workflow Query does not append a history event, but it invokes a workflow
query handler and can require worker availability. It reports mutable workflow
state, not immutable start admission. Therefore:

- do not register or call an OpsMind query for this lane;
- do not use visibility list/search results as proof;
- use describe plus the first immutable history event only.

### No-start enforcement

Defense in depth:

- Java reconciliation port exposes no start/signal/update methods;
- separate client bean and credential;
- Temporal authorization denies every mutating/start RPC;
- server/API audit test asserts zero start calls in every reconciliation case;
- no fallback from `NOT_FOUND` to `start()`;
- no reuse of `WorkflowOptions` or conflict/reuse policy in reconciler code.

## Observation and settlement outcomes

| Observation | DB settlement | External call after observation | Notes |
|---|---|---|---|
| Exact contract match | `PENDING -> STARTED`; store first run ID; process starter and reconciler inboxes; publish outbox | None | Allowed after account/actor revocation because it records fact, not authority |
| First recent `NOT_FOUND` | Leave `PENDING`; release lease; record `workflow.reconciliation-absence-candidate`; retry after confirmation delay | Describe only on next lease | Never one-sample reject |
| Second consecutive recent `NOT_FOUND`, dispatcher still ineligible | `PENDING -> REJECTED`; code `workflow.temporal-start-not-found`; process reconciler inbox; poison starter inbox/outbox | None | Absence is proven only inside retention/convergence envelope |
| Dispatcher reactivated before absence settlement | Leave `PENDING`; clear lease and make normal claim immediately eligible | Normal authorized starter may later call start | Avoids rejecting newly schedulable work |
| Proven type/task queue/memo/input/chain mismatch | `PENDING -> REJECTED`; `workflow.existing-contract-mismatch`; alert collision | None | A workflow exists, but not the admitted exact contract |
| `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`, `ABORTED`, `UNKNOWN`, `INTERNAL`, `CANCELLED`, status-less I/O/timeout | Leave `PENDING`; bounded retry | Read-only calls only | Same ambiguous transport family as current classifier |
| `PERMISSION_DENIED`, wrong cluster/namespace config, decode failure, malformed/missing history, history disappears after describe | Leave `PENDING`; mark reconciliation `BLOCKED`; alert | None until repaired | These do not prove absence or rejection |
| Retry/age bound exhausted | Leave binding/outbox unresolved `PENDING`; poison only reconciler inbox with `workflow.reconciliation-exhausted`; alert | None | Never convert uncertainty into binding rejection |
| Lease lost/expired | No mutation | None | Another owner or future claim decides |

All terminal writes remain one database transaction and use one captured
`clock_timestamp()`. Settlement must lock and revalidate the exact binding,
event, reconciliation inbox, token, and live lease.

## Retry and retention bounds

Recommended defaults:

- poll interval: 1 second;
- claim batch: exactly 1;
- lease: 30 seconds;
- per-RPC timeout: 5 seconds;
- validate `2 * RPC timeout + 5-second DB/settlement margin < lease`;
- transient retries: maximum 8 reconciliation attempts;
- exponential backoff: 1, 2, 4, 8, 16, 32, then 60 seconds;
- maximum automated reconciliation age: 1 hour from reconciliation inbox
  `received_at`;
- absence confirmation delay: at least `2 * RPC timeout` (10 seconds at
  defaults);
- retry jitter: bounded ±20%, while DB computes persisted next-attempt time.

The investigation deadline and normal start maximum age do not prohibit
observation. A workflow accepted before expiry must still settle `STARTED`
after expiry.

Temporal retention is a correctness boundary. A `NOT_FOUND` is eligible for
absence confirmation only when binding age is safely below the configured
namespace workflow-execution retention, minus an explicit safety margin. If
the history may have aged out, settle `BLOCKED/history-retention-unverifiable`,
not `REJECTED`. Deployment must prove:

`maximum handoff age + reconciliation window + safety margin < namespace retention`.

For global namespaces/failover routing, retain the two-sample `NOT_FOUND`
rule. Any found execution between samples wins and is verified.

## Risks and second-order effects

| Risk | Consequence | Mitigation |
|---|---|---|
| Same credential can still start | Code defect becomes unauthorized external action | Separate read-only credential plus negative Start RPC conformance |
| Continue-as-new before reconciliation | Latest run input differs from admitted start | Describe chain, then read first run history by `first_run_id` |
| History retention expiry | `NOT_FOUND` can mean deleted evidence, not no start | Retention invariant; old rows block and alert |
| Account reactivation during two-sample absence | Reconciler could reject newly authorized work | Recheck eligibility under settlement lock; release to normal lane |
| Read attempts reuse `outbox.attempts` | Reconciliation can falsely exhaust starter | Separate reconciliation inbox counter |
| Reconciler unavailable | Stale PENDING rows remain | Dedicated health/lag metrics and paging; no unsafe fallback |
| Worker runs after dispatcher revocation | Investigation may proceed with stale authority | Worker reauthorization/fence remains mandatory before work/effects |
| Contract collision | Foreign workflow occupies deterministic ID | Reject admitted binding, keep collision alert/evidence, never terminate foreign workflow |

## Implementation touchpoints

Expected future plan scope, not code changes in this report:

- forward Flyway migration after V011 for roles, exact claim/settlement
  functions, RLS/grants, and any indexes;
- separate reconciler properties, datasource, scheduler, transactions, and
  read-only Temporal observation client;
- reuse current event codec and exact contract matcher without exposing
  `start()`;
- focused Java unit tests, Temporal test-server tests, and PostgreSQL
  fresh/upgrade/privilege tests;
- deployment configuration and runbook for read-only credentials, retention,
  blockers, and manual re-arm.

Do not edit V010. If V011 has been applied anywhere, add V012; never alter an
applied Flyway checksum.

## Success metrics and validation

Acceptance criteria:

1. An inactive-account row with `attempts = 0` is terminalized without any
   Temporal RPC; an otherwise identical `attempts > 0` row is not.
2. A matching previously accepted workflow converges to `STARTED` with its
   first run ID, even when already closed or continued-as-new.
3. No reconciliation test or production credential can successfully invoke
   `StartWorkflowExecution`, signal-with-start, or update-with-start.
4. One and only one row is leased; wrong tenant/event/token, expired token,
   settled row, or stolen lease mutates nothing.
5. One `NOT_FOUND` never rejects. Two qualifying samples plus still-ineligible
   account reject atomically; a reactivated account returns the row to normal
   dispatch.
6. Every transient/unverifiable/exhausted result leaves the binding `PENDING`
   and the outbox unpoisoned; blocker metric and alert identify the exact row.
7. Reconciliation does not increment normal outbox start attempts.
8. Fresh and upgrade PostgreSQL tests prove PUBLIC denial, no direct table
   access, forced-RLS behavior, exact session user, rollback after each
   settlement sub-step, and no cross-tenant selection.
9. Default healthy convergence target: match within 30 seconds of eligibility;
   99% of transient cases resolve or become explicit `BLOCKED` within 5
   minutes.
10. Metrics expose ready count, oldest age, claims, matches, absence candidates,
    verified absences, mismatches, retries, blocked rows, lease loss, and
    retention-ineligible rows without tenant-sensitive payloads.

## References

- Phase plan:
  `phase-06-post-audit-authorization-and-dispatch-hardening.md`
- Review:
  `reports/reviewer-dispatch-hardening-followup-2026-07-28.md`
- Prior settlement decision:
  `reports/architecture-dispatch-settlement-2026-07-28.md`
- Temporal Java `WorkflowStub.describe()`:
  <https://www.javadoc.io/static/io.temporal/temporal-sdk/1.28.2/io/temporal/client/WorkflowStub.html>
- Temporal workflow service request contracts:
  <https://github.com/temporalio/api/blob/master/temporal/api/workflowservice/v1/request_response.proto>
- Temporal execution-chain metadata:
  <https://github.com/temporalio/api/blob/master/temporal/api/workflow/v1/message.proto>
- Temporal first history event attributes:
  <https://github.com/temporalio/api/blob/master/temporal/api/history/v1/message.proto>

## Unresolved questions

1. Does product policy intentionally terminalize a never-claimed start for
   both temporary `suspended` and permanent `revoked` dispatcher states? This
   report preserves current Phase 6 behavior but recommends making the policy
   explicit.
2. What is the minimum workflow-execution retention in every target namespace,
   including failover clusters? Production absence settlement stays blocked
   until this is configuration evidence, not an assumption.
3. Can the chosen Temporal deployment issue a credential that permits
   describe/history while denying start/signal/update? If not, isolate the
   reconciler behind a namespace read proxy or defer production enablement;
   code-only interface separation is insufficient.
