# V009 catalog boolean representation mismatch

## Executive summary

PR-quality run `30249026086` failed only in PostgreSQL trust job
`89923612844`. Seed cardinality, pre-index append latency, induced
non-transactional failure, failed-history proof, repair, and V009 retry all
passed. The shell then rejected the valid post-recovery catalog because the
query concatenates `pg_index.indisvalid` into text, which PostgreSQL renders as
`true`; the assertion incorrectly expected psql's standalone boolean display
`t`.

This is an evidence-runner representation defect. It is not an invalid index,
another Flyway recovery failure, or an application/schema regression.

## Evidence and timeline

- Run: `30249026086`.
- Job: `89923612844`.
- Artifact: `postgres-trust-contracts` (`8646894325`).
- PostgreSQL: `17.10` in CI.
- V009 evidence step: `08:19:43Z` to `08:41:20Z`.
- Seed passed:
  - target incident rows: 50,000;
  - target investigation rows: 50,000;
  - distractor incident rows: 10,000;
  - distractor investigation rows: 10,000;
  - batch size: 50;
  - `max_locks_per_transaction`: 64.
- Pre-index append passed:
  - incident p95: `1.561 ms`;
  - investigation p95: `2.348 ms`.
- Recovery passed:
  - failed history: `9:009:false`;
  - induced partial catalog:
    `incident_timeline_activity_order_idx:true`,
    `investigation_run_events_activity_order_idx:false`;
  - successful retry: `239.681 ms`;
  - `V009FlywayRecovery=PASS`.
- The transcript ends after the successful recovery test and before the
  post-index append phase; cleanup passed.

## Hypotheses tested

1. **The raw Flyway history selector still fails — eliminated.**
   `V009RecoveryFailedHistory=9:009:false` and
   `V009FlywayRecovery=PASS` prove the failed row was found, repaired, and
   retried successfully.
2. **One of the post-recovery indexes is invalid — eliminated.**
   The recovery harness requires both exact indexes to be valid after retry,
   and V009 completed at version `009`.
3. **The shell compares against the wrong boolean text representation —
   confirmed.** The catalog query uses
   `class.relname || ':' || index.indisvalid`. PostgreSQL 18.4 locally returned
   `index:true|true|t` for concatenation, explicit `::text`, and standalone
   boolean output respectively. The shell expected `:t`, so exact string
   equality failed on a valid `:true` result.

## Exact defect contract

- Symptom: the CI shell exits immediately after
  `V009FlywayRecovery=PASS`, with no post-index benchmark markers.
- Reproduction:
  evaluate the current catalog expression and compare its two-line result with
  the old `:t` expectation.
- Expected: both exact indexes with textual validity `true` pass.
- Actual: PostgreSQL emits `true`, while the assertion expects `t`.
- Root cause:
  `scripts/validation/phase-04b-evidence-records/incident-timeline-v009-evidence.sh`
  conflates psql's standalone display with PostgreSQL boolean-to-text casting.
- Why now: the prior selector fix allowed the job to reach this post-recovery
  assertion for the first time.
- Blast radius: only the V009 evidence shell's post-recovery catalog assertion
  and its static validator markers.

## Fix

- Compare the exact ordered catalog against `:true` for both index names.
- Print `V009PostRecoveryIndexCatalog` before asserting so future failures
  preserve actual catalog state in the artifact.
- Pin the marker plus both exact valid-index strings in the Phase 4B static
  validator.
- Preserve exact equality: missing, invalid, duplicated, or extra catalog rows
  still fail.

## Validation

- PostgreSQL 18.4 representation probe: `index:true|true|t`.
- Positive exact two-index catalog: pass.
- Negative catalog with the investigation index `false`: rejected.
- Bash syntax: pass.
- Node syntax: pass.
- Phase 4B static validator:
  `Errors=0`, `CheckpointResult=PASS`.
- `git diff --check`: pass.

## Recurrence prevention

The evidence transcript now records the post-recovery catalog before equality
evaluation, and the validator pins the exact `true` representation for both
indexes. A future representation drift can no longer fail silently after a
successful recovery.

## Unresolved questions

- Fresh PostgreSQL 17 CI must execute post-index append, query-plan, tenant
  isolation, and messaging gates before merge.
