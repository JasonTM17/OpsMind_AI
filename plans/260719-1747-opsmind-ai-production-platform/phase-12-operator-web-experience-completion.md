---
phase: 12
title: "Operator Web Experience Completion"
status: pending
priority: P1
dependencies: [3, 7, 8, 9, 10, 11]
effort: "2-3 weeks"
---

# Phase 12: Operator Web Experience Completion

## Context Links

- [Master plan](./plan.md)
- Product/runtime framing for Next.js operator experience: `plans/260719-1747-opsmind-ai-production-platform/reports/brainstorm-report.md:14`, `:106`, `:145`, `:205`
- UI obligations and DoD: `plans/260719-1747-opsmind-ai-production-platform/research/master-prompt-requirements-traceability.md:63`, `:64`, `:65`, `:120`, `:151`
- Delivery posture for thin UI early and full UI later: `plans/260719-1747-opsmind-ai-production-platform/research/researcher-02-delivery-evaluation.md:101`, `:120`, `:189`
- Approval and workflow read semantics that UI must respect: `plans/260719-1747-opsmind-ai-production-platform/research/researcher-01-architecture-security.md:55`, `:69`, `:121`

## Overview

Finish the operator-facing web product on top of the bounded backend: authenticated dashboard, incident list/detail/timeline, live investigation state, evidence and citation panels, approvals, knowledge lifecycle, evaluation, usage/cost, audit, and IAM views. The UI must remain technically opinionated and responsive under incident pressure while never becoming an authorization bypass or a chain-of-thought leak surface.

## Objective

Deliver the complete phase-1-to-phase-11 operator experience so that an on-call or incident commander can:

- sign in and see role-scoped operations immediately
- inspect incident state, hypotheses, evidence, citations, and live workflow progress
- approve or reject exact write actions with full diff and risk context
- manage knowledge sources, inspect evaluation quality, and review cost/audit posture

## Non-Goals

- No UI-owned business authority; backend APIs remain the only decision-makers
- No raw reasoning traces, provider `reasoning_content`, secrets, or unrestricted tool output in the browser
- No extra frontend feature breadth beyond the required incident, approval, knowledge, evaluation, usage, audit, and IAM screens
- No backend contract redesign in this phase except additive client-safe fields escalated from preceding phases

## Prerequisites and Dependencies

- Must follow Phases 3, 7, 8, 9, 10 and 11 and their compatible evidence manifests/contracts as declared in frontmatter and the master graph.
- Must preserve `UI-01`, `UI-02`, and `UI-03` from `master-prompt-requirements-traceability.md:63`, `:64`, `:65`.
- Must consume workflow reads through projections/queries rather than direct workflow history, per `researcher-01-architecture-security.md:55`, `:121`.
- Entry validation confirms the Phase 2 app shell, Phase 7 thin routes and compatible Phase 8-11 projections/contracts; missing outputs block entry instead of creating replacement routes.
- This phase should not run in parallel with backend phases touching shared contracts unless all contract changes are additive and reviewed first.

## Architecture and Design Decisions

1. Next.js App Router stays a thin operator shell over platform/API projections, matching the runtime boundary in `brainstorm-report.md:106`. UI composes read views and command submissions; it does not own domain state.
2. Live investigation updates use SSE or equivalent streaming endpoint over backend projections, not direct WebSocket access to worker internals. This is inferred from `brainstorm-report.md:106` and `researcher-01-architecture-security.md:121`.
3. Capability rendering comes from server-provided role/capability matrix; hiding controls in the UI is convenience, not authorization. Source basis: `master-prompt-requirements-traceability.md:63`, `:64`.
4. Every evidence-backed claim shown to operators must include citation metadata and provenance badges; the UI cannot render uncited RCA summaries as authoritative. Source basis: `brainstorm-report.md:12`, `master-prompt-requirements-traceability.md:115`.
5. Approval screens must render exact target, normalized params, resource witness, cited evidence, dry-run result, risk class, and expiry together, reflecting phase 11 exact-action constraints.
6. Accessibility and incident pressure matter more than decorative breadth: keyboard navigation, no hidden critical actions, fast route load, responsive layouts, and explicit stale-data markers are mandatory. Source basis: `master-prompt-requirements-traceability.md:151` and `researcher-02-delivery-evaluation.md:189`.

## Explicit Data Flow

1. User signs in through the selected Phase 3 OIDC/BFF profile and receives server-side session/capability context; refresh credentials never enter client JavaScript.
2. Dashboard route fetches incident summaries, workflow health, pending approvals, knowledge-source health, and token/cost rollups from platform API projections.
3. Incident detail route fetches incident graph, timeline, latest hypotheses, evidence list, citation bundles, and live run snapshot.
4. Live investigation panel subscribes to streaming projection updates; UI patches client state only from backend event payloads.
5. Approval route fetches pending approval details, dry-run previews, and expiry metadata; decisions POST back to platform API and never call Tool Gateway directly.
6. Knowledge, evaluation, usage, audit, and IAM routes fetch specialized read models and submit typed commands through backend APIs only.
7. Browser telemetry and logs are redacted; no page serializes secret values or raw provider reasoning into HTML, JSON payloads, or client logs.

## File Ownership

- Exclusive ownership for this phase: `apps/web/**`, frontend test files, and any frontend-only generated client wrappers.
- Shared additive files only: client-safe contract snapshots or generated API typings under `packages/contracts/` if the project chooses generated clients.
- Explicitly out of scope: backend domain logic, Tool Gateway executors, and workflow state machines.

## File Inventory

| Action | Path | Purpose | Notes |
|---|---|---|---|
| MODIFY | `apps/web/package.json` | Add route/testing/accessibility dependencies | Stop if file missing; upstream blocker |
| MODIFY | `apps/web/next.config.js` | Configure CSP-safe assets, headers, and route flags | Additive only |
| CREATE | `apps/web/src/app/(ops)/layout.tsx` | Operator shell, navigation, and server capability bootstrap | No business logic |
| CREATE | `apps/web/src/app/(ops)/dashboard/page.tsx` | Overview with incidents, approvals, knowledge health, and cost summaries | Primary landing screen |
| CREATE | `apps/web/src/app/(ops)/incidents/page.tsx` | Incident list with filters and severity views | Role-aware |
| CREATE | `apps/web/src/app/(ops)/incidents/[incidentId]/page.tsx` | Incident detail with timeline, hypotheses, evidence, citations, and stop-state summary | Core screen |
| CREATE | `apps/web/src/app/(ops)/approvals/page.tsx` | Pending approvals and recent decisions list | Role-aware |
| CREATE | `apps/web/src/app/(ops)/approvals/[approvalId]/page.tsx` | Exact-action approval detail with dry-run preview | Core screen |
| CREATE | `apps/web/src/app/(ops)/knowledge/page.tsx` | Knowledge-source status, reindex, and tombstone views | Phase 10 consumer |
| CREATE | `apps/web/src/app/(ops)/evaluations/page.tsx` | Benchmark summary, scenario quality, and regression indicators | Phase 8 consumer |
| CREATE | `apps/web/src/app/(ops)/usage/page.tsx` | Token/cost/tool budget views | Operational visibility |
| CREATE | `apps/web/src/app/(ops)/audit/page.tsx` | Audit search and execution trace views | Compliance and debugging |
| CREATE | `apps/web/src/app/(ops)/iam/page.tsx` | Role/capability and service-account visibility | Admin-only |
| CREATE | `apps/web/src/components/incidents/` | Incident list/detail/live-view components | Directory-scoped by design |
| CREATE | `apps/web/src/components/approvals/` | Approval detail, diff, dry-run, and expiry UI | Directory-scoped by design |
| CREATE | `apps/web/src/components/knowledge/` | Source status and citation inspection components | Directory-scoped by design |
| CREATE | `apps/web/src/lib/api/opsmind-client.ts` | Typed API wrapper and response guards | No silent `any` |
| CREATE | `apps/web/src/lib/auth/capability-matrix.ts` | Server-to-client capability mapper | Convenience only |
| CREATE | `apps/web/src/hooks/use-investigation-stream.ts` | SSE connection, reconnect, and stale-state markers | No domain writes |
| CREATE | `apps/web/tests/e2e/operator-workflows.spec.ts` | Primary route and happy-path E2E | Required gate |
| CREATE | `apps/web/tests/e2e/approval-security.spec.ts` | Unauthorized view/action and stale-state UI tests | Required gate |
| CREATE | `apps/web/tests/e2e/accessibility.spec.ts` | Keyboard, focus, and axe-core coverage for primary routes | Required gate |
| DELETE | `None planned` | Keep thin-slice routes behind flags until full UI proves stable | Prefer feature-flag rollback |

## Implementation Tasks

1. Build the operator shell and route-level capability bootstrap.
Contract: authenticated routes require valid session and capability payload from the backend before rendering sensitive content.
Behavior: unauthorized users are redirected or shown explicit denial states; they never receive hidden data in preloaded payloads.

2. Complete incident overview and detail routes.
Contract: incident pages render incident summary, timeline, hypothesis state, evidence list, and citation badges from typed API responses only.
Behavior: uncited analysis is visually marked as draft/ungrounded rather than blended into grounded evidence views.

3. Add live investigation stream and stale-state handling.
Contract: stream hook handles reconnect, backoff, and stale markers without fabricating state transitions client-side.
Behavior: if stream drops, UI falls back to last confirmed snapshot plus clear "live feed disconnected" indicator.

4. Build approval UX on exact-action payloads.
Contract: approval detail renders normalized params, resource witness, citations, risk class, dry-run result, expiry, and required approver count together.
Behavior: submit actions disable on stale data or expired approval; UI cannot alter canonical params before submit.

5. Build knowledge management views.
Contract: knowledge screens show source status, last ingest/reindex result, ACL scope, and citation lineage for troubleshooting.
Behavior: reindex/tombstone commands use typed backend calls and show asynchronous status, not fake immediate completion.

6. Build evaluation, usage, audit, and IAM screens.
Contract: each route exposes the minimum useful operational slice from backend projections without surfacing secrets or chain-of-thought fields.
Behavior: audit route supports timeline/execution trace drill-down; usage route highlights budget pressure before hard failure.

7. Harden accessibility, responsiveness, and content safety.
Contract: keyboard navigation, focus order, reduced-motion respect, and readable mobile/desktop layouts are mandatory on all primary routes.
Behavior: DOM snapshot scans fail the build if secrets, provider reasoning blobs, or unrestricted tool payloads appear in rendered HTML.

8. Lock browser evidence before closeout.
Contract: Playwright/E2E, accessibility, permission-aware rendering, and responsive visual checks must all pass before route flags flip to default-on.
Behavior: if any route depends on unstable backend contracts, keep it feature-flagged and surface explicit degraded-mode copy.
9. Validate the workflow with qualified operators.
Contract: test tasks, start/end timestamps, expected evidence, approval comprehension questions and correction/override reasons follow the Phase 8 human-baseline protocol.
Behavior: observed on-call/incident-command users can identify grounded versus ungrounded claims, stale state, approval target/diff and abstention without facilitator rescue.

## Migration Strategy

- Keep the phase 7 thin-slice routes available behind a fallback flag while new operator screens land.
- Roll out navigation and overview first, then incident detail/live stream, then approvals, then secondary screens.
- Generate or hand-maintain typed client wrappers additively; backend endpoints must not break older thin-slice consumers during migration.
- Use route-level feature flags so approval and knowledge screens can be enabled independently after their backend readiness checks pass.

## Rollback Plan

- Disable affected route flags and fall back to thin-slice routes without removing backend data or API contracts.
- Preserve generated client wrappers and server components for forensic debugging, but stop linking to unstable routes from primary navigation.
- If streaming route causes instability, fall back to polling over the same projection endpoints rather than bypassing workflow projection safety.
- If approval UX is not ready, keep approvals view read-only and leave action submission disabled rather than partially interactive.

## Test and Evidence Matrix

| Layer | Required checks | Evidence artifact |
|---|---|---|
| Component | capability rendering, stale-state banners, citation badges, approval detail formatting | component/unit test output |
| Integration | typed client wrappers, route data loaders, error-state rendering | Next.js integration test report |
| End-to-end | dashboard, incident detail, live stream, approval decision, knowledge lifecycle, audit search | `operator-workflows.spec.ts` |
| Accessibility | keyboard traversal, focus traps, color contrast, axe-core on primary routes | `accessibility.spec.ts` report |
| Security | unauthorized route access, stale approval submit block, DOM secret/reasoning-content scan | `approval-security.spec.ts` report |
| Performance | route load, stream reconnect timing, client bundle size regression | frontend benchmark/build report |
| Operator usability | qualified task walkthrough, time-on-task, correction/override and approval-comprehension rubric | redacted usability report |

## Quantitative Exit Gate

- [ ] `UI-01` evidence exists: login, dashboard, incident list/detail/timeline, and live investigation flows pass end-to-end in at least 10 consecutive browser runs.
- [ ] `UI-02` evidence exists: approvals, knowledge, evaluations, usage, audit, and IAM routes each have at least one role-appropriate E2E path and one deny-path test.
- [ ] `UI-03` evidence exists: zero critical accessibility violations across primary routes and zero DOM snapshots containing secrets or provider reasoning blobs.
- [ ] Live investigation updates appear in the browser within 2 seconds p95 from backend projection update in the staging/local harness.
- [ ] Responsive checks at 375px, 768px, and 1280px show no horizontal scroll on primary routes and no hidden critical controls.
- [ ] Qualified operator pilot meets preregistered comprehension/task thresholds; automated browser reruns are not treated as human usability evidence.

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---:|---:|---|
| UI outruns backend contract stability | Medium | High | Typed wrappers, additive contracts only, and route-level feature flags |
| Hidden secret or reasoning leakage in rendered payloads | Low | Critical | Redaction assertions, DOM snapshot scans, and explicit field allowlists |
| Live view becomes stale without clear operator signal | Medium | High | SSE reconnect state, stale banners, and last-updated timestamps |
| Approval UX encourages rubber-stamp decisions | Medium | High | Exact diff layout, cited evidence, risk badges, expiry display, and disabled stale submits |
| Route sprawl reduces incident usability | Medium | Medium | Keep shell task-focused, prioritize core incident routes, and gate secondary routes until useful |

## Unresolved Decisions

- Whether the live route uses SSE only or optionally upgrades to WebSocket later should remain open until load and browser behavior are measured.
- Exact visual design system choices are intentionally deferred to implementation, but the interface must stay technical and incident-first rather than marketing-styled.
- The final set of admin-only IAM controls depends on phase 3 identity output and should not be guessed in advance.
