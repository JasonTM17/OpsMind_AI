# Code Review: Frontend Dependency Advisory Patch

## Scope

Read-only incremental production-readiness review of:

- legacy `brace-expansion@1.1.16` `${...}` compatibility preservation;
- child-process bound-result parsing and non-empty assertions;
- machine-enforced `GHSA-mh99-v99m-4gvg` exception review deadline;
- regenerated patch/lock binding.

Reviewed current behavior in:

- `patches/brace-expansion@1.1.16.patch`
- `scripts/security/verify-brace-expansion-patch.mjs`
- `pnpm-lock.yaml`

## Assessment

No blocking finding remains. Commit-ready locally: **yes**.

### Resolved Blockers and Non-blockers

- Legacy API behavior restored. Patched 1.1.16 now leaves
  `${a,b}{c,d}` literal, matching the unpatched 1.1.16 contract.
- Child probe now fails for empty output, emits JSON, and verifies parsed count
  and character length in the parent process.
- Temporary audit exception now fails closed after 2026-08-09.
- Patch regeneration updated the lockfile patch hash; frozen-lock validation
  accepts the current patch and lock pair.

No new concurrency, error-propagation, trust-boundary, authorization,
query-efficiency, or data-exposure defect found in the incremental changes.

## Verification Evidence

- `node scripts/validation/validate-repository-layout.mjs`
  - `Errors=0`
  - `Result=PASS`
- `node scripts/security/verify-brace-expansion-patch.mjs`
  - `BraceExpansionPatch=PASS`
  - `ExceptionReviewBy=2026-08-09`
  - `LockedVersions=1.1.16,5.0.8`
  - `DefaultBoundCount=80000`
  - `DefaultBoundCharacters=4000000`
- `pnpm install --lockfile-only --frozen-lockfile --offline`
  - completed successfully; lockfile already current
- Legacy differential corpus
  - 889 cases
  - 0 mismatches against unpatched 1.1.16
- Simulated UTC date `2026-08-10`
  - verifier failed with the expected exception-expired assertion
- `pnpm audit --audit-level moderate`
  - exit 0
  - one high advisory, explicitly patched and ignored
- `git diff --check`
  - exit 0

## Remote CI Contingency

Local evidence does not replace revision-bound CI. Merge remains contingent on
a fully green replacement GitHub Actions run for the exact commit, including
clean bootstrap, actionlint, frontend quality, and dependency-review jobs.

## Unresolved Questions

None.

Status: DONE

Summary: Incremental hardening verified; no blocker remains and the patch is commit-ready pending green remote CI.

Concerns/Blockers: No local blocker. Remote revision-bound CI remains required.
