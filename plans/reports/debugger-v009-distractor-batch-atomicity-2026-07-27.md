# V009 distractor batch atomicity

## Executive summary

PR-quality run `30244230009` failed only in PostgreSQL trust job
`89908503990`. Fresh owner migration completed in 23 seconds, every
non-PostgreSQL quality job passed, and cross-service run `30244230024` passed.
The V009 evidence seed then failed because each distractor snapshot declared
`event_count=1` and committed before its matching event existed.

This is the second disposable evidence-fixture defect. The previous advisory
lock exhaustion is fixed: this run passed the old failure point and emitted no
shared-lock error. Production triggers and constraints behaved correctly.

## Evidence and timeline

- `07:00:25Z` — V009 evidence step started.
- The batched target investigation data completed beyond the prior
  `out of shared memory` failure point.
- The standalone bulk `INSERT INTO investigation_runs` for 10,000 distractors
  reached its transaction boundary without matching ledger events.
- PostgreSQL raised:
  `ERROR: investigation snapshot event count must match the event ledger`.
- Error context named `opsmind_validate_investigation_event_count()`.
- `07:19:02Z` — the step failed after 18 minutes 37 seconds.
- Artifact `8644897589` preserves the failing transcript.

## Hypotheses tested

1. **Advisory-lock exhaustion recurred — eliminated.** The run passed the old
   failure time and statement without `out of shared memory` or
   `max_locks_per_transaction`.
2. **Migration, index recovery, query plan, or latency threshold failed —
   eliminated.** Fresh migration passed. The seed failed before recovery,
   benchmark, and threshold output.
3. **Snapshot and ledger were split across transactions — confirmed.**
   Autocommit correctly bounded locks, but it also committed the standalone
   distractor snapshot insert. The deferred invariant observed
   `event_count=1` with zero matching events and rejected the transaction.

## Fix

- Remove the standalone distractor snapshot insert.
- Generate 200 statements covering `sample_no` ranges `1..10000`, 50 rows per
  statement.
- In each statement, insert snapshots through a data-modifying `runs` CTE and
  feed its `RETURNING` rows directly into the matching `RUN_STARTED` event
  insert.
- Join the deterministic temporary fixture by `run_id` only to recover each
  precomputed `run_event_id`.
- Keep production triggers enabled and preserve the exact 10,000-row
  distractor cardinality.
- Keep autocommit and the 50-key transaction bound. A failure rolls back both
  sides of the affected batch inside the disposable upgrade database.

## Validation

- Phase 4B static validator: `Errors=0`, `CheckpointResult=PASS`.
- Shell syntax, Node syntax, `git diff --check`, and targeted secret scan:
  pass.
- PostgreSQL 18 probe executed the same 50-row data-modifying CTE shape with:
  - an immediate event trigger that selects and locks the authoritative run
    with `FOR UPDATE`, matching the production visibility requirement;
  - a deferred constraint trigger that rejects run/event count mismatch.
- Probe committed exactly `50/50` run/event rows. This proves trigger
  visibility and deferred invariant satisfaction for the chosen statement
  shape.
- Validator now pins the one-to-one distractor join marker in addition to the
  existing range, autocommit, transaction, trigger, and cardinality guards.
- An in-memory negative mutation restored a pre-batch distractor snapshot
  insert; the atomicity regression guard rejected it.

## Recurrence prevention

Transaction boundaries are part of fixture correctness, not only fixture
performance. Any seed for a snapshot plus append-only ledger must create the
snapshot and the ledger state that satisfies its deferred invariants in the
same transaction, while separately bounding per-aggregate advisory locks.

## Unresolved questions

- Fresh PostgreSQL 17 CI must still prove the complete V009 seed, recovery,
  index, latency, storage, tenant, and messaging gates before merge.
