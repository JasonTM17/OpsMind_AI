---
phase: 3
title: "Contracts Data Identity and Tenant Foundation"
status: in-progress
priority: P1
dependencies: [1, 2]
---

# Phase 3: Contracts Data Identity and Tenant Foundation

## Context Links

- [Plan](./plan.md)
- [Phase 1](./phase-01-operating-envelope-and-architecture-governance.md)
- [Phase 2](./phase-02-monorepo-and-developer-platform-foundation.md)
- [Requirements traceability](./research/master-prompt-requirements-traceability.md)
- [Architecture and security research](./research/researcher-01-architecture-security.md)
- [Current progress report](./reports/phase-03-progress-260720.md)

## Overview

This phase creates the first authoritative domain contracts and the trust boundary that all later phases depend on: tenant-aware identity, resource-level authorization, Problem Details API conventions, PostgreSQL migrations, forced RLS, idempotency semantics, and transactional outbox foundations.

The deliverable is not a full incident product yet; it is a trustworthy control-plane substrate. No later phase may bypass these contracts with ad hoc auth checks, duplicate schema definitions, or direct table access that ignores tenant scope.

## Current Implementation Status

The local slice now contains the versioned contracts, fail-closed Spring
security boundary, principal mapping, tenant-scoped project read path,
transaction-local context helper, delegated-capability validation ports,
PostgreSQL migration, forced-RLS policies, split migration/runtime Compose
roles, and outbox/inbox repositories. Static Phase 3 and repository-layout
validators pass, and focused Java tests cover the pure contract behavior.
The current slice also includes strict idempotency/request-digest and
optimistic-concurrency helpers, plus a fixed non-login/non-bypass database role
that owns only the narrow identity and membership resolver functions. A live
PostgreSQL 18 harness now applies Flyway with a migration owner and forces
Hikari to reuse one runtime connection while alternating tenants, commit,
rollback, invalid membership, statement timeout, and missing-context paths.
The matrix passes without context leakage and cleans all ephemeral objects.
The messaging substrate now preserves exact payload bytes beside `jsonb`,
enforces contiguous aggregate sequence in PostgreSQL, and implements bounded
lease/retry/poison plus inbox completion/reclaim primitives. A live fault
matrix covers commit, publish, acknowledgement, duplicate delivery, stale
leases, retry delay, poison, ordering gaps, and orphan reclaim; at-least-once
physical delivery converges to one logical side effect through stable identity.
The resource-server policy now also requires the configured audience, bounded
`iat`/`exp` lifetime (`PT5M` in checked-in runtime defaults) and clock skew, and
mandatory MFA `amr`. With persistence
enabled, every authenticated API request rechecks platform user status; the
live database harness proves active, deprovisioned, and unknown-user paths.
Forward migration V002 now removes outbox lease/ack authority from the web
role, adds a dedicated non-bypass dispatcher login and non-login resolver, and
binds scheduling to active tenant service-account audience/scope metadata. The
live matrix proves no-context denial, one-tenant-per-transaction binding,
bounded scheduling, API/dispatcher privilege separation, and context reset.

The 2026-07-21 local Windows Keycloak 26.7 reference run passes the real
non-production IdP integration criterion. The phase remains **in progress**:
the transcript is ignored, revision-unborn/dirty, and explicitly
`REFERENCE_CONFORMANCE_NOT_PRODUCTION`; the configured Linux CI job has not run
remotely, production identity remains unselected/unproven, and broader G2 exit
criteria remain open. No external dispatcher process is enabled; Phase 9 owns
that runtime and target handoff after this database boundary.

## Objective

Implement the standards-based OIDC resource-server boundary and a real local/reference non-production integration, while retaining production IdP/session ownership as a separate gate; also implement tenant/resource authorization, transaction-local forced RLS, delegated-workload contracts, canonical API contracts, and crash-safe outbox/inbox semantics before tenant-owned product data is added.

## Scope and Non-Goals

**In scope**

- Build the shared API/schema layer and the first Spring platform modules for authn, authz, tenancy, request context, and outbox.
- Define the initial PostgreSQL schema for organizations, projects, environments, users, roles, permissions, service-account metadata, audit-event shell, and outbox tables.
- Wire OpenAPI, Problem Details, request validation, optimistic concurrency/idempotency scaffolding, and RLS context propagation.
- Integrate one real local/reference non-production IdP and verify its browser/resource-server claim, MFA, logout, key rotation, refresh-token rotation/reuse, and revocation behavior.
- Define production browser/BFF or bearer-token session ownership, claim mapping, provisioning/deprovisioning, federation, break-glass, revocation, logout, and approval step-up semantics without treating the reference target as a vendor decision.
- Issue short-lived signed delegated capabilities binding workload audience, actor, tenant/project/environment, incident/run, resources/actions, budgets, nonce and expiry; downstream services never trust duplicated request fields as authority.

**Non-goals**

- No full incident domain yet; that belongs to phase 04.
- No DeepSeek/provider logic, tool execution, RAG, or UI workflow.
- No custom cryptography; token verification must use standards-based IdP libraries or framework support.

## Requirements

### Functional

- `IAM-01`, `IAM-02`, `IAM-03`, `IAM-04`: standards-based identity plus tenant/project/resource authorization and metadata-only credential handling.
- `API-01`, `API-02`, `API-03`: `/api/v1` baseline, Problem Details, validation, pagination/filtering patterns, idempotency keys, and optimistic concurrency conventions.
- `DB-01`, `DB-02`, `EVT-01`: PostgreSQL migrations, forced RLS defense in depth, and transactional outbox/inbox foundations before Kafka.
- `DB-03`: JSONB is limited to versioned schema-flexible envelopes; large evidence bytes belong to the Phase 4 artifact port.
- `DoD-04`, `DoD-26`: authn/authz and API documentation must already be measurable at this layer.

### Non-Functional

- `INV-04`: tenant boundaries must hold at API, DB session, and repository/query level.
- `INV-07`: async publication and duplicate handling must be idempotent by design.
- `INV-08`: no raw API credentials or refresh secrets stored in repo fixtures or default logs.
- `SEC-01`: design explicitly covers IDOR, broken access control, token replay, and owner-bypass RLS failure modes.

## Architecture and Data Flow

1. Request enters `/api/v1` with an IdP-issued access token at the current stateless resource-server boundary; any future BFF/session owner remains a separate production design. The platform never invents its own password/refresh cryptography.
2. Platform API verifies issuer, audience, signature, and token lifetime, then rechecks local platform-user status per persisted request before resolving tenant/project/environment scope with transaction-local forced-RLS semantics. Upstream disablement blocks new login but does not immediately invalidate an already issued access JWT; refresh-token revocation is verified separately.
3. Controller validates payload, applies authorization policy, and dispatches a command into the domain module.
4. Domain change and outbox record commit atomically in PostgreSQL; downstream handlers consume only from the outbox boundary.
5. Responses use Problem Details for errors and versioned contract DTOs from the canonical contract package. Internal calls use workload identity plus a platform-issued delegated capability; actor/scope fields in the body are informational and must match verified claims.

## File Inventory

| Path | Action | Rough size | Test impact |
|---|---|---:|---|
| `packages/contracts/openapi/opsmind-v1.yaml` | MODIFY | 120-220 lines | contract lint and snapshot tests |
| `packages/contracts/json-schema/auth/**` | CREATE | 120-220 LOC | cross-language schema tests |
| `packages/contracts/json-schema/common/**` | CREATE | 80-160 LOC | cross-language schema tests |
| `packages/contracts/fixtures/auth/**` | CREATE | 80-160 LOC | issuer/audience/claim/capability conformance fixtures |
| `services/platform-api/src/main/java/ai/opsmind/platform/identity/**` | CREATE | 240-420 LOC | auth/unit/integration tests |
| `services/platform-api/src/main/java/ai/opsmind/platform/tenancy/**` | CREATE | 220-380 LOC | RLS/authz tests |
| `services/platform-api/src/main/java/ai/opsmind/platform/common/api/**` | CREATE | 140-240 LOC | validation/error tests |
| `services/platform-api/src/main/java/ai/opsmind/platform/messaging/**` | CREATE | 240-420 LOC | outbox/inbox/ordering/reconciliation tests |
| `services/platform-api/src/main/java/ai/opsmind/platform/delegation/**` | CREATE | 160-280 LOC | scoped capability and nonce tests |
| `services/platform-api/src/main/resources/db/migration/V001__identity_tenant_foundation.sql` | CREATE | 120-220 lines | fresh/upgrade migration tests |
| `services/platform-api/src/main/resources/db/migration/V002__outbox_dispatcher_workload.sql` | CREATE | 160-240 lines | forward migration and role-isolation tests |
| `services/platform-api/src/test/java/ai/opsmind/platform/identity/**` | CREATE | 180-320 LOC | authz and replay tests |
| `docs/code-standards.md` | CREATE | 120-220 lines | doc accuracy review |

## Function and Interface Checklist

- The implemented identity endpoint is `GET /api/v1/me`. Resource-administration and internal capability issue/claim endpoints remain planned. Browser login/callback/logout routes belong to the production IdP/BFF boundary when that owner is selected; the Platform API does not expose generic custom login/refresh endpoints.
- Shared DTOs and OpenAPI components for auth requests/responses, Problem Details, pagination, idempotency headers, and version fields.
- Platform interfaces such as `TenantContextResolver`, `AuthorizationPolicyEvaluator`, `OidcTokenVerifier`, `DelegatedCapabilityIssuer`, `ProblemDetailsFactory`, `OutboxPublisher`, and `InboxConsumer`.
- DB/RLS contract: every tenant-owned transaction applies transaction-local tenant context after checkout, fails closed when context setup fails, and clears through commit/rollback/cancellation; application/background roles are non-owner and have no `BYPASSRLS`.
- Request filters/interceptors for correlation IDs, tenant scope, ETag/version handling, and idempotency-key normalization.

## Dependency Map

- Upstream blockers: phases 01 and 02 must provide docs, preflight, workspace, and root commands.
- Downstream consumers: phases 04 through 16 extend these contracts; no later phase may create a parallel schema or alternate authz path.
- Entry baseline: G0.5 approved enterprise OIDC Authorization Code with PKCE and MFA. Keycloak 26.7 is now a passing local/reference target only; production vendor, session/federation, break-glass, and revocation behavior remain phase-owned gates.
- Sequential ownership note: `packages/contracts/**` is the only contract source and can only be extended sequentially later.

## Implementation Steps

1. Define the versioned API baseline and JSON Schema/conformance fixtures so Java, Python and web clients consume one contract surface.
2. Implement Spring modules for the OIDC resource-server policy, identity, tenancy, authorization policy evaluation, delegated-capability contracts, request context propagation, and Problem Details responses; keep production browser/session ownership separate.
3. Create migrations for tenant/identity data, outbox and inbox tables, event aggregate sequence, optimistic versions, and forced RLS. Use transaction-local scope and distinct least-privilege API, dispatcher and background-worker roles.
4. Add service-account and API-credential metadata handling that stores references, scopes, and ownership only; never raw credentials.
5. Implement idempotency-key and optimistic-concurrency scaffolding for later write APIs, including conflict/error mapping.
6. Define the event envelope (`event_id`, tenant, aggregate, aggregate sequence, causation, correlation, occurred-at, schema version, payload digest), transactional inbox uniqueness, gap/out-of-order policy, poison handling and orphan reconciliation.
7. Add tests for invalid issuer/audience/signature, expiry/revocation/deprovisioning, tenant mismatch, resource denial, delegated-capability replay/audience/scope, and owner-bypass regressions.
8. Add alternating-tenant pooled-connection tests covering exception, cancellation, rollback, failed context setup and background jobs; stale context must never survive reuse.
9. Crash-test outbox/inbox at commit, publish, acknowledgement, duplicate and out-of-order boundaries.
10. Publish the initial OpenAPI document and link it from `README.md` and `docs/system-architecture.md`.

## Verification Matrix

| Priority | Scenario | Commands | Evidence |
|---|---|---|---|
| Critical | Cross-tenant, pool-reuse and background-job context attempts deny fail-closed | `scripts/validation/run-phase-03-local-postgres-contract.ps1` locally; focused Maven test in CI | `artifacts/verification/phase-03/authz-rls-pool-matrix.txt` |
| Critical | Real local/reference OIDC enforces PKCE/MFA and resource-server issuer/audience/signature/lifetime policy; refresh-token rotation/reuse/revocation and login invalidation paths deny as specified | `pwsh -NoProfile -File .\scripts\validation\run-phase-03-keycloak-conformance.ps1` | `artifacts/verification/phase-03/identity-delegation.txt` |
| High | OIDC outbound discovery/JWKS uses RS256, bounded HTTP timeouts, and one request per exact target URI/instance/interval while keeping discovery and JWKS targets independent | source review, focused `PlatformSecurityPropertiesTest` and `OidcOutboundRequestRateLimiterTest`, and static trust-foundation validation | unit/source validation output |
| High | Schema-v2 identity evidence binds current profile/source and packaged JAR digests, uses independent refresh families, and publishes only after verified cleanup | `pwsh -NoProfile -File .\scripts\validation\verify-phase-03-keycloak-evidence.ps1` after a live run | local `identity-delegation.txt`; bounded sanitized `identity-delegation-failure.txt` on BLOCK only |
| Critical | Delegated-capability audience/scope/expiry/budget/nonce contracts remain bounded | focused Java validator tests; no live delegation claim from the Keycloak artifact | test output only until a dedicated integration artifact exists |
| Critical | Outbox/inbox survives duplicate, reorder and crash windows | guarded `TransactionalOutboxIntegrationTest` and `TransactionalInboxIntegrationTest` | `artifacts/verification/phase-03/outbox-inbox.txt` |
| Critical | Web append authority is separate from tenant-bound dispatcher lease/ack authority | guarded `OutboxDispatcherWorkloadIntegrationTest` plus SQL role matrix | `artifacts/verification/phase-03/outbox-inbox.txt` |
| High | Fresh migration produces an RLS-enabled schema and outbox tables | packaged Flyway app plus `scripts/validation/run-phase-03-postgres-contract.sh` | `artifacts/verification/phase-03/migration-and-rls.txt` |
| Medium | API contracts and schemas stay versioned and lintable | `spectral lint packages/contracts/openapi/opsmind-v1.yaml` plus cross-language fixture tests | `artifacts/verification/phase-03/contracts.txt` |

## Success / Exit Gate

- [x] Identity, tenancy, authz, Problem Details, and outbox modules exist with no duplicate contract definitions outside their owned paths.
- [x] PostgreSQL migration `V001__identity_tenant_foundation.sql` proves forced tenant isolation and atomic outbox creation in verification transcripts.
- [x] Idempotency and optimistic-concurrency conventions are documented in the OpenAPI baseline and exercised by tests.
- [x] `docs/code-standards.md` and linked architecture docs describe the boundary rules that later phases must follow.
- [x] Alternating-tenant pool/context suite, non-owner/`BYPASSRLS` checks and background-job matrix pass 100% before any new tenant table is accepted.
- [x] One real local/reference non-production IdP integration passes; no custom refresh-token cryptography or unresolved reference claim mapping remains. This does not authorize a production vendor or close the broader identity/session gate.
- [x] Outbox/inbox crash, duplicate, ordering and orphan-reconciliation tests pass with one logical local side effect per event.
- [ ] Production enterprise IdP/session conformance and the configured remote Linux identity job pass with immutable revision-bound evidence.

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---:|---|
| Production IdP/session requirements remain unresolved | Medium | High | keep verifier behind a port; retain Keycloak as reference evidence, not a vendor decision |
| RLS is configured but bypassable by owner role | Medium | Critical | force RLS where required and add negative owner-bypass tests |
| Shared contracts fragment across languages | Medium | High | make shared schema package and OpenAPI the only allowed contract sources |

## Security Considerations

- Never store or log raw refresh tokens, bearer tokens, or external secret values.
- OIDC discovery/JWKS uses 500-millisecond connect/read timeouts and a
  per-exact-target, per-instance minimum interval (`PT1S` default; validated
  100 milliseconds–1 minute). Repeated same-target calls fail closed, so key
  rotation can temporarily reject tokens until the interval elapses.
- Use “refresh-token revocation” precisely. Upstream user disablement denies new
  login but a pre-issued stateless access JWT remains accepted. Its issuance
  lifetime is 300 seconds and timestamp enforcement includes configured skew:
  330-second reference and 360-second checked-in policy upper bounds. The live
  denial horizon is unmeasured. Local platform-user deprovisioning is checked
  per request.
- Treat service-account metadata as sensitive ownership data even without the raw credential.
- Deny by default when tenant scope is missing, malformed, or ambiguous.

## Rollback and Forward-Fix Notes

- Database schema changes are forward-fix first; do not edit an applied shared migration in place.
- If a contract shape is wrong, version it explicitly and update all generated clients/consumers in the same change set.
- If RLS logic proves unsafe, block downstream phases and patch the policy before adding any new tenant-owned tables.

## Next Phase

Phase 04 builds incident CRUD, timeline, state machine, evidence containers, and immutable audit behavior on top of the trust and contract foundation created here.
