# V009 Incident Activity Evidence Gate — Remediation Re-review

Verdict: **PASS — no P0-P2 findings**

## P0-P2 Findings

None.

## Prior Blocker Closure

### P1 — Plan gate accepted non-executed, unscoped plans: fixed

- The executable harness obtains `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` from the shared production query and bindings (`IncidentActivityTimelinePlanHarnessTest.java:41-46`, `:98-118`); the repository uses that same builder (`JdbcIncidentTimelineRepository.java:86-101`, `IncidentActivityTimelineQuery.java:13-88`).
- All initial, source-rank-0, and source-rank-1 branches are exercised (`IncidentActivityTimelinePlanHarnessTest.java:121-138`).
- Assertions reject sequential/bitmap/materialized plans, require three limits, positive loops, loop-weighted row/filter bounds, and zero workers (`IncidentActivityTimelinePlanAssertions.java:20-42`).
- Each exact index must be an executed index scan on its expected relation with organization/project/incident values and mode-specific cursor columns in `Index Cond` (`IncidentActivityTimelinePlanAssertions.java:44-80`).
- The app-role harness proves 50,000 target rows per ledger plus same-tenant/same-project distractors (`IncidentActivityTimelineEvidenceSupport.java:110-146`); the fixture creates 10,000 later-timestamp distractor incidents and valid ledger events (`incident-timeline-v009-seed.sql:245-360`).

### P1 — Samples bypassed the production/app-role path: fixed

- The evidence context uses one persistent Hikari connection as the application role and verifies `current_user`, `session_user`, and transaction-local tenant identity (`IncidentActivityTimelineEvidenceSupport.java:75-106`).
- Append sampling preserves 50 warm-up and 250 measured writes per ledger, creates parent state before timing, and measures committed app-role JDBC transactions (`IncidentActivityTimelineAppendBenchmarkHarnessTest.java:22-60`, `:83-92`, `:108-150`).
- Incident fixture transitions and payloads are trigger-valid (`incident-timeline-v009-seed.sql:59-134`); investigation fixtures create valid `RUN_STARTED` rows under active constraints (`incident-timeline-v009-seed.sql:138-243`).
- Vendor reads call `JdbcIncidentTimelineRepository.listActivity` for 50 warm-up and 50 measured samples per mode on the persistent app pool (`IncidentActivityTimelinePlanHarnessTest.java:21-23`, `:69-95`).
- The shell asserts exact harness-produced sample counts and p95/storage budgets (`incident-timeline-v009-evidence.sh:136-145`, `:165-196`).

### P2 — Shell checkout portability: fixed

- `.gitattributes:2-3` enforces LF for all shell scripts and the V009 seed SQL.
- `git check-attr text eol` reports `text: set` and `eol: lf` for both V009 shell entry points and the seed SQL.
- `bash -n scripts/validation/run-phase-04b-migration-upgrade.sh` and `bash -n scripts/validation/phase-04b-evidence-records/incident-timeline-v009-evidence.sh` both pass.

## Verification

- Clean Java compile plus focused tests: PASS, 13 tests (`IncidentActivityTimelinePlanAssertionsTest`, `IncidentQueryServiceTest`).
- `node scripts/validation/validate-phase-04-incident-contracts.mjs`: PASS, 0 errors.
- `node scripts/validation/validate-phase-04b-evidence-records.mjs`: PASS, 0 errors; correctly reports `V009DatabaseGate=ENVIRONMENT_REQUIRED`.
- `node scripts/validation/validate-phase-07-investigation-slice.mjs`: checkpoint PASS, 0 errors; phase exit remains blocked only by the pre-existing missing cross-service trace report.
- `git diff --check`: PASS.
- Disposable PostgreSQL execution was not rerun in this workspace; `.github/workflows/pr-quality.yml:649-658` remains the authoritative executable database gate.

## Unresolved Questions

None.

Status: DONE
Summary: All three prior P1/P2 findings are materially remediated; no remaining P0-P2 issue found.
Concerns/Blockers: None.
