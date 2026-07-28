## Code Review Summary

### Scope

- Files: 91 pending files before this report (31 tracked modifications, 60 new files)
- Delta: 1,064 tracked additions, 199 tracked deletions, 5,443 new-file lines; 6,706 review LOC
- Focus: Phase 9 Temporal workflow-start handoff, migration V010, dispatcher datasource/RLS, API contract, upgrade/cutover scripts, secret scanner, CI, tests, and plan claims
- Scout findings: deadline/idempotency ordering, Temporal SDK exception wrapping, database/app clock skew, disabled starter admission, RPC/lease envelope, contract 409, crash-after-start reconciliation, cross-tenant scheduling, and linked-worktree secret history

### Overall Assessment

No unresolved production-code blocker remains in the reviewed tree. The first
critical pass found five defects; all were changed in the shared worktree before
this report and the focused 33-test regression set passes.

Pre-landing result remains **HOLD for evidence**, not for another code change:
the full unit and fresh PostgreSQL results supplied to the reviewer predate the
final lease-fence, transport-wrapper, deadline-ordering, and configuration
changes. The final source state still needs the configured full gates.

### Critical Issues

None in the final reviewed source.

### High Priority

#### Verification blocker: final PostgreSQL state has not been rerun

- Evidence: the lease mutation now fences with database time at
  `services/platform-api/src/main/java/ai/opsmind/platform/messaging/TransactionalOutboxLeaseStore.java:116`
  and `:145`; its database-clock tests were changed at
  `services/platform-api/src/test/java/ai/opsmind/platform/investigation/workflow/InvestigationWorkflowDispatcherPersistenceIntegrationTest.java:171`
  and `:201`.
- Problem: the previously reported 13/13 PostgreSQL pass occurred before these
  final changes. Static inspection is not execution evidence for RLS, trigger
  order, transaction rollback, or DB-clock behavior.
- Required fix: rerun the exact Phase 9 CI selector against a fresh V010
  database and rerun the V009-to-V010 upgrade script. Do not land from the
  earlier transcript.

### Medium Priority

None.

### Low Priority

None.

### Edge Cases Found by Scout

- An exact persisted retry after its deadline originally returned 408 before
  idempotency lookup. Final ordering now loads the durable binding first and
  applies the deadline policy only to a new start
  (`DurableInvestigationExecutionStarter.java:25`).
- Temporal SDK 1.35 wraps non-duplicate gRPC start failures in
  `WorkflowServiceException`; raw `StatusRuntimeException` catches did not
  classify transport failures. Final code walks the cause chain and maps
  retryable statuses for both start and AlreadyStarted reconciliation
  (`TemporalInvestigationWorkflowClient.java:61`, `:103`, `:177`).
- Caller-clock lease comparisons could acknowledge an expired database lease.
  Final mutations use `transaction_timestamp()` and tests cover both skew-safe
  live acknowledgement and expired rollback.
- Temporal execution could accept work while the workflow starter was disabled.
  Final startup configuration requires an enabled/valid starter
  (`InvestigationExecutionConfiguration.java:42`).
- RPC timeout plus safety margin could exceed the lease. Final startup
  validation requires the RPC envelope to fit inside the lease
  (`InvestigationWorkflowStarterProperties.java:48`).
- The implementation returned 409 for run conflicts/cutover/rejected bindings
  while OpenAPI omitted it. The final contract declares 409
  (`packages/contracts/openapi/opsmind-v1.yaml:442`).
- V010 trigger/RLS review found no remaining cross-tenant mutation path:
  app insert is tenant/actor-bound, dispatcher reconciliation is role-bound,
  immutable target fields are trigger-protected, and the resolver exposes only
  event-scoped ready tenant enumeration.
- No unbounded database loop or N+1 query was introduced. Tenant enumeration and
  batch claim limits are bounded; Temporal RPC runs outside database
  transactions.
- Workflow history input is bounded to identifiers, budgets, target metadata,
  authorization revision, and digests. No prompt, evidence body, bearer token,
  provider request, or credential is serialized.
- Linked-worktree scanner storage now resolves the common repository root and
  tests working tree, index-only, artifact-only, and history-only findings.

### Positive Observations

- State, binding, and outbox creation remain one application transaction, while
  STARTED/REJECTED binding, inbox, and outbox reconciliation remain one
  dispatcher transaction.
- AlreadyStarted acceptance requires workflow/run identity, workflow type, task
  queue, memo digest, and exact first start input; memo equality alone is
  insufficient.
- Dispatcher credentials use a named secondary datasource and verify both
  `session_user` and `current_user` as `opsmind_dispatcher`.
- Retries are bounded by attempts, age, deadline, and exponential backoff.

### Recommended Actions

1. Rerun full Platform API unit tests on the final tree.
2. Rerun the fresh PostgreSQL Phase 9 13-test selector and V009-to-V010 upgrade.
3. Rerun secret scan, Phase 9 static validation, `git diff --check`, and
   actionlint on the final tree.
4. Keep Phase 9 and plan phases 2-5 in progress until exact-head evidence exists;
   do not mark the parent durable-workflow phase complete because no live
   cluster/compatible worker or restart/resume execution exists.

### Metrics

- Type coverage: not measured; Java compilation passed in focused Maven run
- Test coverage: not measured
- Focused final-tree regression: 33 run, 0 failures, 0 errors, 0 skipped
- Earlier broad unit result: 281 run with 39 intentional integration skips;
  predates final fixes
- Earlier fresh PostgreSQL result: 13/13; predates final fixes
- Secret scan: 0 findings in supplied run
- Static Phase 9 validator: PASS on final tree
- Shell syntax (`run-phase-04b-migration-upgrade.sh`): PASS
- `git diff --check`: 0 errors
- Linting issues: 0 observed; actionlint unavailable in reviewer shell

### Plan Follow-up

- Phase 1 evidence reconciliation: appears complete.
- Phases 2-5 remain marked in progress with unchecked exit criteria. Code and
  tests now cover their handoff scope, but exact-head CI/PostgreSQL evidence is
  still required.
- No plan file or task state was modified by this review.

### Unresolved Questions

None. Remaining work is explicit verification and later Phase 9 runtime scope,
not a product decision.
