---
title: Phase 9 Temporal Investigation Handoff
description: >-
  Close G1 evidence drift and add an atomic, tenant-safe outbox-to-Temporal
  workflow-start handoff without duplicating the Java investigation reducer.
status: in-progress
priority: P1
branch: feature/temporal-investigation-handoff
tags:
  - temporal
  - durability
  - outbox
  - multi-tenant
  - phase-09
blockedBy: []
blocks:
  - phase-09-durable-investigation-worker
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
| 2 | [Atomic workflow-start persistence](./phase-02-atomic-workflow-start-persistence.md) | In Progress |
| 3 | [Temporal client and one-owner starter](./phase-03-temporal-client-and-one-owner-starter.md) | In Progress |
| 4 | [Crash-window replay and security evidence](./phase-04-crash-window-replay-and-security-evidence.md) | In Progress |
| 5 | [CI documentation and ship](./phase-05-ci-documentation-and-ship.md) | In Progress |
| 6 | [Post-audit authorization and dispatch hardening](./phase-06-post-audit-authorization-and-dispatch-hardening.md) | In Progress |
| 7 | [Read-only exact workflow reconciliation](./phase-07-read-only-exact-workflow-reconciliation.md) | In Progress |

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

- Full Temporal worker execution of AI/tool activities, pause/resume/cancel,
  HITL approval, remediation writes, RAG, DeepSeek activation, deployment to a
  live Temporal cluster, and master Phase 9 exit.
- GHCR permission/visibility/linkage, immutable releases, and Docker Hub
  credentials; those remain separate confirmation-gated external actions.

## Current Verification Status

V010-V012 source is integrated. The current integration source passes the
Phase 9 static validator; focused rollback-only PostgreSQL probes pass for V012
migration application, canonical row visibility, hidden-predecessor ordering,
unsafe role-membership rejection, and V012 transaction application. Regression
source now covers V011 legacy ambiguity normalization and preflight-before-decode
parking. Corrected local fresh/upgrade/atomicity reconciliation database proofs
are described below; the capacity guard still blocks exact-head
Maven/Docker/CI, latency, DR, and production PostgreSQL proof.

Phase 7 source is integrated: V013 creates a separate function-only reconciler
login/owner and exact three-function database surface; the default-off observer
uses only Describe plus one first-history read; aggregate metrics and seven
alert rules use the internal management port. A disposable PostgreSQL
V001-V013 real-role contract passes 55 corrected checks including cleanup, including
the global exact-three surface, full database outcome matrix, lease/retention
boundaries, and four rollback failpoints. A local V012-to-V013
exact-three/PUBLIC-deny upgrade and lightweight `javac`/static validators pass.
The database contract and V006-to-V013 upgrade/recovery path are wired into PR
Quality, but the updated job has no revision-bound result.

Phase 7 and B-017 remain in progress. The `D:` capacity guard blocks exact-head
Maven, Docker, performance/DR, and CI gates.
Production namespace retention and read-only credential conformance are absent.
Pinned `promtool` plus live scrape and an external Alertmanager receiver/page
delivery proof are also absent. No Temporal admission, G4, Phase 9 exit,
Docker Hub publication, or release is claimed.

## Unresolved Questions

- Production Temporal namespace, mTLS/identity, retention, and worker deployment
  topology remain deployment-owner decisions. Defaults must stay local-invalid
  or disabled until those decisions are recorded.
- B-013 still blocks production threshold freeze and the Phase 9 exit.
- B-017 blocks Temporal admission/G4 until the wired database gate has a
  revision-bound run and remaining merged-head Maven/Docker/CI/performance/DR,
  live Temporal authorization/retention, scrape, and external alert-delivery
  evidence is proven.

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
