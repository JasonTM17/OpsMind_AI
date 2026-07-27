---
title: Tool Gateway Tenant and Project Isolation
description: >-
  Close B-016 with capability-derived scope propagation, forced PostgreSQL RLS,
  transaction-local context, separate unverified audit storage, and adversarial
  pool-reuse/collision evidence.
status: in-progress
priority: P0
effort: 12-18h
branch: feature/tool-gateway-tenant-isolation
tags:
  - phase6
  - tool-gateway
  - tenant-isolation
  - postgresql
  - security
blockedBy: []
blocks:
  - B-016
created: 2026-07-27T20:43:00+07:00
createdBy: 'ck:plan'
source: skill
---

# Tool Gateway Tenant and Project Isolation

## Outcome

The Tool Gateway keeps global nonce and `execution_id` semantics, but every
tenant-owned receipt and verified execution audit operation is authorized twice:
the Java application propagates scope from the verified delegated capability,
and PostgreSQL enforces the same tenant/project pair through explicit predicates
plus forced RLS bound with transaction-local settings.

Pre-capability failures never inherit tenant/project values from the request.
They append to a separate global, insert-only security-audit lane.

## Phases

| Phase | Name | Status |
|---|---|---|
| 1 | [Threat Model and Contract](./phase-01-threat-model-and-contract.md) | Completed |
| 2 | [Forward-only RLS Migration](./phase-02-forward-only-rls-migration.md) | In progress |
| 3 | [Scoped Runtime and Regression Tests](./phase-03-scoped-runtime-and-regression-tests.md) | In progress |
| 4 | [Security Review, Evidence, and Documentation](./phase-04-security-review-evidence-and-documentation.md) | In progress |

## Architecture Decision

- Trusted scope originates only from a successfully verified
  `VerifiedCapability`, after signature, claim, exact request binding, and nonce
  checks.
- Add immutable `TenantProjectScope`; propagate it through receipt claim,
  receipt lease, replay, finalization, abandon, and verified denial audit.
- Keep capability nonces global and one-use. Tenant scoping a nonce would weaken
  replay protection without a demonstrated product requirement.
- Keep `execution_id` globally unique to preserve the public contract. A foreign
  RLS-invisible collision returns the same non-enumerating `execution.conflict`
  response as another scope/digest mismatch.
- Keep connector I/O outside database transactions.
- Split audit persistence into scoped verified events and a global unverified
  security-audit table. Unverified APIs accept no tenant/project fields.
- Treat transaction-local settings as defense in depth against accidental
  cross-tenant access. Explicit tenant/project SQL predicates remain required.

## Acceptance Criteria

- `execution_receipts` and verified `tool_audit_events` have forced RLS using a
  transaction-local tenant/project pair.
- The runtime role remains non-owner and `NOBYPASSRLS`; readiness fails if the
  required schema usage, tables, grants, functions, forced-RLS flags, or exact
  sole policy definitions are absent or drifted.
- Claim, select, reclaim, complete, and abandon SQL contains explicit
  tenant/project predicates where applicable.
- The success audit append and receipt completion remain one atomic scoped
  transaction; connector execution remains outside the transaction.
- Invalid, expired, replayed, or otherwise unverified capability failures append
  no request-selected tenant/project data.
- Same-ID cross-tenant collisions are HTTP 409 conflicts, not HTTP 503 storage
  failures, and do not expose foreign response data or row existence details.
- Tests cover A-to-B-to-no-context pool reuse, malformed/missing context,
  cross-tenant replay/finalize/abandon, scoped and unverified audit lanes,
  rollback, stale leases, global nonce replay, role grants, migration upgrade,
  and readiness posture.
- Static validators, Tool Gateway tests, PostgreSQL trust contracts,
  cross-service evaluation, security scan, and code review are green at one
  immutable revision before B-016 is moved to resolved.
- Startup rejects any receipt lease shorter than the longest enabled connector
  duration plus the fixed finalization margin.
- Claim and reclaim persist the earlier of request deadline plus the margin and
  transaction time plus the configured lease; completion remains token-fenced
  after that effective expiry.

## Dependencies

- PostgreSQL 17 behavior and existing non-owner
  `opsmind_tool_gateway ... NOBYPASSRLS` role.
- Existing Phase 6 capability verification and canonical request binding.
- GitHub-hosted PostgreSQL verification while local `C:` capacity remains below
  the repository's 10 GiB heavyweight-work threshold.

## Risks and Rollback

- **RLS-invisible unique conflict:** use a zero-or-one scoped query after
  `ON CONFLICT DO NOTHING`; zero rows means non-enumerating conflict.
- **Pooled context leakage:** apply both settings only inside an active
  transaction and prove cleanup on commit, rollback, timeout, and reuse.
- **Legacy audit rows:** preserve them unchanged. They have no trustworthy
  tenant attribution and remain outside runtime-scoped visibility.
- **Rolling deployment:** V003 is expand-first. Old V002 runtime must not be
  deployed after forced RLS because it does not bind context; deploy migration
  and new runtime as one controlled compatibility boundary.
- **Forward recovery:** never edit or delete V001/V002. If application rollback
  is required, restore the new runtime or apply a new migration; do not disable
  forced RLS.

## Unresolved Questions

None for implementation. Production release remains constrained by the other
active blockers and is outside this plan.
