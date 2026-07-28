## Code Review Summary

### Scope

- Files:
  - `services/platform-api/src/main/java/ai/opsmind/platform/investigation/workflow/TemporalInvestigationWorkflowClient.java`
  - `services/platform-api/src/main/java/ai/opsmind/platform/investigation/workflow/TemporalTransportFailureClassifier.java`
  - `services/platform-api/src/test/java/ai/opsmind/platform/investigation/workflow/TemporalInvestigationWorkflowClientTest.java`
- Baseline: `af45504`; reviewed commit `e1c29e6` plus current uncommitted fixes.
- Diff: 306 additions, 36 deletions across three code/test files.
- Focus: follow-up review of exception cause chains, gRPC classification, deterministic reconciliation, permanent/retryable mapping, compatibility, behavioral proof, compile risk.
- Constraints: read-only review; no Maven or Docker because of the D: free-space gate.
- Scout findings: classifier has one production caller; failures flow into bounded dispatcher attempt/age/deadline handling. Retry uses the deterministic workflow ID and `FAIL` conflict policy, then exact description/memo/first-history-input reconciliation.

### Overall Assessment

**BLOCK.** The pending fixes correct the two earlier overbroad behaviors: a status-less local `IllegalStateException` is permanent, and permanent failures during existing-execution reconciliation now use `workflow.existing-contract-unverifiable`. However, the status-less cause-chain rule still classifies a real local Temporal serialization failure as transport-retryable because Jackson serialization exceptions are `IOException` descendants.

No auth/authz, data exposure, N+1, shared-state mutation, public-interface, or schema compatibility defect was found in this three-file scope.

### Critical Issues

None.

### High Priority

#### 1. Local serialization failures are still misclassified as ambiguous transport failures

- Location: `TemporalTransportFailureClassifier.java:67-89`, especially `:81`; missing negative proof at `TemporalInvestigationWorkflowClientTest.java:412-430`.
- Trigger:
  1. Temporal serializes the workflow input or memo before sending the start RPC.
  2. Jackson rejects the value and throws `JsonProcessingException`.
  3. Temporal 1.35.0 wraps that in `DataConverterException`, then `WorkflowStubImpl.startWithOptions` wraps the local exception in `WorkflowServiceException`.
  4. `isStatuslessTransportFailure` walks the whole cause chain, finds `JsonProcessingException -> JacksonException -> IOException`, and returns `true`.
- Evidence: static inspection of the pinned `temporal-sdk-1.35.0.jar` shows `JacksonJsonPayloadConverter.toData` catches `JsonProcessingException` and constructs `DataConverterException`; `WorkflowStubImpl.startWithOptions` catches generic `Exception` and constructs `WorkflowServiceException`. The pinned Jackson hierarchy makes `JacksonException` extend `IOException`.
- Impact: a permanent, pre-RPC serialization/codec defect is released for retry as `workflow.temporal-unavailable`. The dispatcher repeatedly retries until attempt/age/deadline exhaustion, delays terminal visibility, and stores a misleading exhaustion code. This violates Phase 6 requirement 5: explicit local/permanent failures remain terminal.
- Why current test misses it: `statuslessLocalServiceFailure` uses only `IllegalStateException` (`TemporalInvestigationWorkflowClientTest.java:422-429`). It does not model a local SDK exception with a nested `IOException`, which is the exact false-positive path introduced by the classifier.
- Fix: do not treat any `IOException` found anywhere under `WorkflowServiceException` as transport evidence. At minimum, stop classification when a `DataConverterException`/codec exception is encountered. Safer: allow only a direct status-less transport/timeout cause (or explicitly documented neutral wrappers) and keep arbitrary nested I/O permanent. Add a regression test for `WorkflowServiceException -> DataConverterException -> JsonProcessingException` and assert `workflow.temporal-rejected`, `retryable=false`.

### Medium Priority

None.

### Low Priority

#### 1. The checked gRPC `StatusException` branch has no direct test

- Location: `TemporalTransportFailureClassifier.java:45-47`; status fixtures at `TemporalInvestigationWorkflowClientTest.java:396-409`.
- The implementation supports both `StatusRuntimeException` and `StatusException`, but every matrix fixture builds only `StatusRuntimeException`.
- Impact: low; API signatures compile against the pinned gRPC jar and cause traversal is shared. Still, the added branch can regress without a behavioral failure.
- Fix: add one nested `StatusException` retryable case and one permanent case.

### Edge Cases Found by Scout

- Ambiguous start followed by `AlreadyStarted`: exact workflow ID, workflow type, task queue, memo digest, execution/run ID, and first start input are checked before acknowledgement (`TemporalInvestigationWorkflowClient.java:75-138`). The test at `TemporalInvestigationWorkflowClientTest.java:172-219` proves the client-level convergence.
- Reconciliation describe/history transport failures: the shared classifier is exercised for all seven retryable gRPC statuses plus the status-less timeout wrapper (`TemporalInvestigationWorkflowClientTest.java:266-290`).
- Permanent reconciliation failures: pending code now preserves `workflow.existing-contract-unverifiable` through all three catch paths (`TemporalInvestigationWorkflowClient.java:108-121`); representative describe/history tests cover `NOT_FOUND` and `PERMISSION_DENIED`.
- Cause cycles/self-cycles: traversal is depth-bounded to 16 and breaks on a self-cause. No unbounded walk.
- Concurrency/order: client holds no mutable shared state. Duplicate safety depends on Temporal workflow-ID conflict semantics and exact reconciliation, while dispatcher retry remains bounded at `InvestigationWorkflowStartDispatcher.java:94-126`.
- Error exposure: classifier preserves the cause internally but exposes only stable codes to dispatcher persistence; no new log/response leak found.

### Positive Observations

- The uncommitted follow-up fixes the earlier permanent-code mismatch during reconciliation.
- API compatibility is preserved: no exported interface, constructor, schema, or dependency version changed.
- `javap` verification against pinned Temporal 1.35.0 confirms the test fixture APIs and production calls exist: `WorkflowExecutionDescription(response, converter)`, `WorkflowClient.streamHistory(workflowId, runId)`, `DataConverter.toPayload(s)`, and both gRPC status exception types.

### Recommended Actions

1. Fix the nested-local-`IOException` false positive and add the converter-failure regression test.
2. Add direct `StatusException` coverage.
3. Re-run focused `TemporalInvestigationWorkflowClientTest` and `TemporalInvestigationWorkflowHistoryLeakTest`, then Platform compile/test when the storage gate permits.
4. Keep Phase 6 Workstream C and its acceptance criterion in progress until item 1 and fresh compilation/tests pass.

### Plan Follow-up

- Phase 6 requirement 5: **partial**. gRPC ambiguity matrix and deterministic retry/reconciliation are implemented; explicit local/permanent classification is not complete because converter exceptions can enter the retry path.
- Other Phase 6 requirements belong to sibling workstreams and were not assessed here.
- Do not mark the workstream or plan complete from static validation alone.

### Metrics

- Type coverage: not measured.
- Test coverage: not measured; Maven intentionally not run.
- Compile risk: SDK/gRPC method and constructor signatures statically verified with `javap`; full Java compilation not run.
- Linting issues: `git diff --check af45504` found 0 whitespace errors. Full lint not run.
- Static Phase 9 validator: PASS, `Errors=0`.

### Adversarial Review

- Accepted: 1 High (local serialization failure misclassified as transport-retryable).
- Rejected: broad concern that retrying ambiguous starts creates duplicate workflows. Rejected because deterministic workflow ID plus `REJECT_DUPLICATE`/`FAIL` and exact `AlreadyStarted` reconciliation prevent a second accepted execution in this contract.
- Deferred: 1 Low (`StatusException` direct test).

### Unresolved Questions

- Fresh Maven compile and focused tests remain pending until the D: free-space gate permits them.

## Fix verification

### Result

**Static follow-up PASS; prior review block resolved in the inspected fix diff.**
No remaining Critical, High, Medium, or Low finding was found in the requested
classifier/test scope. This does not replace fresh Maven compilation or test
execution.

### High finding resolution — local converter failure

- Resolved at `TemporalTransportFailureClassifier.java:82-86`.
- The status-less scan now encounters `DataConverterException` and returns
  permanent before inspecting its nested `IOException`/`TimeoutException`.
- `statuslessLocalConverterFailureOnStartRemainsPermanent`
  (`TemporalInvestigationWorkflowClientTest.java:243-262`) asserts
  `retryable=false` and `workflow.temporal-rejected`.
- Its fixture (`TemporalInvestigationWorkflowClientTest.java:494-503`) uses
  `WorkflowServiceException -> DataConverterException ->
  SocketTimeoutException`. This is sufficient to prove the ordering that failed
  the prior review: even an I/O-shaped nested cause cannot override the local
  converter boundary.
- Pinned Temporal 1.35.0 inspection confirms
  `DataConverterException(Throwable)` exists. No exported contract or dependency
  change was introduced.

### Low finding resolution — checked gRPC status

- Resolved.
- Retryable checked status: `checked-UNAVAILABLE` is included in
  `ambiguousTransportFailureCases`
  (`TemporalInvestigationWorkflowClientTest.java:555-575`). That source is
  exercised on initial start and both existing-execution describe/history
  reconciliation paths.
- Permanent checked status:
  `checkedNonRetryableTransportFailureOnStartRemainsPermanent`
  (`TemporalInvestigationWorkflowClientTest.java:288-310`) verifies
  `PERMISSION_DENIED` remains non-retryable.
- Fixture construction at
  `TemporalInvestigationWorkflowClientTest.java:458-472` uses the actual checked
  `Status.asException()` API. `javap` against pinned gRPC 1.80.0 confirms that
  API and the `StatusException` return type.
- One retryable and one permanent checked-status case are sufficient because
  checked and runtime status exceptions feed the same `Status.Code` allowlist;
  the existing runtime matrix covers every enum classification.

### Regression and adversarial checks

- Cause-chain order: local converter marker precedes the I/O/timeout allowlist;
  nested converter I/O cannot be retried.
- Status-bearing failures still take precedence over status-less inference and
  remain governed by the explicit seven-code retry allowlist.
- Deterministic workflow reconciliation and dispatcher retry budgets are
  unchanged.
- No new public API, schema, auth/authz, data exposure, concurrency, or
  compatibility risk found.
- Adversarial fix-diff result: 0 accepted, 0 deferred findings.

### Fresh verification evidence

- `git diff --check af45504`: exit 0; zero whitespace errors.
- `node scripts/validation/validate-phase-09-workflow-handoff.mjs`: PASS,
  `Errors=0`.
- Source line-length scan above 100 characters: zero findings.
- `javap`: pinned Temporal `DataConverterException(Throwable)` and gRPC
  `Status.asException()` signatures confirmed.
- Maven/Docker: not run, per D: free-space constraint.

### Updated plan recommendation

- Phase 6 requirement 5 is statically complete in this workstream.
- Workstream C can proceed to integration, but plan/phase completion still
  requires the deferred focused Maven tests, full compile/test, and merged-head
  gates when storage permits.

### Fix-verification unresolved questions

- Fresh Maven compile and focused runtime tests remain pending behind the
  existing D: free-space gate.
