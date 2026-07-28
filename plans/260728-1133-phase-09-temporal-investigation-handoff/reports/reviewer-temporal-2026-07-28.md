## Code Review Summary

### Scope

- Compared `af45504` to `e1c29e6` (`fix(workflow): retry ambiguous temporal starts`).
- Reviewed: `TemporalTransportFailureClassifier.java`, `TemporalInvestigationWorkflowClient.java`, and `TemporalInvestigationWorkflowClientTest.java` (+260/-32 LOC). The commit's worker report was out of scope.
- Scout traced the client into `InvestigationWorkflowStartDispatcher` and inspected the pinned Temporal SDK 1.35.0 exception path.
- Validation: `git diff --check af45504 e1c29e6` passed. Maven/test/build not run: D: had 17.86 GiB free and the review constraint prohibits storage-heavy tests.

### Overall Assessment

`UNKNOWN`, `INTERNAL`, and `CANCELLED` are now retryable. Explicit gRPC auth/contract codes remain outside the retry allowlist, direct target mismatch remains permanent, and the unchanged `AlreadyStarted` path still verifies workflow ID/type, task queue, digest memo, run ID, and the first start input before acknowledging. Two medium defects remain in statusless classification and reconciliation error semantics.

### Critical Issues

None.

### High Priority

None.

### Medium Priority

1. **[Medium] A statusless `TemporalException` is not necessarily an ambiguous transport result.** `TemporalTransportFailureClassifier.java:65-87` makes every statusless causal chain containing `TemporalException` retryable. The pinned Temporal SDK's `WorkflowStubImpl.startWithOptions` catches arbitrary `Exception` from `WorkflowClientCallsInterceptor.start` and wraps it in `WorkflowServiceException`, which is a `TemporalException`; this includes deterministic local serialization/interceptor failures before a remote start is known to be ambiguous. The new test explicitly treats a `WorkflowServiceException` caused by `IllegalStateException` as ambiguous at `TemporalInvestigationWorkflowClientTest.java:392-400` and includes it in the retryable parameter source at `:451-464`. The dispatcher then releases the lease for retry at `InvestigationWorkflowStartDispatcher.java:98-105`, eventually reporting retry/deadline exhaustion rather than the actual permanent fault. Restrict statusless retries to documented transport-side causes (or tag only adapter-created ambiguous wrappers); add a regression test proving a statusless wrapper around a local conversion/configuration failure is terminal.

2. **[Medium] The new reconciliation catch loses the required "unverifiable existing execution" terminal code for explicit non-retryable failures.** During `AlreadyStarted` reconciliation, `TemporalInvestigationWorkflowClient.java:113-117` now catches `TemporalException` and passes `workflow.temporal-rejected` to the mapper. `WorkflowNotFoundException` and `WorkflowServiceException` are `TemporalException`s, so a `NOT_FOUND`, `PERMISSION_DENIED`, or other non-retryable error from `describe()`/history becomes `workflow.temporal-rejected`. Before this change, the `RuntimeException` branch at the same point mapped it to `workflow.existing-contract-unverifiable`, preserving the fact that exact reconciliation could not be proven. The binding is terminal either way, but the persisted safe code no longer distinguishes an external start rejection from a failed exact-proof check required by the phase. Pass `workflow.existing-contract-unverifiable` as the permanent code from this reconciliation catch and add non-retryable describe/history cases.

### Low Priority

None.

### Edge Cases Found by Scout

- Ambiguous gRPC status outcomes are bounded by dispatcher retry/deadline/attempt controls and retain deterministic workflow-ID reconciliation.
- Explicit `PERMISSION_DENIED`, `UNAUTHENTICATED`, invalid-argument, and target-mismatch paths remain terminal when their status/direct validation is available.
- A statusless SDK wrapper can also represent a local exception; it must not be assumed to prove an ambiguous external effect.

### Recommended Actions

1. Narrow the statusless retry predicate and add local-failure versus known-ambiguous-wrapper coverage.
2. Restore the reconciliation-specific permanent code for non-retryable `TemporalException`s from describe/history.
3. After the fixes, run the focused `TemporalInvestigationWorkflowClientTest` and the phase-9 validation/dispatcher suite when storage capacity permits; do not mark Phase 3/4 complete before that evidence exists.

### Metrics

- Type coverage: not measured.
- Test coverage: not measured; tests intentionally not run under the disk constraint.
- Linting issues: not measured.
- Diff whitespace errors: 0 (`git diff --check`).

### Unresolved Questions

- None.

Status: DONE_WITH_CONCERNS
Summary: Two medium Temporal failure-classification defects found; no critical or high findings.
Concerns/Blockers: Fix both medium findings and obtain focused test evidence before landing.
