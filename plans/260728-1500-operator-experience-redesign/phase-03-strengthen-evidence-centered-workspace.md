---
phase: 3
title: "Strengthen Evidence-Centered Workspace"
status: completed
effort: "1 day"
---

# Phase 3: Strengthen Evidence-Centered Workspace

## File Ownership

- `apps/operator-web/features/investigation/investigation-workspace.tsx`
- `apps/operator-web/features/investigation/investigation-workspace.module.css`
- `apps/operator-web/features/investigation/investigation-context.tsx`
- `apps/operator-web/features/investigation/investigation-context.module.css`
- `apps/operator-web/features/investigation/evidence-spine.tsx`
- `apps/operator-web/features/investigation/evidence-spine.module.css`
- `apps/operator-web/features/investigation/cited-conclusion.tsx`
- `apps/operator-web/features/investigation/cited-conclusion.module.css`

## Implementation Steps

1. Introduce the shared responsive grid contract: 3 columns above 1080 px,
   2 columns through 821 px, and 1 column at 820 px and below.
2. Use `align-items: start`; let the conclusion span the two-column row rather
   than stretching context/evidence to artificial equal heights.
3. Preserve the evidence spine as the primary visual signature, improve scan
   order/spacing, and wrap arbitrary server content with `overflow-wrap`.
4. Add a named confidence meter and accessible evidence/citation group labels.
5. Replace spread-heavy citation grouping with bounded linear grouping. Keep
   evidence eagerly rendered: visual review proved offscreen containment could
   leave a blank record in full-page/mobile output.
6. Keep all evidence visible and copyable; no truncation or hidden disclosure.

## Validation

- Unit fixtures for all run states.
- Geometry/overflow Playwright matrix.
- Axe on in-progress, complete, abstained, failed, and degraded views.

## Success Criteria

- [x] Workspace matches the 3/2/1-column contract at exact boundaries.
- [x] All arbitrary content wraps without data loss.
- [x] Meter, sections, evidence, and citations have accessible names.
- [x] Large bounded evidence fixtures remain responsive and fully inspectable.
