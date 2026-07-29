---
phase: 3
title: isolation-and-release-proof
status: completed
effort: 1 day
---

# Phase 3: isolation-and-release-proof

## Overview

Prove tenant fairness and every permit lifecycle with deterministic concurrency
tests rather than timing-only sleeps.

## Files

Create:

- `services/tool-gateway/src/test/java/ai/opsmind/toolgateway/application/TenantConnectorBulkheadTest.java`
- `services/tool-gateway/src/test/java/ai/opsmind/toolgateway/application/BoundedConnectorExecutorPermitLifecycleTest.java`
- `services/tool-gateway/src/test/java/ai/opsmind/toolgateway/application/BoundedConnectorExecutorTestSupport.java`

Modify:

- `services/tool-gateway/src/test/java/ai/opsmind/toolgateway/application/BoundedConnectorExecutorTest.java`

## Implementation Steps

1. Use latches/barriers and bounded test waits to hold connector work
   deterministically; do not rely on scheduler timing or long sleeps.
2. Prove a second operation for the same tenant is rejected at the configured
   limit even when the two requests use different project IDs.
3. While tenant A is saturated, prove tenant B can execute if global capacity
   remains.
4. Prove global capacity still bounds the aggregate across different tenants.
5. Prove permit and registry cleanup after normal return, checked/runtime
   failure, submission rejection, timeout with cooperative cancellation,
   timeout with temporarily ignored interruption, and cancellation before task
   start.
6. Prove an idle registry returns to zero entries and a concurrent
   release/reacquire does not split one tenant across two active slots.
7. Exercise `ToolExecutionService` far enough to show the verified scope, not
   duplicated request authority, reaches admission.

## Success Criteria

- [ ] Same tenant/different project shares one cap.
- [ ] Different tenants remain isolated under saturation.
- [ ] Global and tenant limits are both observed under concurrency.
- [ ] All release paths pass without sleeps that can hide races.
- [ ] Ignored interruption never causes early permit release.
- [ ] Registry cardinality returns to zero after quiescence.
- [ ] Existing timeout and stable-denial assertions still pass.

## Risks and rollback

- Concurrency tests can become flaky if they assert wall-clock ordering. Prefer
  explicit latches, futures, and bounded `await` calls.
- Test-only executor injection must remain package-private and must not alter
  production bean selection.
