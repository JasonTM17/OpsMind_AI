# Phase 3 Trust/Data Foundation — Progress Report

Date: 2026-07-21
Status: **in progress**

## Scope completed locally

- Added the versioned OpenAPI and JSON Schema baseline for `/api/v1`, identity
  claims, tenant scope, delegated capabilities, paging, and Problem Details.
- Added Spring Security fail-closed defaults, framework issuer/JWKS signature
  validation, audience and bounded token-lifetime policy, mandatory MFA AMR,
  safe 401/403 responses, correlation IDs, and principal mapping that does not
  elevate caller-supplied tenant claims into authority.
- Added a persistence-only request filter that rechecks platform user status
  for every authenticated `/api/v1` request. Unknown and deprovisioned users
  deny; authority-store failure returns a safe dependency error.
- Added tenant-scoped project listing with opaque keyset page tokens and a
  transaction-local PostgreSQL context helper.
- Added bounded delegated-capability validation and nonce replay port, plus
  defensive event envelopes and transactional outbox/inbox ports.
- Added the identity/tenant foundation migration: organization/project
  membership, service-account metadata-only references, idempotency records,
  outbox/inbox, append-only audit trigger, forced RLS, and least-privilege
  grants.
- Split local Compose persistence into a one-shot `platform-migrate` job and a
  long-running `platform-api` runtime using `opsmind_app` with
  `NOSUPERUSER`/`NOBYPASSRLS`; runtime Flyway is disabled.
- Added a fixed `opsmind_context_resolver` `NOLOGIN`/`NOSUPERUSER`/
  `NOBYPASSRLS` role for the narrow issuer/subject and tenant-membership
  resolver functions. Authority-table policies grant only that function owner
  the explicit read path needed under forced RLS.
- Added JSON-object and exact UTF-8 SHA-256 payload verification before an
  outbox insert.
- Added idempotency-key/request-digest and strict `If-Match` optimistic-
  concurrency scaffolding, plus bounded JWT claim mapping.
- Added an environment-gated Hikari/PostgreSQL integration test that fixes the
  pool at one physical connection and alternates tenant A, no context, tenant
  B, invalid membership, rollback, statement-timeout cancellation, and a
  background/no-context transaction.
- Added a repeatable Windows PostgreSQL harness and matching CI step. The
  harness uses a random database and runtime password, applies Flyway through
  the packaged application, runs Java and SQL contracts, and verifies cleanup.
- Added exact UTF-8 payload-byte persistence beside `jsonb`; claimed messages
  are digest-verified before dispatch so normalized JSON cannot silently change
  the signed bytes.
- Added database-enforced contiguous aggregate sequence with transaction
  advisory locking, bounded outbox lease/retry/poison state, stale-token-safe
  acknowledgement, and inbox processed/poison/reclaim transitions. Runtime
  update grants are restricted to messaging state columns.
- Added forward migration V002 with a dedicated `opsmind_dispatcher` login and
  `opsmind_dispatch_resolver` non-login owner. V002 removes lease/ack authority
  from `opsmind_app`, binds one tenant and workload per transaction, and lists
  only tenants with claimable work plus an active exact-audience/scope service
  account. No external polling loop is enabled.
- Added an isolated, digest-pinned Keycloak 26.7 reference harness for the real
  local non-production OIDC browser/resource-server boundary. Checked-in
  application, Compose, and environment defaults now cap access-token lifetime
  at `PT5M`.
- Pinned the resource-server decoder to RS256 and bounded its discovery/JWKS
  client with 500-millisecond connect/read timeouts plus one outbound request
  per exact target URI, per Platform API instance, per configured interval.
  The checked-in interval is `PT1S`; startup validation accepts 100
  milliseconds–1 minute. A same-target request inside the interval fails
  closed, so genuine key rotation can temporarily reject a token.

## Evidence available in this worktree

- `node scripts/validation/validate-phase-03-trust-foundation.mjs` — PASS,
  50 required files and trust contracts checked.
- `node scripts/validation/validate-repository-layout.mjs` — PASS, 244 files
  checked and canonical roots preserved.
- Documentation validation — PASS: 12 canonical doc files and 24 internal doc
  links checked with zero errors; a separate README/docs/selected-plan pass
  resolved 94 relative links with zero failures.
- Project secret-pattern scan — PASS: 238 text files scanned and zero
  findings; no provider key or generated runtime password was added.
- POSIX launcher and bootstrap shell syntax — PASS (`bash -n`).
- PowerShell launcher help/parser smoke — PASS.
- GitHub Actions workflow lint — PASS with actionlint 1.7.12.
- Windows command-surface suite — PASS (25 checks); portable POSIX suite —
  PASS (24 checks).
- Fresh full `mvn verify` on JDK 21 — PASS: 40 tests discovered, 35 normal
  tests passed, zero failures/errors, and five environment-gated database tests
  skipped as designed outside their disposable database harness.
- Focused OIDC hardening tests — PASS: five tests across
  `PlatformSecurityPropertiesTest` and `OidcOutboundRequestRateLimiterTest`,
  zero failures/errors. The rate-limiter cases prove exactly one outbound call
  under 16-way same-target concurrency, a later call after the interval, and
  independent discovery/JWKS targets. This is per target and per instance, not
  a cluster-wide limit.
- Disposable PostgreSQL 18/Hikari contract — PASS: the integration test ran
  unskipped on one physical backend and proved context cleanup after commit,
  rollback, invalid membership, and statement timeout. The SQL contract also
  proved role separation, cross-tenant denial, context reset, outbox aggregate
  sequence uniqueness, inbox duplicate suppression, idempotency isolation,
  aggregate sequence contiguity, and audit immutability. The guarded fault
  tests additionally proved atomic pre-commit rollback, reclaim after a crash
  before publish, replay after publish-before-ack, one idempotent logical
  effect across two physical deliveries, stale-token denial, retry delay,
  poison, inbox acknowledgement loss, and received-orphan reclaim. The same
  harness proved active-user resolution, immediate denial after
  deprovisioning, and denial of an unknown issuer/subject mapping. The fifth
  live test proved API/dispatcher privilege separation, no-context denial,
  one-tenant transaction binding, bounded cross-tenant scheduling, service-
  account audience/scope enforcement, and transaction context reset. Final
  markers: `PackageExit=0`, `PoolContractExit=0`, `ContractExit=0`,
  `ResidualObjects=0`, `Result=PASS`. Durable local transcript:
  `artifacts/verification/phase-03/outbox-inbox.txt`.
- Local Windows Keycloak reference conformance — PASS via
  `pwsh -NoProfile -File .\scripts\validation\run-phase-03-keycloak-conformance.ps1`.
  The run proves HTTPS discovery, PKCE S256, direct-grant and wrong-verifier
  denial, TOTP enrollment without MFA, MFA `amr`, exact same-code/same-timestep
  replay denial, RP-initiated logout and refresh-after-logout denial, Platform
  API anonymous/missing-MFA/tampered-signature denial, JWKS rotation refresh,
  old refresh-token reuse denial after rotation, an independent refresh family
  for the pre-revocation positive control, refresh-token revocation, and
  disabled-user new-login denial. An existing
  stateless access JWT remained accepted after upstream disable. Its issuance
  lifetime is 300 seconds, while timestamp enforcement includes `PT30S` skew in
  the harness and `PT60S` in checked-in defaults. The corresponding policy
  upper bounds are 330 and 360 seconds. The run proves immediate post-disable
  acceptance and records the denial horizon as not live-measured. Platform-user
  deprovisioning remains a separate per-request authority check and denies
  immediately.
- The landed schema-v2 runner/verifier contract requires
  `ExistingJwtAfterIdpDisable=PREISSUED_JWT_STILL_ACCEPTED`,
  `RefreshTokenRotationReuseDenied=PASS`,
  `RefreshTokenIndependentSessions=PASS`,
  `RefreshTokenPreRevocationControl=PASS`,
  `AccessTokenLifetimeSeconds=300`, `ConfiguredClockSkewSeconds=30`,
  `MaximumResidualAcceptanceSeconds=330`, and
  `DisableToDenialHorizon=NOT_LIVE_MEASURED`. It binds a manifest digest of
  source/profile inputs and the packaged Platform API JAR digest, verifies
  cleanup before atomic evidence publication, and rejects stale evidence.
  The 124.694-second live schema-v2 run and the independent profile/JAR digest
  verifier both passed. A forced packaging failure separately proved bounded
  sanitized failure evidence, no false success artifact, and verified cleanup.
- The identity transcript records schema/contract/scenario versions,
  environment/runtime identity, configuration digest, command, start/end time,
  `RuntimeSecretsPersisted=NO`, and `Result=PASS`. It is also marked
  `REFERENCE_CONFORMANCE_NOT_PRODUCTION`, `CodeRevision=UNBORN`, and
  `WorkspaceDirty=YES`. Because the artifact is ignored and the configured
  Linux `identity-conformance` CI job has not run remotely, this is not
  immutable release evidence. Local schema-v2 PASS is claimed; remote CI and
  Compose identity PASS are not.

## Security correction made during implementation

An isolated PostgreSQL probe proved that a `SECURITY DEFINER` membership helper
owned by an ordinary forced-RLS table owner sees zero authority rows unless a
policy explicitly permits that owner. The earlier local proof happened to use a
superuser migration owner and therefore did not expose this production failure
mode. The migration now assigns the narrow resolver functions to a fixed
`NOLOGIN`/non-bypass role and permits that role only on the identity and
membership authority paths. A second full transactional migration/RLS probe
passed through the non-superuser runtime role and rolled back cleanly.

The original outbox shape stored a digest of exact producer bytes but retained
only `jsonb`. PostgreSQL normalizes `jsonb`, so a dispatcher could not reproduce
the hashed bytes. The schema now stores bounded original bytes separately and
verifies them after lease claim. Aggregate sequence enforcement also moved
from a repository-only check to a database trigger, preventing direct SQL paths
from bypassing ordering.

The generic resource-server adapter previously proved only issuer/audience and
fixture claim mapping. The local boundary now rejects missing MFA AMR,
overlong/future/invalid token lifetimes, malformed subjects, and unavailable or
inactive platform identity. The Keycloak reference harness now adds discovery,
JWKS rotation refresh, browser PKCE/MFA paths, RP-initiated logout,
old refresh-token reuse denial after rotation, refresh-token revocation, and
disabled-user new-login proof. It deliberately
does not claim production-vendor authorization, federation, break-glass,
state/nonce assurance, browser/BFF session ownership, general bearer replay
prevention, delegated-capability proof, or immediate access-token revocation.

The combined API repository originally exposed both append and lease methods,
which made least privilege depend only on call discipline. The contracts are
now split: the web repository is append-only, V002 revokes its state-update
columns, and the dispatcher lease repository is enabled only by an explicit
safe-default-off property. Scheduler functions execute as a fixed non-login,
non-bypass resolver with column-limited metadata grants; they never return
payloads across tenants.

## Gates still open

1. Select and authorize the production enterprise IdP, then prove its
   federation, session, claim, break-glass, and revocation behavior in the
   appropriate non-production environment.
2. Execute the configured Linux identity-conformance job remotely and collect
   remote CI/Compose smoke evidence without treating configuration as a PASS.

No production secret, DeepSeek key, token, or private evidence is stored in
this report or the repository.

## Unresolved questions

- The production enterprise IdP profile and endpoint are still a phase-owned
  conformance gate. Keycloak is authorized only as the local/reference target.
- V001-to-V002 forward migration is now live-tested. A real dispatcher process,
  external workload authentication, deterministic target handoff, and
  reconciliation remain Phase 9 work rather than hidden Phase 3 behavior.
