# OpsMind AI Code Standards

## Principles

Apply YAGNI, KISS, and DRY in that order. Prefer explicit domain behavior over generic frameworks. Implement real boundary validation and error handling; do not add fake behavior, silent fallback, or test-only shortcuts to make a gate green.

## Canonical Repository Ownership

```text
apps/
  operator-web/       # Next.js
services/
  platform-api/       # Java 21 Spring modular monolith
  ai-runtime/         # Python FastAPI
  tool-gateway/       # Java 21 Spring isolated connector boundary
packages/
  contracts/
    openapi/
    json-schema/
    fixtures/
docs/
scripts/
compose.yaml
```

These paths are created in later phases. Competing API definitions, duplicate Compose roots, or cross-service migration ownership are prohibited.

## Naming

- Java packages: lowercase dot-separated; classes and records: PascalCase; methods and fields: camelCase.
- Python modules and functions: snake_case; classes: PascalCase; constants: uppercase snake case.
- TypeScript variables and functions: camelCase; components and exported types: PascalCase.
- Documentation, scripts, and project-level filenames: descriptive kebab-case unless a tool requires another name.
- Database tables, columns, constraints, and indexes: snake_case with stable domain names.
- Events describe completed domain facts, not implementation steps.

Do not embed plan phase numbers, audit finding IDs, or transient ticket labels in runtime symbols, migrations, tests, or comments.

## Module Boundaries

- Domain modules own behavior and interfaces; adapters own framework and provider details.
- Dependencies point inward: delivery and infrastructure depend on application/domain contracts.
- The AI Runtime cannot import connector credentials or Platform API persistence internals.
- The Tool Gateway cannot trust actor or tenant headers without verifying a platform capability.
- Shared packages contain stable contracts, not service-specific business logic.
- New abstraction requires at least one real boundary or repeated domain need.

## File Size and Structure

Consider modularization when a code file exceeds 200 lines. Split on domain responsibility, lifecycle, or boundary—not merely line count. Markdown, configuration, migrations, and scripts may exceed the guideline when splitting would reduce clarity.

Prefer guard clauses over deep nesting. Keep public interfaces small and typed. Comments explain invariants, threat boundaries, or non-obvious trade-offs; they do not narrate obvious syntax.

## Public Contracts

- OpenAPI and JSON Schema under `packages/contracts` are authoritative.
- Validate external input at the first trusted boundary.
- Reject unknown or unsupported fields where compatibility allows.
- Version intentional breaking changes and provide migration evidence.
- Consumer and provider contract tests must use the same canonical fixtures.
- Model output is external input and receives the same validation discipline.

## Errors

- Use stable machine-readable error codes plus safe human messages.
- Preserve causal exceptions internally; do not leak stack traces, SQL, secrets, tokens, or raw evidence externally.
- Distinguish validation, authentication, authorization, policy denial, conflict, dependency failure, timeout, budget exhaustion, and ambiguous external effect.
- Never catch and silently continue across a state transition or external write.
- Retry only classified transient failures and only within explicit budgets.

## Identity and Tenant Context

- Derive actor and tenant context from verified identity and membership.
- Use framework issuer discovery and JWKS signature verification; custom token
  policy may narrow accepted audience, lifetime, clock skew, subject, and AMR
  but must not implement custom signature or refresh-token cryptography.
- Pin the current OIDC decoder to RS256. Bound discovery/JWKS calls with
  500-millisecond connect/read timeouts and at most one request per exact target
  URI, per Platform API instance, per configured interval. Keep the checked-in
  interval at `PT1S` and validate 100 milliseconds–1 minute. Do not describe
  this as a cluster-wide limit or promise immediate key-rotation acceptance;
  another same-target request inside the interval must fail closed.
- Keep the checked-in access-token maximum at `PT5M` across application,
  Compose, and environment defaults; configuration validation permits only
  1–5 minutes and 0–60 seconds of clock skew. Describe IdP invalidation precisely:
  refresh-token revocation prevents refresh; it does not imply immediate
  invalidation of an already issued stateless access JWT.
- Recheck platform user status on each authenticated API request while the
  system has no stronger bounded-revocation cache. Unknown, deprovisioned, or
  unavailable identity authority must fail closed.
- Distinguish local platform-user deprovisioning from upstream IdP disablement.
  The former denies on the next persisted API request; the latter denies new
  login while a pre-issued access JWT remains accepted. State the 300-second
  issuance lifetime separately from configured timestamp skew (330-second
  reference and 360-second checked-in policy upper bounds); do not claim an
  exact disable-to-denial horizon without an expiry-boundary test.
- Pass a typed security context rather than loose headers or string maps.
- Use transaction-local RLS context and separate least-privilege database roles.
- Run schema migrations with a dedicated owner/job identity; long-running
  application pools use a non-owner role that cannot bypass row security. The
  fixed `opsmind_context_resolver` role is non-login and non-bypass and may own
  only narrow authority resolver functions; it is not a substitute for
  application authorization. Never let the web process apply migrations with
  its runtime credentials.
- Keep outbox append and dispatch authority separate. The web role inserts
  immutable event content; a dedicated non-bypass dispatcher role alone may
  update lease/retry/poison/publication state after one tenant and workload
  identity are bound to the transaction.
- Include tenant isolation failure cases in every persistence or retrieval test suite.
- Never log raw access tokens or capability tokens.

## Persistence and Migrations

- Each service owns its schema and migration sequence.
- Migrations are forward-only in shared environments; rollback is a tested application/data procedure.
- Add indexes for bounded filters and ordering paths; verify plans using representative data.
- Use optimistic versions for mutable aggregates and append-only records for audits/effects.
- State changes and integration events share a transaction through the outbox pattern.
- Event payloads are validated as JSON objects and their exact UTF-8 bytes must
  match the stored SHA-256 digest before an outbox insert and again after an
  outbox lease is claimed. Store original bytes separately from `jsonb` because
  `jsonb` normalization is not byte preserving.
- Enforce per-aggregate event sequence in PostgreSQL under a transaction-level
  advisory lock; repository-only sequence checks are insufficient.
- Outbox acknowledgement and failure updates must match both event identity and
  the current lease token. Never acknowledge a stale claim.
- A poisoned predecessor blocks later aggregate events. Recovery requires an
  explicit reconciliation decision; do not silently skip the gap.

## Concurrency and External Effects

- Design handlers for duplicate delivery and out-of-order arrival.
- Use stable idempotency keys, inbox records, leases, and compare-and-set where applicable.
- Commit inbox claim, local side effect, and processed marker together. A
  committed `received` record is reclaimable; a `processed` or `poisoned`
  record is not.
- Never label a workflow exactly-once without target-side evidence.
- Reconcile ambiguous timeouts before retrying a write.
- Bind approval to the normalized action and target version.

## Logging and Telemetry

- Emit structured logs with trace and domain correlation identifiers.
- Redact secrets and classified evidence before logging.
- Avoid high-cardinality raw tenant or incident labels in metrics.
- Trace dependency calls, policy decisions, queue waits, and model exchanges without recording hidden reasoning or secret content.
- Every alert links to an owned runbook.

## Testing

- Add happy-path, boundary, denial, timeout, duplicate, cancellation, and recovery tests for new behavior.
- Use deterministic clocks, identifiers, and seeded scenario generators where reproducibility matters.
- Contract fixtures are not evidence that a live connector works.
- Do not weaken assertions, skip tests, or replace real behavior with mocks solely to satisfy a gate.
- Run the narrowest test first, then shared contract, lint, type, build, integration, and end-to-end gates according to blast radius.

## Security and Secrets

- Credentials enter only through approved runtime secret mechanisms.
- `.env.example` contains names and non-secret safe defaults only.
- Secret scans cover source, history, images, generated manifests, logs, and evidence bundles.
- Dependencies are pinned through lockfiles and reviewed for license and vulnerability policy.
- Generated code is reviewed and tested like handwritten code.

## Change Discipline

- Keep changes scoped to an accepted phase and its contracts.
- Preserve unrelated user changes in a dirty worktree.
- Use conventional commits without AI attribution.
- Update architecture, setup, security, deployment, contract, and runbook documentation in the same change that makes them true.
- A completed checkbox requires authoritative evidence, not intent.

## Verification Evidence

Later phases add language-specific lint, formatting, type, build, unit, contract, integration, security, and architecture-boundary checks. Phase 1 verifies storage scripts, document links, decision schema, and secret-free defaults. Phase 3 has local PostgreSQL and Keycloak reference evidence; the identity transcript is explicitly non-production and not immutable because its recorded revision is unborn/dirty and the configured CI job has not run remotely.

## Unresolved Questions

Exact formatter, linter, build-tool, test-runner, coverage, dependency-policy, and CI version pins are selected during Phase 2 without changing the architecture ownership defined here.
