# Phase 5 Durable State Code Review

Date: 2026-07-22  
Scope: PostgreSQL nonce, replay, reservation, lease, budget, RLS, migration, runner  
Status: PASS for checkpoint; Phase 5 remains in progress

## Evidence

- Ruff format/check: PASS.
- mypy strict: PASS, 30 source files.
- Offline pytest: 85 passed, 4 PostgreSQL tests skipped by environment gate.
- Static Phase 5 validation: PASS.
- Project secret scan: PASS, 402 files, zero findings.
- Disposable PostgreSQL 18.4: V004 PASS, 4/4 state tests PASS, cleanup PASS.
- Evidence scope: local reference only; unborn/dirty checkout, not release proof.

## Review Findings

No Critical finding.

One High found: configured lease could expire before the request/provider global
deadline. A second replica could recover a live invocation and duplicate provider
cost. Fixed by making effective lease expiry the later of signed request deadline
and configured minimum lease. V004 now enforces `lease_expires_at >=
request_deadline_at`. Fix-only re-review found no Critical/High regression.

Additional correctness fixes made before final review:

- expired-lease recovery commits independently from later budget rejection;
- replay is rechecked after the run-budget row lock;
- every ledger/budget transition verifies exactly one updated row;
- known provider overage charges actual usage up to hard limits, stores uncapped
  actual invocation usage, and prevents budget reuse;
- ambiguous provider failure charges the full reservation.

## Accepted Threat Boundaries

- Nonce is an attempt token. A crash after consumption burns authority but cannot
  make it reusable; hashed consumption metadata remains.
- RLS protects tenant-scoping mistakes and pool reuse. Compromised runtime DB
  credentials with arbitrary SQL remain outside that boundary and require network,
  injection, rotation, least-privilege, and detection controls.
- Local PASS transcript does not replace clean revision-bound CI evidence.

## Unresolved Questions

- Cross-service asymmetric capability issuance and verification not implemented.
- Provider conformance/live synthetic smoke not run.
- Platform API invocation path and HTTP streaming contract not integrated.
