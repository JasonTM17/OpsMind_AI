---
phase: 1
title: Contract and threat boundary
status: completed
priority: P1
dependencies: []
---

# Phase 1: Contract and threat boundary

## Context Links

- [Plan](./plan.md)
- [Parent Phase 4](../260719-1747-opsmind-ai-production-platform/phase-04-incident-control-plane-and-audit-ledger.md)

## Requirements

- Add the nested PATCH operation and merge-patch media type to canonical OpenAPI.
- Define a closed JSON Schema that distinguishes absent `ownerId` from null.
- Keep status, resolution, IDs, actor fields, time, and version out of input.
- Bind normalized body, actor, method/path, and expected version in the request digest.

## Files

- Modify: `packages/contracts/openapi/opsmind-v1.yaml`
- Create: `packages/contracts/json-schema/incidents/patch-incident-request.schema.json`
- Create/modify: `packages/contracts/fixtures/incidents/**`
- Modify: `scripts/validation/phase-04-incident-contracts/**`
- Create: `services/platform-api/src/main/java/ai/opsmind/platform/incident/PatchIncidentRequest.java`
- Create/modify: focused JSON/validator tests

## Success Criteria

- [x] Positive update, assign, and clear fixtures validate.
- [x] Empty/unknown/authority/invalid-owner fixtures fail.
- [x] Contract validator assigns every new fixture explicitly.
- [x] Focused deserialization proves absent-versus-null owner semantics.

## Risks

- A normal Java nullable field cannot distinguish absent from explicit null.
  Use an explicit presence representation and avoid coercion.
