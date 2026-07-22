# ADR-0002: Contract and Repository Ownership

- Status: Accepted
- Date: 2026-07-19
- Decision owners: Architecture governance

## Context

Multi-language platforms often drift into duplicate OpenAPI files, hand-copied model classes, competing Compose manifests, and shared database migrations. That drift creates silent contract breaks and makes automated agents likely to build parallel systems.

## Decision

Use one canonical repository topology:

```text
apps/operator-web/
services/platform-api/
services/ai-runtime/
services/tool-gateway/
packages/contracts/openapi/
packages/contracts/json-schema/
packages/contracts/fixtures/
docs/
scripts/
compose.yaml
```

Rules:

1. `packages/contracts` is the sole source for public OpenAPI and JSON Schema.
2. Generated types/clients are outputs and cannot be edited manually.
3. Each service owns its database schema and migration directory.
4. Cross-service table writes and ownerless shared migrations are prohibited.
5. `compose.yaml` is the only root Compose manifest; variants use profiles or explicitly named environment overlays selected later.
6. Java classes use Java naming conventions, Python uses PEP 8, and TypeScript uses established React/TypeScript conventions.
7. Project docs and scripts use descriptive kebab-case filenames unless a tool requires a canonical name.
8. `README.md` and the documents indexed there are the canonical human entry points.
9. Simulator code remains visibly dev/test-only and cannot be imported into production runtime modules.
10. CI rejects duplicate contract roots, root Compose files, and forbidden module dependencies.

## Contract Workflow

1. Change canonical schema with compatibility intent.
2. Validate schema and examples.
3. Generate language bindings deterministically.
4. Run consumer and provider contract tests.
5. Detect breaking changes against the supported release.
6. Update API/security/deployment documentation when behavior changes.

Model output schemas are also canonical contracts. Provider-specific response structures terminate at the AI Runtime adapter.

## Consequences

Positive:

- One machine-readable truth across Java, Python, and TypeScript.
- Compatibility can be enforced before integration.
- Agents and contributors have deterministic file ownership.
- Migration and Compose ambiguity is removed.

Costs:

- Generation tooling must be reproducible and checked for drift.
- Contract changes require coordinated review.
- Some internal service DTOs remain separate from public schemas by design.

## Alternatives Considered

- Service-local copied contracts: rejected due to drift.
- Shared database schema with central migrations: rejected due to unclear ownership and coupling.
- Multiple root Compose files: rejected because local and CI behavior would diverge.
- Generic filename conventions across languages: rejected because native tooling and readability are better security controls than artificial uniformity.

## Verification

Phase 2 CI scans for canonical roots and generated drift. Phase 3 contract tests and migration ownership checks become release gates. Later phases extend existing contract trees rather than creating alternatives.

## Rollback or Supersession Triggers

A monorepo split, external API governance platform, or independently released SDK may require a new contract publication ADR. Until then, duplicate sources remain forbidden.

## Unresolved Questions

Exact build tools, schema generators, compatibility checker, package publication mechanism, and CI versions are selected in Phase 2.

