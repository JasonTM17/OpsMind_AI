---
title: Incident Activity Timeline Bridge
description: >-
  Add a versioned incident activity timeline that unions incident and
  investigation metadata without breaking the current v1 route.
status: pending
priority: P1
effort: 10-14h
branch: main
tags:
  - phase7
  - incident-timeline
  - investigation
blockedBy: []
blocks: []
created: 2026-07-26T00:00:00.000Z
createdBy: 'ck:plan'
source: skill
---

# Incident Activity Timeline Bridge

## Overview

Close the remaining Phase 7 timeline gap without new parallel services or ledger copies. Today `GET /.../timeline` returns only `incident_timeline_events` through `IncidentController.timeline` -> `IncidentQueryService.timeline` -> `IncidentTimelineRepository.list`, with a v1 incident-version cursor (`services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentController.java:130-141`, `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentQueryService.java:71-107`, `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentTimelineRepository.java:6-16`, `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentTimelinePageToken.java:15-47`). Phase 7 still records that accepted investigation activity is not linked into `incident_timeline_events` and must not be copied there (`plans/260719-1747-opsmind-ai-production-platform/phase-07-thin-evidence-backed-incident-vertical-slice.md:66-80`, `plans/260719-1747-opsmind-ai-production-platform/phase-07-thin-evidence-backed-incident-vertical-slice.md:119-123`).

## Phases

| Phase | Name | Status |
|-------|------|--------|
| 1 | [Contract and Cursor Compatibility](./phase-01-contract-and-cursor-compatibility.md) | Completed |
| 2 | [Unified Read Projection](./phase-02-unified-read-projection.md) | Pending |
| 3 | [Verification and Gate Integration](./phase-03-verification-and-gate-integration.md) | Pending |

## Dependencies

- No formal `blockedBy`: the incident route, V006/V007 ledgers, and the Phase 7 capability-backed checkpoint already exist (`services/platform-api/src/main/resources/db/migration/V006__investigation_run_persistence.sql:165-208`, `services/platform-api/src/main/resources/db/migration/V007__bounded_evidence_records.sql:12-76`, `plans/260723-0021-phase-07-capability-backed-investigation/plan.md:13-29`).
- This slice stays additive: reuse `IncidentController`, `IncidentQueryService`, `IncidentTimelineRepository`, `JdbcIncidentTimelineRepository`, and `IncidentTimelinePageToken`; do not copy investigation events into V003 and do not add a DB view.
- This is a prerequisite for later Phase 9 projection/timeline expectations, but Phase 9 threshold freeze and exit still wait the reviewed human pilot (`plans/260719-1747-opsmind-ai-production-platform/phase-09-durable-investigation-workflow.md:125-145`, `plans/260719-1747-opsmind-ai-production-platform/phase-08-simulator-and-evaluation-baseline.md:274-277`).

## Acceptance Criteria

- `application/json` keeps the current timeline page/event schema and v1 page-token bytes (`packages/contracts/openapi/opsmind-v1.yaml:289-317`, `packages/contracts/json-schema/incidents/incident-timeline-page.schema.json:7-24`, `packages/contracts/json-schema/incidents/incident-timeline-event.schema.json:7-95`).
- `application/vnd.opsmind.incident-activity-timeline.v1+json` requires `incident:analyze` plus `ANALYZE` access and returns only the closed field set defined in Phase 1; it never returns free text, payload fields, evidence/tool identifiers, prompt/model prose, credentials, or canonical evidence.
- The vendor page unions `incident_timeline_events` and `investigation_run_events`, ordered by `(occurred_at, source_rank, event_id)`, with a strictly parsed v2 navigation cursor bound to incident ID, database-returned microsecond timestamp, source rank, and event ID. It is a forward-only live view, not a snapshot or lossless change feed.
- V1 stays in the existing `READ` transaction; the vendor read uses the same hidden-denial transaction shape with `ANALYZE` scope/access (`services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentQueryService.java:84-107`, `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentAnalysisAuthorizer.java:61-70`).
- V009 contains only concurrent indexes and a per-script non-transactional Flyway config. Fresh and V008-to-V009 upgrades, query plans, invalid-index recovery, append latency, and storage overhead are release evidence; rollback is forward recovery, never destructive history editing.
- Performance evidence is released only when a disposable high-cardinality fixture has at least 50,000 target rows in each source ledger plus same-tenant/project distractors; 300 matched append samples per ledger in each pre/post-index phase (50 warm-up, 250 measured; 600 total per ledger); and 300 vendor reads across initial, rank-0 cursor, and rank-1 cursor modes (50 warm-up and 50 measured per mode). The vendor-read p95 and post-index append p95 must each be at most 500 ms, append p95 regression must be at most 20% versus the pre-index baseline, and the two V009 indexes together must be at most 256 bytes per source row and at most 100% of combined `pg_table_size` for the source ledgers. These are measurable release gates, not production-SLO claims.
- Focused Platform API tests plus Phase 4/7 validators cover contract, query, RLS, migration, and compatibility gates.

## Rollback

- Revert controller/query/repository/schema wiring for the vendor media type and keep `application/json` as the only served representation.
- If valid V009 indexes meet the measured write/storage budget, leave them applied while the feature is disabled. If they do not, ship a new forward migration that drops them concurrently; never edit or delete an applied V009. A failed non-transactional V009 is recovered only after catalog/history evidence capture: inspect both V009-owned indexes, drop both exact names concurrently (valid or invalid), run the approved Flyway `repair` API/CLI operation, and retry. Never drop only the invalid half, use a blind history-row delete, or use `IF NOT EXISTS` to hide a wrong-definition index.

## Open Questions

None.

## Red Team Review

### Session — 2026-07-26

**Raw findings:** 15; deduplicated to 8 (7 accepted, 1 rejected). **Severity:** 7 High, 1 Medium.

| # | Finding | Severity | Disposition | Applied To |
|---|---------|----------|-------------|------------|
| 1 | Phase 1 could not serve real vendor data independently | High | Accept: do not advertise the route until code and OpenAPI land together in Phase 2 | Completed |
| 2 | Timestamp keyset is not snapshot/lossless under late commits | High | Accept: document forward-only semantics | Plan, Phase 2 |
| 3 | Public fields and free-text policy were undefined | High | Accept | Phases 1-3 |
| 4 | Accept negotiation and cache separation were incomplete | High | Accept | Phases 1-2 |
| 5 | Authorization widened investigation metadata to READ roles | High | Accept: require ANALYZE | Phases 1-2 |
| 6 | Same-tenant isolation and query-plan gates were missing | High | Accept | Phases 2-3 |
| 7 | V009 online deployment and forward recovery were unspecified | High | Accept | Phases 2-3 |
| 8 | Cursor needed HMAC/expiry as an authorization capability | Medium | Reject: cursor is untrusted navigation only; authorization remains mandatory | Phase 1 |

### Whole-Plan Consistency Sweep

- Files reread: `plan.md` and all three phase files.
- Decision deltas checked: 10; media type is representation v1 while its cursor remains v2, vendor access is ANALYZE-only, and free text/identifier expansion is removed.
- Reconciled stale references: phase ownership, atomic controller/OpenAPI timing, response fields, negotiation, pagination semantics, SQL/index proof, migration recovery, validators, docs, and rollback.
- Unresolved contradictions: 0.
