# Testing Strategy

## Purpose

Testing proves behavior at the boundary where a defect would matter. Passing happy-path unit tests is not production evidence for tenant isolation, model validity, durable workflows, or external writes.

## Test Principles

- Deterministic logic is tested without infrastructure where possible.
- External inputs—including model responses—are malformed until validated.
- Denial paths receive the same attention as successful paths.
- Tests preserve real domain behavior; mocks isolate boundaries, not desired outcomes.
- A fixture proves a contract shape, not a live integration.
- Every release claim names the suite and artifact that proves it.
- Flaky tests are defects and cannot be silently retried into green status.

## Test Layers

| Layer | Primary proof |
|---|---|
| Unit/property | State transitions, canonicalization, policy predicates, budgets, parsers, redaction |
| Architecture | Module dependencies and forbidden trust-zone imports |
| Contract | OpenAPI/JSON Schema compatibility and consumer/provider fixtures |
| Persistence | Migrations, constraints, RLS, transaction isolation, indexes, outbox/inbox |
| Component | Service behavior with real database/object-store/provider stub |
| Integration | Cross-service identity, incident, evidence, AI, tool, and audit flow |
| Live conformance | Non-production IdP, DeepSeek, and first connector behavior |
| End-to-end | Operator task through UI and authoritative backend state |
| Evaluation | RCA, citation, safety, calibration, latency, cost, and human comparison |
| Security | Threat scenarios, secret exposure, dependency/container/IaC and authorization tests |
| Reliability | Crash/restart, duplicate, reorder, timeout, partition, saturation, ENOSPC, restore |

## Critical Scenario Families

### Identity and tenant isolation

- Missing, expired, wrong-audience, and downgraded sessions; authorization-code,
  TOTP, and refresh-token replay/invalidity paths are tested separately from
  general bearer-token replay controls.
- Membership revoked during an investigation.
- Cross-tenant ID enumeration through API, search, vector ranking, artifacts, exports, jobs, and error messages.
- Connection-pool reuse after success, exception, cancellation, and timeout.
- Service role accidentally capable of bypassing forced RLS.

### Evidence and RAG

- Artifact digest mismatch, truncated stream, malware result, orphan metadata, and missing object.
- ACL applied before candidate ranking.
- Citation points to the exact authorized version and content digest.
- Immediate revoke blocks new reads and generation epoch excludes stale index entries.
- Deletion produces receipts and invalidates dataset/model lineage.

### Model provider

- Timeout, throttling, provider outage, invalid JSON, empty content, truncated response, unknown fields, hallucinated tool arguments, repeated tool call, and cost/token exhaustion.
- Thinking/tool continuation survives an Activity boundary without duplicating a side effect.
- Redaction and egress policy deny disallowed evidence even when a credential is configured.
- Provider-neutral conformance ensures the domain does not depend on DeepSeek-specific response objects.

The Phase 5 offline checkpoint currently covers 85 passing Python tests for strict
request/response fixtures, signed request scope/TTL/nonce replay, evidence-bound
classification and citations, bounded declared/chunked ingress, disabled-by-
default configuration, exact outbound host/pricing/numeric gates, global
deadlines, cumulative pre/post-call budget enforcement, provider error/jittered
retry mapping, strict adapter normalization, and contiguous terminal stream
assembly and in-process replay compatibility. Four environment-gated
PostgreSQL tests additionally target global nonce replay, exact successful
response replay, cross-replica reservation serialization, tenant RLS, and
expired-lease/full-overage charging. The capacity-qualified PostgreSQL 18.4
runner now proves V004 plus all four adapter cases and cleanup locally. This is
reference evidence, not immutable release evidence.
None of these tests authorize live DeepSeek egress.

### Workflow and messaging

- Crash before and after transaction commit, outbox claim, workflow start, Activity completion, and inbox acknowledgment.
- Duplicate and out-of-order events.
- Worker build upgrade replay against golden histories.
- Cancellation, incident closure, membership revocation, and budget exhaustion mid-workflow.

### Exact-action execution

- Preview changes before approval.
- Target version changes after approval.
- Approval expires, is revoked, or belongs to another actor/tenant.
- Same execution request is delivered concurrently.
- Target accepts a write but response is lost.
- Provider does not support idempotency and requires discovery/reconciliation.
- Compensation would exceed the original approved scope.
- Audit or intent store reaches capacity before write admission.

## Evaluation Suites

Three deterministic smoke cases verify harness mechanics early. Ten working scenario families ensure breadth by final verification. Neither set alone supports p95/p99, percentage-safety, calibration, or human-benefit claims.

Release evaluation uses a separately governed, independently held-out corpus with preregistered metrics, justified sample size, uncertainty intervals, incident-family/temporal separation, and a qualified human baseline. See [Evaluation Strategy](./evaluation-strategy.md).

## Performance and Load

The approved starting load model is one organization, 25 concurrent
investigations, 500 evidence events per second, and 120 model requests per
minute. The initial service targets are 99.9% availability and 500 ms API p95.
Tests must validate rather than assume these values and distinguish:

- API request latency;
- evidence ingestion throughput;
- retrieval latency and candidate volume;
- queue and workflow wait time;
- provider time-to-first-token and completion latency;
- end-to-end time to useful hypothesis;
- tool preview and execution latency;
- storage, connection, worker, and provider saturation.

Report distributions and sample counts. Do not report percentiles that the sample cannot resolve.

## Test Data

- Default local/CI data is synthetic and deterministic.
- Real incident data requires explicit authorization, classification, redaction, retention, and training eligibility.
- The approved baseline retains incidents for 365 days, evidence for 90 days,
  and audit for 730 days; requires deletion within 24 hours; keeps data in
  Singapore; and permits training only through explicit opt-in.
- Test identities and tenants are clearly synthetic.
- Secrets and production tokens are prohibited.
- Held-out evaluation data is access-controlled and separated from development fixtures.
- Deletion and withdrawal propagate to every derived data product.

## Evidence Standard

Every test artifact records code revision, contract version, environment identity, dependency versions, configuration digest, dataset/scenario version, command, start/end time, result, and relevant logs. Sensitive content is redacted. CI retains immutable evidence according to approved policy.

## Phase 1 Checks

Phase 1 has a composite governance suite plus narrow suites for each boundary:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\governance\test-phase-01-governance.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\storage\test-storage-guards.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\governance\test-product-production-contract.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\governance\test-project-secret-scan.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\governance\test-default-evidence-safety.ps1
```

The portable shell suite is `./scripts/storage/test-portable-storage-guards.sh`. Together these prove forced capacity pass/block behavior, safe roots, multi-filesystem checks, Windows/POSIX path normalization, no unauthorized root creation/evidence publication, typed and duplicate-safe G0.5 validation, strict UTF-8/JSON lexing, transcript-injection resistance, exact index and external-artifact scanning, namespaced config credentials, historical sensitive paths, and fail-closed current/history binary handling. Documentation validation checks local links and file boundaries. They do not prove application behavior; no application runtime exists in Phase 1.

## Phase 3 Identity Reference Conformance

After capacity/root preflight, the executable local command is:

```powershell
pwsh -NoProfile -File .\scripts\validation\run-phase-03-keycloak-conformance.ps1
pwsh -NoProfile -File .\scripts\validation\verify-phase-03-keycloak-evidence.ps1
```

The Platform API unit matrix models 16 concurrent calls to one JWKS URI and
requires exactly one outbound call in the one-second interval, followed by a
successful call when the interval elapses. It also proves discovery and JWKS
URIs are tracked independently. Property tests cover the `PT1S` default and
reject a 99-millisecond value; production code enforces the full
100-millisecond–1-minute range. The static trust-foundation matrix pins the
environment/application/Compose default plus RS256 and limiter wiring. These
tests prove a per-target, per-instance bound, not a cluster-wide rate limit.

The 2026-07-21 Windows run against digest-pinned Keycloak 26.7 passed HTTPS
discovery, Authorization Code with PKCE S256, direct-grant denial, wrong-
verifier denial, TOTP enrollment-versus-MFA separation, MFA `amr`, exact same-
code/same-timestep TOTP replay denial, RP-initiated logout,
refresh-after-logout denial, Platform API anonymous/missing-MFA/tampered-token
denial, JWKS rotation refresh, old refresh-token reuse denial after rotation,
an independent second refresh session as the pre-revocation positive control,
refresh-token revocation, and disabled-user new-login denial. It also confirmed that a pre-issued stateless access JWT
remains accepted after upstream disable. The token's issuance lifetime is 300
seconds; timestamp enforcement includes `PT30S` skew in the harness and `PT60S`
in checked-in defaults, yielding policy upper bounds of 330 and 360 seconds.
The run asserts those policy inputs and immediate post-disable acceptance; it
does not live-measure the denial horizon. Platform-user deprovisioning remains
a separate per-request database check and denies immediately.

The schema-v2 runner/verifier contract requires
`ExistingJwtAfterIdpDisable=PREISSUED_JWT_STILL_ACCEPTED`,
`RefreshTokenRotationReuseDenied=PASS`,
`RefreshTokenIndependentSessions=PASS`,
`RefreshTokenPreRevocationControl=PASS`,
`AccessTokenLifetimeSeconds=300`, `ConfiguredClockSkewSeconds=30`,
`MaximumResidualAcceptanceSeconds=330`, and
`DisableToDenialHorizon=NOT_LIVE_MEASURED`. It also binds a manifest digest of
profile/source inputs and the exact packaged JAR digest, publishes atomically
only after verified cleanup, and rejects stale evidence. The live schema-v2
artifact and its separate profile/JAR verifier passed on 2026-07-21.

A forced packaging failure separately proves the failure path: no success
artifact is emitted, cleanup completes, and a bounded
`identity-delegation-failure.txt` contains only sanitized diagnostics. CI
uploads both candidate paths with `if: always()`; only the success artifact is
accepted by the evidence verifier.

The transcript is ignored local evidence with
`EvidenceScope=REFERENCE_CONFORMANCE_NOT_PRODUCTION`, `CodeRevision=UNBORN`,
and `WorkspaceDirty=YES`; it is not production or immutable release evidence.
The Linux `identity-conformance` job is configured in
`.github/workflows/pr-quality.yml` but has no claimed remote run. This suite
does not prove federation, break-glass, state/nonce assurance, browser/BFF
session ownership, general bearer replay prevention, delegated capabilities,
or immediate access-token revocation.

## Phase 4 Incident Checkpoint 4A

The local reference gate is intentionally split so a static fixture pass cannot
stand in for live transaction behavior:

```powershell
node .\scripts\validation\validate-phase-04-incident-contracts.mjs
powershell.exe -NoProfile -File .\scripts\validation\run-phase-04-domain-tests.ps1
powershell.exe -NoProfile -File .\scripts\validation\run-phase-04-local-postgres-contract.ps1
```

The static gate parses eleven schemas and fourteen fixtures, resolves 128 local
references, checks six OpenAPI operations, and inspects V003/audit wiring. The
focused domain gate runs seven controller/domain classes (25 tests) with zero
failure, error, or skip. The full Maven suite currently discovers 86 tests;
guarded database cases are also run separately rather than counted as a local
skip-based success.

The disposable PostgreSQL 18 gate packages the same JAR used by the refreshed
Keycloak evidence, proves both V001/V002-to-V003 upgrade and fresh V001-V003,
then runs live CRUD, replay, RLS, cross-tenant privacy, authorization-revocation
serialization, one-winner concurrency, semantic timeline/audit validation,
append immutability, audit-chain recomputation, and SQL contracts. Its rollback
case forces a real outbox duplicate after timeline/audit append and verifies no
incident, timeline, audit, or idempotency effect committed. Evidence is:

- `artifacts/verification/phase-04/incident-contracts.txt`
- `artifacts/verification/phase-04/incident-domain.txt`
- `artifacts/verification/phase-04/incident-crud.txt`
- `artifacts/verification/phase-04/audit-and-concurrency.txt`

All four artifacts are local/reference evidence. They record an unborn/dirty
worktree and cannot satisfy immutable remote release proof. Checkpoint 4A does
not cover evidence-object lifecycle, postmortems, provider/tool behavior, UI,
load objectives, production IdP, or the full Phase 4/G2 exit gate.

## Release Gate

Release requires all required suites green, zero unresolved critical security finding, accepted migration and rollback evidence, provider/IdP/connector conformance, held-out evaluation within preregistered bounds, successful restore/reconciliation drill, and operator runbook exercise.

## Verification Evidence

Evidence is written under `artifacts/verification`, `artifacts/evaluation`, `artifacts/security`, and `artifacts/dr`, then uploaded by CI. Local ignored artifacts remain diagnostic/reference evidence until an executed CI run binds them to an immutable revision. The final audit maps every Definition of Done item to a current artifact rather than a plan statement.

## Remaining Test Decisions

G0.5 no longer has pending test-envelope decisions. Frameworks, coverage
thresholds, matrix versions, supported browsers, and environment-specific test
responsibilities are selected in Phase 2; release corpus size and statistical
power are preregistered in Phase 8. Phase 16 must also reconcile and prove the
120-minute service RTO against the four-hour artifact restore target.
