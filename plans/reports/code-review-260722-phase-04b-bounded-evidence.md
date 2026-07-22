# Phase 4B Bounded Evidence Code Review

Date: 2026-07-22

## Scope

- V007 bounded evidence schema, triggers, RLS, and runtime grants.
- Evidence canonicalization, deterministic identity, transaction writer, and
  authorized reader.
- Investigation reducer/event transport and metadata-only persistence codec.
- PostgreSQL integration, rollback, immutability, authorization, and workflow
  gates.

## Assessment

No unresolved Critical or High finding remains in the reviewed checkpoint.
This is a bounded redacted-record control plane, not the large/raw artifact
lifecycle. Phase 4 and G2 remain open.

## Findings Resolved During Review

1. **Run ownership bypass for an empty evidence set — fixed.** The reader had
   returned an empty list before proving that `runId` belonged to the authorized
   organization/project/incident. It now checks the forced-RLS
   `investigation_runs` row first. Unit and PostgreSQL integration regressions
   cover foreign or unknown runs.
2. **Gateway replay provenance discarded — fixed.** The immutable record now
   retains `gateway_duplicate`; the writer and live integration assertion bind
   it to the accepted result.
3. **V007 future-plan filename collision — fixed.** The Phase 11 plan no longer
   pre-allocates V007 after V007 became the evidence migration.
4. **Oversized integration test modules — fixed.** V007 persistence and rollback
   checks moved into separate files below the 200-line review threshold.
5. **Mid-transaction evidence failure proof missing — fixed in CI coverage.** A
   canonical digest mismatch is injected after the snapshot/event path starts;
   the test requires snapshot, run-event, evidence, and audit state to remain at
   the prior committed boundary.

## Risk Checks

- Concurrency: existing optimistic revision and database advisory/row locks
  remain authoritative; evidence uniqueness is organization/run/intent scoped.
- Error boundaries: malformed content, digest drift, missing/foreign records,
  persistence failure, and hidden authorization paths fail closed.
- API contracts: no public evidence upload/download route added; existing
  reducer constructors remain compatible for in-memory tests.
- Input validation: content is object-only, canonical, redacted, structurally
  bounded, at most 65,536 UTF-8 bytes, and independently hashed in Java and SQL.
- Authorization: incident analyze scope/role, current membership, tenant
  transaction context, run ownership, forced RLS, and lifecycle state all gate
  reads.
- Data exposure: event and audit JSON serialize evidence metadata only; known
  content and transport provenance are absent from those payloads.
- Query efficiency: one indexed run-ownership probe plus one ordered evidence
  query, capped at 200 identifiers; no per-record query loop.
- Backward compatibility: predecessor migrations are unchanged; V007 is
  additive and fixture mode remains feature-gated.

## Verification

- Platform API: 145 tests, zero failures/errors, 18 environment-gated skips.
- Phase 4B static gate: PASS.
- Repository layout, actionlint, and diff checks: PASS.
- Secret scan: 1,121 files and 19 history commits, zero findings.
- Live PostgreSQL V007 migration/RLS/rollback tests: pending GitHub Actions.

## Unresolved Questions

- Supported S3-compatible replacement and its versioning, encryption, hold,
  restore, purge, and residency conformance remain B-006/B-008/B-012.
