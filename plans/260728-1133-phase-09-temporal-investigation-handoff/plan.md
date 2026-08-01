---
title: Phase 9 Temporal Investigation Handoff
description: >-
  Close G1 evidence drift, deliver the atomic tenant-safe outbox-to-Temporal
  handoff, and prove the remaining internal Phase 9 runtime-conformance slice.
status: in-progress
priority: P1
branch: feature/phase-09-runtime-conformance
tags:
  - temporal
  - durability
  - outbox
  - multi-tenant
  - phase-09
blockedBy: []
blocks:
  - phase-10-permission-aware-rag-and-knowledge-lifecycle
created: '2026-07-28T04:33:57.532Z'
createdBy: 'ck:plan'
source: skill
---

# Phase 9 Temporal Investigation Handoff

## Overview

The current investigation slice persists reducer state but executes synchronously
inside the API process. A crash after `investigation_runs` is committed can leave
the run without an execution owner. This plan adds the smallest production-shaped
handoff that closes that crash window for new, valid starts after an explicit
active-run cutover: the API atomically creates the initial
run, immutable workflow binding, and canonical outbox event; a separately enabled
dispatcher leases only workflow-start events; Temporal start uses one
deterministic workflow ID; retry after a crash reconciles `AlreadyStarted` as
success; acknowledgement and outbox publication commit together.

This is Phase 9 infrastructure, not the Phase 9 exit. The existing Java
`InvestigationStateMachine` remains authoritative, inline fixture/evaluation
execution remains compatible, and Temporal admission remains compile/runtime
guarded until a compatible worker is observable on the bound task queue. B-013
still blocks threshold freeze and the full Phase 9 exit. B-017 separately blocks
Temporal admission and roadmap G4 enablement until V012 has full fresh/upgrade
real-role and atomicity proof and an independently authorized no-Start
reconciliation/alert lane exists.

## Decision

Use Temporal Java SDK `1.35.0` through its core/manual configuration APIs on
Java 21. Do not use the alpha Spring integration and do not move the reducer to
Python.

| Option | Complexity | Operational cost | Correctness/maintenance | Decision |
|---|---:|---:|---|---|
| Java SDK in Platform API/dispatcher | Medium | One new Temporal client surface | Reuses Java reducer and current persistence contracts | Selected |
| Python Temporal worker | High | Cross-language deployment and fixtures | Duplicates or remotely wraps the authoritative Java reducer | Rejected |
| Separate workflow microservice now | High | New image, auth path, release lane | Premature boundary before the worker contract is proven | Rejected |
| Database poller without Temporal | Low initially | Custom timers/replay/versioning later | Rebuilds Temporal semantics and increases long-term risk | Rejected |

## Phases

| Phase | Name | Status |
|-------|------|--------|
| 1 | [G1 evidence reconciliation and current-state design](./phase-01-g1-evidence-reconciliation-and-current-state-design.md) | Completed |
| 2 | [Atomic workflow-start persistence](./phase-02-atomic-workflow-start-persistence.md) | Completed |
| 3 | [Temporal client and one-owner starter](./phase-03-temporal-client-and-one-owner-starter.md) | Completed |
| 4 | [Crash-window replay and security evidence](./phase-04-crash-window-replay-and-security-evidence.md) | Completed |
| 5 | [CI documentation and ship](./phase-05-ci-documentation-and-ship.md) | In Progress |
| 6 | [Post-audit authorization and dispatch hardening](./phase-06-post-audit-authorization-and-dispatch-hardening.md) | Completed |
| 7 | [Read-only exact workflow reconciliation](./phase-07-read-only-exact-workflow-reconciliation.md) | In Progress |
| 8 | [Runtime conformance, bounded telemetry, and CI evidence](./phase-08-runtime-conformance-bounded-telemetry-and-ci-evidence.md) | In Progress |

## Dependencies

- Upstream: V006 investigation persistence, V001/V002 outbox/dispatcher
  primitives, deterministic Java reducer, current-head CI evidence for G1.
- Runtime: PostgreSQL and Temporal; no DeepSeek key, live connector, RAG, object
  store, or write-action approval is required for this handoff.
- External gate retained: B-013 human pilot/calibration is required before the
  master Phase 9 can exit.
- Safety invariant: no prompt, evidence body, bearer token, capability, provider
  request, or secret may enter the outbox payload or Temporal workflow history.

## Acceptance Criteria

- Initial run, workflow binding, and workflow-start outbox event are one database
  transaction; injected failure leaves all three absent.
- Valid committed starts converge to one deterministic workflow ID across
  pre-start, post-start/pre-ack, lease-expiry, and duplicate-delivery crashes.
- Claim and acknowledgement are tenant-scoped, lease-fenced, and compatible with
  a named secondary datasource authenticated as the existing separate dispatcher
  database role. V012 source removes V011's inherited direct DML on workflow
  bindings, inbox rows, and canonical workflow-start outbox rows; fresh and
  upgrade tests must still prove the capability-only path under the real roles.
- An unknown schema/event type or inconsistent binding found before an RPC is
  quarantined with a bounded safe error code and visible terminal state; no
  external call occurs. This rule never converts a possibly accepted post-RPC
  outcome to terminal rejection.
- HTTP retry with the same `run_id` and request digest returns the existing run;
  conflicting reuse returns 409. Temporal mode uses an additive `202 + Location`
  contract rather than silently changing the existing synchronous `200`.
- Immutable binding includes logical Temporal cluster, namespace, workflow
  type/task queue, request digest, and authorization revision. `AlreadyStarted`
  is accepted only after target execution metadata/history proves the same input.
- Rollout freezes starts, inventories nonterminal binding-less rows, backfills
  only safe initial states, and blocks enablement until the orphan count is zero.
- Inline fixture/evaluation behavior stays green; Temporal mode is explicit,
  disabled by default, requires a compatible worker readiness proof, and cannot
  silently fall back to inline execution.
- Focused unit/integration/history-leak tests, the Phase 9 validator, full PR CI,
  and independent review pass on the exact branch head.
- G1/Phase 2 documentation is reconciled to immutable run `30327014212`, but the
  master Phase 9 remains in progress and B-013/B-017 remain visible.
- A start is admitted only while its actor remains authorized for the target
  incident and an eligible per-tenant dispatcher identity exists; the dispatcher
  rechecks a narrow database-backed authorization/deadline fence immediately
  before the Temporal RPC.
- Ambiguous Temporal transport outcomes remain retryable within the existing
  bounded deadline and attempt budget, binding acknowledgement uses the database
  clock, and the Phase 9 starter never serially holds more than one lease. If a
  retryable outcome may follow a remotely accepted RPC, local attempt, age, or
  deadline exhaustion remains bounded `PENDING` plus reconciliation-required
  alerting; it is never proof of remote rejection.
- Temporal admission and roadmap G4 enablement require B-017: V012 real-role
  containment plus a separately authorized read-only lane that can Describe the
  exact workflow and inspect its first history input without Start authority.
  The lane must have bounded `PENDING` aging/alert behavior when verification is
  unavailable or inconclusive.

## Out of Scope

- Functional Temporal worker execution of AI/tool activities, pause/resume/cancel,
  HITL approval, remediation writes, RAG, DeepSeek activation, deployment to a
  live Temporal cluster, and master Phase 9 exit.
- GHCR permission/visibility/linkage, immutable releases, and Docker Hub
  credentials; those remain separate confirmation-gated external actions.

## Current Verification Status

V010-V013 source is integrated. Exact-main PR Quality run `30699950577` passed
on commit `8092b38a652959f561d00b4922e2136139b5ae3a`: Platform API Maven verify,
the V006-to-V013 upgrade/recovery path, 32 focused Phase 9 PostgreSQL tests,
real-role containment, Compose build/health, and pinned Prometheus config
validation all succeeded. Exact-main CodeQL run `30699950547` also passed all
four language lanes with zero open actionable alerts.

Phase 7 and B-017 remain in progress, but the internal remainder is now narrow:
a compatible default-off workflow-only worker with restart/replay proof, fresh
poller admission, a non-vacuous live scrape of the real reconciliation series
with bounded labels, and secretless local Alertmanager routing evidence.
Production query-plan/latency and DR proof, production database proof, live
Temporal read-only authorization/retention conformance, and external paging
delivery remain deployment-owned evidence. No Temporal admission, G4, Phase 9
exit, Docker Hub publication, or release is claimed.

## Unresolved Questions

- Production Temporal namespace, mTLS/identity, retention, and worker deployment
  topology remain deployment-owner decisions. Defaults must stay local-invalid
  or disabled until those decisions are recorded.
- B-013 still blocks production threshold freeze and the Phase 9 exit.
- B-017 blocks Temporal admission/G4 until the internal Phase 8 conformance
  slice and the remaining production performance/DR, live Temporal
  authorization/retention, and external alert-delivery evidence are proven.

## Red Team Review

### Session — 2026-07-28

**Findings:** 15 (15 accepted, 0 rejected)
**Severity breakdown:** 3 Critical, 11 High, 1 Medium

| # | Finding | Severity | Disposition | Applied To |
|---|---|---|---|---|
| 1 | Production dispatcher role has no datasource/process wiring | Critical | Accept | Completed |
| 2 | Generic outbox permits forged workflow-start identity | Critical | Accept | Completed |
| 3 | Temporal namespace/cluster is not immutable | Critical | Accept | Phases 2-4 |
| 4 | HTTP retry is not idempotent through the current plain insert | High | Accept | Phases 2, 4 |
| 5 | Temporal mode silently changes POST completion semantics | High | Accept | Phases 2, 5 |
| 6 | Existing nonterminal runs are omitted from cutover | High | Accept | Phases 2, 4-5 |
| 7 | Starter inbox and ordering policy are absent | High | Accept | Phases 3-4 |
| 8 | Ack checks token but not live lease expiry | High | Accept | Phases 3-4 |
| 9 | Untyped tenant enumeration can starve workflow events | High | Accept | Phases 2-4 |
| 10 | Poison leaves PENDING/CREATED state misleading forever | High | Accept | Phases 2-5 |
| 11 | AlreadyStarted does not prove identical input | High | Accept | Phases 2-4 |
| 12 | Authorization revocation/stale external start lacks recheck | High | Accept (modified) | Phases 2-4 |
| 13 | Retry backoff has no attempts/age/deadline ceiling or ambiguity handoff | High | Accept (superseded) | Phases 3-4, 6 |
| 14 | Temporal can be enabled without a compatible worker | High | Accept | Phases 3-5 |
| 15 | New SDK must remain inside supply-chain gates | Medium | Accept (modified) | Phase 5 |

### Whole-Plan Consistency Sweep

- Decision deltas: dual datasource; DB-bound canonical event; immutable Temporal
  target; create-or-load API retry; additive async response; cutover inventory;
  inbox/order fence; live-lease ack; event-scoped tenant enumeration;
  pre-RPC terminal state plus post-RPC `PENDING` reconciliation; exact
  existing-execution verification; fresh worker authorization; bounded retry;
  worker-readiness admission; V012 containment/no-Start reconciliation; existing
  SBOM/OSV/dependency-review gates retained.
- Updated overview, acceptance, persistence, dispatcher, negative-test, CI,
  rollout, rollback, and documentation sections. Searches found no remaining
  V005 migration, obsolete Python `app/**` workflow, unrestricted
  `USE_EXISTING`, single-datasource, or documentation-only worker-guard claim in
  this plan.
- B-017 is an explicit unresolved safety condition: V012 removes the V011
  direct-DML bypass and prevents ambiguous post-RPC exhaustion from becoming
  `REJECTED`; V013 now supplies the function-only reconciliation/alert source
  and corrected local fresh/upgrade/four-failpoint database contracts.
  Revision-bound database-gate execution, merged-head
  Maven/Docker/CI/performance/DR, live authorization/retention, scrape, and
  external alert delivery proof remain absent. Production cluster identity,
  mTLS ownership, compatible worker implementation, B-013, and B-017 remain
  explicit gates.

### Session — 2026-08-01

**Findings:** 3 (2 accepted, 1 rejected with exact-main evidence)

| # | Finding | Disposition | Applied To |
|---|---|---|---|
| 1 | Phase 4 history proof allegedly depends on the new worker | Rejected: `TemporalInvestigationWorkflowHistoryLeakTest` already uses the official test environment for the existing client/start contract, and exact-main Maven verify passed it; worker restart history remains a distinct Phase 8 proof | Phase 4 clarified |
| 2 | Worker-only process had no explicit isolated bootstrap owner | Accepted | Phase 8 Lane A/controller ownership and context acceptance |
| 3 | Durable-history leak scan omitted headers, search attributes, failure details, and cancellation reasons | Accepted | Phase 8 security and test acceptance |

The review also confirmed non-overlapping Lane A/Lane B/controller ownership,
SDK 1.35.0 poller identity/build/timestamp feasibility, CI-local versus external
evidence separation, continued B-017/Phase 9 blocking, and the Phase 10 hold.
