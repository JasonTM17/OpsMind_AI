---
phase: 14
title: "Student Model Training, Shadow, and Promotion"
status: pending
effort: "3-6 weeks after dataset/evaluation gates; 2 AI engineers plus platform/security review"
dependencies: [8, 13]
requirements: [TRAIN-01, TRAIN-02, EVAL-01, EVAL-02, EVAL-03, INV-08, INV-09, ADD-03, ADD-06, ADD-07]
---

# Phase 14: Student Model Training, Shadow, and Promotion

## Objective

Provide a reproducible, bounded-compute path for training an open-weight student model and a risk-based promotion process from offline evaluation to shadow and narrow canary. Cost or latency savings never compensate for regressions in evidence grounding, tenant safety, refusal behavior, or critical-incident accuracy.

The bounded TRAIN-01 smoke and model card are mandatory for A-to-Z completion. Full SFT scale, DPO, shadow, canary and promotion are a conditional parallel lane: they start only after a signed ROI/data/license/compute/residency gate, and `do not promote` is a valid outcome.

## Non-goals

- No workstation-scale foundation-model pretraining.
- No direct production promotion from a successful training job.
- No autonomous write/remediation authority for a student model.
- No DPO until SFT, dataset governance, held-out evaluation and preference-pair quality gates pass.
- No hard-coded dependence on one GPU vendor, model registry, or serving platform.

## Prerequisites and entry gate

- Phase 8 benchmark runner is reproducible and has an untouched held-out set.
- Phase 13 has a published immutable snapshot, dataset card, lineage and contamination report.
- Phase 11 policy/approval remains authoritative outside the model; model identity cannot grant tools.
- The smoke path needs only bounded approved compute and a legally compatible tiny model. Full candidate work requires a signed entry decision covering dataset sufficiency, license, remote compute/registry/residency, expected traffic savings and payback.
- Teacher baseline is pinned by model, prompt, parameters, tool set and schema versions.

## Design decisions and patterns

1. **Configuration-as-data:** training recipes name immutable dataset/model revisions, code revision, seed, hyperparameters and environment digest.
2. **Adapter-first training:** start with LoRA/QLoRA where licensing/hardware permit; preserve the base model separately and publish adapter plus merge recipe.
3. **Pipeline stage gates:** `smoke -> full SFT -> offline candidate -> shadow -> canary -> promoted`. Every transition is explicit, signed and reversible.
4. **Champion/challenger routing:** the teacher remains champion until the student passes task- and severity-specific non-inferiority gates.
5. **Independent safety policy:** Tool Gateway validation, authorization and approval do not trust the selected model.
6. **Traffic mirroring with data controls:** shadow requests reuse only approved redacted inputs; student output is never shown or executed.
7. **Immutable registry aliases:** deployments resolve a version digest; human-friendly aliases are pointers with audit history, not mutable artifacts.

## Planned file inventory

| Operation | Path | Purpose |
|---|---|---|
| CREATE | `ml/training/configs/sft-smoke.yaml` | Small deterministic CPU/bounded-GPU smoke recipe |
| CREATE | `ml/training/configs/qlora-reference.yaml` | Parameterized accelerator recipe, disabled by default |
| CREATE | `ml/training/configs/dpo-reference.yaml` | Preference recipe gated behind accepted SFT |
| CREATE | `ml/training/src/opsmind_training/` | Load, validate, train, checkpoint and export pipeline |
| CREATE | `ml/training/tests/` | Config, resume, artifact and dataset-contract tests |
| CREATE | `ml/evaluation/promotion-policy.yaml` | Severity/task thresholds and blocking safety metrics |
| CREATE | `services/ai-runtime/app/providers/student_provider.py` | Provider adapter with the same normalized contract |
| CREATE | `services/ai-runtime/app/routing/champion_challenger.py` | Shadow/canary selection independent of tool authority |
| CREATE | `services/ai-runtime/tests/routing/` | Deterministic assignment, fallback and kill-switch tests |
| CREATE | `services/platform-api/src/main/java/ai/opsmind/platform/modelgovernance/` | Candidate, evaluation, lineage, promotion and rollback records |
| CREATE | `apps/web/src/features/model-governance/` | Candidate comparison and audited promotion UI |
| CREATE | `docs/model-lifecycle.md` | Training, evaluation, promotion, rollback and incident process |
| CREATE | `docs/templates/model-card.md` | Required model-card and risk template |
| CREATE | `scripts/training/run-smoke.ps1` | Disk-checked bounded Windows smoke command |
| CREATE | `scripts/training/run-smoke.sh` | Bounded Linux/macOS smoke command |
| MODIFY | `docs/system-architecture.md` | Add offline/online ML trust boundaries |
| MODIFY | `.gitignore` | Exclude checkpoints, caches, datasets and generated weights |

## Implementation tasks

### 14.1 Define training and artifact contracts

- Validate every recipe against a typed schema and resolve all inputs to immutable digests before a run starts.
- Capture code commit, container/environment digest, random seed, tokenizer, base license, dataset snapshot, hardware summary, duration, energy/compute estimate and cost.
- Store checkpoints and final artifacts outside Git; publish checksums, signatures, model card and evaluation references.
- Refuse a run when dataset policy, base-model license, disk preflight, quota, or held-out isolation cannot be proven.
- Resolve snapshot authorization at run start and checkpoint/export; a revoked lineage ID invalidates the run even if an old immutable artifact URL still exists.

### 14.2 Implement bounded smoke training

- Use a tiny approved fixture/snapshot slice and a small legally compatible model to prove tokenization, loss, checkpoint, resume and export paths.
- Cap steps, memory, runtime, workers and artifact bytes. Cancellation must save only a valid resumable checkpoint or cleanly mark the run failed.
- Test CPU path for pipeline correctness; treat performance numbers from smoke as non-representative.
- Make QLoRA configuration optional and run expensive jobs on approved remote compute, with caches rooted outside C:.

### 14.3 Add SFT quality gates

- Evaluate schema validity, task classification, RCA/evidence metrics, hallucination, abstention, calibration, safety, latency, tokens and cost on the frozen benchmark.
- Report metrics by scenario family, severity, tenant-language slice and failure class; averages cannot hide SEV1/SEV2 regression.
- Require statistical confidence intervals and explicit non-inferiority margins before candidate status.
- Archive raw run references and environment digests so the report can be reproduced.

### 14.4 Add preference data and optional DPO

- Build preference pairs only from adjudicated comparisons with rationale/evidence references and no test-set examples.
- Measure annotator agreement and position/order bias; discard ambiguous or policy-conflicting pairs.
- Gate DPO on an accepted SFT candidate and a minimum quality/size threshold recorded in policy.
- Re-run the full safety and grounding suite; preference optimization cannot waive any blocking metric.

### 14.5 Implement shadow mode

- Deterministically sample eligible redacted requests by tenant opt-in, task class and stable hash.
- Run student asynchronously under separate concurrency/cost budgets; never delay or change champion response.
- Prevent shadow output from tool execution, approval requests, user display, audit conclusions or dataset auto-acceptance.
- Compare normalized outputs and attach both to an evaluation record without exposing hidden reasoning.
- Skip this section entirely—with an explicit gate record—when the full-candidate entry decision does not demonstrate ROI or permitted compute/data.

### 14.6 Canary and promotion

- Permit canary only for low-risk read-only suggestion traffic; exclude SEV1/SEV2 and write/remediation paths initially.
- Require a promotion record naming exact artifact digest, routing percentage, metrics, reviewer roles, expiry and rollback target.
- Auto-disable on safety/schema/error/latency budget breach; operators also receive an immediate model kill switch.
- Expand traffic only after a fixed observation window and minimum sample count; never graduate solely on cost.
- Consume dataset-lineage invalidation events. Quarantine affected candidates/deployments immediately and route to champion until retrained or a named risk owner grants a time-bounded exception.

## Verification and evidence matrix

| Check | Method | Passing evidence |
|---|---|---|
| Reproducible smoke | Two runs from clean environment with fixed seed | Same resolved inputs and acceptably stable metrics/artifact manifest |
| Resume/cancel | Inject termination at checkpoint boundaries | Valid resume without duplicated steps or corrupt registry state |
| Data isolation | Attempt held-out/withdrawn/foreign-tenant snapshot use | Run rejected before compute allocation |
| Lineage revocation | Withdraw data during training, after registry publish and during canary | Run/model quarantined; routing returns to champion; stale artifact cannot reload |
| Artifact integrity | Tamper weight, tokenizer and manifest | Load/deploy fails closed on digest/signature mismatch |
| Critical quality | Compare per-severity teacher/student metrics | No policy-defined regression on SEV1/SEV2, grounding or safety |
| Schema/safety | Full structured-output and adversarial suites | At least 99% schema validity and 100% critical safety cases pass |
| Shadow isolation | Instrument model/tool/UI paths | Shadow outputs cannot execute, approve or display |
| Routing | Seeded assignment, outage and kill-switch tests | Stable cohort; instant champion fallback; no sticky unsafe route |
| Cost/latency | Load run with raw trace references | Candidate meets declared improvement and resource budget |
| Rollback | Promote test alias then trigger breach | Previous champion restored within defined operational objective |

## Exit gate

- Bounded training smoke completes without placing weights/caches on C:, and produces a populated model card plus signed artifact manifest.
- At least one candidate is reproducibly evaluated against the frozen teacher baseline; held-out contamination check is clean.
- Student cannot enter shadow unless blocking schema, evidence, hallucination and safety thresholds pass.
- Shadow isolation and kill-switch tests pass; no student output reaches users, tools or approvals during shadow.
- Full shadow/canary may be recorded `not entered` when ROI/data/license/compute gate fails; this does not block the mandatory bounded smoke or Phase 15 hardening.
- Production promotion remains pending until minimum sample size, canary policy and business risk owner are approved.

## Rollback and recovery

- Route 100% to the pinned champion and disable the student provider independently of application deployment.
- Change registry alias only through an audited compare-and-swap operation; retain the last-known-good digest.
- Quarantine corrupted or policy-withdrawn artifacts and invalidate their routing eligibility.
- Affected lineage never falls back to an older model trained on the same withdrawn records; choose a clean champion or disable the student route.
- Resume failed jobs from verified checkpoints; never reuse partial metrics as promotion evidence.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Benchmark overfitting | Frozen hidden split, family isolation, repeated fresh scenarios |
| Cheap model appears good on averages | Severity/task slices and blocking critical metrics |
| License or provenance violation | Pre-run policy gate and model/dataset cards |
| Shadow leaks sensitive inputs | Tenant opt-in, redaction, scoped storage and retention |
| GPU/cost runaway | Hard quotas, bounded recipes, remote jobs and cancellation |
| Model promotion bypasses tool safety | Model-independent Tool Gateway and approval enforcement |

## Unresolved decisions

- Approved base model/license and target languages/task classes.
- Remote compute and model registry providers, GPU budget and artifact residency.
- Exact non-inferiority margins, shadow duration, sample count and canary ceiling per severity.
