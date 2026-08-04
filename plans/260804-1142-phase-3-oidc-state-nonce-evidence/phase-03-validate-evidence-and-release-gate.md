---
phase: 3
title: Validate evidence and release gate
status: completed
priority: P1
effort: 60m
dependencies:
  - 2
---

# Phase 3: Validate evidence and release gate

## Overview

Upgrade the closed evidence contract, reconcile Phase 3 documentation, and run
the full review/CI landing gates.

## Requirements

- Schema `3` and scenario `phase-03-keycloak-oidc-v3` must require exactly
  `AuthorizationCallbackStateTamperDenied=PASS` and `IdTokenNonceBound=PASS`,
  reject unknown fields, and reject stale v2 evidence.
- Static validation must bind the new test file and source invariants.
- Local heavy Keycloak/Compose execution remains deferred to clean CI because
  workstation capacity preflight is blocked.

## Related Code Files

- Modify: `scripts/validation/run-phase-03-keycloak-conformance.ps1`
- Modify: `scripts/validation/verify-phase-03-keycloak-evidence.ps1`
- Modify: `scripts/validation/validate-phase-03-trust-foundation.mjs`
- Modify: Phase 3 plan/progress files only where claims change

## Implementation Steps

1. Require summary `stateTamperDenied=callback_state_mismatch`, unchanged
   token-request count, and `idTokenNonceBound=true` before publishing v3 PASS.
2. Update strict verifier and static source contracts.
3. Reconcile reference-profile scope in Phase 3 documentation.
4. Run focused tests/static checks, delegated tester, and adversarial review.
5. Commit focused slices, open PR, require exact-head CI, merge, and clean up.

## Success Criteria

- [x] Closed evidence v3 validates and v2 cannot satisfy the verifier.
- [x] Tests/static checks and independent review have zero blocking findings.
- [x] Exact-head CI passes before merge.

Delivery evidence: PR #64 exact head
`f29638e81b483c3c95cfe995ce5ba729681793e8`, PR Quality run
`30881416141`, live Keycloak job `91904344586`, and merge
`57ac8498529ef9c093f65ee77fbc579a515359ca` all succeeded with identical
tested/merged trees.

## Risk Assessment

Main risk is false-positive evidence publication. PowerShell must independently
assert live summary values before writing PASS fields; CI remains authoritative.
