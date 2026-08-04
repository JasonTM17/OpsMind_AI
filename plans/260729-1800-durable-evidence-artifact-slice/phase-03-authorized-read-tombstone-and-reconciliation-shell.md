---
phase: 3
title: "Authorized read tombstone and reconciliation shell"
status: in-progress
effort: "5h"
---

# Phase 3: Authorized read tombstone and reconciliation shell

## Overview

Add the authorization and lifecycle-control shell for read, tombstone,
pre-purge restore, purge receipt, and orphan reconciliation. Reuse the current
tenant-bound authorization transaction pattern rather than trusting object
references.

## Context Links

- Parent exit gate:
  `plans/260719-1747-opsmind-ai-production-platform/phase-04-incident-control-plane-and-audit-ledger.md:232-235`
- Authorized read pattern:
  `services/platform-api/src/main/java/ai/opsmind/platform/evidence/EvidenceRecordReader.java:36-76`
- Current incident authorization seam:
  `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentAnalysisAuthorizer.java`
- Current prompt boundary:
  `services/platform-api/src/main/java/ai/opsmind/platform/investigation/integration/InvestigationAnalysisPromptAssembler.java:36-60`
- Current artifact-reference rejection:
  `services/platform-api/src/main/java/ai/opsmind/platform/investigation/integration/ToolGatewayResponseValidator.java:95-116`

## Requirements

- Read checks: tenant/project/incident/run scope, lifecycle state, auth epoch,
  digest, and object presence.
- Tombstone hides bytes before purge and supports explicit restore.
- Purge receipt is explicit metadata, not an implicit delete side effect.
- Reconciliation detects orphan metadata, missing objects, and failed uploads.
- Large-object bytes stay out of audit payloads, event JSON, and AI prompts.

## Architecture

### Authorization flow

1. Resolve current membership and bind tenant context.
2. Verify incident/run visibility.
3. Resolve artifact metadata under RLS.
4. Reject on lifecycle mismatch, auth epoch mismatch, or digest/object
   inconsistency.
5. Open adapter stream only after metadata authorization succeeds.

### Reconciliation shell

- Implement explicit command/service reconciliation first.
- Background scheduling is out of scope for this slice.
- Reconciliation outcomes: mark `ORPHANED`, re-bind `STORED`, or issue purge
  receipt; never infer success from absence alone.

## Related Code Files

### Create

- `services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/access/**`
- `services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/lifecycle/**`
- `services/platform-api/src/test/java/ai/opsmind/platform/evidence/artifact/access/**`
- `services/platform-api/src/test/java/ai/opsmind/platform/evidence/artifact/lifecycle/**`

### Modify

- `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentAnalysisAuthorizer.java`
- `services/platform-api/src/main/java/ai/opsmind/platform/evidence/EvidenceRecordReader.java`
  only if shared authorization helpers are extracted
- `services/platform-api/src/test/java/ai/opsmind/platform/incident/IncidentAnalysisAuthorizerTest.java`
- `services/platform-api/src/test/java/ai/opsmind/platform/evidence/EvidenceRecordReaderTest.java`

### File Ownership

Phase 3 exclusively owns `.../artifact/access/**`, `.../artifact/lifecycle/**`,
and the authorization call sites above. It must not modify upload/adapter files
from Phase 2 except through their public interfaces.

## Implementation Steps

1. Add metadata repository/service methods for read, tombstone, restore,
   deletion request, purge receipt, and reconcile.
2. Extract or extend the current authorization transaction pattern for artifact
   reads.
3. Keep object bytes out of current incident/investigation prompt paths.
4. Add lifecycle and reconciliation tests for foreign tenant/project/incident,
   revocation, auth-epoch mismatch, missing object, orphan metadata, restore,
   and repeated purge receipt.
5. Preserve fail-closed Tool Gateway and AI runtime behavior for artifact
   references in this slice.

## Test Matrix

| Scope | Validation |
|---|---|
| Unit | lifecycle transition matrix, auth-epoch guards |
| Integration | authorized read, tombstone/restore/purge receipt, orphan reconciliation |
| Regression | current AI/Tool Gateway inline-only behavior remains unchanged |

## Success Criteria

- [ ] Artifact read requires tenant/project/incident/run authorization plus auth
  epoch and lifecycle checks.
- [ ] Tombstone, restore, deletion-request, purge receipt, and reconciliation
  have explicit metadata transitions and tests.
- [ ] Missing or foreign artifacts return non-enumerating failures.
- [ ] Audit/event/prompt payloads remain metadata-only; no raw object bytes leak.
- [ ] Tool Gateway and prompt assembly still reject provider-visible artifact
  references until a later cross-service contract lands.

## Risk Assessment

- High: artifact read bypasses the current membership-bound authorization flow.
  Mitigation: reuse or extract the existing transaction pattern, never object-key
  authority.
- Medium: reconciliation scope expands into always-on background work. Mitigation:
  keep this slice command/service based only.

## Rollback

- Disable artifact lifecycle entry points and leave metadata rows inert; no
  existing inline evidence path depends on them.
