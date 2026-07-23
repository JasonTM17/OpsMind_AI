---
title: Phase 7 Capability-backed Investigation Integration
status: in_progress
priority: P1
created: '2026-07-23'
parent: ../260719-1747-opsmind-ai-production-platform/phase-07-thin-evidence-backed-incident-vertical-slice.md
---

# Phase 7 Capability-backed Investigation Integration

## Outcome

Replace the investigation fixture clients with fail-closed, bounded service
adapters; execute one Platform-owned Prometheus query through independently
authenticated Tool Gateway; persist its canonical evidence; and complete a
cited AI analysis without allowing model output to become an executable
request.

## Phases

| Phase | Scope | Status | Depends on |
|---|---|---|---|
| 1 | [Contract and identity boundary](./phase-01-contract-and-identity-boundary.md) | Completed | Checkpoint 4B |
| 2 | [Investigation AI adapter](./phase-02-investigation-ai-adapter.md) | Completed | 1 |
| 3 | [Platform Tool Gateway client](./phase-03-platform-tool-gateway-client.md) | Completed | 1 |
| 4 | [Durable Gateway and Prometheus connector](./phase-04-durable-gateway-prometheus.md) | Completed | 3 |
| 4A | [Operator investigation workspace](./phase-04a-operator-investigation-workspace.md) | Completed | 2, 4 |
| 5 | [Cross-service verification](./phase-05-cross-service-verification.md) | Completed (fixture checkpoint) | 2, 4, 4A |

## Locked Decisions

- Reuse `HttpAnalysisRuntimeClient`, canonical request construction, and the
  existing AI capability issuer; no second analysis transport stack.
- An AI tool intent is an untrusted selector. Only an exact
  `(connector, operation, arguments_digest)` catalog match can select a
  Platform-owned immutable invocation template.
- Tool Gateway requires two unrelated credentials: a short-lived workload
  bearer acquired by client credentials and a one-use exact-scope delegated
  capability signed by Platform. Neither token substitutes for the other.
- Tool execution IDs and evidence IDs remain deterministic per tenant/run/intent.
- External POSTs are not automatically retried. Ambiguous results reconcile by
  deterministic execution ID and the Gateway receipt contract.
- Tool Gateway durable state owns a separate PostgreSQL schema, migration
  history, runtime role, and migration role. Platform RLS tables stay separate.
- The first connector is read-only Prometheus `query_range`; PromQL, target,
  resource, result bounds, and label selectors are server-owned configuration.
- Inline content remains capped at 64 KiB. Truncated/artifact responses fail
  closed until B-006/B-008/B-012 are resolved.

## Exit Criteria

- All phase acceptance lists pass with revision-bound artifacts.
- A real non-production Prometheus service, not a mocked connector, supplies
  the selected bounded evidence in CI or an approved staging profile.
- The trace proves Operator/Platform → AI Runtime → Platform → Tool Gateway →
  Prometheus → evidence persistence → AI Runtime → cited terminal result.
- No raw prompt, model reasoning, credential, bearer token, private key,
  unrestricted URL, arbitrary PromQL, or raw provider payload is persisted or
  logged.
- Phase 7 remains read-only; Phase 9 durability and Phase 12 UI breadth are not
  falsely claimed.

## Risks

- Shared JWT code can accidentally merge the workload and delegated trust
  domains. Keep grant types, audiences, token-use claims, and providers distinct.
- A catalog digest that is not bound to canonical template bytes can become a
  confused-deputy path. Test digest drift and fail startup on duplicate keys.
- Holding a database transaction across Prometheus HTTP would exhaust the pool.
  Reserve and finalize receipts in separate short transactions with a lease.
- A connector may succeed before receipt finalization. Treat the read as
  potentially repeated physically, but converge to one logical evidence result.

## Unresolved Questions

- Production IdP/client-registration values and live target remain environment
  bindings; implementation uses secret injection and checked-in non-secret
  configuration only.
- Large evidence artifacts remain explicitly outside this checkpoint.
