---
phase: 8
title: "Simulator and Evaluation Baseline"
status: pending
priority: P1
dependencies: [5, 6, 7]
effort: "3-4 weeks"
---

# Phase 8: Simulator and Evaluation Baseline

## Objective

Build a deterministic, versioned incident simulator and benchmark harness that can replay the first investigation slice, emit machine-readable ground truth, and score structured-output validity, evidence quality, tool use, latency, and cost before broader product expansion.

Separate deterministic smoke coverage from statistical product evidence: preregister what “verified RCA” means, create an independently held-out corpus and human comparator protocol, and report confidence intervals instead of treating three or ten authored scenarios as a percentage denominator.

## Non-Goals

- No claim that the first three deterministic scenarios establish production accuracy, p95 latency, calibration or human benefit. Phase 16 owns release-scale evidence and at least ten fully working scenario families.
- No student-model training or promotion. Phase 14 owns that after benchmark stability.
- No production credentials or live customer telemetry inside simulator fixtures.
- No requirement to add MLflow or external experiment tooling if simple versioned artifacts satisfy the baseline gate.

## Source Anchors

- `plans/260719-1747-opsmind-ai-production-platform/research/master-prompt-requirements-traceability.md:54-62`
- `plans/260719-1747-opsmind-ai-production-platform/research/master-prompt-requirements-traceability.md:116-119`
- `plans/260719-1747-opsmind-ai-production-platform/research/master-prompt-requirements-traceability.md:146-150`
- `plans/260719-1747-opsmind-ai-production-platform/research/researcher-02-delivery-evaluation.md:12-16`
- `plans/260719-1747-opsmind-ai-production-platform/research/researcher-02-delivery-evaluation.md:33-42`
- `plans/260719-1747-opsmind-ai-production-platform/research/researcher-02-delivery-evaluation.md:168-201`
- `plans/260719-1747-opsmind-ai-production-platform/research/researcher-02-delivery-evaluation.md:243-267`
- `plans/260719-1747-opsmind-ai-production-platform/reports/brainstorm-report.md:14`
- `plans/260719-1747-opsmind-ai-production-platform/reports/brainstorm-report.md:199-216`
- `plans/260719-1747-opsmind-ai-production-platform/reports/brainstorm-report.md:222-228`

## Prerequisites and Dependency Graph

- Hard blockers:
  - Phase 5 must emit reproducible model invocation metadata and stable structured responses.
  - Phase 6 must provide fixture-capable read-only connector contracts.
  - Phase 7 must define the first end-to-end investigation slice to benchmark.
- Storage and environment blockers:
  - Simulator artifacts, caches, and reports must land on `D:` or another approved non-OS volume. No large generated artifacts or model caches may be written to `C:` while local free space remains below the documented floor.
- Downstream:
  - Phase 9 and later phases use this harness to prevent regression when durable workflow, RAG, approvals, and student-model features land.

## Data Flow

1. Simulator resets a named scenario to a clean starting state.
2. Scenario seed generates incident metadata, synthetic observability/deployment/source/runbook evidence, and machine-readable ground truth.
3. The benchmark runner invokes the same Phase 7 investigation path used by operators.
4. System outputs run artifacts: timeline, tool calls, evidence, RCA, confidence, usage, latency, and cost.
5. Evaluator compares actual artifacts against scenario ground truth and emits:
   - structured-output validity
   - citation correctness / evidence precision
   - tool-selection accuracy
   - hallucinated-root-cause rate
   - safety/refusal outcomes
   - latency and cost summaries
6. Runner writes versioned raw results plus a summarized benchmark report that can be diffed across app, prompt, and model revisions.

## Architecture and Design Decisions

- Production-path benchmarking: call the real Phase 7 slice, not a special benchmark-only path, so results reflect the actual operator experience.
- Ground truth is machine-readable and versioned per scenario. Human-readable markdown is secondary.
- Scenario determinism first:
  - scenario seed
  - fixed clock or bounded timestamp offsets where possible
  - fixture-backed connector mode
  - stable input manifests
- Baseline before scale: start with a small scenario set that covers:
  - successful RCA with supporting evidence
  - insufficient evidence leading to abstain
  - conflicting evidence requiring counter-evidence handling
- Metrics are release gates, not dashboard decoration. A run without persisted raw references does not count as evidence.
- Smoke and benchmark sets are distinct: deterministic scenarios test mechanics; independently reviewed held-out cases measure quality. Provider reruns of one scenario are correlated trials, not new incidents.
- Human baseline is preregistered: incident start, verified-RCA timestamp, permitted tools, adjudication, severity/family strata, correction/override and abstention usefulness.
- Contamination control:
  - keep benchmark scenario corpus separate from future dataset generation exports
  - tag every scenario and report with schema/prompt/model versions
  - do not treat teacher output as ground truth

## File Inventory

### CREATE

- `services/incident-simulator/README.md`
- `services/incident-simulator/pyproject.toml`
- `services/incident-simulator/app/main.py`
- `services/incident-simulator/app/scenario_registry.py`
- `services/incident-simulator/app/reset_service.py`
- `services/incident-simulator/app/ground_truth_models.py`
- `services/incident-simulator/app/fixture_emitters/` - emits synthetic observability, deployment, source, and runbook evidence.
- `services/incident-simulator/scenarios/deployment-latency-regression/*`
- `services/incident-simulator/scenarios/insufficient-evidence-abstain/*`
- `services/incident-simulator/scenarios/conflicting-evidence-regression/*`
- `evaluation/benchmark-manifest.yaml`
- `evaluation/human-baseline-protocol.md`
- `evaluation/statistical-analysis-plan.md`
- `evaluation/held-out/manifest.yaml` - references restricted cases without exposing payloads in Git.
- `evaluation/schemas/scenario-ground-truth.schema.json`
- `evaluation/schemas/benchmark-result.schema.json`
- `evaluation/runner/run_benchmark.py`
- `evaluation/runner/score_structured_output.py`
- `evaluation/runner/score_evidence_quality.py`
- `evaluation/runner/score_tool_selection.py`
- `evaluation/runner/score_safety_latency_cost.py`
- `evaluation/reports/.gitkeep`
- `tests/e2e/simulator-and-eval/*`
- `infra/compose/fragments/incident-simulator.compose.yaml`

### MODIFY

- None required if Phase 7 exposes a stable investigation entrypoint and fixture mode.

### DELETE

- None.

## Implementation Tasks

1. Define scenario and result schemas.
   - Scenario schema must capture scenario ID, seed, incident metadata, synthetic source systems, expected evidence IDs, acceptable RCA labels, acceptable abstain state, allowed tool families, and budgets.
   - Result schema must capture raw run references, metrics, verdict, and versions of app, prompt, schema, and model.
2. Build the simulator reset/apply path.
   - Reset command wipes prior synthetic state for the named scenario only.
   - Apply command loads deterministic fixtures into the evidence backends used by the Tool Gateway fixture adapters.
   - No simulator command may require production credentials.
3. Build the benchmark runner against the real Phase 7 slice.
   - Trigger investigation run.
   - Wait for terminal state.
   - Collect timeline, tool-call, evidence, and RCA artifacts.
   - Persist raw references before scoring.
4. Implement the baseline scoring dimensions.
   - Structured output validity.
   - Citation/evidence correctness.
   - Tool-selection accuracy.
   - Hallucinated root-cause rate.
   - Safety/refusal fidelity.
   - Latency and cost accounting.
5. Seed the first canonical scenario set.
   - Scenario A: deployment latency regression with clear supporting evidence.
   - Scenario B: insufficient evidence requiring abstain.
   - Scenario C: conflicting evidence that forces counter-evidence and a cautious final answer.
   - Specify and reserve identifiers/ground truth for at least ten final scenario families (`SIM-02`), but keep unimplemented families visibly pending until Phase 16.
6. Add contamination and artifact guardrails.
   - Benchmark corpus is stored separately from any future training export path.
   - Reports include scenario version and source fixture digest.
   - Artifact directory defaults to `D:`-backed workspace paths, not `C:`.
7. Add deterministic rerun verification.
   - Same scenario seed and app/prompt/model versions must reproduce the same ground-truth comparison logic and compatible verdict shape.
   - Accept that provider nondeterminism may shift wording; score semantic fields and evidence linkage, not exact prose.
8. Preregister release-quality measurement.
   - Define verified RCA, severity/family slices, independent adjudication, sampling unit and train/dev/test separation.
   - Determine sample size from target effect/confidence/power; do not infer 99%, p95 or calibration from three/ten correlated scenarios.
   - Collect a bounded pilot from qualified on-call/incident-command reviewers to validate timing and rubric before freezing thresholds.
9. Establish an independently held-out corpus.
   - Use separately authored/reviewed synthetic variants and only policy-approved de-identified shadow incidents.
   - Keep labels hidden from model/prompt/dataset generation and record contamination checks by family/lineage hash.

## Migration, Backward Compatibility, and Rollback

- Backward compatibility strategy:
  - Simulator and benchmark outputs are additive and isolated from production incident tables except through normal investigation APIs.
  - Scenario schema and result schema are versioned so later phases can add metrics without invalidating prior artifacts.
- Migration path:
  - Introduce simulator service and evaluation runner behind test-only or internal-only commands.
  - Store artifacts outside the OS drive and outside Git-tracked large-binary paths unless they are intentionally tiny fixtures.
  - Start with fixture-only integrations before any optional live staging evaluation.
- Rollback:
  - Disable simulator compose fragment and benchmark commands without affecting product runtime.
  - Keep old result artifacts for trend analysis even if the runner is reverted.
  - If a scoring rule is wrong, version a new scorer instead of mutating historical verdicts in place.

## Test and Evidence Matrix

| Scope | Coverage | Evidence artifact |
|---|---|---|
| Unit | scenario schema validation, scorer math, contamination tags, artifact-path guardrails | unit CI report |
| Integration | scenario reset/apply, fixture emission, benchmark runner to Phase 7 path | integration report |
| E2E | each baseline scenario runs to terminal state and produces a versioned report | per-scenario benchmark report |
| Determinism | repeated run of same scenario/seed produces stable verdict shape | rerun diff report |
| Statistical validity | preregistered unit/sample/power/CI and independent held-out manifest | statistical analysis artifact |
| Human baseline | qualified-reviewer pilot with adjudication, time-to-verified-RCA and correction/abstention data | redacted baseline report |
| Safety | abstain and denial scenarios do not accidentally produce unsupported RCA or write action | safety regression report |
| Resource policy | artifact path and disk preflight reject unsafe local storage targets | preflight test report |

## Quantitative Exit Gate

- `SIM-01`, `EVAL-01`, `EVAL-02`, and `EVAL-03` have automated evidence attached.
- At least `3` canonical scenarios run end-to-end and produce machine-readable ground truth plus benchmark summaries.
- Structured-output validity is `>= 99%` across at least 100 complete provider invocations spanning held-out cases; confidence interval and correlated-repeat caveats are reported.
- Every scored run includes raw artifact references for timeline, evidence, tool calls, and final RCA.
- Deterministic rerun of the same scenario/seed succeeds with no schema drift in result artifacts.
- Benchmark runner reports latency and cost per scenario, not just aggregate averages.
- `SIM-02` ownership is explicit: at least ten final families are specified and isolated; Phase 8 closes after three deterministic smoke families are stable, while Phase 16 requires all ten implemented and release-quality sample sizing satisfied.
- Human-baseline protocol, pilot and statistical plan are reviewed before Phase 9 freezes no-progress/budget thresholds.

## Risks and Mitigations

| Risk | Likelihood x Impact | Mitigation |
|---|---|---|
| Benchmark set is too synthetic and misses real failure modes | Medium x High | use slice scenarios grounded in the real product flow and expand corpus before release |
| Provider nondeterminism causes flaky verdicts | Medium x Medium | score structured fields and evidence linkage, pin versions, use fixture mode where possible |
| Benchmark contamination later pollutes training data | Medium x High | separate corpus paths, explicit tags, review gate before export |
| Artifact sprawl fills the wrong disk | High x Medium | `D:`-first artifact roots, preflight path guard, no large local downloads on `C:` |
| Teams treat baseline metrics as final release gates | Medium x Medium | mark this as baseline only; final ten-scenario gate stays for release phases |

## Unresolved Decisions

- Whether MLflow or another external experiment store is required in the baseline, or whether versioned local artifacts are sufficient until production graduation.
- Resolved minimum: three deterministic families plus reviewed human/statistical pilot before Phase 9; all ten implemented plus powered held-out corpus before Phase 16 release claim.
- Whether optional live staging evaluation is required before Phase 8 completion, or whether fixture-backed determinism is the only hard gate here.
