# Phase 4A: Operator Investigation Workspace

## Goal

Deliver the thin Phase 7 operator surface through the CK frontend workflow and
Stitch without inventing backend data or weakening the approved browser
security boundary.

## Context

- Stitch project: `2954463725758744307`
- Accepted screen reference: `0c8867ded7bb4f6e83d004699a51460a`
- Export: `./stitch/operator-investigation-workspace-v2/`
- During implementation, generated raster artifacts are kept outside Git until
  they pass the repository's reviewed binary-media manifest. Final release
  media (screenshots/GIF) is added only with an explicit path, SHA-256, size,
  and file-signature record; media must contain no secrets, tokens, prompts,
  reasoning, or raw evidence.
- API source of truth:
  `packages/contracts/json-schema/investigation/v1/investigation-view.schema.json`
- Rich tool history, event ledger, evidence content, and service-hop timings are
  not present in the current public projection. The UI must not synthesize them.

## Files

- `apps/operator-web/app/**`
- `apps/operator-web/features/investigation/**`
- `apps/operator-web/lib/platform-api/**`
- `apps/operator-web/tests/**`
- `apps/operator-web/playwright.config.ts`
- `apps/operator-web/package.json`
- `docs/design-guidelines.md`
- Phase 7 validators, plans, and evidence reports when the implementation is
  verified

## Implementation

1. Define a contract-bound, bounded parser for the incident and investigation
   projections. Reject malformed, oversized, cross-tenant, or cross-run data.
2. Add an authenticated server-side platform client boundary. Production
   requests may use only a server-owned BFF session credential provider; no
   access or refresh token enters client JavaScript. Until the BFF session
   profile exists, live data fails closed with an explicit unavailable state.
   Browser reads require the versioned operator projection plus projection-class,
   redaction-version, and redaction-count headers.
3. Build semantic operator components for incident context, hypothesis,
   budgets, evidence references, citations, terminal/degraded states, and
   read-only safety boundaries.
4. Use the accepted Stitch composition as visual reference, then correct its
   small typography, incomplete focus behavior, contrast, and responsive
   weaknesses.
5. Add deterministic contract-fixture rendering only in the test harness. The
   production route cannot select fixture data through a public query or
   environment toggle.
6. Add Playwright coverage for completed, degraded, narrow viewport, keyboard,
   reduced-motion, and secret/raw-reasoning absence. Keep browser proof separate
   from the later cross-service proof.
7. Run unit, lint, typecheck, production build, accessibility, responsive, and
   static security gates. Record limitations honestly.

## Design Decisions

- Visual system: forensic instrument panel, asymmetric Swiss grid, graphite
  surfaces, warm text, one signal-amber accent, tabular mono metadata.
- Signature pattern: an Evidence Spine assembled only from fields the public
  projection actually proves.
- No direct browser call to Tool Gateway, AI Runtime, or Prometheus.
- No raw prompt, provider reasoning, credential, bearer token, arbitrary
  PromQL, or evidence payload in HTML, client state, logs, screenshots, or test
  artifacts.
- Model-authored prose is not a browser contract. The Platform projection
  emits controlled labels for explanation, rationale, claims, counter-evidence,
  and missing-evidence text unless a future reviewed contract proves them safe.
- No remediation action in Phase 7.

## Validation

- `pnpm --filter @opsmind/operator-web test`
- `pnpm --filter @opsmind/operator-web lint`
- `pnpm --filter @opsmind/operator-web typecheck`
- `pnpm --filter @opsmind/operator-web build`
- Playwright Chromium checks at desktop and 375-pixel viewport
- keyboard and reduced-motion checks
- secret/raw-reasoning negative assertions against rendered HTML and artifacts
- operator-projection media negotiation and assurance-header checks
- `node scripts/validation/validate-phase-07-investigation-slice.mjs`

## Acceptance

- [x] Live route is contract-bound and fails closed without a server-owned
  authenticated session.
- [x] Test-only fixture cannot be enabled through the production route.
- [x] Completed, abstained, failed, budget, empty, loading, and dependency
  failure states are explicit and accessible.
- [x] Every authoritative conclusion exposes its persisted evidence citations;
  uncited output is never styled as complete.
- [x] Desktop, tablet, and 375-pixel layouts have no unintended overflow or
  hidden critical content.
- [x] Keyboard, focus, contrast, touch-target, and reduced-motion gates pass.
- [x] No secret, token, prompt, raw reasoning, executable query, or unredacted
  evidence appears in browser output or test evidence.
- [x] Browser tests and production build pass locally; revision-bound CI and
  the cross-service trace/p95 benchmark remain open.

## Risks and Rollback

- The current projection is intentionally thinner than the Stitch concept.
  Keep unavailable fields absent rather than generating plausible telemetry.
- A temporary browser bearer-token shortcut would violate the planned BFF
  boundary. Fail closed until the session port is implemented.
- Roll back by disabling the investigation route while preserving all backend
  investigation and evidence records.

## Unresolved Questions

- The production enterprise IdP and server-side BFF session implementation are
  Phase 12 proof obligations. Phase 7 must expose that absence, not bypass it.
- Rich event/evidence projection shape must be added and reviewed with the
  cross-service trace instead of inferred in frontend code.
