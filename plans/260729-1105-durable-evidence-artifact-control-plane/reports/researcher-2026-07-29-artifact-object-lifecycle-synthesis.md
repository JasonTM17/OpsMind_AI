# Artifact Object Lifecycle Synthesis

## Decision

Implement Phase 02 as a default-off, single-PUT S3-compatible adapter behind
durable V015 lease fencing. Use AWS SDK Java v2 `2.49.5`, disable SDK retries,
and let the application own ambiguous-outcome recovery through HEAD adoption.
Do not add public ingress, signed URLs, MinIO client code, readable artifacts,
or Tool Gateway acceptance in this slice.

## Verified Existing Boundary

- Platform authorization derives tenant, project, incident, actor, and current
  incident-version epoch inside a transaction-local tenant context.
- V014 stores metadata only, hides `storage_key`, forces RLS, and permits only
  immutable `PENDING_UPLOAD` version 1.
- Tool Gateway and Platform both reject truncated or non-null
  `artifact_reference` evidence; V007 remains bounded inline evidence.
- No artifact ingress route exists.

## Selected Protocol

1. Reauthorize and acquire one durable claim with a database-time lease.
2. Commit before remote I/O.
3. On retry, HEAD before PUT. Adopt only exact length, SHA-256, encryption
   profile, and version metadata.
4. Otherwise stream one conditional PUT with exact length and expected digest.
5. Reauthorize again and settle with attempt ID, lifecycle version, lease,
   digest, length, encryption reference, and version reference fences.
6. Advance only to `STORED`; scanning still gates `AVAILABLE`.

## Rejected Alternatives

- Direct connector/browser S3 credentials or presigned URLs: bypass platform
  authorization and lifecycle authority.
- SDK automatic retry: may replay a non-repeatable stream outside the durable
  attempt protocol.
- MinIO Java client: couples the port to an archived local implementation and
  does not improve production S3 compatibility.
- Multipart in Phase 02: materially expands crash/orphan state. The first
  adapter fails closed above 5,000,000,000 bytes; metadata may still register a
  larger future intent.
- Editing V014: violates immutable applied-migration policy. V015 is additive.

## Remaining Blockers

- B-006: production KMS, lifecycle, scanning, and restore evidence.
- B-008: enforceable hold/deletion/purge and lineage controls.
- B-012: supported backend or approved bounded local-only image exception.
- Phase 03: capability-bound producer transport and a bounded streaming Tool
  Gateway producer abstraction.

## Unresolved Questions

No code-level decision blocks Phase 02. Production backend, KMS ownership,
scanner, retention, and restore conformance remain external release gates.
