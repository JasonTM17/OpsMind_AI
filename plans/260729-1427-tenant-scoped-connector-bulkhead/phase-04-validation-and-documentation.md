---
phase: 4
title: validation-and-documentation
status: completed
effort: 0.5 day
---

# Phase 4: validation-and-documentation

## Overview

Bind the new invariant into repository validators and update only documentation
whose architecture or gate status changed.

## Files

Modify:

- `scripts/validation/validate-phase-06-tool-gateway.mjs`
- `docs/system-architecture.md`
- `docs/testing-strategy.md`
- `docs/project-roadmap.md`
- `docs/progress.md`
- `docs/codebase-summary.md`

## Implementation Steps

1. Extend the Phase 6 validator with source markers for validated configuration,
   trusted-scope admission, tenant-before-global acquisition, lifecycle tests,
   and registry eviction proof.
2. Remove `tenant-scoped connector bulkhead proof is absent` only when those
   markers and executable tests exist.
3. Keep/add accurate Phase 6 exit blockers for the oversized-evidence artifact
   adapter, remaining connector families, named live non-production connector,
   and provider-specific cancellation proof.
4. Update architecture/testing docs with the exact limits, trusted key source,
   fail-fast semantics, and cancellation invariant. Do not describe future
   metrics, queues, retries, or provider behavior as implemented.
5. Update roadmap/progress/codebase summary with revision-bound evidence only
   after tests pass.
6. Run the narrow Tool Gateway test suite first, then Phase 6 validation,
   repository diff check, relevant documentation validation, and secret scan.
7. Request independent production-readiness review before commit/push.

## Success Criteria

- [ ] Tool Gateway Maven tests pass with new concurrency coverage.
- [ ] `node scripts/validation/validate-phase-06-tool-gateway.mjs` returns
      `CheckpointResult=PASS`.
- [ ] Phase 6 remains `PhaseExit=BLOCK` for only evidence-backed remaining
      blockers.
- [ ] Documentation claims match code and test output.
- [ ] `git diff --check` and the repository secret scanner pass.
- [ ] Independent review reports no unresolved blocker/high finding.

## Rollback

Revert code, tests, validator markers, and documentation together. Do not leave
the validator or roadmap claiming tenant isolation after runtime enforcement is
removed.
