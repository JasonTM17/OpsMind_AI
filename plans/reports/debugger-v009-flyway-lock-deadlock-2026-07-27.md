# V009 Flyway Lock Deadlock

## Executive Summary

PR #19 introduced two PostgreSQL `CREATE INDEX CONCURRENTLY` statements in
Platform migration V009. Flyway correctly executed V009 outside the migration
transaction, but retained its default PostgreSQL transaction-level advisory
lock on another connection. The concurrent index waited for that older
transaction; Flyway could not release it until the migration returned. Fresh
migrations therefore self-waited indefinitely.

The fix selects Flyway's session-level PostgreSQL advisory lock for every
Spring persistence-profile migration and for the programmatic V009 recovery
harness. Session locking preserves migrator mutual exclusion without keeping
the transaction that blocks concurrent index completion.

## Evidence

- PR-quality run `30237306482`, fresh PostgreSQL 17:
  - V001 through V008 applied in under one second.
  - Flyway logged V009 as `[non-transactional]`.
  - No later migration output appeared for more than 110 seconds.
  - The diagnostic rerun was cancelled deliberately to conserve CI capacity.
- The same run's Compose artifact showed `platform-migrate` still `Up` after
  43 minutes while PostgreSQL was healthy.
- Cross-service run `30237306509` reproduced the same
  `platform-migrate.stderr` `TimeoutException` in Scenarios A, B, and C after
  each 900-second process bound.
- Baseline runs before V009 used the same Spring entry point, Compose topology,
  and harness lifecycle successfully.
- Flyway 12.4 defaults PostgreSQL transactional locking to true. Redgate's
  PostgreSQL lock setting explicitly requires false for
  `CREATE INDEX CONCURRENTLY`.

## Eliminated Hypotheses

- Slow or high-volume index build: eliminated. Every failing cross-service
  migration ran before seed insertion on a fresh disposable database.
- Spring non-web lifecycle leak: eliminated. The process stopped at V009, and
  the unchanged lifecycle exited successfully before V009 existed.
- Missing V009 script configuration: eliminated. Flyway logged V009 as
  non-transactional, proving the `.sql.conf` sidecar was honored.

## Fix and Prevention

- `application-persistence.yaml` binds
  `spring.flyway.postgresql.transactional-lock=false`.
- `FlywayRecoveryHarnessTest` configures the same PostgreSQL extension directly;
  it does not depend on Spring profile binding.
- `FlywayPostgresqlLockConfigurationTest` loads the real YAML through Spring
  Boot's binder and asserts the effective property is false.
- Phase 3 and Phase 4B validators require the Spring and programmatic lock
  controls.
- Deployment and architecture docs record why both the script transaction and
  Flyway advisory-lock mode matter.

## Verification

- Negative regression proof with
  `-Dspring.flyway.postgresql.transactional-lock=true`: expected test failure,
  `Expecting value to be false but was true`.
- Positive migration/config tests: 10 passed before programmatic-harness fix;
  11 passed with one environment-gated recovery skip after it.
- Full Platform API sweep before the programmatic-harness addition:
  245 tests, zero failures/errors, 30 environment-gated skips.
- Phase 3 validator: `Errors=0`, `Result=PASS`.
- Phase 4B static gate: `Errors=0`, `CheckpointResult=PASS`; live database proof
  remains CI-required.
- Repository layout and `git diff --check`: pass.

## Remaining Gate

Push the fix and require fresh PR PostgreSQL, Compose, and cross-service runs to
prove V009 completes, both indexes are valid, recovery succeeds, and downstream
scenarios execute. Local Docker was intentionally not used because C: had less
than 1.6 GB free.

## Unresolved Questions

None for the root-cause fix. Production/staging migration duration and rollback
evidence remain release gates outside this incident.
