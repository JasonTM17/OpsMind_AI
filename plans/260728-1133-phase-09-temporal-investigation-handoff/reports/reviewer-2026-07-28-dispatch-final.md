---
title: Final dispatch safety-fence review
date: 2026-07-28
scope: Phase 9 V011 integrated dispatch fence
status: blocked
superseded_by: reviewer-2026-07-28-v012-re-review.md
---

# Dispatch Safety-Fence Final Review

> Historical V011 review. V012 remediation and its remaining evidence gaps are
> recorded in `reviewer-2026-07-28-v012-re-review.md`.

## Code Review Summary

### Scope

- Baseline reviewed: `fffb0dd` (`merge: add dispatch safety fence`) in
  `feature/temporal-investigation-handoff`.
- Focus: V011 privilege/RLS boundary, V010/V002 inherited grants, dispatch
  state transitions, ambiguous Temporal outcomes, lease/reconciliation paths,
  and fresh/upgrade test evidence.
- Files read: V002, V010, V011; workflow dispatcher/transactions/client
  classes; outbox lease store/claimer; dispatcher persistence tests; Phase 6
  plan and architecture reports; static and upgrade validators.
- Excluded from the committed review: the current uncommitted test-only mock
  of `InvestigationWorkflowStarterRunner`, owned by the integration lead.
- Scout findings: a valid tenant context gives the dispatcher direct
  workflow-binding/inbox/outbox mutation capability; the common failure helper
  turns an ambiguous post-RPC result into a terminal rejection at a local
  budget boundary; a previously claimed row without an eligible account has no
  normal convergence owner.

### Overall Assessment

**Block Phase 6/B completion and landing as a production safety fence.** V011
correctly narrows its *function* calls, but it does not remove the older raw
relation authority that lets the same dispatcher role bypass those functions.
It also records `REJECTED` after an explicitly ambiguous Temporal transport
result once a local attempt/age/deadline budget is crossed. That can assert a
false external fact and permanently close the deterministic reconciliation
path.

The implementation contains useful controls worth retaining: fixed
`SECURITY DEFINER` search paths, session-user checks, bounded function inputs
and outputs, database-clock settlement, exact live-lease checks, and a
one-item workflow claim. They are insufficient while the direct write path and
ambiguous-terminal state transition remain available.

### Critical Issues

#### 1. Dispatcher can bypass the V011 capability firewall through inherited direct grants

**Evidence**

- `V002__outbox_dispatcher_workload.sql:182-190` grants
  `opsmind_dispatcher` `SELECT` and UPDATE of
  `published_at, attempts, last_error, next_attempt_at, lease_token,
  lease_expires_at, poisoned_at` on `outbox_events`.
- `V010__investigation_workflow_start_handoff.sql:465-474` grants that role
  `SELECT` and transition-column UPDATE on
  `investigation_workflow_bindings`, plus `SELECT, INSERT` and status-related
  UPDATE on `inbox_events`.
- V011 only revokes/grants `EXECUTE` for four functions at
  `V011...sql:636-659`. It contains no revoke for any of those three
  relations.
- Once an active dispatcher account binds its legitimate tenant context through
  V002, RLS admits rows for that tenant. V010's binding trigger
  (`:207-261`) verifies dispatcher session identity and transition *shape*,
  but not the canonical event, a matching live lease, a preflight decision, or
  Temporal evidence.

**Impact**

Possession or compromise of the runtime dispatcher credential can directly
mark any pending binding in its active tenant `STARTED` or `REJECTED`, insert
or poison its inbox record, and publish/poison/release workflow outbox rows.
The claimed V011 comment that settlement "grants no general tenant capability"
is therefore false for the actual role. The result is a trust-boundary and
state-integrity bypass, not merely a missing negative test.

**Required remediation**

Use a forward V012 because V011 is an applied Flyway owner:

1. Route the workflow-start claim through an event-specific fixed function;
   current `InvestigationWorkflowDispatchTransactions.claim()` still calls
   generic `claimBatchForEventType`, whose `TransactionalOutboxClaimer`
   performs direct `SELECT ... FOR UPDATE` and `UPDATE` on the event row.
2. Revoke direct dispatcher binding/inbox grants. The Phase 9 Java path already
   uses preflight/settle/terminalizer functions for those transitions.
3. Add a relation guard for direct workflow-start outbox mutation (for example,
   a restrictive dispatcher RLS policy scoped to that event type), while
   retaining generic direct outbox behavior for non-workflow events. The fixed
   function owner needs its own minimal grants/policies; do not broaden the
   dispatcher role to compensate.
4. Prove this with a real `opsmind_dispatcher` connection: after binding a
   valid tenant, raw workflow binding/inbox/outbox DML must fail, while the
   fixed claim/preflight/settle calls still succeed only for the exact event
   and live token.

#### 2. An ambiguous remote Temporal outcome becomes a false terminal rejection

**Evidence**

- `TemporalTransportFailureClassifier.java:59-65` classifies `UNAVAILABLE`,
  `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`, `ABORTED`, `UNKNOWN`, `INTERNAL`,
  and `CANCELLED` as retryable `workflow.temporal-unavailable`. Those results
  can occur after Temporal accepted the start but before the client received a
  response.
- `InvestigationWorkflowStartDispatcher.handleFailure()` at `:111-128`
  releases only while the local attempt, age, and deadline predicates hold.
  Otherwise it calls `reject()`.
- `terminalCode()` at `:137-142` turns that retryable remote ambiguity into
  `workflow.retry-attempts-exhausted`, `workflow.retry-age-exhausted`, or
  `workflow.deadline-exhausted`.
- The existing unit test explicitly requires this behavior:
  `InvestigationWorkflowStartDispatcherTest.java:64-109` expects a retryable
  `workflow.temporal-unavailable` at attempt eight to be rejected.
- V011 settlement then atomically changes the binding to `REJECTED` and
  poisons inbox/outbox (`V011...sql:437-481`) without querying Temporal.

**Impact**

The database can state that a workflow was rejected while an execution with the
same deterministic ID is running in Temporal. Poisoning removes the only
normal route that could receive `AlreadyStarted` and verify its contract. An
application-host clock is also used to calculate the terminal budget
(`InvestigationSliceConfiguration.java:36-37` and dispatcher `:111-119`), so
clock skew can accelerate that false assertion. This violates Phase 6
requirement 5 and the evidence-first invariant.

**Required remediation**

Separate failures by *whether a remote start may have been attempted*:

- A post-RPC ambiguous result must never become `REJECTED` solely because a
  local retry/age/deadline/attempt budget expires. Leave the canonical start
  `PENDING` and explicitly blocked/reconciliation-required; alert it and hand
  it to the exact, read-only Temporal reconciliation lane.
- That lane must only describe/history-read the exact deterministic workflow
  and settle `STARTED` or a proven terminal absence/collision. It must not
  retry `StartWorkflowExecution` after authority has expired.
- Keep bounded retry policy, but make exhaustion a visible operational
  condition rather than invented external truth. Replace the current unit test
  with a remote-accepted/response-lost scenario and assert no rejection at the
  budget boundary.

### High Priority

#### 3. Claimed, later-ineligible starts still have no safe convergence owner

V011's terminalizer deliberately admits only never-claimed events:
`attempts = 0`, `lease_token IS NULL`, and `lease_expires_at IS NULL`
(`V011...sql:515-547`). That is correct because a previous attempt may have
reached Temporal. However, V010's tenant selector requires an active account,
so an already claimed row whose lease expires after account deactivation is no
longer reachable by normal dispatch. It remains `PENDING` indefinitely.

This is a known liveness gap, not a reason to weaken the lease check or poison
the row. The documented exact read-only reconciler is the appropriate owner.
Keep the plan in progress until that lane is implemented or an explicit,
owned deferral/blocker prevents release claims.

### Medium Priority

#### 4. Static/upgrade gates are not executable privilege or atomicity evidence

- `validate-phase-09-workflow-handoff.mjs` passed with `Errors=0`, but it is a
  source-marker check. It cannot detect a grant/RLS bypass or prove function
  execution under the dispatcher login.
- `run-phase-04b-migration-upgrade.sh:530-565` checks only V011 function
  presence and settlement owner. It does not call the functions as
  `opsmind_dispatcher`, test `PUBLIC` denial, verify relation privilege denial,
  or exercise wrong organization/event/token and rollback behavior.
- The persistence test has positive separate-datasource coverage and an
  expired-lease case, but no raw-DML denial matrix. That absence allowed the
  critical direct-grant bypass to survive.
- The static validator presently requires exactly V010 and V011 workflow
  migrations at `:75-84`; it must become V012-aware in the same change, then
  validate the V012 relation guards. Static checks remain supplementary only.

Add fresh and V001-to-current upgrade PostgreSQL tests covering: role identity;
no `PUBLIC` function execution; direct workflow binding/inbox/outbox DML
denial after valid tenant binding; exact function success; wrong tenant/event/
token/expired lease no mutation; and an injected late settlement failure that
rolls binding, inbox, and outbox back together.

#### 5. Deadline fixture remains timing-sensitive until the current test fix is proven

At reviewed commit, the integration fixture creates a deadline only two seconds
after a `clock_timestamp()` sampled before admission
(`InvestigationWorkflowDispatcherPersistenceIntegrationTest.java:333-343`).
The concurrent scheduled runner can make this order-dependent; an
uncommitted runner mock is being added to remove that interference. Re-run the
combined focused suite after landing the test change and retain enough deadline
slack that ordinary database/admission latency cannot change the intended
preflight branch.

### Edge Cases Found by Scout

- A legitimate active dispatcher account, not just an attacker with a forged
  context, can alter arbitrary workflow rows inside its own tenant through
  inherited grants.
- A response-lost Temporal start at the final attempt can be remotely accepted
  and locally poisoned; a later deterministic `AlreadyStarted` observation is
  no longer reachable.
- Lease-window exhaustion shares the same generic failure helper. It is
  pre-RPC and must be kept distinct from post-RPC ambiguity when defining
  terminal policy.
- A claimed row with expired lease after account deactivation is intentionally
  excluded from the unclaimed terminalizer and requires reconciliation.
- No N+1 query was introduced by the workflow-specific processing path; the
  requested workflow claim is bounded to one item. Generic raw outbox claiming
  is nevertheless the wrong authority surface for this event type.

### Positive Observations

- V011 uses exact event/token predicates and database timestamps inside the
  settlement transaction; an expired lease returns `workflow.lease-lost`
  without partial settlement.
- The dispatcher keeps the Temporal RPC outside the database transaction.
- Account restoration in the integration fixture now prevents prior suspension
  tests from contaminating later cases.

### Recommended Actions

1. Land V012 phase-specific workflow claim plus direct-relation guards; prove
   behavior with real PostgreSQL roles before calling it a security fence.
2. Replace terminalization of ambiguous post-RPC failures with durable blocked
   reconciliation; preserve `REJECTED` only for a proven no-RPC/permanent
   condition or verified external fact.
3. Make the read-only exact-workflow reconciliation lane an owned release
   dependency for previously claimed/ineligible rows.
4. Update V012-aware static validation and add fresh/upgrade executable role,
   token, RLS, and atomic-rollback coverage.
5. Re-run the combined focused dispatcher/handoff suite after the current test
   stabilization lands, then run the full Phase 9 evidence gates.

### Metrics

- `git diff --check af45504..fffb0dd`: pass.
- Static Phase 9 validator: pass (`Errors=0`), marker-only.
- Type coverage: not measured.
- Test coverage: not measured.
- Focused PostgreSQL suite: not executed by this reviewer; shared test file
  had an uncommitted stabilization change during review.
- Linting issues: not measured.

### Unresolved Questions

1. What production component owns the read-only reconciliation credential and
   Temporal retention evidence required to turn a verified absence into a
   rejection?
2. Is V011 already applied in any durable environment? If yes, V012 must remain
   forward-only and no V011 checksum may change.

Status: DONE_WITH_CONCERNS

Summary: Two critical blockers remain: direct dispatcher relation authority
bypasses V011, and ambiguous Temporal outcomes are falsely terminalized at
local retry bounds.

Concerns/Blockers: Do not mark Phase 6/B or the Phase 9 safety fence complete
until V012 relation guards, executable role proofs, and an explicit ambiguous
outcome reconciliation owner exist.
