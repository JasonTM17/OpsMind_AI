# Phase 5: Cross-service Verification

## Goal

Prove the complete read-only investigation path, its degraded modes, and its
operating envelope before frontend expansion.

## Files

- `scripts/validation/cross-service/run-investigation-slice.mjs`
  benchmark/trace runner and `fixture-provider.py` DeepSeek-compatible local
  provider
- CI/Compose wiring and observability configuration
- progress, architecture, testing, security, and deployment documentation

## Implementation

1. Provision ephemeral keys, JWKS, workload client registration, isolated DB
   roles, and synthetic Prometheus data outside Git-tracked secrets.
2. Start Platform, AI Runtime fixture-provider mode, Tool Gateway durable mode,
   PostgreSQL, and Prometheus. Use the same production adapters; only the model
   and evidence data are synthetic.
3. Run one authorized incident through initial analysis, catalog intent,
   Gateway read, evidence transaction, second analysis, cited completion, and
   read-model retrieval.
4. Assert no write-capable manifest exists and no raw reasoning, prompt,
   credential, token, or unredacted evidence appears in logs/audit/artifacts.
5. Correlate run, intent, execution, evidence, Gateway audit, model invocation,
   and trace IDs without using tenant-sensitive labels.
6. Exercise wrong workload token, wrong capability, selector drift, provider
   timeout, Gateway crash/reclaim, Prometheus unavailable, evidence digest
   mismatch, and current-membership revocation.
7. Measure warm p50/p95, bounded memory/body sizes, token/cost use, and DB pool
   behavior. Record limitations; do not claim production SLO from CI alone.

The checked-in fixture provider is explicitly loopback-only and can be enabled
only with `AI_PROVIDER=fixture` plus `AI_FIXTURE_PROVIDER_ENABLED=true`.
External DeepSeek remains on the narrower approved egress data-class policy;
the fixture-only policy extension is limited to already-redacted incident
summary material needed to exercise the production request shape.

## Acceptance

- [x] One revision-bound run proves the entire cited read-only path through a
  disposable Compose/IdP execution; the 100-warm-run report is bound to the
  clean revision and is validated fail-closed.
- [ ] Failure paths are visible, sanitized, bounded, and leave no partial state.
- [x] Trace/correlation evidence joins every service boundary.
- [x] Secret/history/dependency/static/full-suite/Compose gates pass for the
  local fixture-backed checkpoint.
- [x] Phase 7 backend integration checkpoint is complete, while live DeepSeek,
  a named live non-production connector, frontend BFF/session, Temporal resume,
  artifact lifecycle, and production release remain explicitly open.

## Rollback

Disable the real-client profiles and keep stored evidence/audit immutable.
