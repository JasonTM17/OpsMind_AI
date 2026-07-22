# Code Review Summary: Assumption-Destroyer Plan Review

## Scope

- Target: `plans/260719-1747-opsmind-ai-production-platform/plan.md`, all 16 phase files, the master traceability matrix, both research reports, and the accepted brainstorm report.
- Material inspected: 21 Markdown files, 3,874 lines.
- Focus: feasibility, sequencing, external dependencies, production definition, user value, and contradictions between the master plan and executable phase instructions.
- Method: direct scout of dependencies, file ownership, contract roots, trust boundaries, external-provider gates, quantitative exit criteria, and unresolved decisions. No implementation, build, lint, or plan edits performed.

## Overall Assessment

**BLOCK. Do not begin Phase 2 implementation from this plan.** The plan has sound safety principles, but its executable sequence assumes away decisions that define the product: deployment model, IdP, allowed AI egress, target integrations, evidence storage, operating envelope, and staffed budget. Several phase documents also disagree on runtime language, canonical contract locations, and dependency metadata. A team can satisfy the written fixture gates and still end with a staging-only demo that cannot legally process production data, authenticate enterprise users, replay evidence, or support a real on-call workflow.

Findings: **8 accepted** (3 Critical, 5 High).

## Critical Issues

### 1. Critical — “Production” is deliberately undefined until the final phase

**Evidence**

- The plan fixes the topology and commits to full A-to-Z delivery before resolving whether the product is internal, managed SaaS, or customer-hosted: `plans/260719-1747-opsmind-ai-production-platform/plan.md:23`, `plans/260719-1747-opsmind-ai-production-platform/plan.md:205-210`.
- Phase 1 only requires privacy/residency to be recorded as questions, not decided: `plans/260719-1747-opsmind-ai-production-platform/phase-01-operating-envelope-and-architecture-governance.md:53`.
- The actual deployment target, IdP, secret mechanism, backup store, regions, and operational owners are postponed to the Phase 16 entry gate; without them the result explicitly stops at staging: `plans/260719-1747-opsmind-ai-production-platform/phase-16-delivery-disaster-recovery-and-final-verification.md:19`, `plans/260719-1747-opsmind-ai-production-platform/phase-16-delivery-disaster-recovery-and-final-verification.md:28`, `plans/260719-1747-opsmind-ai-production-platform/phase-16-delivery-disaster-recovery-and-final-verification.md:163`, `plans/260719-1747-opsmind-ai-production-platform/phase-16-delivery-disaster-recovery-and-final-verification.md:186-188`.
- Research also leaves cloud/region and production SLO/RTO/RPO open: `plans/260719-1747-opsmind-ai-production-platform/research/researcher-02-delivery-evaluation.md:341-346`.

**Destroyed assumption**

Portable adapters do not make SaaS, internal, and customer-hosted deployments equivalent. Those models change tenancy, identity claims, egress, data deletion, KMS/secrets, upgrade strategy, support boundary, audit ownership, HA, and cost allocation.

**Second-order effect**

Phases 2-15 can pass locally while locking schemas and service boundaries that are invalid for the eventual target. The late decision then forces identity/data migrations, connector redesign, compliance review, new operational ownership, and a schedule reset. The headline “production platform” would be false; Phase 16 itself permits only a staging release.

**Remediation**

Add a blocking `G0.5 Product and Production Contract` before Phase 2. Require signed decisions for: initial deployment archetype; target cloud/on-prem and region; tenant/isolation model; data classes and external egress; first integrations; expected tenant/concurrency/load envelope; SLO/RTO/RPO; retention/deletion; launch/on-call/risk owners. Create explicit variant deltas for customer-hosted versus managed delivery. Re-estimate only after this gate.

### 2. Critical — The trust foundation is designed before an IdP/federation contract exists

**Evidence**

- Phase 3 assumes IdP-issued bearer tokens while also defining OpsMind-owned `login`, `refresh`, and `logout` endpoints: `plans/260719-1747-opsmind-ai-production-platform/phase-03-contracts-data-identity-and-tenant-foundation.md:57-61`, `plans/260719-1747-opsmind-ai-production-platform/phase-03-contracts-data-identity-and-tenant-foundation.md:80-84`.
- The same phase admits IdP requirements are unresolved and proposes only a verifier port as mitigation: `plans/260719-1747-opsmind-ai-production-platform/phase-03-contracts-data-identity-and-tenant-foundation.md:121`.
- The authoritative requirement demands a real OIDC integration plus refresh/revocation behavior, not merely a mocked token-verifier contract: `plans/260719-1747-opsmind-ai-production-platform/research/master-prompt-requirements-traceability.md:33-36`.
- Release-1 IdP and federation remain an open master-plan question: `plans/260719-1747-opsmind-ai-production-platform/plan.md:206`.

**Destroyed assumption**

An `IdTokenVerifierPort` does not abstract issuer/audience rules, authorization-code/PKCE or BFF flow, session ownership, refresh-token handling, group-to-role mapping, organization provisioning, deprovisioning, service identities, MFA/step-up, logout, or revocation behavior. The proposed auth endpoints already choose a session architecture without admitting it.

**Second-order effect**

Tenant IDs and roles can become derived from the wrong claims, refresh tokens may cross the application boundary unnecessarily, and approval attestations may lack the enterprise identity evidence later required in Phase 11. Retrofitting federation after incidents, audit, RLS, and approvals depend on the identity model is a breaking trust-boundary migration.

**Remediation**

Make a selected IdP or a precise supported OIDC profile a Phase 3 entry gate. Specify authorization flow, session boundary, issuer/audience/claim mapping, tenant provisioning, group/role mapping, service-account identity, revocation/deprovisioning, MFA/step-up for risky approvals, and logout behavior. Replace generic-only tests with one real non-production IdP integration suite. Remove `/auth/login|refresh|logout` endpoints unless the chosen BFF/session design actually requires them.

### 3. Critical — Core product value is hard-bound to DeepSeek before data egress is permitted or capability is benchmarked

**Evidence**

- Phase 5 hard-wires the first runtime to DeepSeek and accepts only DeepSeek model identifiers: `plans/260719-1747-opsmind-ai-production-platform/phase-05-deepseek-ai-runtime-and-model-gateway.md:14`, `plans/260719-1747-opsmind-ai-production-platform/phase-05-deepseek-ai-runtime-and-model-gateway.md:65`, `plans/260719-1747-opsmind-ai-production-platform/phase-05-deepseek-ai-runtime-and-model-gateway.md:123-130`.
- Its exit gate requires a real DeepSeek smoke when staging credentials exist, while the same file leaves permission to send telemetry/source content unresolved: `plans/260719-1747-opsmind-ai-production-platform/phase-05-deepseek-ai-runtime-and-model-gateway.md:184`, `plans/260719-1747-opsmind-ai-production-platform/phase-05-deepseek-ai-runtime-and-model-gateway.md:196-200`.
- Provider privacy/residency is an authoritative missing completion gate and remains an open research question: `plans/260719-1747-opsmind-ai-production-platform/research/master-prompt-requirements-traceability.md:145`, `plans/260719-1747-opsmind-ai-production-platform/research/master-prompt-requirements-traceability.md:169`.
- Architecture research explicitly did not benchmark DeepSeek in this environment: `plans/260719-1747-opsmind-ai-production-platform/research/researcher-01-architecture-security.md:170-174`.

**Destroyed assumption**

A provider adapter solves API churn; it does not solve legal egress, residency, retention, incident-data sensitivity, regional availability, latency, quota, or model-quality suitability. A single redacted smoke does not prove multi-turn tool calling, evidence grounding, long-context behavior, failure recovery, or the stated p95/cost envelope.

**Second-order effect**

If production content cannot leave the organization, Phases 5-14 lose their teacher, investigation, evaluation, dataset-generation, and student-baseline path. Discovering that after platform construction is an architectural restart, not a configuration change. Even when egress is allowed, an unbenchmarked provider can make the 120-second workflow and quality gates unattainable.

**Remediation**

Add a pre-Phase-5 provider/data decision: allowed data classes, redaction boundary, DPA/retention/residency, region availability, and failure fallback. Keep all live calls synthetic until accepted. Define a provider-neutral conformance benchmark covering multi-turn tools, structured output/empty responses, evidence grounding, context limits, latency, cost, rate limiting, and outages. If external incident data is prohibited, select and prove a compliant hosted/on-prem model path before G3; do not defer that fallback to the future student model.

## High Priority

### 4. High — The “locked” runtime and canonical contracts fork across phase documents

**Evidence**

- The master plan locks an isolated **Spring** Tool Gateway, and Phase 2 bootstraps it with Maven: `plans/260719-1747-opsmind-ai-production-platform/plan.md:23`, `plans/260719-1747-opsmind-ai-production-platform/plan.md:31`, `plans/260719-1747-opsmind-ai-production-platform/phase-02-monorepo-and-developer-platform-foundation.md:73-75`.
- Phase 6 silently replaces that service with Python/FastAPI files and `pyproject.toml`: `plans/260719-1747-opsmind-ai-production-platform/phase-06-safe-tool-gateway-and-read-only-connectors.md:89-107`.
- Phase 3 declares `packages/api-contracts/**` and `packages/shared-schemas/**` the only canonical contract sources: `plans/260719-1747-opsmind-ai-production-platform/phase-03-contracts-data-identity-and-tenant-foundation.md:67-69`, `plans/260719-1747-opsmind-ai-production-platform/phase-03-contracts-data-identity-and-tenant-foundation.md:89-90`.
- Phases 5-7 instead create three new top-level contract trees, then Phases 9-12 assume a fourth root and an OpenAPI file no earlier phase creates: `plans/260719-1747-opsmind-ai-production-platform/phase-05-deepseek-ai-runtime-and-model-gateway.md:101-103`, `plans/260719-1747-opsmind-ai-production-platform/phase-06-safe-tool-gateway-and-read-only-connectors.md:113-115`, `plans/260719-1747-opsmind-ai-production-platform/phase-07-thin-evidence-backed-incident-vertical-slice.md:88-89`, `plans/260719-1747-opsmind-ai-production-platform/phase-09-durable-investigation-workflow.md:70`, `plans/260719-1747-opsmind-ai-production-platform/phase-09-durable-investigation-workflow.md:77-79`.
- Machine-readable dependencies also disagree with prose: Phase 7 declares only `[5, 6]` but calls Phase 4 a hard blocker: `plans/260719-1747-opsmind-ai-production-platform/phase-07-thin-evidence-backed-incident-vertical-slice.md:6`, `plans/260719-1747-opsmind-ai-production-platform/phase-07-thin-evidence-backed-incident-vertical-slice.md:36-39`.

**Destroyed assumption**

The greenfield sequence is not executable merely because every phase has a file inventory. Different implementers following these documents will create incompatible services and parallel sources of truth.

**Second-order effect**

CI and generated clients fork, Java/Python ownership and security tooling change, migrations and compose wiring drift, and Phase 9 cannot perform its promised additive modification. Parallel work begins against missing incident/audit dependencies and produces rework or contract shims that permanently weaken type safety.

**Remediation**

Before implementation, issue one topology/ownership ADR that chooses the Tool Gateway runtime, one contract root/OpenAPI composition strategy, one migration naming/location convention, and one source owner per shared artifact. Rewrite every phase inventory and dependency frontmatter to that decision; mechanically validate the graph against each phase's “hard blockers.”

### 5. High — G3 “product proof” can pass without any live connector or target-system identity

**Evidence**

- Phase 6 says it acquires live evidence, but makes live smoke optional and requires only three connector families in fixture mode for exit: `plans/260719-1747-opsmind-ai-production-platform/phase-06-safe-tool-gateway-and-read-only-connectors.md:20`, `plans/260719-1747-opsmind-ai-production-platform/phase-06-safe-tool-gateway-and-read-only-connectors.md:53-55`, `plans/260719-1747-opsmind-ai-production-platform/phase-06-safe-tool-gateway-and-read-only-connectors.md:186`, `plans/260719-1747-opsmind-ai-production-platform/phase-06-safe-tool-gateway-and-read-only-connectors.md:188-195`.
- The real observability/deployment/Kubernetes/Kafka systems, DB read model, and gateway signer are all unresolved: `plans/260719-1747-opsmind-ai-production-platform/phase-06-safe-tool-gateway-and-read-only-connectors.md:207-211`.
- Phase 7 claims a real incident path, but its integration path is a seeded local fixture and its exit proves only an internal test tenant: `plans/260719-1747-opsmind-ai-production-platform/phase-07-thin-evidence-backed-incident-vertical-slice.md:14`, `plans/260719-1747-opsmind-ai-production-platform/phase-07-thin-evidence-backed-incident-vertical-slice.md:130-138`, `plans/260719-1747-opsmind-ai-production-platform/phase-07-thin-evidence-backed-incident-vertical-slice.md:166-173`.
- The master plan nevertheless labels Phases 5-7 “Read-only product proof”: `plans/260719-1747-opsmind-ai-production-platform/plan.md:120`.

**Destroyed assumption**

Fixture contract compatibility is not evidence that a connector works. Real systems differ in credential delegation, tenancy/project mapping, pagination, time semantics, rate limits, query cost, redaction, schema drift, and network policy.

**Second-order effect**

The first genuine integration occurs after contracts, evidence envelopes, evaluation scenarios, and UI have been declared stable. Its auth or data shape can invalidate all four. Phase 11 also names deployment/feature-flag/GitHub writes without first proving the corresponding provider-specific read and sandbox identities.

**Remediation**

Select one named first-wave observability/deployment/source stack before Phase 6. Make G3 require one end-to-end call against a live **non-production** target using real workload identity, allowlists, pagination, redaction, outage handling, and synthetic incident data. Retain fixtures for determinism. Gate every future write executor on a provider-specific sandbox rehearsal of dry-run, verify, compensation, and scoped credentials.

### 6. High — Incident evidence has references but no owned durable artifact store until a dataset-only profile in Phase 13

**Evidence**

- The master plan names object storage as an external runtime dependency whose owning phase must land, but assigns no owner: `plans/260719-1747-opsmind-ai-production-platform/plan.md:59`.
- The accepted architecture requires large logs, traces, documents, and source snapshots in object artifacts: `plans/260719-1747-opsmind-ai-production-platform/reports/brainstorm-report.md:113-120`.
- Phase 4 stores only external references/checksums and explicitly defers raw evidence storage: `plans/260719-1747-opsmind-ai-production-platform/phase-04-incident-control-plane-and-audit-ledger.md:56-60`, `plans/260719-1747-opsmind-ai-production-platform/phase-04-incident-control-plane-and-audit-ledger.md:118-120`.
- Phases 7-10 rely on replayable evidence/artifact references, yet the first concrete S3-compatible store is Phase 13's optional **dataset** profile: `plans/260719-1747-opsmind-ai-production-platform/phase-08-simulator-and-evaluation-baseline.md:178`, `plans/260719-1747-opsmind-ai-production-platform/phase-10-permission-aware-rag-and-knowledge-lifecycle.md:59-65`, `plans/260719-1747-opsmind-ai-production-platform/phase-13-dataset-flywheel-and-governance.md:37`, `plans/260719-1747-opsmind-ai-production-platform/phase-13-dataset-flywheel-and-governance.md:54`.
- Phases 15-16 assume an artifact store already exists and can fail or be backed up: `plans/260719-1747-opsmind-ai-production-platform/phase-15-security-reliability-and-observability-hardening.md:68-70`, `plans/260719-1747-opsmind-ai-production-platform/phase-15-security-reliability-and-observability-hardening.md:105-108`, `plans/260719-1747-opsmind-ai-production-platform/phase-16-delivery-disaster-recovery-and-final-verification.md:104-109`.

**Destroyed assumption**

An artifact ID/checksum is not durable evidence unless bytes, access policy, immutability/version, retention, and restore are owned. Source URLs and local `D:` paths are neither stable nor tenant-authorized production storage.

**Second-order effect**

RCA citations can become unreplayable, retention/deletion cannot propagate, cross-tenant signed-URL access is undefined, and DR cannot reconstruct incident history. Pressure to “make it work” will either put raw blobs in PostgreSQL or leave sensitive evidence on unmanaged local/source storage.

**Remediation**

Add an incident-evidence artifact-store capability before Phase 4/6: provider-neutral storage port, encrypted object backend, content addressing/versioning, tenant/project authorization, scoped URLs, KMS/secret ownership, retention/deletion, malware/DLP boundary, orphan collection, and restore tests. Give it a local MinIO/S3-compatible profile and a production backend selected by G0.5. Dataset artifacts may reuse the capability later but must not be its first owner.

### 7. High — Simulator gates and product metrics have no statistical or human-validity basis

**Evidence**

- The north-star metric is a 40% reduction in median time-to-verified-root-cause against a human baseline, but no phase owns collection of that baseline: `plans/260719-1747-opsmind-ai-production-platform/reports/brainstorm-report.md:35-46`, `plans/260719-1747-opsmind-ai-production-platform/reports/brainstorm-report.md:195-206`.
- Phase 8 starts with only three synthetic, author-defined scenario classes and still gates on `>=99%` structured-output validity: `plans/260719-1747-opsmind-ai-production-platform/phase-08-simulator-and-evaluation-baseline.md:66-79`, `plans/260719-1747-opsmind-ai-production-platform/phase-08-simulator-and-evaluation-baseline.md:173-181`.
- Final release expands to only ten deterministic scenarios while claiming p95 latency, RCA accuracy, citation precision, hallucination, and calibration results: `plans/260719-1747-opsmind-ai-production-platform/phase-16-delivery-disaster-recovery-and-final-verification.md:111-115`, `plans/260719-1747-opsmind-ai-production-platform/phase-16-delivery-disaster-recovery-and-final-verification.md:152-160`.
- Phase 12's operator evidence is ten automated browser runs and responsive/accessibility checks, not on-call or incident-commander task validation: `plans/260719-1747-opsmind-ai-production-platform/phase-12-operator-web-experience-completion.md:160-166`. The only named operator walkthrough appears as a generic final DoD artifact: `plans/260719-1747-opsmind-ai-production-platform/plan.md:172`.
- Expected concurrency and latency SLO are still unresolved: `plans/260719-1747-opsmind-ai-production-platform/plan.md:210`.

**Destroyed assumption**

Three or ten deterministic fixtures cannot support 99%/99.5% rates, p95 latency, calibration, class-level RCA accuracy, or non-inferiority claims. Repeated runs of the same authored fixtures are correlated observations. Browser automation proves mechanics, not whether an on-call can reach a verified decision faster under pressure.

**Second-order effect**

The system will optimize for its own simulator, pass numerically impressive but meaningless gates, and miss real incident ambiguity, missing telemetry, organizational handoffs, and approval fatigue. The north-star improvement cannot be computed, and UI breadth can increase cognitive load while all tests remain green.

**Remediation**

Before Phase 7, define “verified RCA,” timestamps, comparator workflow, incident taxonomy, severity slices, adjudication, and target confidence/power. Build a held-out corpus using independently reviewed synthetic cases plus permitted de-identified historical/shadow incidents; repeat provider trials and report confidence intervals, not bare percentages. Add on-call/incident-commander observation at G3 and G6, including time-on-task, correction/override rate, abstention usefulness, and approval comprehension. Keep three-scenario smoke tests, but do not call their metrics release evidence.

### 8. High — The effort estimate hides the staffed cost and makes an optional student-model experiment a production blocker

**Evidence**

- The master estimate is `38-56 engineer-weeks`: `plans/260719-1747-opsmind-ai-production-platform/plan.md:6`.
- Research's own seven coarse phase ranges imply **41-130 engineer-weeks** when weeks are multiplied by the stated engineer ranges: `plans/260719-1747-opsmind-ai-production-platform/research/researcher-02-delivery-evaluation.md:66-84`, `plans/260719-1747-opsmind-ai-production-platform/research/researcher-02-delivery-evaluation.md:86-104`, `plans/260719-1747-opsmind-ai-production-platform/research/researcher-02-delivery-evaluation.md:106-134`.
- The same research assumes eight core roles plus shared QA/security and 5-7 months, and says teams below four core engineers must defer training and broad remediation: `plans/260719-1747-opsmind-ai-production-platform/research/researcher-02-delivery-evaluation.md:269-292`.
- Phase 14 cannot start without a compute target, budget, license, and retention policy; all remain unresolved: `plans/260719-1747-opsmind-ai-production-platform/phase-14-student-model-training-shadow-and-promotion.md:24-30`, `plans/260719-1747-opsmind-ai-production-platform/phase-14-student-model-training-shadow-and-promotion.md:149-153`.
- Its own exit says production promotion remains pending, yet Phase 15 depends on Phase 14 and the master G6 includes the student pipeline: `plans/260719-1747-opsmind-ai-production-platform/phase-14-student-model-training-shadow-and-promotion.md:123-129`, `plans/260719-1747-opsmind-ai-production-platform/phase-15-security-reliability-and-observability-hardening.md:5-7`, `plans/260719-1747-opsmind-ai-production-platform/plan.md:123`.
- Research says a student should proceed only when materially cheaper/faster with no critical regression: `plans/260719-1747-opsmind-ai-production-platform/research/researcher-02-delivery-evaluation.md:243-267`.

**Destroyed assumption**

Calendar weeks, phase effort, and engineer-weeks are being mixed. The current estimate excludes or compresses product, security, SRE, reviewer, legal/vendor, and integration effort. Full student-model lifecycle has no demonstrated traffic volume, savings, suitable data volume, base-model license, GPU/registry, or payback case.

**Second-order effect**

The delivery promise becomes structurally impossible, so security/evaluation work gets cut to protect dates. Production hardening is held behind a 3-6 week, two-AI-engineer experiment that may correctly conclude “do not promote,” while scarce GPU/residency decisions and review queues extend the critical path.

**Remediation**

Re-estimate from a staffed WBS with critical path, parallel capacity, review/security/ops effort, external procurement/legal lead time, and contingency. Publish schedules for the actual team size. Preserve the required bounded TRAIN-01 smoke, but make full Phase 14 shadow/canary a parallel optional track with an entry decision based on observed teacher volume/cost/latency, dataset sufficiency, approved license, compute/registry/residency, and quantified payback. Do not block core production hardening on student promotion evidence unless that business gate passes.

## Edge Cases Found by Scout

- A planner using only frontmatter can start Phase 7 before Phase 4 even though incident/audit state is a prose hard blocker.
- A Phase 6 implementer following its inventory will overwrite the Phase 2 Spring/Maven gateway assumption with Python.
- A Phase 9 implementer has no canonical `packages/contracts/openapi/opsmind-v1.yaml` created by earlier phases.
- Fixture-only connectors can produce a fully green G3 while all target-system credentials and schemas remain unknown.
- Evidence references can outlive the bytes they cite because no early artifact lifecycle owner exists.
- Production can legitimately stop at staging under Phase 16 while the parent plan still presents a production A-to-Z estimate.

## Recommended Actions

1. Block Phase 2 and decide G0.5 production/deployment, IdP, provider-egress, first integrations, artifact store, operating envelope, and owners.
2. Reconcile runtime language, contract roots, migrations, file ownership, and machine-readable dependency metadata across all phase files.
3. Replace fixture-only G3 with one real non-production connector/product slice and establish the human/on-call baseline before measuring improvement.
4. Redesign evaluation sample strategy and thresholds; separate smoke determinism from release-quality evidence.
5. Re-estimate with actual staffing and external lead times; decouple full student-model work from the production critical path unless its ROI/compute gate passes.

## Metrics

- Type coverage: not applicable; planning-only repository.
- Test coverage: not applicable; no runtime implementation reviewed.
- Lint/build issues: not run by assignment.
- Review findings: 3 Critical, 5 High, 0 Medium.

## Unresolved Questions

- Who has authority to decide the initial deployment archetype and production data-egress policy?
- Which IdP and first-wave observability/deployment/source systems can be used in non-production integration tests?
- Is external DeepSeek processing contractually permitted for incident telemetry, source snippets, and runbooks?
- What is the actual team size, budget, deadline, and access to cloud/object/GPU resources?
- Is the required student deliverable only a bounded training smoke, or is shadow/canary itself a release obligation?

Status: DONE

Summary: Eight evidence-backed blockers found. Plan should remain pending; implementation should not start until production/identity/provider decisions and internal topology contradictions are reconciled.

Concerns/Blockers: No repository `README.md` or `docs/code-standards.md` existed to supplement the plan. This did not block the review because all assigned plan/research files were available.
