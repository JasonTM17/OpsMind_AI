# Phase 9 hardening diagnosis

## Scope and pre-fix state

Baseline: `af4550497ff153129ba8c8ec09766b0ab5886c5c`.

The Phase 9 audit reported that an authorization revocation could race with
durable admission, a stale-but-live lease could call Temporal after authority
or deadline changes, and ambiguous Temporal transport outcomes were terminal.
The same baseline also used application-clock values for binding settlement and
allowed the workflow starter to claim a batch before serial RPC processing.

## Reproduction model and observed behavior

1. Authorize a caller, revoke its project membership before durable handoff,
   then invoke the old `createOrLoad`. Baseline
   `JdbcInvestigationWorkflowHandoffRepository.java:95-106` only applied tenant
   context before creating the run, binding, and outbox event; it made no fresh
   `IncidentAnalysisAuthorizer` call and did not verify dispatcher eligibility.
   Expected: denial and zero rows. Actual: the old authorization snapshot could
   admit the write.
2. Claim a valid workflow-start event, then revoke the actor, disable the
   tenant dispatcher account, or move its deadline inside the RPC margin.
   Baseline `InvestigationWorkflowStartDispatcher.java:64-69` performed only
   `leaseIsLive` before `workflowClient.start`. Baseline
   `InvestigationWorkflowDispatchTransactions.java:69-95` checked lease/PENDING
   state but neither current authority, dispatcher eligibility, nor deadline.
   Expected: tenant-scoped terminal rejection with no RPC. Actual: Temporal
   could be invoked using stale authority.
3. Throw a Temporal SDK wrapper containing gRPC `UNKNOWN`, `INTERNAL`, or
   `CANCELLED`, or a status-less `TemporalException`, during start or duplicate
   reconciliation. Baseline `TemporalTransportFailureClassifier.java:19-28`
   returned permanent `workflow.temporal-rejected` whenever its narrow
   retryable set did not match; lines 50-54 listed only four statuses.
   Expected: bounded retry, allowing a later deterministic `AlreadyStarted`
   reconciliation. Actual: terminal rejection.
4. Supply an application clock behind PostgreSQL after a remote start or
   terminal rejection. Baseline
   `InvestigationWorkflowDispatchTransactions.java:83-122` and `145-181` wrote
   app-provided timestamps into workflow bindings. Expected: database-clock
   binding timestamps. Actual: a skewed app clock could violate the binding
   time invariants.
5. Configure `batch-size > 1`. Baseline
   `InvestigationWorkflowStartDispatcher.java:46-52` claimed a list and
   performed serial RPCs, increasing the chance later leases expired before
   dispatch. Expected: one workflow-start lease per dispatch transaction.

## Root cause

The baseline separated initial HTTP authorization from the durable write and
external start, but did not place authoritative, current-state guards at those
two side-effect boundaries. Its transport classifier also treated uncertainty
as a proven rejection, while settlement used a clock outside the database
authority that enforces the binding.

## Why it surfaced now

V010 established the durable handoff and deterministic Temporal identity but
left these time-of-check/time-of-use and transport-classification gaps visible
to the independent post-implementation audit. Phase 6 explicitly records the
forward-only V011 remediation scope.

## Blast radius

- Temporal `POST /investigations` admission and idempotent handoff path.
- Dispatcher pre-RPC start, acknowledgement, rejection, retry, and lease
  handling.
- PostgreSQL V001-V011 migration/upgrade evidence and dispatcher least-
  privilege/RLS guarantees.
- Temporal duplicate reconciliation and all retry-budget policy paths.

## Selected remediation and proof plan

- A: reauthorize in the existing handoff transaction and require an active,
  scoped tenant dispatcher before the first durable write.
- B: add a narrow V011 SECURITY DEFINER decision function and use it directly
  before RPC; settle binding timestamps with `clock_timestamp()` and enforce
  a one-lease workflow starter.
- C: classify ambiguous gRPC/Temporal outcomes as retryable while preserving
  explicit contract, target, and authorization failures as permanent.
- Verify with unit tests, PostgreSQL revoke/eligibility/deadline/skew proofs,
  fresh and upgrade migrations, static validation, full Maven tests, and an
  independent merged-head review once the storage gate permits execution.

## Current verification constraint

At diagnosis time C: had 12.16 GiB free and D: had 17.88 GiB free. The
repository capacity guard requires 20 GiB on D:, so Maven, disposable database,
and build verification are intentionally deferred until capacity is restored.

## Unresolved questions

- No source-level question remains. Runtime validation is blocked only by the
  documented D: capacity gate; compacting Docker's D: VHD would interrupt
  unrelated active containers and requires explicit user direction.
