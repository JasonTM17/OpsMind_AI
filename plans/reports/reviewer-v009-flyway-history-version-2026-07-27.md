# V009 Flyway History Selector Production-Readiness Review

## Code Review Summary

### Scope

- Base: `3dedbdeddceb9dcddf2c743551b8511c2995d6f5`
- Changed files:
  - `services/platform-api/src/test/java/ai/opsmind/platform/persistence/FlywayRecoveryHarnessTest.java`
  - `scripts/validation/validate-phase-04b-evidence-records.mjs`
- Context:
  - `V009__incident_activity_timeline_indexes.sql`
  - V009 evidence runner and recovery invocation
  - Flyway Core 12.4.0 runtime/bytecode
  - `debugger-v009-flyway-history-version-2026-07-27.md`
- Diff: 7 additions, 2 deletions.
- Focus: raw versus semantic Flyway versions, failed-history retention, repair,
  retry proof, exact index state, and false-green paths.

### Overall Assessment

**PASS — no P0, P1, P2, or P3 findings.**

`V009_HISTORY_VERSION = "009"` matches the raw version Flyway 12.4.0 persists
for `V009__...sql`. The same value remains a valid semantic target because
Flyway compares parsed numeric version parts. The patch changes only the
selector contract; failure induction, failed-history proof, repair, retry, and
exact index assertions remain intact.

### Critical Issues

None.

### High Priority

None.

### Medium Priority

None.

### Low Priority

None.

### Scout Findings

#### Raw and semantic version behavior

- Migration filename is
  `services/platform-api/src/main/resources/db/migration/V009__incident_activity_timeline_indexes.sql`.
- Flyway 12.4.0 runtime probe:
  - `MigrationVersion.fromVersion("009").toString()` = `009`;
  - `getVersion()` = `009`;
  - equality with `MigrationVersion.fromVersion("9")` = `true`;
  - `compareTo(...)` = `0`.
- Flyway bytecode stores the constructor input as display/raw text while parsing
  numeric parts separately for comparison. Therefore
  `FlywayRecoveryHarnessTest.java:40-43` targets semantic V009 correctly.
- PostgreSQL probe returned `false|true` for text equality
  `'009' = '9'` and integer equality `'009'::integer = '9'::integer`.
  The direct history query at `FlywayRecoveryHarnessTest.java:123-128` therefore
  must use raw `009`.

#### Failed history retention

- V009 contains two `CREATE INDEX CONCURRENTLY` statements at migration lines
  4-8 and is intentionally non-transactional.
- Flyway 12.4.0 `DbMigrate` bytecode calls
  `SchemaHistory.addAppliedMigration(..., false)` on the non-transactional
  migration failure path.
- `JdbcTableSchemaHistory.doAddAppliedMigration` serializes the version with
  `MigrationVersion.toString()`, producing `009`.
- The test still requires the failed migration and the intended partial catalog
  state at `FlywayRecoveryHarnessTest.java:52-69`.

#### Repair and retry

- Flyway 12.4.0 `removeFailedMigrations` filters rows where
  `AppliedMigration.isSuccess()` is false and deletes the matching stored
  version. Official Redgate documentation also defines repair as removing
  failed migrations:
  [Flyway repair](https://documentation.red-gate.com/flyway/reference/commands/repair).
- The harness drops both exact partial indexes, invokes `flyway.repair()`, and
  asserts no failed raw-009 row remains at
  `FlywayRecoveryHarnessTest.java:71-73`.
- Retry must then reach semantic successful version 9 and recreate both exact
  indexes as valid at lines 74-84. These assertions are unchanged.

#### Recovery proof remains strong

- The invalid investigation index is deliberately induced and asserted invalid
  at `FlywayRecoveryHarnessTest.java:89-100`.
- Catching an unrelated Flyway exception cannot false-green: the next catalog
  assertion requires the exact expected partial state—incident index valid and
  investigation index invalid—at lines 60-65.
- Repair cannot false-green as a no-op: the failed selector must become empty,
  retry must succeed, semantic version must advance to 9, and both exact indexes
  must be valid.
- `successfulVersion()` normalizes versions with `version::integer`, but this
  does not hide the original defect because failed-history presence/removal is
  separately checked with exact raw text.

#### Static regression guard

- `validate-phase-04b-evidence-records.mjs:260-272` now pins:
  - `V009_HISTORY_VERSION = "009"`;
  - `.target(V009_HISTORY_VERSION);`;
  - the direct `WHERE version = ... V009_HISTORY_VERSION` fragment;
  - existing repair, lock, failed-history, exact-catalog, and environment-gate
    markers.
- This prevents the constant from remaining as dead text while either consumer
  silently drifts back to a different literal.

### Adversarial Adjudication

- **Rejected:** target `009` skips or duplicates semantic version 9. Flyway
  12.4.0 runtime equality and comparison prove equivalence.
- **Rejected:** failed non-transactional migration leaves no history row.
  Flyway failure-path bytecode explicitly records `success=false`; prior CI
  failure also reached the exact selector assertion after migration failure.
- **Rejected:** selector now matches an unrelated failed migration. It filters
  exact raw version `009`, and the catalog assertion requires V009's exact
  partial-index side effects.
- **Rejected:** `repair()` could pass without removing the failed row. Immediate
  post-repair raw selector plus successful retry/index assertions prevent it.
- **Rejected:** SQL injection through string concatenation. The value is a
  private compile-time constant, not external input.

### Behavioral Checklist

- Concurrency: non-transactional concurrent-index partial state and lock
  configuration preserved.
- Error boundaries: Flyway exception is caught only to assert failure; later
  history/catalog checks prove the expected cause and state.
- API contracts: Flyway raw/display and semantic version behavior independently
  verified against pinned 12.4.0 jar.
- Backwards compatibility: production migration and runtime code unchanged.
- Input validation/authz: no external input or sensitive operation added.
- Query efficiency: constant-time test history/catalog queries; no N+1.
- Data leaks: no secret or PII output added.
- Plan fact-check: no plan supplied; debugger claims checked against diff,
  pinned bytecode, SQL probe, and test structure.

### Lightweight Verification

- Flyway 12.4.0 JShell version probe: pass.
- Flyway 12.4.0 bytecode inspection for history insert/failure/repair: pass.
- Offline Maven `test-compile`: pass.
- Focused `FlywayRecoveryHarnessTest`: loads with 0 errors/failures and skips
  exactly once because the disposable-database enable flag is absent locally.
- `node --check scripts/validation/validate-phase-04b-evidence-records.mjs`:
  pass.
- Phase 4B static validator: `Errors=0`, `CheckpointResult=PASS`,
  `V009DatabaseGate=ENVIRONMENT_REQUIRED`.
- PostgreSQL raw-text selector probe: `'009' = '9'` is false; integer semantic
  equality is true.
- `git diff --check HEAD`: pass.
- Full PostgreSQL 17 recovery gate not run in this review.

### Metrics

- Type coverage: not collected; Java test compilation passed.
- Test coverage: focused harness loaded, but runtime recovery path requires the
  disposable CI database.
- Lint/syntax issues: 0 in focused checks.

### Recommended Actions

1. Rerun the PostgreSQL 17 Phase 4B job before landing.

### Unresolved Questions

- Does fresh CI complete the full failure-history, repair, retry, post-index,
  tenant, and messaging sequence?

Status: DONE

Summary: PASS. Raw V009 selector now matches Flyway 12.4.0 history semantics;
strong recovery/index assertions remain intact; no P0-P3 found.

Concerns/Blockers: No code-review blocker. Fresh PostgreSQL 17 recovery evidence
remains the pre-landing gate.
