---
phase: 1
title: "Metadata authority and migration"
status: completed
effort: "4h"
---

# Phase 1: Metadata authority and migration

## Overview

Add the additive schema and domain model for artifact metadata. Preserve V007 as
the immutable bounded-inline path. Do not repurpose `evidence_records` for
large objects.

## Context Links

- Parent scope owner:
  `plans/260719-1747-opsmind-ai-production-platform/phase-04-incident-control-plane-and-audit-ledger.md:23-33`
- Deferred artifact work:
  `plans/260719-1747-opsmind-ai-production-platform/phase-04-incident-control-plane-and-audit-ledger.md:120-129`
- Full exit gate:
  `plans/260719-1747-opsmind-ai-production-platform/phase-04-incident-control-plane-and-audit-ledger.md:227-238`
- ADR baseline:
  `docs/adr/ADR-0003-evidence-artifact-storage.md:13-44`, `:46-56`
- Current inline constraint:
  `services/platform-api/src/main/resources/db/migration/V007__bounded_evidence_records.sql:12-67`

## Requirements

- Add a new additive Flyway migration at V014. Never edit V007 bytes.
- Store artifact metadata separately from inline evidence rows.
- Carry authoritative scope and control fields:
  `organization_id`, `project_id`, `incident_id`, `run_id`, source identity,
  classification, digest, byte count, retention class, lifecycle state,
  authorization epoch, object reference, lifecycle version, audit relation,
  timestamps.
- Force RLS and least-privilege grants from the first migration.
- Keep the schema compatible with later supported-backend selection.

## Architecture

- New table family: `evidence_artifacts` plus narrow helper tables only when a
  real invariant needs them.
- Initial lifecycle set:
  `PENDING_UPLOAD`, `STORED`, `SCANNING`, `AVAILABLE`, `TOMBSTONED`,
  `DELETION_REQUESTED`, `PURGED`, `RECEIPT_RECORDED`, `ORPHANED`, `FAILED`.
- `TOMBSTONED` is the explicit pre-purge hidden state required by the parent
  Phase 4 exit gate. If implementation proves the ADR text should be refined,
  update the ADR in Phase 4 rather than silently diverging.
- Metadata remains the authorization authority; object key or URL is never
  sufficient.

## Related Code Files

### Create

- `services/platform-api/src/main/resources/db/migration/V014__evidence_artifact_metadata.sql`
- `services/platform-api/src/main/java/ai/opsmind/platform/evidence/artifact/model/**`
- `services/platform-api/src/test/java/ai/opsmind/platform/evidence/artifact/model/**`

### Modify

- `services/platform-api/src/test/java/ai/opsmind/platform/persistence/MigrationContractTest.java`
- `scripts/validation/run-phase-04b-migration-upgrade.sh`

### File Ownership

Phase 1 exclusively owns the migration and `.../evidence/artifact/model/**`.
Later phases may consume the model but must not modify the V014 migration text.

## Implementation Steps

1. Design the metadata table and enum values from ADR-0003 and the parent exit
   gate.
2. Add V014 with additive tables, constraints, indexes, RLS, grants, and
   comments.
3. Create artifact model records/enums/value objects under a dedicated
   subpackage so later phases can work without file overlap.
4. Add migration-contract assertions for fresh and upgrade paths, grants, and
   forbidden broad mutation.
5. Confirm V007 remains unchanged and still represents bounded inline evidence
   only.

## Test Matrix

| Scope | Validation |
|---|---|
| Unit | enum/value validation, digest/length field rules |
| Integration | fresh/upgrade migration, RLS, least-privilege grants |
| Regression | V007 still fixed to inline-only semantics |

## Success Criteria

- [x] V014 is additive and forward-only; V001-V013 bytes remain unchanged.
- [x] Metadata schema contains digest, byte count, lifecycle, auth epoch,
  retention, and object reference fields.
- [x] Forced RLS and least-privilege grants exist for the new table(s).
- [x] Migration tests prove fresh and upgrade paths.
- [x] No code path widens V007 inline semantics.

## Evidence

V014 was delivered by PR #27 (`1da9787`) and is covered by the revision-bound
V014/V015 PostgreSQL contract recorded in the roadmap.

## Risk Assessment

- High: schema names or enums force a later ADR rewrite. Mitigation: keep model
  provider-neutral and lifecycle-oriented.
- High: touching V007 breaks exact replay or existing gates. Mitigation: no V007
  edits; new migration only.

## Rollback

- Migration is additive. Rollback is feature-disable plus leaving unused tables
  in place until a later governed cleanup migration.
