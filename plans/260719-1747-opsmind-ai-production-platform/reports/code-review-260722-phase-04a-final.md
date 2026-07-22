# Code Review — Phase 4A Incident Control Plane

## Scope

- Incident create/detail/transition/timeline boundary, authorization, idempotency, audit/outbox transaction, rollback, and verification harnesses.
- Latest follow-up: servlet-context body-size filter hardening and regression test.
- Evidence reviewed: `incident-contracts.txt`, `incident-domain.txt`, `incident-crud.txt`, `audit-and-concurrency.txt`, Phase 3 identity evidence, migration sources, and the current diff.

## Overall assessment

Checkpoint 4A is suitable to close as local/reference evidence. No P0, P1, or P2 code blocker was found. The core trust boundaries are implemented at the database/service boundary: tenant context is derived from verified identity, access is serialized with row locks, hidden resources use privacy-safe problem instances, and audit/timeline values are checked against authoritative rows.

## Critical and high-priority findings

None found.

## Medium / informational findings

1. Local evidence is not release proof. The PostgreSQL runner binds production source, contract, migration, and JAR digests, but the current local artifact does not yet bind every integration-test source or independently verify fresh Surefire report counts. This is acceptable for the explicitly labelled `LOCAL_REFERENCE_NOT_RELEASE_PROOF` checkpoint; the final release runner must bind the complete test manifest, immutable revision, registry digest, SBOM, signature, and provenance.
2. The first review identified that the body-size filter used a raw request URI and could miss a servlet context prefix. The follow-up changed matching to `servletPath + pathInfo` and added `JsonRequestBodyLimitFilterTest` covering `/opsmind/api/v1/...`; the fix is correct, but the capacity-gated full Maven/PostgreSQL evidence must be refreshed before release.
3. Evidence was produced from an unborn, dirty local workspace. It is intentionally reference-only and must not be represented as production validation.

## Verified behaviors

- One-winner transition and row-lock ordering under concurrency.
- Authorization revocation linearization and cross-tenant invisibility.
- Forced outbox failure rolls back incident, timeline, audit, and idempotency rows.
- Semantic timeline/audit payload forgery is rejected; digest/sequence fields remain database-derived.
- Fresh and upgrade migration paths preserve V001/V002 checksums.
- Nested incident paths and 404 responses do not reflect scoped identifiers.
- Request payload limits return a typed 413 problem before JSON deserialization.

## Recommended actions

1. Recover the C: free-space gate, rerun the full 87-test Maven suite and disposable PostgreSQL evidence, then compare all source/JAR digests.
2. Before release, run the immutable verifier on a pushed revision and publish the same signed multi-architecture digest to Docker Hub and GHCR; verify the GHCR package is linked to this repository.
3. Keep the repository About metadata and public contribution/security/support documents synchronized with the release workflow.

## Metrics

- Focused domain evidence: 25 tests, 0 failures, 0 errors, 0 skips (last capacity-safe run before the final filter follow-up).
- HTTP/filter focused evidence after follow-up: 11 tests, 0 failures, 0 errors, 0 skips.
- Full Maven baseline: 86 tests, 0 failures, 0 errors, 11 guarded skips; refresh required after the new regression test.
- Static Phase 4 contract validator: 11 schemas, 14 fixtures, 128 references, 6 operations, 0 errors.

## Unresolved questions

- Final Docker Hub namespace/repository names and protected publisher identity.
- Production identity/evidence backend and remote immutable-revision CI proof.
