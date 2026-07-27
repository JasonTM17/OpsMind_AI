---
phase: 2
title: "Unified Read Projection"
status: in-progress
priority: P1
dependencies: [1]
---

# Phase 2: Unified Read Projection

## Overview

Implement the metadata-only bridge in the existing incident query stack. The current authorized transaction gates access before the timeline query runs (`services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentQueryService.java:84-107`); this phase keeps legacy JSON on `READ`, adds an ANALYZE-only vendor query in the same service/repository stack, and wires the contract created in Phase 1.

## Requirements

- Functional: keep the current v1 repository read for `application/json`, and add the vendor read over a parameterized `UNION ALL` of `incident_timeline_events` and `investigation_run_events`; no DB view and no copy into V003.
- Functional: stable per-query order is `(occurred_at, source_rank, event_id)` with source rank `0` for incident and `1` for investigation. Pagination is a forward-only live view: rows committed later at or before the cursor may require a fresh traversal and no snapshot/lossless-feed claim is allowed.
- Security: the vendor service path requires `ANALYZE_SCOPE` and `IncidentAccessMode.ANALYZE`; v1 stays on READ. SQL selects only the eight Phase 1 fields and never reads either JSON `payload`, evidence records, accepted response, reason/free text, tool/evidence IDs, or canonical content.
- Non-functional: tenant context and hidden denial stay inside the existing transaction (`services/platform-api/src/main/java/ai/opsmind/platform/incident/JdbcIncidentAccessRepository.java:37-60`, `services/platform-api/src/main/java/ai/opsmind/platform/incident/JdbcIncidentAccessRepository.java:119-149`).
- Non-functional: V009 uses concurrent indexes plus `V009__incident_activity_timeline_indexes.sql.conf` with `executeInTransaction=false`; the persistence profile and direct recovery harness select Flyway's session-level PostgreSQL advisory lock so the build cannot wait on Flyway's own transaction lock while migration runners remain serialized. Deployment applies migration before mixed old/new application rollout (`services/platform-api/src/main/resources/db/migration/V003__incident_control_plane.sql:86-91`, `services/platform-api/src/main/resources/db/migration/V006__investigation_run_persistence.sql:199-208`).
- Non-functional: release evidence uses at least 50,000 target rows in each source ledger plus same-tenant/project distractors; 300 matched append samples per ledger in each pre/post-index phase (50 warm-up, 250 measured); and 300 vendor reads across initial, rank-0 cursor, and rank-1 cursor modes (50 warm-up, 50 measured per mode). Vendor-read and post-index append p95 must each be at most 500 ms; append p95 regression must be at most 20% versus the pre-index baseline; combined V009 index bytes must be at most 256 bytes per source row and at most 100% of combined source-ledger `pg_table_size`. These values are test gates, not a claim about population production latency.

## Data Flow

1. `IncidentController.timeline` negotiates the Accept matrix from Phase 1 using RFC 9110 media-range precedence: the most-specific matching range determines each supported representation's quality. Missing/blank/`*/*` selects JSON; JSON remains the tie-breaker when the selected qualities are equal; the vendor is selected only when its selected quality is strictly higher and nonzero. A `q=0` range excludes only the representation for which it is the most-specific match, so another positive supported representation may still succeed. Unsupported media types may be ignored when a supported representation remains. Vendor parameters other than `q`, malformed q-values, or no positive supported representation return 406. JSON calls existing `timeline`; the vendor calls a new `activityTimeline` method and sets exact `Content-Type`, `Vary: Accept`, and `Cache-Control: no-store`.
2. `IncidentQueryService.activityTimeline` validates ANALYZE scope, IDs, page size, and v2 token, then calls `JdbcIncidentAccessRepository.requireAccess(..., ANALYZE)` inside its transaction before incident existence/query (`services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentAnalysisAuthorizer.java:61-70`).
3. `JdbcIncidentTimelineRepository` runs two branch-local filters with identical organization/project/incident predicates and branch-local tuple predicates, combines them with `UNION ALL`, orders once, and limits to `pageSize + 1`. It never selects `payload`.
4. The repository maps the exact Phase 1 fields. The service encodes the next cursor from the last database-returned `(occurred_at, source_rank, event_id)` tuple after microsecond canonicalization.

## Related Code Files

- Modify: `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentQueryService.java`
- Modify: `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentTimelineRepository.java`
- Modify: `services/platform-api/src/main/java/ai/opsmind/platform/incident/JdbcIncidentTimelineRepository.java`
- Modify: `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentController.java`
- Modify: `packages/contracts/openapi/opsmind-v1.yaml`
- Modify: `scripts/validation/phase-04-incident-contracts/openapi-static-contract-validator.mjs`
- Create: `services/platform-api/src/main/resources/db/migration/V009__incident_activity_timeline_indexes.sql`
- Create: `services/platform-api/src/main/resources/db/migration/V009__incident_activity_timeline_indexes.sql.conf`
- Modify: `services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentQueryServiceTest.java`
- Modify: `services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentControllerHttpTest.java`
- Modify: `services/platform-api/src/test/java/ai/opsmind/platform/persistence/MigrationContractTest.java`
- Create: `services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentActivityTimelineHttpIntegrationTest.java`

## TDD First

1. Extend `IncidentQueryServiceTest` so v1 keeps READ/version pagination while the vendor requires ANALYZE and pages on the database tuple. Assert VIEWER, DEVELOPER, and SECURITY_REVIEWER cannot use vendor output; SRE/ADMIN can.
2. Extend `IncidentControllerHttpTest` for missing Accept, `*/*`, exact types, weighted/equal alternatives, `q=0`, unsupported/malformed/parameterized types, vendor-extra-parameter 406, JSON fallback with unsupported types, exact content types, `Vary`, `no-store`, and unchanged JSON body/null omission (`services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentControllerHttpTest.java:309-324`). Add the matching OpenAPI response media type, 406 contract, ANALYZE requirement text, and static markers in this same phase.
3. Add `IncidentActivityTimelineHttpIntegrationTest` for static ordering/ties/continuation, a late tied/backdated append that proves documented live semantics, cross-tenant, same-organization cross-project, same-project cross-incident, cursor-from-other-incident, and secret/free-text sentinels absent from serialized bytes.
4. Extend `MigrationContractTest` for the two concurrent indexes, non-transactional script config, valid-index state, and forward-only migration contract (`services/platform-api/src/test/java/ai/opsmind/platform/persistence/MigrationContractTest.java:68-173`).

## Implementation Steps

1. Extend the existing service/repository with `activityTimeline`/`listActivity`; keep current v1 signatures intact. Wire the controller and publish the matching OpenAPI route contract only now that both paths are real, then apply the Phase 1 negotiation matrix without a new helper class.
2. Implement branch-local parameterized tuple predicates before `UNION ALL`. Project incident `event_id/event_kind/occurred_at/actor_id/incident_version` plus literals; project investigation `event_id/event_type/occurred_at/actor_id/run_id/sequence_no` plus literals. Select no JSON path and no other table.
3. Apply identical organization/project/incident predicates to both branches even though RLS protects organization only. Treat the v2 cursor as an untrusted start position; strict parsing never replaces authorization.
4. Add `incident_timeline_activity_order_idx` and `investigation_run_events_activity_order_idx` concurrently on `(organization_id, project_id, incident_id, occurred_at, event_id)` plus the matching `.sql.conf` `executeInTransaction=false`. Select Flyway's session-level PostgreSQL advisory lock in Spring and any direct Flyway recovery seam; the default transaction-level advisory lock self-waits with `CREATE INDEX CONCURRENTLY`. On failure, stop rollout, capture Flyway history and both exact index catalog rows, drop both V009-owned indexes concurrently whether valid or invalid, invoke the approved Flyway `repair` seam, and retry; never modify applied migration bytes or repair by blind SQL deletion.
5. Capture representative high-cardinality `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` evidence. Require branch-bounded index access, no ledger sequential scan, no unbounded full-ledger materialization, p95 thresholds from Requirements, and the exact index-byte budgets; revise SQL/index order if any gate fails.

## Validation

- `mvn -f services/platform-api/pom.xml "-Dtest=IncidentQueryServiceTest,MigrationContractTest" test`
- `$env:OPSMIND_PHASE4_DB_INTEGRATION='true'; $env:OPSMIND_PHASE7_DB_INTEGRATION='true'; mvn -f services/platform-api/pom.xml "-Dtest=IncidentActivityTimelineHttpIntegrationTest,IncidentHttpPersistenceIntegrationTest,InvestigationPersistenceIntegrationTest,InvestigationEvidencePersistenceIntegrationTest" test`
- V008-to-V009 upgrade plus fresh migration run; record valid-index catalog state, query plan, migration duration, append p95 before/after/delta, vendor-read p95, row counts, index bytes, `pg_table_size`, and the evidence-gated recovery result.

## Risk Assessment

- High: late/backdated commits fall before an issued cursor. Mitigation: explicitly expose a forward-only live view, test this boundary, and require a new snapshot/feed design before any lossless claim.
- High: investigation payload or evidence content leaks through an allowed free-text field. Mitigation: no free-text output fields, column-only SQL, byte-level sentinel tests, and ANALYZE-only access.
- High: derived-union query ignores branch indexes. Mitigation: branch-local predicates and high-cardinality EXPLAIN gate.
- High: concurrent index build fails or leaves one valid and one invalid index. Mitigation: non-transactional script config, migration-before-code order, catalog/history capture, exact-name concurrent drops of both V009 indexes, Flyway repair through an approved seam, and retry proof.
- Medium: same-tenant leakage through a missing project/incident predicate. Mitigation: identical branch filters plus same-org/project negative fixtures.

## Rollback

- Remove the vendor activity service/repository path and keep the current legacy v1 repository read.
- Leave valid V009 indexes only if measured append/storage budgets pass. Otherwise ship a later forward migration to drop them concurrently after the vendor path is disabled.

## Success Criteria

- [x] Vendor representation v1 returns only the eight-field contract, is ANALYZE-only, and uses the strictly parsed v2 live-view cursor.
- [x] The vendor media type and 406 response are published in OpenAPI only with the real controller/service/repository path; the legacy OpenAPI response remains compatible.
- [x] The current JSON route still reads only `incident_timeline_events` through the unchanged v1 path.
- [ ] Same-tenant and cross-tenant negatives prove both branches stay scoped; forbidden sentinels are absent from response bytes.
- [ ] V009 is online, forward-recoverable, fresh/upgrade tested, and its representative query plan/append/storage budgets pass without a ledger rewrite, copy, or view.
