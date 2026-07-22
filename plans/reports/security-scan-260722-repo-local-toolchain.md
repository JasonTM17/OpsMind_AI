# Security Scan — Repo-local CK Toolchain

Date: 2026-07-22
Scope: `.agents/`, `.codex/`, `.claude/` dependency manifests and executable code/config

## Summary

| Category | Critical | High | Medium | Low |
|---|---:|---:|---:|---:|
| Secrets | 0 | 0 | 0 | 0 |
| Dependencies | 0 | 9 | 4 | 0 |
| Runtime application dependencies | 0 | 0 | 0 | 0 |

The 13 open Dependabot alerts affected repository-local development skills, not
the OpsMind application runtime. They still required remediation because these
tools process local files and may run during development workflows. None were
dismissed as false positives; all affected manifests are remediated locally and
await GitHub ingestion after push.

## Evidence

- Project secret scanner: 1,138 application files and 21 commits scanned; 0 findings.
- Toolchain supplemental scan: 794 executable/code/config files scanned; 0
  high-confidence secret matches.
- `npm audit --omit=dev`:
  - `markdown-novel-viewer`: 1 high vulnerability (`js-yaml`).
  - `show-off/scripts`: 3 high vulnerabilities (`js-yaml`, `sharp`, `ws`).
- `.agents` and `.codex` manifests and lockfiles are byte-identical before remediation.
- `.claude/skills/show-off/scripts/package.json` carries the same vulnerable direct
  `sharp` range but has no tracked lockfile.

The application scanner intentionally excludes `.agents`, `.codex`, and `.claude`.
Its clean result therefore cannot be used as evidence for the toolchain; the
supplemental scan closes that scope gap for this checkpoint.

## Findings

1. `js-yaml` is below the patched 3.x or 4.x release in mirrored lockfiles.
   - Advisories: `GHSA-52cp-r559-cp3m`, `GHSA-h67p-54hq-rp68`
   - Required versions: `3.15.0` for `markdown-novel-viewer`; `4.3.0` for
     `show-off/scripts`.
2. `sharp` is a direct dependency below `0.35.0` in all three `show-off` manifests.
   - Advisory: `GHSA-f88m-g3jw-g9cj`
   - Selected version: `0.35.3`, the current patched release returned by the npm
     registry.
3. Puppeteer's transitive `ws` is below `8.21.0` in the mirrored `show-off` lockfiles.
   - Advisory: `GHSA-96hv-2xvq-fx4p`
   - Selected version: `8.21.1`, within the existing transitive major line.

## Remediation Contract

1. Raise direct `sharp` ranges to `^0.35.3` in `.agents`, `.codex`, and `.claude`.
2. Regenerate only the affected mirrored lockfiles with lifecycle scripts disabled.
3. Keep `.agents` and `.codex` package manifests and lockfiles byte-identical.
4. Run `npm audit --omit=dev` in both tool packages and require zero findings.
5. Validate package-lock integrity and run the existing `markdown-novel-viewer` tests.
6. Re-query Dependabot after GitHub processes the pushed manifests; do not dismiss
   any alert manually.

## Verification After Remediation

- `npm audit --omit=dev`: 0 vulnerabilities in all four tracked lockfile packages.
- Resolved versions: `js-yaml` `3.15.0` and `4.3.0`, `sharp` `0.35.3`, `ws`
  `8.21.1`.
- `.agents` and `.codex` package manifests and lockfiles remain byte-identical.
- Clean install with lifecycle scripts disabled completed for
  `markdown-novel-viewer`; YAML/front-matter parsing passed.
- Clean install with Puppeteer browser download disabled completed for
  `show-off/scripts`; JavaScript syntax check passed and `sharp 0.35.3` plus
  Puppeteer imported successfully.
- `git diff --check`: pass.

## Existing Validation Debt

The `markdown-novel-viewer/tests/run-tests.cjs` aggregate runner is already broken:
it imports `scripts/lib/dashboard-renderer.cjs` and dashboard assets that do not
exist in either mirror. This predates and is unrelated to the dependency changes.
The dependency-specific parser validation passed; the stale aggregate suite was
not hidden or marked successful.

## Unresolved Questions

- Decide in a separate maintenance change whether to restore the missing dashboard
  implementation or delete the stale dashboard-only tests.
