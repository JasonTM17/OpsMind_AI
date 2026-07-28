---
phase: 4
title: "Complete Terminal, Loading, and Identifier States"
status: completed
effort: "0.5 day"
---

# Phase 4: Complete Terminal, Loading, and Identifier States

## File Ownership

- `apps/operator-web/app/organizations/[organizationId]/projects/[projectId]/incidents/[incidentId]/investigations/[runId]/loading.tsx`
- `apps/operator-web/app/organizations/[organizationId]/projects/[projectId]/incidents/[incidentId]/investigations/[runId]/loading.module.css`
- `apps/operator-web/features/investigation/unavailable-workspace.tsx`
- `apps/operator-web/features/investigation/unavailable-workspace.module.css`
- `apps/operator-web/features/investigation/copy-field.tsx`
- `apps/operator-web/features/investigation/copy-field.module.css`
- `apps/operator-web/features/investigation/refresh-status-button.tsx`
- `apps/operator-web/features/investigation/refresh-status-button.module.css`

## Implementation Steps

1. Make loading skeleton use the same shell, section structure, and responsive
   breakpoints as the final route; mark decorative blocks non-semantic.
2. Replace implicit empty-URL retry with an explicit 44 px refresh button using
   the router refresh path; keep error details server-sanitized.
3. Keep degraded status text in its own live region and the action outside it.
4. Make repeat copy actions re-announce success and preserve clear button names.
5. Ensure missing/long identifiers, digests, and error codes wrap without
   changing copy value.

## Validation

- Loading geometry at 1024/820/768/375.
- Keyboard and accessible-name checks for refresh and copy.
- Repeated copy and actual refresh request/re-render tests.

## Success Criteria

- [x] Loading and final layout never jump between contradictory column models.
- [x] Retry is an explicit functioning button.
- [x] Repeated copy is perceivable to assistive technology.
- [x] No identifier state causes horizontal overflow.
