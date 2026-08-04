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

### Phase 8B deterministic evaluation checkpoint

The additive evaluation slice implements three strict, training-ineligible
contracts. Scenario A completes after one read-only latency result. Scenario B
is `ABSTAINED` with no tools, citations, or hypotheses. Scenario C uses two
opposing read-only evidence results and requires digest-bound counter-evidence
plus cautious confidence. Strict schema and fixture-digest checks cover the
three implemented families while seven families remain reserved.

Platform V008 is a rolling-compatible expand migration: it accepts both the
legacy V007 event shape and strictly validated response-bearing writes. The
V007-to-V008 upgrade proof checks that a historical accepted event remains
byte-stable and that a legacy writer can still append after V008; evaluator
exports still require response-bearing events. Tool Gateway V002
persists the observed tool/action/risk, connector ID/profile, and digest of the
manifest bytes selected at runtime. The disposable exporter uses a non-login
least-privilege view owner and a separate non-inheriting evaluator; exact
transaction-local organization/project/incident/run/actor scope is enforced by
every security-barrier view. SQL and Node bounds reject extra rows, ambiguous
invocations, foreign identities, unsafe values, or exports above 4 MiB. The
projection contains accepted normalized analysis and metadata/digests only—no
prompts, provider reasoning, credentials, raw connector bodies, or evidence
content—and distinguishes raw-byte from canonical JSON digests. It derives tool
executions from durable intent/receipt/audit/evidence bindings, requires the
three durable evidence digests to agree, and sums all accepted invocation cost.
Malformed UTF-8 and duplicate evidence digests fail closed.

The exact CI evaluation command passes 61/61. The Phase 8 validator reports six
schemas, ten families/three implemented, held-out and human inputs
`UNAVAILABLE` with zero cases, three canonical results, eight metrics, four
negative cases, zero errors, checkpoint `PASS`, and phase exit `BLOCK`.
Revision-bound A/B/C, same-job attestation, exact-revision artifact, and two
independent supervision reviews pass. This checkpoint is not a held-out or
human study and cannot support production accuracy, calibration, population
latency, p95, or benefit claims.

### Incident activity timeline checkpoint

The legacy timeline representation remains `incident:read` + `READ` and reads
only `incident_timeline_events`. The opt-in vendor representation requires
`incident:analyze` + `ANALYZE`; authorization runs before incident lookup.
Integration tests prove exact organization/project/incident predicates on both
branches, same/cross-tenant denial, a forward-only v2 cursor, and absence of
payloads, free text, credentials, evidence/tool identifiers, and canonical
evidence from response bytes.

Platform migrations now run through V009. PR Quality run `30257587569`,
PostgreSQL job `89950772823`, and artifact `8650178111` prove fresh and upgrade
paths, invalid-index recovery, bounded query plans, legacy writes, cleanup, and
the 3/3 route matrix. The fixture contains 60,600/61,206 ledger rows; append
p95 regression is 11.66%/4.02%, vendor p95 is 2.563/1.466/1.533 ms, and the
indexes cost 95.70 bytes/source row and 10.76% of table bytes. These pass the
plan's <=500 ms, <=20%, <=256 bytes/row, and <=100% test gates. They are not
production latency, SLO, or rollout evidence.

`OPSMIND_EPHEMERAL_DB=true` is a guard against running destructive harness
steps without explicit intent. It does not isolate a database; the job/runner
must provision and verify a disposable database boundary independently.

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

- V014 metadata registration: owner-bound run scope, idempotency drift,
  digest/byte-count bounds, authorization epoch, pending-read denial, and no
  projected storage key/body.
- Forced RLS, append-only UPDATE/DELETE/TRUNCATE denial, atomic initial
  event/audit binding, and fresh plus V013-to-V014 migration contracts.
- V015 fresh and V014-to-V015 proofs: forced-RLS attempt authority, valid
  zero/active attempt shapes, five-second-to-five-minute leases, one concurrent
  claim winner, probe-after-explicit-uncertainty, quarantine of an expired
  unsettled claim, immediate retry after a definitive `FAILED` attempt,
  permanent automated reclaim denial after `ORPHANED`, stale settlement denial,
  cross-tenant denial, exact replay, and rollback when the STORED event/audit
  pair is absent.
- Storage unit contracts: one conditional PUT, no SDK retry, precomputed
  SHA-256, exact EOF/digest, canonical KMS response identity, 1,024-byte opaque
  version bound, denied-probe ambiguity, and distinct retryable versus
  post-PUT-quarantined short/long/mismatched stream outcomes.
- Upload orchestration tests prove current authorization before claim and
  settlement, no object I/O inside a database transaction, probe-before-retry,
  and attempt-level failure/uncertain/orphan outcomes that never make an
  artifact readable.
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
- Connector admission uses a global 32-operation ceiling plus a four-operation
  per-tenant ceiling keyed from verified capability scope. Same-tenant requests
  across different projects share the cap; a saturated tenant cannot consume
  another tenant's allowance. Tests cover fail-fast backpressure, success and
  failure release, timeout with ignored interruption, cancellation before task
  start, and idle-slot eviction.

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

Phase 8 implements the three deterministic smoke contracts described above.
Ten working scenario families remain required by final verification. Neither
three authored cases nor ten authored families alone support p95/p99,
percentage-safety, calibration, or human-benefit claims.

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
The Linux `identity-conformance` job passes in revision-bound PR-quality
CI. This remains non-production reference conformance; the suite
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

The static gate parses 24 schemas and 32 fixtures, evaluates 19 fixture cases
(10 positive and nine negative), resolves 237 local references, checks 11
OpenAPI operations, and inspects V003/audit wiring. Guarded database cases run
separately rather than counting local skips as success.

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
- `artifacts/verification/phase-04/incident-resolution-closure.txt`

The first four artifacts are historical local/reference evidence. They record
an unborn/dirty worktree and cannot satisfy immutable remote release proof. The
closure artifact is exact-head CI evidence but remains non-production.
Checkpoint 4A does not cover evidence-object lifecycle, postmortems,
provider/tool behavior, UI, load objectives, production IdP, or the full Phase
4/G2 exit gate.

### Incident list pagination checkpoint

The collection-read gate extends the Phase 4 validator and Platform tests with
closed six-field fixtures, HTTP input boundaries, authorization-before-query,
cross-tenant and revoked-membership invisibility, exact-status filtering,
timestamp ties, arbitrary seek boundaries, live-view mutation behavior, and
zero-write snapshots. Disposable PostgreSQL coverage applies V001-V016 and
V015-to-V016, proves the two concurrent indexes valid/ready, repairs a failed
build using a non-superuser migration role, and checks bounded filtered and
unfiltered plans. Static or unit output alone is not database proof.

### Incident resolution-to-closure checkpoint

Contract fixtures prove the CLOSED request/event shape, and controller tests
reject client-supplied resolution fields on closure. Exact-head PostgreSQL CI
executes `OPEN -> INVESTIGATING -> RESOLVED -> CLOSED`, retained resolution
data, linked timeline/audit/outbox event IDs and sequence, byte-identical
idempotent replay, stale-ETag `412`, and every outgoing CLOSED transition as
`409`, with no durable effects from rejected operations. PR Quality run
`30868733961` on head `13a0224` executed three lifecycle tests with zero
failures, errors, or skips. This remains non-production backend proof.

## Release Gate

Release requires all required suites green, zero unresolved critical security finding, accepted migration and rollback evidence, provider/IdP/connector conformance, held-out evaluation within preregistered bounds, successful restore/reconciliation drill, and operator runbook exercise.

## Current CI Checkpoint

PR-quality run `30257587569` passes on revision
`a975f922fcd93c71479b9e15563643a9ea1aa04f`. Its executable jobs prove
Linux/Windows bootstrap, repository secret scan, actionlint, Operator Web, AI
Runtime, both Java services, dependency security, PostgreSQL trust, Keycloak
reference conformance, and Compose build/health. PostgreSQL job `89950772823`
and artifact `8650178111` prove the V009/activity checkpoint above.

Cross-service run `30257587543` passes A/B/C through the real service path with
samples `100/1/1`, all eight metrics `PASS`, and `GitTree=0`. The evaluation
unit suite passes 61/61. Artifact `8649696519` also records Phase 7
OperatorWorkspace/CrossService/Checkpoint/PhaseExit `PASS`. The Phase 8
validator still returns `PhaseExit=BLOCK` because held-out and human-baseline
inputs are unavailable; no quality, calibration, or human-benefit inference is
permitted.

The newer incident-closure merge is bound to PR Quality `30868733961`, CodeQL
`30868731708`, and cross-service `30868733964`; all passed on tested head
`13a0224`, whose tree was merged by PR #59 as `3bad910`.

## Verification Evidence

Evidence is written under `artifacts/verification`, `artifacts/evaluation`, `artifacts/security`, and `artifacts/dr`, then uploaded by CI. Local ignored artifacts remain diagnostic/reference evidence until an executed CI run binds them to an immutable revision. The final audit maps every Definition of Done item to a current artifact rather than a plan statement.

## Remaining Test Decisions

G0.5 no longer has pending test-envelope decisions. Frameworks, coverage
thresholds, matrix versions, supported browsers, and environment-specific test
responsibilities are selected in Phase 2; release corpus size and statistical
power are preregistered in Phase 8. Phase 16 must also reconcile and prove the
120-minute service RTO against the four-hour artifact restore target.
