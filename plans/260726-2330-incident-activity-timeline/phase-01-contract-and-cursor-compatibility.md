---
phase: 1
title: Contract and Cursor Compatibility
status: completed
priority: P1
dependencies: []
---

# Phase 1: Contract and Cursor Compatibility

## Overview

Lock the internal additive contract before the SQL bridge lands. Preserve the current v1 route/schema/token bytes while defining the vendor response records, closed JSON Schemas, fixtures, and a v2 cursor in the existing page-token component (`services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentController.java:130-141`, `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentTimelinePageToken.java:15-47`). This phase is contract-only: it must not advertise the vendor representation on the public route. Runtime controller/service wiring and the matching OpenAPI route change land atomically in Phase 2 so every served representation has real ledger-backed behavior.

## Requirements

- Functional: `application/json` keeps `IncidentTimelinePage` and `IncidentTimelineEvent` exactly as today (`packages/contracts/json-schema/incidents/incident-timeline-page.schema.json:7-24`, `packages/contracts/json-schema/incidents/incident-timeline-event.schema.json:7-95`).
- Functional: `application/vnd.opsmind.incident-activity-timeline.v1+json` is the first version of the new representation; Phase 1 captures its internal record/schema contract but leaves the public `GET /.../timeline` OpenAPI operation unchanged until the real implementation lands in Phase 2. Its page token is v2 because v1 token bytes are already reserved by the legacy representation (`packages/contracts/openapi/opsmind-v1.yaml:289-317`).
- Functional: v1 token stays `Base64url("v1:<incidentId>:<incidentVersion>")`; v2 token binds incident ID, exact timestamp, source rank, and event ID in the existing `IncidentTimelinePageToken` component (`services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentTimelinePageToken.java:15-47`).
- Security: the vendor representation requires `incident:analyze` and `IncidentAccessMode.ANALYZE`; legacy JSON keeps `incident:read` and `READ` (`services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentRolePolicy.java:11-19`, `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentAnalysisAuthorizer.java:61-70`).
- Non-functional: invalid Accept and invalid token paths are 406/400, and no parallel query service, repository, page-token class, or media-negotiation helper is introduced.

## Field-Level Contract

The page reuses `items`, `pageSize`, `nextPageToken`, and `hasMore`. The entry schema is a closed `oneOf`: common fields are required; source-specific fields are present only for their source. No field is derived from either ledger's JSON `payload`.

| JSON field | Type | Source | Exact derivation | Rule |
|------------|------|--------|------------------|------|
| `eventId` | UUID | Both | `event_id` | Required |
| `source` | `INCIDENT` or `INVESTIGATION` | Both | SQL literal/source rank 0 or 1 | Required; closed enum |
| `eventType` | closed ledger event enum | Both | `event_kind` or `event_type` | Required; V003/V006 values only |
| `occurredAt` | RFC 3339 UTC instant | Both | `occurred_at` | Required; database-returned microsecond precision |
| `actorId` | UUID | Both | `actor_id` | Required; vendor route is ANALYZE-only |
| `incidentVersion` | integer 0..2147483647 | Incident only | `incident_version` | Required for incident; forbidden for investigation |
| `investigationRunId` | UUID | Investigation only | `run_id` | Required for investigation; forbidden for incident |
| `investigationSequence` | integer >= 1 | Investigation only | `sequence_no` | Required for investigation; forbidden for incident |

Forbidden everywhere: `reason`, `rootCause`, `resolutionSummary`, `operationId`, external trace, evidence/tool IDs, connector/source metadata, free text, nested objects, `payload`, accepted response, prompt/reasoning, credentials, and canonical evidence.

## Negotiation Contract

| Accept condition | Result |
|------------------|--------|
| Missing, `*/*`, or equal-quality JSON/vendor | Legacy `application/json` wins for compatibility |
| Vendor has strictly higher non-zero quality | Vendor representation; ANALYZE authorization required |
| A range has `q=0` | That range is unacceptable and ignored |
| Vendor has any parameter other than `q`, malformed input, or only unsupported ranges | 406 |

Both successful representations set exact `Content-Type` and `Vary: Accept`; vendor responses also set `Cache-Control: no-store`. Phase 2 owns runtime negotiation tests.

## Data Flow

1. V1 continues to use the current version cursor and response contract (`services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentTimelinePageToken.java:15-47`).
2. V2 encodes/decodes a keyset cursor through new explicit methods on `IncidentTimelinePageToken`; no new page-token class is introduced.
3. Two new incident JSON Schemas and fixtures pin the internal vendor contract without changing the served route.
4. Phase 2 implements controller negotiation, selects the correct authorized query, and publishes the matching OpenAPI route contract in the same phase.

## Related Code Files

- Modify: `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentTimelinePageToken.java`
- Create: `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentActivityTimelineEntry.java`
- Create: `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentActivityTimelinePage.java`
- Create: `packages/contracts/json-schema/incidents/incident-activity-timeline-entry.schema.json`
- Create: `packages/contracts/json-schema/incidents/incident-activity-timeline-page.schema.json`
- Create: `packages/contracts/fixtures/incidents/incident-activity-timeline-page.valid.json`
- Create: `packages/contracts/fixtures/incidents/incident-activity-timeline-page.payload-leak.invalid.json`
- Create: `services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentTimelinePageTokenTest.java`
- Create: `services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentActivityTimelineEntryTest.java`
- Create: `services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentActivityTimelinePageTest.java`
- Modify: `scripts/validation/phase-04-incident-contracts/fixture-contract-validator.mjs`
- Modify: `scripts/validation/validate-phase-04-incident-contracts.mjs`

## TDD First

1. Add `IncidentTimelinePageTokenTest` to pin v1 round-trip compatibility, v2 round-trip, incident mismatch, oversized token, and bad-version failures. Add record tests for the closed source variants, page defensive copying, bounds, and continuation-token consistency.
2. Add v2 token negatives for non-canonical UUIDs, malformed Base64/UTF-8, field-count drift, incident mismatch, negative/out-of-range epoch micros, non-microsecond input, source rank outside 0/1, and oversized input. A token is untrusted navigation, never authorization; every request still performs scope/access checks.
3. Add schema fixtures that pin both closed source variants and reject forbidden/free-text fields, then extend only the schema/fixture validation path without changing public-route markers.

## Implementation Steps

1. Add the two response records with exactly the field table above; serialize source-specific nulls as absent and forbid additional properties in schemas.
2. Extend `IncidentTimelinePageToken` with separate v1 and v2 methods; v2 payload is `v2:<incidentId>:<occurredAtEpochMicros>:<sourceRank>:<eventId>`. Parse once, validate strictly, and encode only database-returned timestamps truncated to microseconds.
3. Add two new JSON Schemas and matching valid/negative fixtures. Existing `incident-timeline-*` schemas remain the v1 authority; do not add the vendor media type to the OpenAPI route in this phase.
4. Do not wire the controller or return placeholder data in this phase.

## Validation

- `mvn -f services/platform-api/pom.xml "-Dtest=IncidentTimelinePageTokenTest,IncidentActivityTimelineEntryTest,IncidentActivityTimelinePageTest" test`
- `node scripts/validation/validate-phase-04-incident-contracts.mjs`

## Risk Assessment

- High: accidental v1 cursor or response drift. Mitigation: keep separate v1/v2 encode-decode branches and assert v1 byte compatibility first.
- Medium: vendor response grows into a payload passthrough. Mitigation: no free-text fields, closed source variants, and invalid fixtures for each forbidden field class.

## Rollback

- Remove the unserved vendor contract, new response records, and v2 cursor branch; keep the existing JSON route and v1 token behavior.

## Success Criteria

- [x] `application/json` timeline behavior remains byte-compatible with the current route and v1 token.
- [x] Vendor representation v1 has closed internal records plus two new incident JSON Schemas and positive/negative fixtures, while the public OpenAPI route remains unchanged.
- [x] The exact field contract and v2 cursor are executable before runtime wiring; authorization, negotiation, cache behavior, and OpenAPI publication remain Phase 2 acceptance gates.
- [x] Invalid v2 tokens fail closed without introducing a new page-token class or treating the cursor as authorization.
