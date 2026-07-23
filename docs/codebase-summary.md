# OpsMind AI Codebase Summary

Last verified: 2026-07-23

## Purpose and Verification Basis

OpsMind AI is an evidence-first SRE/DevSecOps platform. Deterministic code owns
identity, authorization, tenant scope, incident state, audit, budgets,
approvals, and external effects; models are intended to assist within those
boundaries rather than receive direct infrastructure authority.

This summary is based on:

- a Repomix 1.14.0 XML snapshot generated at `repomix-output.xml` from 4,181
  repository files; Repomix reported no suspicious files;
- direct inspection of the root manifests, Compose file, current service code,
  contracts, Flyway migrations, validation runners, and Phase 4 evidence;
- cross-checks against [System Architecture](./system-architecture.md),
  [Security Model](./security-model.md), [Testing Strategy](./testing-strategy.md),
  [Progress](./progress.md), and [Roadmap](./project-roadmap.md).

Generated local artifacts are evidence inputs, not source-of-truth
documentation. Source code and canonical contracts take precedence.

## Delivery State

| Area | Verified state |
|---|---|
| Phase 1 | Complete; operating-envelope and governance gates passed. |
| Phase 2 | In progress; pinned polyglot workspace, launchers, Compose, and cross-platform quality-gate foundation exist. |
| Phase 3 | In progress; identity, tenant/RLS, persistence, and messaging substrate exists. Production-authorized IdP conformance remains open. |
| Phase 4 | In progress; checkpoint 4A incident write ledger is locally complete. Full Phase 4 and G2/G3 are not complete. |
| Phase 5 | In progress; provider-neutral analysis, DeepSeek adapter, egress guards, durable PostgreSQL state, V005 append-only probe audit, Platform API integration, and stream assembly exist. Static checkpoint passes; exit remains blocked by B-004 and missing rotated-key synthetic smoke. |
| Phase 6 | In progress; durable PostgreSQL and synthetic Prometheus checkpoint passes revision-bound CI. Artifact/broader-connector exit remains blocked. |
| Phase 7 | In progress; integration phases 1–4 complete. Cross-service trace, p95, CK/Stitch UI, and browser E2E exit remain blocked. |
| Later phases | Durable workflow, RAG, remediation, complete operator UX, evaluation, and production-hardening outcomes remain pending. |

Phase 7's local Operator Web checkpoint is now complete for the safe
projection boundary; cross-service trace/p95, incident-timeline linkage, and
the production BFF/session gate remain open.

Historical Phase 3/4 workstation transcripts remain local/reference evidence
and explicitly deny release status. Revision-bound GitHub Actions evidence is
tracked separately; no production IdP or production deployment result is
claimed.

## Repository Map

| Path | Current responsibility |
|---|---|
| `apps/operator-web/` | Next.js server-rendered operator investigation workspace with a server-only Platform client, versioned safe-projection parser, degraded states, and Playwright coverage. |
| `services/platform-api/` | Spring Boot control plane for OIDC identity, tenant/project access, persistence, messaging primitives, checkpoint 4A incidents, and the Phase 7 deterministic plus PostgreSQL persistence checkpoint. |
| `services/ai-runtime/` | FastAPI bounded analysis runtime with provider-neutral contracts, DeepSeek adapter, shared PostgreSQL replay/accounting, startup/periodic capability probe, `/health` liveness, and `/ready` readiness; live egress remains disabled. |
| `services/tool-gateway/` | Spring Boot fail-closed Tool Gateway: separated workload/delegated JWT trust, manifest registry, bounded DLP execution, dedicated PostgreSQL nonce/receipt/audit state, and exact read-only Prometheus query-range connector. Default profiles remain fail closed; durable/live checkpoint has revision-bound CI proof. |
| `packages/contracts/` | Canonical OpenAPI, JSON Schema, and synthetic fixtures. |
| `scripts/dev/` | Shared command dispatcher and PowerShell/portable launchers. |
| `scripts/storage/` | Capacity and storage-root preflight guards. |
| `scripts/governance/` | Governance, documentation, contract, and secret-safety checks. |
| `scripts/validation/` | Repository, Phase 3, and Phase 4 validation/evidence runners. |
| `docs/` | Evergreen architecture, security, testing, operating, and delivery documentation. |
| `plans/260719-1747-opsmind-ai-production-platform/` | Sixteen-phase executable delivery plan and supporting reports. |
| `artifacts/` | Ignored local verification/evaluation/security/DR output; not release proof by itself. |
| `.github/` plus `CONTRIBUTING.md`, `SECURITY.md`, `SUPPORT.md` | Repository About source-of-truth, safe issue/PR intake, contribution, security-reporting, and support contracts. |

## Toolchain and Runtime Foundation

| Surface | Pinned implementation |
|---|---|
| Workspace | Node 24.12.0, pnpm 11.15.0 |
| Operator Web | Next.js 16.2.10, React 19.2.7, TypeScript 6.0.3 |
| Platform API | Java 21, Maven 3.9.12, Spring Boot 4.1.0 |
| AI Runtime | Python 3.13, uv 0.11.29, FastAPI 0.139.2, Pydantic 2.13.4, Psycopg 3.3.4 |
| Tool Gateway | Java 21, Maven 3.9.12, Spring Boot 4.1.0 |
| Local database | Compose pins PostgreSQL/pgvector; the Phase 4 disposable gate used PostgreSQL 18.4. |

CI installs Maven 3.9.12 from the official Apache repository with a pinned
SHA-512 digest before every job that invokes Maven. The local PowerShell
installer is `scripts/dev/install-pinned-maven.ps1`; actionlint remains pinned
through its verified release installer. Java dependency policy uses two
CycloneDX 2.9.2 SBOMs plus a single checksum-pinned OSV 2.4.0 scan; its
fail-closed evaluator requires exact source and package coverage and blocks
known CVSS severity at 7 or greater. Jackson Databind is pinned to patched
3.1.5 in both Java services.

`compose.yaml` defines PostgreSQL, an idempotent role provisioner, optional
Redis, digest-pinned synthetic Prometheus, a disabled object-storage review
profile, separate Platform/AI/Tool Gateway migration and runtime roles, AI
Runtime, Tool Gateway, and Operator Web. Model egress, write actions, and the
external dispatcher remain disabled by default. Long-running services use
non-owner roles; Flyway runs through separate migration services.

The Phase 7 cross-service harness is under
`scripts/validation/cross-service/`: a loopback-only DeepSeek-compatible
fixture provider and a 100-warm-run Platform benchmark that emits p50/p95,
correlation IDs, evidence IDs, and bounded terminal-state proof without
persisting credentials or raw prompts. The report is intentionally ignored
until a disposable Compose/IdP execution produces it.

## Implemented Platform API Boundaries

The current controllers expose:

| Method and path | Implementation |
|---|---|
| `GET /api/v1/me` | `CurrentPrincipalController` |
| `GET /api/v1/organizations/{organizationId}/projects` | `ProjectQueryController` |
| `POST /api/v1/organizations/{organizationId}/projects/{projectId}/incidents` | `IncidentController.create` |
| `GET /api/v1/organizations/{organizationId}/projects/{projectId}/incidents/{incidentId}` | `IncidentController.detail` |
| `POST /api/v1/organizations/{organizationId}/projects/{projectId}/incidents/{incidentId}/transitions` | `IncidentController.transition` |
| `GET /api/v1/organizations/{organizationId}/projects/{projectId}/incidents/{incidentId}/timeline` | `IncidentController.timeline` |
| `POST /api/v1/organizations/{organizationId}/projects/{projectId}/incidents/{incidentId}/investigations` | `InvestigationRunController.start` (feature flagged) |
| `GET /api/v1/organizations/{organizationId}/projects/{projectId}/incidents/{incidentId}/investigations/{runId}` | `InvestigationRunController.get` (feature flagged) |

The incident controller is enabled only when persistence is enabled. Create
requires `Idempotency-Key`; transition also requires a strong numeric
`If-Match`. Mutation responses carry an ETag and `X-Operation-Id`; create also
returns `Location`. Timeline pages accept 1-100 items and an opaque,
incident-bound cursor.

Incident and investigation detail reads additionally support the typed
`application/vnd.opsmind.operator-projection.v1+json` representation. It carries
projection-class, redaction-version, and redaction-count assurances, is
`no-store`, varies on `Accept`, and is scoped by organization/project/
incident/run. Legacy JSON remains available for non-browser callers.

The canonical public contract is
`packages/contracts/openapi/opsmind-v1.yaml` (OpenAPI 3.1.1, contract version
0.4.0). Incident and audit schemas live under
`packages/contracts/json-schema/incidents/` and
`packages/contracts/json-schema/audit/`, and Tool Gateway schemas live under
`packages/contracts/json-schema/tool-gateway/v1/`. Phase 7 contracts live under
`packages/contracts/json-schema/investigation/v1/`.

## Checkpoint 4A Incident Ledger

### Authority and state

- `IncidentScopePolicy` requires `incident:read` or `incident:write` before
  database lookup.
- `JdbcIncidentAccessRepository` resolves the verified issuer/subject and the
  complete organization/project membership tuple through
  `opsmind_resolve_incident_access`.
- `IncidentRolePolicy` permits reads for `ADMIN`, `SRE`, `DEVELOPER`,
  `SECURITY_REVIEWER`, and `VIEWER`; mutations require `ADMIN` or `SRE` at both
  organization and project level.
- Invisible resources use the same safe `404`; `ProblemInstanceUri` uses a safe
  correlation URN rather than reflecting scoped identifiers.
- `IncidentStateMachine` implements `OPEN`, `INVESTIGATING`,
  `AWAITING_APPROVAL`, `MITIGATING`, `RESOLVED`, and `CLOSED`. Resolution fields
  are required only for `RESOLVED`; reopening clears the current resolution.

### Transaction and persistence

`IncidentMutationService` uses one Spring `TransactionTemplate` for authority
resolution, tenant binding, idempotency claim, incident mutation, timeline,
audit, outbox, and cached response completion. `IncidentDomainEventAppender`
appends the same authoritative timeline payload to the timeline, audit, and
outbox boundaries. A failure before commit rolls back the whole operation.

Flyway migration
`services/platform-api/src/main/resources/db/migration/V003__incident_control_plane.sql`
adds:

- `incidents` and `incident_timeline_events`, both with forced RLS;
- legal state/version and authoritative timeline-payload triggers;
- append-only timeline and audit protections;
- `opsmind_resolve_incident_access` under a narrow non-login resolver owner;
- a database-assigned, per-organization audit sequence and SHA-256 chain;
- least-privilege runtime grants and explicit dispatcher denial.

V001 owns identity, organization/project membership, idempotency, outbox,
inbox, and initial audit tables. V002 adds bounded dispatcher tenant scheduling
and workload binding. Applied migrations are additive; V001/V002 are not
rewritten by checkpoint 4A.

## Phase 7 Investigation Persistence Checkpoint

`InvestigationStateMachine` remains the pure command/event reducer and
`InvestigationOrchestrator` remains a bounded synchronous adapter. When
`opsmind.persistence.enabled=true` and `opsmind.investigation.store=postgres`,
`JdbcInvestigationRunStore` persists a tenant-scoped snapshot under optimistic
revision control. V006 adds forced-RLS `investigation_runs` and append-only
`investigation_run_events`; `InvestigationEventLedger` mirrors the authoritative
event payload into the database-owned `audit_events` chain in the same
transaction. Database triggers enforce contiguous sequence, exact event JSON,
terminal-response semantics, and event/snapshot parity even for direct SQL.

Checkpoint 4B extends this boundary with V007 `evidence_records` and the
`ai.opsmind.platform.evidence` package. Tool results carry a bounded collected
envelope through one reducer event; `EvidenceRecordWriter` stores canonical,
already-redacted JSON in the same transaction as the run event and audit append.
Platform-owned UUIDv8 identities scope evidence and execution to organization,
run, and intent. PostgreSQL independently verifies the SHA-256 digest, exact
event linkage, append-only behavior, forced RLS, and least-privilege grants.
`EvidenceRecordReader` resolves only an authorized organization/project/
incident/run set, preserves caller order, hides missing or foreign records, and
re-verifies content before returning the redacted AI-input projection. Event and
audit JSON intentionally retain metadata only.

This is persistence, not durable orchestration. The code does not resume an
in-flight runner after process loss and does not append investigation events to
`incident_timeline_events`. Only fixture implementations of the Phase 7 AI and
Tool ports are currently usable in the local Operator Web browser harness, so
the live capability-backed path and G3 remain open. The browser harness mirrors
the typed Platform projection and rejects unassured or unclassified media.

## Security and Failure Posture

- OIDC mode validates signature/issuer, audience, subject, time claims, maximum
  lifetime, clock skew, and required MFA `amr`; tenant claims are not authority.
- Persistence-enabled API requests recheck platform-user status. Unknown or
  deprovisioned users deny; authority-store failure fails closed.
- Application authorization and forced PostgreSQL RLS are separate controls.
- The web database role cannot lease or acknowledge outbox rows. The dispatcher
  role cannot access incident tables and sees no tenant payload before bounded
  workload binding.
- Evidence, model output, connector content, and request bodies are untrusted
  inputs. Runtime secrets belong in process/secret-manager channels, not source,
  fixtures, evidence, or documentation.
- Provider outage or disabled egress must degrade to human-only investigation;
  it must not select an unapproved provider.

See [Security Model](./security-model.md) for the complete threat model and
[Dataset Governance](./dataset-governance.md) for data lifecycle rules.

## Verification Evidence

| Evidence | Current result | Scope limit |
|---|---|---|
| Phase 5 Python suite | 149 passed; five PostgreSQL-gated skipped | Offline/default local verification; no provider call |
| Phase 5 quality checks | Ruff and mypy clean | Local verification |
| Platform API Maven suite | Pass | Local verification, including pgJDBC `42.7.13` and V005 migration contracts |
| `scripts/validation/validate-phase-05-ai-runtime.mjs` | Static checkpoint PASS | Exit gate remains BLOCK: active B-004 plus absent passing rotated-key synthetic smoke |
| `scripts/validation/validate-phase-06-tool-gateway.mjs` | Durable Prometheus connector checkpoint PASS with schemas, canonical fixtures, digest/manifest/OpenAPI/source abuse checks | Phase exit BLOCK: artifact adapter, remaining connector families, tenant bulkhead, and provider-specific cancellation proof |
| `scripts/validation/validate-phase-07-investigation-slice.mjs` | Durable Gateway/Prometheus implementation checkpoint PASS | Phase exit BLOCK: CK/Stitch UI/browser E2E and cross-service trace/p95 proof |
| GitHub Actions `29987371420` | PASS on commit `ace3642`: PostgreSQL trust contracts, live Prometheus Compose query, dependency security, service suites, Keycloak, and cross-platform bootstrap | CI non-production evidence; not the Phase 7 cross-service trace or staging conformance |

| Evidence | Verified result | Scope limitation |
|---|---|---|
| `artifacts/verification/phase-04/incident-contracts.txt` | PASS; 18 schemas and 21 fixtures parsed, 12 incident fixture cases evaluated, 171 local references resolved, eight OpenAPI operations checked | Deterministic static gate; no packaged JAR or live database |
| `artifacts/verification/phase-04/incident-domain.txt` | PASS; seven selected classes, 25 tests, zero failures/errors/skips | Focused JVM test gate |
| `artifacts/verification/phase-04/incident-crud.txt` | PASS; package, fresh/upgrade migration, guarded tests, SQL contract, cleanup, RLS/CRUD/privacy/transition checks | Disposable local PostgreSQL reference |
| `artifacts/verification/phase-04/audit-and-concurrency.txt` | PASS; digest recomputation, caller-forgery override, linear/concurrent chain, mutation denial | Disposable local PostgreSQL reference |
| `artifacts/verification/phase-03/identity-delegation.txt` | PASS; refreshed Keycloak 26.7 schema-v2 local reference | Not production identity or release proof |

Current hash recomputation matches all recorded Phase 4 source manifests, the
V003 digest, and packaged Platform API JAR digest. The Phase 3 identity and
Phase 4 PostgreSQL transcripts bind the same JAR digest. The Phase 7 checkpoint
has a clean full Platform API suite plus dedicated PostgreSQL migration,
persistence, and direct-SQL integrity coverage. Environment-guarded tests are
proven by the disposable PostgreSQL job; revision-bound counts come from the
current CI artifact rather than a stale local report directory.

## Standard Commands

After storage preflight and explicit setup, the Windows command surface is:

```powershell
.\scripts\dev\opsmind.ps1 setup
.\scripts\dev\opsmind.ps1 test
.\scripts\dev\opsmind.ps1 lint
.\scripts\dev\opsmind.ps1 build
.\scripts\dev\opsmind.ps1 security
```

Portable equivalents use `./scripts/dev/opsmind.sh`. `dev`/`up` require runtime
database secrets and Docker-storage attestation. `seed` and `evaluate` fail
explicitly until their owning phases implement them. See
[Local Development](./local-development.md) for prerequisites and failure
semantics.

## Explicitly Not Implemented or Proven

- Full Phase 4: incident list/patch/assignment, postmortems, and governed
  evidence upload/read/tombstone/restore/purge/reconciliation.
- Operator incident UI, live provider egress, live connectors, the real
  Platform-to-Tool-Gateway execution path, Temporal workflows, RAG,
  remediation, and production object storage.
- Production IdP/federation/session/break-glass conformance.
- Measured load/SLO proof, DR proof, or a production release.

These gaps are tracked in [Roadmap](./project-roadmap.md),
[Progress](./progress.md), and [Blockers](./blockers.md).
