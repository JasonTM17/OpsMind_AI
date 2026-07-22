# Phase 4A Database Worker Report

## Status

DONE_WITH_CONCERNS. Source, integration contracts, and disposable PostgreSQL
runner implemented. Live PostgreSQL/Maven evidence not executed because the
mandatory capacity gate is red: C: 8.95 GB free; repository minimum 10 GB and
user operating threshold 12 GB. D: 27.68 GB free.

## Delivered

- Added additive `V003__incident_control_plane.sql`; V001/V002 bytes unchanged.
- Added tenant/project-safe incident aggregate with exact severity/status
  vocabulary, optimistic version trigger, legal transition graph, resolution
  requirements, reopen clearing, actor binding, composite authority FKs, forced
  RLS, org-first indexes, and least-privilege grants.
- Added immutable `incident_timeline_events` with incident-version uniqueness,
  typed event kinds, bounded trace/reason/payload, current-version guard,
  update/delete/truncate denial, and no dispatcher authority.
- Hardened `audit_events` with per-tenant sequence, advisory transaction lock,
  database-derived previous/digest values, caller-forgery override, SHA-256 over
  canonical stored fields, and update/delete/truncate denial.
- Upgrade backfill temporarily removes FORCE RLS only under the migration
  transaction/DDL lock, re-chains and verifies every existing row, restores
  FORCE RLS, then asserts the final catalog state. This supports a non-BYPASS
  table-owning migration role instead of relying on superuser visibility.
- Added migration digest/static contracts, deterministic tenant/project
  fixtures, SQL-layer incident denial/transition/immutability tests, and audit
  digest/concurrent-chain tests.
- Added Windows runner that requires capacity/storage-root checks plus explicit
  D-backed Docker attestation, uses a unique PostgreSQL 18 container, provisions
  a distinct non-super/non-BYPASS Flyway owner, proves both fresh and V002->V003
  upgrade paths, cleans exact generated resources, sanitizes bounded failures,
  and atomically publishes success evidence only after cleanup.
- Added portable SQL verifier for migration history, forced RLS, privilege
  separation, no-context invisibility, proof rows, digest recomputation, and
  linear audit continuity.

## Verification

- PowerShell parser: PASS.
- Bash `-n`: PASS.
- Static V003 markers: PASS.
- V001 SHA-256: `7fce0dc7639490c6a888d949d8857c28f8fb94fc8d4fafbfc7246465115e39f0`.
- V002 SHA-256: `809536725bbf37623802531bf0574323c4e3e86513664a8d921c68516c874faf`.
- Owned-file trailing whitespace scan: PASS.
- Maven compile/tests: NOT RUN; capacity gate blocked heavy work.
- PostgreSQL 18 fresh/upgrade matrix: NOT RUN; capacity gate blocked Docker.
- Evidence files: NOT PUBLISHED; no live result was fabricated.

## Concerns / Blockers

- Re-run `scripts/validation/run-phase-04-local-postgres-contract.ps1` only after
  C: reaches at least 10 GB free, preferably the user's 12 GB threshold, and
  `OPS_DOCKER_STORAGE_VERIFIED=true` reflects a current D-backed Docker check.
- The official `postgres:18-bookworm` tag is acceptable for local/reference
  proof because the runner records actual server version. Immutable CI/release
  evidence should pin an approved image digest.
- Java compilation and live SQL semantics remain unproven until that runner
  passes; failures must produce only `phase-04-postgres-failure.txt`, never the
  two success artifacts.

## Unresolved Questions

- None requiring product input. Remaining work is capacity recovery and live
  verification, not a schema decision.
