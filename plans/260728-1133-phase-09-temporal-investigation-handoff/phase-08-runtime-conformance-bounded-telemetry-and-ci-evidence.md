---
phase: 8
title: Runtime conformance, bounded telemetry, and CI evidence
status: in-progress
priority: P1
dependsOn:
  - phase-06-post-audit-authorization-and-dispatch-hardening
  - phase-07-read-only-exact-workflow-reconciliation
---

# Phase 8: Runtime Conformance, Bounded Telemetry, and CI Evidence

## Goal

Close the remaining repository-owned portion of B-017 without claiming a
functional investigation executor or production provider proof. Deliver a
default-off workflow-only Temporal worker, prove restart/replay against a pinned
local Temporal development server, reject stale compatible-poller records, scrape the real
reconciliation metrics through pinned Prometheus, and prove secretless local
Alertmanager routing. Phase 10 remains blocked.

## Scope decisions

- Reuse the existing Platform API artifact as a separate worker-only process;
  do not create a Python worker or a new microservice/image boundary.
- The worker bootstrap configuration lives outside the Platform API component
  scan. Enabling the worker can never create a poller inside the API process,
  and the Compose API service never receives worker activation variables.
- Register exactly `opsmind-investigation-v1` and accept the existing bounded
  `InvestigationWorkflowStartRequest`. The implementation validates immutable
  workflow metadata, schedules no activities, and parks durably. It must remain
  disabled outside conformance because it cannot complete an investigation.
- Readiness requires exact identity/build ID and a fresh `PollerInfo.lastAccessTime`
  checked with an injected clock. A configured bounded future-skew tolerance
  accounts for server/client clock difference; missing, malformed, stale, or
  implausibly future timestamps fail closed. Describe always targets the
  workflow-task queue, never an activity or unspecified queue type.
- Keep `deploy/prometheus/**` canonical. Add internal Alertmanager wiring and a
  deterministic local receipt fixture; never describe that fixture as external
  page delivery.
- Use `prom/alertmanager:v0.28.1@sha256:27c475db5fb156cab31d5c18a4251ac7ed567746a2483ff264516437a39b15ba`.
  Reuse the existing exact Prometheus tag and digest everywhere.

## Parallel lanes and exclusive ownership

### Lane A — Temporal worker and freshness proof

**Branch:** `feature/phase-09-temporal-worker-proof`

Owns only:

- new worker interface, implementation, configuration, and properties under
  `services/platform-api/src/main/java/ai/opsmind/platform/investigation/workflow/`;
- a worker-only application entrypoint plus a bootstrap configuration outside
  `ai.opsmind.platform`, which explicitly boots only the Temporal worker
  configuration with `WebApplicationType.NONE`;
- `InvestigationTemporalClientProperties.java`;
- `InvestigationTemporalClientConfiguration.java`;
- `TemporalInvestigationWorkerReadinessProbe.java`;
- directly corresponding worker/readiness tests under the same package.

Acceptance:

1. Worker absent by default and validates exact type/queue/identity/build plus
   bounded concurrency and shutdown settings when enabled.
2. Worker-only context contains no web server, DataSource, Flyway, application
   service, dispatcher, reconciler, AI Runtime, Tool Gateway, or connector bean;
   its process needs Temporal connectivity only.
   Enabling the worker must not add an `InvestigationTemporalWorkerRuntime` to
   the full Platform API component scan.
3. Fresh exact poller opens admission; wrong, missing, stale, malformed,
   implausibly future,
   or RPC-failed poller closes it.
4. A pinned local Temporal development server proves start, first task, worker
   stop, replacement-worker replay, and terminal test cancellation without
   duplicate start or activity events. The in-process test server is retained
   only for deterministic unit/history tests because it does not implement the
   `DescribeTaskQueue` or `ResetStickyTaskQueue` RPCs needed for this proof.
5. Every history event is scanned across payloads, memo, headers, search
   attributes, workflow failure details, and cancellation reasons. History and
   artifacts contain none of the prohibited prompt, evidence, token,
   capability, provider, incident-title, or summary canaries.

### Lane B — Prometheus and Alertmanager source proof

**Branch:** `feature/phase-09-observability-conformance`

Owns only:

- `deploy/prometheus/**`;
- new `deploy/alertmanager/**`;
- `scripts/validation/validate-phase-09-reconciliation-observability.mjs`;
- new Phase 9 live-scrape and local-receipt validators/fixtures.

Acceptance:

1. Prometheus routes alerts to internal `alertmanager:9093`; Alertmanager uses a
   secret file or CI-local URL, never an inline external receiver credential.
2. Pinned `promtool check config`, `promtool check rules`, and deterministic
   rule tests pass.
3. Live validator requires a healthy `platform-api:8082` target, nonempty real
   `opsmind_workflow_reconciliation_*` series, exact allowed labels, forbidden
   tenant/run/workflow/error labels absent, and bounded response/series counts.
4. Local fixture accepts exactly one bounded webhook and records only sanitized
   status/digest evidence marked `CI_LOCAL_ROUTING_CONFORMANCE`.

### Controller — shared integration and evidence

**Branch:** `feature/phase-09-runtime-conformance`

Controller alone owns:

- `.github/workflows/pr-quality.yml`;
- `compose.yaml`;
- `services/platform-api/src/main/resources/application.yaml`;
- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/workflow/`
  metrics wiring needed to expose default-off reconciliation gauges without
  enabling an observer or reconciler in the general application smoke lane;
- `.env.example`;
- `scripts/dev/opsmind.ps1` and `scripts/dev/opsmind.sh`;
- `services/platform-api/pom.xml` and `services/platform-api/Dockerfile` only if
  the existing executable-jar launch contract cannot select the isolated worker
  entrypoint without changing them;
- plan/docs/status reconciliation after the merged behavior is proven.

The controller merges Lane A then Lane B, resolves only integration joins, runs
focused validators/tests, pushes the integration branch, waits for exact-head
required checks, requests an independent production-readiness review, fixes all
blocking findings, and merges only after green evidence.
Merge conflict resolution must not modify lane-owned implementation files; any
such change requires an explicitly reassigned follow-up on the owning branch.

## CI evidence contract

Artifacts under `artifacts/verification/phase-09-workflow-handoff/` must record:

- exact commit SHA and UTC timestamps;
- exact Prometheus and Alertmanager image references/digests;
- configuration/rule digests and executed commands;
- pinned Temporal development-server image digest, startup command, retained server log,
  restart test class, and zero-failure/zero-skip counts;
- target health, series count, exact observed label names, and response bounds;
- one sanitized local routing receipt digest/status.

Each CI-live lane writes an `opsmind-phase9-evidence-v1` manifest with those
revision-bound fields. The Compose worker conformance service shares the worker
network namespace solely with a pinned local Temporal development sidecar, so
its strict `127.0.0.1` cleartext policy is not widened for Docker DNS. The
general application smoke leaves the observer/reconciler disabled; its scrape
proves bounded zero-state metric exposure and local routing, not a reachable
reconciliation attempt.

Evidence classification is mandatory:

| Class | What it proves | What it cannot prove |
|---|---|---|
| Static | source topology, disabled defaults, image pins, no inline secrets | live scrape, worker replay, or delivery |
| CI live | real local Temporal replay, real Platform metrics scraped by Prometheus, local Alertmanager routing | provider authorization, retention, production DB, or external page delivery |
| External | protected live Temporal and paging-provider receipts | never inferred from source or localhost |

## Security and history constraints

- Worker process receives Temporal connectivity only: no application DB, AI,
  Tool Gateway, connector, provider, or object-store credentials.
- Preserve the ordered existing start request; add no free text or secret-bearing
  fields to workflow input, memo, headers, search attributes, failure details,
  cancellation reasons, logs, metrics, or artifacts.
- Register no activities, signals, updates, queries, or product cancellation API.
- Test cancellation is cleanup/replay evidence only.
- Temporal client, starter, dispatcher, observer, reconciler, and worker remain
  default-off. No fallback to inline execution.
- External receiver secrets must live in protected secret files/environments and
  must not enter Git, CI logs, fixture receipts, or Docker build context.

## Verification order

1. Before each heavy command, require C free space >= 10 GiB and D >= 20 GiB.
2. Run focused Java configuration/readiness/restart tests and Phase 9 validators.
3. Run `git diff --check`, repository layout, secret/history, shell, YAML, and
   Compose config validation.
4. Run only storage-safe local checks; use exact-head CI for the full Maven,
   PostgreSQL, Docker/Compose, Prometheus, and cross-platform matrix.
5. Require every protected branch check and independent final review before merge.

## Rollback

Disable/remove the profile-gated worker and its local Temporal conformance
sidecar; disable the worker and reconciliation observer flags. Retain
Alertmanager when the application profile needs its explicitly configured
receiver. Do not edit V010-V013 or terminalize any uncertain `PENDING` binding.
Remove only the new source/config surface through a reviewed forward commit;
preserve immutable evidence.

## Remaining external blockers

- Production database query-plan/latency and DR exercise evidence.
- Live Temporal read credential can Describe/history-read while all mutation
  paths are denied, plus namespace-retention conformance.
- Paging provider selection, protected receiver secret, provider receipt schema,
  and end-to-end external acceptance/delivery receipt.
- B-013 pilot/calibration and threshold freeze.

Until those proofs exist, B-017 and master Phase 9 stay in progress, Temporal
admission/G4 stay disabled, and Phase 10 must not start.
