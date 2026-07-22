---
phase: 9
title: "Durable Investigation Workflow"
status: pending
priority: P1
dependencies: [4, 5, 6, 7, 8]
effort: "2-3 weeks"
---

# Phase 9: Durable Investigation Workflow

## Context Links

- [Master plan](./plan.md)
- Product framing and runtime boundaries: `plans/260719-1747-opsmind-ai-production-platform/reports/brainstorm-report.md:12`, `:104`
- Investigation workflow pattern and Temporal semantics: `plans/260719-1747-opsmind-ai-production-platform/research/researcher-01-architecture-security.md:9`, `:55`, `:68`, `:116`
- Required obligations: `plans/260719-1747-opsmind-ai-production-platform/research/master-prompt-requirements-traceability.md:41`, `:42`, `:44`, `:78`
- Delivery order constraint: `plans/260719-1747-opsmind-ai-production-platform/reports/brainstorm-report.md:210`, `:212`

## Overview

Deliver the first durable incident investigation engine: start, pause, resume, abstain, and finish a long-running investigation without losing state across worker restarts, network faults, or human wait states. This phase is where OpsMind stops being a thin request/response assistant and becomes a governed investigation system of record.

## Objective

Create deterministic Temporal-backed investigation orchestration that can:

- persist run state, hypotheses, evidence references, budgets, and decision checkpoints
- separate deterministic workflow code from non-deterministic tool/model activities
- expose queryable projections to the platform API without leaking raw workflow history
- fail closed when evidence is insufficient, duplicated, stale, or over budget

## Non-Goals

- No write-capable remediation in this phase; phase 11 owns approval-bound writes
- No broad knowledge ingestion or vector retrieval; phase 10 owns RAG lifecycle
- No operator-facing UI completion; phase 12 owns screens and navigation
- No destructive Temporal migration strategy beyond additive workflow versioning

## Prerequisites and Dependencies

- Must follow Phases 4-8 and their compatible evidence manifests/contracts as declared in frontmatter and the master dependency graph.
- Must inherit the bounded-context split of platform API, AI runtime, and Tool Gateway from `brainstorm-report.md:106`, `:107`, `:108`.
- Must enforce the invariant that workflow state survives failure and every write path remains disabled until approval/remediation arrives, per `master-prompt-requirements-traceability.md:42`, `:78` and `brainstorm-report.md:214`.
- Entry validation must confirm upstream evidence manifests and compatible contract versions from Phases 4-8; missing runtime paths mean a prerequisite is incomplete, not permission to create a parallel bootstrap.
- This phase blocks phases 10, 11, and 12 because they depend on stable investigation-run IDs, projection schemas, workflow state queries, and additive event contracts.

## Architecture and Design Decisions

1. Temporal owns investigation control flow, timers, retries, and human wait states; HTTP handlers in the platform API only start workflows and read projections. Source basis: `researcher-01-architecture-security.md:23`, `:55`, `:118`.
2. Workflow code must be deterministic. Model calls, tool calls, retrieval, parsing, and persistence side effects run only in Activities. Source basis: `researcher-01-architecture-security.md:9`, `:119`.
3. Workflow history stores artifact IDs, evidence IDs, and projection references, never raw secrets, raw prompt content, or unbounded tool payloads. Source basis: `brainstorm-report.md:130`, `researcher-01-architecture-security.md:123`.
4. Platform API owns authoritative incident and projection reads; Temporal history is not a user-facing datastore. Source basis: `researcher-01-architecture-security.md:54`, `:55`.
5. The investigation loop must terminate via explicit stop reasons: verified root cause, insufficient evidence, duplicate/no-progress, budget exhaustion, or operator cancel. Source basis: `master-prompt-requirements-traceability.md:42` and `brainstorm-report.md:145`.
6. Workflow evolution must use additive event/state changes plus Temporal patch/version markers so old executions can replay safely during rollout and rollback. This is an implementation constraint inferred from `researcher-01-architecture-security.md:68`, `:116`, `:156`.
7. The Phase 7 pure state machine remains authoritative. Temporal is a runner adapter around its commands/events/stop reasons, not a second investigation domain model.
8. The outbox dispatcher is the sole Temporal starter. Workflow ID derives from immutable investigation-run ID; inbox/reconciliation closes crash, acknowledgement, duplicate and ordering gaps.
9. Worker Build IDs/version routing or immutable versioned task queues keep open histories on compatible code; sanitized golden histories from every release replay in CI.

## Explicit Data Flow

1. Platform API receives `StartInvestigation` and atomically persists `investigation_run` plus a versioned outbox event; it never directly starts Temporal.
2. The sole outbox dispatcher claims the event and starts Temporal with a deterministic Workflow ID derived from `investigationRunId`; already-started conflicts reconcile instead of duplicating work.
3. Workflow asks AI runtime Activities to generate hypotheses and evidence plans; activity output is schema-validated before state mutation.
4. Workflow invokes read-only Tool Gateway activities to gather evidence. Tool results are normalized into evidence artifacts and persisted by ID, not embedded verbatim in workflow history.
5. Workflow scores supporting and counter-evidence, updates projection rows, and emits audit/timeline events after every major checkpoint.
6. Platform API exposes projection/query endpoints for incident detail, live status, and run summaries; phase 12 will consume those endpoints through SSE or polling adapters.
7. If workflow hits a stop condition, it writes final state, abstains or concludes, and emits machine-readable reason codes for simulator/evaluation replay.

## File Ownership

- Exclusive ownership for this phase: investigation workflow contracts, Temporal bootstrap, workflow projections, workflow tests, and phase-specific DB migration.
- Shared files allowed only as additive changes under canonical `packages/contracts/**` and the existing Phase 7 state-machine package.
- Explicitly out of scope for this phase: `apps/web/**`, remediation executors, and knowledge-ingestion screens.

## File Inventory

| Action | Path | Purpose | Notes |
|---|---|---|---|
| MODIFY | `packages/contracts/openapi/opsmind-v1.yaml` | Add investigation run start/query/stop endpoints and projection payloads | Additive only |
| MODIFY | `packages/contracts/json-schema/investigation/v1/investigation-run.schema.json` | Add durable run/version/build metadata | Versioned payload |
| CREATE | `packages/contracts/json-schema/investigation/v1/investigation-decision.schema.json` | Stop reason, confidence, and evidence summary | Simulator/eval reuse |
| CREATE | `packages/contracts/fixtures/temporal-histories/` | Sanitized golden histories by released workflow version | Replay gate |
| MODIFY | `services/platform-api/pom.xml` | Add Temporal client and projection dependencies | Stop if file missing; phase 2 blocker |
| CREATE | `services/platform-api/src/main/resources/db/migration/V005__durable_investigation_workflow.sql` | Extend run; add checkpoint, signal, inbox and projection state | Additive migration |
| CREATE | `services/platform-api/src/main/java/ai/opsmind/platform/investigation/application/InvestigationWorkflowCommandService.java` | Starts, cancels, and resumes workflows | Thin orchestration façade |
| CREATE | `services/platform-api/src/main/java/ai/opsmind/platform/investigation/query/InvestigationRunProjectionRepository.java` | Query model for UI/API reads | Read-only projection |
| CREATE | `services/platform-api/src/main/java/ai/opsmind/platform/investigation/messaging/InvestigationWorkflowStarter.java` | Sole outbox-to-Temporal starter with inbox and orphan reconciliation | Deterministic Workflow ID |
| MODIFY | `services/platform-api/src/main/java/ai/opsmind/platform/investigation/api/InvestigationRunController.java` | Preserve Phase 7 routes; switch runner behind port | No duplicate route |
| MODIFY | `services/ai-runtime/pyproject.toml` | Add Temporal SDK and deterministic workflow test deps | Stop if file missing; phase 2 blocker |
| CREATE | `services/ai-runtime/app/workflows/investigation_workflow.py` | Temporal workflow definition | Deterministic only |
| CREATE | `services/ai-runtime/app/activities/investigation_activities.py` | AI/tool orchestration activities | Non-deterministic work |
| CREATE | `services/ai-runtime/app/activities/projection_callbacks.py` | Writes projection deltas back through stable contracts | No direct UI logic |
| CREATE | `services/ai-runtime/app/clients/platform_control_plane_client.py` | Authorize tool intents through platform; no Gateway credential | Additive only |
| CREATE | `services/platform-api/src/test/java/ai/opsmind/platform/investigation/InvestigationWorkflowCommandServiceIT.java` | Start/resume/cancel integration tests | Temporal test environment |
| CREATE | `services/ai-runtime/tests/workflows/test_investigation_workflow.py` | Replay, determinism, and stop-condition tests | Required gate |
| DELETE | `None planned` | Avoid churn while workflow contracts are stabilizing | If deletion seems necessary, reopen design review |

## Implementation Tasks

1. Define investigation workflow contracts.
Contract: shared schema must include `investigationRunId`, `incidentId`, `tenantId`, `attempt`, `status`, `stopReason`, `budgetSnapshot`, and `evidenceRefs`.
Behavior: both Java and Python runtimes reject payloads with unknown required fields or missing tenant scope.

2. Add persistent workflow projection storage.
Contract: schema must separate append-only checkpoints from the current query projection; checkpoints remain auditable while projections stay compact.
Behavior: every state transition writes one checkpoint row and updates one current projection row in the same local transaction.

3. Implement workflow start, cancel, and resume façade in the platform API.
Contract: API must be idempotent on repeated `StartInvestigation` for the same incident + idempotency key.
Behavior: API writes only local command/outbox state. The dispatcher alone starts/signals workflows; cancel never kills worker threads, and resume creates a new attempt only after a terminal or paused state.

4. Close the DB-to-Temporal handoff.
Contract: event envelope carries event/tenant/aggregate IDs, aggregate sequence, causation/correlation, schema version and digest; inbox uniqueness and a gap/out-of-order policy are mandatory.
Behavior: crashes before/after Temporal start, acknowledgement loss, duplicates and reordering converge to one workflow and one logical transition.

5. Implement deterministic Temporal workflow adapter.
Contract: workflow module may depend only on deterministic state helpers, typed signals/queries, and version markers.
Behavior: all randomness, clocks, provider calls and network I/O move into Activities; replay completes without nondeterminism. The adapter applies the existing Phase 7 reducer rather than reimplementing stop logic.

6. Encode bounded-loop rules and abstention policy.
Contract: stop reasons are enumerated and externally visible; no hidden "best effort" continuation after budgets or no-progress thresholds trigger.
Behavior: duplicate evidence, contradictory evidence, and repeated tool plans decrement progress budget and can force abstain.

7. Emit projection, audit, and timeline updates after each major checkpoint.
Contract: projection updates are additive and redact secrets; audit rows include actor type, workflow ID, incident ID, tenant ID, and correlation ID.
Behavior: UI consumers can reconstruct live investigation status from projection + timeline without reading workflow history.

8. Add failure-recovery and provider-continuation behavior before broad adoption.
Contract: worker restart, Activity timeout, and duplicate delivery must be test-covered before the phase can close.
Behavior: retries are bounded and idempotent. A DeepSeek multi-turn exchange is one bounded Activity or restarts from persisted evidence references; workflow progress never depends on process-memory `reasoning_content`.

9. Implement Phase 7 cutover and workflow versioning.
Contract: freeze starts, inventory/finish/import active runs, preserve IDs/projections, route old/new histories by Worker Build ID or versioned task queue, and version signal/query/update/Activity payloads.
Behavior: sanitized golden histories from every released version replay on upgrade and rollback, including open and approval-wait histories.

10. Wire simulator/evaluation hooks to final stop-state outputs.
Contract: simulator can replay run summaries and compare stop reason, evidence coverage, and final RCA structure against ground truth.
Behavior: every completed run stores machine-readable evaluation metadata alongside human-readable summary fields.

## Migration Strategy

- Roll out DB/inbox migration and sole starter first, perform the Phase 7 active-run cutover, then deploy version-routed workers and enable the existing start endpoints.
- Keep API changes additive: new investigation-run resources and new fields on incident detail responses only.
- Create a dedicated Temporal task queue for investigation workflows so worker drain/rollback does not affect unrelated background jobs.
- Use Temporal patch/version markers plus Worker Build IDs or immutable task queues from the first release; define compatibility duration and continue-as-new policy.

## Rollback Plan

- Disable new investigation start endpoints behind a feature flag; leave existing runs queryable.
- Keep workers alive long enough to drain or explicitly pause active workflows; never redeploy incompatible workflow code without version markers.
- If migration issues appear, apply forward-fix SQL and freeze new starts; do not drop checkpoint tables that already back audit evidence.
- Revert additive API fields only after consumers stop reading them; compatibility break is not allowed mid-rollout.

## Test and Evidence Matrix

| Layer | Required checks | Evidence artifact |
|---|---|---|
| Unit | state reducer, stop reasons, budget decrement rules, duplicate/no-progress detection | `services/ai-runtime/tests/workflows/test_investigation_workflow.py` |
| Integration | API start/resume/cancel idempotency, projection writes, outbox emission | `InvestigationWorkflowCommandServiceIT.java` |
| Handoff/order | crash at DB/dispatch/start/ack boundaries, duplicate/out-of-order events and orphan reconciliation | outbox/inbox fault report |
| Workflow durability | worker kill/restart/resume, signal after restart, activity timeout recovery | Temporal replay/test-environment run log |
| Upgrade/rollback | Phase 7 cutover and golden histories on old/new/rolled-back workers | worker-version routing report |
| Security | no secret payloads in workflow history, tenant scope always present, unauthorized cancel rejected | audit/history inspection report |
| Evaluation | simulator replay consumes stop-state payloads, abstain reasons parse cleanly | phase-8 evaluation artifact update |
| Performance | projection query latency under load, restart recovery timing | benchmark report with raw timings |

## Quantitative Exit Gate

- [ ] `WF-01` evidence exists: kill/restart/resume scenario passes at least 20 consecutive runs with no lost state or duplicate terminal outcomes.
- [ ] `AI-04` evidence exists: bounded-loop suite covers verified root cause, abstain, duplicate/no-progress, budget exhausted, and operator cancel paths with 100% pass rate.
- [ ] Projection freshness stays within 2 seconds p95 from workflow checkpoint write to API-read visibility in the local/staging test harness.
- [ ] Secret-leak inspection over sampled workflow histories finds zero raw secrets, zero raw provider chain-of-thought blobs, and zero unrestricted tool payload dumps.
- [ ] Additive API contract tests pass with no breaking changes to existing incident endpoints.
- [ ] Every DB-to-Temporal crash/ack/duplicate/reorder case converges to one deterministic Workflow ID and one logical transition; no committed run remains orphaned.
- [ ] Golden histories for every released build replay on compatible workers through upgrade and rollback, including paused and future approval-wait histories.

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---:|---:|---|
| Non-deterministic code slips into workflow module | Medium | High | Enforce import boundaries, replay tests, and code review checklist on workflow files |
| Projection drift from workflow truth | Medium | High | Single write path for checkpoints, reconciliation job, and integration test that replays history against projections |
| Retry storm after worker/tool instability | Medium | Medium | Bounded retries, jitter, task-queue isolation, and duplicate-delivery guards |
| Sensitive payloads land in history or audit | Low | Critical | Store references only, redact at activity boundary, and add secret-scan assertions |
| Phase 2/5 foundations are still missing | High | High | Hard block phase start if manifests, task queues, or incident domain contracts are absent |

## Unresolved Decisions

- Temporal Cloud vs self-hosted Temporal remains unresolved per `researcher-01-architecture-security.md:181`.
- Exact pause/resume semantics for operator-driven edits to hypotheses are still open; this phase should support pause and restart, not in-place mutable edits.
- The final threshold values for no-progress and budget exhaustion should come from phase 8 benchmark calibration, not gut feel.
