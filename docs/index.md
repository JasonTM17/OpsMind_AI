# OpsMind AI Documentation

This is the canonical reading path for the repository at `main`. The product
is an evidence-first AI SRE/DevSecOps platform; documentation distinguishes
implemented behavior, verified evidence, planned work, and release blockers.

## Start here

| Need | Read |
|---|---|
| Understand the product and its boundaries | [Product PDR](./project-overview-pdr.md) |
| Run the repository locally | [Local Development](./local-development.md) |
| Understand components and trust boundaries | [System Architecture](./system-architecture.md) |
| Verify tests and release evidence | [Testing Strategy](./testing-strategy.md) |
| Deploy or roll back | [Deployment Guide](./deployment-guide.md) |
| See current delivery status | [Project Roadmap](./project-roadmap.md) and [Progress](./progress.md) |
| See what still blocks production | [Blockers](./blockers.md) |

## Documentation map

- [Codebase Summary](./codebase-summary.md) — verified modules, entry points,
  migrations, runtime boundaries, and explicit non-claims.
- [Code Standards](./code-standards.md) — ownership, contracts, errors,
  migrations, testing, security, and change discipline.
- [Security Model](./security-model.md) — assets, identities, delegated
  capabilities, tenant isolation, provider egress, and response.
- [Evaluation Strategy](./evaluation-strategy.md) — deterministic checks,
  held-out evaluation, human adjudication, safety, and promotion decisions.
- [Dataset Governance](./dataset-governance.md) — provenance, consent,
  redaction, retention, deletion, lineage, and model withdrawal.
- [Design Guidelines](./design-guidelines.md) — operator workspace language,
  evidence spine, states, responsive behavior, and accessibility.
- [Architecture Decisions](./adr/ADR-0001-platform-topology.md) — accepted
  topology and deployment assumptions.

## Evidence and status rules

1. A green static validator is not production proof by itself.
2. A local run is evidence for the current checkout, not an immutable release
   claim; revision-bound CI artifacts are authoritative for merged code.
3. A plan phase is complete only when its acceptance evidence exists and its
   blockers are resolved or explicitly carried forward.
4. Production claims remain false until the active blockers are closed with
   the evidence named in [Blockers](./blockers.md).

## Current repository state

The current `main` head is merge `0788ee3` (`feat: harden artifact lifecycle
runtime capability`). V018/V019 artifact lifecycle runtime capability and
run-bound authorized-read probing have static, focused, and disposable
PostgreSQL contract evidence. Object-store ingress/read, scanning,
retention/deletion, restore, production KMS/backend support, and several
provider, connector, evaluation, and Temporal production gates remain open.

For the shortest verified command surface, use the root [README](../README.md)
and [Local Development](./local-development.md). Do not treat generated media,
working-tree changes, or Docker publication as production deployment evidence.
