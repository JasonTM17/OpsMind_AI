---
title: Scoped Runtime and Regression Tests
status: in-progress
---

# Phase 3: Scoped Runtime and Regression Tests

## Work

- [x] Bind transaction-local tenant/project settings after connection checkout.
- [x] Propagate scope through claims and immutable leases.
- [x] Scope replay, verified denial, success, complete, and abandon operations.
- [x] Keep pre-verification denials unverified/global and tenant-free.
- [x] Add explicit tenant/project SQL predicates in addition to RLS.
- [x] Map an RLS-invisible global ID collision to `CONFLICT`.
- [x] Make readiness verify the complete isolation posture.
- [x] Update fixture/fail-closed adapters without weakening production contracts.
- [x] Add adversarial integration and application tests.

## Test Matrix

| Case | Expected |
|---|---|
| Same scope, same ID/digest | in-progress or exact replay |
| Same ID, foreign tenant/project | non-enumerating conflict |
| Same tenant, foreign project via raw RLS | zero visible rows |
| Foreign lease complete/abandon | zero mutation / lease lost |
| A → B → no-context on one connection | no cross-scope rows |
| Malformed or missing GUC | fail closed |
| Success finalization rollback | neither audit nor receipt commits |
| Post-verification denial | scoped audit |
| Verification failure/replay | unverified audit, no tenant fields |
| Runtime role/readiness drift | readiness false |
| Schema `USAGE` revoked / same-name permissive policy | readiness false |
| Lease shorter than connector bound plus margin | startup failure |
| Completion after request deadline, inside margin | fenced completion succeeds |
| Authenticated malformed/missing-capability delivery | tenant-free audit |

## Files

- Tool Gateway application, audit, persistence adapters
- Tool Gateway application and PostgreSQL integration tests

## Validation

- Focused unit tests first.
- PostgreSQL tests against a disposable CI database.
- Full service test/build and repository validators after focused tests pass.

Local unit/static evidence passes. PostgreSQL and cross-service CI execution is
still required before this phase is completed.
