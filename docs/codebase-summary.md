# OpsMind AI Codebase Summary

Last verified: 2026-07-28

## Purpose and Verification Basis

OpsMind AI is an evidence-first SRE/DevSecOps platform. Deterministic code owns
identity, authorization, tenant scope, incident state, audit, budgets,
approvals, and external effects; models are intended to assist within those
boundaries rather than receive direct infrastructure authority.

This summary is based on:

- direct inspection of the root manifests, Compose file, current service code,
  contracts, Flyway migrations, validation runners, and Phase 4 evidence;
- cross-checks against [System Architecture](./system-architecture.md),
  [Security Model](./security-model.md), [Testing Strategy](./testing-strategy.md),
  [Progress](./progress.md), and [Roadmap](./project-roadmap.md).

Generated local artifacts are evidence inputs, not source-of-truth
documentation. Source code and canonical contracts take precedence. Repomix
1.14.0 packed 4,714 files on 2026-07-28. Claims below were still checked
against the current code, OpenAPI, migrations, tests, and CI evidence rather
than inferred from the compaction.

## Delivery State

| Area | Verified state |
|---|---|
| Phase 1 | Complete; operating-envelope and governance gates passed. |
| Phase 2 | Complete; immutable clean-runner evidence closes G1. |
| Phase 3 | In progress; identity, tenant/RLS, persistence, and messaging substrate exists. Production-authorized IdP conformance remains open. |
| Phase 4 | In progress; 4A, bounded 4B, and tenant-scoped incident-list checkpoints exist. Phase 4C now includes V014 metadata authority, V015 fenced upload, and V018/V019 lifecycle/access runtime shells. Full Phase 4 and G2/G3 are not complete. |
| Phase 5 | In progress; provider-neutral analysis, DeepSeek adapter, egress guards, durable PostgreSQL state, V005 append-only probe audit, Platform API integration, and stream assembly exist. Static checkpoint passes; exit remains blocked by B-004 and missing rotated-key synthetic smoke. |
| Phase 6 | In progress; durable PostgreSQL, synthetic Prometheus, and tenant-scoped bulkhead checkpoints pass. Artifact/broader-connector/live/provider-cancellation exit remains blocked. |
| Phase 7 | In progress; cross-service trace, 100-warm-run fixture, CK/Stitch UI/browser E2E, and the metadata-only incident activity route plus V009 CI fixture gates pass. G3 remains blocked by live non-production connector/provider/legal conformance and BFF/session proof. |
| Phase 8 | In progress; Phase 8B contracts, V008 binding, bounded projection, production-path A/B/C, artifact attestation, and blocking review pass. Parent exit remains blocked by unavailable held-out/human/calibration evidence. |
| Phase 9 | In progress; default-off atomic workflow-start handoff, capability-contained dispatcher/reconciler, and profile-gated Temporal worker conformance exist. PR #45 proves revision-bound PostgreSQL/Maven/Docker, pinned local restart/replay, compatible polling, bounded live scrape, and CI-local routing; PR #56 proves explicit pinned rule syntax checks. B-017 remains active for production database query-plan/latency and DR, live namespace read-only/retention conformance, and external alert delivery. B-013 is active. |
| Later phases | RAG, remediation, complete operator UX, and production-hardening outcomes remain pending. |

Phase 7's local Operator Web and fixture-backed cross-service checkpoints are
complete for the safe projection boundary. The activity representation now
links incident and investigation metadata at read time without copying ledger
rows. Live provider/connector/legal conformance and the production BFF/session
gate remain open. Timeline-plan Phases 2/3 and V009 fixture gates are complete;
no broader Phase 7/G3 or production performance completion is claimed. PR
Quality run `30257587569` and
PostgreSQL artifact `8650178111` prove the route/V009 gates; cross-service run
`30257587543` and artifact `8649696519` preserve the Phase 7 regression and 100
warm Scenario A runs. These are CI fixture/test gates, not production SLO proof.

Historical Phase 3/4 workstation transcripts remain local/reference evidence
and explicitly deny release status. Revision-bound GitHub Actions evidence is
tracked separately; no production IdP or production deployment result is
claimed.

## Repository Map

| Path | Current responsibility |
|---|---|
| `apps/operator-web/` | Next.js server-rendered operator investigation workspace with a server-only Platform client, versioned safe-projection parser, degraded states, and Playwright coverage. |
| `services/platform-api/` | Spring Boot control plane for OIDC identity, tenant/project access, persistence, messaging, incidents, deterministic investigation, and the default-off Phase 9 Temporal client/dispatcher/read-only reconciler. |
| `services/ai-runtime/` | FastAPI bounded analysis runtime with provider-neutral contracts, DeepSeek adapter, shared PostgreSQL replay/accounting, startup/periodic capability probe, `/health` liveness, and `/ready` readiness; live egress remains disabled. |
| `services/tool-gateway/` | Spring Boot fail-closed Tool Gateway: separated workload/delegated JWT trust, capability-derived tenant/project scope, exact-policy forced-RLS receipt/verified-audit state, tenant-free unverified audit, lease safety, bounded DLP, and read-only Prometheus. Default profiles remain fail closed; V003 isolation has immutable CI evidence. |
| `evaluation/` | Versioned, training-ineligible A/B/C smoke contracts; strict export/projector, scorer, held-out and human-input validators, schemas, and regression fixtures. Production-path smoke passes; release-scale held-out/human evidence is unavailable. |
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

The Phase 7/8 cross-service harness is under
`scripts/validation/cross-service/`: a loopback-only DeepSeek-compatible
fixture provider and a 100-warm-run Platform benchmark that emits p50/p95,
correlation IDs, evidence IDs, and bounded terminal-state proof without
persisting credentials or raw prompts. Local reports remain ignored; CI uploads
revision-bound artifacts after disposable execution and cleanup verification.

## Implemented Platform API Boundaries

The current controllers expose:

| Method and path | Implementation |
|---|---|
| `GET /api/v1/me` | `CurrentPrincipalController` |
| `GET /api/v1/organizations/{organizationId}/projects` | `ProjectQueryController` |
| `POST /api/v1/organizations/{organizationId}/projects/{projectId}/incidents` | `IncidentController.create` |
| `GET /api/v1/organizations/{organizationId}/projects/{projectId}/incidents` | `IncidentListController.list`; exact-status filter and live-view keyset pagination |
| `GET /api/v1/organizations/{organizationId}/projects/{projectId}/incidents/{incidentId}` | `IncidentController.detail` |
| `POST /api/v1/organizations/{organizationId}/projects/{projectId}/incidents/{incidentId}/transitions` | `IncidentController.transition` |
| `GET /api/v1/organizations/{organizationId}/projects/{projectId}/incidents/{incidentId}/timeline` | `IncidentController.timeline`; legacy JSON plus opt-in metadata-only activity representation |
| `POST /api/v1/organizations/{organizationId}/projects/{projectId}/incidents/{incidentId}/investigations` | `InvestigationRunController.start` (feature flagged); default inline `200`, Temporal `202 + Location` |
| `GET /api/v1/organizations/{organizationId}/projects/{projectId}/incidents/{incidentId}/investigations/{runId}` | `InvestigationRunController.get` (feature flagged) |

The incident controllers are enabled only when persistence is enabled. Create
requires `Idempotency-Key`; transition also requires a strong numeric
`If-Match`. Mutation responses carry an ETag and `X-Operation-Id`; create also
returns `Location`. Timeline pages accept 1-100 items and an opaque,
incident-bound cursor. Collection reads require `incident:read`, authorize the
tenant/project before binding cursor context, and return only `id`, `title`,
`severity`, `status`, `updatedAt`, and `version`. The unsigned token is a
bounded navigation hint for `(updated_at DESC, id DESC)`, not authorization or
snapshot state; V016 supplies filtered and unfiltered concurrent indexes.

Incident and investigation detail reads additionally support the typed
`application/vnd.opsmind.operator-projection.v1+json` representation. It carries
projection-class, redaction-version, and redaction-count assurances, is
`no-store`, varies on `Accept`, and is scoped by organization/project/
incident/run. Legacy JSON remains available for non-browser callers.

The timeline route preserves the existing `application/json` READ path and v1
incident-version cursor. Its opt-in
`application/vnd.opsmind.incident-activity-timeline.v1+json` representation
requires `incident:analyze` plus `ANALYZE`, returns `no-store`, and uses a
strict v2 cursor over `(occurredAt, sourceRank, eventId)`. Negotiation uses the
most-specific matching media range before quality; JSON wins ties. OpenAPI
uses `x-opsmind-representation-security` because standard OpenAPI security
requirements cannot vary by response representation, so generic tooling may
not enforce that mapping.

`JdbcIncidentTimelineRepository.listActivity` runs a parameterized `UNION ALL`
with organization/project/incident predicates in both branches. It projects
only `eventId`, `source`, `eventType`, `occurredAt`, `actorId`, and the source-
specific `incidentVersion` or `investigationRunId` plus
`investigationSequence`. It does not select JSON payloads or free text. The
ordering cursor is a forward-only live view: a fresh traversal is required for
rows committed later at or before an issued cursor.

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
  are supplied when entering `RESOLVED`; reopening clears them. Entering
  `CLOSED` rejects client-supplied resolution fields, preserves the stored root
  cause and resolution summary, and makes the aggregate terminal.

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

V006/V007 are persistence, not durable orchestration. The current code still
does not resume an in-flight reducer after process loss and does not append or
copy investigation events to `incident_timeline_events`; the activity route
reads both ledgers without changing either. The capability-backed AI/Tool
clients and loopback synthetic Prometheus path run in the disposable
cross-service harness; they do not establish a named live non-production
connector or provider/legal conformance, so G3 remains open.

### Phase 4C Durable Artifact Object Authority

V014 remains separate from V007 `evidence_records`: it records a
tenant-scoped, owner-bound artifact intent with deterministic UUIDv8 identity,
expected digest/length, policy classes, an opaque non-projected storage key,
and one exact `PENDING_UPLOAD` event/audit pair.

V015 adds forced-RLS upload attempts, a five-second-to-five-minute claim lease,
single-winner and stale-settlement fences, and deferred validation requiring
the exact STORED lifecycle event and redacted audit row in the settlement
transaction. The default-off AWS SDK v2 adapter performs one bounded
conditional S3-compatible PUT with a precomputed SHA-256 and SSE-KMS, disables
SDK retries, and HEAD-verifies ambiguous retries before another write. It
separates the request KMS identifier from the canonical response identifier
and retains only a non-null opaque version reference bounded to 1,024 UTF-8
bytes. Definitive failures are reclaimable, while post-PUT source/response
mismatches settle `ORPHANED` and block automated reclaim. Object I/O runs
between two current-authorization transactions.

No route accepts or returns artifact bodies yet. `STORED` remains unreadable
until scanning and `AVAILABLE`; retention, deletion receipts, restore, and
backend/KMS conformance remain follow-up work under B-006/B-008/B-012. V018
adds lifecycle state, tombstone/restore/purge-receipt/reconciliation metadata
transitions. V019 adds one `SECURITY DEFINER` capability that the fixed
`opsmind_app` runtime may execute only with the bound tenant and actor; direct
`UPDATE` on `evidence_artifacts` remains denied. The run-bound
`EvidenceArtifactReadService.authorizeReadableObject(...)` authorizes the
incident/run scope before probing the object and returns a non-enumerating
`evidence-artifact.not-found` failure for absent or mismatched objects. The
disposable contract is
`scripts/validation/run-phase-04c-artifact-lifecycle-postgres-contract.sh`:
it requires `OPSMIND_EPHEMERAL_DB=true`, migrates a fresh throwaway database
through V018 then V019, proves the capability boundary, checks atomic metadata
plus event/audit settlement, and drops the database on exit. The current local
contract is not a production backend/KMS, scanning, retention, restore, or
release proof.

V009 adds concurrent ordering indexes on both activity sources and opts that
script out of Flyway transactions. Rollout order is migration before code;
failed builds use catalog/history capture, exact-name concurrent removal of
both V009 indexes, Flyway repair, and retry rather than mutation of applied
history. PR Quality run `30257587569`, PostgreSQL job `89950772823`, and
artifact `8650178111` prove fresh/upgrade, recovery, bounded plans, legacy
writes, cleanup, and the 3/3 activity HTTP matrix. Its 60,600 incident and
61,206 investigation rows produced append p95 regressions of 11.66% and 4.02%,
vendor p95 of 2.563/1.466/1.533 ms, and index cost of 95.70 bytes/source row
and 10.76% of table bytes. All plan thresholds pass as CI fixture/test gates;
they are not production latency or SLO claims.

## Phase 9 Temporal Start Handoff

Execution mode defaults to `inline`, preserving synchronous `200` start
responses. In explicit `temporal` mode, admission validates the configured
target and requires a task-queue workflow poller with the exact configured
identity and build ID. A new start returns `202` with `Location`.

`JdbcInvestigationWorkflowHandoffRepository` opens one tenant/actor-bound
application transaction for the initial run/event/audit state, immutable V010
binding, and canonical workflow-start outbox event. V010 fixes the workflow ID
as `opsmind-investigation/{organizationId}/{runId}` and binds exact payload
bytes/digest, client request digest, authorization revision, logical cluster,
namespace, workflow type, and task queue. It grants the app insert/select
authority while V011/V012 route dispatcher mutation through fixed capabilities.

The opt-in workflow-start scheduler lives in the Platform API artifact and uses
a dedicated dispatcher datasource. Each tenant claim transaction commits before
`TemporalInvestigationWorkflowClient.start`; the RPC is explicitly rejected
inside a database transaction. V012 revokes direct dispatcher authority on
workflow bindings and inbox rows, hides canonical workflow-start outbox rows
from generic dispatcher DML, and exposes a one-item resolver-owned claim
function. Because those rows are hidden from the generic claim path, a
resolver-owned predecessor check preserves same-aggregate ordering for visible
successor events.

`AlreadyStarted` converges only after exact workflow/type/task-queue/execution
identity, memo digest, and first start-history input verification. A retryable
post-RPC result may have been accepted remotely. V012 retries ambiguity inside
the bounded policy and then retains `PENDING` with
`workflow.reconciliation-required` at attempt, age, deadline, authorization, or
lease exhaustion rather than recording `REJECTED`. Attempted V011 legacy
`workflow.temporal-unavailable` rows are normalized conservatively, and durable
ambiguity preflight precedes payload validation/decode.

V013 adds the separate LOGIN role `opsmind_workflow_reconciler` and NOLOGIN
owner `opsmind_workflow_reconciliation_resolver`. Blanket privilege revocation
precedes grants on exactly three fixed functions: one-item claim, lease-fenced
settlement, and aggregate status. The runtime has no direct table access,
tenant-context setter, or trigger-function execution. Claim does not increment
normal starter attempts. Settlement records an exact match, requires two
retention-bounded absence samples before rejection, releases work when a normal
dispatcher becomes eligible, and preserves binding/outbox `PENDING` for retry,
permission/configuration/history uncertainty, or exhaustion.

The default-off `InvestigationWorkflowObserver` has one exact-observation
method. `TemporalInvestigationWorkflowObserver` issues
`DescribeWorkflowExecution` by workflow ID, then requests one first-history
event pinned to `first_run_id`. This remains correct across Continue-As-New:
the first-run event supplies workflow type, task queue, memo digest, and decoded
start input rather than mutable current-execution description fields. The port
exposes no Start, signal, update, or query surface. A pre-observation maximum
handoff-age fence emits `workflow.reconciliation-handoff-age-exceeded` and
preserves canonical `PENDING` state.

`.env.example` declares the fixed reconciler role plus disabled datasource.
Both launchers reject reconciler secrets in `.env`, require a distinct
process-scoped password for application Compose, and enforce the fixed username.
The datasource pool is bounded to 1-4 connections. Its 3,000 ms default
connection timeout is validated within 250-30,000 ms; its 1-second default query
timeout is validated within 1-30 seconds and applied to JDBC statements,
PostgreSQL socket reads, and JDBC transactions. Reconciler startup additionally
requires connection acquisition plus query timeout to fit strictly inside both
the settlement margin and lease-minus-settlement window. Aggregate-only metrics
use management port `8082`. Application defaults expose health only; Compose
explicitly enables `health,prometheus` on the internal network. Prometheus
retains bounded metric/label allowlists and evaluates seven reconciliation
alerts.

This checkpoint remains default-off and does not authorize a production
Temporal namespace. Profile-gated local/CI conformance includes a pinned
Temporal development server and investigation worker. PR #45 exercised
restart/replay and a fresh compatible workflow poller, ran the disposable
V001-V013/upgrade contracts in revision-bound CI, scraped the bounded
reconciliation metrics live, and recorded one CI-local Alertmanager callback.
V010 performs no legacy backfill; nonterminal binding-less runs block Temporal
admission pending operator reconciliation.
The Phase 9 static gate, observability validator, lightweight `javac` main/test
compile, and disposable PostgreSQL V001-V013 contract pass. The database
contract produced 55 PASS markers, including cleanup, and proves
direct/PUBLIC denial, the global exact-three executable set,
membership-drift denial, match, two-sample absence,
reactivation, mismatch, retry, blocked/retention/exhaustion `PENDING`
preservation, lease fencing/takeover, cross-tenant denial, four settlement
rollback failpoints, and cleanup. A local V012-to-V013 upgrade probe also passes
exact-three/PUBLIC denial, and both database paths are wired into PR Quality.
PR #45 provides the merged-head Maven/Docker/database/runtime evidence. PR #56
run `30802636501` additionally records pinned `promtool check config`, explicit
`check rules` for 10 recording and seven alert rules, and deterministic rule
tests. Production query-plan/latency and DR evidence, live namespace
retention/read-only credential conformance, and an external receiver with an
end-to-end page receipt remain missing. Master Phase 9 and roadmap G4 remain in
progress; B-013 and B-017 remain active.

## Phase 8B Evaluation Boundary

`evaluation/benchmark-manifest.yaml` marks exactly SIM-01, SIM-02, and SIM-03
implemented. A is a deployment-latency regression with one read-only evidence
result. B terminates `ABSTAINED` with no tools, hypotheses, or citations. C
executes two opposing read-only evidence collections and requires persisted
counter-evidence plus confidence no greater than `0.6`.

Platform V008 is a rolling-compatible expand migration: it retains the exact
legacy response-less V007 write shape while strictly validating a complete
normalized response whenever a response-aware writer supplies one. Evaluator
exports require response-bearing events. The V007-to-V008 upgrade proof retains
a historical payload unchanged and appends a legacy event after migration;
the later contract step waits until old writers are drained. Tool Gateway V002
records observed tool/action/risk, connector ID/profile, and the digest of the
manifest bytes selected at runtime. V003 adds capability-derived
`TenantProjectScope`, transaction-local tenant/project binding, forced RLS on
receipts and verified audits, and a separate insert-only lane for
pre-verification decisions. Global nonces and `execution_id` remain compatible;
an RLS-invisible foreign-ID collision is a generic conflict. Readiness checks
schema usage and the exact sole RLS definitions, authenticated delivery
rejections use the tenant-free audit lane, and startup rejects leases shorter
than enabled connector duration plus finalization margin. The disposable
cross-service harness creates a `NOLOGIN` view owner with allowlisted
source-column access and a separate non-inheriting, read-only evaluator.
Security-barrier views require exact transaction-local organization, Tool
Gateway tenant/project, incident, run, and actor scope. The evaluator has no
raw-table or allowlist-table read grant.

`cross-service-evaluation-export.sql` caps one run at 128 events, 200 evidence
metadata rows, 20 receipts, 21 analysis invocations, and 4 MiB. The Node
projector rejects duplicate keys, malformed UTF-8, duplicate evidence digests,
unsafe values, unknown fields, scope drift, ambiguous accepted-response
bindings, and receipt/evidence mismatch. It binds invocation
model/prompt/schema/token/tool/cost accounting to the accepted response, sums
cost across every accepted invocation, and derives trusted tool executions from
accepted intents plus durable receipt/audit/evidence records. Audit result,
receipt evidence, and persisted evidence digests must be identical. Scenario C
derives metric meaning from canonical metric content rather than unstable
ordering. Projection output contains normalized accepted analysis and
timeline/evidence/invocation/receipt metadata only; prompts, provider reasoning,
credentials, raw connector bodies, and evidence content are excluded. Raw bytes
and semantic JSON carry separate typed, domain-separated SHA-256 digests.
Reparse ancestors are rejected before writes; cleanup deletes credentials and
raw exports first and aggregates failures.

The exact CI evaluation command passes 61/61. The Phase 8 validator reports six
schemas, ten families/three implemented, held-out `UNAVAILABLE` with zero cases,
human baseline `UNAVAILABLE` with zero cases, three canonical results, eight
metrics, four negative cases, zero errors, checkpoint `PASS`, and phase exit
`BLOCK`. Revision-bound PR-quality run `30257587569` and cross-service run
`30257587543` are terminal green on
`a975f922fcd93c71479b9e15563643a9ea1aa04f`; A/B/C pass with samples `100/1/1`
and `GitTree=0`. Artifact `8649696519` is evidence bound to that revision.
Two independent process-supervision reviews pass.

## Security and Failure Posture

- OIDC mode validates signature/issuer, audience, subject, time claims, maximum
  lifetime, clock skew, and required MFA `amr`; tenant claims are not authority.
- The schema-v3 Keycloak reference flow rejects a locally tampered callback
  `state` before token-endpoint I/O and binds the authorization-request nonce to
  the returned ID-token payload nonce over CA-pinned TLS. Its Python helper does
  not verify ID-token signatures. The closed verifier requires both PASS fields
  and rejects missing, duplicate, or unknown evidence fields. Production
  browser/BFF session-level state/nonce ownership remains unproven.
- Persistence-enabled API requests recheck platform-user status. Unknown or
  deprovisioned users deny; authority-store failure fails closed.
- Application authorization and forced PostgreSQL RLS are separate controls.
- The web database role cannot lease or acknowledge outbox rows. The dispatcher
  role cannot access incident tables and sees no tenant payload before bounded
  workload binding.
- For Phase 9 settlement, V012 removes direct dispatcher access to workflow
  binding/inbox rows and canonical workflow-start outbox rows. V013 gives the
  reconciler function execution only; corrected disposable fresh, upgrade, and
  four-failpoint atomicity contracts pass. PR #45 proves the repository-owned
  database/Maven/Docker/runtime boundary. B-017 remains active for production
  query-plan/latency and DR, live Temporal authorization/retention, and external
  page-delivery proof.
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
| AI Runtime Python suite | 159 passed; five PostgreSQL-gated skipped in revision-bound CI | Offline/default verification; no provider call |
| Phase 5 quality checks | Ruff and mypy clean | Local verification |
| Platform API Maven suite | Pass | Local verification, including pgJDBC `42.7.13` and V005 migration contracts |
| `scripts/validation/validate-phase-05-ai-runtime.mjs` | Static checkpoint PASS | Exit gate remains BLOCK: active B-004 plus absent passing rotated-key synthetic smoke |
| `scripts/validation/validate-phase-06-tool-gateway.mjs` | Durable Prometheus connector plus tenant-scoped bulkhead checkpoint PASS with schemas, canonical fixtures, digest/manifest/OpenAPI/source abuse checks, lifecycle and eviction markers | Phase exit BLOCK: artifact adapter, remaining connector families, named live connector, and provider-specific cancellation proof |
| `scripts/validation/validate-phase-04c-evidence-artifacts.mjs` | V014/V015 metadata/upload/RLS/S3 source checkpoint plus V018/V019 lifecycle/access markers pass; object storage remains default-off | Disposable PostgreSQL lifecycle contract and focused Java tests still need exact-revision execution; no production backend/KMS, scanning, retention, restore, or release proof |
| `scripts/validation/run-phase-04c-artifact-lifecycle-postgres-contract.sh` | Fresh disposable PostgreSQL V018-to-V019 migration, exact-three capability boundary, run-bound transition/event/audit atomicity, direct mutation denial, and cleanup markers | Requires an explicitly disposable database and packaged Platform API JAR; local storage preflight or unavailable PostgreSQL can block execution |
| `scripts/validation/validate-phase-07-investigation-slice.mjs` | Artifact `8649696519` records OperatorWorkspace/CrossService/Checkpoint/PhaseExit PASS; Scenario A has 100 warm runs | G3 still requires live provider/connector/legal and BFF/session proof |
| `scripts/validation/validate-phase-08-evaluation-foundation.mjs` | Six schemas, ten families/three implemented, three results/eight metrics/four negative cases, zero errors, checkpoint PASS | Phase exit BLOCK; held-out and human inputs unavailable |
| `scripts/validation/validate-phase-09-workflow-handoff.mjs` | Integration static gate PASS with V010-V013, one Temporal pin, 18 payload fields, seven required test files, and zero errors | Static output alone; pair with PR #45 revision-bound restart/replay and compatible-poller evidence. It does not prove production namespace authorization |
| `scripts/validation/run-phase-09-reconciliation-postgres-contract.sh` | Corrected disposable PostgreSQL V001-V013 real-role run PASS with 55 markers including cleanup: global exact-three privileges; direct/PUBLIC/membership denial; match/absence/reactivation/mismatch/retry/block/retention/exhaustion; lease/takeover/cross-tenant; four atomic rollback failpoints. Local V012-to-V013 exact-three/PUBLIC-deny upgrade also passes; merged-head PR Quality run `30775354989` is green | Query-plan/latency, DR, and production database proof remain |
| `scripts/validation/validate-phase-09-reconciliation-observability.mjs` | Internal port-8082 scrape contract, bounded labels, aggregate recordings, and seven alert rules pass; PR #45 Compose evidence proves a healthy live scrape plus `Phase9AlertReceipt=PASS`, and PR #56 records explicit pinned rule checks | CI-local routing only; configured external receiver/page delivery remains unproved |
| GitHub Actions `30257587569` | PASS on revision `a975f922`: bootstrap, secrets, actionlint, service/UI suites, dependency security, PostgreSQL job `89950772823`, Keycloak, Compose; artifact `8650178111` proves V009/activity gates | CI fixture/non-production evidence; not production latency, SLO, or conformance |
| GitHub Actions `30257587543` | PASS on revision `a975f922`: A/B/C samples `100/1/1`, all metrics PASS, `GitTree=0`, Phase 7 regression PASS, artifact `8649696519` | Deterministic authored smoke; not held-out quality, calibration, or human benefit |

| Evidence | Verified result | Scope limitation |
|---|---|---|
| `artifacts/verification/phase-04/incident-contracts.txt` | PASS; 24 schemas and 32 fixtures parsed, 19 incident fixture cases evaluated, 237 local references resolved, 11 OpenAPI operations checked | Deterministic static gate; no packaged JAR or live database |
| `artifacts/verification/phase-04/incident-domain.txt` | PASS; seven selected classes, 25 tests, zero failures/errors/skips | Focused JVM test gate |
| `artifacts/verification/phase-04/incident-crud.txt` | PASS; package, fresh/upgrade migration, guarded tests, SQL contract, cleanup, RLS/CRUD/privacy/transition checks | Disposable local PostgreSQL reference |
| `artifacts/verification/phase-04/audit-and-concurrency.txt` | PASS; digest recomputation, caller-forgery override, linear/concurrent chain, mutation denial | Disposable local PostgreSQL reference |
| `artifacts/verification/phase-04/incident-resolution-closure.txt` | PR Quality `30868733961` on head `13a0224`: three PostgreSQL HTTP lifecycle tests, zero failures/errors/skips | Exact-head non-production CI proof; no frontend or production SLO claim |
| `artifacts/verification/phase-03/identity-delegation.txt` | Historical PASS: Keycloak 26.7 schema-v2 local reference; current schema-v3 contract adds callback-state tamper denial before token I/O, ID-token payload nonce binding over CA-pinned TLS, and a closed evidence field set | Not production IdP, BFF/session, federation, break-glass, or release proof; Python payload decoding is not ID-token signature verification |

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
database secrets and Docker-storage attestation. `seed` remains unavailable.
`evaluate` validates the Phase 8 contracts and scores an existing clean-revision
Phase 7 trace; it does not generate the trace. Missing raw artifacts or
provenance fail closed. See
[Local Development](./local-development.md) for prerequisites and failure
semantics.

## Explicitly Not Implemented or Proven

- Full Phase 4: incident list/patch/assignment, postmortems, and governed
  evidence body upload/read/stream/finalize/hold/restore/purge/reconciliation.
- Live provider egress, a named live non-production connector, a production
  Temporal cluster/namespace, RAG, remediation, and production object
  storage/lifecycle. The default-off Temporal handoff and profile-gated local/CI
  worker restart/replay are implemented checkpoints.
- Phase 9 production Temporal retention/read-only credential conformance,
  query-plan/latency, DR, and external Alertmanager delivery proof.
  Repository-owned merged-head Maven/Docker/CI, local worker/restart, live
  scrape, and CI-local routing are implemented checkpoints; they are not
  rollout authorization.
- Production IdP/federation/session/break-glass conformance.
- Measured load/SLO proof, DR proof, or a production release.

These gaps are tracked in [Roadmap](./project-roadmap.md),
[Progress](./progress.md), and [Blockers](./blockers.md).
