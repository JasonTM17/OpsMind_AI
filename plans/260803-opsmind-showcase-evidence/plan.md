---
status: partial
title: Improve operator showcase media and repository documentation
created: 2026-08-03
---

# Goal

Make the OpsMind repository communicate the real operator workflow clearly and
truthfully. Replace the current screenshot-only showcase with reviewed desktop
and mobile evidence, a workflow-oriented GIF, and concise media documentation.
Reduce the impression of generic AI UI without changing the security boundary,
projection contract, or investigation behavior.

## Scope

- Audit and refine the operator web presentation where it improves hierarchy,
  labels, and evidence readability.
- Extend the repository media capture script to produce reviewed desktop/mobile
  PNGs and a bounded workflow GIF from the existing E2E fixture stack.
- Update the media manifest and add a media guide documenting provenance,
  regeneration, review rules, and limitations.
- Refresh the README operator experience section to explain the workflow and
  link the supporting docs.

## Out of scope

- No backend, API, database, auth, or evidence contract changes.
- No fabricated telemetry, model output, credentials, or production claims.
- No external image generation or third-party product assets.

## Acceptance criteria

1. README shows a clear desktop/mobile product story and links the media guide.
2. Capture script produces reproducible reviewed media from the real E2E stack.
3. GIF demonstrates more than a static vertical crop while remaining derived only
   from approved operator-safe projections.
4. Manifest validates exact paths, digests, dimensions, frame counts, and review
   provenance for every checked-in media file.
5. Operator Web remains accessible, responsive at existing tested widths, and
   passes typecheck, lint, focused E2E, media scan, and diff checks.

## Planned files

- `apps/operator-web/features/investigation/*` (presentation-only refinements)
- `apps/operator-web/app/styles.css` (token refinements if required)
- `scripts/media/capture-operator-media.mjs`
- `docs/media/media-manifest.json`
- `docs/media/README.md`
- `README.md`
- `docs/design-guidelines.md` if visual rules change

## Verification

- `pnpm --filter @opsmind/operator-web typecheck`
- `pnpm --filter @opsmind/operator-web lint`
- focused operator E2E and accessibility assertions
- `node scripts/media/capture-operator-media.mjs` when the local stack is
  available
- `powershell -File scripts/governance/scan-project-secrets.ps1`
- `git diff --check`

## Current report

The README and media guide are updated, and the GIF was regenerated from the
existing reviewed PNG with a slower 16-frame eased walkthrough. Docker cleanup
removed stopped project containers, unused images, transient MCP sandboxes and
anonymous volumes while preserving active compose services and named data
volumes. The full Playwright capture remains blocked on local dependency repair
and storage preflight: C: has 7.53 GB free against a 10 GB threshold; D: has
16.61 GB free against a 20 GB threshold. No new screenshot was checked in
without a real browser run.
