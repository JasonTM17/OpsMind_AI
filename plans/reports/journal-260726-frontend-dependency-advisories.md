---
type: journal
date: 2026-07-26
---

# Dependency Mitigation Exposed a Broken Container Build Contract

**Date**: 2026-07-26 09:19
**Severity**: High
**Component**: Operator Web dependency security gate
**Status**: Mitigated locally; replacement CI pending

## What Happened

GitHub Actions run `30183734779` failed Operator Web at `pnpm audit --audit-level moderate` after three July 2026 high advisories landed against locked frontend dependencies. Replacement run `30184775103` proved the dependency/security remediation: Operator Web and Phase 8A passed. The run still failed because Compose job `89747686992` could not build the Operator Web image.

## The Brutal Truth

The first red run was the audit gate doing its job. The second was our mistake. We added a required patch artifact to pnpm configuration but failed to add it to the Docker build contract. Getting the security job green only to trip over a missing `COPY` is maddening because this was deterministic and preventable.

## Technical Details

The initial audit reported:

- PostCSS arbitrary file read, `GHSA-6g55-p6wh-862q`.
- PostCSS source-map path traversal, `GHSA-r28c-9q8g-f849`.
- `brace-expansion` unbounded expansion/OOM, `GHSA-mh99-v99m-4gvg`, across 93 dependency paths.

The mitigation locks PostCSS `8.5.18`, moves brace 5.x to `5.0.8`, and patches legacy `brace-expansion@1.1.16` with bounded expansion. The pre-audit probe proves the patch hash, versions, CommonJS function API, minimatch behavior, and output bounds. Local audit exits `0` with one patched advisory narrowly ignored; tests, lint, typecheck, and build pass.

Compose failed during frozen install with:

```text
ENOENT: no such file or directory, open '/workspace/patches/brace-expansion@1.1.16.patch'
```

## What We Tried

- Rejected globally forcing `brace-expansion@5.0.8`; `minimatch@3` consumers require the incompatible 1.x function-export API.
- Kept the bounded 1.1.16 backport and allowed the GHSA ignore only after the executable probe.
- Added `COPY patches ./patches` before `pnpm install --frozen-lockfile` in the Operator Web Dockerfile.
- Added and locally passed a repository-layout validator assertion that fails if the patch copy is absent or ordered after frozen install.

## Root Cause Analysis

We treated the pnpm patch as dependency metadata, not as a required build input. The frontend job installed from the complete repository checkout, where `patches/` already existed. The Docker stage constructed `/workspace` only from explicit `COPY` instructions, so the contexts diverged. Existing validation checked frozen-install policy but never checked that patch inputs were copied before install.

## Lessons Learned

A lockfile patch is source code and must be present in every install context. Host install success says nothing about a Docker stage assembled by selective copies. Any new install-time artifact needs a validator covering both presence and ordering.

## Next Steps

- PR owner: push the Dockerfile and validator fix, then require another fully green replacement run. Local mitigation is not authoritative CI evidence.
- Platform Security: review and remove the temporary GHSA ignore by 2026-08-09, or sooner when upstream stops resolving 1.x.
- Dependency owners: track ESLint/Next.js upgrades that remove `minimatch@3`.

## Unresolved Questions

None. Replacement CI remains a required confirmation gate, not an unresolved design question.
