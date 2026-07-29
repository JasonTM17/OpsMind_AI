---
phase: 2
title: Bounded S3-Compatible Streaming Port
status: in-progress
priority: P1
dependsOn: [phase-01]
---

# Phase 02: Bounded S3-Compatible Streaming Port

## Objective

Implement the real object-storage adapter behind the Phase 01 metadata port.
It must first acquire a durable, lease-fenced upload attempt; then stream a
declared bounded length while hashing, use the approved KMS configuration
boundary, and finalize metadata only after storage success and digest/length
verification. It is disabled by default and must not require secrets in source,
test fixtures, Docker image layers, or browser clients.

## File Inventory

| Path | Action | Responsibility |
|---|---|---|
| `services/platform-api/pom.xml` | Modify | vetted S3 SDK dependencies only if the selected adapter requires them |
| `services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/storage/**` | Create | storage port, bounded stream wrapper, concrete S3-compatible adapter |
| `services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/**` | Modify | create/finalize service and failure taxonomy |
| `services/platform-api/src/main/resources/db/migration/V015__evidence_artifact_upload_fencing.sql` | Create | durable attempts, lease fencing, exact stored transition, least-privilege functions |
| `services/platform-api/src/main/resources/application.yaml` | Modify | default-off, validated endpoint/region/bucket/KMS configuration |
| `.env.example` | Modify | non-secret disabled defaults; no credential fields |
| `services/platform-api/src/test/java/.../evidence/artifact/storage/**` | Create | stream, timeout, digest, adapter failure tests |
| `scripts/validation/validate-phase-04c-evidence-artifacts.mjs` | Modify | assert disabled/default and no credential leakage |
| `scripts/validation/run-phase-04c-artifact-object-postgres-contract.sh` | Create | disposable fresh/upgrade real-role fencing proof |

## Design

- Require expected SHA-256 and byte count in the first implementation so the
  storage key can be content-addressed before streaming. The key also includes
  the opaque artifact identity; equal content never merges authorization rows.
- Use an opaque application-owned object key; never return a presigned URL or
  object-store credential to callers.
- Before remote I/O, atomically acquire an `upload_attempt_id`, lease expiry,
  and monotonically increasing attempt count. Finalization is fenced by that
  attempt. A retry after timeout must HEAD the immutable expected object and
  compare length/checksum/version before it can adopt a possible prior Put;
  it must never assume either success or absence.
- Use an immutable-create precondition where the selected S3-compatible
  backend supports it. A collision with nonmatching checksum/length becomes a
  corruption/orphan path, not an overwrite.
- Require bounded timeouts, HTTPS except an explicit loopback-only component
  test mode, an external workload credential chain, and an encryption metadata
  reference rather than key material.
- On a transient adapter failure, leave metadata retryable and auditable.
  A source-contract or verified-response mismatch after a successful PUT is
  durable `ORPHANED` residue: automated reclaim is denied until Phase 03
  reconciliation, and the object can never be adopted as `STORED`.

## Locked Implementation Decisions

1. Pin AWS SDK for Java v2 `2.49.5` through its BOM and use `s3`, `sts`, and
   `apache5-client`. Do not add the MinIO Java client, a presigner, static
   credentials, or browser-facing storage configuration.
2. Configure one synchronous `PutObject` with `If-None-Match: *`, a
   precomputed SHA-256 checksum, SSE-KMS, a known content length, and automatic
   SDK retries disabled. Phase 02 caps the enabled adapter at 5,000,000,000
   bytes; V014's larger metadata bound is not advertised as upload support.
3. After a successful PUT, consume one extra source byte and require exact EOF,
   length, constant-time digest equality, response checksum, encryption mode,
   canonical KMS response identity, and a non-`null` opaque version reference
   bounded to 1,024 UTF-8 bytes. The request KMS identifier and expected
   canonical response identifier are configured separately. A mismatch is
   possible residue and never becomes `STORED`.
4. A retry after an ambiguous outcome, collision, or expired claim must HEAD
   first with checksum mode enabled. Exact object metadata may be adopted;
   absence permits a new conditional PUT; mismatch becomes `ORPHANED`; an
   unavailable probe prohibits a write.
5. V015 is additive. It removes only Phase-01-only state constraints, adds an
   opaque storage-version reference and immutable upload-attempt authority,
   and exposes narrowly granted `SECURITY DEFINER` claim/settle functions.
   Direct runtime table UPDATE/DELETE/TRUNCATE remains revoked.
6. Object I/O is bracketed by two independent
   `IncidentAnalysisAuthorizer.withAnalyzeAccess(...)` transactions. Current
   authorization is rechecked before claim and before fenced settlement;
   no database transaction remains open during network I/O.
7. Successful settlement advances only `PENDING_UPLOAD -> STORED`. `STORED`
   remains unreadable and uncitable until Phase 03 supplies scanning and an
   independently reviewed `AVAILABLE` transition.

## Parallel Ownership

| Branch | Exclusive ownership | Integration contract |
|---|---|---|
| `feature/artifact-v15-upload-fencing` | V015, PostgreSQL runner, migration/static DB assertions | Exposes claim and settlement result columns named in this phase file |
| `feature/artifact-s3-streaming-adapter` | `pom.xml`, storage package, storage unit/component tests | Implements the storage port without application/database imports |
| `feature/artifact-upload-orchestration` | upload application types/services/repositories and their tests | Consumes the agreed claim/storage records; never edits V015 or storage package |
| integration lead | application YAML, `.env.example`, validator aggregation, CI/docs, merge conflict resolution | Merges and validates all three branches |

No parallel branch may edit another branch's owned files. Shared contracts are
defined before branch creation and changed only by the integration lead.

## Test Matrix

| Scenario | Expected outcome |
|---|---|
| Exact declared stream | Stored/finalized only after digest and length match |
| Short stream or pre-PUT source failure | Retryable bounded failure; later claim probes first when residue is possible |
| Long or digest-drift stream discovered after PUT | `ORPHANED`; later automated claim denied, never adopted as stored |
| Definitive storage failure | `FAILED`; a later claim is immediately retryable without a residue probe |
| Timeout, 5xx, credential error, duplicate object | Stable sanitized failure taxonomy; no raw body/log leak |
| Timeout after remote Put | Later attempt HEAD-verifies immutable object then adopts or quarantines; it never writes blindly twice |
| Concurrent or expired lease | Exactly one active attempt finalizes; stale attempt cannot advance metadata |
| Invalid endpoint/KMS/bucket/timeout config | Startup/enablement fails closed |
| Repeated exact upload/finalize | One logical metadata/object identity, retry safe |
| SDK retry attempt | Disabled; a non-repeatable stream is never replayed behind the durable protocol |
| Stored but unscanned artifact | Metadata is `STORED`; every readable/citation path still denies it |
| Authorization changes after PUT | Settlement denies; object is residue for later reconciliation, never available |

## Acceptance Criteria

- [ ] A production-shaped S3-compatible adapter is real code, default-off, and
  accepts credentials only through an approved external provider chain.
- [ ] Object I/O never runs inside the metadata transaction and never buffers
  the whole artifact in memory.
- [ ] A Docker-free loopback wire component proves request shape,
  streaming/digest/failure behavior without claiming it is an S3 backend or
  production KMS conformance.
- [ ] Disposable PostgreSQL fresh and V014-to-V015 paths prove claim,
  takeover, stale-finalize denial, exact settlement, RLS, and least privilege.
- [ ] Existing inline/gateway behavior remains fail-closed and unchanged.

## External Evidence Boundary

The checked-in invalid MinIO image sentinel remains unchanged while B-012 is
active. A real MinIO/backend component job may be added only after platform and
security owners approve an immutable image digest and supply-chain exception.
That later job cannot by itself prove production backend support, KMS
ownership, Singapore residency, scanning, restore, retention, or deletion.
