# V009 Distractor Batch Atomicity Production-Readiness Review

## Code Review Summary

### Scope

- Base: `f4c8a4ab7c09bbcbbc1561e39ee55829cc26c904`
- Changed files:
  - `scripts/validation/phase-04b-evidence-records/incident-timeline-v009-seed.sql`
  - `scripts/validation/validate-phase-04b-evidence-records.mjs`
- Context inspected:
  - `V006__investigation_run_persistence.sql`
  - `V007__bounded_evidence_records.sql`
  - `V008__accepted_analysis_event_binding.sql`
  - V009 evidence runner and disposable-upgrade cleanup
  - `debugger-v009-distractor-batch-atomicity-2026-07-27.md`
- Diff: 100 additions, 36 deletions.
- Focus: PostgreSQL data-modifying CTE visibility, immediate/deferred triggers,
  one-to-one fixture mapping, batch bounds, failure atomicity, and constraint
  bypass.

### Overall Assessment

**PASS — no P0, P1, P2, or P3 findings.**

The patch removes the invalid standalone distractor snapshot transaction and
creates each run plus its `RUN_STARTED` event in one generated SQL statement.
The reviewed shape satisfies the production immediate append trigger and the
deferred snapshot/event-count invariant. Each successful transaction covers
exactly 50 distinct runs; a failing event rolls back its matching run batch.

### Critical Issues

None.

### High Priority

None.

### Medium Priority

None.

### Low Priority

None.

### Scout Findings and Adjudication

#### Data-modifying CTE and trigger visibility

- `incident-timeline-v009-seed.sql:324-380` inserts runs in a data-modifying
  `runs` CTE and consumes its `RETURNING` rows in the event insert.
- The production V008 `BEFORE INSERT` trigger acquires the run advisory lock,
  then selects the authoritative run `FOR UPDATE` at
  `V008__accepted_analysis_event_binding.sql:259-280`.
- A PostgreSQL 18.1 probe used the same CTE shape, a production-like immediate
  trigger with `SELECT ... FOR UPDATE`, and a deferred count trigger. Result:
  `50 runs / 50 events`; no visibility error.
- **Adjudication:** rejected concern that the immediate trigger cannot see the
  CTE-inserted run. Runtime probe proves the required SPI visibility.

#### Deferred event-count constraint

- New runs declare `event_count=1` at
  `incident-timeline-v009-seed.sql:326-342`.
- Their matching sequence-1 events are inserted in the same statement at lines
  344-380.
- The production constraint trigger is `DEFERRABLE INITIALLY DEFERRED` and
  validates ledger maximum against snapshot count at
  `V006__investigation_run_persistence.sql:476-506`.
- The PostgreSQL probe committed all 50 pairs with the deferred validator
  enabled.
- **Adjudication:** original split-transaction failure is closed.

#### One-to-one event mapping

- Fixture `run_id` and `run_event_id` values derive deterministically from
  `sample_no` at `incident-timeline-v009-seed.sql:260-269`.
- The event insert joins the CTE output back to the fixture only by the returned
  `run_id` at line 380.
- Independent enumeration found zero duplicate run IDs and zero duplicate event
  IDs across all 10,000 fixtures. The successful insert also relies on the
  production run primary/unique and event primary-key constraints.
- PostgreSQL probe reported zero run/event mapping mismatches.
- **Adjudication:** no fan-out, dropped row, or event-ID reuse found.

#### Exact ranges and advisory-lock bound

- Distractor batches use `LEAST(batch_start + 49, 10000)` and
  `generate_series(1, 10000, 50)` at
  `incident-timeline-v009-seed.sql:381-383`.
- Independent enumeration: 200 contiguous batches, 10,000 rows, maximum width
  50, no gap or overlap.
- V008 takes one transaction advisory lock per `(organization_id, run_id)` at
  `V008__accepted_analysis_event_binding.sql:259-262`. Each statement contains
  at most 50 unique runs, so it takes at most 50 such locks.
- Probe observed exactly 50 advisory locks during the statement and zero after
  autocommit.

#### Autocommit, propagation, and cleanup

- The evidence runner explicitly uses `--no-psqlrc`,
  `ON_ERROR_STOP=1`, and `AUTOCOMMIT=on` at
  `incident-timeline-v009-evidence.sh:36-42`.
- `\gexec` executes each generated batch as its own transaction. Statement
  failure rolls back both the CTE run insert and event insert.
- Shell `set -e` propagates the psql failure. The EXIT trap drops the disposable
  upgrade database at `run-phase-04b-migration-upgrade.sh:32-52`, including
  already committed earlier batches.

#### Production constraints remain active

- No trigger disable/drop, `session_replication_role`, constraint removal, or
  migration change appears in the diff.
- V006 run-write, event-append, immutable-ledger, foreign-key, and deferred
  count constraints remain active. V007's event tool-identity trigger also
  remains active; `RUN_STARTED` correctly carries no evidence tool identity.
- The static validator rejects trigger disablement, transaction wrapping, and
  autocommit disablement at
  `validate-phase-04b-evidence-records.mjs:187-216`.

### Structural Regression Guard

- `validate-phase-04b-evidence-records.mjs:108-186` requires the bounded
  distractor range and deterministic join, checks the ordered CTE-to-event
  statement structure, and rejects a standalone pre-batch distractor run insert.
- In-memory checks:
  - base `f4c8a4a` split form: rejected because atomic sequence is absent;
  - current form: accepted;
  - synthetic current form with restored standalone snapshot insert: rejected.

### Behavioral Checklist

- Concurrency: transaction advisory-lock scope and release verified.
- Error boundaries: psql error propagation and EXIT cleanup verified.
- API contracts: CTE `RETURNING`, trigger lookup, and deferred-count contracts
  verified against actual migrations and runtime probe.
- Backwards compatibility: no production migration or exported interface change.
- Input validation/authz: no external input or sensitive operation added; owner
  fixture path does not disable production constraints.
- Query efficiency: 200 bounded statements; no unbounded database-call loop.
- Data leaks: no secrets, PII, stack traces, or prohibited evidence fields added.
- Plan fact-check: no plan supplied; debugger claims checked against code and
  independent probe.

### Lightweight Verification

- `node --check scripts/validation/validate-phase-04b-evidence-records.mjs`:
  pass.
- `node scripts/validation/validate-phase-04b-evidence-records.mjs`:
  `Errors=0`, `CheckpointResult=PASS`,
  `V009DatabaseGate=ENVIRONMENT_REQUIRED`.
- `bash -n` on the V009 evidence runner: pass.
- PostgreSQL 18.1 production-like atomicity probe:
  `50|50|50|0|0` for run rows, event rows, locks during statement, locks after
  commit, and mapping mismatches.
- Independent range and deterministic-ID enumeration: pass.
- Static negative-mutation checks: prior and synthetic split forms rejected.
- Targeted trigger-bypass/secret scan: no implementation match.
- `git diff --check HEAD`: pass.
- Full Maven/Docker/PostgreSQL 17 CI gate not run in this review.

### Metrics

- Type coverage: not applicable to SQL fixture change.
- Test coverage: focused static and PostgreSQL behavioral probes passed; full
  suite coverage not collected.
- Lint/syntax issues: 0 in focused checks.

### Recommended Actions

1. Run the fresh PostgreSQL 17 Phase 4B CI gate before landing.

### Unresolved Questions

- Does the complete PostgreSQL 17 job pass the remaining recovery, index, query
  plan, latency, storage, tenant, and messaging gates after this seed fix?

Status: DONE

Summary: PASS. Atomic distractor batching satisfies production trigger and
deferred-count invariants; no P0-P3 found.

Concerns/Blockers: No code-review blocker. Fresh PostgreSQL 17 CI evidence remains
the pre-landing environment gate.
