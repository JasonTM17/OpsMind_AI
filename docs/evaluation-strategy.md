# AI and Operator Evaluation Strategy

## Objective

Evaluation determines whether OpsMind improves incident work without increasing unsupported claims, unsafe actions, data exposure, or operational cost beyond approved limits. It is a release control, not a product demo score.

## Evaluation Units

An evaluation case contains:

- incident family and scenario version;
- authorized evidence graph and ground-truth source references;
- tenant/policy/data-class configuration;
- expected facts, plausible hypotheses, contradictions, and acceptable abstentions;
- prohibited claims and prohibited actions;
- optional remediation preview and target-state transitions;
- workload budgets and dependency fault schedule;
- evaluator version and human-review rubric.

Cases are separated by incident family and time to reduce leakage between development, training, and held-out evaluation.

## Metric Families

| Family | Example measures | Failure interpretation |
|---|---|---|
| Evidence grounding | Citation precision/recall, source-version correctness, unsupported claim rate | Model output cannot be trusted as presented |
| RCA usefulness | Root-cause ranking, top-k coverage, time to useful hypothesis, missing-evidence identification | Investigation does not improve operator work |
| Safety | Policy bypass, cross-tenant exposure, unsafe recommendation/action, abstention quality | Release blocker |
| Calibration | Confidence versus empirical correctness, overconfidence, selective accuracy | Confidence UI is misleading |
| Robustness | Performance under missing/noisy/contradictory evidence and dependency faults | Brittle workflow or provider dependence |
| Latency | Stage and end-to-end distributions with sample count | SLO or operator-flow risk |
| Cost | Tokens, cache, provider spend, storage, compute per incident | Quota/business risk |
| Human factors | Task completion, correction rate, time, trust calibration, usability issues | Automation adds cognitive burden |

## Evaluation Stages

### Deterministic smoke

Three small cases validate harness determinism, schema handling, evidence citation, and result recording. They are run frequently and make no population-level quality claim.

### Scenario-family regression

At least ten fully working scenario families cover diverse incident mechanisms, evidence gaps, authorization conditions, provider failures, and remediation risks. This suite protects known behavior but remains insufficient for precise percentile or percentage claims.

### Held-out release corpus

An independently governed corpus is frozen before a release candidate is evaluated. Metric definitions, exclusions, thresholds, uncertainty method, minimum sample size, and multiple-comparison handling are preregistered. Any tuning against results creates a new development cycle and a new held-out set.

### Human baseline

Qualified operators perform representative tasks with and without OpsMind under a reviewed protocol. Record experience level, task order, time, corrections, final decisions, confidence, and qualitative failure modes. The goal is measured assistance, not replacing expert judgment.

## Ground Truth and Adjudication

Ground truth may contain multiple acceptable root causes or remediation paths. Two qualified reviewers adjudicate ambiguous cases, with disagreement recorded rather than forced. Evaluators distinguish:

- directly observed facts;
- deductions strongly supported by evidence;
- plausible but unconfirmed hypotheses;
- claims contradicted by evidence;
- information unavailable under the actor's authorization.

## Safety Evaluation

Adversarial cases target prompt injection in logs/runbooks, evidence poisoning, malicious connector content, tenant-crossing references, capability replay, approval substitution, stale target state, invented commands, provider schema drift, data-exfiltration requests, and hidden training eligibility changes.

Any confirmed cross-tenant disclosure, unapproved external effect, secret exposure, audit bypass, or deletion/revocation failure blocks release regardless of aggregate score.

## Provider Comparison

Provider adapters are compared on the same authorized input, schema, budget, and evaluator. Store provider/model identifier, feature flags, prompt/template version, request policy, response status, latency, token usage, and cost. Do not persist hidden chain-of-thought as a product artifact.

DeepSeek V4 Flash is the default target; a different model requires conformance and regression evidence rather than a configuration-only switch. Evaluations may send only redacted metrics and redacted log summaries, prohibit provider retention, require approved provider region/terms, and fall back to human-only investigation when egress is denied.

## Statistical Honesty

- Publish sample counts and uncertainty.
- Do not report p95 or p99 from a sample too small to resolve the tail.
- Do not treat correlated variants of one incident as independent cases.
- Do not optimize on the held-out set.
- Separate exploratory metrics from release criteria.
- Record missing data and evaluator disagreement.
- A zero observed failure rate is not proof of zero risk.

## Cost and Budget Evaluation

Measure cold/cached input, output, retries, invalid responses, tool turns, evidence storage, retrieval, workflow overhead, and human correction. Test hard and soft budget enforcement per incident and tenant. Budget exhaustion must produce a safe, explainable partial result rather than silent truncation or policy bypass.

The initial provider budget is USD 1,000 per month. The workload envelope used
to design tests is one organization, 25 concurrent investigations, 500
evidence events per second, and 120 model requests per minute. Evaluation must
measure these bounds rather than assume they are achievable.

## Promotion Decisions

Student-model training smoke is mandatory to prove the pipeline. Promotion is conditional. A candidate is rejected or quarantined when lineage, security, safety, calibration, capability, latency, or cost gates fail. “Do not promote” is a valid production decision.

## Evidence Format

Each report records code/model/prompt/evaluator/dataset/scenario versions, environment, metric definitions, raw aggregate inputs, exclusions, uncertainty, result, decision, and reviewers. Raw sensitive evidence remains access-controlled; the release artifact can contain redacted summaries and immutable references.

## Verification Evidence

Phase 8 creates the executable harness and baseline. Phases 13–14 add dataset/model lineage. Phase 16 runs final held-out, human, safety, performance, cost, and restore-linked evaluation.

## Remaining Evaluation Decisions

G0.5 approved the starting workload and provider-spend envelope. Phase 8 must
still preregister release thresholds, corpus ownership, qualified reviewer
pool, statistical power, and human-study constraints. Those later decisions
must not alter the approved policy silently.
