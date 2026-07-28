---
title: Dispatch hardening follow-up review
date: 2026-07-28
scope: Phase 6 Workstream B / V011
status: blocked
---

# Dispatch Hardening Follow-up Review

## Code Review Summary

### Scope

- Branch/worktree: `fix/phase9-dispatch-safety-fence` at
  `D:\worktrees\OpsMind_AI-phase9-dispatch-safety-fence`.
- Files: 12 tracked modifications plus three untracked additions.
- Delta: tracked `+646/-210`; new files add 700 lines, including V011 at 634
  lines.
- Focus: PostgreSQL syntax and grants/RLS, SECURITY DEFINER trust boundaries,
  exact live-lease checks, preflight/settlement atomicity, account revocation,
  retry/rejection truth, terminalizer safety, Java API consistency, and test
  realism.
- Scout findings: settlement is now event/token scoped and context-free;
  unclaimed terminalization deliberately excludes every prior claim; ambiguous
  retry can still be converted to terminal rejection; the PostgreSQL test class
  leaks suspended service-account state between methods.
- Constraints: Maven, Docker, and PostgreSQL were not run because the task
  prohibited heavy work while D: is below the 20 GiB gate.

### Overall Assessment

**BLOCK.** The follow-up fixes the prior V011 execution/grant defect, removes
the tenant-context dependency from post-claim settlement, checks the remaining
database-clock RPC window, and keeps STARTED settlement available after account
deactivation. Static inspection found no PostgreSQL syntax defect in the added
functions and no stale Java caller of the changed APIs.

The implementation still records an unproven external outcome as REJECTED:
`RETRY` is converted to `workflow.dispatcher-ineligible` when account
eligibility disappears. A Temporal start can already exist after an ambiguous
transport failure, so this transition can leave a live Temporal workflow behind
a poisoned outbox and REJECTED binding. Two integration-test defects also make
the claimed PostgreSQL proof non-runnable/order-dependent.

### Critical Issues

#### 1. Ambiguous Temporal acceptance is converted into a false terminal rejection

- Location:
  - `services/platform-api/src/main/resources/db/migration/V011__investigation_workflow_dispatch_safety_fence.sql:312-326`
  - same file `:407-451`
  - `services/platform-api/src/main/java/ai/opsmind/platform/investigation/workflow/InvestigationWorkflowStartDispatcher.java:85-121`
  - `services/platform-api/src/test/java/ai/opsmind/platform/investigation/workflow/InvestigationWorkflowDispatcherPersistenceIntegrationTest.java:446-469`
- Trigger:
  1. Preflight returns `workflow.preflight-allowed`.
  2. Temporal accepts the deterministic workflow start, but the client receives
     an ambiguous retryable failure such as timeout/UNAVAILABLE/UNKNOWN.
  3. The tenant dispatcher account is suspended or revoked before
     `releaseRetry`.
  4. V011 changes `effective_outcome` from `RETRY` to `REJECTED` and poisons the
     binding, inbox, and outbox.
- Impact: PostgreSQL permanently records `REJECTED` while the Temporal workflow
  may be running. The event can no longer reach deterministic
  `AlreadyStarted` reconciliation. This contradicts Phase 6 requirement 5,
  which keeps ambiguous failures retryable specifically so reconciliation
  remains reachable, and violates the evidence-first rule by asserting a
  rejection without target-side evidence.
- Current test is false assurance: it calls `releaseRetry` directly after
  suspension and never models a remote request whose acceptance is unknown.
- Required fix: do not convert an ambiguous post-RPC `RETRY` to `REJECTED`
  solely from account ineligibility. Preserve a durable
  reconciliation-required/PENDING state and provide a narrowly authorized
  target-side reconciliation path that can query the exact deterministic
  workflow identity without authorizing a new start. Only settle REJECTED after
  Temporal absence/rejection is proven.
- Adversarial verdict: **Accept — data-truth and external-effect blocker.**

### High Priority

#### 2. Insufficient remaining lease window permanently poisons an otherwise valid start

- Location:
  - `V011__investigation_workflow_dispatch_safety_fence.sql:88-98`
  - `InvestigationWorkflowDispatchPreflightDecision.java:10`
  - `InvestigationWorkflowStartDispatcher.java:67-75`
  - integration test `:348-370`
- Problem: `workflow.lease-window-exhausted` is marked
  `rejectWithoutRpc=true`. The dispatcher therefore calls REJECTED settlement,
  transitions the binding to REJECTED, and poisons the event. This condition is
  local and recoverable: the exact lease is still live but has too little time
  remaining; releasing it and claiming a fresh full-duration lease restores the
  required RPC window.
- Trigger: scheduler pause, GC pause, slow payload decoding, or database latency
  consumes most of a 30-second lease before preflight.
- Impact: a valid authorized investigation is permanently lost because of
  transient dispatcher delay, not deadline, authorization, target, or payload
  failure.
- Required fix: treat lease-window exhaustion as a fenced retry/release, with a
  bounded delay and the existing attempt/age/deadline limits. Keep true deadline
  exhaustion terminal. Add a dispatcher-level test proving zero RPC, PENDING
  binding, cleared lease, and a future retry time.
- Adversarial verdict: **Accept — recoverable condition is misclassified as
  terminal.**

#### 3. Previously claimed work becomes permanently stranded after account deactivation

- Location:
  - terminalizer selector `V011...sql:485-517`, especially `:499-501`
  - V010 ready-tenant selector
    `V010__investigation_workflow_start_handoff.sql:408-427`
  - V011 comment `:458-461`
- Problem: the terminalizer safely limits itself to `attempts = 0` and no lease,
  because a prior claim may have reached Temporal. However, V010 will not
  enumerate a tenant whose dispatcher account is inactive. Therefore a claimed
  event whose lease expires after a dispatcher crash/account revocation has no
  remaining scheduler or settlement path.
- Impact: binding stays PENDING and outbox stays unpublished indefinitely. If
  Temporal accepted the start, PostgreSQL never converges to STARTED; if it did
  not, the investigation never starts. This is safer than blind poisoning, but
  Phase 6 requirement 4 and the terminalizer liveness goal remain incomplete.
- Required fix: keep the terminalizer exclusion; do not guess an ambiguous
  outcome. Add an exact-workflow reconciliation lane with separate authority,
  or explicitly defer it in the plan/blocker register and prevent Workstream B
  from being marked complete.
- Adversarial verdict: **Accept — no convergence path for a documented
  production state.**

#### 4. PostgreSQL integration tests leak account state and cannot reliably run as a suite

- Location:
  - test setup
    `InvestigationWorkflowDispatcherPersistenceIntegrationTest.java:108-123`
  - suspension tests `:390-491`
  - fixture seed
    `services/platform-api/src/test/java/ai/opsmind/platform/testing/PostgresTenantFixtures.java:79-94`
- Problem: three tests set Tenant A's dispatcher account to `suspended`.
  `@BeforeEach` calls the shared fixture, but the service-account insert uses
  `ON CONFLICT (id) DO NOTHING`; it does not restore status, scopes, or audience.
  The setup only quarantines old workflow events. The first suspension test
  therefore leaves later tests unable to bind tenant context and claim work.
- Impact: test results depend on method order, and at least the later suspension
  tests can fail before reaching the behavior they claim to prove. CI evidence
  is not reproducible.
- Required fix: reset the exact fixture account to active with canonical
  capabilities in `@BeforeEach`, or isolate each test in a rolled-back/
  disposable database transaction compatible with the multiple datasources.
  Assert the precondition before each claim.
- Adversarial verdict: **Accept — required PostgreSQL gate is stateful and
  order-dependent.**

#### 5. The one-lease test deterministically makes the released lease eligible again

- Location:
  `InvestigationWorkflowDispatcherPersistenceIntegrationTest.java:494-522`
- Problem: the test releases the first/earliest lease with a one-second
  database-clock delay, then claims with caller time `NOW.plusSeconds(2)`, where
  `NOW` is 2030. The released row is immediately eligible relative to that
  supplied time and still sorts before the untouched row by
  `(occurred_at,event_id)`. The second claim may re-claim the same event, while
  the test requires `remainingRunId`.
- Impact: the test does not prove one-claim behavior and is expected to fail
  under the real query ordering. It also hides the production distinction
  between database-clock retry scheduling and caller-clock claim selection.
- Required fix: assert the first transaction leased exactly one row directly,
  then terminally settle it before the next claim; or use a database-derived
  claim time that is strictly before the released row's `next_attempt_at`.
- Adversarial verdict: **Accept — fixture timing contradicts the query
  contract.**

### Medium Priority

#### 6. Upgrade and privilege evidence remains structural rather than behavioral

- Location:
  - `scripts/validation/run-phase-04b-migration-upgrade.sh:530-565`
  - `scripts/validation/validate-phase-09-workflow-handoff.mjs:49-67`
- Problem: upgrade proof checks only function presence and settlement owner.
  The static validator checks marker strings. Neither calls V011 as the real
  dispatcher login, proves PUBLIC denial/direct identity-table denial, verifies
  RLS policy behavior, supplies a wrong tenant/token, or forces a late
  settlement sub-step failure to prove rollback.
- Impact: function ownership, column-grant, RLS, and atomicity regressions can
  pass the upgrade gate. The fresh integration class exercises positive
  dispatcher calls when runnable, but findings 4-5 currently undermine it and
  it lacks the negative privilege matrix.
- Required fix: extend the disposable upgrade proof with real
  `opsmind_dispatcher` calls and negative privilege/lease-token cases. Add one
  forced late-step error and assert binding/inbox/outbox all roll back.

### Low Priority

None.

### Edge Cases Found by Scout

- Wrong/expired/stolen lease: settlement locks the exact event/binding and
  returns `workflow.lease-lost` before mutation. Expired-lease test covers one
  case; wrong tenant/token and already-settled cases remain unproved.
- Account suspended after confirmed Temporal success: STARTED settlement
  intentionally remains available and records the external fact. This matches
  the selected architecture.
- Account suspended before RPC: context-free preflight and REJECTED settlement
  are now reachable without weakening normal tenant claim authority.
- Terminalizer locks outbox and binding rows with `SKIP LOCKED`, rechecks
  eligibility, and excludes all prior claims. No N+1 database loop or unbounded
  batch was introduced.
- Preflight-to-RPC revocation remains a committed-snapshot race. The phase
  design already acknowledges PostgreSQL cannot atomically fence a remote RPC;
  worker-side reauthorization/fence remains the correct downstream control.
- SECURITY DEFINER functions use static fully qualified SQL, fixed
  `search_path`, `session_user` checks, bounded inputs/results, and PUBLIC
  revocation. No SQL injection, credential return, PII exposure, or direct
  identity-table grant to `opsmind_dispatcher` was found.
- Java caller search found no stale constructor or changed settlement/claim API
  call in `src`. Full compilation was not run.

### Positive Observations

- Prior blockers are materially addressed: preflight has the required outbox
  columns, settlement no longer requires active-account tenant context, and
  binding/inbox/outbox terminal transitions occur inside one definer call and
  transaction.
- `STARTED` settlement records database-owned timestamps and preserves
  convergence after account suspension.
- Normal claim authority still requires active tenant/workload context; the
  exact lease was not expanded into a tenant-wide post-revocation capability.

### Recommended Actions

1. Remove the ambiguous-RETRY-to-REJECTED conversion and introduce exact
   target-side reconciliation for inactive-account cases.
2. Make lease-window exhaustion retryable; keep deadline/authorization/known
   contract rejection terminal.
3. Add a convergence owner for prior-claim/expired-lease rows or keep Phase 6/B
   explicitly blocked on it.
4. Reset shared service-account fixtures and repair the one-lease timing test.
5. Add real dispatcher execution, negative privilege/RLS, wrong-token, and
   rollback checks to fresh and upgrade PostgreSQL evidence.
6. After capacity recovers, run focused Java tests, V001-V011 fresh/upgrade
   PostgreSQL gates, then full Platform Maven validation.

### Plan Follow-ups

- Requirement 3: source implementation substantially present; runtime proof
  incomplete.
- Requirement 4: live and never-claimed ineligible paths implemented; expired
  prior-claim convergence remains incomplete.
- Requirement 5: **not met** when account ineligibility converts an ambiguous
  retry into terminal rejection.
- Requirement 6: database-clock settlement implemented.
- Requirement 7: one-item claim configured and enforced; integration proof is
  defective.
- Workstream B acceptance: **not met**. Do not update plan status from this
  review.

### Metrics

- Type coverage: not measured.
- Test coverage: not measured.
- Maven/PostgreSQL tests: not run by instruction.
- Static Phase 9 validator: PASS, `Errors=0`.
- Node syntax check: PASS.
- Shell syntax check for migration-upgrade script: PASS.
- `git diff --check`: PASS, 0 whitespace errors.
- Linting issues: not measured.

### Adversarial Review

- Accepted: one Critical, four High, one Medium.
- Rejected: broad direct dispatcher identity-data exposure. The dispatcher
  receives EXECUTE only; owner roles are NOLOGIN/NOBYPASSRLS and return bounded
  codes.
- Rejected: stale confirmed-start settlement after account suspension. This is
  convergence of an existing external fact, not authorization for a new RPC.
- Deferred: the preflight-to-RPC revocation window requires the already
  documented worker-side authorization fence; it cannot be closed by holding a
  PostgreSQL transaction over Temporal.

### Unresolved Questions

1. Which component owns exact Temporal reconciliation after the account is
   permanently revoked: this dispatcher, a separate administrative reconciler,
   or the Temporal worker/control plane?
2. Does product policy intend temporary `suspended` accounts to terminalize
   never-claimed starts immediately, or only permanently `revoked` accounts?
   The current implementation treats both identically.
