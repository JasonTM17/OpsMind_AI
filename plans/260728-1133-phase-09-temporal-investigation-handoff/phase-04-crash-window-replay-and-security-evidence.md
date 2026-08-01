---
phase: 4
title: "Crash-window replay and security evidence"
status: completed
effort: "1 day"
---

# Phase 4: Crash-window replay and security evidence

## Overview

Turn the design invariants into executable negative evidence. Tests must prove
recovery and tenant/data boundaries, not merely execute happy paths.

## Implementation Steps

1. Add a crash-window matrix covering:
   - committed start before claim;
   - crash after claim before Temporal RPC;
   - Temporal accepted then crash before ack;
   - expired lease and stale ack;
   - acknowledgement after token match but lease expiry;
   - duplicate concurrent dispatcher;
   - Temporal unavailable then retry;
   - max attempts/age/deadline exhaustion;
   - wrong event type/schema/digest/binding/workflow ID;
   - forged same-tenant run/actor/project/incident payload;
   - existing Temporal ID with wrong namespace/type/input digest;
   - foreign-tenant claim/read/ack;
   - poison path with `REJECTED` binding and bounded safe error code;
   - authorization revocation between request, handoff, and future activity;
   - more than 100 unrelated ready tenants without workflow-start starvation.
2. Add a Temporal history test using the test environment. Inspect serialized
   history and assert it contains only approved identifiers, budgets, and
   timestamps; assert representative prompt/evidence/token sentinels are absent.
3. Add migration upgrade/recovery and deterministic replay vectors. Re-run the
   current reducer replay tests to prove no Java/Python state-machine fork.
   Exercise fresh V010, V009 upgrade, safe initial-state backfill, unsafe
   nonterminal cutover block, and zero-orphan enablement.
4. Create `scripts/validation/validate-phase-09-workflow-handoff.mjs` to check
   migration ownership, dependency pins, default-off flags, prohibited payload
   fields, event/schema constants, test inventory, and stale V005/Python-app
   paths.
5. Produce bounded artifacts under
   `artifacts/verification/phase-09-workflow-handoff/` in CI only; do not commit
   runtime histories or tenant data.

## Verification Matrix

| Priority | Gate | Required evidence |
|---|---|---|
| Critical | Atomicity/RLS | Real PostgreSQL app + dispatcher role tests |
| Critical | One-owner crash recovery | Fault-injected dual-role dispatcher/inbox integration tests |
| Critical | History/data leak | Temporal test history negative assertions |
| High | Replay/backward compatibility | Existing reducer replay suite + golden vectors |
| High | Configuration safety | Default-off/misconfiguration context tests |
| Medium | Static contract | Phase 9 validator with zero findings |

## Risks and Rollback

- False-positive history scan: assert both approved-field presence and prohibited
  sentinel absence against decoded history.
- Tests pass only with mocks: critical atomic/RLS gates must use PostgreSQL;
  Temporal semantics must use the official test environment.
- No destructive rollback. Quarantined test rows live only in disposable test DBs.

## Success Criteria

- [x] Critical matrix rows fail before implementation and pass after it.
- [x] Tenant pool reuse and stale lease attempts cannot cross or mutate scope.
- [x] Existing client/start-contract history contains no prompt/evidence/secret
  sentinel, proven by `TemporalInvestigationWorkflowHistoryLeakTest` using the
  official `TestWorkflowEnvironment` in exact-main Maven verify. Phase 8 owns
  the separate worker restart/replay history proof.
- [x] Validator and existing reducer replay suites pass.
- [x] Async contract, worker-readiness admission, cutover inventory, bounded
  retry behavior, and exact AlreadyStarted verification pass. Ambiguous
  post-RPC exhaustion remains `PENDING`; only proven pre-RPC invalidity becomes
  `REJECTED`.
