---
phase: 2
title: tenant-capacity-enforcement
status: completed
effort: 1 day
---

# Phase 2: tenant-capacity-enforcement

## Overview

Implement strict tenant admission and integrate it after capability
verification but before connector I/O.

## Files

Create:

- `services/tool-gateway/src/main/java/ai/opsmind/toolgateway/application/TenantConnectorBulkhead.java`

Modify:

- `services/tool-gateway/src/main/java/ai/opsmind/toolgateway/application/BoundedConnectorExecutor.java`
- `services/tool-gateway/src/main/java/ai/opsmind/toolgateway/application/ToolExecutionService.java`
- `services/tool-gateway/src/main/java/ai/opsmind/toolgateway/config/GatewayRuntimeConfiguration.java`

## Implementation Steps

1. Build a reference-counted tenant-slot registry keyed by the verified
   `TenantProjectScope.tenantId()`. Retain/release and eviction must be atomic
   per key so a concurrent acquire cannot attach to an evicted slot.
2. Acquire the tenant permit before the global permit. Use fail-fast admission;
   release the tenant permit immediately if global admission fails.
3. Return one idempotent permit bundle that owns both permits and removes an
   idle tenant slot after its final holder/reference exits.
4. Change `BoundedConnectorExecutor.execute(...)` to require the trusted scope.
   Do not reconstruct scope from `ToolExecutionRequest`.
5. Preserve the signed request/manifest deadline for actual connector
   execution and keep `execution.backpressure`, `connector.timeout`, and
   `connector.cancelled` contracts stable.
6. Add a queued/running/released state guard around submitted work:
   cancellation before start releases capacity, while cancellation after start
   retains capacity until the connector body exits. Submission rejection and
   synchronous setup failure also release exactly once.
7. Pass the already verified `trustedScope` from `ToolExecutionService`; wire
   the dedicated properties through `GatewayRuntimeConfiguration`.
8. Keep compatibility constructors only where they reduce unrelated test churn;
   production wiring must always use explicit validated properties.

## Success Criteria

- [ ] No connector operation is admitted from request-body tenant data alone.
- [ ] Tenant admission precedes global admission.
- [ ] No path double-releases or leaks a tenant/global permit.
- [ ] Backpressure messages contain no tenant or project identifier.
- [ ] Production bean wiring uses validated configuration.
- [ ] Main code remains modular; no edited Java file crosses 200 lines without
      a justified existing exception.

## Risks and rollback

- Releasing on `Future.cancel(true)` can over-admit if the connector ignores
  interruption. The state guard must release running work only from its
  `finally` path.
- Reference-count eviction has an ABA race if implemented as a plain
  get/remove. Use atomic per-key map operations and identity checks.
- Rollback restores the global-only executor; no data rollback is needed.
