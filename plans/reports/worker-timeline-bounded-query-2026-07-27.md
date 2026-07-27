# Timeline Bounded Query Worker Report

Status: DONE

## Summary

- Reworked `JdbcIncidentTimelineRepository.listActivity` so each source applies
  its cursor predicate, `ORDER BY occurred_at ASC, event_id ASC`, and local
  `LIMIT ?` before `UNION ALL`.
- Preserved the public eight-field projection, both organization/project/incident
  filters, parameterized values, legacy path, and outer global
  `(occurred_at, source_rank, event_id)` ordering/limit.
- Added a JDBC-capture regression test for initial, incident-rank cursor, and
  investigation-rank cursor SQL/parameter layouts.

## Exact SQL and Parameter Contract

- Branch marker: `ORDER BY occurred_at ASC, event_id ASC LIMIT ?` (twice).
- Outer marker: `ORDER BY occurred_at ASC, source_rank ASC, event_id ASC LIMIT ?`.
- Rank 0 cursor: incident `(occurred_at, event_id) > (?, ?)`; investigation
  `occurred_at >= ?`.
- Rank 1 cursor: incident `occurred_at > ?`; investigation
  `(occurred_at, event_id) > (?, ?)`.
- Parameters, initial: incident scope + branch limit; investigation scope +
  branch limit; outer limit.
- Parameters, rank 0: incident scope + timestamp + event ID + branch limit;
  investigation scope + timestamp + branch limit; outer limit.
- Parameters, rank 1: incident scope + timestamp + branch limit;
  investigation scope + timestamp + event ID + branch limit; outer limit.

## Files Modified

- `services/platform-api/src/main/java/ai/opsmind/platform/incident/JdbcIncidentTimelineRepository.java`
- `services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentQueryServiceTest.java`

## Verification

- `mvn -f services/platform-api/pom.xml "-Dtest=IncidentQueryServiceTest" test`
  passed: 8 tests, 0 failures, 0 errors.
- Static post-check: two branch-local limits, one outer limit, zero literal
  source-rank tuple predicates, and no `payload` in `listActivity`.
- PostgreSQL HTTP/integration test not run: task constrained verification to
  focused safe tests; Docker was not started because C: has about 2.8 GB free.

## Blockers

- None for this owned query/test scope. Query-plan evidence remains owned by the
  Phase 3 gate work; this change intentionally makes the physical bounds
  explicit rather than claiming a particular optimizer plan.

## Unresolved Questions

- None.
