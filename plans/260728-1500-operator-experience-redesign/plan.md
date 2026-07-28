---
title: "OpsMind Operator Experience Redesign"
description: >-
  Refine the evidence-first investigation workspace into a responsive,
  accessible forensic instrument without weakening server-owned data or
  redaction boundaries.
status: in-progress
priority: P1
branch: "feature/operator-experience-redesign"
tags:
  - frontend
  - operator-experience
  - accessibility
  - responsive
  - evidence
blockedBy: []
blocks: []
created: "2026-07-28T08:18:12.091Z"
createdBy: "ck:plan"
source: skill
---

# OpsMind Operator Experience Redesign

## Overview

The current investigation route is functionally sound and already passes its
baseline unit, lint, type, build, and 28-test Playwright suite. The supplied
desktop capture exposes an operator-cost problem: the instrument is compressed
into the left side of a wide viewport, important prose and controls are too
small, the responsive rules disagree with the loading state, and long
server-supplied values can break the evidence layout.

This plan preserves the warm graphite/off-white/amber forensic identity and the
server-component trust boundary. It improves hierarchy, responsive geometry,
accessibility, failure recovery, and rendering cost without adding a UI
dependency, generic dashboard cards, decorative gradients, fabricated data, or
client-side access to Platform credentials.

## Design Decision

- Visual model: one continuous operator instrument, not a tile dashboard.
- Density/motion/variance: `7 / 2 / 6`; dense evidence, restrained transitions,
  asymmetric evidence spine.
- Responsive model: three columns above 1080 px, two columns from 821–1080 px
  with the conclusion spanning the row, one column at 820 px and below.
- Runtime model: retain server rendering; only copy and explicit refresh
  interactions may be client islands.
- Content model: all arbitrary identifiers, explanations, citations, digests,
  and errors must wrap safely; no truncation that hides evidence.

## Phases

| Phase | Name | Status |
|-------|------|--------|
| 1 | [Lock responsive and safety regression gates](./phase-01-lock-responsive-and-safety-regression-gates.md) | Completed |
| 2 | [Refine instrument shell and incident summary](./phase-02-refine-instrument-shell-and-incident-summary.md) | Completed |
| 3 | [Strengthen evidence-centered workspace](./phase-03-strengthen-evidence-centered-workspace.md) | Completed |
| 4 | [Complete terminal, loading, and identifier states](./phase-04-complete-terminal-loading-and-identifier-states.md) | Completed |
| 5 | [Validate accessibility, performance, and media](./phase-05-validate-accessibility-performance-and-media.md) | In Progress |

## Dependencies

- Existing Operator Web investigation contracts and fixtures.
- Existing Playwright/axe stack; no new runtime or test dependency.
- Platform API remains the source of authorization, redaction, evidence, and
  terminal state. FE must not infer or invent those values.
- Phase 9 backend work may merge independently; this branch owns Operator Web,
  its tests, media capture, and this plan only.

## Acceptance Criteria

- No horizontal overflow at 1440, 1024, 820, 768, 375, or 320 CSS px, including
  adversarial long identifiers, prose, digests, and citation lists.
- Layout geometry matches the 3/2/1-column model and loading skeleton at the
  same breakpoints; panels align to content rather than equal-height stretching.
- One page `main`, one page `h1`, valid labeled sections, keyboard-visible
  controls, named confidence meter, and no meaningless loading landmarks.
- Primary reading text is at least 16 px on narrow screens; touch controls are
  at least 44 CSS px; text/control contrast and zoom behavior pass axe/manual
  geometry checks.
- Unavailable state has an explicit button action; degraded status does not
  wrap the action inside a live region; repeated copy gives perceivable feedback.
- Server-only session/fetch/redaction boundary and production fail-closed tests
  remain unchanged and green.
- Unit, lint, typecheck, production build, full Playwright, production smoke,
  and independent code/design review pass.
- Final 1440 px PNG and bounded GIF are regenerated from the verified route,
  documented in the media manifest, and pass the repository secret scan.

## Risks and Rollback

- Dense evidence can become an enormous DOM: keep the server contract bounded.
  Visual verification rejected offscreen CSS containment because it produced
  blank evidence in full-page/mobile captures.
- Responsive CSS drift: geometry assertions own the contract, not screenshots
  alone.
- Client-island expansion: reject any change that moves workspace loading or
  authorization into the browser.
- Rollback is a single feature revert; no schema, API, or persisted data changes.

## Unresolved Questions

None for this slice. Stitch remains optional; current visual identity and
component boundaries are sufficient, so implementation does not depend on an
external generation service.
