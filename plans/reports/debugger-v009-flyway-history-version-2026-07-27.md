# V009 Flyway history version mismatch

## Executive summary

PR-quality run `30246495860` failed only in PostgreSQL trust job
`89915651246`. The corrected seed completed with exact target and distractor
cardinality, and the pre-index append benchmark passed. The recovery harness
then induced the intended non-transactional V009 failure but queried Flyway
history with `version = '9'`. Flyway 12.4.0 stores this migration's raw version
as `009`, so the harness filtered out the failed row it was meant to prove.

This is a recovery-test selector defect. It is not another seed failure, a
missing failed-history record, a migration-lock regression, or an append
performance failure.

## Evidence and timeline

- `07:38:52Z` — V006-to-V009 evidence step started.
- `07:57:43Z` — seed completed:
  - target incident rows: 50,000;
  - target investigation rows: 50,000;
  - distractor incident rows: 10,000;
  - distractor investigation rows: 10,000;
  - batch size: 50;
  - live `max_locks_per_transaction`: 64.
- `07:57:49Z` — pre-index append harness passed:
  - incident p95: `1.302 ms`;
  - investigation p95: `2.154 ms`.
- `07:57:53Z` — Flyway reported the expected non-transactional V009 failure.
- The harness failed at line 61 because `failedV009History()` returned an empty
  list.
- Cleanup passed.
- Artifact `8645804415` preserves the evidence transcript.

## Hypotheses tested

1. **Seed atomicity or advisory-lock exhaustion recurred — eliminated.** All
   four exact row-count assertions passed with a 50-lock batch and the append
   benchmark started.
2. **Flyway did not persist non-transactional failure history — eliminated.**
   Flyway 12.4.0 `DbMigrate` bytecode calls
   `SchemaHistory.addAppliedMigration(..., false)` on this failure path. Its
   log also identified V009 as non-transactional and emitted the restore
   warning associated with that path.
3. **History selector used the wrong textual version — confirmed.**
   `JdbcTableSchemaHistory` persists `MigrationVersion.toString()`.
   Flyway 12.4.0 runtime inspection produced:
   - `MigrationVersion.fromVersion("009").toString() = "009"`;
   - `getVersion() = "009"`;
   - semantic equality with version `"9"` is true.
   SQL text equality still makes `'009' <> '9'`, exactly explaining the empty
   result.

## Fix

- Define one `V009_HISTORY_VERSION = "009"` constant in the recovery harness.
- Use it for both Flyway target selection and direct schema-history filtering.
- Keep the strong assertion that failed V009 history is present.
- Keep repair, exact valid/invalid index catalog assertions, and successful
  retry proof unchanged.
- Pin the raw V009 history version in the Phase 4B static validator.

## Validation

- Flyway 12.4.0 runtime inspection confirms the raw/display version is `009`
  while semantic equality with `9` remains true.
- Phase 4B static validator:
  `Errors=0`, `CheckpointResult=PASS`.
- Offline Maven compilation succeeded for all 100 test sources.
- Focused recovery test loads successfully and skips only because the
  disposable-database enable flag is intentionally absent locally.
- Node syntax, `git diff --check`, and targeted secret scan: pass.

## Recurrence prevention

Tests that inspect `flyway_schema_history` directly must use the raw migration
version stored by the pinned Flyway release. Semantic `MigrationVersion`
equality does not imply SQL string equality. The validator now prevents this
zero-padding contract from silently drifting again.

## Unresolved questions

- Fresh PostgreSQL 17 CI must execute the full recovery path and the remaining
  post-index, tenant, and messaging gates before merge.
