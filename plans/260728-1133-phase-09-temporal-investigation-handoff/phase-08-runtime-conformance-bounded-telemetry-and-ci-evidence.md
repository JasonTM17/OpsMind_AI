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
default-off workflow-only Temporal worker, prove restart/replay with the official
test environment, reject stale compatible-poller records, scrape the real
reconciliation metrics through pinned Prometheus, and prove secretless local
Alertmanager routing. Phase 10 remains blocked.

## Scope decisions

- Reuse the existing Platform API artifact as a separate worker-only process;
  do not create a Python worker or a new microservice/image boundary.
- Register exactly `opsmind-investigation-v1` and accept the existing bounded
  `InvestigationWorkflowStartRequest`. The implementation validates immutable
  workflow metadata, schedules no activities, and parks durably. It must remain
  disabled outside conformance because it cannot complete an investigation.
- Readiness requires exact identity/build ID and a fresh `PollerInfo.lastAccessTime`
  checked with an injected clock. Missing, malformed, stale, or future timestamps
  fail closed.
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
- `InvestigationTemporalClientProperties.java`;
- `InvestigationTemporalClientConfiguration.java`;
- `TemporalInvestigationWorkerReadinessProbe.java`;
- directly corresponding worker/readiness tests under the same package.

Acceptance:

1. Worker absent by default and validates exact type/queue/identity/build plus
   bounded concurrency and shutdown settings when enabled.
2. Fresh exact poller opens admission; wrong, missing, stale, malformed, future,
   or RPC-failed poller closes it.
3. Official Temporal test environment proves start, first task, worker stop,
   replacement worker replay, and terminal test cancellation without duplicate
   start or activity events.
4. History/artifacts contain none of the prohibited prompt, evidence, token,
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
- `.env.example`;
- `scripts/dev/opsmind.ps1` and `scripts/dev/opsmind.sh`;
- plan/docs/status reconciliation after the merged behavior is proven.

The controller merges Lane A then Lane B, resolves only integration joins, runs
focused validators/tests, pushes the integration branch, waits for exact-head
required checks, requests an independent production-readiness review, fixes all
blocking findings, and merges only after green evidence.

## CI evidence contract

Artifacts under `artifacts/verification/phase-09-workflow-handoff/` must record:

- exact commit SHA and UTC timestamps;
- exact Prometheus and Alertmanager image references/digests;
- configuration/rule digests and executed commands;
- Temporal restart test class plus zero-failure/zero-skip counts;
- target health, series count, exact observed label names, and response bounds;
- one sanitized local routing receipt digest/status.

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
  fields to workflow input, memo, logs, metrics, or artifacts.
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

Disable/remove the profile-gated worker and Alertmanager conformance services;
disable the worker and reconciliation observer flags. Do not edit V010-V013 or
terminalize any uncertain `PENDING` binding. Remove only the new source/config
surface through a reviewed forward commit; preserve immutable evidence.

## Remaining external blockers

- Production database query-plan/latency and DR exercise evidence.
- Live Temporal read credential can Describe/history-read while all mutation
  paths are denied, plus namespace-retention conformance.
- Paging provider selection, protected receiver secret, provider receipt schema,
  and end-to-end external acceptance/delivery receipt.
- B-013 pilot/calibration and threshold freeze.

Until those proofs exist, B-017 and master Phase 9 stay in progress, Temporal
admission/G4 stay disabled, and Phase 10 must not start.
