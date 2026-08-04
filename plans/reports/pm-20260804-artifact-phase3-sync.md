# Artifact Phase 3 sync — 2026-08-04

## Scope

- Fast-forwarded local `main` from `b1ee08f` to `origin/main` `082bd2e`, which
  contains the reviewed access/lifecycle/reconciliation shell.
- Integrated the follow-up persistence commit as `9f20589` and registered
  `ArtifactLifecycleService` as a Spring component so the repository wiring is
  constructible when persistence is enabled.
- Replaced runtime-role table mutation with the least-privilege V019
  `SECURITY DEFINER` transition capability; `opsmind_app` still cannot update
  `evidence_artifacts` directly.
- Bound lifecycle/read lookups to `runId`, derived transition time from the
  database clock, and added the authorized object-probe application entry point.
- Added and CI-wired the disposable V018-to-V019 lifecycle PostgreSQL contract.
- Updated `scripts/validation/validate-phase-04c-evidence-artifacts.mjs` to
  validate the Phase 3 access, lifecycle, persistence, and reconciliation
  boundaries and to report the implemented metadata-only state accurately.

## Verification

- Artifact validator: `Errors=0`, `CheckpointResult=PASS`.
- Node syntax check and `git diff --check`: pass.
- Platform API full suite: 549 tests, 0 failures, 0 errors, 67 skipped.
- Lifecycle/read focused suite: 14 tests, 0 failures, 0 errors, 0 skipped.
- Disposable PostgreSQL lifecycle contract: V018 boundary, V019 capability,
  metadata/event/audit atomicity, rollback, and cleanup all PASS.
- Disposable metadata and object contracts PASS; fresh Flyway migration reached V019.
- Production backend/KMS and object-byte streaming remain explicitly deferred.
- Local storage preflight is healthy after cleanup (C: 12.21 GB, D: 21.51 GB).

## Remaining gates

- Keep B-006, B-008, B-011, and B-012 open; no production readiness claim.
- Object stream opening, production backend/KMS, scanning, retention/deletion,
  restore drill, and provider-visible artifact analysis remain parent Phase 4
  gates.

## Unresolved questions

- CI should run the new lifecycle runner with separated roles and the pinned
  pgvector image. Local proof used Java 24 while the module target remains Java 21.
