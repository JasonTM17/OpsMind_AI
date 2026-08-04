---
phase: 2
title: Implement browser-flow safeguards and tests
status: completed
priority: P1
effort: 90m
dependencies:
  - 1
---

# Phase 2: Implement browser-flow safeguards and tests

## Overview

Implement state and nonce safeguards in the existing helper and add focused
standard-library unit tests.

## Requirements

- Reject invalid callback state/code before any token request; prove this with
  an unchanged request counter in the live helper and a mocked zero-call test.
- Require a string, well-formed ID token containing a string exact request nonce.
- Keep token-bearing results private and preserve all existing flow behavior.

## Architecture

Use one callback-code parser shared by live exchange and the tamper probe. Keep
a private token-request counter incremented only by `token_request()`. Decode
only the bounded pinned-TLS token response already used by the harness, validate
types, compare nonce with constant-time equality, and return the unchanged token
response after validation. Do not add nonce logic to refresh/revocation paths.

## Related Code Files

- Modify: `scripts/validation/keycloak/oidc_browser_flow.py`
- Modify: `scripts/validation/keycloak/run_oidc_conformance.py`
- Create: `scripts/validation/keycloak/test_oidc_browser_flow.py`
- Modify: `scripts/validation/keycloak/keycloak-conformance-profile-files.txt`

## Implementation Steps

1. Extend the `start()` tuple and every destructuring caller; preserve nonce in
   `AuthorizationResult` with all transient fields excluded from `repr`.
2. Refactor callback validation ahead of token I/O.
3. Bind returned ID-token nonce before accepting tokens.
4. Add unit tests for zero-call state denial; absent/empty/non-string/malformed
   ID token; absent/non-string/mismatch nonce; exact match; unchanged result.
5. Emit bounded summary values for the live runner.

## Success Criteria

- [ ] State tamper never invokes `token_request`.
- [ ] Every invalid ID-token/nonce shape fails closed with bounded errors; exact
  match succeeds and returns the original dictionary object.
- [ ] Existing flow signatures and callers are coherently updated.

## Risk Assessment

Signature changes can leave stale call sites. Search every `start` and
`exchange` call and run syntax/unit checks before evidence changes.
