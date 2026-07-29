---
phase: 1
title: design-and-configuration
status: completed
effort: 0.5 day
---

# Phase 1: design-and-configuration

## Overview

Define the bounded configuration contract and freeze the admission semantics
before changing executor behavior.

## Files

Create:

- `services/tool-gateway/src/main/java/ai/opsmind/toolgateway/config/ConnectorBulkheadProperties.java`
- `services/tool-gateway/src/test/java/ai/opsmind/toolgateway/config/ConnectorBulkheadPropertiesTest.java`

Modify:

- `services/tool-gateway/src/main/resources/application.yaml`
- `services/tool-gateway/.env.example`

## Implementation Steps

1. Add dedicated `@ConfigurationProperties` instead of widening
   `GatewaySettings`, whose many direct test constructors are unrelated to
   connector capacity.
2. Preserve the global default of 32 concurrent connector operations.
3. Add a conservative per-tenant default of 4 and require
   `1 <= perTenantConcurrency <= globalConcurrency`.
4. Bound the global value to a documented operational range and fail startup on
   zero, negative, or internally inconsistent values.
5. Expose only non-secret environment overrides in `application.yaml`.
6. Document that admission is fail-fast: the Gateway does not park unbounded
   request threads behind a tenant semaphore.

## Success Criteria

- [ ] Defaults bind to global `32` and per-tenant `4`.
- [ ] Invalid zero, negative, over-global, and excessive values fail closed.
- [ ] Configuration adds no credential or secret-bearing field.
- [ ] Focused property tests pass.

## Risks and rollback

- An overly small tenant default can increase safe denials during bursts. Keep
  it configurable and preserve the stable denial code.
- Rollback is a source/config revert; no persisted state or migration changes.
