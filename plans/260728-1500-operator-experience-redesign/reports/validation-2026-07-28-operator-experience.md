---
title: "Operator Experience Local Validation"
date: "2026-07-28"
status: pass-local
branch: "feature/operator-experience-redesign"
---

# Operator Experience Local Validation

## Summary

The final local tree passes behavior, accessibility automation, responsive
geometry, production fail-closed, media integrity, and staged-tree
secret-history gates. Exact-head CI and merge evidence remain the last Phase 5
actions.

## Gate Evidence

| Gate | Result | Evidence |
|---|---:|---|
| Operator unit contracts | PASS | 11/11 |
| ESLint | PASS | zero errors |
| TypeScript | PASS | `tsc --noEmit` |
| Next production build | PASS | Next.js 16.2.11 optimized build |
| Chromium E2E | PASS | 31/31 |
| Production standalone smoke | PASS | 2/2 |
| Responsive geometry | PASS | 3 columns at 1440; 2 at 1024; 1 at 820/768/375/320 |
| Projection maxima | PASS | 200 evidence IDs, 100 citations, 2,000-character explanation, 900-character claim at 1024/320 |
| Accessibility automation | PASS | axe WCAG A/AA representative states; one `main`, one `h1`, named meter |
| Refresh interaction | PASS | click emits a real Next RSC route request plus named start/completion feedback |
| Copy interaction | PASS | repeated feedback, reversed Promise order, and delayed old correlation copy after refresh |
| Production boundary | PASS | no browser credential fallback; unauthorized route fails closed |
| Independent pre-landing review | PASS after remediation | no P0/P1/P3 findings; stale async correlation-copy feedback fixed and regression-tested |
| Phase 7 source checkpoint | PASS | 43 files; `Errors=0`; only external cross-service report blocks phase exit |
| Repository layout | PASS | 914 files; one OpenAPI root; zero errors |
| Media capture | PASS | fixture-derived 1440 px PNG and 10-frame 720×480 GIF |
| Media manifest | PASS | dimensions, byte sizes, and SHA-256 regenerated |
| Secret scan | PASS | 1,829 candidates; 1,825 text; 4 reviewed binaries; 138 commits; 0 findings |
| Diff hygiene | PASS | `git diff --check`; no suppression/TODO/unsafe client storage added |

## Visual Review

Desktop and 320 px captures preserve the forensic instrument identity, reading
order, evidence content, and continuous panel structure. Visual review found
that offscreen `content-visibility:auto` could leave durable evidence blank in
a full-page mobile capture. The containment was removed; the repeated list is
already bounded by the server projection contract. Recaptured media shows all
evidence content.

## Changed Trust Boundary

None. Investigation fetch, session assertion, authorization, media-type
assurance, schema parsing, redaction, and error sanitization remain server-owned.
The only new client island calls `router.refresh()`; it receives no token,
credential, incident payload, or executable tool contract.

## Pending Ship Evidence

- Commit and push.
- Exact-head GitHub Actions run.
- Squash merge, tree-equivalence check, and safe worktree cleanup.

## Unresolved Questions

None.
