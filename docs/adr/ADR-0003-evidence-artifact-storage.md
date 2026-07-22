# ADR-0003: Evidence Artifact Storage Port

- Status: Accepted
- Date: 2026-07-19
- Decision owners: Architecture governance, `platform-team`, and `security-team`

## Context

Incident evidence can be large, sensitive, mutable at its source, and subject to retention, legal hold, deletion, residency, malware scanning, and restore requirements. Storing all bodies in PostgreSQL would increase transactional and backup pressure. Treating object URLs as authorization would leak data and make lifecycle control provider-specific.

## Decision

Introduce an application-owned evidence artifact port in the incident control plane. PostgreSQL stores authoritative tenant/incident metadata, lifecycle state, source/version identity, classification, content digest, byte count, authorization epoch, retention class, object reference, and audit relation. Large bodies stream to an S3-compatible adapter selected per environment.

The port accepts:

- verified tenant, project, incident, actor/workload, and policy context;
- source identity/version and classification;
- bounded content stream and optional expected digest/length;
- retention, residency, and deletion class;
- correlation and idempotency identity.

The port returns:

- opaque artifact identity;
- cryptographic content digest;
- stored byte count;
- storage/lifecycle version;
- encryption metadata reference without key material;
- scan/availability state.

## Required Properties

1. Authorization is checked through platform metadata; object location or signed URL is not sufficient authority.
2. Content addressing detects duplicate/corrupt bodies without merging authorization records.
3. Encryption uses the approved `production-kms` boundary; implementation must
   prove key ownership, isolation, rotation, and recovery behavior.
4. Upload is bounded and streamed; partial and orphan objects are reconciled.
5. Read verifies authorization epoch, lifecycle state, and digest.
6. Retention hold, expiry, deletion request, purge, and deletion receipt are explicit states.
7. Malware/content scanning gates availability where policy requires.
8. Backup/restore preserves metadata-object consistency and reports missing or extra objects.
9. Logs, traces, and errors never include raw bodies or credentials.
10. Revocation immediately blocks access even if physical purge is asynchronous.

## Lifecycle

```text
pending-upload -> stored -> scanning -> available
                         -> quarantined
available -> held | deletion-requested | expired
deletion-requested/expired -> purged -> receipt-recorded
orphaned/failed -> reconciled or purged
```

Transitions are controlled by deterministic application policy and audited. Object-store lifecycle rules support but do not replace application lifecycle state.

## Local and Production Adapters

MinIO is the approved local adapter because it exercises the S3-compatible
boundary. Production uses an S3-compatible backend in Singapore behind the
`production-kms` boundary, with lifecycle accountability assigned to
`platform-security` and an artifact restore target of four hours.

New evidence after approval: the
[upstream MinIO repository](https://github.com/minio/minio) was archived on
2026-04-25. Phase 2 may pin its final security release for isolated local
development only because that preserves the approved adapter contract without
claiming production support. Production promotion requires a supported
replacement/supply-chain decision or an explicitly bounded exception and exit
plan; the approved production backend remains the abstract S3-compatible port,
not MinIO.

Bucket/account topology, replication, versioning, object lock, malware
scanning, and backup integration remain implementation decisions that must
conform to this baseline.

Filesystem storage may be used only for narrow unit/component tests and cannot be production evidence.

## Consequences

Positive:

- Database transactions remain focused on metadata and durable control state.
- Storage backend can vary without changing incident contracts.
- Provenance, authorization, deletion, and restore have a single domain contract.
- Large artifacts can stream and scale independently.

Costs:

- Metadata/object consistency requires outbox, reconciliation, and orphan handling.
- Backups need cross-system watermarks.
- Deletion and legal hold require application plus backend coordination.

## Alternatives Considered

- PostgreSQL large objects for every body: rejected as the default because size, backup, and streaming pressure would couple control and evidence planes.
- Direct connector-to-object-store writes: rejected because authorization, digest, lifecycle, and audit could be bypassed.
- Provider-specific object URLs in domain records: rejected due to portability and authorization leakage.

## Verification

Phase 4 tests upload/idempotency/digest/orphan/authorization behavior. Phase 10 tests revoke/delete across retrieval. Phase 15 injects outage and storage exhaustion. Phase 16 proves consistent backup, restore, lifecycle, and reconciliation.

## Rollback or Supersession Triggers

New regulatory, sovereignty, immutability, scale, or performance evidence may require a backend/topology ADR. The domain port and metadata authority remain unless evidence proves a different consistency model.

An unavailable security fix, incompatible license/support posture, or inability
to reproduce the pinned local image also triggers supersession of the MinIO
local adapter.

## Remaining Implementation Decisions

The G0.5 backend, KMS boundary, Singapore residency, retention durations, and
24-hour deletion SLA are approved. Detailed key topology, legal-hold behavior,
malware scanning, replication, consistent backup cut, and restore proof remain
later release gates. The four-hour artifact restore target must also be
reconciled with the service-level 120-minute RTO before production promotion.

See the
[authoritative contract](../decisions/product-production-contract.json) and
[approval record](../decisions/g0-5-approval-2026-07-19.md).
