# V009 Flyway Lock Fix Production-Readiness Review

## Code Review Summary

### Scope

- Focus: uncommitted V009 CI deadlock fix and affected migration callers
- Changed files: 7
- Diff size: 60 added/deleted lines including the untracked configuration test
- Surrounding paths reviewed: V009 SQL/sidecar, Compose migration/runtime split, PR PostgreSQL job, Phase 4B upgrade/recovery gate, cross-service migration harness

### Overall Assessment

**PASS — no P0, P1, or P2 findings.**

The diagnosis reaches the root cause, not only the timeout symptom. V009 is non-transactional because it executes `CREATE INDEX CONCURRENTLY`, but Flyway's default PostgreSQL transactional advisory lock keeps a separate transaction open around migration execution. Setting `spring.flyway.postgresql.transactional-lock=false` selects Flyway's documented session-level advisory lock. Flyway 12.4.0 still derives the same lock identifier from the schema-history table, retries acquisition, serializes competing migrators, and releases the session lock in `finally`.

Spring Boot 4.1.0 exposes and applies the exact nested property. The checked-in profile binds it at `services/platform-api/src/main/resources/application-persistence.yaml:18`, and the regression test validates the real Boot `FlywayProperties` shape at `services/platform-api/src/test/java/ai/opsmind/platform/persistence/FlywayPostgresqlLockConfigurationTest.java:23`.

The latest diff also covers the one caller that bypasses Boot: `FlywayRecoveryHarnessTest` obtains Flyway's `PostgreSQLConfigurationExtension`, sets the same mode, and asserts it before loading Flyway at `services/platform-api/src/test/java/ai/opsmind/platform/persistence/FlywayRecoveryHarnessTest.java:43`.

### P0

None.

### P1

None.

### P2

None.

### P3

- `scripts/validation/validate-phase-04b-evidence-records.mjs:230` — Fresh verification did not execute the PostgreSQL-backed V009 path; the validator explicitly reports `V009DatabaseGate=ENVIRONMENT_REQUIRED`. The compile/unit check proves configuration binding and the direct Flyway extension call, but not the original PostgreSQL 17 wait sequence. This is an evidence gap, not a code defect, because the checked-in PR job exercises both the Boot migrator and disposable V006-to-V009 recovery path at `.github/workflows/pr-quality.yml:606` and `.github/workflows/pr-quality.yml:649`.
  - Action: require a fresh successful PostgreSQL 17 PR job or equivalent disposable local gate before claiming the incident resolved.

### Edge Cases Found by Scout

- Boot migration entry points use the `persistence` profile: Compose at `compose.yaml:163`, cross-service harness at `scripts/validation/cross-service/run-cross-service-verification.ps1:275`, and upgrade harness at `scripts/validation/run-phase-04b-migration-upgrade.sh:62`.
- Long-running Platform API keeps Flyway disabled at `compose.yaml:215`; the change does not expand runtime database authority.
- The Phase 4B recovery test was a separate direct `Flyway.configure()` path and initially would not have inherited the Boot property. The latest diff addresses it at `FlywayRecoveryHarnessTest.java:43-48`, and the Phase 4B validator pins the marker at `validate-phase-04b-evidence-records.mjs:158`.
- Session-level and transaction-level PostgreSQL advisory locks conflict on the same Flyway key, so session locking preserves mutual exclusion, including overlap with an older transactional-lock runner. Session termination also releases the lock.
- No API, auth/authz, input, PII, secret, schema-byte, or dependency surface changed.

### Two-Pass Checklist

- Critical/blocking pass: no findings.
- Informational pass: one P3 evidence gap above.
- Suppressions checked: no style-only, already-addressed, or hypothetical findings reported.

### Verification

- Focused Maven: 2 tests, 0 failures, 0 errors, 1 expected environment skip; build success.
- Phase 3 trust-foundation validator: `Errors=0`, `Result=PASS`.
- Phase 4B static validator: `Errors=0`, `CheckpointResult=PASS`; database gate remains environment-required.
- `git diff --check`: pass.
- User-supplied broader evidence: platform Maven verify 245 tests, 0 failures, 30 environment skips; Phase 3, Phase 4B static, repository layout, and diff checks pass.

### Behavioral Checklist

- Concurrency: checked; session advisory lock preserves migration serialization.
- Error boundaries: checked; Flyway owns lock release and migration error propagation.
- API contracts/backwards compatibility: no exported API or schema-byte change.
- Input validation/auth/authz/data leaks/N+1: no affected boundary.
- Scope: configuration, regression tests, static contract markers, and accurate operational documentation only.

### Unresolved Questions

- Will the fresh PostgreSQL 17 CI run complete both the initial Boot V009 migration and the direct Flyway invalid-index recovery path within the job timeout?
