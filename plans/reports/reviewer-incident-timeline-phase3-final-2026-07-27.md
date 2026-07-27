# V009 Incident Activity Evidence Gate — Final Blocking Review

Verdict: **BLOCK**

## P1 — Plan gate accepts non-executed, unscoped index plans

Evidence:

- `scripts/validation/phase-04b-evidence-records/incident-timeline-v009-plan-validator.mjs:32-77` checks forbidden node names, `Actual Rows <= 200`, presence of two index names, and three `Limit` nodes. It never checks `Relation Name`, `Index Cond`, `Actual Loops`, worker rows, or rows removed by a filter.
- A synthetic plan containing both named indexes, three limits, no `Index Cond`, and `Actual Loops: 0` returned `V009QueryPlanResult=PASS`.
- `scripts/validation/phase-04b-evidence-records/incident-timeline-v009-evidence.sh:548` discards query rows. Its fixture is dominated by one tenant/project/incident, so missing branch predicates or wrong rows need not fail the evidence run.

Impact: an ordered full-index walk, non-executed branch, or plan lacking organization/project/incident/cursor bounds can satisfy the release evidence. This does not prove the phase requirement for branch-bounded access.

Exact fix:

1. Require one executed scan for each exact index and expected source relation.
2. Require `Actual Loops > 0`; bound total work using `Actual Rows * Actual Loops`, including worker statistics.
3. Require each scan's `Index Cond` to contain equality on `organization_id`, `project_id`, and `incident_id`; require the mode-specific `occurred_at`/`event_id` cursor condition.
4. Seed high-cardinality distractor tenants/projects/incidents and assert returned identities belong only to the target, rather than discarding every row.
5. Add negative validator fixtures proving missing `Index Cond`, zero loops, wrong relation, and excessive loop-weighted rows fail.

## P1 — Latency and compatibility samples bypass the production path

Evidence:

- `incident-timeline-v009-evidence.sh:248-370` starts a new `psql` process/connection for every append timing. Incident timing also creates an incident; investigation timing creates an incident and run before the ledger event. Connection/process and unrelated parent-write cost can hide a greater-than-20% ledger-index regression.
- Those append samples call `query_upgrade_database`, which uses `POSTGRES_USER` at `scripts/validation/run-phase-04b-migration-upgrade.sh:73-78`. They bypass the `session_user = 'opsmind_app'` tenant/actor checks in V003/V006/V008 and do not prove an old application-role write after V009.
- `incident-timeline-v009-evidence.sh:535-551` times duplicated raw SQL, not the vendor HTTP/service/JDBC path. The shell has only initial and incident-rank cursor SQL (`:426-505`), while Java has a distinct investigation-rank cursor branch at `JdbcIncidentTimelineRepository.java:102-105`. The evidence can pass while Java SQL, prepared-plan behavior, authorization/mapping, or the rank-1 path regresses.

Impact: the reported append regression and vendor-read p95 are not measurements of the deployed contract, and application-role write compatibility is unproven.

Exact fix:

1. Run all timed samples through a persistent, warmed JDBC/HTTP harness using `POSTGRES_APP_USER`; bind tenant and actor transaction-locally.
2. Measure around each real transaction commit without process/connection creation in the sample and without unrelated parent creation inside the timed region.
3. Exercise the actual vendor route/repository with initial, source-rank-0 cursor, and source-rank-1 cursor modes. Derive EXPLAIN statements from the same repository SQL/bindings instead of maintaining shell copies.
4. Preserve the required 50/250 append and 50/50 read warm-up/measured counts, then assert them from harness-produced records.

## P2 — Shell gate is not checkout-portable

Evidence:

- `git check-attr text eol` reports both V009 shell files as unspecified.
- On this supported Windows workspace, `bash -n scripts/validation/run-phase-04b-migration-upgrade.sh` fails at line 9 on `$'do\r'`.
- `.gitattributes` pins LF only for selected shell subtrees, not these two gate scripts. A Windows checkout can therefore materialize both as CRLF even though Ubuntu CI receives LF.

Impact: the release gate cannot be parsed from a normal Windows checkout, so local evidence is environment-dependent.

Exact fix:

1. Add `*.sh text eol=lf` or exact LF rules covering `scripts/validation/run-phase-04b-migration-upgrade.sh` and `scripts/validation/phase-04b-evidence-records/*.sh`.
2. Renormalize the scripts and require `bash -n` for both files in a clean checkout.

## Verification

- `node --check` passed for both changed `.mjs` validators.
- `node scripts/validation/validate-phase-04b-evidence-records.mjs` exited 0 but correctly reported `V009DatabaseGate=ENVIRONMENT_REQUIRED`; no database evidence was run.
- `bash -n incident-timeline-v009-evidence.sh` passed.
- `bash -n run-phase-04b-migration-upgrade.sh` failed on CRLF as documented.
- No Docker or database execution used.

## Unresolved Questions

None.

Status: DONE_WITH_CONCERNS  
Summary: Three P0-P2 blockers invalidate the current V009 release-evidence claim.  
Concerns/Blockers: Plan structural false-PASS, non-production timing/auth path, and CRLF shell portability.
