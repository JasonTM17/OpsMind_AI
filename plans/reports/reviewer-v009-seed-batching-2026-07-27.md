# V009 Seed Batching Production-Readiness Re-review

## Code Review Summary

### Scope

- Files:
  - `scripts/validation/phase-04b-evidence-records/incident-timeline-v009-evidence.sh`
  - `scripts/validation/phase-04b-evidence-records/incident-timeline-v009-seed.sql`
  - `scripts/validation/validate-phase-04b-evidence-records.mjs`
- Diff: 103 additions, 9 deletions.
- Focus: P3 hardening follow-up for advisory-lock-safe fixture batching.
- Scout focus: caller transaction flags, psql autocommit state, generated range
  boundaries, exact fixture cardinality, live lock configuration, temporary-table
  lifetime, trigger execution, and failure cleanup.

### Overall Assessment

**PASS — no P0, P1, P2, or P3 findings.**

The 50-row batches bound each generated statement to at most 50 distinct run
advisory locks. Exact target/distractor cardinality and the live
`max_locks_per_transaction` setting are now asserted by the evidence runner.
The caller explicitly sets psql `AUTOCOMMIT=on`; the current execution path is
safe and still exercises the production V008 trigger.

### Critical Issues

None.

### High Priority

None.

### Medium Priority

None.

### Low Priority

None.

### Prior P3 Adjudication

- **Exact cardinality and lock headroom: closed.**
  `incident-timeline-v009-evidence.sh:53-83` asserts exactly 50,000 target rows
  per source, exactly 10,000 distractor rows per source, and live
  `max_locks_per_transaction >= 50`; lines 84-90 emit those values as evidence.
- **Autocommit invariant: closed.**
  `validate-phase-04b-evidence-records.mjs:128-159` checks that the outer
  transaction ends before the two `\gexec` batch families, rejects a later
  `BEGIN`, rejects both `--single-transaction` and its `-1` alias, and rejects
  false/off/zero `AUTOCOMMIT` assignments in either the seed or caller.
  Lines 88-106 pin `--no-psqlrc`, `ON_ERROR_STOP`, and the caller's explicit
  `AUTOCOMMIT=on`.

### Edge Cases Found by Scout

- Target ranges are 1,000 contiguous batches: generated IDs `2..50000`, 49,999
  generated rows, plus the fixed target row for exactly 50,000.
- Distractor ranges are 200 contiguous batches: generated IDs `1..10000`, exactly
  10,000 rows.
- The outer seed transaction ends at
  `incident-timeline-v009-seed.sql:190`, before either batch family.
- The evidence runner explicitly passes `--set AUTOCOMMIT=on` at line 41.
- `phase_v009_distractors` uses PostgreSQL's default `ON COMMIT PRESERVE ROWS`;
  it survives autocommitted batches in the same psql session and is explicitly
  dropped.
- No later `BEGIN`, trigger disable/drop, or trigger-function change exists.
- `ON_ERROR_STOP` plus shell `set -e` propagates generated-statement failure to
  the EXIT cleanup; partial commits stay inside the disposable upgrade database.
- Target runs and events remain in one statement per batch, so a trigger failure
  rolls back that batch's newly inserted runs.

### Recommended Actions

1. Rerun the PostgreSQL 17 Phase 4B database gate to produce fresh runtime
   evidence for the 50-row batches.

### Metrics

- Type coverage: not applicable to shell/SQL fixture changes.
- Test coverage: focused static gate passed; database coverage not run locally.
- Linting/syntax issues: 0 in focused checks.

### Lightweight Verification

- `node scripts/validation/validate-phase-04b-evidence-records.mjs`:
  `Errors=0`, `CheckpointResult=PASS`; database gate remains
  environment-required.
- `bash -n` for the V009 evidence runner: pass.
- Independent range enumeration:
  - target: 1,000 batches, 49,999 generated rows, no gaps/overlaps;
  - distractor: 200 batches, 10,000 rows, no gaps/overlaps.
- Source scan: validator rejects `--single-transaction`, `-1`, caller
  `AUTOCOMMIT=off|false|0`, seed `\set AUTOCOMMIT off|false|0`, later `BEGIN`,
  and trigger disablement.
- `git diff --check HEAD`: pass.
- Docker and Maven intentionally not run due the stated C: capacity constraint.

### Unresolved Questions

- Will the fresh PostgreSQL 17 Phase 4B database gate pass on the GitHub Actions
  service with the 50-lock batches?

Status: DONE

Summary: Both prior P3 findings are closed. Current batching and its regression
guards pass review; no P0-P3 found.

Concerns/Blockers: No code-review finding. Fresh PostgreSQL 17 database evidence
remains a pre-landing environment gate and was not run under the stated local
capacity constraint.
