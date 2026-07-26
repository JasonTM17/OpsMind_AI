---
type: journal
date: 2026-07-26
---

# Frontend Dependency Advisories Broke an Otherwise Green CI Run

**Date**: 2026-07-26 09:19
**Severity**: High
**Component**: Operator Web dependency security gate
**Status**: Mitigated locally; replacement CI pending

## What Happened

GitHub Actions run `30183734779` failed in Operator Web at `pnpm audit --audit-level moderate`. The Phase 8A deterministic evaluation gate passed; the overall run still failed because three newly published July 2026 high advisories hit versions already locked in the frontend graph: PostCSS `8.5.10` under `next`, `brace-expansion` `5.0.7`, and legacy `brace-expansion` `1.1.16` under `minimatch@3.1.5`.

## The Brutal Truth

This is frustrating precisely because the feature gate was green. We did the Phase 8 work correctly and still got a red build from dependency risk outside that feature. That does not make the failure noise. The audit gate did its job: shipping around it would have converted an annoying CI interruption into a knowingly accepted file-disclosure and denial-of-service risk.

## Technical Details

CI reported `3 vulnerabilities found`, `Severity: 3 high`, then exited `1`:

- PostCSS arbitrary file read, `GHSA-6g55-p6wh-862q`.
- PostCSS source-map path traversal, `GHSA-r28c-9q8g-f849`.
- `brace-expansion` unbounded expansion/OOM, `GHSA-mh99-v99m-4gvg`, across 93 dependency paths.

The current fix locks PostCSS `8.5.18`, moves brace 5.x to `5.0.8`, and applies `patches/brace-expansion@1.1.16.patch`. The patch backports bounded iterative expansion with caps of 100,000 results and 4,000,000 characters. `scripts/security/verify-brace-expansion-patch.mjs` proves the exact lock versions, patch hash, CommonJS function export, minimatch behavior, and bounds before audit runs.

Local evidence: probe exit `0`; audit exit `0` with `1 high (1 ignored)`; Operator Web tests, lint, typecheck, and build pass.

## What We Tried

- Rejected globally forcing `brace-expansion@5.0.8`. Legacy plugins still resolve `minimatch@3`, which expects the 1.x function-export API; 5.x is incompatible.
- Chose the narrow 1.1.16 backport plus a runtime/lock probe.
- Ignored only `GHSA-mh99-v99m-4gvg`, and only after the probe proves the patched code is installed and bounded.

## Root Cause Analysis

The dependency graph retained vulnerable versions when the advisory database changed. The hard part was not PostCSS or brace 5.x; both had compatible releases. The real trap was legacy minimatch consumers with no safe compatible 1.x release. A blanket override would have made audit green by breaking consumers.

## Lessons Learned

Never equate “audit ignored” with “risk accepted.” An ignore is defensible only when an executable probe proves the exact patched artifact and behavior first. Also test dependency API shape before forcing a transitive major version.

## Next Steps

- PR owner: push the fix and require a fully green replacement CI run immediately.
- Platform Security: review and remove the temporary GHSA ignore by 2026-08-09, or sooner when upstream stops resolving 1.x.
- Dependency owners: track ESLint/Next.js upgrades that remove `minimatch@3`.

## Unresolved Questions

None. At the time of this entry, replacement CI remained the authoritative
confirmation gate rather than a conceptually unresolved question.
