---
title: Phase 3 OIDC State and Nonce Evidence
description: >-
  Prove callback-state tamper denial and ID-token nonce binding in the
  revision-bound Keycloak reference harness.
status: completed
priority: P1
effort: 3h
branch: fix/oidc-state-nonce-evidence
tags:
  - auth
  - security
  - tests
blockedBy: []
blocks: []
created: '2026-08-04T04:44:42.126Z'
createdBy: 'ck:plan'
source: skill
---

# Phase 3 OIDC State and Nonce Evidence

## Overview

Close the bounded Phase 3 reference-profile assurance gap without claiming a
production IdP or browser-session implementation. Preserve the generated nonce
through authorization-code exchange, reject state tampering before token I/O,
bind the returned ID token to the request nonce, and publish closed schema-v3
evidence backed by deterministic unit tests plus live Keycloak CI.

## Phases

| Phase | Name | Status |
|-------|------|--------|
| 1 | [Lock state and nonce contracts](./phase-01-lock-state-and-nonce-contracts.md) | Completed |
| 2 | [Implement browser-flow safeguards and tests](./phase-02-implement-browser-flow-safeguards-and-tests.md) | Completed |
| 3 | [Validate evidence and release gate](./phase-03-validate-evidence-and-release-gate.md) | Completed |

## Dependencies

- Parent: `../260719-1747-opsmind-ai-production-platform/plan.md`.
- Builds on merged PR #62 revision-bound Linux Keycloak evidence.
- Does not unblock production enterprise IdP/session conformance or G2.

## Acceptance Criteria

- Tampered callback state returns the exact bounded outcome
  `callback_state_mismatch`; a before/after request counter and a mocked unit
  boundary both prove the token endpoint was not invoked.
- Authorization-code exchange rejects absent, empty, non-string, malformed, or
  nonce-less ID tokens plus non-string/mismatched nonces; exact nonce succeeds
  and returns the token dictionary unchanged.
- Live Keycloak flow reports state denial and nonce binding without printing
  tokens, authorization codes, passwords, or nonce values.
- Runner/verifier/static contracts require exactly
  `EvidenceSchemaVersion=3`, `ScenarioVersion=phase-03-keycloak-oidc-v3`,
  `AuthorizationCallbackStateTamperDenied=PASS`, and
  `IdTokenNonceBound=PASS`; stale v2 evidence cannot pass.
- Focused Python tests, static Phase 3 validation, and exact-head PR CI pass.

The two PASS fields are derived only after the Python result summary equals
`stateTamperDenied=callback_state_mismatch`, reports unchanged token-request
count, and reports `idTokenNonceBound=true`; PowerShell asserts these values
before evidence publication.

## Delivery Evidence

PR #64 exact head `f29638e81b483c3c95cfe995ce5ba729681793e8`
passed PR Quality run `30881416141`, including live Keycloak job
`91904344586`, and merged as
`57ac8498529ef9c093f65ee77fbc579a515359ca`. All 18 rollup checks succeeded;
the merge tree equals the tested head tree. This closes only the reference
harness child plan, not production IdP or browser/BFF session conformance.
