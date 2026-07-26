---
phase: 4
title: "Verification and Delivery"
status: completed
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
| Create | `reports/code-review-260726-phase8b-evaluation.md` | Plan-scoped blocking review evidence |

## Current Evidence

Cross-service child processes now read their exit status from the process
handle on every platform instead of inferring it from `$LASTEXITCODE`. The
previous non-Windows path lost that status once the call ran inside a pipeline,
so a failing child was reported as success; the SQL role/schema helper shared
the same defect and ran immediately before the failing Tool Gateway migration.
Standard output and error are copied concurrently as raw bytes, standard input
is fed after the drain starts, and secrets reach only the child environment
rather than the parent process.

The current local evaluation suite passes 52/52. The validator reports six
schemas, ten families/three implemented, held-out `UNAVAILABLE` with zero cases
because no held-out cases are registered, human baseline `UNAVAILABLE` with
zero cases because its root is not configured, three canonical results, eight
metrics, four negative cases, zero errors, checkpoint `PASS`, and phase exit
`BLOCK`.

PR-quality run `30209210001` and cross-service run `30209209999` are terminal
green for revision `df4620313a3f39721ef1bb521a9cf7ddcac5929c`.
The cross-service run executes fresh disposable A/B/C through Platform, AI
Runtime, Tool Gateway, PostgreSQL, Prometheus, and operator projection; A/B/C
score `PASS` with samples `100/1/1`, all eight metrics pass, and `GitTree=0`.
Run `30200584275` on `134d63c` remains the historical first green run and is
superseded by this confirmation.

Five defects closed between the previous transcript and that run: a shell
argument boundary that split the probe command, an evidence identity contract
that rejected the version 8 UUIDs the platform derives, a cost budget that was
unreachable rather than strict, a reported cost that did not survive the durable
numeric scale, and an artifact reference that named a transient working path.

Artifact `8634029083` is 221,461 bytes, was created
2026-07-26T16:01:34Z, and expires 2026-10-24T15:54:25Z. The preceding
`5dfbc00` artifact `8633891153` has independently recomputed ZIP SHA-256
`78d0f930e4cbcc55f6c2afd7e46fb5624642d485f62b579668e40eeefe903834`.
Its attested component hashes are recorded in the parent plan.

Two independent process-supervision reviews now pass. Linux probes show no
leaked detached child and no leak after controller `SIGKILL`; 4 MiB transport
completes in about 5-8 seconds. Windows path-safety reports
`LargeTransport=PASS` and `LateCleanupStatus=PASS`. The design uses a Windows
Job Object and Linux `setsid`/subreaper/pidfd with authenticated status, an EOF
ownership lease, and fail-closed `/proc` identity metadata. Phase 8 exit still
stays `BLOCK` on held-out cases, qualified human records/adjudication,
calibration, and human comparison.

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
- [x] Independent review has zero blocking findings.
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
