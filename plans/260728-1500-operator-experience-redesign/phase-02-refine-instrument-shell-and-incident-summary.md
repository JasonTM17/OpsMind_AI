---
phase: 2
title: "Refine Instrument Shell and Incident Summary"
status: completed
effort: "0.5 day"
---

# Phase 2: Refine Instrument Shell and Incident Summary

## File Ownership

- `apps/operator-web/app/layout.tsx`
- `apps/operator-web/app/styles.css`
- `apps/operator-web/features/investigation/operator-shell.tsx`
- `apps/operator-web/features/investigation/operator-shell.module.css`
- `apps/operator-web/features/investigation/incident-summary.tsx`
- `apps/operator-web/features/investigation/incident-summary.module.css`

## Implementation Steps

1. Make shell width use the available desktop canvas while retaining a bounded
   readable instrument and centered gutters.
2. Move page content under the single `main`; convert summary wrapper to a
   labeled section and preserve exactly one page heading.
3. Strengthen title/summary/status hierarchy, metadata wrapping, focus-visible
   treatment, control borders, selection, and reduced-motion behavior.
4. Increase narrow-screen reading size and target sizes without making desktop
   evidence needlessly loose.
5. Simplify expensive global texture where it adds no diagnostic value and add
   a dark browser theme color through supported Next metadata.

## Validation

- Existing shell/summary unit assertions.
- Axe and keyboard checks at desktop and 200% zoom.
- Visual comparison at 1440 and 375 px.

## Success Criteria

- [x] Shell uses wide screens intentionally without recreating empty right space.
- [x] Landmark and heading hierarchy is valid.
- [x] Summary metadata wraps without overlap or clipped identifiers.
- [x] Focus, contrast, text size, and reduced motion meet the project guidelines.
