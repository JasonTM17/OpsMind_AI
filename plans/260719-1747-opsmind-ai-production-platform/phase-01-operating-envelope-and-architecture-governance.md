---
phase: 1
title: Operating Envelope and Architecture Governance
status: completed
priority: P1
dependencies: []
---

# Phase 1: Operating Envelope and Architecture Governance

## Context Links

- [Plan](./plan.md)
- [Brainstorm report](./reports/brainstorm-report.md)
- [Requirements traceability](./research/master-prompt-requirements-traceability.md)
- [Architecture and security research](./research/researcher-01-architecture-security.md)
- [Delivery and evaluation research](./research/researcher-02-delivery-evaluation.md)

## Overview

This phase creates the operating rules before any broad scaffolding or service code exists. It establishes the repository's canonical architecture docs, disk-capacity guardrails, artifact/cache root policy, and the ADR/security/test/deployment documents that every later phase must extend instead of re-inventing.

The key delivery is not runtime code; it is a fail-closed operating envelope. The phase is complete only when the repo has machine-checkable preflight scripts, the `D:`-first storage policy is documented and testable, and future phases have named documents and governance slots instead of relying on tribal memory.

## Objective

Create the safe operating envelope and obtain the product/production decisions that materially determine identity, tenancy, data handling, integrations, storage, reliability and deployment. Documentation/preflight work can proceed immediately; Phase 2 cannot begin until G0.5 is approved.

## Scope and Non-Goals

**In scope**

- Establish the product overview, architecture baseline, local-development policy, test strategy, deployment guide, security model, blocker log, progress log, and ADR convention.
- Define `OPS_CACHE_ROOT`, `OPS_ARTIFACT_ROOT`, `OPS_DATA_ROOT`, and `OPS_MODEL_ROOT` as required configuration inputs, with `D:`-backed defaults and explicit low-space failure behavior.
- Add PowerShell preflight scripts that measure `C:` and `D:` capacity and block heavyweight local work when thresholds are violated.
- Define the evidence-artifact layout used by later phases for verification, security, evaluation, and DR outputs.
- Produce the G0.5 product/production contract: deployment archetype, target environment/region, tenant model, IdP/OIDC profile, permitted DeepSeek data classes, first live integration, evidence artifact store, load envelope, SLO/RTO/RPO, retention/residency, and named operational/risk owners.
- Lock one canonical repository ownership ADR: Spring Tool Gateway, `packages/contracts/{openapi,json-schema,fixtures}`, `compose.yaml`, per-service migration ownership, canonical doc names, and Java/Python naming rules.

**Non-goals**

- No service bootstrap, dependency installation, model download, Docker build, or compose startup.
- No application runtime code, database schema, IdP integration, or provider credentials.
- No destructive cleanup automation; deletion paths stay manual and user-approved only.

## Requirements

### Functional

- `DISK-01`: monitor `C:` and `D:` and block heavy local work below safe thresholds.
- `DEV-01`: establish Windows and Linux/macOS local-development instructions before service code exists.
- `DOC-01`: create the architecture, deployment, testing, evaluation, dataset, local-development, and security document set required by the master prompt and final audit.
- `DoD-25`, `DoD-27`, `DoD-28`, `DoD-29`, `DoD-33`: seed the README/diagram, deployment guide, security model, test strategy, and disciplined delivery process.

### Non-Functional

- `INV-08`: no secrets, raw credentials, or sensitive prompt content in repo defaults or planning artifacts.
- `INV-10`: all later phases inherit fail-closed preflight rules for disk-heavy work.
- `ADD-02`: retention/residency/privacy decisions must be recorded as explicit policy questions, not hidden assumptions.
- `ADD-07`: cost and storage quotas must be named now so later phases implement them consistently.

## Architecture and Data Flow

1. Operator runs `scripts/storage/check-capacity.ps1` before any heavyweight command family such as dependency setup, `docker compose up`, benchmark execution, or training smoke.
2. The script reads policy thresholds and root paths from `.env.example` conventions and/or local env, then emits a pass/fail transcript into `artifacts/verification/phase-01/`.
3. When the preflight passes, later phases may use the documented `D:`-backed cache/artifact/data/model roots; when it fails, they stop before download/build side effects occur.
4. ADRs and core docs become the control plane for architecture changes: later phases update them sequentially instead of creating parallel truth sources.

## File Inventory

| Path | Action | Rough size | Test impact |
|---|---|---:|---|
| `README.md` | CREATE | 120-220 lines | doc validation, diagram rendering |
| `docs/project-overview-pdr.md` | CREATE | 160-260 lines | doc link validation |
| `docs/system-architecture.md` | CREATE | 180-320 lines | architecture review only |
| `docs/local-development.md` | CREATE | 120-220 lines | Windows/Linux command walkthrough |
| `docs/deployment-guide.md` | CREATE | 120-220 lines | release drill later depends on accuracy |
| `docs/testing-strategy.md` | CREATE | 120-220 lines | later suite-to-doc audit |
| `docs/security-model.md` | CREATE | 120-220 lines | later threat-model linkage |
| `docs/code-standards.md` | CREATE | 120-220 lines | naming, boundary and verification conventions |
| `docs/project-roadmap.md` | CREATE | 80-160 lines | milestone/gate tracking |
| `docs/blockers.md` | CREATE | 40-120 lines | manual review |
| `docs/progress.md` | CREATE | 40-120 lines | commit/process audit |
| `docs/decisions/product-production-contract.md` | CREATE | 120-220 lines | G0.5 signed decisions and variant deltas |
| `docs/adr/ADR-0001-platform-topology.md` | CREATE | 80-160 lines | architecture traceability |
| `docs/adr/ADR-0002-contract-and-repository-ownership.md` | CREATE | 80-160 lines | canonical roots, migration and naming rules |
| `docs/adr/ADR-0003-evidence-artifact-storage.md` | CREATE | 80-160 lines | object lifecycle port and production selection criteria |
| `scripts/storage/check-capacity.ps1` | CREATE | 80-160 LOC | Windows C:/D: direct script tests |
| `scripts/storage/check-capacity.sh` | CREATE | 60-120 LOC | workspace-volume portable check |
| `scripts/storage/assert-storage-roots.ps1` | CREATE | 60-120 LOC | direct script tests |
| `scripts/storage/assert-storage-roots.sh` | CREATE | 60-120 LOC | portable root validation |

## Function and Interface Checklist

- PowerShell command `check-capacity.ps1` with explicit threshold flags for `C:` and `D:`; portable shell equivalent checks the workspace and every configured heavy-state root across distinct filesystems.
- `assert-storage-roots` scripts validate configured roots exist, are writable, and do not default to unsafe OS-volume locations.
- Documented env contract: `OPS_CACHE_ROOT`, `OPS_ARTIFACT_ROOT`, `OPS_DATA_ROOT`, `OPS_MODEL_ROOT`, plus low-space thresholds.
- ADR template for future architecture decisions, including context, decision, consequences, and rollback triggers.
- Evidence-artifact directory convention: `artifacts/verification/phase-01/`, `artifacts/evaluation/`, `artifacts/security/`, and `artifacts/dr/`.

## Dependency Map

- Upstream blockers: none.
- Downstream consumers: phases 02 through 16; no later phase may bypass the preflight and document set created here.
- Sequential ownership note: `README.md` and `docs/**` are first owned here. Later phases may extend them only after phase 01 is complete and must update existing sections instead of creating competing documents.

## Implementation Steps

1. Create `README.md` with repository purpose, a real rendered Mermaid architecture diagram, supported host OS notes, and links to the core docs created in this phase.
2. Create the required docs under `docs/` and seed each with explicit sections for assumptions, external dependencies, verification evidence, and unresolved questions.
3. Define the repository-wide artifact layout and the four storage-root env variables, including `D:`-first defaults and a clear statement that no heavyweight artifact may default to `C:`.
4. Implement the Windows and portable capacity checks so they fail closed when free space falls below configured thresholds and write timestamped transcripts without deleting or moving data.
5. Implement both `assert-storage-roots` scripts so they validate configured cache/artifact/data/model roots before later phases create builds, downloads, or benchmarks.
6. Add policy text to `docs/local-development.md` and `docs/blockers.md` describing what counts as a blocker, what remains user-approved only, and where evidence transcripts must be stored.
7. Write `ADR-0001` to lock the four initial runtime deployables, PostgreSQL/pgvector, optional Redis, transactional outbox before Kafka, and Temporal deferral until phase 09.
8. Write `ADR-0002` to lock the Spring Tool Gateway, contract tree, per-service migration ownership, canonical root files and language-appropriate naming; CI in Phase 2 will reject duplicates.
9. Write `ADR-0003` to define the incident-evidence artifact port, content addressing, encryption, authorization, retention/deletion, orphan handling and restore contract; select the production backend at G0.5.
10. Complete `product-production-contract.md` with accountable decisions. If an owner cannot decide an item, record it as a Phase-2 blocker rather than burying it as an assumption.
11. Verify every new doc cross-links correctly and that no secret/example value appears in any generated file.

## Verification Matrix

| Priority | Scenario | Commands | Evidence |
|---|---|---|---|
| Critical | Low-space block prevents heavy work on `C:` | `pwsh ./scripts/storage/check-capacity.ps1 -MinCFreeGb 10 -MinDFreeGb 20` | `artifacts/verification/phase-01/disk-preflight.txt` |
| Critical | G0.5 decisions are owned and complete | decision-schema validation plus architecture/risk review | `artifacts/verification/phase-01/product-production-contract.txt` |
| High | Storage roots resolve to safe writable paths | `pwsh ./scripts/storage/assert-storage-roots.ps1` | `artifacts/verification/phase-01/storage-roots.txt` |
| Medium | Core docs are internally linked and secret-free | link validation plus repository secret scanner | `artifacts/verification/phase-01/doc-secret-scan.txt` |

## Success / Exit Gate

- [x] `README.md`, `docs/`, and `docs/adr/ADR-0001-platform-topology.md` exist with no placeholder text and link to one another coherently.
- [x] Both preflight scripts fail closed below threshold and emit transcripts under `artifacts/verification/phase-01/`.
- [x] Storage-root policy clearly defaults heavy artifacts away from `C:` and names the exact env variables later phases must honor.
- [x] `docs/blockers.md` and `docs/progress.md` exist and explain how future blockers and delivery increments will be recorded.
- [x] G0.5 has approved deployment, identity, provider-egress, integration, storage, load/SLO/DR/data-lifecycle and ownership decisions; the strict validator records `Result=PASS`.
- [x] ADR-0002 leaves one runtime/contract/migration/root-file owner, with no competing source tree in later phase inventories.

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---:|---|
| Docs drift into vague promises | Medium | High | Require explicit evidence sections and sequential updates only |
| Preflight becomes advisory instead of blocking | High | High | Implement fail-closed exit codes and use transcripts in CI/local gating |
| Root-path policy is forgotten by later phases | Medium | High | Put env contract in README, local dev guide, and `.env.example` handoff notes for phase 02 |

## Security Considerations

- Do not include real secret values, provider hosts, or credential samples in any doc or script output.
- Treat disk transcripts as potentially sensitive environment metadata; store only free-space numbers and configured roots, not usernames or unrelated filesystem listings.
- Keep deletion and cleanup paths manual until the operator explicitly approves them.

## Rollback and Forward-Fix Notes

- Docs can be corrected in place if wording is wrong, but ADR reversals require a new ADR that names the superseded decision.
- Preflight thresholds should be forward-fixed by configuration or script patch; do not silently disable the block to unblock later work.
- If a document structure proves wrong, update the document and its inbound links in the same change set so no stale navigation remains.

## Next Phase

Phase 02 consumes the docs, env contracts, and preflight policy from this phase to build the workspace skeleton, CI scaffold, and standard command families without violating the `D:`-first storage rule.

## Unresolved Questions

- None. The full recommended baseline was explicitly approved on 2026-07-19 and is recorded in the authoritative product/production contract.
