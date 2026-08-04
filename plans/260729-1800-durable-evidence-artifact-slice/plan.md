---
title: "Durable Evidence Artifact Slice"
description: "Add a Phase 4 child execution plan for durable evidence metadata, lifecycle shell, and authorization without claiming production object-store readiness."
status: in-progress
priority: P1
effort: 16h
branch: "feature/artifact-lifecycle-runtime"
tags: [feature, backend, database, security, artifact-lifecycle]
blockedBy: []
blocks: []
created: "2026-07-29"
createdBy: "ck:plan"
source: skill
---

# Durable Evidence Artifact Slice

## Overview

This child plan executes the next credible Phase 4 slice without weakening the
existing bounded-inline evidence contract. V007 is intentionally inline-only:
`evidence_records` stores redacted canonical JSON with a 64 KiB limit, fixed
`AVAILABLE` lifecycle, and no object reference
(`services/platform-api/src/main/resources/db/migration/V007__bounded_evidence_records.sql:12-67`).
Current ingress and prompt paths reject `artifactReference != null` and require
inline content (`services/platform-api/src/main/java/ai/opsmind/platform/evidence/CollectedEvidence.java:42-58`,
`services/platform-api/src/main/java/ai/opsmind/platform/investigation/integration/ToolGatewayResponseValidator.java:95-116`,
`services/platform-api/src/main/java/ai/opsmind/platform/investigation/integration/InvestigationAnalysisPromptAssembler.java:26-60`).

This slice therefore adds a separate metadata/lifecycle plane under Phase 4
ownership (`plans/260719-1747-opsmind-ai-production-platform/phase-04-incident-control-plane-and-audit-ledger.md:23-33`,
`:227-238`) and progresses B-006/B-008 while keeping B-012 and B-011 explicit
(`docs/blockers.md:15-19`). It does not claim supported production backend,
KMS proof, malware scanning, live restore, or provider-visible large-object
analysis.

## Cross-Plan Dependencies

| Relationship | Plan | Status | Why |
|---|---|---|---|
| Child of | `plans/260719-1747-opsmind-ai-production-platform` Phase 4 | In progress | Parent owns upload/finalize/read/tombstone/restore/purge/reconciliation scope. |
| Constrained by | `docs/adr/ADR-0003-evidence-artifact-storage.md` | Accepted | Metadata is authoritative; object I/O is not authorization. |
| Constrained by | `docs/decisions/product-production-contract.json` | Approved | Singapore residency, `production-kms`, 90-day evidence retention, 24-hour deletion SLA, 4-hour artifact restore target remain binding. |

## Dependency Graph

`phase-01 -> phase-02 -> phase-03 -> phase-04`

This plan is intentionally sequential. The hot files are the Platform evidence
package, one new Flyway migration, authorization seams, and validation scripts.
Parallelizing now would create overlap before stable subpackage boundaries
exist.

## Data Flow Lock

1. Create artifact metadata row inside PostgreSQL with `PENDING_UPLOAD`.
2. Stream bytes to an adapter outside the database transaction.
3. Finalize in a second transaction after digest/length verification.
4. Read only after tenant/project/incident/run authorization, lifecycle check,
   and authorization-epoch check.
5. Tombstone, restore, deletion-request, purge receipt, and orphan
   reconciliation mutate metadata and audit only; object location is never
   authority.

## Non-Goals

- No production backend selection or B-012 closure.
- No MinIO/Docker/local heavy object-store profile.
- No Tool Gateway positive artifact path yet; current artifact-reference
  rejection stays fail-closed.
- No provider-visible large-object prompt path.
- No restore drill claim; B-011 remains open.

## Phases

| Phase | Name | Status |
|-------|------|--------|
| 1 | [Metadata authority and migration](./phase-01-metadata-authority-and-migration.md) | Completed |
| 2 | [Upload finalize adapter shell](./phase-02-upload-finalize-adapter-shell.md) | Completed |
| 3 | [Authorized read tombstone and reconciliation shell](./phase-03-authorized-read-tombstone-and-reconciliation-shell.md) | Completed |
| 4 | [Validation docs and release gates](./phase-04-validation-docs-and-release-gates.md) | Pending |

## Verified history

- Phase 1 metadata authority is present in V014 and was merged in PR #27
  (`1da9787`) with the additive, forced-RLS contract.
- Phase 2 upload/finalize fencing is present in V015 and was merged in PR #46
  (`1f87187`); revision-bound CI also covers the integrated V014/V015 path.
- Phase 3 lifecycle controls are implemented with V018 metadata transitions,
  the V019 least-privilege runtime capability, run-bound authorized probe
  entry point, explicit reconciliation commands, and persistence wiring on
  `feature/artifact-lifecycle-runtime`. Focused Java, full module, and
  disposable PostgreSQL evidence passes. Phase 4 release reconciliation and
  production backend/KMS evidence remain open.

## Dependencies

- `docs/adr/ADR-0003-evidence-artifact-storage.md:13-44`, `:46-56`, `:58-79`,
  `:101-119`
- `docs/system-architecture.md:112-120`, `:403-405`, `:466-468`
- `docs/blockers.md:15-19`, `:25-32`
- `docs/decisions/product-production-contract.json:97-110`, `:140-150`
