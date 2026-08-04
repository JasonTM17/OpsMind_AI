---
phase: 2
title: Transactional command and persistence
status: in-progress
priority: P1
dependencies: [1]
---

# Phase 2: Transactional command and persistence

## Context Links

- [Plan](./plan.md)
- [Phase 1](./phase-01-contract-and-threat-boundary.md)

## Requirements

- Add PATCH controller binding with mandatory idempotency and strong ETag.
- Authorize actor and owner membership inside the existing transaction.
- Update exactly one version and append one linked metadata-change event.
- Return canonical incident response including owner identity.

## Files

- Modify: `services/platform-api/src/main/java/ai/opsmind/platform/incident/**`
- Modify: `services/platform-api/src/test/java/ai/opsmind/platform/incident/**`
- Modify only if required: `services/platform-api/src/main/resources/db/migration/**`

## Success Criteria

- [ ] Update/assign/clear happy paths return the next ETag.
- [ ] Replay is byte/ETag/operation-ID identical with no new effects.
- [ ] Owner authority and optimistic-concurrency failures are side-effect free.
- [ ] Timeline, audit, and outbox share operation/event identity and version.
- [ ] Existing incident behavior and tests remain green.

## Risks

- Membership validation separated from mutation can race revocation. Keep both
  under the transaction and let the existing membership FK reject stale owners.
