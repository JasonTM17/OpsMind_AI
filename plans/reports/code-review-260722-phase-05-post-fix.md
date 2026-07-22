---
date: 2026-07-22
scope: phase-05-post-fix-adversarial-review
status: complete-with-concerns
---

# Phase 5 Post-fix Adversarial Review

## Scope

- Python egress redaction and capability-probe lifecycle
- Java authoritative evidence redaction parity
- PostgreSQL V005 quota/audit migration and adapter
- readiness monitor, startup/retry scheduling, and cancellation handling
- focused tests plus disposable PostgreSQL evidence

## Findings and disposition

| Finding | Severity | Disposition |
|---|---:|---|
| CamelCase and structured credential keys (`apiKey`, `bearerToken`, `token`, `authorizationHeader`, `apiCredential`, etc.) bypassed redaction | Critical | Fixed in Python and Java patterns; mirrored canary tests pass; Phase 2 secret scan passes |
| Shared success cache could make one replica prove another replica's provider path | High | Removed; every process performs its own probe |
| Five-minute dedupe and app-clock window created readiness races | High/Medium | Replaced with PostgreSQL advisory transaction lock, DB-clock hourly quota, and default timestamp |
| Replica probes synchronized during rollout | High | Bounded startup jitter plus healthy/retry jitter; quota sizing formula documented |
| Monitor could die without recovery | Medium | Outer monitor boundary marks unready and retries; exceptional done callback remains fail-closed |
| Cancelled probe could leave an unfinished lifecycle event | Medium | Shielded bounded terminal audit attempt with explicit cancellation code; DB outage remains a documented best-effort orphan signal |

## Verification

- Python: 149 passed, five PostgreSQL-gated skipped; Ruff and mypy clean.
- Platform API Maven: pass; focused migration/redaction tests and full suite pass.
- Tool Gateway Maven: prior suite pass before Phase 6 ownership work.
- PostgreSQL 18.4 disposable gate: five tests passed, including concurrent quota race and append-only privilege denial; cleanup passed.
- Phase 2 foundation: all 18 checks pass, including secret scan and repository layout.
- Phase 5 static checkpoint: `CheckpointResult=PASS`; phase exit remains intentionally `BLOCK` for B-004 and absent rotated-key live synthetic evidence.

## Residual concerns

The default `AI_PROVIDER_PROBE_MAX_CALLS_PER_HOUR=120` supports at most roughly
ten continuously probing replicas at the 300–330 second healthy interval. A
larger deployment must set the quota from the documented replica formula and
the provider rate limit. Cancellation audit completion is best effort when the
database itself is unavailable; orphaned `started` rows must be monitored and
reconciled without treating them as successful readiness.

## Unresolved questions

- B-004 provider region, processing terms, retention, and redaction verification
  still require an owner-approved external evidence package.
- The rotated staging credential and live synthetic smoke remain intentionally
  absent; no provider key was loaded or logged by this review.
