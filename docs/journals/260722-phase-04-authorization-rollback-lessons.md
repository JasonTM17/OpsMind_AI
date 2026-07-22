# Phase 4 Authorization and Rollback Lessons

**Date**: 2026-07-22 09:53
**Severity**: High
**Component**: Incident control plane authorization, timeline/audit ledger, and transaction boundary
**Status**: Resolved

## What Happened

Checkpoint 4A initially treated `FOR KEY SHARE` as enough to freeze the authorization tuple during an incident mutation. It was not. A concurrent `UPDATE project_memberships SET status = 'suspended'` could proceed because changing `status` does not modify a referenced key. The empirical revocation test exposed the mistake; V003 now uses `FOR SHARE` across the user, organization, project, and membership rows. The same verification pass added a real PostgreSQL outbox collision to prove rollback of the entire incident write.

## The Brutal Truth

We confused a lock name with a lock guarantee. That is dangerous in authorization code: the SQL looked deliberate while still allowing authority to change mid-write. The 300 ms revocation timeout was more valuable than our confidence. It was frustrating to lose time to a basic lock-mode semantic, then trip over the test harness itself, but far better here than during an operator revocation in production.

## Technical Details

`IncidentAuthorizationRevocationIntegrationTest` now proves the authority update blocks until the authorized mutation commits, then proves the suspended member receives a non-enumerating `404`. V003 also validates timeline semantics, not just immutability: incident version, actor, timestamp, transition status, and payload must match authoritative incident state. Audit rows must exactly match their timeline event, while PostgreSQL assigns sequence and SHA-256 chain fields.

`IncidentTransactionalRollbackIntegrationTest` pre-seeds `outbox_events` with the generated event UUID. The real unique-key conflict maps to `event.duplicate-conflict`; incident, timeline, audit, and idempotency counts remain zero while the baseline outbox row remains one. The first seed attempt passed a raw `Instant` and failed with `Can't infer the SQL type to use for an instance of java.time.Instant`; converting via `Timestamp.from(OCCURRED_AT)` fixed the harness without weakening the assertion.

## What We Tried

- Rejected `FOR KEY SHARE`: it permits non-key authority updates.
- Rejected append-only as sufficient: immutable but semantically false history is still corrupt history.
- Rejected a mocked outbox failure as rollback proof: it cannot prove PostgreSQL transaction behavior.

## Root Cause Analysis

We relied on plausible API semantics instead of testing the exact database conflict matrix and transaction boundary.

## Lessons Learned

Authorization locking, ledger validity, and rollback require adversarial database tests. Unit orchestration tests are necessary but not evidence of database behavior.

## Next Steps

- Platform API/database owners: keep both integration tests in the Phase 4 guarded matrix before any checkpoint promotion.
- CI/release owner: rerun on an immutable revision and clean workspace before release claims.
- Phase 4 owner: finish deferred lifecycle/API scope and prerequisite gates before marking Phase 4, G2, or release complete.

## Unresolved Questions

- None for these corrected defects; broader Phase 4 and release gates remain open.
