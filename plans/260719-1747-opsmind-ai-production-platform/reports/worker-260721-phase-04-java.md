# Phase 4A Java Incident Control Plane

## Status

DONE_WITH_CONCERNS. Java source and owned tests implemented. Maven execution
deferred because storage preflight blocked at C: 8.86 GB; repo requires at least
10 GB and user preference requires 12 GB. D: remained 27.68 GB. No data deleted.

## Implemented

- Nested incident create/detail/transition/timeline HTTP boundary with exact
  idempotency, strong ETag, `Location`, and `X-Operation-Id` response handling.
- Exact severity/status vocabulary and full legal state graph. Resolve requires
  RCA fields; reopen clears current resolution; CLOSED remains terminal.
- Scope plus database-authoritative organization/project membership policy.
  Mutations: ADMIN/SRE. Reads: ADMIN/SRE/DEVELOPER/SECURITY_REVIEWER/VIEWER.
  AI_AGENT and invisible resources receive non-enumerating denial.
- Explicit transaction orchestration: identity/access recheck, tenant context,
  idempotency claim, aggregate write, timeline, DB-derived audit append, exact-
  byte outbox append, cached response completion, commit.
- Canonical request digest binds actor, method, nested path, normalized payload,
  and expected version. Replay reconstructs status/body/Location/ETag/operation.
- One shared event ID and exact timeline payload across timeline/audit/outbox;
  generated operation UUID is durable correlation. External trace stays a
  bounded safe string and is never parsed as UUID.
- Audit writer omits `sequence_no`, `tenant_sequence_no`, `previous_digest`, and
  `event_digest`; V003 trigger remains sole chain-integrity authority.
- JDBC aggregate and immutable timeline adapters split on real persistence
  boundaries. Largest remaining source is mutation coordinator at 222 LOC; it
  retains two commands because they share the same transaction/idempotency
  ordering. Event append, JSON/cache, request identity, validation, state,
  access, query, aggregate JDBC, and timeline JDBC are already separated.

## Owned Tests Added

- `IncidentStateMachineTest`: exhaustive transition/complement matrix,
  resolution requirements, prohibited fields, reopen behavior.
- `IncidentControllerTest`: create/detail headers, exact body, auth failure,
  missing/malformed If-Match.
- `IncidentServiceTest`: scope/role matrices, durable effect ordering, exact
  replay, stale version, rollback, shared event/operation identity, outbox
  sequence and payload consistency.
- `TransactionalAuditRepositoryTest`: transaction requirement, trusted-chain
  column omission, safe persistence rejection.

## Verification

- Lightweight static inspection: schema field/length mapping checked against
  V003 and canonical JSON Schemas; event vocabulary/payload contract confirmed
  with contract and database workers; no TODO/FIXME in owned source.
- Maven compile/test: NOT RUN. Storage gate explicitly prohibited builds.

## Concerns / Blockers

- C: must recover to at least 12 GB before Maven/test execution under the user
  threshold. Until compile, focused tests, full Maven suite, and real PostgreSQL
  checks pass, this Java slice is not evidence-backed completion.
- Runtime unknown-JSON-field rejection depends on the shared Jackson policy;
  canonical schemas reject additional properties, but this could not be
  empirically confirmed without the blocked controller test run.

## Unresolved Questions

- None in the Java/API/SQL contract. Remaining concern is verification capacity.
