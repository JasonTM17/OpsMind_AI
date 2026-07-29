---
phase: 5
title: "Validate Accessibility, Performance, and Media"
status: in-progress
effort: "0.5 day"
---

# Phase 5: Validate Accessibility, Performance, and Media

## File Ownership

- `scripts/media/capture-operator-media.mjs`
- `docs/media/operator-investigation-workspace.png`
- `docs/media/operator-investigation-workspace-walkthrough.gif`
- `docs/media/media-manifest.json`
- `README.md` media references when the verified dimensions change
- `plans/260728-1500-operator-experience-redesign/reports/**`

## Implementation Steps

1. Run unit, lint, typecheck, production build, full Playwright, and production
   fail-closed smoke tests from a clean dependency graph.
2. Run axe across representative states; manually verify keyboard order,
   200% zoom, reduced motion, touch targets, and no overflow.
3. Capture 1440 px desktop PNG and bounded GIF from the real fixture route; do
   not edit pixels to hide UI defects.
4. Validate media dimensions, frame count, size, manifest digest, README links,
   and repository secret scan.
5. Run independent pre-landing review, fix accepted findings, push, wait for
   exact-head CI, squash merge, verify tree equivalence, and clean the worktree.

## Success Criteria

- [x] All local gates pass.
- [ ] Exact-head CI gates pass.
- [ ] No unresolved Critical/High accessibility, security, or contract finding.
- [x] PNG/GIF show the verified implementation at intended dimensions.
- [x] Media manifest and README are accurate; secret scan reports zero findings.
- [ ] Feature branch is squash-merged and temporary worktree removed safely.
