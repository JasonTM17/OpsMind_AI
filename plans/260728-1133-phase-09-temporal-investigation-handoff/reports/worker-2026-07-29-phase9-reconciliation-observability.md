---
type: worker
title: Phase 9 reconciliation observability implementation
date: 2026-07-29
scope: Phase 7 workstream C
status: complete
---

# Phase 9 reconciliation observability implementation

## Result

Implemented network-scoped Prometheus ingestion, aggregate recording rules, and
operator alerts for exact-workflow reconciliation. Scrape storage fails closed
to the required aggregate metrics and fixed label values.

## Files

- `deploy/prometheus/prometheus.yml`
- `deploy/prometheus/opsmind-recording-rules.yml`
- `deploy/prometheus/opsmind-reconciliation-alerts.yml`
- `scripts/validation/validate-phase-09-reconciliation-observability.mjs`
- this report

## Contract delivered

- Scrapes only `platform-api:8082/actuator/prometheus` on the internal Compose
  network. Redirects and HTTP/2 disabled.
- Keeps only the eight reconciliation metric families.
- Keeps only `job`, `instance`, `outcome`, `operation`, `result`, `le`, and
  `quantile`, plus `__name__`.
- Fixed values:
  - `outcome`: `match`, `absence_candidate`, `verified_absence`, `released`,
    `mismatch`, `retry`, `blocked`, `lease_lost`, `exhausted`;
  - `operation`: `describe`, `first_history`;
  - `result`: `started`, `rejected`, `blocked`.
- Enforces 1 MiB response, 256 accepted samples, eight labels, 128-character
  label names, and 256-character label values.
- Records aggregate readiness, backlog, lag, blocker, retention, outcome, and
  five-minute timer means without tenant/run/event/workflow/error labels.
- Aggregates readiness with `min(...)` so one unready replica cannot be masked
  by a healthy replica.
- Adds critical alerts for blocked, exhausted, retention-ineligible, critical
  lag, not-ready, and no-progress conditions. Adds warning lag at 30 seconds
  sustained for two minutes.
- Treats a failed reconciliation scrape as not-ready, evaluates outcome
  increases per raw replica, and links every alert to the owned cutover runbook.

## Evidence

Passed:

```text
node scripts/validation/validate-phase-09-reconciliation-observability.mjs
Phase 9 reconciliation observability validation passed: internal scrape, bounded labels, aggregate recordings, and seven alerts.

python -c "<PyYAML safe-load of three Prometheus YAML files>"
PyYAML syntax validation passed: 3 mapping documents

git diff --check
PASS
```

No Docker, build, dependency install, or image pull run. Local `promtool` is not
installed and D: free space is below the required heavy-work threshold.

## Integration risks

1. `prometheus.yml` references
   `/etc/prometheus/opsmind-reconciliation-alerts.yml`; the Compose owner must
   add the read-only bind mount before merged-head Prometheus startup.
2. `OpsMindWorkflowReconcilerNotReady` assumes the readiness series is absent
   while Temporal mode is disabled and is `0` only for an enabled, unready
   reconciler. Runtime integration must preserve that contract.
3. Runtime timer labels must match the fixed allowlists exactly. Unknown label
   values are intentionally dropped.
4. Merged head still needs pinned `promtool check config`, rule evaluation, and
   a live scrape proving no forbidden labels before B-017 closes.

## Unresolved questions

None.
