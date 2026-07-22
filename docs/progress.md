# OpsMind AI Progress

## Reporting Rules

- Report only behavior and artifacts verified in the current worktree.
- Link each completed claim to a command, file, or immutable CI artifact.
- Keep planned behavior separate from implemented behavior.
- Record blockers explicitly and leave downstream phases pending.
- Do not include secrets, raw credentials, or sensitive evidence.

## 2026-07-19 — Architecture and A–Z plan

Completed:

- Parsed and traced the master requirements into 79 identifiers.
- Produced a sixteen-phase implementation plan with dependencies, risks, acceptance criteria, and 35 final Definition of Done items.
- Applied four hostile review lenses and integrated fifteen deduplicated corrections.
- Strict plan validation reports sixteen phases, zero errors, and zero warnings.
- Configured the Git `origin` remote for the project repository.

Evidence:

- [A-Z plan](../plans/260719-1747-opsmind-ai-production-platform/plan.md)
- [Requirements traceability](../plans/260719-1747-opsmind-ai-production-platform/research/master-prompt-requirements-traceability.md)
- [Red-team review section](../plans/260719-1747-opsmind-ai-production-platform/plan.md#red-team-review)

Not completed:

- No runtime service, database schema, provider call, connector, UI, deployment, or production control exists.

## 2026-07-19 — Phase 1 operating envelope

Implemented:

- Storage capacity checks for Windows C:/D: and every distinct portable filesystem containing the workspace or configured heavy-state roots.
- Storage-root validation for cache, evidence, data, and model directories.
- Fail-closed exit codes and bounded verification transcripts.
- Direct PowerShell tests for forced block, forced pass, writable D-backed roots, and Windows system-volume rejection.
- Canonical architecture, PDR, local development, deployment, testing, evaluation, dataset, security, code, roadmap, blocker, decision, and ADR documents.
- Secret-safe `.env.example` and generated-artifact ignore policy.

Verified:

- Windows storage guard tests pass `12/12`; portable storage tests pass `11/11`, including forced low-space, distinct-filesystem, unsafe artifact roots, missing external roots, Windows/POSIX path aliases, device/UNC, reparse, and overlap cases.
- Product/production contract mutation tests pass `34/34`, including typed values/bounds, strict UTF-8/JSON/canonical-number handling, SemVer schema parity, exact-case and duplicate-property rejection, transcript-injection defense, schema fingerprint drift, pending/rejected states, and evidence-source overwrite protection.
- Secret-scan canary tests pass `12/12`, including benign token-name controls, ignored configuration, UTF-16 and UTF-32 behavior, namespaced JSON/YAML/env credentials, configured external artifacts, exact staged index state, Git-history content and sensitive paths, and binary fail-closed handling.
- Default-evidence safety tests pass `6/6`, proving missing or repository-ancestor artifact roots cannot be created or modified by contract, documentation, or secret gates.
- Composite governance suite passes `10/10` against the approved contract after
  harness alignment; `test-product-production-contract.ps1` and
  `test-phase-01-governance.ps1` now reflect the validator's approved-state
  exit code of `0`.
- The concrete G0.5 recommendation proposal validates as `STRUCTURE_VALID_PENDING` with exit `10`; it remains separate from the authoritative contract and carries no approval metadata.
- Documentation validation checks 46 Markdown files and 93 local links with zero errors.
- Project-scoped secret-pattern scan checks 78 product/evidence/review files plus bounded Git history with zero findings.
- Independent tester reproduced the Phase 1 gates. Independent review findings drove strict type/JSON validation, complete scan surfaces, safe evidence publication, concurrent canary isolation, and portable path/root hardening. The final frozen-worktree controller review reports no unresolved P1/P2 defect in the Phase 1 boundary.

G0.5 approval and completion:

- The project owner approved the complete twelve-decision recommended baseline;
  the approval scope and source statement are preserved in the
  [approval record](./decisions/g0-5-approval-2026-07-19.md).
- The authoritative contract records typed approved values, accountable roles,
  one RFC 3339 timestamp, and one canonical evidence URI.
- Fresh storage checks passed with C: above the 10 GB floor and D: above the
  20 GB floor.
- The strict contract validator returned `Result=PASS` with exit code `0` and
  published the Phase 1 evidence transcript.
- The phase-01 governance wrapper reran clean after aligning the harness to the
  approved contract semantics.

Current phase state: **complete**, six of six exit criteria proven. Phase 2 is
authorized, subject to a fresh capacity/root preflight before each heavy command.

## 2026-07-20 — Phase 3 trust/data foundation in progress

Implemented locally:

- Versioned `/api/v1` OpenAPI and auth/problem-detail JSON Schema contracts with synthetic, secret-free fixtures.
- Spring Security fail-closed default and standards-based OIDC resource-server
  adapter with issuer/JWKS signature, audience, bounded lifetime/clock skew,
  subject, mandatory MFA AMR policy, and checked-in `PT5M` maximum lifetime;
  tenant claims are not treated as
  authority.
- Persistence-enabled `/api/v1` requests recheck the verified issuer/subject
  against platform identity authority; unknown or deprovisioned users deny and
  authority-store failure fails closed.
- Transaction-local tenant context, tenant-scoped project read path, opaque paging tokens, delegated-capability validation ports, and replay/budget checks.
- PostgreSQL V001 identity/tenant/outbox/inbox/audit foundation plus forward
  V002 dispatcher migration: forced RLS, append-only audit, explicit non-owner
  grants, and non-login context/dispatch resolvers.
- Compose role split: `platform-migrate` owns Flyway; `platform-api` runs with
  append-only `opsmind_app`; the dormant `opsmind_dispatcher` identity alone
  owns lease/ack state. Pairwise-distinct passwords remain process/secret-manager inputs.
- Event payload JSON-object and exact UTF-8 SHA-256 verification before outbox insertion.
- Bounded idempotency-key/request-digest persistence and strict `If-Match`/optimistic-version helpers; malformed verified JWT claims now fail as safe authentication errors instead of leaking a 500 path.
- Environment-gated Hikari/PostgreSQL test with a one-connection pool, plus a
  disposable local harness and matching CI evidence step.
- Crash-safe outbox lease/retry/poison primitives, transactional inbox
  completion/reclaim primitives, exact payload-byte preservation, and a
  database-level contiguous aggregate-sequence trigger.
- Bounded tenant scheduler and transaction-local dispatcher workload binding;
  tenants require an active service account with the exact dispatcher audience,
  scope, and database principal before their events become schedulable.

Verified in this worktree:

- `node scripts/validation/validate-phase-03-trust-foundation.mjs` — `Result=PASS`, 50 files checked.
- `node scripts/validation/validate-repository-layout.mjs` — `Result=PASS`, 230 files checked.
- `mvn verify` — PASS: 27 tests discovered, zero failures/errors; 22 normal
  tests passed and five live-database tests skipped outside their guarded harness.
- Vendor-neutral OIDC token-policy tests reject missing MFA, excessive token
  lifetime, invalid subject/audience/time claims, and unsafe configuration.
- Local PostgreSQL 18 migration/RLS/Hikari matrix — PASS; the one-connection
  runtime pool did not leak context after commit, rollback, invalid membership,
  statement timeout, or a missing-context/background transaction. The SQL
  contract proved role separation, cross-tenant denial, outbox order
  uniqueness, inbox deduplication, idempotency isolation, and append-only audit.
  The guarded identity test proved active-user resolution, immediate denial
  after deprovisioning, and denial of an unknown issuer/subject mapping.
  The two-role dispatcher test proved the web role cannot update dispatch
  state, the dispatcher sees zero rows before binding, cross-tenant switching
  in one transaction denies, fair bounded scheduling advances to tenant B, and
  tenant/workload context clears after transaction completion.
  Cleanup markers were `PackageExit=0`, `PoolContractExit=0`,
  `ContractExit=0`, and `ResidualObjects=0`.
- Live outbox/inbox fault matrix — PASS: append and local state rolled back
  atomically before commit; expired claims were reclaimed; publish-before-ack
  replay produced two physical deliveries but one idempotent logical effect;
  stale lease acknowledgement was rejected; retry delay, poison, aggregate
  ordering/gap rejection, inbox rollback, acknowledgement loss, received-orphan
  reclaim, and poison denial all converged. Exact JSON payload bytes survived
  the `jsonb` round trip. Durable transcript:
  `artifacts/verification/phase-03/outbox-inbox.txt`.
- POSIX/PowerShell launcher syntax and focused command-surface tests — PASS
  (24/24 and 25/25); missing migration secrets exit deterministically with code 2.

## 2026-07-21 — Local Keycloak reference conformance

Verified locally on Windows:

- `pwsh -NoProfile -File .\scripts\validation\run-phase-03-keycloak-conformance.ps1`
  completed with `Result=PASS` against digest-pinned Keycloak 26.7.
- The isolated HTTPS profile passed PKCE S256; direct-grant and wrong-verifier
  denial; TOTP enrollment without MFA, MFA `amr`, and exact same-code/
  same-timestep replay denial; RP-initiated logout and refresh-after-logout
  denial; anonymous, missing-MFA, and tampered-signature Platform API denial;
  JWKS rotation refresh; old refresh-token reuse denial after rotation;
  a separate refresh family for the pre-revocation positive control;
  refresh-token revocation; and disabled-user new-login denial.
- The resource-server decoder is now RS256-only. Discovery/JWKS requests use
  500-millisecond connect/read timeouts and a per-exact-target, per-instance
  minimum interval (`PT1S` default; validated 100 milliseconds–1 minute). Five
  focused property/rate-limiter tests pass with zero failures or errors,
  including 16-way same-target concurrency and independent discovery/JWKS
  targets. This is not a cluster-wide request bound; key rotation can fail
  closed until the same-target interval elapses.
- After upstream user disable, a pre-issued stateless access JWT remained
  accepted. Its issuance lifetime is 300 seconds; timestamp enforcement also
  includes `PT30S` skew in the harness and `PT60S` in checked-in defaults. The
  corresponding policy upper bounds are 330 and 360 seconds. The run proves
  immediate post-disable acceptance and records the denial horizon as not
  live-measured. This is separate from platform-user deprovisioning, which the
  persistence filter checks on every request and the PostgreSQL harness proves
  denies immediately.
- The landed schema-v2 runner/verifier contract requires
  `ExistingJwtAfterIdpDisable=PREISSUED_JWT_STILL_ACCEPTED`,
  `RefreshTokenRotationReuseDenied=PASS`,
  `RefreshTokenIndependentSessions=PASS`,
  `RefreshTokenPreRevocationControl=PASS`,
  `AccessTokenLifetimeSeconds=300`, `ConfiguredClockSkewSeconds=30`,
  `MaximumResidualAcceptanceSeconds=330`, and
  `DisableToDenialHorizon=NOT_LIVE_MEASURED`. It binds the source/profile
  manifest and packaged Platform API JAR digests, verifies cleanup before
  atomic publication, and rejects stale evidence. The 124.694-second live
  schema-v2 run and the independent profile/JAR verifier both passed.
- A forced packaging-failure probe emitted no success artifact, verified
  cleanup, and produced a 573-byte bounded/sanitized failure artifact. The
  project secret scan then returned zero findings. CI uploads the mutually
  exclusive success/failure paths on every run; failure evidence cannot satisfy
  the success verifier.
- A fresh full JDK 21 `mvn verify` after the live run passed 40 tests with zero
  failures/errors; 35 normal tests passed and five guarded database tests were
  skipped outside their disposable PostgreSQL harness. Rebuilding the JAR did
  not invalidate the evidence artifact digest.
- The transcript records schema/contract/scenario versions, runtime identity,
  configuration digest, command, timestamps, and no persisted runtime secrets.
  It also records `EvidenceScope=REFERENCE_CONFORMANCE_NOT_PRODUCTION`,
  `CodeRevision=UNBORN`, and `WorkspaceDirty=YES`; the ignored local artifact is
  reproducible reference evidence, not immutable release evidence.
- `.github/workflows/pr-quality.yml` runs the Linux schema-v2 verifier and
  uploads its evidence. The later revision-bound run `29923961768` passed this
  Keycloak job and the Compose build/health smoke; the workstation transcript
  above remains local reference evidence rather than being retroactively
  promoted.

This resolves B-003 only for the local/reference non-production IdP scope. It
does not authorize a production IdP or prove federation, break-glass,
state/nonce assurance, browser/BFF session ownership, general bearer replay
prevention, delegated capabilities, or immediate access-token revocation.

Still open:

- Production-authorized enterprise IdP selection/conformance remains open.
- No dispatcher polling process or external publish target is enabled. Phase 3
  now proves its database identity and scheduler boundary; Phase 9 must add and
  verify the externally authenticated runtime and deterministic target handoff.

## 2026-07-22 â€” Phase 4 checkpoint 4A incident write ledger

Implemented locally:

- Canonical nested incident create, detail, status-transition, and timeline
  routes plus OpenAPI, Draft 2020-12 schemas, positive/negative fixtures, safe
  Problem Details, a 32 KiB configurable JSON-body bound, idempotency keys, and
  strong numeric ETags.
- One transaction for authority resolution, tenant binding, idempotency,
  incident mutation, timeline, audit, outbox, and cached response completion.
- V003 incident/timeline persistence with forced RLS, state/version guards,
  exact authoritative timeline payload validation, append-only controls, and a
  database-assigned tenant audit chain whose inputs must match the timeline.
- A narrow SECURITY DEFINER authorization resolver that locks active user,
  organization, memberships, project, and role rows so revocation serializes
  with an already-authorized mutation.
- Hidden-resource `404` responses use correlation URNs and never reflect scoped
  organization/project/incident identifiers.
- Schema-versioned reference evidence runners with source/config/migration/JAR hashes,
  tool versions, timing, bounded diagnostics, atomic publication, and explicit
  local/non-release scope.

Verified in this worktree:

- Static incident contract gate: 11 schemas, 14 fixtures, 128 local references,
  six OpenAPI operations, zero diagnostics, `Result=PASS`.
- Focused domain gate: seven test classes, 25 tests, zero
  failures/errors/skips, `Result=PASS`.
- Full Maven suite: 86 discovered, zero failures/errors; 11 guarded integration
  cases skip only outside their dedicated harness.
- Disposable PostgreSQL 18 gate: package, V001/V002-to-V003 upgrade, fresh
  V001-V003, guarded integration matrix, and portable SQL contract all exited
  zero; cleanup reported zero residual containers.
- Live tests prove authorized CRUD/replay, actor mismatch, non-enumerating
  cross-tenant access, one 200/one 412 concurrent transition, immediate next-
  request denial after serialized membership revocation, forged timeline/audit
  rejection, linear concurrent audit append, caller-forged chain override, and
  update/delete/truncate denial.
- A real outbox primary-key conflict after timeline and audit append rolled back
  the incident, timeline, audit, and idempotency rows; the test ran once with
  zero failure/error/skip.
- Refreshed Keycloak 26.7 schema-v2 conformance and its independent verifier
  pass against the exact same packaged JAR digest as Phase 4 PostgreSQL evidence.
- Layout checked 333 files, trust foundation checked 50 files, and the project
  secret scan checked 330 text files with zero findings before the final docs
  sync; final counts are revalidated after this update.

Evidence:

- `artifacts/verification/phase-04/incident-contracts.txt`
- `artifacts/verification/phase-04/incident-domain.txt`
- `artifacts/verification/phase-04/incident-crud.txt`
- `artifacts/verification/phase-04/audit-and-concurrency.txt`
- `artifacts/verification/phase-03/identity-delegation.txt`

Checkpoint state: **4A locally complete**. Phase 4 remains **in progress**.
List/patch/assignment, resolution/closure UX, postmortems, and the governed
evidence-object upload/read/tombstone/restore/purge/reconciliation lifecycle are
not implemented. Local evidence records `CodeRevision=UNBORN` and
`WorkspaceDirty=YES`; later revision-bound CI verifies the repository contracts
without converting that historical local transcript into production evidence.

## 2026-07-22 — Phase 5 provider-neutral runtime checkpoint

Implemented offline in `services/ai-runtime/`:

- strict versioned analysis request/response/problem contracts and matching
  JSON Schema roots;
- disabled-by-default typed settings with `deepseek-v4-flash` default,
  legacy alias retirement guard, and opaque secret handling;
- platform-issued delegated capability scope matching, nonce replay protection,
  last-hop data-class/redaction policy, and bounded token/tool/cost guard;
- provider-neutral application port separating orchestration from the DeepSeek
  adapter, strict outbound URL/numeric config bounds, and stable post-call
  budget/invalid-response failures;
- signed exact-request digest and maximum capability lifetime, evidence-source
  classification/citation binding, bounded pre-parse/chunked HTTP ingress, and
  global queue/provider deadlines;
- cumulative per-run token/cost allowance translated into provider-side
  completion caps; live readiness rejects unknown zero pricing;
- DeepSeek transport/adapter, sanitized `400/401/402/422/429/500/503` error
  taxonomy, structured-output validation, and contiguous terminal-frame stream
  assembler;
- endpoint/contract/unit tests that use only synthetic redacted payloads.
- additive PostgreSQL V004 state tables for hashed nonce consumption,
  cumulative run budgets, bounded leases, normalized success replay, forced
  tenant RLS, and a dedicated non-bypass `opsmind_ai_runtime` role;
- Psycopg async state adapter with row-lock reservation, crash-to-ambiguous
  recovery, full-reservation charging, fail-closed provider-overage accounting,
  success replay, and a disposable local
  PostgreSQL integration runner.
- additive V005 append-only capability-probe lifecycle/usage audit. Each
  process proves its own provider path; PostgreSQL advisory locking enforces a
  bounded provider/model/region hourly quota using the database clock, with
  jittered startup/retry scheduling.

Verification: 149 offline Python tests passed with
`PYTHONPATH=services/ai-runtime/src`. No real provider key or external call was
used. Flyway V004/V005 also applied successfully in the PostgreSQL 18 Phase 5
disposable migration gate. The dedicated five-test Phase 5 database runner
first exposed the Windows Proactor/Psycopg incompatibility and a lease-recovery
rollback defect. Selector-loop execution and an independent recovery
transaction now resolve both; PostgreSQL 18.4, V004/V005, all five tests, and cleanup
pass in a capacity-qualified local run. This remains unborn/dirty local
reference evidence. Cross-service asymmetric capability conformance passes;
final adversarial re-review found no surviving Critical/High issue after
cross-language structured-secret redaction, per-process capability proof,
DB-clock quota locking, startup/retry jitter, monitor recovery, and concurrent
quota-race fixes. See
[`code-review-260722-phase-05-post-fix.md`](../plans/reports/code-review-260722-phase-05-post-fix.md).
provider conformance/live synthetic smoke and production egress remain open.
Phase 5 is **in progress**.

## 2026-07-22 — Phase 6 Tool Gateway checkpoint

Implemented the fail-closed execution boundary: independent workload and
delegated-capability JWT domains, exact body/scope binding, one-use nonce,
idempotent receipts, policy/manifest enforcement, bounded connector execution,
recursive redaction, normalized evidence, deterministic audit, and explicit
liveness/readiness separation. Four schemas, five fixtures, canonical digest
checks, and 24 Maven tests pass. Durable atomic stores, three connector families,
the Platform capability issuer/client path, a selected live target, and
provider-specific cancellation/bulkhead proof remain open. Phase 6 is **in
progress**; its checkpoint passes but its exit gate is blocked.

## 2026-07-22 — Phase 7 durable investigation persistence checkpoint

Implemented in the Platform API:

- pure investigation reducer and bounded synchronous runner with visible
  completion, abstain, budget, duplicate, no-progress, and dependency failures;
- feature-gated fixture AI/Tool clients and investigation start/read endpoints;
- V006 `investigation_runs` snapshots plus contiguous immutable
  `investigation_run_events`, forced RLS, least-privilege grants, and optimistic
  revision/event-count concurrency;
- same-transaction `investigation-audit-v1` audit-chain writes and exact event,
  terminal-response, snapshot-parity, and append-only database triggers;
- direct-SQL integrity tests proving a runtime role cannot forge malformed
  completion state/events or mutate either ledger.

Verification:

- `validate-phase-07-investigation-slice.mjs`: `CheckpointResult=PASS`,
  `PhaseExit=BLOCK`;
- full local Platform API suite passed with zero failures/errors and database
  tests gated to their dedicated harness;
- GitHub Actions run `29923961768` at revision
  `0ec3cff944102b716dc098871384ba0534df06fd` passed governance, Ubuntu/Windows
  bootstrap, PostgreSQL migration/persistence/integrity, Keycloak, Operator Web,
  Compose, and AI Runtime jobs. Both Java suites also completed successfully,
  but their jobs were cancelled at the 60-minute limit while two independent
  unauthenticated OWASP Dependency-Check processes each imported the full NVD
  corpus. The replacement separates bounded Maven verification from one shared
  CycloneDX/OSV policy job.
- GitHub Actions run `29930327761` at revision `8a6bd398` completed successfully
  across every executable job. Java dependency security completed in 32 seconds;
  its artifact contains 111- and 97-component SBOMs, 208 scanned packages,
  checksum-pinned OSV 2.4.0, zero vulnerability groups after the Jackson
  Databind 3.1.5 upgrade, and `Result=PASS`. All nine local security-tool tests
  also pass (two installer and seven evaluator cases).

This checkpoint is durable data, not durable workflow. It does not resume an
in-flight orchestrator and does not append to `incident_timeline_events`.
Capability-backed clients, the allowlisted live connector, CK/Stitch UI/browser
E2E, and cross-service trace/p95 evidence remain required. Phase 7 is **in
progress**.

## 2026-07-22 — Checkpoint 4B bounded evidence records

Implemented an immutable small-record evidence control plane before enabling
real Phase 7 clients:

- V007 `evidence_records` with a 64 KiB canonical JSON limit, independent
  PostgreSQL SHA-256 verification, forced RLS, append-only triggers, constrained
  runtime grants, and one-to-one linkage to `EVIDENCE_APPENDED` run events;
- deterministic Platform-owned evidence/execution UUIDv8 identities scoped to
  organization, run, and tool intent;
- same-transaction run successor, run-event, evidence, and audit persistence;
- metadata-only event/audit serialization, with canonical content available
  only through a tenant/incident/run-authorized resolver that re-verifies the
  digest and preserves caller order;
- Gateway request/audit/provenance, redaction, truncation, and duplicate replay
  metadata retained without exposing credentials or raw provider payloads.

Local verification: Platform API `148` tests, `0` failures/errors and `20`
environment-gated integration skips; Phase 4B static checkpoint PASS; repository
layout, actionlint, diff, and working-tree/history secret scans PASS.

GitHub Actions run `29936897223` at revision
`77f7ab80edb64f7ac8a0a46b68c37a3ad2f043eb` completed successfully with 11
successful executable jobs and one expected push-only dependency-policy skip.
The run applied fresh V001–V007, passed 11 PostgreSQL integration cases with no
failure/error/skip, including evidence persistence and rollback, and passed the
full Compose health smoke. Run `29938632667` at revision
`3da19efcb23db60e4c42c7a849f5a34c790f1a32` subsequently passed every executable
job and proved a guarded disposable V006→V007 upgrade from table absence to
presence with cleanup PASS.

Exact transition replay is now accepted only when the successor snapshot,
deterministic event, tool execution/request digest, and complete evidence
provenance match; drift fails closed. A real final-step audit conflict now also
proves rollback after snapshot/event/evidence writes. GitHub Actions run
`29940796700` at revision `14eb8837b94f16933722954e7a03e55a73295d16`
passed all 11 executable jobs. Its PostgreSQL artifact reports 13 tests with zero
failure/error/skip, including replay `1/1`, rollback `2/2`, and the guarded
upgrade/cleanup proof. Bounded-record checkpoint 4B is **complete**. Phase 4 and
G2 remain open because the large/raw artifact lifecycle is still blocked by
B-006/B-008/B-012.

## Next Allowed Work

1. Build the real Phase 7 integration through an allowlisted intent catalog,
   short-lived capability issuance, independent workload authentication, and
   bounded Platform-to-AI Runtime/Tool Gateway HTTP clients; model output must
   never become an executable request directly.
2. Add the Prometheus live non-production connector path and persist/link
   accepted evidence into the incident timeline without weakening Tool Gateway
   policy, replay, or audit controls.
3. Build the operator slice through CK frontend workflow plus Stitch, then add
   Playwright failure-path and accessibility coverage.
4. Continue Phase 4 evidence-object lifecycle and select/authorize the
   production IdP profile, then prove federation,
   session, claim, break-glass, and revocation behavior in non-production.
5. Keep the external dispatcher loop disabled until Phase 9 binds its runtime
   identity, deterministic workflow ID, and reconciliation contract.
6. Keep dependency downloads, container builds, and service startup behind a
   fresh capacity/root preflight. Both monitored volumes remain above their
   floors, but prefer CI for heavy Docker work while C remains close to its
   minimum reserve.

## Unresolved Questions

No current implementation decision is being silently deferred. Production IdP,
external publisher/runtime, provider/legal, live connector, UI, performance,
DR, and release conformance remain explicit gates; see
[Blockers](./blockers.md).
