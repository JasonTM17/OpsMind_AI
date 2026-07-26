---
phase: 4
title: "Verification and Delivery"
status: in-progress
priority: P1
dependencies: [2, 3]
effort: "0.5 day plus CI"
---

# Phase 4: Verification and Delivery

## Overview

Run CK test/review/ship gates, synchronize authoritative docs and verify the
exact pushed revision in GitHub Actions. Phase 8 remains in progress unless
all broader quantitative exit gates independently pass.

## Related Code Files

| Action | Path | Purpose |
|---|---|---|
| Modify | `.github/workflows/pr-quality.yml` | Direct Phase 8B contract artifact gate |
| Create | `.github/workflows/cross-service-evaluation.yml` | Build/run/attest A/B/C integration matrix |
| Modify | `scripts/validation/validate-repository-layout.mjs` | Canonical files/wiring |
| Modify | `evaluation/README.md` | Current checkpoint/run/evidence limits |
| Modify | `docs/evaluation-strategy.md` | A/B/C semantics and remaining gates |
| Modify | `docs/testing-strategy.md` | Test/evidence ownership |
| Modify | `docs/progress.md` | Verified checkpoint only |
| Modify | `docs/project-roadmap.md` | Phase 8B current evidence and Phase exit BLOCK |
| Modify | `plans/260719-1747-opsmind-ai-production-platform/phase-08-simulator-and-evaluation-baseline.md` | Parent-plan checkpoint |
| Read | `plans/reports/tester-260726-phase8b-evaluation.md` | Completed QA evidence |
| Pending | `plans/reports/code-review-260726-phase8b-evaluation.md` | Blocking review evidence |

## Current Evidence

Cross-service child processes now read their exit status from the process
handle on every platform instead of inferring it from `$LASTEXITCODE`. The
previous non-Windows path lost that status once the call ran inside a pipeline,
so a failing child was reported as success; the SQL role/schema helper shared
the same defect and ran immediately before the failing Tool Gateway migration.
Standard output and error are copied concurrently as raw bytes, standard input
is fed after the drain starts, and secrets reach only the child environment
rather than the parent process.

The independent earlier tester recorded 28/28 Node tests; the current
remediation rerun passes 33/33 Node tests and 40/40 targeted Python tests, plus
50 shuffled semantic-order trials and the path-safety test. That test now also
covers exit status read from the handle, 512 KB concurrent capture, and a
standard-input round trip, and it executed end to end on Windows PowerShell 7
for the first time. A mutated expected exit code makes it fail, so the
assertion is not vacuous. The Phase 8 validator reports
`Implemented=3 CanonicalResults=3 Errors=0 CheckpointResult=PASS
PhaseExit=BLOCK`; repository layout, actionlint, syntax checks, and the project
secret scan pass. The independent tester completed with concerns limited to
unrun heavy integration and coverage depth.

Revision-bound GitHub Actions run `30199870220` on commit `963ab8d` is terminal
green. It executed fresh disposable Docker/PostgreSQL Scenario A with 100 warm
runs, Scenario B, and Scenario C through the real Platform, AI Runtime, Tool
Gateway, and PostgreSQL path, and scored each one `EvaluationVerdict=PASS` with
`ScorerExit=0`. All eight metrics pass in all three scenarios, including
`root_cause_semantic`. The uploaded artifact records `GitHead=963ab8d`,
`GitTree=0`, and SHA-256 digests for both service JARs, the connector manifest,
the export SQL, and the projector.

Five defects closed between the previous transcript and that run: a shell
argument boundary that split the probe command, an evidence identity contract
that rejected the version 8 UUIDs the platform derives, a cost budget that was
unreachable rather than strict, a reported cost that did not survive the durable
numeric scale, and an artifact reference that named a transient working path.

Blocking independent review remains pending. Phase 8 exit stays BLOCK on the
held-out corpus and human baseline, which this checkpoint does not claim.

The workflow publishes traces only below `.opsmind/reports` and uploads
`artifacts/verification/phase-08b/` plus the A/B/C trace paths as
`phase-08b-cross-service-evaluation`. Raw exports and enriched working files
must not appear in the artifact.

## Implementation Steps

1. Run Node unit/scenario/projector tests, Platform focused migration/domain
   tests, and both Phase 7/8 validators.
2. Run repository layout, secret/history scan, actionlint and lightweight
   syntax checks locally; do not bypass storage guards for heavy suites.
3. Delegate independent tester and code-reviewer. Red-team trust boundaries,
   artifact tampering, cross-tenant rows, resource bounds and false claims.
4. Fix all critical/high findings and re-run focused gates.
5. Sync all four plan phases with `ck plan check`; update parent Phase 8/docs
   from actual evidence.
6. CI builds service artifacts in the same job, hashes JARs/runtime source and
   manifest resources, runs fresh A/B/C disposable harnesses, scores them, and
   uploads redacted success/failure evidence.
7. Commit conventionally, push `main`, then wait for GitHub Actions terminal
   success and inspect Phase 8/cross-service artifacts.

## Verification Commands

```powershell
node --test evaluation/runner/*.test.mjs
node scripts/validation/validate-phase-08-evaluation-foundation.mjs
node scripts/validation/validate-phase-07-investigation-slice.mjs
node scripts/validation/validate-repository-layout.mjs
& .\.opsmind\cache\tools\actionlint\1.7.12\actionlint.exe -no-color
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\governance\scan-project-secrets.ps1
```

## Success Criteria

- [x] All scoped lightweight tests and static/security gates report zero failures.
- [ ] Independent review has zero blocking findings.
- [x] Pushed revision receives terminal green CI with uploaded verification
      artifacts bound to exact source and executable digests.
- [x] Docs state which evidence now exists and that Phase 8/A-Z G4 remains BLOCK
      on held-out corpus and human baseline.
- [x] Worktree clean and C/D free space reported without destructive cleanup.

## Risk Assessment

Local heavy integration is blocked by disk floors. This is a safety control,
not permission to weaken tests; same-job revision/binary-bound CI must execute
all production-path scenarios.
Harness/scorer/workflow changes can be disabled or reverted independently.
V008 is the rolling expand migration and already supports old and response-aware
writers. Rollback during this window keeps V008 applied. Contract enforcement
must be a later forward migration after old-writer drain; never down-migrate or
delete accepted-response history in place.
