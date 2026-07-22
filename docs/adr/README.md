# Architecture Decision Records

Architecture Decision Records capture durable choices that constrain multiple phases or trust boundaries. They are not task logs.

## Status Values

- `Proposed`: under review and not authoritative.
- `Accepted`: current architecture rule.
- `Superseded`: replaced by a later ADR that names it.
- `Rejected`: evaluated but not selected.

## Required Sections

Every ADR includes status/date, context, decision, consequences, alternatives, verification, rollback/supersession triggers, and unresolved questions. An accepted ADR changes only through a new ADR; history is preserved.

## Index

| ADR | Decision | Status |
|---|---|---|
| [ADR-0001](./ADR-0001-platform-topology.md) | Initial deployables and infrastructure sequence | Accepted |
| [ADR-0002](./ADR-0002-contract-and-repository-ownership.md) | Canonical repository, contract, migration, and naming ownership | Accepted |
| [ADR-0003](./ADR-0003-evidence-artifact-storage.md) | Evidence artifact port and lifecycle contract | Accepted; G0.5 adapter and KMS baseline approved |

## Governance State

The G0.5 baseline is authoritative in the
[Product/Production Contract](../decisions/product-production-contract.json)
and its [approval record](../decisions/g0-5-approval-2026-07-19.md). Later ADRs
must still capture implementation-specific topology and any change to the
approved baseline; policy approval is not runtime conformance evidence.
