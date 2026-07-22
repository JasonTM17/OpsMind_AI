---

## Current implementation checkpoint (2026-07-22)

The Spring namespace is `ai.opsmind.toolgateway` (the historical `com.*`
inventory below is stale and is not authoritative). Checkpoint evidence is
`node scripts/validation/validate-phase-06-tool-gateway.mjs` and the Tool
Gateway Maven suite (24 tests, zero failures/errors). The checkpoint covers:

- dedicated workload JWT vs one-use delegated RS256/JWKS capability domains;
- exact composite action/resource/body binding, nonce replay boundary, signed
  deadline and manifest timeout;
- checked-in manifest loading, typed synthetic observability action, selector
  binding, 32-slot backpressure bulkhead, cancellation, recursive DLP, metadata
  validation, canonical request/evidence digests, and stable failure statuses;
- strict JSON parsing, byte/body limits, canonical OpenAPI `/tools/execute`,
  four schemas, five fixtures, and fail-closed `/ready` behavior.

`CheckpointResult=PASS` is not `PhaseExit=PASS`. Exit remains BLOCKED until
durable atomic nonce/receipt/audit/artifact adapters, actual Platform API
capability issuer conformance, three fixture families, a selected live
non-production read-only connector, and provider-specific cancellation plus
tenant bulkhead evidence exist. No production connector or live provider
egress is claimed.
phase: 6
title: "Safe Tool Gateway and Read-only Connectors"
status: in-progress
priority: P1
dependencies: [1, 2, 3, 4]
effort: "3-4 weeks"
---

# Phase 6: Safe Tool Gateway and Read-only Connectors

## Objective

Deliver the separate Spring Tool Gateway as the only execution path for model-requested evidence acquisition. It must derive authority from platform-issued delegated capabilities, dispatch only typed manifest-backed read actions, isolate connector credentials, normalize evidence into the Phase 4 artifact lifecycle, and make allowed and denied attempts reconstructable.

## Non-goals

- No write-capable remediation or approval flow; Phase 11 owns them.
- No generic shell, URL fetcher, filesystem reader, SQL text, Kubernetes verb, Git remote or admin API.
- No direct AI-runtime-to-Gateway authority in release 1; AI runtime proposes intents, platform API authorizes and delegates.
- No claim that a fixture-only connector is production-ready.

## Prerequisites and entry gate

- Phase 1 G0.5 selects the first live non-production observability/deployment/source integration and its identity model.
- Phase 2 supplies the Maven/Spring service skeleton and canonical `packages/contracts/**` tree.
- Phase 3 supplies workload identity, delegated-capability claims, tenant/resource authorization and event/audit conventions.
- Phase 4 supplies incident/evidence IDs, append-only audit and the durable evidence-artifact port.
- Phase 5 can run in parallel only through frozen contracts; it never owns Gateway files or credentials.

## Data flow and trust boundaries

1. AI runtime returns a normalized tool intent; it has no target credential and cannot sign a Gateway request.
2. Platform API authorizes the intent for the human/service actor and issues a short-lived capability binding issuer, audience, subject, tenant/project/environment, incident/run, exact action/resources, budgets, nonce and expiry.
3. Platform API submits `ToolExecutionRequestV1` with stable `execution_id`; Tool Gateway authenticates the workload and verifies the capability.
4. Gateway derives authority only from verified claims. Duplicated body scope must match and can never expand them.
5. Manifest registry validates action/schema/risk/timeout/result-size/egress/redaction/audit configuration before connector dispatch.
6. Connector uses its own scoped read identity, executes against an allowlisted target, and cannot receive arbitrary commands, paths, URLs or queries.
7. Gateway redacts and normalizes output. Large evidence bytes are finalized through the Phase 4 artifact port; response contains immutable references/digests/provenance.
8. Gateway emits allowed, denied, timed-out, truncated and duplicate outcomes to the audit contract with request/response digests.

## Architecture and design patterns

- **Separate Spring process and deployment identity:** consistent with ADR-0001 and Phase 2 Maven bootstrap.
- **Ports and adapters:** one connector port per bounded provider capability; provider SDK objects never enter domain contracts.
- **Manifest registry:** each action declares name/version, risk class, JSON schemas, credential profile, egress targets, timeout, fan-out/result limits, redaction and audit class.
- **Capability-based delegation:** workload authentication is not end-user authorization. Only platform-issued, audience-bound, one-purpose capabilities carry authority.
- **Deny by default:** unknown action/version, missing scope, body/claim mismatch, replay, expired capability, egress mismatch or budget breach fails before network I/O.
- **Read-only by construction:** source-side role plus connector code and tests prohibit mutation; action names alone are not security.
- **Stable execution identity:** canonical request digest and `execution_id` deduplicate retries and support audit reconciliation.
- **Evidence envelope:** source, target identity, observation time/window, connector/manifest version, trust class, digest, redaction and artifact reference are mandatory.

## File inventory

### Create

- `services/tool-gateway/src/main/java/com/opsmind/toolgateway/ToolGatewayApplication.java`
- `services/tool-gateway/src/main/java/com/opsmind/toolgateway/config/GatewaySettings.java`
- `services/tool-gateway/src/main/java/com/opsmind/toolgateway/api/ToolExecutionController.java`
- `services/tool-gateway/src/main/java/com/opsmind/toolgateway/domain/{ToolExecutionRequest,EvidenceEnvelope,ToolOutcome}.java`
- `services/tool-gateway/src/main/java/com/opsmind/toolgateway/application/{ToolExecutionService,PolicyEvaluator,DelegatedCapabilityVerifier,EvidenceNormalizer}.java`
- `services/tool-gateway/src/main/java/com/opsmind/toolgateway/audit/ToolAuditWriter.java`
- `services/tool-gateway/src/main/java/com/opsmind/toolgateway/connectors/{observability,deployments,git,runbooks,database,kubernetes,kafka}/`
- `services/tool-gateway/src/main/resources/tool-manifests/`
- `services/tool-gateway/src/main/resources/db/migration/*__tool_gateway_audit_and_registry.sql`
- `services/tool-gateway/src/test/java/com/opsmind/toolgateway/{unit,contract,integration,security}/`
- `services/tool-gateway/src/test/resources/fixtures/`
- `packages/contracts/json-schema/tool-gateway/v1/{tool-execution-request,tool-execution-response,evidence-envelope}.schema.json`
- `packages/contracts/fixtures/tool-gateway/`
- `infra/compose/fragments/tool-gateway.yaml`
- `services/tool-gateway/.env.example`
- `services/tool-gateway/README.md`

### Modify

- `services/tool-gateway/pom.xml` — Spring Web/Security, validation, resilience, Flyway and tests.
- `packages/contracts/openapi/opsmind-v1.yaml` — internal tool API, stable denial Problem Details.

### Delete

- None. CI must reject a parallel Python/FastAPI Gateway tree or alternate tool schemas.

## Implementation tasks

### 6.1 Lock contracts and delegation

- Request includes `execution_id`, incident/run IDs, tool/action/schema version, arguments, deadline and result budget. Authoritative actor/scope/actions/budgets come from the capability.
- Response includes status, evidence references/items, denial/error reason, audit event ID, redaction/truncation summary and manifest/source provenance.
- Verify workload issuer/audience and capability signature/audience/expiry/nonce. Reject forged actor/tenant, scope mismatch and direct AI-runtime calls.

### 6.2 Build manifest registry and policy engine

- Fail startup on duplicate action/version, unresolved schema, illegal write declaration, missing limit, missing credential profile or unbounded egress.
- Dispatch by registry ID only. No class name, path, URL or provider command comes from the model.
- Enforce role/resource permission, risk class, tenant quota, deadline, fan-out, time window, row/line/byte and concurrency limits before execution.

### 6.3 Implement connector families

- Observability: bounded metrics/logs/traces with fixed query templates and safe label selectors.
- Deployment: release/rollout history and revision state.
- Git/source: commit/diff and allowlisted repository-relative file reads.
- Runbook: registered runbook/version lookup, never arbitrary URL.
- Database: approved read view/query-template IDs with typed parameters, never SQL text.
- Kubernetes/Kafka: typed status/lag actions; fixture-only unless selected at G0.5.
- Each family receives deterministic fixtures, one happy path and abuse/denial cases.

### 6.4 Prove one real non-production integration

- Use the G0.5-selected system with synthetic incident data, real workload identity and source-side read-only permissions.
- Exercise pagination/window semantics, label/field redaction, rate limits, timeout/cancellation, schema drift and outage.
- Prove the identity cannot mutate the target and egress cannot reach unlisted hosts/private addresses through redirect or DNS rebinding.

### 6.5 Normalize, persist and audit evidence

- Redact before logs, persistence or return. Seed canaries and assert they never escape permitted fields.
- Store large evidence through Phase 4 and return a tenant-authorized immutable reference; cap/truncate inline summary with full-content digest.
- Audit allowed/denied/timeout/truncation/duplicate outcomes with capability ID, manifest version and request/result digests, never raw credentials.

### 6.6 Reliability and idempotency

- Apply bounded timeout, rate limit, per-tenant bulkhead and queue backpressure.
- Claim `execution_id` before dispatch. Repeated read request returns the recorded compatible result/reference or explicit reconciliation state.
- Retry only idempotent reads and only categorized transient errors; cancellation propagates to provider client.

## Migration and rollback

- Apply Gateway-owned Flyway schema, deploy all actions disabled, then enable manifests individually after contract/abuse proof.
- Contract changes are additive/versioned; audit rows remain readable across rollback.
- Global and per-manifest kill switches fail closed without deployment.
- An unsafe connector is disabled independently; no fallback to a generic executor exists.

## Verification matrix

| Scope | Required evidence |
|---|---|
| Contract | Cross-language schema fixtures and stable denial/error codes |
| Delegation | Wrong workload audience, forged scope/actor, body mismatch, replay/expiry and direct AI-runtime call all denied |
| Abuse | SSRF/redirect/DNS rebinding, path traversal, command/query injection, oversized result and cross-tenant attempts blocked |
| Connector | At least one happy/denial fixture per family; selected connector also passes live non-production proof |
| Read-only | Source-side role inspection and mutation attempts prove no write capability |
| Evidence | Redaction/canary, artifact digest, scoped read and cross-tenant signed-reference tests |
| Reliability | Timeout, cancellation, duplicate `execution_id`, bulkhead and backpressure tests |
| Audit | Allowed and denied attempts reconcile to stable execution/audit IDs without secret payloads |

## Quantitative exit gate

- `TOOL-01`, `TOOL-02` and `TOOL-03` have green contract/abuse evidence.
- 100% of actions have versioned manifest/schema, bounded resources, credential profile and audit class.
- 100% of Critical SSRF/path/command/freeform-query/cross-tenant/delegation cases are denied before target I/O.
- At least three needed families work in fixture mode, and the selected first-wave connector works against a live non-production target with synthetic data.
- Zero action exposes arbitrary shell, URL, path, SQL, remote or admin verb; zero connector identity can mutate its target.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Read-only connector over-fetches sensitive data | Scope/window/cardinality caps, source redaction, canaries and scoped artifact access |
| Compromised internal workload becomes confused deputy | Platform-only delegation, exact capability claims, nonce/audience checks |
| Connector breadth delays slice | Three fixture families plus one selected live integration; other providers stay disabled |
| Policy tested in wrong runtime | Spring/Maven and canonical contracts enforced by ADR/CI |
| Source schema/rate semantics invalidate fixtures | Required live non-production conformance and versioned adapters |

## Unresolved decisions

- First-wave live connector and source identity are fixed at G0.5; unselected integrations remain explicit backlog, not production claims.
- Database evidence source (read replica, approved views or templates) is selected with the production data architecture; freeform SQL remains prohibited in every variant.
