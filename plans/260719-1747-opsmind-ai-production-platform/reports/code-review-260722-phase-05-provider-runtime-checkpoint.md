# Phase 5 Provider Runtime Checkpoint Review

Date: 2026-07-22  
Scope: provider-neutral contract, delegated capability, egress policy, cumulative budget guard, DeepSeek client/adapter, bounded API ingress, schemas, fixtures, and offline tests.

## Verdict

Checkpoint review passed. Full Phase 5 remains in progress.

## Review sequence

1. Spec-compliance checkpoint verified implementation against the Phase 5 contract and non-goals.
2. Edge-case scouting identified classification, citation, deadline, concurrency, response-bounding, and cumulative-budget risks.
3. Production-readiness review identified caller-controlled classification, missing provider completion limits, pre-parse body exposure, capability-lifetime bounds, and deterministic retry timing.
4. Adversarial review identified expiry races, cumulative allowance gaps, unsafe token estimation, zero-price readiness, schema/body-limit mismatch, model-owned status leakage, slow/chunk-amplified bodies, and ambiguous provider accounting.
5. Fix-only re-reviews verified each accepted finding. Final reviewer reported no remaining Critical or High blocker in this checkpoint scope.

## Accepted controls

- Exact canonical request digest, bounded capability lifetime, issuer/audience/scope/deadline matching, and nonce replay protection.
- Evidence source classification must match the signed request; citations bind authorized evidence ID and content digest.
- Exact provider-host allowlist, positive live pricing, provider-disabled default, and key-presence-not-authorization behavior.
- Pre-parse 1 MiB body cap, bounded receive time, bounded chunk count, and coalesced ASGI replay.
- Atomic cumulative per-run token/cost allowance, one in-flight call per run, provider `max_tokens` cap, and full-reservation charging after ambiguous provider execution.
- Global provider deadline, bounded full-jitter retries, bounded response bytes, strict model/finish/schema/usage validation, and sanitized problem details.
- Raw reasoning content is ignored and never persisted or logged.

## Fresh verification

- Ruff format/check: pass.
- Mypy strict: pass for 23 source files.
- Pytest: 82 passed.
- `uv lock --offline --check`: pass; 54 packages resolved.
- No real provider key or provider call used.

## Release status

This is local checkpoint evidence only. Repository state is unborn and dirty, so it is not release evidence and no artifact was pushed.

## Remaining Phase 5 work

- Durable invocation and replay persistence.
- Shared replay/nonce and accounting state suitable for horizontal scaling.
- Platform-issued asymmetric cross-service capability verification.
- Provider capability probe, fixture conformance runner, and approved synthetic live smoke.
- Streaming HTTP contract and Platform API integration.

## Unresolved questions

- Production provider region, privacy, retention, and incident-data egress authorization remain external decisions.
- Streaming is not yet approved as a first operator-slice requirement.
