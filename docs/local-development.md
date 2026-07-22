# Local Development

## Safety Contract

Local setup is fail-closed. Do not install dependencies, build images, start Compose, download models, run benchmarks, or train while the capacity check fails. Storage cleanup, Docker pruning, WSL shutdown, and file deletion remain manual and require operator approval.

The repository now contains health-only bootstrap services, the standard command
surface, and an in-progress Phase 3 trust/data substrate. The Platform API is
fail-closed by default; its OIDC, tenant/RLS, migration, and outbox contracts
are implemented locally. A disposable PostgreSQL 18 harness now proves the
RLS, pooled-connection, user-deprovisioning, and messaging crash-window
boundaries. It also proves API/dispatcher database-role separation and
tenant-safe scheduling. A live local Windows Keycloak 26.7 reference profile
passes, while production IdP selection/conformance and remote CI/Compose
evidence remain open; no external publish loop is enabled.
Incident, DeepSeek, connector, and remediation behavior remain later phase
work.
Compose publishes local service ports on `127.0.0.1` only; it does not expose
the unauthenticated Phase 2 health scaffolds to the workstation LAN.

## Supported Hosts

- Windows 11 with Windows PowerShell 5.1 or PowerShell 7.
- Linux and macOS with a POSIX-style shell plus `df`, `awk`, `mktemp`, and standard filesystem tools. PowerShell 7 (`pwsh`) is required for the complete governance and secret-scan suites invoked by `test` and `security`.
- Git Bash is supported for the portable command surface on Windows.
- WSL may be used later, but its virtual disk, pagefile impact, and Docker storage location must be included in capacity checks.

Install Node 24.12.0 with Corepack, Python 3.13, JDK 21, Maven 3.9.12, and Docker
Compose. Exact project pins are `.node-version`, `.python-version`,
`.java-version`, `.maven-version`, `pnpm@11.15.0`, and `uv==0.11.29`. The setup command installs
the pinned uv and actionlint 1.7.12 into `OPS_CACHE_ROOT`; it does not depend on
mutable global installations. Actionlint archives are selected by OS/CPU,
limited in size, checked against official release SHA-256 digests, verified by
executing `-version`, and then published atomically. Cache hits re-hash the
retained verified source archive and compare executable bytes before trust. A
`tar` executable is
therefore required on every supported host; current Windows includes one.
Active Compose and Dockerfile base images also carry immutable registry
digests; update a digest only through a reviewed image-refresh change. The
disabled object-store sentinel is intentionally not a pullable image.

## Windows Preflight

From the repository root:

```powershell
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File .\scripts\storage\check-capacity.ps1
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File .\scripts\storage\assert-storage-roots.ps1 -CreateMissing
```

The first command checks both fixed drives:

- `C:` must have at least `OPS_MIN_C_FREE_GB`, default 10 GB.
- `D:` must have at least `OPS_MIN_D_FREE_GB`, default 20 GB.

Override thresholds for a deliberate diagnostic without changing policy files:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\storage\check-capacity.ps1 -MinCFreeGb 12 -MinDFreeGb 25
```

A non-zero exit is a blocker. Do not append `; exit 0`, suppress `$LASTEXITCODE`, or bypass the check in a wrapper.

## Portable Preflight

```sh
./scripts/storage/check-capacity.sh
./scripts/storage/assert-storage-roots.sh --create-missing
```

The portable capacity check measures the workspace plus `OPS_CACHE_ROOT`, `OPS_ARTIFACT_ROOT`, `OPS_DATA_ROOT`, and `OPS_MODEL_ROOT`. On Git Bash it also measures `C:/` against `OPS_MIN_C_FREE_GB`, so the Windows system-volume floor cannot be bypassed through the portable launcher. Existing roots are measured directly; a missing repository-contained root is measured on the repository filesystem without being created, while a missing external configured root blocks. It reports distinct devices/mounts and blocks when any represented filesystem is below `OPS_MIN_WORKSPACE_FREE_GB` (default 20 GB). Override `--path` only when the heavy workspace itself is elsewhere; configured roots are still checked independently:

```sh
./scripts/storage/check-capacity.sh --path /approved/workspace --min-workspace-free-gb 25
```

On Git Bash, the root guard accepts only `D:`-backed roots because the Windows workstation policy monitors only `C:` and `D:`. The portable capacity check still queries every distinct filesystem represented by the workspace and four roots. On Linux and macOS the root guard requires absolute, canonical non-overlapping, non-symlinked, writable roots and rejects an unsafe filesystem root path.

## Storage Root Contract

The four roots are independent so retention and cleanup can be controlled without confusing cache, evidence, data, and model lineage:

```text
OPS_CACHE_ROOT      disposable dependency/build cache
OPS_ARTIFACT_ROOT   verification, evaluation, security and DR evidence
OPS_DATA_ROOT       local databases, object data and simulator state
OPS_MODEL_ROOT      downloaded and produced model artifacts
```

Storage values are blank in `.env.example` and resolve relative to the checkout. This keeps the current `D:/OpsMind_AI` workstation D-backed without making a clean clone depend on that exact path. Windows roots must be absolute after resolution, on the fixed non-mapped `D:` volume, non-overlapping, non-reparse/symlinked, and writable. UNC, mapped, substituted, device-namespace, filesystem-root, repository-ancestor, and unmonitored Windows-volume roots fail closed. Git Bash normalizes `D:/...` and `/d/...` before containment and overlap comparisons; WSL uses `/mnt/d/...` as its native absolute form. Production mount policy is a separate G0.5 decision.

`.opsmind/`, `artifacts/`, `.env`, build output, package caches, and Python caches are ignored by Git. Evidence required for review is uploaded by CI or referenced by its immutable artifact identity; secrets never enter evidence.

## Bootstrap and Commands

Start from the repository root. Creating `.env` is optional and is limited to
the documented non-secret allowlist. Runtime secrets must not be written below
the repository, even in ignored files; provide them through the process
environment or an approved secret manager.

```powershell
Copy-Item .env.example .env
.\scripts\dev\opsmind.ps1 setup
.\scripts\dev\opsmind.ps1 doctor
```

```sh
cp .env.example .env
./scripts/dev/opsmind.sh setup
./scripts/dev/opsmind.sh doctor
```

`setup` installs the checksum-verified actionlint pin, performs a frozen pnpm
install, a locked uv sync, and Maven offline dependency resolution. pnpm,
actionlint, uv, pip, Maven, temporary files, and OWASP
Dependency-Check data are routed beneath `OPS_CACHE_ROOT`. The command checks
capacity between ecosystems. Setup runs pnpm in explicit non-interactive mode
and may replace generated `node_modules` state when it is stale. All other pnpm
script commands use `verifyDepsBeforeRun: error`; they fail with an instruction
to run setup rather than implicitly installing or purging dependencies. The
workspace explicitly disables pnpm's global virtual store so CI and local
commands share the same project-local dependency layout.
Before installing anything, setup verifies the exact Node, pnpm, Java, Maven,
and Python pins. A mismatch blocks with exit code 2; use `doctor` for the full
tool-by-tool report.

Heavy commands are single-writer per checkout. The launchers acquire
`.opsmind/command-locks/heavy` before preflight and release it on success,
failure, or handled signals. A second heavy command fails before touching shared
pnpm, Maven, uv, venv, or evidence state. After an unclean process termination,
inspect `owner.txt`, verify the recorded PID is no longer active, and use the
explicit recovery utility only after that verification:

```sh
node scripts/dev/recover-stale-command-lock.mjs \
  --lock-path "$PWD/.opsmind/command-locks/heavy" --confirm-stale
```

The utility refuses live owners, symlinked paths, unexpected lock contents, and
missing owner metadata. Never auto-break an active or unverified lock.

Use either launcher consistently:

| Command | Contract |
|---|---|
| `test` | Phase 1 governance, repository layout, frontend test, both Java tests, Python pytest. |
| `lint` | Layout, ESLint, TypeScript, Java compile, uv-lock check, Ruff, and mypy. |
| `build` | Next.js production build, both Spring Boot packages, and Python bytecode validation. |
| `security` / `security-scan` | Working tree/history secret scan, pnpm audit at moderate+, pip-audit, and Java dependency scan failing at CVSS 7+. |
| `dev` | Build and run the Compose `application` profile in the foreground. |
| `up` | Build and run the profile detached, waiting for health checks. |
| `down` | Stop the profile; remains available when capacity is below threshold. |
| `migrate` | Applies the Phase 3 Flyway schema using the supplied migration-role datasource; it does not start the web server. |
| `seed` | Exit 3 until deterministic seed data has an owning phase. |
| `evaluate` | Exit 3 until Phase 8 owns the benchmark harness. |

The first Java dependency scan can take substantially longer while it builds
the NVD database. Its data remains in the D-backed cache. The disabled
`minio-review` profile is deliberately non-routable while blocker B-012 is
unresolved; do not replace its deliberately invalid image sentinel locally.

Before `dev` or `up`, verify Docker's daemon/build storage is backed by an
approved capacity-monitored volume, then set
`OPS_DOCKER_STORAGE_VERIFIED=true` in the calling process. On this Windows
workstation, inspect Docker Desktop's `CustomWslDistroDir` in
`$env:APPDATA\Docker\settings-store.json`; it must resolve to `D:`. On Linux,
inspect `docker info --format '{{.DockerRootDir}}'` and the filesystem reported
by `df`. The repository cannot safely relocate Docker Desktop/WSL storage and
therefore blocks Compose builds until the operator attests this check.
CI performs the equivalent `docker info` plus `df` check immediately before
setting its process-scoped attestation; downstream jobs repeat filesystem
preflight because hosted runners do not share the bootstrap job's disk state.

Set all three database passwords and the attestation only in the process that
launches Compose. `POSTGRES_PASSWORD` is the migration owner secret;
`POSTGRES_APP_PASSWORD` is the non-owner web runtime secret; and
`POSTGRES_DISPATCHER_PASSWORD` belongs only to the dormant outbox dispatcher
identity. All three values must be pairwise different.
Fresh PostgreSQL data directories also receive the fixed non-login
`opsmind_context_resolver` role used by the narrow RLS membership resolver; it
has no password and is not an interactive login. The separate non-login
`opsmind_dispatch_resolver` owns only bounded tenant scheduling and workload
binding functions. Compose creates the dispatcher database principal but does
not start a dispatcher process.
The launcher never reads non-empty password fields from `.env`:

```powershell
$env:POSTGRES_PASSWORD = '<runtime-secret>'
$env:POSTGRES_APP_PASSWORD = '<different-runtime-secret>'
$env:POSTGRES_DISPATCHER_PASSWORD = '<third-distinct-runtime-secret>'
$env:OPS_DOCKER_STORAGE_VERIFIED = 'true'
.\scripts\dev\opsmind.ps1 up
```

```sh
export POSTGRES_PASSWORD='<runtime-secret>'
export POSTGRES_APP_PASSWORD='<different-runtime-secret>'
export POSTGRES_DISPATCHER_PASSWORD='<third-distinct-runtime-secret>'
export OPS_DOCKER_STORAGE_VERIFIED=true
./scripts/dev/opsmind.sh up
```

OIDC endpoints and policy values are non-secret configuration and are allowed
in the untracked `.env`; bearer tokens and client credentials are not. The
checked-in defaults intentionally keep the API fail-closed:

| Variable | Safe local default | Contract |
|---|---|---|
| `OPSMIND_SECURITY_MODE` | `fail-closed` | `/api/v1/**` denies until explicitly changed to `oidc`. |
| `OIDC_ISSUER_URL` | non-routable endpoint sentinel | OIDC mode requires an HTTPS issuer with discovery/JWKS support. |
| `OIDC_AUDIENCE` | `opsmind-platform-api` | Token must contain this audience. |
| `OIDC_REQUIRED_AMR` | `mfa` | Token `amr` must include this exact value. |
| `OIDC_MAX_TOKEN_LIFETIME` | `PT5M` | `exp - iat` may not exceed 300 seconds; validation accepts 1–5 minutes. |
| `OIDC_CLOCK_SKEW` | `PT60S` | Timestamp skew; validation accepts 0–60 seconds. |
| `OIDC_JWKS_REFRESH_MINIMUM_INTERVAL` | `PT1S` | Minimum gap between outbound requests to the same OIDC target URI; validation accepts 100 milliseconds–1 minute. |

The resource-server decoder accepts only RS256. Its issuer-discovery and JWKS
HTTP client uses 500-millisecond connect and read timeouts. It permits at most
one outbound request per exact target URI, per Platform API instance, during
the configured minimum interval; discovery and JWKS URIs are tracked
independently. This is not a cluster-wide limit. Another request to the same
target inside the interval fails closed, so a genuine signing-key rotation can
temporarily reject a token until the interval elapses.

`OPSMIND_DISPATCHER_ENABLED=false` remains the safe application default. Phase
3 proves the database identity, permissions, scheduler, and lease repository;
it does not start a polling loop or publish to any external system.

With the Compose persistence profile, authenticated API requests also resolve
the issuer/subject against the platform user table on every request. Local
platform-user deprovisioning therefore denies the next request. Disabling the
user only in the upstream IdP blocks new login but does not invalidate an
already issued stateless access JWT. Its issuance lifetime is `PT5M`, while
timestamp enforcement also applies configured clock skew (`PT30S` in the
conformance harness and `PT60S` in checked-in defaults). That produces policy
upper bounds of 330 and 360 seconds respectively; the run proves immediate
post-disable acceptance but did not live-measure the disable-to-denial horizon.

## Local Keycloak Reference Conformance

After the storage capacity/root preflight, run the isolated reference harness:

```powershell
pwsh -NoProfile -File .\scripts\validation\run-phase-03-keycloak-conformance.ps1
pwsh -NoProfile -File .\scripts\validation\verify-phase-03-keycloak-evidence.ps1
```

The harness packages the current Platform API, starts the digest-pinned
Keycloak 26.7 image with ephemeral TLS and users, exercises the browser and
resource-server boundaries, removes its temporary files/container, and writes
`artifacts/verification/phase-03/identity-delegation.txt`. The 2026-07-21 local
Windows run recorded `Result=PASS`, `RuntimeSecretsPersisted=NO`, and
`EvidenceScope=REFERENCE_CONFORMANCE_NOT_PRODUCTION` for:

- Authorization Code with PKCE S256; direct grant and wrong verifier denial;
- TOTP enrollment without MFA assurance, MFA `amr`, and exact same-code/
  same-timestep replay denial;
- RP-initiated logout and refresh-after-logout denial;
- missing-MFA, anonymous, and tampered-signature Platform API denial;
- JWKS rotation refresh, old refresh-token reuse denial after rotation,
  an independent refresh session as the pre-revocation positive control,
  refresh-token revocation, and disabled-user new-login denial.

The live schema-v2 artifact records
`ExistingJwtAfterIdpDisable=PREISSUED_JWT_STILL_ACCEPTED`,
`RefreshTokenRotationReuseDenied=PASS`,
`RefreshTokenIndependentSessions=PASS`,
`RefreshTokenPreRevocationControl=PASS`,
`AccessTokenLifetimeSeconds=300`, `ConfiguredClockSkewSeconds=30`,
`MaximumResidualAcceptanceSeconds=330`, and
`DisableToDenialHorizon=NOT_LIVE_MEASURED`. The upper bound is policy evidence,
not a timed expiry observation. The runner binds the manifest digest of its
profile/source inputs and the exact packaged Platform API JAR digest, verifies
cleanup, then publishes evidence atomically. The separate verifier checks those
digests and rejects stale or token-bearing evidence. The 2026-07-21 schema-v2
run completed in 124.694 seconds; its independent verifier passed both the
profile and packaged-JAR digests after the full Maven verification rebuild.

On execution or cleanup failure, the runner publishes only
`identity-delegation-failure.txt`: at most 100 sanitized diagnostic lines with
runtime secrets, bearer values, JWTs, authorization query parameters, control
characters, and oversized values removed. Success and failure artifacts are
mutually exclusive, and CI uploads whichever exists. The failure artifact is
diagnostic evidence only and can never satisfy the schema-v2 success verifier.

The ignored transcript also records revision/dirty state, schema and scenario
versions, runtime versions, configuration digest, command, and timestamps. Its
`CodeRevision=UNBORN` and `WorkspaceDirty=YES` fields make it reproducible local
evidence, not immutable release evidence. The Linux `identity-conformance` job
is configured in `.github/workflows/pr-quality.yml`; it has not run remotely.
This reference does not authorize a production IdP or prove federation,
break-glass, state/nonce assurance, browser/BFF session ownership, general
bearer replay prevention, delegated capabilities, or immediate access-token
revocation.

To reproduce the local Phase 3 database boundary on Windows, run the
self-contained disposable harness:

```powershell
$env:JAVA_HOME = (Get-ChildItem .\.opsmind\cache\tools -Directory `
  -Filter 'temurin-jdk-21*' | Sort-Object Name -Descending | Select-Object -First 1).FullName
.\scripts\validation\run-phase-03-local-postgres-contract.ps1
```

The harness packages the current source before provisioning anything, refuses
pre-existing fixed OpsMind roles, creates a random database and random runtime
passwords, applies both Flyway migrations through that packaged application,
runs the pooled RLS, platform-user-status, two-role dispatcher, and messaging
fault matrices plus the SQL contract, then removes the exact database and four
roles. Success requires `PackageExit=0`,
`PoolContractExit=0`, `ContractExit=0`, and `ResidualObjects=0`. It never
prints the generated passwords.

## Capacity Status Levels

| Condition | Status | Allowed work |
|---|---|---|
| All configured thresholds pass | Ready | Work within the current approved phase |
| Any drive/root check blocks | Blocked | Read-only inspection, planning, and small documentation edits only |
| Capacity changes rapidly | Investigate | Identify process/cache growth before heavy work |
| Evidence/audit storage unavailable | Fail closed | No new external write or remediation |

The recurring workstation monitor is read-only. It may warn, but it does not delete Temp files, prune Docker, remove volumes, stop WSL, or alter pagefile settings.

## Safe Recovery Procedure

1. Stop starting new heavyweight work.
2. Record current C:/D: free space using the capacity script.
3. Inspect known consumers without deleting: Temp, Docker images/build cache, WSL virtual disks, application caches, pagefile growth, and active processes.
4. Obtain operator approval for exact cleanup targets.
5. Prefer recoverable cleanup and never remove Docker volumes or project directories implicitly.
6. Re-run capacity and root checks.
7. Save the passing transcript before resuming.

Stopping WSL or Docker may interrupt active workloads. Pagefile space may not return immediately and can depend on workload termination or reboot. Neither action is automated by this repository.

## Development Flow

The expected development flow is:

1. Run capacity and storage-root preflight.
2. Run `setup` after a clean clone or lockfile change.
3. Load runtime secrets into the process only from an approved secret channel; never place them in repository-local `.env` files.
4. Run the narrow service checks for changed code.
5. Run shared contract and boundary tests when public surfaces change.
6. Generate verification evidence under the configured artifact root.
7. Update affected docs and phase status only when evidence passes.
8. Request review before commit, push, deploy, or destructive action.

## Troubleshooting

### `pwsh` is missing

Use `powershell.exe` on Windows. Phase 1 scripts are compatible with Windows PowerShell 5.1. Later CI may standardize PowerShell 7 separately.

### A configured root is reported missing

Review the resolved path. If it is an approved repository-contained default on the correct volume, run `assert-storage-roots.ps1 -CreateMissing` or the portable `--create-missing` option. Auto-creation is intentionally limited to repository-contained roots; an external configured root must be provisioned explicitly by the operator. Root validation creates only the authorized directories and a short-lived writability probe.

When `OPS_ARTIFACT_ROOT` is missing or unsafe and no explicit evidence path was supplied, capacity and governance commands emit their bounded transcript to stdout and do not create or follow the artifact path. Run the storage-root preflight first; never redirect default evidence into a root that the guard rejected.

### A root is rejected as overlapping

Assign paths that are neither equal nor nested inside one another. Combining cache, retained evidence, data, and model roots makes deletion and retention unsafe.

### C: falls below 10 GB during work

The next preflight blocks. Stop heavyweight work, inspect growth, and follow the approved recovery procedure. Do not lower the threshold merely to make the check pass.

### `doctor` reports a tool mismatch

`doctor` compares Node, pnpm, Java, Maven, Python, and uv against the checked-in
version contracts and exits 6 on a mismatch. Install or select the declared
toolchain; do not weaken a version file to match an incidental host runtime.

## Verification Evidence

- `OPS_ARTIFACT_ROOT/verification/phase-01/disk-preflight.txt`
- `OPS_ARTIFACT_ROOT/verification/phase-01/storage-roots.txt`
- `OPS_ARTIFACT_ROOT/verification/phase-02/foundation-validation.txt`
- `OPS_ARTIFACT_ROOT/verification/phase-02/repository-layout.txt`
- `OPS_ARTIFACT_ROOT/verification/phase-03/identity-delegation.txt` (local/reference only)
- `OPS_ARTIFACT_ROOT/verification/phase-03/identity-delegation-failure.txt` (failure diagnostics only)
- Phase 2 Windows and portable command-surface test results
- dependency-check reports under each Java service's ignored `target/` tree

## Unresolved Questions

CI defines clean-bootstrap lanes for both hosted Linux and Windows runners;
their remote execution evidence is still required before the Phase 2 gate can
claim clean-clone parity. The local object-store implementation remains blocked
by B-012 pending a supported backend decision.
