---
title: Tenant-scoped connector bulkhead
description: >-
  Add fail-fast per-tenant connector admission derived from verified capability
  scope while preserving the global connector ceiling and stable denial
  contract.
status: completed
priority: P1
branch: feature/tenant-connector-bulkhead
tags:
  - tool-gateway
  - multi-tenant
  - reliability
  - security
blockedBy: []
blocks:
  - Phase 6 tenant-bulkhead proof
created: '2026-07-29T07:27:02.156Z'
createdBy: 'ck:plan'
source: skill
---

# Tenant-scoped connector bulkhead

## Overview

`BoundedConnectorExecutor` currently has one process-wide 32-permit semaphore.
It bounds total connector concurrency but does not stop one tenant from consuming
the entire allowance. This plan adds a per-tenant admission layer keyed only
from the verified `TenantProjectScope`, keeps the global ceiling, rejects
capacity exhaustion with the existing `execution.backpressure` contract, and
proves permits and registry entries cannot leak across success, failure,
timeout, cancellation, or rejected submission.

The smallest safe design is fail-fast admission with no hidden in-memory wait
queue. Tenant admission happens before global admission so a saturated tenant
cannot reserve global capacity. The key is `tenantId`, not tenant/project:
different projects belonging to one tenant must share the same allowance.

This is an incremental Phase 6 checkpoint. It does not claim the full Tool
Gateway or Phase 6 exit is complete.

## Phases

| Phase | Name | Status |
|-------|------|--------|
| 1 | [design-and-configuration](./phase-01-design-and-configuration.md) | Completed |
| 2 | [tenant-capacity-enforcement](./phase-02-tenant-capacity-enforcement.md) | Completed |
| 3 | [isolation-and-release-proof](./phase-03-isolation-and-release-proof.md) | Completed |
| 4 | [validation-and-documentation](./phase-04-validation-and-documentation.md) | Completed |

## Dependencies

- Runtime authority: `TenantProjectScope.fromVerifiedCapability(...)` must remain
  the only scope source passed into connector admission.
- Branch base: PR #25 head `78c37566a39d40a4588a2d5ed042476684827f0c`.
  Work may proceed independently; landing waits until PR #25 is merged or the
  branch is rebased onto its merged `main`.
- No database migration, provider credential, external call, or Docker workload
  is required.

## Acceptance criteria

- One tenant cannot occupy more than its configured connector allowance,
  including when it uses multiple projects.
- A saturated tenant does not consume another tenant's allowance.
- Total running connector work never exceeds the existing configurable global
  ceiling.
- Capacity exhaustion remains a fail-closed `execution.backpressure` denial and
  does not expose tenant identifiers.
- Permits are released exactly once. A timed-out connector that ignores
  interruption retains capacity until it actually exits.
- Idle tenant registry entries are evicted without an acquire/release race.
- Focused Tool Gateway tests, Phase 6 validator, diff check, and secret scan pass.
- Phase 6 documentation removes only the tenant-bulkhead blocker; live
  connector, artifact lifecycle, connector-family, and provider-cancellation
  blockers remain explicit.
