---
phase: 4
title: "Incident Control Plane and Audit Ledger"
status: in-progress
priority: P1
dependencies: [2, 3]
---

# Phase 4: Incident Control Plane and Audit Ledger

## Context Links

- [Plan](./plan.md)
- [Phase 3](./phase-03-contracts-data-identity-and-tenant-foundation.md)
- [Phase 4 scout report](./reports/scout-260721-phase-04-incident-control-plane.md)
- [Checkpoint 4B evidence-record plan](./phase-04b-bounded-evidence-record-ingress.md)
- [Requirements traceability](./research/master-prompt-requirements-traceability.md)
- [Architecture and security research](./research/researcher-01-architecture-security.md)
- [Active blockers](../../docs/blockers.md)

## Overview

This phase introduces the first product domain: incidents, their immutable
timeline, tamper-evident audit chain, and stable outbox events. The full phase
also owns evidence metadata, artifact lifecycle, resolution, closure, and a
postmortem shell. Later AI, tool, workflow, RAG, and UI phases consume these
contracts; they do not mutate incident tables directly.

Implementation starts with checkpoint **4A: authorized incident write ledger**.
It creates, reads, and transitions an incident while committing the aggregate,
timeline, audit, outbox, and idempotency result in one PostgreSQL transaction.
This proves the highest-risk local invariants without fabricating an artifact
backend while B-006, B-008, and B-012 remain active.

Phase 3 hands shared-contract ownership to this sequential local slice. Phase 3
remote CI and production IdP gates remain open, so Phase 4 cannot close G2.
Adding Platform API sources also invalidates the current source/JAR-bound local
identity transcript; rerun that reference verifier after checkpoint 4A lands.

## Objective

Deliver an authoritative, tenant-isolated incident system of record whose write
effects are concurrency-safe, replay-safe, append-only, correlated, and ready
for later evidence-backed RCA without giving AI or tools direct mutation access.

## Locked Design Decisions

### API and authority

- Canonical routes are nested under
  `/api/v1/organizations/{organizationId}/projects/{projectId}/incidents`.
  Flat ID-only routes are deferred because RLS needs a verified tenant context
  before lookup; no request body, header, or token tenant claim is authority.
- `incident:read` is required for reads; `incident:write` is required for
  mutations. Active database membership remains authoritative after scope
  verification.
- `ADMIN` and `SRE` may mutate. `ADMIN`, `SRE`, `DEVELOPER`,
  `SECURITY_REVIEWER`, and `VIEWER` may read. `AI_AGENT` receives no direct
  public incident authority; later phases must use a verified delegated
  capability.
- Invisible organization, project, or incident resources return the same
  non-enumerating `404` response. Deprovisioned identities fail closed.
- Incident deletion is not CRUD here. `CLOSED` is the terminal operational
  state; retention/privacy deletion belongs to the governed lifecycle.

### Domain vocabulary and transitions

- Severity is exactly `SEV1`, `SEV2`, `SEV3`, or `SEV4`.
- Status is exactly `OPEN`, `INVESTIGATING`, `AWAITING_APPROVAL`, `MITIGATING`,
  `RESOLVED`, or `CLOSED`.
- Legal transitions are:

| From | To |
|---|---|
| `OPEN` | `INVESTIGATING` |
| `INVESTIGATING` | `AWAITING_APPROVAL`, `MITIGATING`, `RESOLVED` |
| `AWAITING_APPROVAL` | `INVESTIGATING`, `MITIGATING` |
| `MITIGATING` | `INVESTIGATING`, `RESOLVED` |
| `RESOLVED` | `INVESTIGATING`, `CLOSED` |
| `CLOSED` | none |

Every transition requires a bounded human-readable reason. `RESOLVED` requires
root cause and resolution summary. Reopening clears the current resolution;
historical values remain in timeline/audit. PostgreSQL enforces the graph as a
defense behind Java domain validation.

### Transactions, correlation, and audit

- Every mutation requires `Idempotency-Key`; every transition/patch additionally
  requires a strong numeric `If-Match` ETag.
- The request digest binds a schema version, actor, method, canonical nested
  path, canonical body, and expected version. Same key plus different identity
  or operation fails `409`; exact replay returns the original semantic response.
- One transaction resolves issuer/subject, applies tenant context, verifies
  membership/role/project, claims idempotency, mutates incident state, appends
  timeline/audit/outbox, completes the cached response, and commits.
- A UUID operation ID joins incident response, timeline, audit, and outbox.
  The externally supplied safe trace string remains separate metadata because
  it is not necessarily a UUID.
- PostgreSQL assigns each tenant audit sequence, serializes appenders with a
  tenant-scoped advisory transaction lock, derives `previous_digest`, and
  computes SHA-256 from the stored canonical fields. Callers cannot forge the
  chain. Audit and timeline updates/deletes are denied.

## Scope and Non-Goals

### Checkpoint 4A in scope

- Incident create, detail read, explicit status transition, and timeline read.
- Canonical OpenAPI and Draft 2020-12 schemas for those operations plus the
  stable incident/timeline/audit event shapes.
- `V003__incident_control_plane.sql` with incident/timeline tables, constraints,
  forced RLS, least privilege, query indexes, transition guard, and audit-chain
  hardening. V001/V002 remain immutable.
- Java 21/Spring JDBC domain, authorization, transaction orchestration,
  idempotency, ETag, timeline, audit, and outbox integration.
- Unit, controller, real PostgreSQL, race, rollback, migration, RLS, and static
  contract tests with bounded sanitized local evidence.

### Deferred until later Phase 4 checkpoints

- List/search/pagination, generic patch, owner/alert assignment, resolve/close
  UX, postmortem authoring, and evidence attachment APIs.
- Evidence metadata/lifecycle implementation, malware/DLP scanning, provider
  adapter, authenticated bounded streaming, tombstone/restore/purge receipts,
  and bidirectional orphan reconciliation.
- No MinIO profile, filesystem fake, durable provider URL, or direct presigned
  GET. PostgreSQL and object storage are coordinated through pending/finalized
  metadata plus reconciliation, never described as one atomic transaction.

### Phase-wide non-goals

- No DeepSeek invocation, tool execution, Temporal workflow, broad frontend,
  remediation, approval binding, RAG ingestion, or Kafka.

## File Inventory for Checkpoint 4A

| Path | Action | Ownership |
|---|---|---|
| `packages/contracts/openapi/opsmind-v1.yaml` | MODIFY | canonical API |
| `packages/contracts/json-schema/incidents/**` | CREATE | incident contracts |
| `packages/contracts/json-schema/audit/**` | CREATE | audit contract |
| `packages/contracts/fixtures/incidents/**` | CREATE | positive/negative fixtures |
| `services/platform-api/src/main/java/ai/opsmind/platform/incident/**` | CREATE | incident boundary |
| `services/platform-api/src/main/java/ai/opsmind/platform/audit/**` | CREATE | audit append boundary |
| `services/platform-api/src/main/resources/db/migration/V003__incident_control_plane.sql` | CREATE | additive migration |
| `services/platform-api/src/test/java/ai/opsmind/platform/incident/**` | CREATE | domain/API tests |
| `services/platform-api/src/test/java/ai/opsmind/platform/testing/**` | MODIFY | real DB fixtures only as needed |
| `scripts/validation/validate-phase-04-incident-contracts.mjs` | CREATE | offline static gate |
| `scripts/validation/run-phase-04-local-postgres-contract.ps1` | CREATE | disposable DB evidence |
| `scripts/validation/run-phase-04-postgres-contract.sh` | CREATE | portable DB evidence |

## Implementation Steps

1. Publish the scoped API, incident/timeline/audit schemas, and deterministic
   positive/negative fixtures; pin validation to the existing lockfile rather
   than invoking an uninstalled global Spectral binary.
2. Add V003 with typed constraints, composite tenant/project keys, forced RLS,
   explicit grants, append-only timeline, state/version guards, and a database-
   computed tenant audit chain.
3. Implement the status machine and one transaction coordinator using existing
   `TransactionTemplate`, tenant context, idempotency, optimistic concurrency,
   exact-byte outbox, Problem Details, and conditional persistence patterns.
4. Add create/detail/transition/timeline controllers and reconstruct `Location`,
   ETag, and operation headers on idempotent replay.
5. Prove authorization, cross-tenant invisibility, transition matrix, replay
   conflicts, stale ETags, rollback, one-winner races, append immutability, and
   audit-chain continuity against real PostgreSQL.
6. Emit schema-versioned local/reference evidence with revision/dirty state,
   source/migration/JAR digests, commands, versions, timing, cleanup, and only
   bounded sanitized failure diagnostics.
7. Rerun Phase 3 local identity evidence because its source/JAR binding becomes
   stale, then sync architecture/testing/security/progress docs to proven facts.

## Checkpoint 4A Acceptance Criteria

- [x] Authorized create returns `201`, `Location`, `ETag: "0"`, operation ID,
  and an `OPEN` incident with exactly one timeline, audit, and outbox append.
- [x] Authorized detail read returns the resource and current ETag.
- [x] Legal transition with `If-Match: "0"` returns the next version/ETag;
  missing, malformed, stale, and illegal transitions fail without side effects.
- [x] Exact idempotent replay creates no new effect; same key with another body,
  path, version, or actor returns `409`.
- [x] Missing scope, disallowed role, revoked membership, deprovisioned actor,
  wrong project, and cross-tenant access fail closed; invisible resources do not
  reveal existence.
- [x] Forced audit/outbox failure rolls back incident, timeline, idempotency, and
  all append effects.
- [x] Two concurrent requests with one expected version produce one mutation and
  one logical event; timeline ordering and audit chain remain linear.
- [x] Runtime and migration paths cannot update, delete, or truncate timeline or
  audit rows; forged audit sequence/digest input cannot alter computed values.
- [x] Fresh V001/V002/V003 and upgrade V001/V002 -> V003 both pass; V001/V002
  checksums remain unchanged.
- [x] Static contracts, focused tests, complete Maven suite, layout/doc checks,
  secret scan, and both C:/D: capacity guards pass.

Checkpoint 4A status: **complete as local/reference evidence; Phase 4 remains in progress**.
Evidence: `artifacts/verification/phase-04/incident-contracts.txt`,
`incident-domain.txt`, `incident-crud.txt`, and `audit-and-concurrency.txt`,
plus Phase 3 identity/RLS evidence and the repository governance checks. The
current workspace is unborn and dirty, and the latest body-limit source/test
patch requires a fresh capacity-guarded Maven/PostgreSQL rerun before any
immutable release claim. No DeepSeek key, registry credential, or package
publication is part of this checkpoint.

Checkpoint 4B is implemented locally as the minimum prerequisite for real Phase
7 clients. It persists only bounded, normalized, redacted Tool Gateway evidence
records and atomically links them to the investigation ledger. Static gates and
the full offline Platform suite pass; live V007 PostgreSQL CI remains required
before completion. It does not implement or claim the large-object lifecycle
deferred above.

## Verification Matrix

| Priority | Scenario | Command | Evidence |
|---|---|---|---|
| Critical | Contract/schema/fixture invariants | `node scripts/validation/validate-phase-04-incident-contracts.mjs` | `artifacts/verification/phase-04/incident-contracts.txt` |
| Critical | Domain/controller/HTTP binding behavior | `mvn --batch-mode --no-transfer-progress -f services/platform-api/pom.xml -Dtest=IncidentStateMachineTest,IncidentControllerTest,IncidentControllerHttpTest,IncidentJsonCodecTest,IncidentQueryServiceTest,IncidentRequestIdentityTest,IncidentRuntimeValuesTest test` | `artifacts/verification/phase-04/incident-domain.txt` |
| Critical | Live CRUD/RLS/idempotency | `pwsh -NoProfile -File scripts/validation/run-phase-04-local-postgres-contract.ps1` | `artifacts/verification/phase-04/incident-crud.txt` |
| High | Audit/rollback/concurrency | same disposable PostgreSQL matrix | `artifacts/verification/phase-04/audit-and-concurrency.txt` |
| Release | Immutable bound manifest | standalone Phase 4 evidence verifier | `artifacts/verification/phase-04/phase-04-verification.txt` |

Dirty or unborn local evidence is reference-only. Remote immutable-revision CI,
production IdP, and supported evidence backend evidence remain separate gates.

## Full Phase Exit Gate

- [ ] Checkpoint 4A acceptance criteria pass with independently verified evidence.
- [ ] Incident list/patch/owner/alert, resolution/closure, evidence reference, and
  postmortem contracts are implemented and exercised.
- [ ] Evidence upload/finalize/read/tombstone/pre-purge restore/purge receipt and
  orphan reconciliation pass against a supported backend decision.
- [ ] A citation resolves immutable bytes by digest only after current tenant,
  project, lifecycle, and authorization-epoch checks.
- [ ] Architecture/runbooks explain downstream contract/event consumption,
  recovery, retention, and audit verification.
- [ ] Phase 2/3 prerequisite release gates and remote revision-bound CI evidence
  pass; only then may Phase 4 and G2 be marked complete.

## Risks and Rollback

- Contract mistakes are corrected additively. Applied migrations are never
  renamed or edited; forward-fix migrations retain old-reader compatibility.
- If a transition is too permissive, disable that edge and run the complete
  transition/complement matrix before re-enabling it.
- If audit verification fails, block incident writes, preserve the append-only
  rows, and investigate; never rewrite history to manufacture continuity.
- If storage capacity fails, stop heavy work. No automated deletion is allowed.

## Next Phase

After the full Phase 4 gate, Phase 5 adds the provider-neutral AI runtime and
DeepSeek adapter without taking incident, authorization, or audit ownership.

## Unresolved Questions

- B-006/B-008/B-012 still block supported evidence lifecycle and production
  durability claims. Checkpoint 4A deliberately does not choose a fake backend.
