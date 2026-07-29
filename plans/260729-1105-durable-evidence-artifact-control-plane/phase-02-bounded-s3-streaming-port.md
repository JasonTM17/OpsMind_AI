---
phase: 2
title: Bounded S3-Compatible Streaming Port
status: pending
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
| `services/platform-api/src/main/resources/application.yaml` | Modify | default-off, validated endpoint/region/bucket/KMS configuration |
| `services/platform-api/src/test/java/.../evidence/artifact/storage/**` | Create | stream, timeout, digest, adapter failure tests |
| `scripts/validation/validate-phase-04c-evidence-artifacts.mjs` | Modify | assert disabled/default and no credential leakage |

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
- On adapter failure or checksum/length mismatch, leave metadata retryable and
  auditable, classify possible object residue for Phase 03 reconciliation, and
  never mark it `AVAILABLE`.

## Test Matrix

| Scenario | Expected outcome |
|---|---|
| Exact declared stream | Stored/finalized only after digest and length match |
| Short, long, or digest-drift stream | No available artifact; bounded failure metadata |
| Timeout, 5xx, credential error, duplicate object | Stable sanitized failure taxonomy; no raw body/log leak |
| Timeout after remote Put | Later attempt HEAD-verifies immutable object then adopts or quarantines; it never writes blindly twice |
| Concurrent or expired lease | Exactly one active attempt finalizes; stale attempt cannot advance metadata |
| Invalid endpoint/KMS/bucket/timeout config | Startup/enablement fails closed |
| Repeated exact upload/finalize | One logical metadata/object identity, retry safe |

## Acceptance Criteria

- [ ] A production-shaped S3-compatible adapter is real code, default-off, and
  accepts credentials only through an approved external provider chain.
- [ ] Object I/O never runs inside the metadata transaction and never buffers
  the whole artifact in memory.
- [ ] A component environment proves streaming/digest/failure behavior without
  claiming it is production backend or KMS conformance.
- [ ] Existing inline/gateway behavior remains fail-closed and unchanged.
