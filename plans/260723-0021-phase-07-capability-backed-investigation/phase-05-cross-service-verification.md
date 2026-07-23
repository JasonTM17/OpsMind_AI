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

- [ ] One revision-bound run proves the entire cited read-only path. The
  runner and local provider are implemented; the revision-bound report still
  requires a disposable Compose/IdP execution.
- [ ] Failure paths are visible, sanitized, bounded, and leave no partial state.
- [ ] Trace/correlation evidence joins every service boundary.
- [ ] Secret/history/dependency/static/full-suite/Compose gates pass.
- [ ] Phase 7 backend integration checkpoint is complete, while live DeepSeek,
  frontend, Temporal resume, artifact lifecycle, and production release remain
  explicitly open.

## Rollback

Disable the real-client profiles and keep stored evidence/audit immutable.
