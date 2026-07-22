---
phase: 7
title: "Thin Evidence-backed Incident Vertical Slice"
status: in_progress
priority: P1
dependencies: [4, 5, 6]
effort: "2-3 weeks"
---

# Phase 7: Thin Evidence-backed Incident Vertical Slice

## Objective

Prove one end-to-end, read-only incident investigation flow that starts from a real incident record, collects bounded evidence through the Tool Gateway, calls the AI runtime for structured analysis, persists a cited RCA and investigation timeline, and renders the run in a thin operator UI without exposing secrets or chain-of-thought.

The slice must use one pure deterministic investigation state machine. Phase 7 supplies an in-process runner adapter; Phase 9 later replaces only that runner with Temporal, avoiding a second domain model or route.

## Non-Goals

- No durable long-running workflow or resume-after-crash semantics beyond bounded request retry; Phase 9 owns durable orchestration.
- No write actions, approval UX, rollback UX, or autonomous remediation.
- No broad multi-incident dashboard polish. Only the minimum UI to inspect one live investigation path.
- No RAG-backed document search beyond explicit runbook/source evidence already returned by connectors.

## Source Anchors

- `plans/260719-1747-opsmind-ai-production-platform/research/master-prompt-requirements-traceability.md:37-46`
- `plans/260719-1747-opsmind-ai-production-platform/research/master-prompt-requirements-traceability.md:63-66`
- `plans/260719-1747-opsmind-ai-production-platform/research/researcher-02-delivery-evaluation.md:12-14`
- `plans/260719-1747-opsmind-ai-production-platform/research/researcher-02-delivery-evaluation.md:76-84`
- `plans/260719-1747-opsmind-ai-production-platform/research/researcher-02-delivery-evaluation.md:152-157`
- `plans/260719-1747-opsmind-ai-production-platform/reports/brainstorm-report.md:12-14`
- `plans/260719-1747-opsmind-ai-production-platform/reports/brainstorm-report.md:141-157`
- `plans/260719-1747-opsmind-ai-production-platform/reports/brainstorm-report.md:199-216`

## Prerequisites and Dependency Graph

- Hard blockers:
  - Phase 5 must expose stable AI runtime contracts for `complete`, `need_more_evidence`, and `abstain`.
  - Phase 6 must expose stable Tool Gateway execution contracts and at least the connector families required by the chosen slice scenario.
  - Phase 4 incident/audit ledger must already persist incidents, evidence references, and timeline events.
- Scenario blocker:
  - Choose one representative incident class before implementation. Recommended first slice: deployment-caused latency regression because it exercises observability, deployment, source, and runbook evidence in one path.
- Downstream:
  - Phase 8 uses this slice as the baseline system-under-test for deterministic benchmark scoring.
- File ownership:
  - This phase owns orchestration and operator surfaces only: `services/platform-api/**`, `apps/web/**`, slice-specific fixtures, and cross-service E2E tests.
  - It should not modify `services/ai-runtime/**` or `services/tool-gateway/**` if Phases 5 and 6 expose adequate contracts.

## Data Flow

1. Operator or test fixture opens an incident in the platform API.
2. Platform API creates an investigation run record with budgets, prompt/schema versions, and a bounded scenario context.
3. Platform API calls AI runtime for an initial structured analysis and tool plan.
4. AI runtime returns either:
   - final RCA immediately, or
   - a bounded set of requested read-only tool intents.
5. Platform API executes each allowed tool intent through Tool Gateway and appends normalized evidence to the incident timeline.
6. Platform API re-calls AI runtime with evidence references and receives a final `complete`, `abstain`, or `need_more_evidence` outcome.
7. Platform API persists the result, evidence links, missing-evidence list, counter-evidence, confidence, token/cost summary, and audit chain.
8. `apps/web` renders the incident investigation timeline, evidence list, tool calls, and final RCA via SSE or polling without showing raw chain-of-thought.

## Architecture and Design Decisions

- Thin orchestration first: implement a pure command/event/state reducer with no I/O, plus a bounded in-process runner inside `services/platform-api`. Temporal is deferred, but the domain state machine, stop reasons and API routes are not rewritten in Phase 9.
- Strictly read-only slice: any tool intent classified as write-capable is denied and surfaced as unsupported in this phase.
- RCA credibility over verbosity: every final claim shown in UI must link to at least one evidence item. Unsupported claims are either omitted or represented as missing evidence.
- Fail-visible UX: operator sees run status, denial reasons, tool failures, missing evidence, and abstentions. Silent fallback to "success" is forbidden.
- Per-run budgets:
  - max investigation duration
  - max tool rounds
  - max token budget
  - max cost budget
  - max evidence items
- No chain-of-thought rendering: UI may show rationale summaries generated by the structured response, but never raw hidden provider reasoning.
- Additive integration only: keep existing incident CRUD and audit flows intact; investigation is a new capability attached to incidents, not a rewrite of incident management.
- G3 is not fixture-only: the chosen scenario uses the Phase 6 live non-production connector with synthetic data, while deterministic fixtures remain the repeatable test path.

## File Inventory

### CREATE

- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/api/InvestigationRunController.java`
- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/application/InvestigationRunService.java`
- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/application/InvestigationOrchestrator.java`
- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/domain/InvestigationStateMachine.java`
- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/domain/InvestigationCommand.java`
- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/domain/InvestigationEvent.java`
- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/integration/AiRuntimeClient.java`
- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/integration/ToolGatewayClient.java`
- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/projection/InvestigationProjectionAssembler.java`
- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/projection/InvestigationRunReadModel.java`
- `services/platform-api/src/main/resources/db/migration/V004__investigation_slice.sql`
- `services/platform-api/src/test/java/ai/opsmind/platform/investigation/`
- `packages/contracts/json-schema/investigation/v1/investigation-run.schema.json`
- `packages/contracts/json-schema/investigation/v1/investigation-view.schema.json`
- `packages/contracts/fixtures/investigation/`
- `apps/web/src/app/(ops)/incidents/[incidentId]/investigation/page.tsx`
- `apps/web/src/components/investigation-run/*`
- `apps/web/src/lib/api/investigation-client.ts`
- `apps/web/tests/e2e/thin-evidence-backed-incident.spec.ts`
- `tests/fixtures/vertical-slice/deployment-latency-regression/*`
- `infra/compose/profiles/phase-07-vertical-slice.compose.yaml`

### MODIFY

- Existing incident detail route and API router files only if Phase 4 created them and only to add links/navigation to the investigation view.

### DELETE

- None.

## Implementation Tasks

1. Lock the slice scenario and acceptance boundaries.
   - Choose one incident class.
   - Define exactly which connectors are allowed.
   - Define the maximum rounds, budgets, and abstain rules.
   - Define the minimum evidence required before the model is allowed to output a final RCA.
2. Add investigation run records and projection contract.
   - Persist run status, started/ended timestamps, prompt/schema/model version, budgets, token/cost totals, and final outcome.
   - Projection contract must support list of tool calls, evidence references, missing evidence, counter-evidence, and confidence.
3. Implement the pure investigation state machine and bounded in-process runner inside platform API.
   - Commands/events and stop reasons contain no network/database/time/random calls.
   - Runner performs I/O through ports and applies only validated events to the same reducer Phase 9 will reuse.
   - Start run.
   - Call AI runtime.
   - Execute read-only tool intents through Tool Gateway.
   - Rehydrate evidence references.
   - Re-call AI runtime until `complete`, `abstain`, or budget exhaustion.
   - Write timeline and audit entries at each step.
4. Add explicit no-progress and duplicate controls.
   - If the model requests the same tool with the same arguments repeatedly, stop and surface `no_progress`.
   - If evidence arrives but does not increase state, stop after configured repeat threshold.
   - If the run exceeds time/token/tool budgets, end with a visible budget outcome.
5. Build the thin operator UI.
   - Incident page exposes an "Investigate" action only for allowed roles.
   - Investigation view shows run status, evidence cards, tool call history, missing evidence, final RCA, confidence, and usage/cost summary.
   - UI must display explicit failure/abstain states and correlation IDs for support.
6. Add the local integration profile and seed fixture.
   - Compose profile wires platform API, AI runtime, Tool Gateway, and seeded incident fixture together.
   - Seed scenario must be deterministic enough to reproduce later in Phase 8; a separate G3 path points the same slice at the selected live non-production connector with synthetic data.
7. Add end-to-end and failure-path coverage.
   - Happy path for the chosen slice.
   - Tool denial path.
   - AI runtime abstain path.
   - Budget-exceeded path.
   - Duplicate-tool/no-progress path.

## Migration, Backward Compatibility, and Rollback

- Backward compatibility strategy:
  - Existing incident CRUD remains additive; investigation endpoints and UI routes are new.
  - Investigation buttons can stay hidden behind `INVESTIGATION_V1_ENABLED` until slice validation completes.
  - Existing audit readers keep working because timeline additions are append-only.
- Migration path:
  - Add investigation run tables or projections.
  - Deploy API and UI with feature flag off.
  - Enable for internal test tenants only after end-to-end smoke passes.
- Rollback:
  - Disable `INVESTIGATION_V1_ENABLED`.
  - Revert API/UI artifacts while leaving run history tables intact for forensic review.
  - If UI regresses under pressure, keep API live but hide the entrypoint until a fix lands.

## Test and Evidence Matrix

| Scope | Coverage | Evidence artifact |
|---|---|---|
| Unit | orchestrator state transitions, duplicate-tool detection, budget enforcement, projection assembly | unit CI report |
| Contract | platform API to AI runtime and Tool Gateway payload compatibility | client contract report |
| Integration | seeded incident happy path, denial path, abstain path, no-progress path | integration report with timeline snapshots |
| Live non-production | same synthetic scenario through selected real connector and scoped identity | redacted connector/product trace |
| E2E UI | operator starts run, sees evidence, sees cited RCA, sees failure states | Playwright report |
| Security | role-based entrypoint, hidden chain-of-thought, no write-capable tool path | security regression report |
| Observability | trace propagation across platform API, AI runtime, and Tool Gateway | trace ID correlation artifact |

## Quantitative Exit Gate

- One representative incident flow completes end-to-end with a cited RCA and visible evidence in the UI.
- `AI-03` structured hypotheses/counter-evidence/missing-evidence/actions/confidence contract is schema-valid and grounded.
- `100%` of final RCA claims shown in the UI link to at least one persisted evidence item.
- `0` write-capable or unauthorized tool executions occur in the slice suite.
- Standard investigation `p95` stays under `120 seconds` for the slice benchmark, subject to provider availability.
- Happy path, abstain path, denial path, and budget-exceeded path all have automated coverage.
- Internal test tenant can replay the same seeded slice without manual data cleanup.
- The selected live non-production connector completes the synthetic slice; a fixture-only run cannot close G3.

## Risks and Mitigations

| Risk | Likelihood x Impact | Mitigation |
|---|---|---|
| Short-lived orchestrator becomes a hidden long-running workflow | Medium x High | strict slice budget, fail-visible outcomes, defer durability to Phase 9 |
| UI exposes raw reasoning or sensitive tool output | Medium x High | projection whitelist, no chain-of-thought field, redaction checks |
| Slice scenario is too broad and delays learning | High x Medium | one incident class, bounded connectors, explicit non-goals |
| Evidence does not actually support the final RCA | Medium x High | evidence-link requirement, counter-evidence section, abstain when insufficient |
| API clients need changes in AI runtime or Tool Gateway late in the phase | Medium x Medium | require stable contracts from Phases 5 and 6 before Phase 7 starts |

## Unresolved Decisions

- Which incident class becomes the canonical first slice if deployment-latency regression is not available in target environments.
- Whether the first UI update stream should use SSE or polling based on Phase 2/4 frontend infrastructure choices.
- Whether investigation runs are tenant-admin only in the first release or available to standard on-call roles.
