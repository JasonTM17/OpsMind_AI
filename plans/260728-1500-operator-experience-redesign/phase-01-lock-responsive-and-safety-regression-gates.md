---
phase: 1
title: "Lock Responsive and Safety Regression Gates"
status: completed
effort: "0.5 day"
---

# Phase 1: Lock Responsive and Safety Regression Gates

## Context

Baseline is green: 11 unit assertions, lint, typecheck, production build, and
28 Playwright tests. Existing tests do not lock the layout cliff, long-content
wrapping, loading parity, repeated interaction, or large evidence rendering.

## File Ownership

- `apps/operator-web/tests/e2e/investigation-workspace.spec.ts`
- `apps/operator-web/tests/e2e/investigation-boundaries.spec.ts`
- `apps/operator-web/tests/support/investigation-fixtures.mjs`
- `apps/operator-web/tests/support/investigation-state-fixtures.mjs`
- `apps/operator-web/tests/support/platform-fixture-server.mjs`
- `apps/operator-web/tests/support/investigation-projection-variants.mjs`
- `apps/operator-web/tests/workspace-contract.test.mjs`

## Implementation Steps

1. Add geometry helpers/assertions for 1440, 1024, 820, 768, 375, and 320 px.
2. Add adversarial fixtures for long UUID-like values, unbroken digests, prose,
   citations, missing data, and a large but bounded evidence collection.
3. Assert no document or panel horizontal overflow and lock the 3/2/1-column
   model using bounding boxes rather than brittle computed CSS strings.
4. Cover one `main`, one `h1`, labeled meter, touch-sized interactive controls,
   keyboard focus, repeated copy feedback, and unavailable refresh behavior.
5. Keep production fail-closed scenarios unchanged.

## Validation

- `pnpm --filter @opsmind/operator-web test`
- Focused Playwright investigation specs at Chromium desktop/mobile sizes.

## Success Criteria

- [x] Regression tests lock the known layout/accessibility gaps.
- [x] Tests assert user-visible behavior and geometry, not implementation names.
- [x] No fixture introduces fake production behavior or weakens redaction checks.
