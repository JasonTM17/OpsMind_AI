---
date: 2026-07-22
scope: phase-06-tool-gateway-checkpoint
status: complete-with-concerns
---

# Phase 6 Tool Gateway Checkpoint Review

## Scope

- `services/tool-gateway/src/main/java/ai/opsmind/toolgateway/**`
- Tool Gateway schemas, fixtures, OpenAPI, and manifest resource
- Phase 6 deterministic validator and Maven tests
- Focus: trust boundaries, contract invariants, bounded execution, replay, and readiness

## Overall assessment

The fixture-only checkpoint is contract-safe and fail-closed by default. Dedicated workload
and delegated-capability token domains, strict JSON parsing, exact resource binding, bounded
evidence normalization, DLP canaries, response status mapping, and readiness gates are present.
The implementation is not yet production-ready because the durable and live-integration gates
are intentionally absent.

## Findings and disposition

| Finding | Severity | Disposition |
|---|---:|---|
| Missing request fields could produce a response without the required `execution_id` | High | Fixed with Jakarta boundary validation, 400 Problem Details, and null-preserving request mapping |
| Fixture manifest accepted unresolved schema/credential/egress declarations | High | Fixed for the authoritative fixture: exact schema ID, credential profile, and egress target are required; egress values are bounded and reject credentials, wildcards, private literals, and non-HTTPS schemes (except `fixture://`) |
| Connector provenance metadata bypassed the content redactor | Medium | Fixed by rejecting metadata that changes under the same sensitive-value redactor |
| Audit success is written before durable receipt completion | High | Remains a PhaseExit blocker; requires a transactional state machine or outbox/reconciliation adapter |
| Interrupt-ignoring providers can retain a bulkhead permit; bulkhead is global rather than tenant-scoped | High | Remains a PhaseExit blocker; requires provider cancellation evidence and tenant-aware limits |
| JWKS client has transport timeouts but no per-target refresh limiter | High | Remains a production hardening item; requires exact-URI refresh throttling/cache metrics |
| Three connector families and a selected live non-production connector are absent | High | Remains a PhaseExit blocker |
| Platform API capability issuer conformance is absent | High | Remains a PhaseExit blocker |

## Verification

- Maven Tool Gateway: 24 tests passed, zero failures/errors/skips.
- Phase 6 validator: `CheckpointResult=PASS`, `Errors=0`.
- Phase 4 contract/OpenAPI validator: 18 schemas, 21 fixtures, 171 references, 8 operations, `Result=PASS`.
- Repository layout validator: `Result=PASS`.
- Full security scan before publication: required as the final pre-commit gate.

## PhaseExit status

`PhaseExit=BLOCK` remains correct. Blocking conditions are durable atomic nonce/receipt/audit/
artifact adapters, Platform API issuer conformance, three fixture connector families, selected
live read-only connector proof, and provider-specific cancellation plus tenant bulkheads.

## Unresolved questions

- Should `max_items` be included in the signed capability budget, or remain a manifest/request bound?
- Which durable adapter owns atomic receipt, audit, and artifact state transitions?
- What cancellation contract can each live provider prove under timeout and client disconnect?
