---
phase: 2
title: "Upload finalize adapter shell"
status: completed
effort: "4h"
---

# Phase 2: Upload finalize adapter shell

## Overview

Add the application-owned artifact port and transaction boundaries for
create/upload/finalize. Object I/O stays outside PostgreSQL transactions. The
default runtime adapter remains fail-closed. A filesystem adapter is allowed
only for narrow unit/component tests per ADR-0003.

## Context Links

- ADR upload and partial/orphan rule:
  `docs/adr/ADR-0003-evidence-artifact-storage.md:34-44`
- ADR local/production adapter rule:
  `docs/adr/ADR-0003-evidence-artifact-storage.md:58-79`
- Existing inline transaction writer:
  `services/platform-api/src/main/java/ai/opsmind/platform/evidence/EvidenceRecordWriter.java:34-64`
- Existing replay contract:
  `services/platform-api/src/main/java/ai/opsmind/platform/evidence/EvidenceRecordWriter.java:67-104`

## Requirements

- Port operations must separate metadata transaction from object upload.
- Upload must be bounded and digest-aware.
- Finalize must compare expected versus observed digest/length.
- Default runtime implementation must fail closed when no approved adapter is
  configured.
- Test-only filesystem adapter cannot be treated as release evidence.

## Architecture

### Proposed port

- `beginUpload(...) -> artifact metadata`
- `streamUpload(artifactId, InputStream) -> observed digest/length`
- `finalizeUpload(artifactId, observed digest/length/result)`
- `openRead(...) -> bounded stream or denial`

### Transaction split

1. TX1: insert `PENDING_UPLOAD` metadata + audit relation + auth epoch.
2. No TX: stream bytes to adapter.
3. TX2: move to `STORED`/`SCANNING`/`FAILED` after digest/length verification.

### Adapter posture

- `UnavailableEvidenceArtifactStore` is the checked-in default.
- `FilesystemEvidenceArtifactStore` may exist only in test/component scope.
- No MinIO profile or Compose wiring in this slice.

## Related Code Files

### Create

- `services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/port/**`
- `services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/upload/**`
- `services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/adapter/**`
- `services/platform-api/src/test/java/ai/opsmind/platform/evidence/artifact/upload/**`
- `services/platform-api/src/test/java/ai/opsmind/platform/evidence/artifact/adapter/**`

### Modify

- `services/platform-api/src/main/java/ai/opsmind/platform/evidence/EvidenceContentCanonicalizer.java`
  only if shared digest helpers can be extracted without changing inline limits
- `services/platform-api/src/test/java/ai/opsmind/platform/evidence/EvidenceContentCanonicalizerTest.java`
  only if shared helpers are extracted

### File Ownership

Phase 2 exclusively owns `.../artifact/port/**`, `.../artifact/upload/**`, and
`.../artifact/adapter/**`. It may not change authorization files or validation
scripts owned by later phases.

## Implementation Steps

1. Define port interfaces and result records for begin/upload/finalize/read.
2. Implement fail-closed default adapter and test-only filesystem adapter.
3. Add upload coordinator services with explicit transaction boundaries.
4. Add idempotency and digest/length mismatch handling.
5. Add tests for adapter unavailable, partial upload, digest mismatch, and
   repeated finalize.

## Test Matrix

| Scope | Validation |
|---|---|
| Unit | digest/length mismatch, lifecycle transition guards |
| Component | test-only filesystem adapter bounded stream behavior |
| Regression | no remote I/O inside the inline evidence transaction path |

## Success Criteria

- [x] Metadata create and finalize are split across two explicit transactions.
- [x] Default runtime adapter fails closed with stable machine-readable errors.
- [x] Test-only filesystem adapter is isolated to test/component scope.
- [x] Partial upload and digest mismatch do not produce false `AVAILABLE` state.
- [x] Existing inline evidence write/replay semantics remain unchanged.

## Evidence

V015 upload/finalize fencing was merged in PR #46 (`1f87187`) with exact-head
CI coverage for the integrated V014/V015 path. This remains a checkpoint, not
production backend or KMS closure.

## Risk Assessment

- High: accidental transaction widening around object I/O. Mitigation: explicit
  service split and tests that fail if remote I/O is attempted in-transaction.
- Medium: helper extraction weakens inline byte limit. Mitigation: keep the
  64 KiB inline cap invariant covered by existing tests.

## Rollback

- Keep the default adapter unavailable and the feature disabled. Additive code
  can remain inert while the slice is backed out operationally.
