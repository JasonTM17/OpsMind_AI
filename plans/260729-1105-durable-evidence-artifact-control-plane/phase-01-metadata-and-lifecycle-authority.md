---
phase: 1
title: Metadata and Lifecycle Authority
status: in-progress
priority: P1
dependsOn: []
---

# Phase 01: Metadata and Lifecycle Authority

## Objective

Create the durable metadata authority needed before any object body can be
accepted. The artifact starts as `PENDING_UPLOAD`; it cannot be read or cited
until the later storage/finalization path has independently established an
eligible lifecycle state. This phase is deliberately database/control-plane
only: it does not introduce a public upload API, remote object write, or a
fake production filesystem implementation.

## File Inventory

| Path | Action | Responsibility |
|---|---|---|
| `services/platform-api/src/main/resources/db/migration/V014__evidence_artifact_metadata.sql` | Create | additive schema, constraints, forced RLS, least privilege, lifecycle fence, metadata audit binding |
| `services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/**` | Create | immutable domain values, lifecycle policy, scoped repository/reader, no storage SDK use |
| `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentAnalysisAuthorizer.java` or a new adjacent extracted authorization collaborator | Modify only if necessary | reuse verified authorization and tenant transaction pattern without widening authority |
| `services/platform-api/src/test/java/ai/opsmind/platform/evidence/artifact/**` | Create | domain, repository, isolation, lifecycle, integrity tests |
| `services/platform-api/src/test/java/ai/opsmind/platform/incident/**` | Modify only if extraction changes observable authorization behavior | non-enumeration regression |
| `scripts/validation/validate-phase-04c-evidence-artifacts.mjs` | Create | source/migration/test inventory gate scoped to V014 |
| `scripts/validation/run-phase-04c-artifact-metadata-postgres-contract.*` | Create | remote disposable PostgreSQL migration/RLS/lifecycle contract runner |
| `.github/workflows/pr-quality.yml` | Modify only after runner is stable | remote-only execution wiring |

## Data Model

V014 must keep one immutable identity per authorized artifact intent and at
least these authoritative values: organization/project/incident/run/actor,
source identity and version, classification, expected SHA-256 and byte count,
authorization epoch, retention class, opaque storage key/reference, lifecycle
state and monotonically increasing lifecycle version, encryption metadata
reference, audit-event relation, idempotency identity, timestamps, bounded
failure reason code, and nullable durable upload-claim fields (attempt ID,
lease expiry, attempt count). Credentials, signed URLs, key material, and raw
body content are forbidden columns. Phase 02 owns claim acquisition and
attempt-event persistence; Phase 01 reserves the metadata so a retry protocol
does not require a corrective migration.

The migration must use composite scope constraints, forced RLS, runtime grants
no broader than the required read/create/finalize functions, and a transition
guard that rejects direct or invalid lifecycle mutation. Existing V001–V013
must be read-only historical inputs.

## Tests Before Implementation

1. Add pure lifecycle tests for legal and illegal transitions, idempotency,
   terminal-state handling, digest/length syntax, bounded metadata, and
   authorization-epoch equality.
2. Add repository contract tests for missing/foreign scope, duplicate intent,
   lifecycle-version fencing, and safe unavailable failures.
3. Add PostgreSQL integration cases for fresh/upgrade migration, RLS cross-
   tenant denial, direct UPDATE/DELETE/TRUNCATE denial, invalid state transition
   denial, and metadata/audit rollback.

## Implementation Steps

1. Define small immutable domain values (`ArtifactId`, digest/length, lifecycle
   state, classification/retention, authorization snapshot) with strict bounds.
2. Define application-facing create/lookup/finalize metadata interfaces. The
   methods accept verified scope/context; raw object location is opaque and is
   never a caller-provided authorization token.
3. Add V014 with scope/database constraints, forced RLS, a state-transition
   fence, and audit linkage. Do not add an artifact column to V007.
4. Implement the JDBC metadata repository inside the existing short
   tenant-bound transaction pattern. Return the same safe not-found result for
   absent and invisible artifact identities.
5. Reuse/extract the existing incident analysis authorization logic only enough
   to bind current organization/project/incident/run/actor context; preserve
   current inline-evidence behavior and tests.
6. Add the dedicated Phase 4C static validator and remote PostgreSQL runner.

## Test Scenario Matrix

| Scenario | Expected outcome |
|---|---|
| Same authorized idempotency request repeats | Same pending metadata identity; no duplicate audit/logical effect |
| Same intent with changed digest, length, scope, or epoch | Conflict without mutation |
| Wrong tenant/project/incident/run/actor | Non-enumerating denial before object metadata is exposed |
| Direct SQL state change or row mutation | Database-denied |
| Stale lifecycle version/finalization | No advancement; retry-safe conflict/denial |
| Expired or concurrent upload claim | Metadata reserves one durable attempt identity; Phase 02 must acquire/fence it before any remote I/O |
| `PENDING_UPLOAD` read request | Denied; never returns object reference/body |
| Metadata/audit append failure | Transaction rolls back both effects |
| Existing V007 inline evidence | Existing static and focused tests unchanged and green |

## Acceptance Criteria

- [ ] V014 is additive and passes fresh plus V013-to-V014 contract paths.
- [ ] Metadata includes all ADR-mandated authority fields and excludes secret,
  URL, and raw-body fields.
- [ ] Tenant/RLS, authorization epoch, lifecycle, version fence, and digest /
  byte-count checks are enforced by code and database constraints.
- [ ] No direct runtime mutation bypasses lifecycle policy; audit relations are
  atomic with metadata transitions.
- [ ] Focused tests and a remote PostgreSQL contract runner demonstrate the
  threat matrix without local heavy execution.

## Risks and Rollback

The key risk is making metadata mutable enough for lifecycle operations while
weakening audit immutability. Keep metadata transitions explicitly fenced and
audited; a disabled feature flag is the rollback path. Do not use a down
migration or purge evidence.
