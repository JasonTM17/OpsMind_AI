# Tool Gateway Tenant-Isolation Security Review

- Date: 2026-07-27
- Scope: B-016 application scope propagation, PostgreSQL V003, audit lanes,
  readiness, leases, migration/CI evidence
- Revision: `269bd39e626836607fe66ed7eb050e1aa309044a`
- Result: immutable PASS

## Threat Model

| STRIDE area | Boundary and control | Local result |
|---|---|---|
| Spoofing | Platform workload JWT and exact delegated-capability binding precede trusted `TenantProjectScope` construction | PASS |
| Tampering | Explicit tenant/project predicates, forced RLS, exact-policy readiness, fenced lease token, append-only audit triggers | PASS |
| Repudiation | Scoped verified audit plus tenant-free unverified and authenticated delivery-rejection lanes | PASS |
| Information disclosure | Missing/malformed context sees zero rows; foreign global IDs return generic conflict with no response | PASS locally; DB execution pending |
| Denial of service | Bounded body/result/connector execution; unauthenticated traffic cannot append delivery audits | PASS |
| Elevation of privilege | Dedicated non-owner runtime, `NOBYPASSRLS`, schema/function/table least privilege, owner subject to forced-RLS gate | PASS statically; DB execution pending |

## Adversarial Review Disposition

Initial review found three P1 defects. All have local fixes and regression gates:

1. Digest/canonicalization failures escaped the decision boundary.
   - Null-bearing JSON lists now canonicalize.
   - Digest derivation runs inside the fail-closed boundary.
   - Non-canonicalizable requests append exactly one unverified decision before
     capability verification.
   - Authenticated malformed/validation/missing-capability delivery rejections
     append only tenant-free audit data.
2. Readiness accepted schema and same-name policy drift.
   - Nonce, receipt, and audit readiness require schema `USAGE`.
   - Receipt/audit readiness requires exactly one `ALL`, `public`,
     `PERMISSIVE` policy with the exact tenant-and-project `USING` and
     `WITH CHECK` expression.
   - PostgreSQL tests revoke schema access and substitute `USING (true)`.
3. Supported short leases allowed connector overlap.
   - Startup compares the lease with the longest enabled manifest duration plus
     a five-second completion margin.
   - Unsafe 100 ms lease/current 5 s manifest configuration now fails.
   - A follow-up review found the initial claim SQL still capped the lease at
     the raw request deadline. Claim and reclaim now persist the earlier of
     request deadline plus the margin and transaction time plus the configured
     lease.
   - A PostgreSQL test completes after the request deadline but inside the
     margin; the existing stale-token test still proves reclaim fencing.

Independent review round three reports zero unresolved P0/P1 issues.

## Invariants and Limits

- Trusted tenant/project values never originate from the request.
- Global nonce uniqueness remains intentional replay protection.
- Global `execution_id` compatibility remains; conflict output is
  non-enumerating.
- Connector I/O remains outside database transactions. Only audit plus receipt
  finalization is atomic.
- Custom PostgreSQL settings defend against application mistakes. They do not
  protect a fully compromised runtime database role able to issue arbitrary
  SQL; role isolation, query boundaries, and credential controls remain
  required.
- Historical V001/V002 audit scope is unknown. It remains immutable,
  unattributed, and invisible to tenant-scoped runtime reads.

## Evidence

- Reviewer-remediation tests: 13/13 pass.
- Full Tool Gateway suite: 65 tests, 0 failures/errors, 12 intentionally skipped
  PostgreSQL-gated tests.
- Phase 6 static checkpoint: zero errors.
- Phase 7 static checkpoint: zero errors.
- Repository layout: 893 files, zero errors.
- Secret scan: 0 findings across working tree, Git index, configured artifacts,
  and 129 historical commits.
- Upgrade harness syntax: PASS.

Immutable CI evidence for source
`269bd39e626836607fe66ed7eb050e1aa309044a`:

- PR Quality run `30279072972` and PostgreSQL job `90022080029` are terminal
  green. Artifact `8658901958`
  (`sha256:1e76f5c67d0abc726b6d0779022f5005d777fcea63e52123cc176d1baabf909e`)
  records the V002-to-V003 and isolation matrix; targeted persistence tests
  are 12/12 with zero failures/errors.
- Cross-service run `30279067839` and artifact `8658216777`
  (`sha256:a5e2798b5c3035bdca3992f083cefbafc8df0121f35bd1447dcf3d46478b27a7`)
  are terminal green. Scenarios A/B/C pass with 100/1/1 samples and cleanup
  verification passes.

## First Immutable CI Attempt

PR Quality run `30277638321`, PostgreSQL job `90017201442`, proved that the
initial completion-margin SQL left the deadline bind parameter ambiguous.
PostgreSQL rejected every receipt claim with:

```text
ERROR: LEAST types interval and timestamp with time zone cannot be matched
```

Cross-service run `30277633352` independently observed the same failure before
any Prometheus query. Claim and reclaim now cast the bound deadline explicitly
to `timestamptz`. The corrected immutable PostgreSQL/cross-service attempt is
recorded above.

## Required Immutable Evidence

All required immutable evidence is present in the recorded PostgreSQL and
cross-service artifacts: fresh V003 and V002-to-V003 migration, exact policy
catalog definitions and role grants, forced RLS against runtime and
table-owning migrator, same-tenant/foreign-project and no-context denial,
A-to-B-to-no-context pool reuse, cross-service regression at the exact source
SHA, and independent review round three with zero unresolved P0/P1 findings.

## Unresolved Questions

None for implementation. B-016 CI evidence is complete for this revision;
broader Phase 6 and production gates remain separate promotion blockers.
