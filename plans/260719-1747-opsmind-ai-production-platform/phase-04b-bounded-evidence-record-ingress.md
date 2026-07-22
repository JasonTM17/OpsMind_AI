---
phase: 4
checkpoint: 4B
title: "Bounded Evidence Record Ingress and Citation Foundation"
status: in_progress
priority: P1
dependencies: [4A, 6-checkpoint, 7-persistence-checkpoint]
---

# Checkpoint 4B: Bounded Evidence Record Ingress and Citation Foundation

## Objective

Remove the evidence-orphan gap before real Phase 7 HTTP clients are enabled.
Persist every accepted, inline Tool Gateway result as a tenant-scoped,
immutable, digest-verifiable `EvidenceRecord` linked to the authoritative
incident, investigation run, tool intent, execution, investigation event, and
actor. The same transaction must append the `EVIDENCE_APPENDED` run event and
its audit record.

This checkpoint is the small-record control plane, not the large-object
artifact plane from [ADR-0003](../../docs/adr/ADR-0003-evidence-artifact-storage.md).
It unblocks grounded replay and citation for the selected bounded Prometheus
slice without claiming that B-006, B-008, or B-012 is resolved.

## Why This Checkpoint Is Required

The current Tool Gateway response contains redacted normalized content and a
content digest but no Platform evidence identity. The current investigation
runner keeps only generated evidence IDs and metadata. A real HTTP client wired
directly to that port would therefore create references that cannot be read,
replayed, authorized, or cited later.

The existing `incident_timeline_events` table is an aggregate-mutation ledger:
its unique ordering key and database trigger intentionally bind one event to
each incident aggregate version. Evidence collection does not mutate the
incident aggregate. This checkpoint therefore preserves that invariant and
links evidence through the already-authoritative `investigation_run_events`
ledger. A later Phase 7 read projection may merge incident mutation events,
investigation events, and evidence records into one operator timeline without
weakening the write models.

## Options Evaluated

| Option | Complexity | Release value | Decision |
|---|---:|---:|---|
| Implement the complete S3/KMS lifecycle first | High; blocked by backend/support decisions | Full artifact plane | Defer; B-006/B-012 remain active |
| Store tool content only inside investigation event JSON | Low initially, high later | No first-class authorization, lifecycle, or citation identity | Reject |
| Persist bounded canonical `EvidenceRecord` rows and keep large artifacts behind the port | Medium | Grounds the Phase 7 slice without pretending to solve object storage | Choose |

Second-order effect: PostgreSQL now carries a deliberately small amount of
redacted evidence content. The boundary must remain hard. Any content over
65,536 canonical UTF-8 bytes, any non-object content, any non-null artifact
reference, or any unsupported trust/source classification fails closed until
the artifact adapter exists.

## Locked Design

### Record identity and replay

- Platform derives one stable evidence UUID from organization, run, and intent
  identity. Model output and Tool Gateway never choose the evidence ID.
- `(organization_id, run_id, intent_id)` is unique. Exact replay with the same
  execution/request/content digests returns the same logical evidence; changed
  bytes or provenance for the same intent is a conflict.
- Tool execution ID is stable per run/intent so Gateway idempotency and Platform
  idempotency describe the same logical call.

### Content and digest boundary

- Accept only one inline evidence envelope for the first
  `observability:metrics.query:1.0` slice. Multi-envelope execution remains a
  future additive contract.
- Store exact canonical JSON text, not provider response text and not a mutable
  object URL. It must decode to one JSON object and be at most 65,536 UTF-8
  bytes.
- Recompute SHA-256 in Platform before persistence and compare it to the
  Tool Gateway `content_digest`. The database independently checks the stored
  canonical bytes against the stored digest.
- Store the digest as 32 bytes; render it externally as `sha256:<64 lowercase
  hex characters>`.
- Raw credentials, authorization headers, provider secrets, raw chain of
  thought, and non-redacted source payloads are forbidden.

### Authority and lifecycle

- Every row carries organization, project, incident, run, actor, source type,
  source identity, target identity, observed window, connector/manifest/policy
  versions, trust class, redaction count, truncation flag, retention class, and
  lifecycle state. Gateway duplicate state is retained so replay provenance is
  not discarded at the Platform boundary.
- Checkpoint 4B accepts only `AVAILABLE`, inline, redacted records with retention
  class `evidence-90d`. It records lifecycle intent but does not claim purge,
  legal hold, deletion receipt, malware scan, restore, or residency proof.
- Forced RLS and current membership/incident authorization remain authoritative
  on every read. An evidence ID is never authority.
- Runtime roles receive `SELECT` and constrained `INSERT`; no runtime
  `UPDATE`, `DELETE`, or `TRUNCATE` grant exists.

### Atomic event linkage

- Reducer commands/events may carry the bounded collected evidence value, but
  reducer state retains only the immutable evidence identity/digest.
- `JdbcInvestigationRunStore.save` persists the run successor, run event,
  evidence record, and audit append within its existing tenant-bound
  transaction.
- The evidence record references exactly one `EVIDENCE_APPENDED` run event;
  database validation rejects mismatched tenant/project/incident/run/actor,
  evidence ID, intent ID, digest, or timestamp.
- Any evidence, run-event, audit, or run-snapshot failure rolls back all four
  effects.

## File Ownership and Expected Changes

| Path | Action | Responsibility |
|---|---|---|
| `services/platform-api/src/main/resources/db/migration/V007__bounded_evidence_records.sql` | CREATE | immutable record schema, RLS, grants, digest/event parity |
| `services/platform-api/src/main/java/ai/opsmind/platform/evidence/**` | CREATE | evidence value, canonicalizer, repository, resolver |
| `services/platform-api/src/main/java/ai/opsmind/platform/investigation/domain/**` | MODIFY | carry bounded evidence through one pure transition |
| `services/platform-api/src/main/java/ai/opsmind/platform/investigation/integration/**` | MODIFY | return collected envelope instead of invented evidence ID |
| `services/platform-api/src/main/java/ai/opsmind/platform/investigation/application/InvestigationEventLedger.java` | MODIFY | transactionally append evidence with run event/audit |
| `services/platform-api/src/main/java/ai/opsmind/platform/investigation/application/JdbcInvestigationRunStore.java` | MODIFY only if wiring is required | preserve the single transaction boundary |
| `services/platform-api/src/test/java/ai/opsmind/platform/evidence/**` | CREATE | canonicalization, validation, repository tests |
| `services/platform-api/src/test/java/ai/opsmind/platform/investigation/**` | MODIFY | reducer/store rollback, replay, parity tests |
| `scripts/validation/validate-phase-04b-evidence-records.mjs` | CREATE | offline contract/migration inventory gate |
| `scripts/validation/run-phase-04b-postgres-contract.*` | CREATE | disposable PostgreSQL RLS/integrity evidence |

No public upload/download endpoint, object-store adapter, or user-supplied
evidence body is introduced here.

## Implementation Sequence

1. Define the bounded evidence value and canonical JSON/digest verifier; prove
   parity against the Tool Gateway valid fixture.
2. Add V007 with immutable evidence rows, composite foreign keys, exact digest
   check, forced RLS, least privilege, replay uniqueness, and run-event parity.
3. Change the Tool Gateway investigation port so callers receive verified
   collected evidence rather than a fabricated Platform evidence ID.
4. Carry the bounded value through `ToolEvidenceReceived` / `EvidenceAppended`
   while keeping investigation state and public projections content-free.
5. Persist the evidence inside the existing `JdbcInvestigationRunStore.save`
   transaction and add exact replay/conflict behavior.
6. Add a read resolver that accepts only evidence IDs belonging to the current
   organization/project/incident/run and verifies the digest before producing
   a redacted AI-runtime evidence input.
7. Run focused unit tests, migration/static gates, disposable PostgreSQL
   RLS/rollback/race tests, then the full Platform Maven suite and repository
   governance checks.

## Acceptance Criteria

- [ ] A valid Tool Gateway fixture canonicalizes to the exact published digest;
  reordered object keys do not change it and changed values do.
- [ ] Oversize, non-object, malformed, unsupported trust/source, non-null
  artifact reference, digest mismatch, unsafe metadata, and unredacted
  sensitive keys/values are rejected before persistence.
- [ ] One accepted result creates exactly one evidence record, one
  `EVIDENCE_APPENDED` run event, one audit row, and one run-state successor in a
  single transaction.
- [ ] Exact replay returns the same evidence identity without another logical
  effect; changed digest, request digest, execution identity, or provenance for
  the same run/intent fails closed.
- [ ] Direct SQL cannot forge linkage, change canonical bytes/digest, cross a
  tenant, or update/delete/truncate an evidence record.
- [ ] A forced failure at each persistence step leaves no partial evidence,
  run-event, audit, or run-snapshot effect.
- [ ] Evidence read rechecks current tenant/incident authority, validates
  run ownership and digest, preserves caller order, and rejects missing,
  duplicate, foreign-run, foreign-incident, and foreign-tenant IDs.
- [ ] In-memory fixture mode remains deterministic and existing reducer/API
  contracts remain backward-compatible until the real clients are enabled.
- [ ] Static validation, focused tests, full Platform `mvn verify`, disposable
  PostgreSQL contract, docs/layout checks, secret scan, and fresh C:/D: capacity
  guards pass.

## Risks and Rollback

- Canonical JSON drift across services would invalidate evidence. Lock fixture
  parity and fail closed; never silently re-hash a different representation.
- Adding content to reducer events could leak into projections/audit. The event
  codec must explicitly serialize metadata only and tests must assert absence of
  content and known secret markers.
- PostgreSQL growth is bounded by 64 KiB per record, 200 records per run, the
  approved 25-concurrent-run envelope, and 90-day retention intent. G3 load
  evidence must measure the resulting table/index pressure.
- Rollback disables the investigation feature and reverts application code.
  V007 remains forward-compatible and immutable; no destructive down migration.

## Deferred Exit Work

- Supported local S3-compatible replacement decision and production backend
  conformance.
- Streaming upload/finalize/read, encryption/KMS, scanning, hold, tombstone,
  restore, purge receipt, and orphan reconciliation.
- Unified operator timeline projection and public evidence detail endpoint.
- Real capability/workload-authenticated HTTP clients and Prometheus connector;
  these start only after this checkpoint passes.

## Implementation Checkpoint (2026-07-22)

V007, the evidence value/canonicalizer/identity/writer/reader boundary, reducer
transport, metadata-only event codec, static gate, and integration tests are
implemented. Local Platform verification reports `146` tests, zero failures or
errors, with `18` environment-gated database tests skipped. Phase 4B static
validation, layout, actionlint, diff, and secret scans pass. Status remains
`in_progress` until GitHub Actions applies V007 to fresh and upgraded PostgreSQL
and executes the RLS, atomicity, authorization, and immutability tests.

## Unresolved Questions

- Which supported local S3-compatible backend replaces archived MinIO after a
  capability matrix and supply-chain review. Garage currently lacks required
  versioning, object lock, and server-side encryption; Ceph RGW is credible but
  too heavy to adopt as a workstation default without measurement.
