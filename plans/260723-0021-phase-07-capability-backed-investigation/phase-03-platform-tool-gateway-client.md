# Phase 3: Platform Tool Gateway Client

## Goal

Execute one catalog-approved read through strict dual authentication and convert
only a fully verified Gateway response into `CollectedEvidence`.

## Files

- Tool Gateway client/configuration/transport under the investigation integration package
- shared bounded HTTP response helper only if the existing analysis helper cannot be composed safely
- Platform tests and contract fixtures

## Implementation

1. Resolve an intent to one immutable catalog invocation. Compute deterministic
   execution/evidence IDs and canonical request bytes.
2. Acquire an independent workload bearer and issue a one-use delegated tool
   capability for the exact body scope.
3. Send one deadline-bounded POST with strict media types, maximum response
   bytes, no redirect, no ambient proxy, and no automatic retry.
4. Map only documented Gateway statuses/codes to sanitized Platform dependency
   errors; unknown status, field, media type, or body is invalid dependency data.
5. Require matching execution ID, request digest, manifest, target, audit ID,
   provenance, result bounds, and exactly one inline evidence envelope.
6. Canonicalize evidence content in Platform and require digest parity. Reject
   artifacts, truncation, unsupported trust/source, unsafe metadata, and any
   identity mismatch before constructing `CollectedEvidence`.
7. Preserve Gateway duplicate state. A later Platform save uses the exact replay
   verifier completed by checkpoint 4B.

## Tests

- Request/capability fixture parity and deterministic identities.
- Success and duplicate responses.
- Mismatched execution/request/content digest, manifest, target, provenance,
  audit ID, oversize, extra fields, denial taxonomy, timeout, and ambiguous
  transport failure.
- Prove workload bearer and capability headers are separate and never logged.

## Acceptance

- [x] Unknown/mutated AI intent cannot cause HTTP.
- [x] Only exact catalog body bytes receive a matching capability.
- [x] Only a fully verified inline response becomes persisted evidence.
- [x] Ambiguous transport is not retried automatically.

## Evidence

- `HttpInvestigationToolGatewayClient` sends one direct, no-redirect bounded
  POST with separate workload and delegated-capability headers.
- The shared canonical investigation request fixture is byte-equal to the
  Platform request and accepted by the Tool Gateway request digester.
- Boundary tests reject execution/request/content digest, manifest, target,
  provenance, audit, truncation/artifact, item-bound, media-type, and unknown
  field drift; documented denial codes map to sanitized dependency errors.
- Credential acquisition and capability issuance happen only after the
  immutable selector catalog resolves the model intent.
- Full local verification: Platform API 190 tests with 0 failures/errors and
  20 environment-gated skips; Tool Gateway 30 tests with 0 failures/errors.

## Rollback

Disable the HTTP Tool Gateway client and retain deterministic fixture mode.
