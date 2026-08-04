---
phase: 1
title: Lock state and nonce contracts
status: completed
priority: P1
effort: 30m
dependencies: []
---

# Phase 1: Lock state and nonce contracts

## Overview

Define the exact security behavior and evidence contract before changing the
browser-flow implementation.

## Requirements

- Expected output: Python safeguards/tests, schema-v3 PowerShell evidence, and
  Phase 3 plan/report reconciliation.
- Boundary: reference Keycloak harness only; no production BFF/session, vendor,
  API, token-signature, or application authorization changes.
- Compatibility: existing PKCE, MFA, logout, refresh, revocation, and sanitized
  result-file behavior remain unchanged.

## Architecture

`start()` returns nonce after state. Every tuple consumer (`enroll_totp`,
`login_with_totp`, replay denial, password authorization, and disabled-user
assertion) is updated explicitly. `AuthorizationResult` stores all transient
fields with `repr=False`. Callback state is checked before token exchange.
Nonce validation runs only inside authorization-code `exchange()`—never shared
`token_request()` used by password-denial, refresh, reuse, or revocation paths.

## Related Code Files

- Modify: `scripts/validation/keycloak/oidc_browser_flow.py`
- Modify: `scripts/validation/keycloak/run_oidc_conformance.py`
- Modify: closed evidence/static validators under `scripts/validation/`

## Implementation Steps

1. Lock state-before-I/O and exact nonce-match invariants.
2. Define schema `3`, scenario `phase-03-keycloak-oidc-v3`, and exact fields
   `AuthorizationCallbackStateTamperDenied=PASS` and `IdTokenNonceBound=PASS`.
3. Derive PASS only from exact bounded summary values asserted independently by
   the PowerShell runner before publication.
4. Define deterministic negative and positive test cases.

## Success Criteria

- [x] Requirements cover state tamper, absent/empty/non-string/malformed ID
  token, absent/non-string/mismatched nonce, and exact-match success.
- [x] Scope explicitly excludes production identity/session claims.

## Risk Assessment

Main risks: consuming a valid code during the tamper probe, leaking transient
values, or claiming production assurance. Keep the tamper check local, expose
boolean/bounded markers only, and retain reference-only evidence scope.
