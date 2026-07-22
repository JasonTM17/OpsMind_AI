# Phase 1: Contract and Identity Boundary

## Goal

Define the trusted types and configuration that prevent AI output, request
bodies, and one credential domain from acquiring authority in another domain.

## Files

- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/integration/**`
- `services/platform-api/src/main/java/ai/opsmind/platform/delegation/**`
- `services/platform-api/src/main/resources/application.yaml`
- `.env.example`
- contract fixtures and focused tests under existing module conventions

## Implementation

1. Add an immutable investigation intent catalog keyed by connector, operation,
   and canonical template digest. Reject duplicates and unknown selectors at
   startup and execution.
2. The first entry owns tool/action/schema, exact resource, typed arguments,
   result bounds, required role, policy version, and expected manifest version.
3. Include the catalog's safe public selectors in the Platform-generated model
   prompt. Never include secrets, endpoint URLs, bearer tokens, or raw PromQL.
4. Add a Tool Gateway capability grant and RS256 issuer whose exact claims match
   the checked-in Gateway verifier contract: tenant/project/incident/run,
   internal actor subject, one action, one resource, one call, byte bound,
   policy version, audience, authorized party, nonce, and deadline.
5. Extract only the minimal shared signing primitive from the existing analysis
   issuer. Keep analysis and tool grant construction separate.
6. Add a workload token provider port plus OAuth2 client-credentials adapter.
   Bound endpoint, issuer/audience configuration, response size, connect/request
   timeout, token type, expiry, and refresh skew. Cache one token with
   single-flight refresh; never persist or log it.
7. Every new feature remains disabled by default and startup fails closed when
   a partially enabled security configuration is invalid.

## Tests

- Catalog canonical digest parity, duplicate collision, selector drift, and
  arbitrary model values denied.
- Tool capability fixture parity and adversarial cross-tenant/action/resource,
  audience, token-use, expiry, and body-binding tests.
- Workload token success, non-Bearer, oversize, malformed, expired, timeout,
  concurrent refresh, and sanitized failure tests.
- Existing analysis capability conformance remains unchanged.

## Acceptance

- [ ] No executable argument originates from `ToolIntent` text.
- [ ] Analysis, tool capability, and workload token domains are structurally
  distinct and cross-use is rejected.
- [ ] Disabled/missing/partial configuration fails before a network call.
- [ ] Unit and cross-language fixture tests pass without real secrets.

## Rollback

Disable the new client profile and retain fixture adapters. No migration occurs
in this phase.
