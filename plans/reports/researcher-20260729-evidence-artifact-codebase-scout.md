---
title: Evidence Artifact Codebase Scout
date: 2026-07-29
scope: Durable evidence-artifact implementation seams in Platform API and Tool Gateway
status: findings
---

# Evidence Artifact Codebase Scout

## Summary

The repository has a complete bounded-inline evidence control plane, but no
durable large-object artifact implementation. `evidence_records` deliberately
stores canonical redacted JSON only, with a hard 64 KiB bound, fixed
`AVAILABLE` lifecycle, and no artifact reference. The current Tool Gateway and
Platform client intentionally reject an artifact reference or truncated result.

The next artifact slice must therefore be additive: preserve V007 and its
inline replay contract, add a separate metadata/lifecycle boundary, and keep
object-store I/O outside PostgreSQL transactions. A supported local/production
backend decision remains a release prerequisite; this scout does not select one.

## Verified Extension Seams

| Seam | Evidence | Consequence for artifact work |
|---|---|---|
| Architecture contract | `docs/adr/ADR-0003-evidence-artifact-storage.md` | PostgreSQL is authoritative for metadata/lifecycle; bodies stream through an S3-compatible adapter; reads check authorization epoch, lifecycle, and digest. |
| Phase ownership | `plans/260719-1747-opsmind-ai-production-platform/phase-04-incident-control-plane-and-audit-ledger.md` | Full Phase 4 explicitly owns upload/finalize/read/tombstone/restore/purge/orphan reconciliation. It says PostgreSQL and object storage are not one atomic transaction. |
| Existing inline schema | `services/platform-api/src/main/resources/db/migration/V007__bounded_evidence_records.sql` | V007 fixes `canonical_content` to a JSON object of 2–65,536 UTF-8 bytes, validates its SHA-256 in PostgreSQL, fixes `retention_class='evidence-90d'` and `lifecycle_state='AVAILABLE'`, and has no object-reference column. Do not repurpose it for large bodies. |
| Inline value boundary | `services/platform-api/src/main/java/ai/opsmind/platform/evidence/CollectedEvidence.java` | Constructor rejects content over `EvidenceContentCanonicalizer.MAXIMUM_BYTES` and rejects every non-null `artifactReference`. |
| Inline canonicalization | `services/platform-api/src/main/java/ai/opsmind/platform/evidence/EvidenceContentCanonicalizer.java` | `canonicalize(Map)` serializes an in-memory object and caps it at 64 KiB. It is not a bounded streaming/digest implementation. |
| Atomic inline persistence | `services/platform-api/src/main/java/ai/opsmind/platform/evidence/EvidenceRecordWriter.java`, `.../investigation/application/InvestigationEventLedger.java`, `.../JdbcInvestigationRunStore.java` | A V007 record, `EVIDENCE_APPENDED` event, audit event, and run snapshot share the current tenant-bound transaction. Artifact network I/O must not be inserted into this transaction. |
| Exact replay | `services/platform-api/src/main/java/ai/opsmind/platform/investigation/application/InvestigationReplayVerifier.java` | `EvidenceRecordWriter.matchesExact` participates in exact replay. Any new artifact transition needs its own idempotency/replay binding rather than weakening this comparison. |
| Authorized read | `services/platform-api/src/main/java/ai/opsmind/platform/incident/IncidentAnalysisAuthorizer.java`, `.../evidence/EvidenceRecordReader.java` | `requireEvidenceRecords` performs scope/role authorization, tenant-context binding, incident lookup, run-scope validation, RLS query, and digest verification inside one short transaction. This is the correct authorization pattern to reuse or extract for artifact reads. |
| Tenant binding | `services/platform-api/src/main/java/ai/opsmind/platform/incident/JdbcIncidentAccessRepository.java`, `.../tenancy/TenantContextSql.java` | The verified issuer/subject is resolved first; then `opsmind_set_tenant_context` binds organization and actor transaction-locally. Caller-supplied scope is not authority. |
| AI prompt boundary | `services/platform-api/src/main/java/ai/opsmind/platform/investigation/integration/InvestigationAnalysisBoundaryValidator.java`, `InvestigationAnalysisPromptAssembler.java` | The current non-fixture path accepts only non-truncated `metric` records and embeds `canonicalContent` in the prompt. Large artifacts cannot be made provider-visible by merely returning an object reference. |
| Tool Gateway response ingress | `services/platform-api/src/main/java/ai/opsmind/platform/investigation/integration/ToolGatewayResponseValidator.java` | Success requires one non-truncated inline envelope and rejects `artifactReference != null`. This boundary must remain fail-closed until an artifact response contract is implemented. |
| Tool Gateway producer | `services/tool-gateway/src/main/java/ai/opsmind/toolgateway/application/EvidenceNormalizer.java` | Oversize connector output is denied with `RESULT_OVERSIZE`; it does not upload an artifact. |
| Existing future-shaped field | `packages/contracts/json-schema/tool-gateway/v1/evidence-envelope.schema.json`, `services/tool-gateway/src/main/java/ai/opsmind/toolgateway/domain/EvidenceEnvelope.java` | `artifact_reference` exists in the schema/domain record, and is required when `truncated=true`; Platform still rejects it. This is a contract seam, not a working artifact path. |
| Static phase gate | `scripts/validation/validate-phase-04b-evidence-records.mjs:330` | The historical V007 gate currently fails if V007 text contains `CREATE TABLE evidence_artifacts`. A new artifact gate must be separate; the V007 assertion should remain scoped to V007 rather than banning later additive migrations. |

## Current Persistence and Authorization Constraints

- V007 uses forced RLS on `evidence_records`, grants only constrained `SELECT`/
  `INSERT` to `opsmind_app`, and revokes `UPDATE`, `DELETE`, and `TRUNCATE`.
  Its triggers bind an evidence row to one `EVIDENCE_APPENDED` run event and
  reject mutation.
- `InvestigationPersistenceJsonCodec.eventDetails` stores only evidence
  metadata. `InvestigationEvidenceEventSerializationTest` proves canonical
  content, Gateway audit ID, execution ID, and request digest do not enter the
  event/audit payload.
- `IncidentAnalysisAuthorizer.requireEvidenceRecords` first requires
  `incident:analyze` and `ANALYZE`, then resolves current membership and the
  incident before calling `EvidenceRecordReader.resolve`. Missing/foreign
  records return a non-enumerating not-found problem.
- The current V007 record has no authorization-epoch field. ADR-0003 requires
  an authorization-epoch check for artifact reads, so a new artifact metadata
  model must carry that state; it cannot be inferred from an object URL.
- The next unused Platform Flyway number is V014. Existing migrations V001–V013
  are already present under `services/platform-api/src/main/resources/db/migration/`;
  the Phase 4 plan requires additive forward migrations and no edits to applied
  migration bytes.

## Recommended Minimal Vertical Slice

1. Keep the bounded V007 path unchanged. Add a new V014 artifact-metadata
   migration rather than extending V007's fixed inline-only checks. The metadata
   record needs the ADR-required scope, source/provenance, content digest/byte
   count, retention/classification, object reference, lifecycle version, audit
   relation, and authorization epoch.
2. Add an application-owned artifact port in
   `services/platform-api/src/main/java/ai/opsmind/platform/evidence/` with
   distinct create/finalize/read operations. Its create transaction records
   `pending-upload`; bounded stream I/O runs outside that transaction; a second
   transaction verifies expected versus observed digest/length before lifecycle
   advancement. This preserves the ADR lifecycle
   `pending-upload -> stored -> scanning -> available` and avoids pretending the
   database/object-store pair is atomic.
3. Reuse the current short authorization-transaction pattern for artifact read
   and finalization authorization. This requires either an artifact-specific
   method on `IncidentAnalysisAuthorizer` or extraction of its private
   `authorize` operation; do not authorize from an object key/reference.
4. Keep artifact bytes out of `InvestigationPersistenceJsonCodec`,
   `InvestigationEventLedger`, audit payloads, and the browser projection. A
   later, separately reviewed integration may change Tool Gateway and prompt
   contracts after a durable adapter is available.
5. Add a dedicated artifact validator and PostgreSQL integration runner. Amend
   the 4B static rule only so it continues to assert that **V007** is bounded
   inline storage; do not let it reject a later V014 artifact table.

This is the smallest vertical slice compatible with the approved architecture.
It does not close B-006/B-008/B-012 by itself, because backend support, KMS,
scan, retention/deletion, restore, and reconciliation proof are separate
requirements.

## Test Seams

| Test seam | Existing proof | Artifact addition should preserve/add |
|---|---|---|
| Canonical inline behavior | `EvidenceContentCanonicalizerTest` | Keep byte/digest parity for V007; add independent streaming digest/length tests rather than widening the inline limit. |
| Authorized record reads | `EvidenceRecordReaderTest`, `IncidentAnalysisAuthorizerTest` | Add artifact authorization tests for foreign tenant/project/incident/run, revocation, lifecycle denial, and authorization-epoch mismatch. |
| Atomic V007 write | `InvestigationEvidencePersistenceIntegrationTest` | Preserve one inline record/event/audit/snapshot transaction; do not add remote I/O to it. |
| Replay and rollback | `InvestigationEvidenceReplayIntegrationTest`, `InvestigationEvidenceRollbackIntegrationTest` | Add artifact create/finalize idempotency, digest mismatch, adapter failure, and no-partial-metadata assertions. |
| Metadata-only event/audit | `InvestigationEvidenceEventSerializationTest` | Assert no artifact bytes, object credentials, or raw object location enter event/audit JSON. |
| AI boundary | `AuthorizedInvestigationAiRuntimeClientTest` | Keep large/truncated/object-backed evidence out of the current metric prompt until an explicitly bounded provider-facing representation exists. |
| Gateway boundary | `HttpInvestigationToolGatewayBoundaryTest`, `HttpInvestigationToolGatewayClientTest` | Preserve rejection of artifact references until the new contract, capability binding, and durable adapter are live; then add the positive/negative artifact contract matrix. |
| Migration/static gate | `MigrationContractTest`, `scripts/validation/validate-phase-04b-evidence-records.mjs`, `scripts/validation/run-phase-04b-migration-upgrade.sh` | Add V014 fresh/upgrade/RLS/least-privilege tests and a new artifact lifecycle gate without changing historical migration semantics. |

## Risks That Must Stay Explicit

- `EvidenceContentCanonicalizer` and the current Tool Gateway normalizer buffer
  JSON objects; neither proves streamed large-object handling.
- Tool Gateway currently denies oversize results and Platform rejects the schema's
  future `artifact_reference`; enabling either side alone would create an
  invalid cross-service contract.
- The current AI route admits only `metric` evidence and serializes inline
  canonical content. Supplying object references would violate the prompt and
  citation boundary rather than add durable evidence.
- B-006 (lifecycle/restore), B-008 (retention/deletion/residency), and B-012
  (archived MinIO support) remain active in `docs/blockers.md`. B-011 also
  records the unresolved four-hour artifact restore target versus 120-minute
  service RTO conflict.

## Unresolved Questions

- Supported S3-compatible backend and local replacement/supply-chain decision:
  blocked by B-012.
- Artifact KMS topology, scanning, legal hold, replication, consistent backup
  cut, restore drill, and object reconciliation policy: explicitly deferred by
  ADR-0003.
- Whether future artifact evidence can be represented to the AI Runtime without
  exposing raw bodies: current implementation supports only inline redacted
  metrics.
