# Phase 9 Compose CI Audit

## Scope

- `.github/workflows/pr-quality.yml`
- `compose.yaml`
- `deploy/prometheus/*`, `deploy/alertmanager/*`, `deploy/ai-runtime/ci-disabled-egress-policy.json`
- Temporal observer/reconciler configuration only

## Confirmed blocker

### High — enabled reconciler cannot reach Temporal in the Compose job

- The `compose` job enables the reconciler and observer, then supplies `127.0.0.1:7233` as the observer target ([pr-quality.yml:927-935](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/.github/workflows/pr-quality.yml)).
- Its smoke command starts only the `application` and `phase-09-conformance` profiles ([pr-quality.yml:1050-1055](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/.github/workflows/pr-quality.yml)). `compose.yaml` has no Temporal service; the only worker is a separate, unstarted `phase-09-temporal-worker` service ([compose.yaml:302-333](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/compose.yaml)). The pinned Temporal `docker run` belongs to the separate PostgreSQL job, so it cannot be reached by this job.
- `platform-api` receives that value directly ([compose.yaml:258-263](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/compose.yaml)); inside that container, `127.0.0.1` is the platform container, not the GitHub runner or the other job. The observer uses that exact target ([InvestigationTemporalObserverConfiguration.java:41-50](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/services/platform-api/src/main/java/ai/opsmind/platform/investigation/workflow/InvestigationTemporalObserverConfiguration.java)).
- This can remain hidden: client stubs are constructed without a connection probe and mark observer readiness true ([InvestigationTemporalObserverConfiguration.java:68-77](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/services/platform-api/src/main/java/ai/opsmind/platform/investigation/workflow/InvestigationTemporalObserverConfiguration.java)); the scheduled reconciler does not issue an RPC unless it claims an outbox candidate ([InvestigationWorkflowReconcilerRunner.java:28-45](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/services/platform-api/src/main/java/ai/opsmind/platform/investigation/workflow/InvestigationWorkflowReconciler.java:50-57)). A real candidate invokes `DescribeWorkflowExecution` against the unreachable target and is classified/retried ([TemporalInvestigationWorkflowObserver.java:49-64](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/services/platform-api/src/main/java/ai/opsmind/platform/investigation/workflow/TemporalInvestigationWorkflowObserver.java)).

**Fix before treating this Compose lane as enabled-reconciliation evidence:** either keep observer/reconciler disabled in the Compose smoke and explicitly scope it to scrape/alert routing, or supply a same-job, reachable Temporal endpoint plus a conformance candidate. The present cleartext allowlist only accepts `127.0.0.1`/`localhost` ([InvestigationTemporalObserverProperties.java:32-35](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/services/platform-api/src/main/java/ai/opsmind/platform/investigation/workflow/InvestigationTemporalObserverProperties.java)); therefore a normal `temporal:7233` Compose service would require an intentionally narrow, reviewed policy change rather than silently weakening validation.

## Confirmed operational gap (not a current CI-start blocker)

### Medium — default `application` profile points Prometheus at a missing Alertmanager

- Prometheus always loads an alert receiver at `alertmanager:9093` ([prometheus.yml:9-18](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/deploy/prometheus/prometheus.yml)).
- Prometheus belongs to `application`, whereas Alertmanager only belongs to `phase-09-conformance` ([compose.yaml:100-102](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/compose.yaml), [compose.yaml:144-168](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/compose.yaml)). Thus `docker compose --profile application up` runs a Prometheus instance whose Alertmanager DNS target has no service.

This does not stop the current CI smoke because it activates `phase-09-conformance`, but an alert from the normal application profile cannot be delivered. Fix by making Alertmanager wiring explicitly environment/profile-configurable, or include a safe, configured receiver whenever the alert rules are enabled. Do not present the default profile as pager-capable until that is resolved.

## Verified non-blockers

- The CI egress policy is tracked and structurally valid, and `AI_PROVIDER` remains disabled in the Compose service ([pr-quality.yml:925](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/.github/workflows/pr-quality.yml), [compose.yaml:342-350](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/compose.yaml), [ci-disabled-egress-policy.json](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/deploy/ai-runtime/ci-disabled-egress-policy.json)). It does not authorize DeepSeek egress.
- Alertmanager has no published host port; the local receipt server is reached only through the internal bridge's `host-gateway` mapping ([compose.yaml:144-168](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/compose.yaml), [pr-quality.yml:1031-1036](D:/worktrees/OpsMind_AI-feature-phase-09-runtime-conformance/.github/workflows/pr-quality.yml)). This is a bounded CI-local route, not an exposed webhook listener.
- The direct synthetic POST proves `platform-api -> Alertmanager -> host receipt server` routing, but it is not proof that Prometheus evaluated a live rule and dispatched it. The static `promtool` rule test plus direct receipt test are complementary, not an end-to-end alert evaluation proof.

## Unresolved questions

- None for the topology findings. The decision is whether this Compose lane claims enabled reconciliation or only config/scrape/receipt routing.

Status: DONE_WITH_CONCERNS
Summary: Found one confirmed unreachable Temporal target in the enabled Compose reconciler lane and one default-profile Alertmanager mismatch; no AI-egress or host-port exposure blocker.
Concerns/Blockers: Do not merge the Compose lane as reconciliation-runtime proof until the Temporal topology or the lane scope is corrected.
