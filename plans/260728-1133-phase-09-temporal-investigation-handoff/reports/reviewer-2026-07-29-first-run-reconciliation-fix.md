# Temporal first-run reconciliation review — 2026-07-29

## Finding

The `WorkflowExecutionAlreadyStarted` recovery path previously treated
`exception.getExecution().runId` as the immutable workflow identity. Temporal
can return the current run after Continue-As-New, while
`WorkflowExecutionDescription` separately exposes `firstRunId`. The old code
therefore described and streamed history for a later run and could permanently
reject a valid response-lost retry whose original start input was correct.

## Fix

- Validate the described current execution still matches the exception
  identity and workflow contract.
- Resolve `WorkflowExecutionDescription.getFirstRunId()` and fail closed when it
  is absent.
- Stream and decode the first-start history using that first run ID.
- Return the first run ID in `StartResult`, matching the durable V013 handoff
  contract.
- Add a regression test with `temporal-run-continued` as current run and
  `temporal-run-1` as first run; assert history lookup and result use the first
  run.

## Evidence

- Focused suite:
  `mvn -q -f services/platform-api/pom.xml -Dtest=TemporalInvestigationWorkflowClientTest test`
  — PASS.
- Static Phase 9 validator and diff check must pass again on the amended head.
- The original reviewer’s other concerns remain: exact-head PostgreSQL CI,
  independent review metadata, and broader Phase 9/A–Z gates are still open.

Status: DONE_WITH_CONCERNS
Summary: High-severity run-identity defect fixed with a current-vs-first regression test.
Concerns/Blockers: Exact-head CI and final independent review remain required.
