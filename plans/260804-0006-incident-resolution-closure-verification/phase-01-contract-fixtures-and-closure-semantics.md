---
phase: 1
title: Contract fixtures and closure semantics
status: completed
priority: P1
dependencies: []
---

# Phase 1: Contract fixtures and closure semantics

## Overview

Close the contract evidence gap without changing the public route or state model.

## Context Links

- [Plan](./plan.md)
- [Parent Phase 4](../260719-1747-opsmind-ai-production-platform/phase-04-incident-control-plane-and-audit-ledger.md)

## Requirements

- Add positive CLOSED transition, CLOSED incident, and closure timeline fixtures.
- CLOSED transition body contains only `targetStatus` and bounded `reason`.
- CLOSED aggregate and timeline events retain non-empty root cause and resolution summary.
- Tighten timeline schema so `toStatus` RESOLVED or CLOSED requires both fields.
- Keep all fixture files explicitly registered by the validator.

## Related Code Files

- Modify: `packages/contracts/json-schema/incidents/incident-timeline-event.schema.json`
- Create: `packages/contracts/fixtures/incidents/transition-incident-request.closed.valid.json`
- Create: `packages/contracts/fixtures/incidents/incident.closed.valid.json`
- Create: `packages/contracts/fixtures/incidents/incident-timeline-page.closed.valid.json`
- Modify: `scripts/validation/phase-04-incident-contracts/fixture-contract-validator.mjs`

## Implementation Steps

1. Add deterministic fixtures using existing synthetic tenant/project/actor IDs.
2. Model a legal lifecycle with monotonic versions and immutable resolution values.
3. Extend the schema conditional from RESOLVED-only to RESOLVED-or-CLOSED.
4. Register every new fixture as a positive case.
5. Run `node scripts/validation/validate-phase-04-incident-contracts.mjs`.

## Success Criteria

- [ ] Closure fixtures validate and no incident fixture is unassigned.
- [ ] CLOSED event cannot validate with null/blank resolution fields.
- [ ] Existing OPEN/INVESTIGATING fixtures remain valid.
- [ ] Static validator passes with zero errors.

## Risks

- Schema drift from runtime/database invariant. Mitigate with exact CLOSED fixture and negative assertions in Phase 2.
