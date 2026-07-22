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

- [ ] The adapter reuses the existing analysis transport and signer boundary.
- [ ] Every provider-visible evidence byte came from an authorized redacted
  Platform record or the authorized incident snapshot.
- [ ] A response cannot cite or execute anything outside supplied references and
  catalog selectors.
- [ ] No network call occurs after authorization/evidence/deadline failure.

## Rollback

Switch the investigation client selector back to the fixture profile.
