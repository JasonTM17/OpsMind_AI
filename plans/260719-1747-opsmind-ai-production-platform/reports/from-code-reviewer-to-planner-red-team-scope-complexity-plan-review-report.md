# Red-Team Scope and Complexity Plan Review

## Scope

Reviewed `plan.md`, phases 01-16, the traceability matrix, and both research reports as a greenfield delivery plan. Lens: YAGNI/KISS, critical path, ownership duplication, sequencing cost, and graduation gates. No code/build/lint was run.

## Verdict

**REVISE BEFORE IMPLEMENTATION.** Full A-to-Z scope can remain, but the current critical path mixes mandatory production capability with experiments, repeats integration layers, and leaves shared ownership inconsistent. The fixes below preserve scope while moving optional scale/model choices behind measured entry gates.

## Findings

### 1. High — One platform is described by multiple incompatible source trees

**Evidence:** The plan locks a Spring Tool Gateway (`plan.md:23`, `:31`) and Phase 2 creates its Maven build (`phase-02-monorepo-and-developer-platform-foundation.md:75`), while Phase 6 creates a Python service (`phase-06-safe-tool-gateway-and-read-only-connectors.md:91-107`). Phase 3 declares `packages/api-contracts/**` and `packages/shared-schemas/**` authoritative (`phase-03-contracts-data-identity-and-tenant-foundation.md:67-69`, `:90`); phases 5-8 use top-level `contracts/**`, and phases 9-12 use `packages/contracts/**` (`phase-09-durable-investigation-workflow.md:70`, `:77-79`). Compose, CI, test-strategy, and changelog names also change between phases 1/2 and 16.

**Second-order effect:** each team builds a correct component against a different repository contract, creating generators, adapters, migration ordering, and CI duplication before product behavior exists.

**Fix:** lock one ADR before Phase 2: Spring Tool Gateway; `packages/contracts/{openapi,json-schema,fixtures}`; per-service migration ownership with one ordering policy; `compose.yaml`; one CI workflow family; one canonical docs path per subject. Add a plan validator that rejects duplicate schema/route/migration ownership.

### 2. High — The thin slice is implemented twice instead of evolved through one deterministic core

**Evidence:** Phase 7 builds a bounded orchestration process manager in the platform API (`phase-07-thin-evidence-backed-incident-vertical-slice.md:54`, `:103-126`). Phase 9 then creates another controller/orchestration path and moves the loop into Temporal (`phase-09-durable-investigation-workflow.md:77-93`, `:100-124`). Both own investigation state, duplicate/no-progress logic, budgets, and projections.

**Second-order effect:** Phase 9 becomes a migration/rewrite with two run semantics, double tests, possible duplicate starts, and replay incompatibility precisely when the system begins adding RAG and approvals.

**Fix:** Phase 7 must implement a pure deterministic investigation state machine and ports, with an in-process runner only as the first adapter. Phase 9 replaces the runner with a Temporal adapter around the same commands/events/stop reasons, plus an explicit active-run cutover. Never create a second domain model or controller route.

### 3. High — A fixture-only product proof postpones the first expensive integration fact

**Evidence:** Phase 6 allows fixture-first connector families and makes live smoke optional (`phase-06-safe-tool-gateway-and-read-only-connectors.md:147-157`, `:186-195`). Phase 7 then proves a seeded local slice (`phase-07-thin-evidence-backed-incident-vertical-slice.md:130-138`, `:166-173`). Yet the concrete first observability/deployment/source systems and signer remain unresolved (`phase-06-safe-tool-gateway-and-read-only-connectors.md:207-211`). The evidence artifact backend is also unowned until the optional dataset profile in Phase 13 (`phase-13-dataset-flywheel-and-governance.md:54`).

**Second-order effect:** auth delegation, pagination, timestamps, query cost, redaction, artifact retention, and schema drift are discovered after evidence contracts and evaluation fixtures have hardened around idealized data.

**Fix:** select one named non-production read integration and an S3-compatible evidence-artifact port in G0.5. Phase 4 owns artifact metadata/lifecycle; Phase 6 must pass one real synthetic non-production integration before G3. Keep fixtures for determinism, not as product evidence.

### 4. High — Student-model experimentation unnecessarily blocks production hardening

**Evidence:** Phase 14 requires unresolved GPU/registry/license/budget decisions and may correctly end without promotion (`phase-14-student-model-training-shadow-and-promotion.md:24-30`, `:123-129`, `:149-153`). Phase 15 nevertheless depends on Phase 14 (`phase-15-security-reliability-and-observability-hardening.md:5-7`), and G6 bundles student work with core product completion (`plan.md:123`). Research says small teams should defer full student work and promotion is conditional on measurable benefit (`research/researcher-02-delivery-evaluation.md:243-267`, `:287-292`).

**Second-order effect:** security/load/DR work waits on a model experiment with no demonstrated ROI, data volume, compute, or legal path.

**Fix:** keep TRAIN-01 bounded smoke and model-card evidence mandatory for the A-to-Z DoD, but run Phase 14 as a parallel lane after Phase 13. Remove Phase 14 from Phase 15 prerequisites. Shadow/canary/promotion execute only if a signed entry gate proves dataset sufficiency, license, compute/residency, and projected payback; `do not promote` is an acceptable evidence-backed outcome.

### 5. High — The estimate is not a staffed delivery model

**Evidence:** `plan.md` declares 38-56 engineer-weeks (`plan.md:6`), while the delivery research assumes roughly eight cross-functional roles and a 5-7 month calendar (`research/researcher-02-delivery-evaluation.md:269-292`). The 16 phases include vendor/legal decisions, live integrations, security review, human labeling, GPU work, staging/DR, documentation, and ten-scenario E2E, but their review/coordination and external lead times are not represented.

**Second-order effect:** the date is protected by cutting evaluation, security, or operational rehearsal, producing nominal completion without the evidence gates the plan depends on.

**Fix:** replace the single estimate with three scenario ranges after G0.5: 3-4 core engineers, 6-8 cross-functional contributors, and full team. Show critical path, parallel lanes, external lead times, review capacity, 25-35% contingency, and what remains staging-only when an external decision is absent.

## Recommended sequencing correction

1. Phase 1 ends with G0.5 decisions and canonical ownership ADRs.
2. Phases 2-4 establish one contract tree, artifact storage port, trust/data foundation, and pure investigation state machine contracts.
3. Phases 5-8 prove DeepSeek conformance, one live non-production connector, the thin slice, and statistically honest baseline.
4. Phases 9-13 evolve durability, RAG, approval, UI, and data governance.
5. Phase 14 runs in parallel with Phase 15 after its entry gate; only bounded training smoke is unconditional.
6. Phase 16 closes release, DR, and A-to-Z evidence.

## Unresolved questions

- Actual team size, deadline, budget, review capacity, and external procurement lead times.
- First live observability/deployment/source integration and evidence object-store provider.
- Whether student shadow/canary is a business deliverable or an experiment whose valid result may be “do not promote.”

Status: DONE_WITH_CONCERNS

Summary: Five high-impact complexity findings. Preserve full scope, but canonicalize ownership, reuse one investigation core, force one live integration early, decouple student promotion from hardening, and re-estimate after product decisions.

Concerns/Blockers: The delegated reviewer did not return within the bounded review window; the controller completed this fourth required lens directly from the plan evidence.
