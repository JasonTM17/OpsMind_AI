# Code Review: Platform Tool Gateway Client

## Scope

- Platform investigation catalog, request canonicalization, dual-credential
  HTTP transport, failure taxonomy, response verification, configuration
- Shared canonical Tool Gateway fixture and Gateway digest conformance
- Phase 7 validator, architecture/status docs, focused boundary tests

## Assessment

No blocking or informational defect remains after adversarial review.

## Verified Boundaries

- Unknown or mutated model selectors fail before workload-token acquisition,
  capability issuance, or HTTP.
- Request body contains only the immutable Platform catalog template.
- Deterministic execution/evidence IDs and exact canonical body SHA-256 bind
  the one-use capability to tenant/project/incident/run/actor scope.
- Workload bearer and delegated capability use separate providers and headers.
- Direct HTTP disables ambient proxies and redirects, bounds connect/request/
  response-body time, and has no application retry path.
- Success and duplicate responses require exact execution/request/content
  digests, manifest, target, audit ID, provenance, bounds, and one inline
  non-truncated envelope.
- Unknown field/media type/status/code, artifact fallback, unsafe content, and
  all tested identity/provenance drift fail closed with sanitized errors.
- No production Tool Gateway client or credential component logs tokens,
  request bodies, or raw dependency responses.

## Adversarial Fixes Applied

- Added catalog-owned maximum execution duration so signed request deadlines
  cannot exceed the Gateway manifest limit.
- Added recursive item-bound enforcement instead of checking bytes only.
- Added raw digest format validation before constant-time comparison.
- Enforced denial outcome/code parity and exact problem instance UUIDs.
- Removed process proxy inheritance from the HTTP client.
- Added a byte-identical shared request fixture consumed by both services.

## Evidence

- Platform API: 190 tests, 0 failures, 0 errors, 20 environment-gated skips.
- Tool Gateway: 30 tests, 0 failures, 0 errors.
- Phase 7 checkpoint: `DUAL_CREDENTIAL_TOOL_CLIENT_CHECKPOINT`, 0 errors.
- Repository layout and documentation gates: pass.
- Prior revision `b79bde8`: remote PR quality run `29973495140` passed.

## Open Exit Gates

- Durable Tool Gateway nonce, execution receipt, and audit stores
- Live non-production read-only Prometheus evidence
- CK/Stitch operator UI and browser E2E
- Cross-service trace and p95 benchmark evidence

## Unresolved Questions

None.
