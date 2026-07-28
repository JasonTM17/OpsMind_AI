# Operator Experience UI/UX Audit

## Scope

- Source: supplied 1758×754 desktop capture plus current Operator Web route,
  components, styles, fixtures, and tests.
- Intent: evidence-first incident investigation for operators under time pressure.
- Brand: warm graphite, off-white, one amber signal, monospace operational data.

## Current-State Assessment

The evidence spine and restrained palette are distinctive and appropriate. The
capture still reads like a fixed-width prototype embedded in a larger canvas:
usable content occupies the left ~60%, fine metadata requires effort, three
columns are compressed, and the right side provides no operational value.

The most important issue is not cosmetic. Responsive CSS changes at 768 px while
the loading skeleton uses different thresholds, producing a one-pixel layout
cliff and state-to-state shift. Arbitrary model/server strings also lack a
universal wrapping contract, so the densest evidence can break the workspace.

## Findings

### High

1. Final and loading breakpoints disagree; 768/767 changes structure abruptly.
2. Body text falls below a comfortable 16 px on narrow screens.
3. Long explanations, digests, citations, and identifiers can overflow columns.
4. Page summary sits outside `main`; repeated header semantics weaken landmark
   navigation.
5. Loading skeleton exposes decorative `aside` landmarks.
6. Control borders are too subtle against the dark surface for reliable focus
   and boundary perception.
7. Confidence visualization has no accessible meter name/value contract.

### Medium

1. Equal-height grid stretching creates empty panel space below short content.
2. Degraded live status wraps its action, causing noisy announcements.
3. Retry uses an empty `href` rather than an explicit action.
4. Copy feedback may not announce an identical second success.
5. Evidence/citation arrays are repeatedly spread while grouping.
6. Large evidence sets have no render containment.
7. Global fixed texture adds paint work without diagnostic meaning.
8. Media capture width is 1280, smaller than the supplied desktop context.

Implementation outcome: capture width is now 1440. The proposed render
containment was tested and rejected because a full-page 320 px capture showed a
blank offscreen durable-evidence card. The projection is already bounded, so
eager evidence rendering is the safer and simpler choice.

## Recommended Design

- Keep one continuous forensic instrument with the evidence spine as the visual
  anchor; reject generic KPI cards, cyan SaaS styling, heavy gradients, and
  ornamental motion.
- Expand the centered shell to use wide screens, then structure the workspace
  as 3 columns (>1080), 2 columns (821–1080), and 1 column (≤820).
- At two columns, place assessment and evidence side-by-side and span the cited
  conclusion below. Use natural panel height.
- Raise reading size and target size on narrow screens while leaving metadata
  compact where it is not the primary reading surface.
- Preserve all evidence text; wrap rather than truncate. Add render containment,
  not hidden pagination, for bounded large collections.
- Keep data loading, authorization, and redaction server-side. Use tiny client
  islands only for copy feedback and explicit refresh.

## Success Metrics

- Zero horizontal overflow at six named widths and 200% zoom.
- Axe zero serious/critical findings in all representative states.
- One `main`, one `h1`, named meter, visible keyboard focus, 44 px actions.
- Loading/final geometry parity.
- Full existing production fail-closed suite remains green.
- Verified 1440 px media accurately represents the final route.

## Unresolved Questions

None. External Stitch generation is not required to execute this scoped
redesign; the existing brand and component boundaries already provide a stronger
project-specific direction.
