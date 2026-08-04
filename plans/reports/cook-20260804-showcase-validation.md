# CK Cook validation — operator showcase slice

Date: 2026-08-04
Scope: `plans/260803-opsmind-showcase-evidence/plan.md`

## Evidence

- `node scripts/media/capture-operator-media.mjs` with
  `OPSMIND_MEDIA_SKIP_CAPTURE=1`: `MediaCapture=PASS`; regenerated GIF matches
  the working-tree manifest (`16` frames, `720x480`, `948208` bytes).
- `git diff --check`: pass.
- Operator Web unit suite passes directly (`11/11`) through Node's test runner.
- `node --check scripts/media/capture-operator-media.mjs`: pass.
- AI Runtime offline suite: `170 passed`, `5` PostgreSQL-gated skips; Ruff and
  production-source mypy (`36` files) pass. Canonical docs retain the last
  revision-bound CI count (`159`) until a clean exact-revision CI run replaces
  that evidence.
- Capture manifest regeneration now rejects unsupported media entries instead
  of silently assigning GIF metadata to future assets.
- `scripts/governance/validate-documentation.ps1`: pass (`68` Markdown files,
  `153` local links, `Errors=0`).
- Direct Node validators: repository layout, bounded evidence (4B), Tool
  Gateway checkpoint (6), and evaluation foundation (8) pass their bounded
  checkpoints; their documented phase-exit blockers remain active.
- `scripts/governance/scan-project-secrets.ps1`: pass when the changed media
  and manifest are aligned in the index (`Findings=0`). An unstaged working
  tree alone reports the expected reviewed-media integrity mismatch against the
  old index snapshot.
- Fresh working-tree scan confirms the fail-closed boundary: exit `7` with the
  single expected `git-index/docs/media/operator-investigation-workspace-walkthrough.gif`
  `reviewed-media-integrity-mismatch`; no credential or secret finding.
- GIF validation now parses image blocks and requires the declared manifest
  frame count, rejects malformed fixed-size extensions, and rejects trailing
  bytes after the GIF trailer. The existing secret-scan suite completed
  `30/30` cases; a focused negative control (`frames=15`) returned `BLOCK` with
  `reviewed-media-integrity-mismatch`.

## Limitations

- Full browser capture was not attempted because storage preflight fails closed:
  `C:` has `7.53 GB` free (minimum `10 GB`) and `D:` has `16.61 GB` free
  (minimum `20 GB`).
- Docker cleanup removed stopped project containers, unused images, transient
  MCP sandboxes, anonymous volumes, and the unused Trivy cache. The 11 active
  compose services and named database/object-store/cache volumes were retained
  and recovered healthy after Docker restarted.
- Docker's VHDX still occupies `85.14 GB` on `D:` while its filesystem reports
  about `7.2 GB` used. Online TRIM completed, but Windows denied `Optimize-VHD`
  because the current process lacks the required administrator permission.
- Operator Web typecheck/lint cannot run because the existing package links
  point to missing `typescript`/`eslint` payloads; the frozen workspace setup
  is intentionally blocked by the capacity guard.
- The repository-level `node_modules/.pnpm` store is absent, so no local/global
  fallback compiler or linter payload is available without a setup install.
- Phase 7 cross-service evidence is not bound to the current dirty revision.
- The showcase plan remains partial: no new mobile capture or full E2E evidence
  is claimed.

## Unresolved questions

- An administrator must compact Docker's VHDX, or the operator must provide at
  least another `2.47 GB` on `C:` and `3.39 GB` on `D:`, before frozen workspace
  setup and real responsive/mobile browser acceptance can run.
