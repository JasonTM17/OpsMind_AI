# Phase 4A Incident Contracts Worker Report

## Scope

Implemented canonical incident contracts only. No Java, SQL, package metadata,
plan, documentation, Git, or heavy build changes.

## Delivered

- OpenAPI 3.1.1 API version `0.4.0` with nested create/detail/transition/timeline
  operations under the `/api/v1` server.
- OIDC scope requirements: `incident:read` and `incident:write`; request payloads
  expose no tenant, actor, role, status-on-create, version, or audit authority.
- Mutation contracts require `Idempotency-Key`; transition also requires strong
  numeric `If-Match`. Mutation responses expose ETag and UUID
  `X-Operation-Id`; create also exposes `Location`.
- Draft 2020-12 schemas for create, transition, incident, timeline event/page,
  shared incident vocabulary, and incident audit event.
- Exact severity/status vocabulary, resolved-field conditional semantics,
  required-nullable history fields, bounded strings/arrays, and
  `additionalProperties: false` on data objects.
- One canonical incident fact payload for timeline/audit/outbox consumers:
  `INCIDENT_CREATED` or `INCIDENT_STATUS_TRANSITIONED`; audit payload references
  the timeline event schema.
- Twelve deterministic incident fixtures: five positive and seven negative.
  Negative cases cover caller-supplied authority, invalid severity, incomplete
  resolution, premature resolution data, invalid resolved state, wrong event
  field naming, and an injected audit payload field.
- Dependency-free offline validator with safe root/symlink checks, duplicate
  JSON-key detection, full JSON parse, local file/pointer reference resolution,
  schema vocabulary/bounds checks, subset fixture evaluation, unique
  operationIds, internal/external OpenAPI ref checks, and operation-level static
  assertions. It explicitly does not claim general OpenAPI metaschema validation.
- Validator concerns are split under
  `scripts/validation/phase-04-incident-contracts/`: safe file access,
  duplicate-key detection, reference resolution, schema inspection, subset
  validation, fixture cases, and OpenAPI static checks. The entry point is 116
  lines; modules range from 55 to 149 lines.

## Verification

```text
node scripts/validation/validate-phase-04-incident-contracts.mjs
JsonSchemasParsed=11
JsonFixturesParsed=14
FixturePositiveCases=5
FixtureNegativeCases=7
LocalReferencesResolved=112
OpenApiOperations=6
Errors=0
Result=PASS
```

Compatibility check:

```text
node scripts/validation/validate-phase-03-trust-foundation.mjs
FilesChecked=50
Errors=0
Result=PASS
```

`node --check` passed for the entry point and every module under
`scripts/validation/phase-04-incident-contracts/`. The final modularized run
preserved the exact Phase 4 metrics shown above.

## Constraints

Storage preflight blocked at C: 8.86 GB free versus the 10 GB repository gate;
D: passed with 27.68 GB. Only lightweight Node validation ran. No cleanup or
heavy Maven/Docker/build command ran.

## Unresolved Questions

- None inside the assigned contract scope.
- General OpenAPI metaschema/tool validation remains an integration gate owned
  by the lead; this worker's validator is intentionally static and offline.
