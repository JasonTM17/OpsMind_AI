# Phase 2: Investigation AI Adapter

## Goal

Compose the existing authorized analysis transport for iterative investigation
rounds without duplicating HTTP, capability, or error-taxonomy code.

## Files

- investigation AI port/adapter/configuration under `services/platform-api/**`
- analysis evidence prompt/resolution helpers where composition requires it
- focused tests under `services/platform-api/src/test/**`

## Implementation

1. Pass the initially authorized incident snapshot into the synchronous runner;
   keep the pure reducer unchanged.
2. For each round, open a short tenant/actor-bound read transaction and resolve
   the run's current evidence IDs through `EvidenceRecordReader`.
3. Build a bounded prompt from the authoritative incident snapshot, canonical
   evidence content, and safe catalog selectors. Treat all incident/evidence
   strings as data, not instructions.
4. Map evidence to exact references and approved data classifications. For this
   slice only `incident_summary` and `metric` are enabled.
5. Derive remaining token/tool budgets and deadline from reducer state; issue a
   fresh analysis capability for the exact canonical request digest.
6. Delegate transport to `AnalysisRuntimeClient`. Preserve its bounded body,
   strict content type, correlation, deadline, and Problem Details mapping.
7. Verify run/prompt version and allow the reducer to validate citations and
   state transitions. No provider-specific type enters investigation code.

## Tests

- Initial round, evidence round, catalog prompt, citation, abstain, budget, and
  deadline behavior.
- Foreign/missing evidence and digest failure stop before provider transport.
- AI capability/body digest mismatch and provider response drift fail closed.
- Existing fixture behavior and all analysis client tests remain green.

## Acceptance

- [x] The adapter reuses the existing analysis transport and signer boundary.
- [x] Every provider-visible evidence byte came from an authorized redacted
  Platform record or the authorized incident snapshot.
- [x] A response cannot cite or execute anything outside supplied references and
  catalog selectors.
- [x] No network call occurs after authorization/evidence/deadline failure.

## Checkpoint Evidence (2026-07-23)

- The synchronous runner carries the initially authorized incident snapshot and
  derives remaining round, token, and tool budgets from reducer state.
- Every round re-authorizes the verified principal and resolves exact evidence
  IDs inside one short transaction before prompt assembly or model I/O.
- Prompt tests prove untrusted strings stay serialized as data and public
  selector triples contain no PromQL, target, label, or executable argument.
- Capability tests bind the internal actor and exact canonical request digest;
  response checks reject unknown selectors, citation drift, nested foreign
  citations, prompt/run drift, and provider budget overrun. Long run deadlines
  are capped per round by the configured capability lifetime.
- Focused adapter/runner/service tests: 15 tests, 0 failures/errors.
- Platform full suite: 177 tests, 0 failures/errors, 20 environment-gated skips.
- Repository layout and Phase 7 static checkpoint validators pass. Phase exit
  remains blocked by the real Tool Gateway client, live connector, UI/E2E, and
  cross-service trace/p95 evidence.

## Rollback

Switch the investigation client selector back to the fixture profile.
