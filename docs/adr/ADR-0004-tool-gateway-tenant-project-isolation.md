# ADR-0004: Tool Gateway Tenant and Project Isolation

- Status: Accepted
- Date: 2026-07-27
- Decision owners: Security and Tool Gateway owners

## Context

The Tool Gateway receives tenant/project values in the execution body, but those
values are authoritative only after a signed delegated capability is verified
and bound to the exact canonical request. V001 stores tenant/project on
`execution_receipts` without RLS. V001/V002 audit rows have no scope, and some
denials occur before capability verification can establish one.

Application-only comparison cannot protect a missed SQL predicate or pooled
connection. Conversely, assigning request scope to a pre-verification audit
would turn attacker-controlled input into tenant attribution.

## Decision

1. Derive immutable `TenantProjectScope` only from a returned
   `VerifiedCapability`, after exact request binding.
2. Propagate scope through receipt claims and leases, replay, verified denials,
   success finalization, and abandon.
3. Bind tenant/project to PostgreSQL with transaction-local settings after
   connection checkout. Force RLS on receipts and verified audit events; keep
   explicit tenant/project SQL predicates.
4. Store pre-verification denials in a separate global, insert-only,
   append-only security-audit table whose API and schema contain no
   tenant/project fields.
5. Keep capability nonce claims global and one-use.
6. Keep `execution_id` globally unique for public compatibility. If uniqueness
   finds an RLS-invisible foreign row, return the same non-enumerating conflict
   used for another scope or digest.
7. Keep connector I/O outside database transactions. Success audit append and
   receipt completion remain one atomic scoped transaction.
8. Reject startup when a configured receipt lease cannot cover the longest
   enabled connector timeout plus the fixed finalization margin.
9. Treat schema `USAGE` and the exact single RLS policy definitions as
   readiness contracts. Authenticated delivery rejections that precede request
   handling append only tenant-free audit decisions.

Historical V001/V002 audit rows remain immutable and unattributed. They are not
backfilled with guessed tenant/project values and are invisible to the runtime
scoped policy.

## Consequences

Positive:

- A missing application predicate no longer widens receipt or verified-audit
  access.
- Connection-pool reuse fails closed when context is absent or malformed.
- Pre-verification security evidence remains available without accepting
  request-selected tenant attribution.
- Public request/response and global idempotency contracts do not change.

Costs:

- Every tenant-owned persistence operation requires a scoped transaction.
- Old V002 runtime cannot operate after V003 because it does not bind context;
  migration and compatible runtime form one controlled deployment boundary.
- Legacy unattributed audit rows require privileged forensic access and are not
  part of tenant-scoped evaluation.
- Transaction-local settings mitigate accidental cross-tenant access; they do
  not make a compromised runtime role a tenant authority. Capability
  verification remains mandatory.

## Alternatives Considered

### Capability-only authorization

Rejected. It leaves every query dependent on perfect application predicates and
contradicts the repository rule requiring application authorization plus forced
RLS for tenant-owned persistence.

### Tenant-composite execution identity

Rejected because it changes the public idempotency contract and permits the same
execution ID to represent multiple effects.

### Nullable global rows in the scoped audit table

Rejected for new writes. An allow-null RLS policy would let the runtime choose
the global lane through field omission. A distinct API and table make the trust
boundary explicit.

### Tenant-scoped nonces

Rejected. A delegated capability nonce is a global one-use replay control;
partitioning it by caller-selected scope weakens replay resistance.

## Verification

- Tool Gateway unit tests prove verified and unverified audit routing.
- PostgreSQL tests prove forced RLS, runtime role posture, cross-tenant global-ID
  conflict, same-tenant/foreign-project denial, same-name policy-drift
  detection, fenced completion/abandon, atomic rollback, and single-connection
  A-to-B-to-no-context reuse.
- The upgrade gate seeds V002 receipt/audit state, applies V003, and proves
  preservation, exact forced-RLS policy definitions including enforcement
  against the table owner, no-context and foreign-project denial, scoped
  runtime access, and the separate unverified audit lane.
- Cross-service evaluation binds the same Tool Gateway tenant/project context
  before its read-only export views execute.
- B-016 remains active until those gates pass at an immutable CI revision.

## Rollback or Supersession Triggers

Recovery is forward-only. Restore a V003-compatible runtime or add a new
migration; never rewrite V001/V002 history or disable forced RLS. Supersede this
ADR only if a different durable authority source, database-per-tenant topology,
or versioned execution-identity contract is approved and migration-tested.

## Unresolved Questions

None for B-016. Production forensic access to legacy unattributed audit rows is
part of later operational access-control and retention work.
