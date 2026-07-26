# Phase 8B Evaluation Verification

### Scope
- Read-only validation of the Phase 8B production-path evaluation changes.
- Changed surfaces checked: evaluation runner/projection code, V008 migration and contract test, CI workflow wiring, and Phase 7/8 validators.

### Test Results Overview
- `node --test evaluation/runner/*.test.mjs`: pass, 30/30 after adding direct
  projector-to-scorer integration and tamper tests.
- `node --test --experimental-test-coverage evaluation/runner/*.test.mjs`: pass, 30/30.
- `node scripts/validation/validate-phase-08-evaluation-foundation.mjs`: pass, checkpoint PASS, PhaseExit BLOCK by design.
- `node scripts/validation/validate-repository-layout.mjs`: pass.
- `node scripts/validation/validate-phase-07-investigation-slice.mjs`: checkpoint PASS, PhaseExit BLOCK because cross-service report is not bound to the clean current revision.
- PowerShell parse for `scripts/dev/opsmind.ps1`: pass.
- `node --check` on the touched JS modules: pass.
- Python compile check for `scripts/validation/cross-service/fixture-provider.py`: pass.
- `actionlint` on `.github/workflows/*.yml`: pass.
- `git diff --check`: pass.

### Coverage Metrics
- Overall coverage from the coverage-enabled test run: line 90.72%, branch 75.89%, function 98.30%.
- Strong coverage:
  - `evaluation/runner/cross-service-evaluation-projector.mjs`: 100% line, 100% branch, 100% funcs.
  - `evaluation/runner/score-phase-07-projection.mjs`: 100% line, 88.46% branch, 100% funcs.
  - `evaluation/runner/raw-analysis-contract.mjs`: 98.69% line, 85.71% branch, 100% funcs.
  - `evaluation/runner/cross-service-evaluation-projection.mjs`: 88.57% line, 66.67% branch, 100% funcs.
- Coverage gaps still present:
  - `evaluation/runner/cross-service-evaluation-projection-verifier.mjs` now
    reaches 100% line/function coverage; branch mutation depth remains 56.25%.
  - `evaluation/runner/project-cross-service-evaluation-export.mjs` still has uncovered CLI/safety branches.
  - `evaluation/runner/cross-service-evaluation-export-contract.mjs`, `evaluation/runner/evaluation-value-safety.mjs`, and `evaluation/runner/score-phase-07-trace-core.mjs` still have uncovered error branches.

### Findings
#### Pass
- A/B/C scenario coverage exists and is explicit in [score-phase-07-trace.test.mjs](D:/OpsMind_AI/evaluation/runner/score-phase-07-trace.test.mjs#L80).
- Export trust-boundary coverage exists for duplicate JSON keys, unsafe strings, prohibited nested keys, foreign scope, event gaps, ambiguous bindings, connector substitution, receipt drift, and trace enrichment in [cross-service-evaluation-projection.test.mjs](D:/OpsMind_AI/evaluation/runner/cross-service-evaluation-projection.test.mjs#L38).
- V008 contract coverage exists in [MigrationContractTest.java](D:/OpsMind_AI/services/platform-api/src/test/java/ai/opsmind/platform/persistence/MigrationContractTest.java#L151) and the new accepted-response serialization test [InvestigationAcceptedAnalysisEventSerializationTest.java](D:/OpsMind_AI/services/platform-api/src/test/java/ai/opsmind/platform/investigation/application/InvestigationAcceptedAnalysisEventSerializationTest.java#L1).
- CI wiring for exact executable inputs, source/executable attestation, A/B/C execution, Phase 7 preservation, and cleanup is present in [cross-service-evaluation.yml](D:/OpsMind_AI/.github/workflows/cross-service-evaluation.yml#L58).
- PR-quality workflow still runs the Phase 8A foundation gate, repository layout check, and actionlint in [pr-quality.yml](D:/OpsMind_AI/.github/workflows/pr-quality.yml#L80).

#### Blocker
- Local Phase 7 validation does not fully close because the cross-service report is not bound to a clean current revision. That is enforced by the validator and matches the CI attestation model, but it means local evidence is not revision-clean in this workspace.

### CI Wiring / Attestation
- The cross-service workflow records `GitHead`, `GitTree`, executable digests, and then runs `git diff --quiet` before scoring. That is the correct exact-revision attestation pattern for this phase.
- The workflow also runs the fresh Scenario A/B/C matrix and preserves the Phase 7 production-path contract after the Phase 8B scoring step.
- The PR-quality workflow still gates repository layout, actionlint, and the Phase 8 deterministic contract path, so the new work sits inside existing repo-wide checks.

### Not Run Locally
- Heavy paths intentionally not run per task constraints and disk floor: Docker build/start, Maven package/verify, pnpm install/build/test, uv sync/test, and full GitHub Actions execution.
- These remain CI-required evidence, not local failures.

### Recommendations
1. Add targeted tests for the uncovered CLI/output-path branches in `project-cross-service-evaluation-export.mjs`.
2. Add branch-focused mutations for the projection verifier,
   `evaluation-value-safety.mjs`, and
   `cross-service-evaluation-export-contract.mjs`.
3. Keep the exact-revision attestation in CI mandatory; the local dirty-tree blocker is acceptable only if CI proves the clean-source path on the pushed commit.

### Conclusion
- Phase 8B evaluation implementation is locally healthy on the scoped checks that were allowed here.
- The main remaining risk is coverage depth in the lower-level export/safety helpers and the fact that production-path attestation must still be proven in CI, not from this dirty local workspace.

Status: DONE_WITH_CONCERNS

Summary: Lightweight Phase 8B tests and trust-boundary checks pass; integration attestation remains CI-owned.

Concerns/Blockers: Fresh Docker/PostgreSQL A/B/C and exact-revision artifacts were not locally executed because the storage floor blocks heavy work.
