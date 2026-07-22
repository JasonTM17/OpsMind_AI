---
date: 2026-07-22
scope: phase-07-deterministic-investigation-checkpoint
status: complete-with-concerns
---

# Phase 7 Deterministic Investigation Checkpoint Review

## Scope

- Platform API investigation domain, runner, fixture ports, feature-flagged routes and projection
- Investigation request/view JSON Schemas, fixtures and canonical OpenAPI operations
- Phase 7 deterministic validator and Platform API Maven suite
- Repository secret scanner consistency across working tree, Git index and Git history
- Focus: grounding, budget/deadline control, duplicate suppression, tenant lookup and fail-closed defaults

## Findings and disposition

| Finding | Severity | Disposition |
|---|---:|---|
| Terminal analysis transitions discarded consumed round/token accounting and the `AnalysisAccepted` event | High | Fixed; terminal state and event list preserve both accounting and the accepted response event |
| Dependency exceptions could escape while leaving a non-terminal run in the local store | High | Fixed; the runner persists a visible `FAILED` terminal state for AI Runtime and Tool Gateway dependency failures |
| A `need_more_evidence` response without tool intents could spin until round exhaustion | High | Fixed; reducer terminates `NO_PROGRESS` immediately |
| Deadline was checked only at API admission | High | Fixed for the in-process runner; deadline is part of reducer state and checked before each dependency call. Real client cancellation remains an exit blocker |
| Reusing a run UUID silently overwrote an existing in-memory run | High | Fixed with atomic create/replace semantics and a stable conflict response |
| Final analysis could cite an evidence UUID that was never accepted | Critical | Prevented by reducer invariant; such a response becomes visible `ABSTAINED` rather than `COMPLETED` |
| Fixture clients/store could be enabled without an explicit local profile | Medium | Fixed; all fixture adapters require the Spring `fixture` profile in addition to explicit flags |
| Reducer exceeded the 200-line modularization threshold | Medium | Split into a 102-line public state machine and 171-line transition module |
| Secret-scan exclusions were applied to the working tree but not the Git index snapshot or Git history | High | Fixed by reusing the declared pathspec policy across all scopes; added an excluded-tooling regression case while retaining product secret, binary and history canaries |

## Verification

- Platform API Maven: 131 tests, zero failures/errors, 12 PostgreSQL-environment skips.
- Phase 7 deterministic gate: two schemas, two fixtures, one local reference, 17 source files, `Errors=0`, `CheckpointResult=PASS`.
- Phase 4/OpenAPI validator: 20 schemas, 23 fixtures, 193 references, 10 operations, `Result=PASS`.
- Secret scanner regression suite: 14/14 passed. Full repository scan: 1,046 candidates, 3,023,422 history bytes, zero findings; dependency audit reported no known vulnerabilities (private local package not published to PyPI was explicitly skipped).
- Default runtime: investigation feature flag false; fixture adapters also require profile `fixture`.

## PhaseExit status

`PhaseExit=BLOCK` remains correct. Durable tenant-scoped run/event/timeline/audit
persistence, real capability-backed service clients, selected live non-production
connector proof, the CK/Stitch operator UI, browser E2E, cross-service traces and p95
benchmark evidence are not implemented.

## Unresolved questions

- Which durable transaction/outbox boundary owns investigation state plus incident timeline/audit events?
- Should the first real client execute synchronously under the HTTP request or enqueue the in-process runner before Temporal adoption?
