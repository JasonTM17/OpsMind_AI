## Code Review Summary

### Scope

- Files: 10 worker/runtime production files and 5 corresponding tests under `services/platform-api/.../investigation/workflow/`; compared with Phase 8 plan and `pr-quality.yml`.
- LOC: 1,044 lines in reviewed files; four tracked files have 82 uncommitted added lines, with the rest new and untracked.
- Focus: pending Temporal worker lane plus its integration/CI contract.
- Scout findings: Platform admission, worker process startup, and restart recovery are distinct paths. `DescribeTaskQueue` identifies a queue poller but contains no workflow-type assertion; the only CI Java invocation is generic Maven verification and the real-server test is environment-gated.
- Validation: `git diff --check HEAD` passed. Maven, Docker, local Temporal, lint, and coverage commands were deliberately not run because C: is below the stated safety headroom.

### Overall Assessment

Do not merge this as Phase 8 conformance. The worker source mostly has the intended narrow shape, but the production admission invariant can still accept a workflow type the worker cannot execute. The separately owned integration work also has not made the worker runnable from the current artifact or made the mandatory restart proof run in CI. A passing generic Java job would therefore be false evidence for B-017.

### Critical Issues

None found in the reviewed source. This is not a clearance: the high-priority failures below block the Phase 8 acceptance gate.

### High Priority

1. **[H1] Admission can start a workflow type the compatible worker does not register.**

   `InvestigationWorkflowProperties.validateStartTarget()` accepts any syntactically valid workflow type at `InvestigationWorkflowProperties.java:24-35`. `TemporalInvestigationWorkflowClient` then accepts a request matching that configured type at `TemporalInvestigationWorkflowClient.java:77-82`, while readiness only confirms an identity/build poller on the queue at `TemporalInvestigationWorkerReadinessProbe.java:36-46`. The separate worker process rejects every type except `opsmind-investigation-v1` at `InvestigationTemporalWorkerProperties.java:36-41` and registers only `ParkedInvestigationWorkflow` at `InvestigationTemporalWorkerRuntime.java:84`.

   Trigger: configure the platform client with `opsmind-investigation-v2` and the same namespace/queue as a healthy v1 worker. Admission sees the fresh poller and StartWorkflow succeeds; the worker subsequently cannot service v2. The handoff can be settled as started even though no compatible executor exists.

   Fix: enforce `InvestigationWorkflow.TYPE` at the shared client/admission boundary, not only in worker startup. Add tests proving a non-v1 property is rejected before both `DescribeTaskQueue` and StartWorkflow.

2. **[H2] The checked-in artifact has no selectable worker-only launch path.**

   `InvestigationTemporalWorkerApplication.java:12-22` creates the isolated non-web application, but `services/platform-api/pom.xml:131-138` builds one ordinary Boot executable and `services/platform-api/Dockerfile:17-18` always runs `java -jar /app/app.jar`. The selected boot application remains `PlatformApiApplication.java:7-12`, which scans/starts the Platform API. No launcher, image command, profile, or executable selection invokes `InvestigationTemporalWorkerApplication`.

   This leaves the worker entry point as unreachable source rather than a separate process that can be deployed with Temporal connectivity only. The Phase 8 plan assigns the final launch-contract change to the controller lane; it is still required before the lane can claim acceptance criterion 2.

   Fix: make the worker entrypoint explicitly selectable from the packaged artifact/image, and add a process-level test proving that selection has no web, datasource, Flyway, application, dispatcher, reconciler, AI Runtime, Tool Gateway, or connector beans.

3. **[H3] PR-quality does not execute the mandatory real-Temporal restart/replay proof.**

   `InvestigationTemporalWorkerRestartTest.java:27-30` is skipped unless `OPSMIND_PHASE9_TEMPORAL_INTEGRATION=true`. The provided workflow's generic Java job only executes `mvn ... verify` at `.github/workflows/pr-quality.yml:463-488`; it neither supplies that variable nor starts a pinned Temporal development server. Search of the CI workflow, Compose file, scripts, and resources finds no invocation of this test or provisioning of such a server.

   Consequently acceptance criterion 4 is not exercised, its zero-skip requirement is not checked, and no Phase 9 artifact records the server identity, test class, test count, or history scan. The in-process test environment in the configuration test cannot substitute: the plan explicitly excludes it for `DescribeTaskQueue`/sticky-reset conformance.

   Fix: add a bounded CI job that starts a pinned local Temporal development server, sets the gate variable and target, runs only the restart class with `surefire.failIfNoSpecifiedTests=true`, fails on every skip/failure, scans the produced history, and uploads the required revision-bound evidence.

### Medium Priority

1. **[M1] The enabled-context test bypasses the production factory and therefore does not test configured worker boot.**

   `InvestigationTemporalWorkerConfigurationTest.java:42-48` constructs `InvestigationTemporalWorkerRuntime` directly with `TestWorkflowEnvironment` stubs, then injects it with `withBean`. That makes `@ConditionalOnMissingBean` at `InvestigationTemporalWorkerConfiguration.java:22-31` skip the factory under test. The test never binds valid `temporal-client`/`workflow` properties into a real runtime, never calls `createServiceStubs`, and never launches `InvestigationTemporalWorkerApplication`.

   A broken binding name, launch profile, or constructor wiring can pass this test while production fails at boot. Exercise the actual factory/application against the same real local Temporal development server used for H3; retain a separate unit test for the no-unwanted-beans assertion.

2. **[M2] Forced shutdown is not awaited before the runtime reports stopped and closes its transport.**

   After the graceful wait expires, `InvestigationTemporalWorkerRuntime.stop()` calls `workerFactory.shutdownNow()` at lines 102-104 but does not wait for termination again. It immediately sets `running=false`; `close()` can then close `WorkflowServiceStubs` at lines 118-123 while worker threads may still be unwinding. The pinned SDK exposes shutdown and termination as separate operations.

   This can leave pollers/workflow threads racing the replacement worker during the restart test and defeats a bounded shutdown claim under an unresponsive workflow task. Use one overall shutdown deadline, await termination after the forced signal, and keep the transport open until that deadline is exhausted; expose a failure if termination cannot be confirmed.

3. **[M3] Explicit zero concurrency is silently converted to active defaults.**

   `InvestigationTemporalWorkerProperties.java:23-26` maps both `0` executor and `0` poller values to `32` and `5`, respectively. Thus an enabled deployment explicitly configured with zero (an invalid bound) starts a worker with default parallelism instead of failing closed. The validation at lines 42-46 cannot detect the original zero, and `InvestigationTemporalWorkerPropertiesTest` has no zero-input rejection case.

   Preserve the distinction between absent and supplied values (for example, nullable boxed inputs/default binding) and reject explicit zero when enabled. Add both executor-zero and poller-zero cases.

4. **[M4] The history-canary test does not prove its scanner detects leakage in every required field.**

   `TemporalWorkflowHistoryCanaryAssertions` recursively scans populated protobuf fields, but `InvestigationTemporalWorkerRestartTest.java:92-99` supplies none of the listed `restart-*` canaries to any payload, memo, header, search attribute, failure detail, or cancellation reason. The test can pass if the helper stops scanning one of those locations because the generated history contains no matching value. It also emits no artifact to scan.

   Add positive helper tests with synthetic history events carrying each prohibited canary in the required locations, plus an integration assertion over the complete fetched history. Persist only a sanitized pass/fail/digest artifact, never the canary-bearing event data.

### Low Priority

None.

### Edge Cases Found by Scout

- A fresh poller is queue-scoped, not workflow-type-scoped; this produced H1.
- Worker startup, process selection, and remote readiness are independent state machines. The current tests exercise them separately or through injected dependencies, not as one deployable process.
- Restart requires forceful termination because the intended workflow waits indefinitely for cancellation; the runtime needs a final termination fence after `shutdownNow`.
- RPC failure, missing timestamps, malformed timestamps, stale timestamps, and future timestamps fail closed in source. `TemporalInvestigationWorkflowAdmission` maps propagated readiness failures to 503, so no error-swallowing defect was found there.
- The worker source introduces no activity registration, signal/update/query handlers, provider credentials, datasource, or direct product cancellation API. That narrows the worker's local trust boundary, but does not replace launch and CI proof.

### Positive Observations

- `DescribeTaskQueue` explicitly requests `TASK_QUEUE_TYPE_WORKFLOW` and `reportPollers`; it does not accidentally use an activity or unspecified queue.
- Freshness checks reject absent/invalid timestamps and use bounded age plus future skew with an injected clock.
- The pinned 1.35.0 SDK exposes the Temporal APIs used by the code (`WorkflowInfo.getTaskQueue()` and cancellation-scope promise), so no static SDK-signature mismatch was found.

### Recommended Actions

1. Block Phase 8 merge until H1 is fixed: enforce canonical v1 workflow type at platform admission/client and prove rejection before remote calls.
2. Controller lane: provide an isolated, selectable worker artifact/image command and verify its actual bean graph.
3. Controller lane: add a pinned real-Temporal CI conformance job with zero-skip enforcement and Phase 9 evidence upload.
4. Fix shutdown fencing, explicit-zero configuration handling, and canary-scanner positive coverage before calling restart/recovery proven.
5. Keep Phase 8/B-017/G4 in progress. Criteria 1, 2, 4, and 5 are partial or unmet; criterion 3 has useful source/tests but no end-to-end operational proof.

### Metrics

- Type Coverage: not measured (no Maven/typecheck run under storage constraint).
- Test Coverage: not measured; required restart test is skipped by current CI configuration.
- Linting Issues: not measured.
- Static diff hygiene: 0 `git diff --check` findings.

### Unresolved Questions

- What exact launcher/image contract will the controller use to select the worker main class without allowing the web application as a fallback?
- Which pinned Temporal development-server image/binary and version will CI use, and how will its digest and test result be preserved as Phase 9 evidence?
