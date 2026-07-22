---
phase: 5
title: "DeepSeek AI Runtime and Model Gateway"
status: in-progress
priority: P1
dependencies: [1, 2, 3, 4]
effort: "2-3 weeks"
---

# Phase 5: DeepSeek AI Runtime and Model Gateway

## Objective

Stand up `services/ai-runtime` as a separate FastAPI runtime that exposes a versioned investigation-analysis contract, routes model traffic through a DeepSeek adapter with default model ID `deepseek-v4-flash`, captures reproducible invocation metadata, and fails closed when provider output, configuration, or budgets are invalid.

Before any incident content leaves the platform, enforce the G0.5 provider/data-processing contract by tenant and data class. A configured API key is necessary but never sufficient authorization for external egress.

## Non-Goals

- No direct tool execution. This phase may emit normalized tool intents only; Phase 6 owns execution.
- No durable Temporal workflow. Short-lived bounded analysis only; Phase 9 owns durable orchestration.
- No write-capable remediation, approval flow, RAG ingestion, or student-model work.
- No raw API key in repo, fixtures, logs, screenshots, or benchmark artifacts.

## Source Anchors

- `plans/260719-1747-opsmind-ai-production-platform/research/master-prompt-requirements-traceability.md:39-43`
- `plans/260719-1747-opsmind-ai-production-platform/research/master-prompt-requirements-traceability.md:109-112`
- `plans/260719-1747-opsmind-ai-production-platform/research/master-prompt-requirements-traceability.md:146-150`
- `plans/260719-1747-opsmind-ai-production-platform/research/researcher-01-architecture-security.md:7-12`
- `plans/260719-1747-opsmind-ai-production-platform/research/researcher-01-architecture-security.md:56-57`
- `plans/260719-1747-opsmind-ai-production-platform/research/researcher-01-architecture-security.md:101-115`
- `plans/260719-1747-opsmind-ai-production-platform/reports/brainstorm-report.md:106-109`
- `plans/260719-1747-opsmind-ai-production-platform/reports/brainstorm-report.md:159-168`
- `plans/260719-1747-opsmind-ai-production-platform/reports/brainstorm-report.md:199-206`

## Prerequisites and Dependency Graph

- Hard blockers:
  - Phase 1 G0.5 must decide allowed outbound data classes, provider/region, retention/privacy terms, consent/purpose and compliant fallback. Until then, only synthetic provider conformance calls are allowed.
  - Phase 2 must create the monorepo service scaffold, local Python toolchain, and service-level dev commands.
  - Phase 3 must define versioned API contracts, tenant identity claims, idempotency conventions, and additive database migration rules.
  - Phase 4 must create the incident/audit ledger and the incident identifiers this runtime records against.
- Downstream consumers:
  - Phase 7 depends on this phase and Phase 6 to build the first thin investigation slice.
  - Phase 8 depends on this phase for baseline teacher-model benchmarking.
- Parallel safety:
  - This phase may run in parallel with Phase 6 if file ownership stays limited to `services/ai-runtime/**` plus its assigned `packages/contracts/json-schema/ai-runtime/**` and service-local migration/env fragments.
  - Do not modify shared root manifests that Phase 6 would also need. Use service-local files and per-service compose fragments.

## Data Flow

1. `services/platform-api` posts `AnalysisRequestV1` with workload identity plus a short-lived delegated capability; local contract tests use a dedicated test issuer.
2. AI runtime derives tenant/actor/run/resource scope from verified capability claims, checks body equality, enforces tenant/data-class egress policy, DLP/redaction, prompt/schema versions, budgets and provider mode before a provider call.
3. Adapter translates the normalized request into DeepSeek chat-completion payloads, including configured model, thinking mode, streaming flag, and opaque provider `user_id`.
4. DeepSeek returns content, tool-call intents, usage, and provider status. A complete multi-turn provider exchange is one bounded operation; `reasoning_content` exists only inside that operation. A crash fails the exchange and a caller restarts from persisted evidence references rather than attempting an invalid partial continuation.
5. Runtime validates the response against app-owned Pydantic/JSON schemas and maps provider failures to stable problem codes.
6. Runtime writes invocation metadata, prompt/model/schema versions, latency, token usage, cost estimate, and normalized outcome to audit/projection storage.
7. Runtime returns either:
   - `complete` with hypotheses, counter-evidence, missing evidence, citations, confidence, and action proposals, or
   - `need_more_evidence` with normalized tool intents for Phase 6/7, or
   - `abstain` / `provider_unavailable` / `budget_exceeded` with explicit fail-closed semantics.

## Architecture and Design Decisions

- Separate trust boundary: `services/ai-runtime` stays isolated from tool execution and from the Java control plane codebase to avoid a single process holding both model and production-access paths.
- Ports-and-adapters only: provider-specific request/response shapes live under a DeepSeek adapter. Domain and application layers consume a provider-neutral interface so model churn does not infect business logic.
- Configuration over code: `base_url`, `api_key` reference, `model`, thinking mode, concurrency ceilings, and timeout budgets are runtime config. Legacy aliases `deepseek-chat` and `deepseek-reasoner` are accepted only as migration-only config names until `2026-07-24 15:59 UTC`, after which startup must reject them explicitly.
- Fail-closed structured output: JSON mode is advisory only. Empty content, malformed JSON, schema drift, missing citations, or invalid tool args become explicit error states, never silent coercion.
- Privacy boundary: raw chain-of-thought is never persisted or rendered. `reasoning_content` is scoped to one in-process bounded provider exchange, is never keyed as durable run state, and is dropped on completion, cancellation, timeout, or failure.
- Delegation boundary: only the platform API issues analysis capabilities; tenant/actor scope from JSON cannot expand the signed capability.
- Egress boundary: tenant, purpose, sensitivity, provider and region policy plus outbound DLP are evaluated at the last hop. Synthetic canaries prove prohibited fields never cross that boundary.
- Reproducibility boundary: every call records prompt version, schema version, model ID, temperature or reasoning mode, token/cost totals, request hash, and provider response class so Phase 8 can replay and compare runs.
- Runtime safeguards:
  - bounded retries only for categorized `429`, `500`, and `503`
  - no blind retry for `400`, `401`, `402`, or schema-invalid content
  - per-run token, time, and cost ceilings
  - startup capability probe to verify provider feature support before live traffic
  - a streaming assembler that requires ordered complete terminal frames before any tool intent or model result becomes accepted state

### Durable runtime-state checkpoint

This checkpoint uses one `ai_runtime` schema in the existing PostgreSQL cluster. It does not add Redis, Kafka, or a second database. The runtime connects through a dedicated `opsmind_ai_runtime` login with `NOSUPERUSER`, `NOINHERIT`, and `NOBYPASSRLS`; the migration owner remains separate.

- `capability_nonces` stores only a SHA-256 nonce digest plus tenant/run/expiry metadata. A global primary key and `INSERT ... ON CONFLICT DO NOTHING` make consumption atomic across replicas without retaining bearer material.
- `analysis_run_budgets` stores immutable tenant/incident/run limits, cumulative committed token/tool/cost usage, and at most one active invocation reservation. Reserve/commit/fail operations lock this row, so provider allowance cannot be oversubscribed by concurrent replicas.
- `analysis_invocations` is append-only for identity and request metadata, with explicit `reserved`, `succeeded`, `failed`, and `ambiguous` states. Successful rows may retain only the validated normalized response, never the raw prompt, provider reasoning, capability, credential, or provider error body.
- An active reservation has a bounded lease that never expires before the signed request deadline; the configured duration is a minimum for short requests. A later reservation that finds an expired lease first commits the prior invocation as ambiguous and charges its full reserved token/cost amount before evaluating remaining allowance in a separate transaction.
- A successful `(tenant_id, run_id, request_digest)` is replayed from its validated normalized response after a fresh delegated capability is verified and its nonce is consumed. Failed or ambiguous attempts are not replayed as success.
- Every transaction binds `opsmind.ai_runtime_tenant_id` with `SET LOCAL`; forced RLS scopes budget and invocation access. The runtime role has no direct mutation path that bypasses the tables' tenant policy.
- Primary queries are exact nonce consume, run-budget `FOR UPDATE`, completed-response lookup by tenant/run/request digest, and invocation history by tenant/run/time. Indexes follow only those paths; retention cleanup by `retain_until` stays a later lifecycle-worker responsibility.
- Migration is additive. Application rollback disables the PostgreSQL state backend and does not downgrade or delete durable invocation history.

## File Inventory

### CREATE

- `services/ai-runtime/pyproject.toml` - Python service package and test dependencies.
- `services/ai-runtime/app/main.py` - FastAPI bootstrap and health routes.
- `services/ai-runtime/app/config/settings.py` - typed env/config loader and startup guard.
- `services/ai-runtime/app/api/v1/analysis.py` - sync and streaming analysis endpoints.
- `services/ai-runtime/app/domain/analysis_contracts.py` - request/response domain models.
- `services/ai-runtime/app/application/analysis_service.py` - orchestration for validation, provider call, and audit write.
- `services/ai-runtime/app/application/prompt_registry.py` - prompt/schema/model version lookup.
- `services/ai-runtime/src/opsmind_ai_runtime/application/budget_guard.py` - atomic token, tool-intent, time, and cost budget reservation.
- `services/ai-runtime/src/opsmind_ai_runtime/application/delegated_capability.py` - workload audience, scope, nonce and expiry enforcement.
- `services/ai-runtime/src/opsmind_ai_runtime/application/egress_policy.py` - tenant/data-class purpose, DLP and provider/region gate.
- `services/ai-runtime/src/opsmind_ai_runtime/application/provider_gateway.py` - provider-neutral outbound port and sanitized failure taxonomy.
- `services/ai-runtime/src/opsmind_ai_runtime/providers/deepseek/client.py` - raw HTTP client wrapper.
- `services/ai-runtime/src/opsmind_ai_runtime/providers/deepseek/adapter.py` - DeepSeek adapter implementation.
- `services/ai-runtime/src/opsmind_ai_runtime/providers/deepseek/error_mapping.py` - provider HTTP status classification.
- `services/ai-runtime/src/opsmind_ai_runtime/providers/deepseek/capability_probe.py` - startup validation for configured model and mode.
- `services/ai-runtime/src/opsmind_ai_runtime/providers/deepseek/stream_assembler.py` - ordered complete SSE/tool-call state machine.
- `services/ai-runtime/src/opsmind_ai_runtime/adapters/persistence/invocation_audit_repository.py` - writes reproducible invocation rows.
- `services/ai-runtime/app/adapters/persistence/sql/` - SQL statements scoped to the runtime schema.
- `services/ai-runtime/tests/unit/test_analysis_contracts.py`
- `services/ai-runtime/tests/unit/test_budget_guard.py`
- `services/ai-runtime/tests/unit/providers/test_deepseek_error_mapping.py`
- `services/ai-runtime/tests/contract/test_analysis_response_schema.py`
- `services/ai-runtime/tests/contract/test_deepseek_provider_matrix.py`
- `services/ai-runtime/tests/integration/test_analysis_endpoint.py`
- `services/ai-runtime/tests/integration/test_missing_key_behavior.py`
- `services/ai-runtime/tests/fixtures/deepseek/` - redacted fixture payloads for success and error cases.
- `packages/contracts/json-schema/ai-runtime/v1/analysis-request.schema.json`
- `packages/contracts/json-schema/ai-runtime/v1/analysis-response.schema.json`
- `packages/contracts/json-schema/ai-runtime/v1/problem-details.schema.json`
- `packages/contracts/fixtures/deepseek/` - provider conformance and cross-language fixtures.
- `services/ai-runtime/alembic/versions/*_ai_runtime_invocations.py`
- `infra/compose/fragments/ai-runtime.compose.yaml`
- `services/ai-runtime/.env.example`
- `services/ai-runtime/README.md`
- `scripts/validation/validate-phase-05-ai-runtime.mjs` - offline provider-contract and safe-default gate.

### MODIFY

- None expected if earlier phases provide empty service roots only.

### DELETE

- None.

## Implementation Tasks

1. Define the normalized analysis contract.
   - Request fields include `incident_id`, `tenant_id`, `run_id`, `prompt_version`, `schema_version`, `analysis_mode`, `context_refs`, `data_classifications`, `purpose`, `token_budget`, `tool_budget`, and `deadline_at`; authoritative scope comes from the signed capability.
   - Response fields must include `status`, `hypotheses`, `counter_evidence`, `missing_evidence`, `citations`, `confidence`, `usage`, `cost_estimate`, and optional `requested_tool_calls`.
   - Contract must support `complete`, `need_more_evidence`, `abstain`, and explicit error outcomes.
2. Implement typed configuration and startup gating.
   - Load DeepSeek config from env or secret reference only.
   - If provider mode is `deepseek` and the key reference is missing, service health reports degraded and analysis routes return stable `provider_unavailable` problem details instead of crashing mid-request.
   - Default to `deepseek-v4-flash`; any additional model must be present in an operator allowlist and pass the same capability/conformance suite. Temporary legacy aliases are rejected after `2026-07-24 15:59 UTC`.
3. Build the provider adapter and capability probe.
   - Support thinking and non-thinking mode.
   - Support streaming and non-streaming responses.
   - Preserve `reasoning_content` only inside one bounded exchange; never depend on it after process failure.
   - Assemble streaming deltas by provider sequence/state, require a terminal frame, reject duplicate/out-of-order/incomplete tool calls, and propagate cancellation.
   - Normalize provider usage totals and latency.
4. Build stable error mapping and retry policy.
   - Map `400`, `401`, `402`, `422`, `429`, `500`, and `503` into internal categories with deterministic retry rules.
   - Add bounded jittered retries only where idempotent and categorized retryable.
   - Emit a stable machine-readable problem code for every fail path.
5. Enforce budgets and fail-closed validation.
   - Reject requests with missing prompt/schema version, impossible deadlines, or zero budgets.
   - Stop provider loops after configured token/time/cost ceilings.
   - Canonicalize normalized tool intent + scope + time window into a digest, reserve budgets atomically, and reject duplicate/concurrent budget races.
   - Reject empty/truncated JSON, invalid citations, incomplete streams and malformed tool intents even when provider status is `200`.
6. Enforce workload delegation and outbound data policy.
   - Verify workload issuer/audience and platform-issued capability; reject body/claim mismatch, replayed nonce, expiry and unauthorized purpose/data class.
   - Redact/classify immediately before provider serialization. Provider key presence must never enable a tenant or data class whose egress policy is disabled.
   - Run only synthetic conformance payloads until provider privacy/residency/retention terms are approved.
7. Persist reproducibility metadata.
   - Write additive invocation rows keyed by run ID and incident ID.
   - Store prompt/schema/model version, provider mode, usage, latency, cost estimate, request hash, response class, and error code.
   - Do not store raw prompt secrets, raw chain-of-thought, or bearer credentials.
8. Provide local-service docs and a compose fragment.
   - Compose fragment must be service-local so Phase 6 can add its own fragment without touching the same file.
   - README must document degraded mode, required secrets, fixture tests, and live smoke command names.
9. Add verification and opt-in live smoke.
   - Contract and integration tests use fixture payloads and a fake provider server.
   - Live smoke uses synthetic canary-safe input until G0.5 explicitly authorizes incident data; credentials alone never change the allowed data class.

## Migration, Backward Compatibility, and Rollback

- Backward compatibility strategy:
  - New API routes are additive under `v1`; no existing incident APIs are changed in this phase.
  - Legacy DeepSeek alias handling exists only to bridge external configuration until `2026-07-24 15:59 UTC`; warnings must include the exact retirement date.
  - Invocation persistence is append-only and keyed by run ID so old rows remain readable after schema additions.
- Migration path:
  - Apply the additive invocation table migration.
  - Deploy runtime with provider disabled or degraded mode first.
  - Enable provider traffic only after contract tests and startup probe pass.
- Rollback:
  - Flip `AI_RUNTIME_ENABLED=false` or `AI_PROVIDER=disabled` to stop new calls immediately.
  - Revert service artifact without reverting additive invocation tables.
  - If a prompt/schema change regresses output quality, roll back prompt/model config independently from app code.

## Test and Evidence Matrix

| Scope | Coverage | Evidence artifact |
|---|---|---|
| Unit | request/response schema validation, budget guard, retry classifier, cost estimator | `services/ai-runtime/tests/unit/*` CI report |
| Contract | `400/401/402/422/429/500/503`, empty JSON, truncated/duplicate/out-of-order stream, legacy alias timing | provider matrix report |
| Integration | endpoint request lifecycle, degraded missing-key behavior, invocation persistence, trace propagation | integration test logs plus DB assertion report |
| Security | raw secret redaction, no chain-of-thought persistence, problem-code stability | security test log and artifact inspection |
| Delegation/egress | forged tenant/actor, wrong audience, nonce replay, prohibited data class, seeded DLP canaries | capability and outbound-policy report |
| Live smoke | one redacted call against real DeepSeek config | redacted smoke transcript with model ID and timing |
| Replayability | same fixture request reproduces same schema version, prompt version, and normalized shape | replay metadata diff report |

## Quantitative Exit Gate

- `AI-01`, `AI-02`, and `AI-05` tests from the traceability matrix are green.
- Structured-output parse success is `>= 99%` on the held-out fixture suite for this phase.
- `100%` of successful calls emit usage, latency, and cost metadata tied to the run ID.
- `100%` of invalid config cases fail before first live provider call.
- `100%` of forged delegation and prohibited-egress cases fail before serialization/network send; seeded critical canaries have zero outbound occurrences.
- Error-mapping suite covers `400/401/402/422/429/500/503` with no unclassified provider failures.
- One synthetic, redacted conformance run succeeds against `deepseek-v4-flash` when an externally injected rotated staging key is available; incident-data smoke additionally requires the G0.5 egress decision.

## Risks and Mitigations

| Risk | Likelihood x Impact | Mitigation |
|---|---|---|
| Provider behavior changes or model alias retirement on `2026-07-24` break startup | Medium x High | capability probe, config validation, exact-date warning, config-only rollback |
| JSON mode returns empty or malformed content | High x High | app-side schema validation, fail-closed response handling, fixture regression suite |
| Raw reasoning or sensitive prompts leak into logs | Medium x High | transient in-memory handling only, explicit log redaction tests, no broad telemetry capture |
| Cost or token runaway during loops | Medium x Medium | hard per-run budgets, timeout ceilings, explicit `budget_exceeded` outcome |
| Future tool-calling semantics couple provider and tool execution too early | Medium x Medium | emit normalized tool intents only; keep execution out of this service |

## Unresolved Decisions

- Whether production telemetry and source content may leave the organization is a G0.5 entry decision, not a late phase assumption.
- Whether any model beyond `deepseek-v4-flash` enters the initial allowlist after conformance testing.
- Whether streaming is required for the first operator-facing slice or may stay internal-only until Phase 7 UI wiring lands.

## Implementation checkpoint — provider-neutral contract and adapter

The first Phase 5 checkpoint now exists under `services/ai-runtime/`:

- strict `AnalysisRequestV1`/`AnalysisResponseV1` Pydantic contracts and JSON
  Schema roots;
- typed disabled-by-default settings with DeepSeek V4 Flash as the default
  model and legacy alias retirement guard;
- delegated capability scope matching, nonce replay protection, last-hop
  redaction/data-class egress policy, and bounded in-process budgets;
- exact signed-request digest and capability TTL/deadline binding, evidence
  metadata classification/citation checks, and pre-parse body size/time bounds;
- provider-neutral application port with a DeepSeek client/adapter, sanitized
  error taxonomy, outbound URL/numeric config bounds, and contiguous
  terminal-frame stream assembler;
- cumulative run allowance converted to provider completion caps, positive
  live-pricing requirement, global deadline, and jittered retry backoff;
- stable replay, invalid-provider-response, and provider-usage-over-budget
  failure paths that do not expose provider details;
- offline contract, adapter, policy, capability, endpoint, replay, and stream
  tests. No real key or provider call is used by these tests.

Current checkpoint evidence: the Python suite reports 149 passed with five
PostgreSQL-gated skips in the default local run; Ruff and mypy are clean; and
the full Maven suite passes. The static validator reports `CheckpointResult=PASS`.
The additive durable invocation/replay schema and Psycopg adapter exist, and
V005 adds an append-only, secret-free synthetic provider capability-probe audit
table. Each process proves its own provider path; PostgreSQL advisory locking
enforces a bounded provider/model/region hourly quota using the database clock,
with jittered startup/retry scheduling. Configure the quota to at least
`12 * replica_count` plus rollout headroom, subject to the provider rate limit.
The Platform API pins pgJDBC to `42.7.13` and verifies that pin in its
test suite. `/health` is liveness, while `/ready` returns `503` whenever shared
state or the startup/periodic capability probe is degraded. Provider transport
uses `trust_env=False`; JWT/bearer values are redacted before egress.

The static pass is not exit or release evidence. `PhaseExitGate=BLOCK` because
`B-004` remains active for provider region, processing terms, retention
behavior, and redaction verification, and because a passing synthetic smoke
with an externally injected rotated staging key is absent. No live DeepSeek
smoke, incident-data smoke, or production egress is claimed.

Checkpoint review: the initial production-readiness pass found one High risk
where a short lease could expire during a legitimate provider call. The lease
now lasts at least through the signed request deadline, V004 enforces the same
invariant, the capacity-qualified PostgreSQL matrix passes 5/5, and fix-only
re-review found no remaining Critical/High regression. See
`../reports/code-review-260722-phase-05-post-fix.md`.
