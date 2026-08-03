---
phase: 1
title: Contract and Tests
status: completed
priority: P1
dependencies: []
---

# Phase 1: Contract and Tests

## Overview

Lock the additive public contract and executable tests before runtime wiring.

## Requirements

- Add collection `GET` beside the existing collection `POST`.
- Query: `status` optional exact `IncidentStatus`; `pageSize` default 25, range 1..100;
  list-specific `pageToken` optional, maximum 512 ASCII characters.
- Response: strict `IncidentListPage`; each `IncidentSummary` contains exactly
  `id`, `title`, `severity`, `status`, `updatedAt`, and `version`. It must not
  expose description/summary, root cause, resolution summary, owner, actor,
  organization, or project fields.
  `pageSize`, `hasMore`, and optional `nextPageToken`.
- Fixed sort: `updatedAt DESC, id DESC`. No caller-selected sort.
- Operation ID is `listIncidents`; success requires `Cache-Control: no-store`.
  Errors publish existing Problem Details for `400/401/403/404/503`.
- Existing operations, shared `PageToken`, and schemas remain compatible.
- Contract text states live view, not snapshot/lossless feed; clients deduplicate
  and restart traversal for a fresh view.

## Architecture

Create a new summary/page schema and fixtures. Extend the static validator to
assert operation, parameters, local references, positive/negative fixtures, and
closed-object behavior. Tests define unsigned canonical token parsing, deferred
path/filter binding, authorization-before-query behavior, and unchanged-dataset traversal.

## Related Code Files

- Modify: `packages/contracts/openapi/opsmind-v1.yaml`
- Create: `packages/contracts/json-schema/incidents/incident-summary.schema.json`
- Create: `packages/contracts/json-schema/incidents/incident-list-page.schema.json`
- Create: `packages/contracts/fixtures/incidents/incident-list-page.*.json`
- Modify: `scripts/validation/validate-phase-04-incident-contracts.mjs`
- Create: `services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentListPageTokenTest.java`
- Create: `services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentListQueryServiceTest.java`
- Create: `services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentListControllerHttpTest.java`
- Create: `services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentListHttpPersistenceIntegrationTest.java`
- Create: `services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentListPlanAssertionsTest.java`
- Create: `services/platform-api/src/test/java/ai/opsmind/platform/persistence/FlywayV016RecoveryHarnessTest.java`
- Modify: `services/platform-api/src/test/java/ai/opsmind/platform/persistence/MigrationContractTest.java`

## Implementation Steps

1. Add positive summary/page fixtures and negative extra-field/type fixtures.
2. Publish the collection GET without changing POST semantics.
3. Write cursor tests for canonical Base64URL, version, UUID/status/timestamp/id,
   field substitution, truncation, padding, Unicode, arbitrary seek
   boundaries, path/filter mismatch, blank/oversize/malformed tokens, and ties.
   Valid unsigned boundary substitutions remain bounded by post-authorization
   scope/filter binding and the indexed seek predicate.
4. Write controller/query tests for defaults, validation, no-store response,
   authorization, tenant isolation, page-size+1, and zero write side effects.
5. Write migration tests for the V016 sidecar, concurrent DDL, valid/ready index
   catalog state, failed-build repair/retry, and historical checksum stability.

## Success Criteria

- [x] OpenAPI and JSON schemas are additive, closed, and all local refs resolve.
- [x] Exact response shape excludes every forbidden field in schema, fixtures, and HTTP tests.
- [x] Runtime and V016 behavior have focused executable regression coverage. A
  durable red-before-green transcript was not retained, so none is claimed.
- [x] Contract validator recognizes collection GET without weakening existing gates.

## Risk Assessment

Main risks: excessive list disclosure and accidental compatibility break. Use
an exact six-field summary, a list-specific token parameter, and retain all
existing v1 response schemas untouched.
