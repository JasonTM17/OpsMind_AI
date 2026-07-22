---
phase: 16
title: "Delivery, Disaster Recovery, and Final Verification"
status: pending
effort: "3-5 weeks; platform/SRE plus representatives from every owning team"
dependencies: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15]
requirements: [DR-01, DEV-01, DEV-02, DISK-01, K8S-01, K8S-02, CI-01, CI-02, GIT-01, DOC-01, ADD-01, ADD-02, ADD-03, ADD-04, ADD-07, ADD-08]
---

# Phase 16: Delivery, Disaster Recovery, and Final Verification

## Objective

Turn the accepted platform into reproducible release artifacts, deploy it to staging, prove rollback and data recovery, and close every master-prompt Definition of Done with inspectable evidence. “Implemented” is not completion; the release candidate must be buildable from a clean clone and survive the documented operational drills.

## Non-goals

- No multi-region active-active, multi-cloud abstraction or Kafka migration without a measured graduation need.
- No production launch before deployment model, data residency, SLO, RTO/RPO and operational ownership are approved.
- No plaintext secrets in Compose, CI, Helm values, manifests, docs, test fixtures or release artifacts.
- No destructive cleanup of C: or D: as part of automation; disk guards report/block only.

## Prerequisites and entry gate

- Every prior phase exit gate has an evidence link, owner and accepted exceptions with expiry.
- Phase 15 has no unresolved Critical security finding and has a declared capacity/SLO envelope.
- Ten simulator scenarios and the complete alert-to-postmortem path pass on the release candidate.
- Deployment target, identity provider, external secret mechanism, backup store, regions and operational owners are decided; otherwise stop at deployable staging release.

## Design decisions and patterns

1. **Build once, promote by digest:** staging and production consume the same signed immutable images/artifacts.
2. **GitOps-compatible declarative delivery:** configuration is versioned; secrets are external references; emergency drift is detected and reconciled.
3. **Expand/contract database delivery:** forward-compatible migrations precede app rollout; destructive contraction waits until old versions are gone and recovery is proven.
4. **Environment protection:** production promotion requires named approvers, required checks and serialized deployments.
5. **Recovery hierarchy:** disable/contain, application rollback, forward-fix schema, point-in-time restore only when required; each path has decision criteria.
6. **Evidence ledger:** CI emits machine-readable requirement/DoD results linked to immutable run, commit and artifact digests.
7. **Portable developer commands:** PowerShell and POSIX entrypoints share command semantics; containers are optional for narrow tests and required only for integrated profiles.
8. **Dual registry publication:** every release image is published by immutable
   digest to Docker Hub and GHCR; the GHCR publication is linked to the GitHub
   repository so the repository exposes the corresponding Package. Tags are
   pointers only and never replace digest verification.

## Planned file inventory

| Operation | Path | Purpose |
|---|---|---|
| CREATE | `Dockerfile` files beside each runtime | Multi-stage, non-root, minimal runtime images |
| MODIFY | `compose.yaml` | Complete core topology and optional profiles with health-dependent startup |
| CREATE | `infra/compose/profiles/` | Optional observability, simulator, dataset and evaluation profiles |
| CREATE | `infra/helm/opsmind/` | Parameterized chart, probes, limits, PDB, policies and service accounts |
| CREATE | `infra/kubernetes/policies/` | Network, pod-security and secret-reference policy checks |
| MODIFY | `.github/workflows/pr-quality.yml` | Complete language, contract, migration, security and policy checks |
| CREATE | `.github/workflows/main-build.yml` | Reproducible images, SBOM, provenance and evidence artifacts |
| CREATE | `.github/workflows/release.yml` | Protected staged deploy, smoke, promotion and rollback |
| CREATE | `.github/workflows/publish-packages.yml` | Multi-arch Docker Hub/GHCR publication, signing, SBOM, provenance and digest-parity proof |
| CREATE | `.github/repository-metadata.yml` | About-panel source of truth: description, homepage, topics, repository settings, and package inventory |
| CREATE | `.github/ISSUE_TEMPLATE/**`, `.github/pull_request_template.md` | Safe issue intake, support/security routing, and evidence-oriented review checklist |
| CREATE | `CONTRIBUTING.md`, `SECURITY.md`, `SUPPORT.md` | Public contribution, vulnerability-reporting, and support contract |
| CREATE | `.github/workflows/restore-drill.yml` | Scheduled/manual isolated restore verification |
| MODIFY | `scripts/dev/opsmind.ps1` | Complete Windows setup/dev/test/build/up/down/migrate/seed/evaluate/security CLI |
| MODIFY | `scripts/dev/opsmind.sh` | Complete Linux/macOS equivalent |
| MODIFY | `scripts/storage/check-capacity.ps1` | Final read-only C:/D: threshold gate |
| MODIFY | `scripts/storage/check-capacity.sh` | Final portable workspace-volume threshold gate |
| CREATE | `scripts/release/verify-release.*` | Master verification orchestrator and evidence index |
| CREATE | `scripts/operations/backup.*` | Scoped backup command with checksum/encryption evidence |
| CREATE | `scripts/operations/restore-drill.*` | Isolated restore, integrity and application smoke drill |
| CREATE | `docs/deployment-guide.md` | Staging/production prerequisites, deploy, verify and rollback |
| CREATE | `docs/disaster-recovery.md` | Data classes, RTO/RPO, backup, restore and decision tree |
| MODIFY | `docs/testing-strategy.md` | Final test pyramid, environments, fixtures and release gates |
| CREATE | `docs/evaluation-guide.md` | Benchmark reproduction and metric interpretation |
| CREATE | `docs/demo-script.md` | Timed alert-to-postmortem demonstration |
| CREATE | `docs/runbooks/` | Dependency, safety, cost, capacity, restore and rollback runbooks |
| CREATE | `docs/project-changelog.md` | Release changes and known limitations |
| CREATE | `release/evidence-schema.json` | Machine-readable DoD/requirement result format |
| MODIFY | `README.md` | Product scope, architecture diagram, prerequisites and verified quick start |
| MODIFY | `docs/project-overview-pdr.md` | Final requirements, constraints and accepted scope |
| MODIFY | `docs/code-standards.md` | Actual commands, contracts and delivery conventions |
| MODIFY | `docs/system-architecture.md` | Deployed topology, trust/failure boundaries and ADR links |
| MODIFY | `docs/project-roadmap.md` | Release status, deferrals and graduation triggers |

## Implementation tasks

### 16.1 Produce secure reproducible artifacts

- Create multi-stage builds with locked dependencies, deterministic metadata, non-root users, read-only defaults, explicit health behavior and no build secrets in layers.
- Generate SBOM and provenance for each image/artifact, sign release digests, and verify signatures at promotion.
- Publish the same multi-architecture OCI manifest digest to Docker Hub and
  `ghcr.io`; link GHCR packages to this GitHub repository and verify package
  visibility/metadata through the registry API after publication.
- Obtain Docker Hub and GitHub registry credentials only from protected
  environment/OIDC-backed secret storage. Never place tokens in source,
  workflow defaults, build arguments, image layers, logs, or evidence.
- Label compatible API/schema/migration versions and retain a last-known-good release set.
- Scan final images and manifests; prove no repository secret or provided DeepSeek key appears in working tree, Git history or artifacts.

### 16.2 Complete local developer experience

- Expose the same verbs on Windows and POSIX: `setup`, `dev`, `test`, `lint`, `build`, `up`, `down`, `migrate`, `seed`, `evaluate`, `security`, `verify-release`.
- Make commands fail with actionable messages for missing prerequisites/config; optional features remain profiles.
- Run disk preflight before Docker build/pull, dependency/model download, dataset materialization, evaluation bundle and training. Default floors: block heavy work when C: is below 10 GB on this Windows host or target workspace volume lacks task headroom.
- The capacity check is read-only and reports exact consumer categories; it never deletes, moves, prunes or cleans automatically.

### 16.3 Build Compose and Kubernetes delivery

- Compose core profile starts PostgreSQL, required runtime services and web with healthchecks; optional Redis/Temporal/observability/simulator profiles are explicit.
- Helm resources include startup/readiness/liveness probes, requests/limits, PDB, topology spread where justified, service accounts, NetworkPolicy and external secret references.
- Validate manifests with schema/policy tools and deploy to staging namespace using least-privilege workload identities.
- Keep Kafka absent until a recorded throughput/coupling gate justifies it; transactional outbox remains the contract.

### 16.4 Implement CI, promotion and rollback

- PR jobs run format/lint/typecheck/unit/integration/contract/migration/document-link checks plus secret, SAST, dependency/license, IaC and policy scans.
- Main builds once, runs integration/E2E/evaluation, publishes signed images/SBOM/provenance and stores an evidence bundle.
- The protected publish job promotes the already-tested digest to Docker Hub
  and GHCR, proves cross-registry manifest parity for every architecture, then
  records repository-package URLs and immutable digests in the release ledger.
- Release workflow uses protected environments, concurrency lock, immutable digests, expand migrations, staged rollout, smoke/E2E checks and explicit promotion.
- On failure, stop promotion and restore the last-known-good application release; choose forward-fix versus data restore using the DR decision tree.

### 16.5 Prove backup and disaster recovery

- Classify PostgreSQL/audit/outbox/inbox, Temporal history/visibility/config, object versions, identity/config references, dataset/model aliases and ephemeral caches; assign system of record, owner, backup method, retention, RPO and RTO to each.
- Encrypt backups with keys outside the backup store, checksum them, restrict deletion, and keep a logically/physically separate copy.
- Implement a consistent-cut runbook: stop admission, fence all write executors, drain/checkpoint bounded work, record Postgres/Temporal/object/outbox/model watermarks, then capture each store.
- Restore with workflow/write workers disabled. Reconcile Temporal histories, projections, inbox/outbox, object hashes, approval intents/provider receipts and external target state before reopening admission.
- Restore to an isolated target, validate schema/checksums, then run authorization, incident timeline, audit-chain, RAG citation, one active investigation and one executed-but-unacknowledged/mid-remediation case.
- Record start/end timestamps, achieved RPO/RTO, missing data, manual steps and remediation. A backup is not accepted until this drill passes.

### 16.6 Final verification campaign

- Execute all ten deterministic simulator scenarios from alert ingestion through triage, evidence gathering, hypothesis/counter-evidence, recommendation, approval path where applicable, resolution and postmortem.
- Run the frozen independently held-out benchmark at its preregistered sample size and publish confidence intervals/raw-run references for RCA, evidence, hallucination, calibration, tool, RAG, safety, latency, token and cost metrics. Ten deterministic scenarios prove scenario functionality, not statistical rates by themselves.
- Run accessibility/responsive browser E2E for operator workflows and verify no chain-of-thought or secrets are rendered.
- Perform clean-clone builds on Windows and Linux; a clean environment may use CI-hosted runners to avoid local disk pressure.
- Audit TODO/FIXME/placeholder claims, generated API docs, internal doc links, changelog, Git history and known limitations.

### 16.7 Close the evidence ledger

- Map every requirement in `research/master-prompt-requirements-traceability.md` to immutable test/run/report URLs and an accountable owner.
- Mark a requirement passed only when its authoritative evidence was inspected; file existence or a developer assertion is insufficient.
- Record accepted deviations with severity, risk owner, expiry and a release-blocking/non-blocking decision.
- Produce a signed release-readiness summary and retain it with the release digest.

## Definition of Done closure matrix

| DoD group | Required final evidence |
|---|---|
| 1-3 local/build | Windows + Linux clean-clone transcripts and Compose health/E2E report |
| 4-5 identity/incidents | OIDC/RBAC/tenant matrix and incident API/UI E2E |
| 6-10 AI/tools | Redacted live DeepSeek opt-in smoke, missing-key behavior, schema benchmark, tool policy/audit suite |
| 11-12 approval/RAG | Expiry/replay/TOCTOU/concurrency tests and citation/ACL benchmark |
| 13 simulator | Ten scenario deterministic run bundle |
| 14-16 data/model/eval | Dataset card/artifact, bounded training/model card, versioned benchmark |
| 17-19 product/E2E | Accessibility/browser report and complete alert-to-postmortem trace |
| 20-24 security/platform/CI | History secret scan, Critical triage, non-root inspection, K8s policy validation, required checks |
| 25-32 documentation | Rendered README diagram, OpenAPI, deployment/security/test/eval guides, cards, demo, changelog |
| 33-35 integrity | Git-history audit, placeholder/known-limits scan, requirement-by-requirement evidence audit |

## Verification and evidence matrix

| Check | Method | Passing evidence |
|---|---|---|
| Clean build | Fresh Windows and Linux checkout | Documented commands pass without undeclared local state |
| Compose | Core and selected optional profiles | Healthy startup, shutdown and representative E2E |
| Kubernetes | Render/schema/policy plus staging deploy | Probes, limits, PDB, NetworkPolicy, service accounts and no plaintext secret |
| Release | Build/sign/promote same digest | Staging and candidate digest match; protected approval recorded |
| Registry/package | Docker Hub + GHCR inspection | Multi-arch digest parity, signatures/SBOM/provenance verify, and GitHub repository Package exists |
| Rollback | Deliberately fail staging smoke | Last-known-good restored within objective without data corruption |
| Restore | Consistent-cut isolated restore with active investigation and mid-remediation uncertainty | Reconciled Temporal/Postgres/artifacts/external state; no duplicate effect; measured RPO/RTO |
| Security | Final full-history/artifact/scanner suite | Zero exposed secret and zero unaccepted Critical finding |
| Product | Ten scenarios and browser/accessibility suite | Complete evidence-grounded workflows with permission-aware UI |
| AI/evaluation | Frozen benchmark and opt-in live smoke | Thresholds pass with raw run, version, token, latency and cost references |
| Traceability | Automated evidence-ledger validation plus human audit | Every required ID and DoD 1-35 has valid inspected evidence |

## Exit gate

- Same signed artifact digest passes staging and is the only candidate eligible for production promotion.
- Docker Hub and GHCR expose the same signed multi-arch release digest, and the
  GitHub repository shows its linked Package; missing credentials, digest
  mismatch, unsigned image, failed scan, or missing package metadata blocks
  completion.
- Clean-clone, Compose, Kubernetes staging, rollback and isolated restore drills pass with timestamped evidence.
- All ten scenarios and final evaluation gates pass; unsafe writes, cross-tenant leaks and unsupported RCA claims are zero in blocking suites.
- Secret/history/artifact scans find no credential; all Critical findings are fixed or release remains blocked.
- DoD 1-35 and every traceability ID have inspected evidence. No completion claim relies on a placeholder or unimplemented path.
- Named owners accept SLO, RTO/RPO, privacy/residency, on-call and launch decision. Without these approvals, deliver staging release and explicitly do not claim production launch.
- Recovery resumes admission only after watermarks and ambiguous external executions reconcile; Temporal or object-store omission fails the gate.

## Rollback and recovery

- Application: promote the last-known-good signed digest and verify health plus representative incident flow.
- Database: prefer forward-compatible fix; use point-in-time restore only under the DR decision criteria and reconcile external side effects.
- Model/provider: route to pinned champion or disable model calls while preserving manual/read-only incident operations.
- Tools/remediation: globally disable write action classes; already approved digests do not bypass the switch.
- Release evidence: never overwrite failed evidence; issue a new candidate/run after repair.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Environment choice remains unresolved | Produce portable artifacts; stop at staging rather than invent production assumptions |
| Migration blocks rollback | Expand/contract, compatibility window and rehearsal |
| Backup succeeds but restore fails | Isolated application-level restore drill is a hard gate |
| CI evidence is green but incomplete | Authoritative traceability ledger plus manual inspection |
| Local disk exhaustion | Read-only 30-minute monitor, heavy-task preflight and remote CI/compute |
| Documentation drifts | Execute documented commands and validate links/claims in release workflow |

## Unresolved decisions

- Managed SaaS, internal platform or customer-hosted release model; target cloud/region and residency.
- Final production SLO, per-data-class RTO/RPO, retention and backup cadence.
- On-call team, incident escalation, risk-acceptance and production-promotion authorities.
- Docker Hub namespace/repository names and protected publisher identity. These
  are bound at release setup; no credential or personal namespace is guessed or
  committed earlier.
