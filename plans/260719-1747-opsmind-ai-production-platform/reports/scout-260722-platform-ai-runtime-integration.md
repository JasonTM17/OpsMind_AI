---
type: scout
date: 2026-07-22
scope: platform-api-to-ai-runtime
---

# Platform API to AI Runtime Scout Report

## Summary

Reuse the incident authorization/RLS transaction, verified JWT principal mapper,
correlation filter, RFC 9457 handler, and RS256 capability issuer. Add a separate
incident-analysis controller because the current incident controller is already
near the modularization threshold. End the database transaction before network
I/O. Hash the exact canonical outbound bytes and never retry an ambiguous POST.

## Integration Map

| Concern | Existing seam | Decision |
|---|---|---|
| Identity | `JwtPrincipalMapper`, `OpsMindPrincipal` | Accept verified JWT authentication only |
| Authorization | `IncidentAccessRepository`, `IncidentRepository` | Add `incident:analyze`; hide cross-tenant/missing resources as 404 |
| Actor | `IncidentActor.id()` | Capability `sub` is the internal platform-user UUID |
| Request identity | `RequestDigest` | Canonical JSON bytes are serialized once, hashed, signed, and sent unchanged |
| Correlation | `CorrelationIdFilter` | Propagate its safe value to AI Runtime |
| Errors | `PlatformProblemException` | Map dependency errors to stable non-reflecting problems |
| Transport | No existing AI client | Exact configured endpoint, bounded timeout/body, redirects disabled |
| Replay | AI Runtime durable `(tenant, run, digest)` replay | Caller supplies stable `run_id`; no blind platform retry |

## Contract Hazards

- Java must emit explicit snake-case fields, UTC `Z` deadlines, recursively
  sorted object keys, sorted unique data classifications, and ordered context refs.
- Runtime capability JWTs require exactly three header fields and twelve claims;
  any convenience claim breaks verification.
- `VIEWER` must not gain provider-spend authority through `incident:read`.
- A response body must be capped and validated before returning it to the client.

## Verification

- Shared request fixture plus golden digest asserted independently in Java and Python.
- Ephemeral RSA keys only; no committed private key.
- Unit tests cover exact body/header propagation, timeout/error mapping, authorization,
  and no network call before access/capability validation.

## Unresolved Questions

- Tenant-wide/daily cost quotas and durable platform-side analysis-run lifecycle are
  later gates; the current runtime enforces per-run cumulative limits only.
- Production AI Runtime endpoint and workload TLS identity remain deployment inputs.
