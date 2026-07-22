---
phase: 2
title: "Monorepo and Developer Platform Foundation"
status: in-progress
priority: P1
dependencies: [1]
---

# Phase 2: Monorepo and Developer Platform Foundation

## Context Links

- [Plan](./plan.md)
- [Phase 1](./phase-01-operating-envelope-and-architecture-governance.md)
- [Requirements traceability](./research/master-prompt-requirements-traceability.md)
- [Delivery and evaluation research](./research/researcher-02-delivery-evaluation.md)

## Overview

This phase creates the polyglot repository skeleton and the standard command surface for frontend, Java, Python, Docker Compose, and CI. It must stay lightweight enough for the constrained workstation while still producing a real bootstrap path for every deployable that later phases will fill in.

The phase is complete only when a clean clone can discover the workspace layout, read `.env.example`, run the preflight and baseline command families, and validate the PR CI definition without requiring broad application logic.

## Objective

Bootstrap one canonical polyglot workspace and cross-platform command surface after G0.5. This phase establishes build/test wiring only; it must not fork contracts, migrations, service runtimes or root-file names decided in ADR-0002.

## Scope and Non-Goals

**In scope**

- Create the root workspace files, service/app directories, package/build manifests, `.env.example`, and `Makefile` command families.
- Add a minimal `compose.yaml` and PR CI workflow that validate structure, lint/typecheck hooks, and future service bootstrap.
- Wire the phase 01 storage-root and preflight policy into the root command surface so heavy tasks block before side effects.

**Non-goals**

- No production-ready business logic, database schema, or provider integration.
- No image publish, release automation, Kubernetes manifests, or full observability stack startup.
- No large dependency download if the preflight says the host is unsafe.

## Requirements

### Functional

- `DEV-01`: support Windows and Linux/macOS bootstrap from a clean clone.
- `DEV-02`: create standard `setup`, `dev`, `test`, `lint`, `build`, `up`, `down`, `migrate`, `seed`, `evaluate`, and `security-scan` command families.
- `CI-01`: define PR quality gates for Java, Python, frontend, migrations, and secret/dependency scanning.
- `DoD-01`, `DoD-02`, `DoD-03`, `DoD-24`: establish the minimum repo, compose, and CI foundation later phases can harden.

### Non-Functional

- `INV-08`: no secrets in repo defaults, CI examples, or compose skeletons.
- `INV-10`: root commands must honor disk preflight and configurable artifact roots before downloads or builds.
- `GIT-01`: commits and progress logging must be supported by the repo structure, not bolted on later.

## Architecture and Data Flow

1. Operator runs `make setup` or equivalent root command family.
2. The root command first invokes the phase 01 preflight scripts, then resolves env defaults from `.env.example` and local overrides.
3. Language-specific bootstraps fan out to the four runtime deployables: `apps/operator-web`, `services/platform-api`, `services/ai-runtime`, and `services/tool-gateway`. `services/incident-simulator` is a development/test-only optional profile introduced by Phase 8.
4. CI mirrors the same contract: preflight/static validation first, then language-specific lint/type/test jobs, then compose/config validation.

## File Inventory

| Path | Action | Rough size | Test impact |
|---|---|---:|---|
| `.env.example` | CREATE | 80-160 lines | config validation, secret-safety checks |
| `.gitignore` | CREATE | 60-120 lines | prevents secret/artifact leakage |
| `.editorconfig` | CREATE | 20-40 lines | formatting consistency only |
| `package.json` | CREATE | 40-100 lines | root script validation |
| `pnpm-workspace.yaml` | CREATE | 10-30 lines | workspace resolution |
| `Makefile` | CREATE | 80-160 lines | all root command families |
| `scripts/dev/opsmind.ps1` | CREATE | 100-180 lines | native Windows command surface |
| `scripts/dev/opsmind.sh` | CREATE | 100-180 lines | Linux/macOS command surface |
| `compose.yaml` | CREATE | 120-220 lines | `docker compose config` validation |
| `.github/workflows/pr-quality.yml` | CREATE | 120-220 lines | `actionlint` / CI schema validation |
| `packages/contracts/openapi/opsmind-v1.yaml` | CREATE | 40-100 lines | sole OpenAPI root |
| `packages/contracts/json-schema/` | CREATE | scaffold | sole JSON Schema root |
| `packages/contracts/fixtures/` | CREATE | scaffold | cross-language conformance fixtures |
| `apps/operator-web/package.json` | CREATE | 40-100 lines | frontend bootstrap |
| `apps/operator-web/app/**` and config | CREATE | minimal scaffold | frontend build, type, lint, smoke tests |
| `services/platform-api/pom.xml` | CREATE | 80-160 lines | Java bootstrap |
| `services/platform-api/src/**` | CREATE | minimal scaffold | context and health smoke tests |
| `services/ai-runtime/pyproject.toml` | CREATE | 60-120 lines | Python bootstrap |
| `services/ai-runtime/src/**`, `services/ai-runtime/tests/**` | CREATE | minimal scaffold | import, health, lint, type, unit tests |
| `services/tool-gateway/pom.xml` | CREATE | 80-160 lines | gateway bootstrap |
| `services/tool-gateway/src/**` | CREATE | minimal scaffold | context and health smoke tests |
| `apps/operator-web/Dockerfile`, `services/*/Dockerfile` | CREATE | 20-50 lines each | compose build contract; builds deferred locally when capacity is tight |
| `scripts/validation/**` and command-surface tests | CREATE | 60-180 lines each | canonical ownership, config and fail-closed command checks |

## Function and Interface Checklist

- PowerShell and POSIX commands: `setup`, `dev`, `test`, `lint`, `build`, `up`, `down`, `migrate`, `seed`, `evaluate`, and `security`; `security-scan` remains an explicit alias required by `DEV-02`. `Makefile` is a thin wrapper over the portable script.
- Root env contract in `.env.example` including storage roots, service ports, and placeholder external endpoints with no live secrets.
- `compose.yaml` services for PostgreSQL/pgvector, optional Redis, platform API, AI runtime, Tool Gateway, and Operator Web. The former MinIO slot remains isolated in a non-routable review-only profile until blocker B-012 is resolved. Simulator/evaluation are not runtime services in this phase.
- PR workflow jobs for docs/preflight, Java checks, Python checks, frontend checks, compose/config validation, and secret/dependency scanning.
- Workspace directories and bootstrap manifests for all initial deployables so later phases can add code without restructuring the repo.

## Dependency Map

- Upstream blocker: phase 01 docs, ADRs, and preflight policy.
- Downstream consumers: phases 03 through 16 depend on the workspace layout, root scripts, and CI skeleton created here.
- Entry gate: satisfied on 2026-07-19 by the approved G0.5 decision artifact, strict contract `PASS`, completed Phase 1 exit gate, and accepted ADR-0002.
- Sequential ownership note: `.env.example`, `.github/workflows/**`, `Makefile`, `scripts/dev/**`, `compose.yaml`, and `packages/contracts/**` are first owned here; later phases may extend them only after phase 02 is complete.

## Implementation Steps

1. Create the root workspace manifests and directories for the web app, platform API, AI runtime, Spring Tool Gateway, canonical contracts, offline training/evaluation, infrastructure, and docs; keep simulator out of the runtime topology until Phase 8.
2. Add `.gitignore` entries for `.env`, caches, artifacts, model outputs, and local IDE state so later phases do not leak secrets or large blobs.
3. Create `.env.example` with non-secret placeholders and documented defaults for ports, storage roots, provider base URLs, and feature flags.
4. Implement `opsmind.ps1` and `opsmind.sh`; make every heavyweight verb call the Phase 1 preflight before executing language-specific tooling. `Makefile` delegates to the portable script rather than becoming a second behavior source.
5. Add minimal bootstrap manifests for Next.js, Spring Boot, and FastAPI/pytest so the repo shape is real and testable even before business logic exists.
6. Create `compose.yaml` with buildable health-only service scaffolds, healthcheck hooks, D-backed configurable data/artifact bind mounts, and no production-only assumptions. Preserve the object-store slot only as a disabled review profile while blocker B-012 remains open.
7. Create `.github/workflows/pr-quality.yml` with structure/static-validation jobs first and language-specific jobs second.
8. Update `README.md` and `docs/local-development.md` to point at the standard commands and explain which commands are intentionally blocked on low disk.

## Verification Matrix

| Priority | Scenario | Commands | Evidence |
|---|---|---|---|
| Critical | Windows and Linux clean clones expose identical command semantics | `pwsh ./scripts/dev/opsmind.ps1 setup` and `./scripts/dev/opsmind.sh setup` in clean CI jobs | `artifacts/verification/phase-02/setup-matrix.txt` |
| High | Compose skeleton builds, starts, and exposes healthy loopback-only services | `docker compose config`; CI Compose build/up/probes/down | `artifacts/verification/phase-02/compose-config.txt`, `compose-smoke.txt` |
| High | Canonical ownership has no duplicate OpenAPI/schema/migration roots | repository layout validator | `artifacts/verification/phase-02/repository-layout.txt` |
| Medium | PR workflow schema, tool pins, root scripts, and locks are valid | Phase 2 foundation validator and `actionlint` | `artifacts/verification/phase-02/foundation-validation.txt`, `ci-and-lint.txt` |

## Current Review Status (2026-07-20)

Local implementation and adversarial review fixes are green. See
[the adversarial landing audit](./reports/adversarial-review-260720-phase-02.md).
The phase remains **in progress** because the authoritative clean-runner
bootstrap and Docker Compose smoke artifacts must still be produced by the
remote PR workflow; local validation cannot substitute for those runner
boundaries.

## Success / Exit Gate

- [x] Root workspace files and per-service bootstrap manifests exist and match the four-initial-runtime topology from `ADR-0001`.
- [x] `Makefile` command families invoke preflight before heavyweight work and document blocked behavior clearly.
- [x] `.env.example` contains placeholders only, names the storage-root env vars, and does not leak credentials or machine-specific paths.
- [ ] `compose.yaml` and `.github/workflows/pr-quality.yml` validate successfully and store transcripts under `artifacts/verification/phase-02/` (local static/config evidence passes; remote Compose smoke and clean-runner transcripts pending).
- [x] All contracts live under `packages/contracts/**`; CI rejects duplicate endpoint/schema IDs and alternate contract roots.

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---:|---|
| Polyglot bootstrap becomes over-engineered | Medium | Medium | keep commands thin, let later phases deepen only touched services |
| CI definitions assume tools not yet installed | Medium | High | validate syntax first; gate heavy jobs behind preflight and optional profiles |
| `.env.example` drifts from docs | Medium | High | update `.env.example`, README, and local-development guide in the same change set |

## Security Considerations

- Every placeholder endpoint or token variable in `.env.example` must be obviously fake and documented as such.
- Compose files and CI workflows must avoid plaintext secrets and must not assume privileged Docker, shell, or cloud credentials.
- Artifact/cache paths must stay under operator-controlled roots so local secrets do not get written into random temp directories.

## Rollback and Forward-Fix Notes

- Workspace manifest mistakes are forward-fixed by updating the root manifests and README together; avoid renaming directories after later phases depend on them.
- CI miswiring is rolled back by disabling the affected job only if syntax validation still passes and the reason is documented in `docs/blockers.md`.
- Compose skeleton changes should preserve service names once phase 03+ begins relying on them; use additive fixes, not churn.

## Next Phase

Phase 03 builds the first authoritative contracts, identity/tenant model, Problem Details API baseline, and outbox/RLS foundation on top of the workspace and root command surface created here.

## Unresolved Questions

- The approved service RTO is 120 minutes while the artifact-store restore target is four hours. Preserve both approved values and treat their reconciliation as a Phase 16 release gate; do not silently weaken either target in this scaffold.
- MinIO was approved for local use on 2026-07-19, but its upstream repository
  had already been archived on 2026-04-25; that maintenance fact was not
  surfaced in the approval packet. Keep the Compose slot disabled and
  non-routable until the platform/security owners explicitly accept a bounded
  local-only pin or approve a supported S3-compatible replacement.
