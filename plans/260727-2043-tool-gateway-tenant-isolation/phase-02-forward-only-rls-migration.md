---
title: Forward-only RLS Migration
status: in-progress
---

# Phase 2: Forward-only RLS Migration

## Work

- [x] Add V003 without changing V001/V002 history.
- [x] Add safe tenant/project current-context and transaction-local setter
  functions in the `tool_gateway` schema.
- [x] Add nullable scope columns to legacy `tool_audit_events`; do not invent
  tenant attribution for historical rows.
- [x] Add a separate append-only, insert-only unverified security-audit table.
- [x] Enable and force RLS on receipts and verified audit events.
- [x] Add exact tenant/project policies, indexes, comments, grants, and public
  revocations.
- [ ] Verify fresh migration, V002-to-V003 upgrade, ownership, runtime
  `NOBYPASSRLS`, and legacy row preservation.

## Files

- `services/tool-gateway/src/main/resources/db/migration/V003__*.sql`
- migration/static contract tests and CI evidence wiring if required

## Validation

- Missing or malformed settings expose zero tenant rows and reject tenant writes.
- Owner/migrator cannot silently bypass forced RLS.
- Runtime cannot read or mutate either audit lane.
- Existing V001/V002 checksums remain unchanged.

## Rollback

Forward recovery only. Restore a compatible runtime or add V004; do not rewrite
Flyway history or disable RLS.
