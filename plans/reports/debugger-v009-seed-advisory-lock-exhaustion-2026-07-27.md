# V009 seed advisory-lock exhaustion

## Executive summary

PR-quality run `30241930897` failed only in PostgreSQL trust job
`89901658608`. Fresh application migration completed in 24 seconds, Compose
passed, and cross-service run `30241930903` passed. The remaining V009 gate
failed before benchmarks or index recovery because the high-cardinality seed
held 49,999 distinct transaction-scoped advisory locks in one transaction.

This is a disposable evidence-fixture defect, not a production Flyway
deadlock, migration failure, query-plan regression, or failed performance
threshold.

## Evidence and timeline

- `06:17:44Z` — fresh owner migration started.
- `06:18:08Z` — fresh owner migration completed successfully.
- `06:18:20Z` — V006-to-V009 evidence step started.
- `06:31:16Z` — PostgreSQL emitted `ERROR: out of shared memory` with
  `HINT: You might need to increase "max_locks_per_transaction"`.
- The failing context is
  `opsmind_validate_investigation_event_append()` calling
  `pg_advisory_xact_lock(hashtextextended(organization_id || ':' || run_id))`
  for the single `generate_series(2, 50000)` event insert.
- `06:31:17Z` — step failed and cleanup passed.
- Artifact `8643933819` preserves
  `phase-04/evidence-migration-upgrade.txt`.

## Hypotheses tested

1. **Flyway transaction-lock self-wait recurred — eliminated.** The exact
   fresh migration path that previously hung completed in 24 seconds. V006,
   V007, and V008 also completed before the seed ran.
2. **A latency, storage, or query-plan threshold failed — eliminated.** The
   transcript ended at seed line 243 before row-count, append, recovery,
   query-plan, or metric output.
3. **Fixture exhausted PostgreSQL's shared lock table — confirmed.** The seed
   wrapped all generated investigation events in one outer transaction. Each
   distinct run acquired a transaction-scoped advisory lock that could not be
   released until commit. The database error names that trigger and lock call.

## Fix

- Commit the valid initial fixture transaction before high-cardinality run
  events.
- Generate target event batches for ranges `2..50000` in groups of 50.
- Generate distractor event batches for ranges `1..10000` in groups of 50.
- Use psql `\gexec`; autocommit gives each generated statement its own
  transaction and releases advisory locks between batches.
- Keep production triggers enabled. Do not raise PostgreSQL lock capacity,
  bypass constraints, reduce row counts, or weaken evidence thresholds.
- Preserve the distractor temp table across autocommitted statements, then
  drop it explicitly.

## Validation

- Phase 4B static validator: `Errors=0`, `CheckpointResult=PASS`.
- Range proof: target `49999/49999` unique values covering `2..50000`;
  distractor `10000/10000` unique values covering `1..10000`.
- A PostgreSQL 18 TEMP-table probe executed the same
  `SELECT format(...) \gexec` shape: three target batches and two distractor
  batches produced exact, gap-free ranges and five distinct transaction IDs.
  The temporary table survived every autocommit boundary and vanished with the
  client session.
- The local default `max_locks_per_transaction=64`,
  `max_connections=100` server acquired 500 transaction-scoped advisory locks
  in a probe transaction and released them on rollback. The checked-in batch
  is capped lower at 50 and the live gate asserts the server setting is at
  least 50.
- Validator now requires both bounded batch families, exactly two `\gexec`
  boundaries, an outer commit before batching, temp-table survival, and no
  trigger disable.
- `git diff --check`: pass.

## Recurrence prevention

Any high-cardinality fixture that exercises a trigger with
transaction-scoped per-aggregate advisory locks must bound unique aggregate
keys per transaction. Increasing `max_locks_per_transaction` would only hide
an unbounded fixture and make the gate host-dependent.

## Unresolved questions

- Fresh PostgreSQL 17 CI must prove psql batch execution, recovery, query
  plans, latency, storage, and the downstream tenant/messaging contracts before
  merge.
