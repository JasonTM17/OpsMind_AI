---
phase: 2
title: "Cross-Service Artifact Harvesting"
status: in-progress
priority: P1
dependencies: [1]
effort: "0.5-1 day"
---

# Phase 2: Cross-Service Artifact Harvesting

## Overview

Wire the pure Phase 8B projector into the existing Phase 7 disposable
cross-service harness after all runs finish and before Git/revision
attestation. Do not expose a new HTTP endpoint.

## Requirements

- Migration owner creates disposable-only evaluator roles and security-barrier
  views, then export runs as NOBYPASSRLS/NOINHERIT read-only identities.
- Exact tenant/actor/run scope is transaction-local and verified by every view.
- Export to the managed run directory, never an arbitrary path.
- Atomically replace the trace only after full validation.
- Never pass JSON through environment variables or command-line arguments.
- Preserve default 100-warm-run Phase 7 counts and latency behavior.
- SQL bounds row count, each JSON value and aggregate bytes before client
  materialization.

## Data Flow

`run-investigation-slice.mjs` writes bounded operator trace -> PowerShell runs
the checked-in SQL in disposable PostgreSQL -> UTF-8 export lands under
`.opsmind/cross-service/<run>/` -> projector validates and atomically enriches
the trace -> finalizer adds durable counts and Git cleanliness -> scorer reads
the enriched trace.

## Current Evidence

The evaluator roles/views, allowlist registration and cross-tenant proof,
bounded SQL export, managed-path utility, projector invocation, finalizer
checks, and success/failure cleanup are wired. Local projector/security tests
pass. Fresh harness execution is still pending, so no production-path export
or Phase 7 100-run compatibility claim is made from this worktree.

`-ReportPath` must be a descendant of `.opsmind/reports`; the default is
`.opsmind/reports/cross-service-trace.json`. The CI matrix uses that path for A
and `cross-service-trace.scenario-b.json` /
`cross-service-trace.scenario-c.json` for B/C. Per-run exports and the enriched
working file stay under `.opsmind/cross-service/<run-id>/`. An existing report
is archived under `.opsmind/reports/archive/` before publication. Cleanup
removes raw exports and the enriched working file on success or failure; the
whole run directory is removed only after success.

## Related Code Files

| Action | Path | Purpose |
|---|---|---|
| Create | `scripts/validation/cross-service/create-evaluation-export-roles.sql` | Disposable least-privilege views/roles |
| Modify | `scripts/validation/cross-service/cross-service-harness-support.ps1` | Bounded output and scoped evaluator session |
| Modify | `scripts/validation/cross-service/run-cross-service-verification.ps1` | Managed export/projector invocation |
| Modify | `scripts/validation/cross-service/cleanup-cross-service-run.ps1` | Unconditional transient export deletion |
| Reuse | `scripts/validation/safe-validation-evidence.mjs` | Reparse-safe publication boundary |
| Modify | `scripts/validation/cross-service/finalize-cross-service-report.mjs` | Require projected row counts/digests |
| Modify | `scripts/validation/cross-service/run-investigation-slice.mjs` | Declare projection absent until harvested |
| Modify | `scripts/validation/validate-phase-07-investigation-slice.mjs` | Validate enriched real trace without changing G3 |
| Modify | `scripts/validation/validate-phase-08-evaluation-foundation.mjs` | Require production-path harvesting source |

## Implementation Steps

1. Add focused PowerShell contract tests/static checks before wiring.
2. Create ephemeral roles/views only after production migrations in the
   disposable database. Assert role attributes/grants and prove cross-tenant
   reads return zero while exact-scope reads succeed.
3. Execute one `READ ONLY` scoped export with SQL-side count/size guards;
   reject blank, multi-document or oversized output.
4. Stream UTF-8 output without BOM under a validated managed root. Reject
   junction/reparse ancestors before every create/rename.
5. Invoke projector with file paths only; reject duplicate keys from raw bytes,
   validate all values, then use `wx` plus reparse-safe atomic publication.
6. Finalizer checks analysis/event/evidence/receipt counts against each run and
   adds SQL-manifest/export snapshot digests.
7. Cleanup always deletes transient raw export and ephemeral credentials on
   success/failure; postcondition verifies absence. Retain only redacted reason
   code and digests.

## Failure Matrix

| Failure | Behavior |
|---|---|
| SQL/query manifest missing or changed unexpectedly | stop; no enriched trace |
| DB row incomplete/foreign/oversized | stop; no partial artifact |
| Projector crash | original trace remains unclaimed/incomplete |
| Finalizer count mismatch | cross-service gate fails |
| Secret/reasoning pattern in export | gate fails; raw export deleted |
| Managed path contains reparse point | fail before write/rename |
| Role bypasses RLS or has raw-column grant | fail before export |

## Success Criteria

- [ ] A fresh cross-service run contains trusted normalized analysis, timeline,
      evidence and receipt projections for every completed run.
- [x] Local projection tests reject raw connector response, prompt, provider
      reasoning, credential, and unsafe-value fields.
- [ ] Existing Phase 7 100-run latency/count contract remains intact.
- [x] Missing harvesting makes Phase 8 scoring `INCOMPLETE`, never PASS.

## Risk Assessment

PowerShell/psql formatting and large output are the main hazards. Use SQL-side
bounds, unaligned streaming output and safe managed paths. No local heavy
execution while C/D safety thresholds fail; same-job CI builds and attests
every executed binary before providing integration proof.
