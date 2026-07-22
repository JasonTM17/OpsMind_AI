# Test Report — 2026-07-21 — Phase 4A Incident Control Plane

## Scope

Read-only QA pass. No Maven, Docker, live PostgreSQL, dependency download, build, or package command was run because the C: storage gate is still red.

Storage check:

- C: 8.71 GB free
- D: 27.68 GB free
- Repo gate: 10 GB minimum
- User threshold: 12 GB preferred

## Test Results Overview

| Check | Result | Notes |
|---|---:|---|
| Phase 4 incident contract validator | PASS | Offline static validation only |
| Phase 3 trust-foundation validator | PASS | Compatibility surface intact |
| Repository layout validator | PASS | No layout errors |
| PowerShell parse of Phase 4 runner | PASS | Parse-only, no execution |
| JS syntax checks (`node --check`) | PASS | 10 Phase 4 JS modules |
| Docs validator | PASS with warnings | Pre-existing/false-positive warnings only |
| Shell syntax check (`bash -n`) | SKIPPED | `bash` resolves to `C:\WINDOWS\system32\bash.exe`; avoided WSL invocation |

Runtime tests executed: 0.

## Coverage Metrics

No runtime coverage report was produced. Live Java/Maven and PostgreSQL verification stayed blocked by storage.

| Metric | Value | Threshold | Status |
|---|---:|---:|---|
| Lines | not produced | 80% | BLOCKED |
| Branches | not produced | 70% | BLOCKED |
| Functions | not produced | 80% | BLOCKED |

## Verified Static Surfaces

- `node scripts/validation/validate-phase-04-incident-contracts.mjs`
  - `JsonSchemasParsed=11`
  - `JsonFixturesParsed=14`
  - `FixturePositiveCases=5`
  - `FixtureNegativeCases=7`
  - `LocalReferencesResolved=112`
  - `OpenApiOperations=6`
  - `Errors=0`

- `node scripts/validation/validate-phase-03-trust-foundation.mjs`
  - `FilesChecked=50`
  - `Errors=0`

- `node scripts/validation/validate-repository-layout.mjs`
  - `FilesChecked=317`
  - `OpenApiRoots=1`
  - `EvidencePublication=FILE`
  - `Errors=0`

- `node .claude/scripts/validate-docs.cjs docs`
  - 12 files checked
  - 24 internal links verified
  - 22 config keys confirmed
  - warnings only: `CustomWslDistroDir`, `PT1S`, `PT5M`, `PT30S`, `PT60S`, `LASTEXITCODE`, and similar token-like strings in docs, likely false positives rather than defects

## Source Inspection Findings

### Incident controller and state machine

- `IncidentControllerTest` has real assertions, not phantom execution:
  - exact `201`, `Location`, `ETag: "0"`, `X-Operation-Id`, and JSON body on create
  - missing and malformed `If-Match` rejected before mutation
  - detail read returns current `ETag`
  - unsupported auth fails closed
- `IncidentStateMachineTest` covers the full transition matrix and the resolution/reopen rules:
  - every legal and illegal transition is checked
  - `RESOLVED` requires root cause and resolution summary
  - reopen clears current resolution fields

### Incident service / replay / rollback

- `IncidentServiceTest` contains concrete contract checks:
  - durable-effect ordering is asserted with `InOrder`
  - exact replay returns cached semantics without new IDs or append effects
  - append failure rolls back and never completes idempotency
  - stale version rolls back before transition/event append
  - scope and role policy fails closed
  - shared event identity and outbox sequence are asserted
- This is real behavioral coverage, not empty test shells.

### Database and migration surface

- `MigrationContractTest` checks the actual SQL text for V001/V002/V003 and verifies V001/V002 SHA-256 stability.
- `IncidentControlPlaneIntegrationTest` is a real PostgreSQL integration test gated by `OPSMIND_PHASE4_DB_INTEGRATION=true`:
  - tenant/project boundaries
  - illegal transition and version guard
  - append-only timeline behavior
  - dispatcher invisibility
- `AuditLedgerIntegrationTest` is also real PostgreSQL work:
  - tenant chain recomputation
  - forged digest/sequence rejection
  - concurrent append ordering
  - update/delete/truncate denial
- `TransactionalAuditRepositoryTest` verifies SQL shape and transaction boundary handling:
  - trusted chain fields stay out of the app-side insert
  - append outside transaction is rejected
  - database rejection maps to a safe problem code

### Runner / cleanup behavior

- `scripts/validation/run-phase-04-local-postgres-contract.ps1` has real fresh/upgrade/cleanup logic on paper:
  - fresh V003 path
  - V001/V002 -> V003 upgrade path
  - cleanup and residual-container checks
  - bounded failure artifact emission
  - sanitized diagnostics
- But none of that live execution was allowed under current storage.

## Failed Tests

No repository test failed in this pass.

Tooling correction:

- One early `node --check` invocation accidentally included `.ps1` / `.sh` files and returned `ERR_UNKNOWN_FILE_EXTENSION`.
- That was corrected immediately by restricting `node --check` to the 10 Phase 4 `.mjs` modules, which all passed.

## Critical Issues

1. Full Phase 4A verification is blocked by storage.

   C: is at 8.71 GB free, below both the repository floor (10 GB) and the user threshold (12 GB). Because of that, Maven, Docker, and live PostgreSQL validation were not run. The incident control plane is therefore not fully evidence-backed yet.

## Recommendations

1. Recover C: storage above 12 GB, then rerun the blocked live gates in this order:
   - Maven package/test
   - disposable PostgreSQL runner
   - guarded Java integration tests
   - final evidence verification
2. Keep the current static contract checks in CI; they are passing and useful as a cheap guard.
3. Treat the docs-validator warnings as follow-up hygiene, not Phase 4 blockers, unless a doc claim is later proven false.

## Unresolved Questions

- When can the workspace reclaim enough C: storage to run Maven and disposable PostgreSQL safely?
- Should the next QA pass target only the blocked live gates, or repeat the static validators alongside them for a full evidence bundle?
