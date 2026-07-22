---
type: brainstorm
date: 2026-07-19
status: accepted-for-planning
scope: hold
---

# Brainstorm Report: OpsMind AI Production Platform

## Summary

OpsMind AI is not a chatbot or an unrestricted autonomous SRE. It is a governed evidence-to-action platform: capture evidence, maintain competing hypotheses, produce a cited RCA, propose bounded remediation, require policy and human approval for writes, verify outcomes, and preserve an auditable decision trail.

Recommended direction: evolutionary architecture with four initial runtime deployables—Next.js web, Spring modular control plane, Python AI runtime, and isolated Tool Gateway—plus PostgreSQL/pgvector and offline evaluation jobs. Evaluation and simulator work move before broad feature expansion. Kafka, Kubernetes, dedicated RAG/evaluation services, and student-model serving enter only through measurable graduation gates.

## 1. Proposed Solution That Triggered The Brainstorm

The master prompt proposed a production-grade monorepo with:

- Next.js web dashboard.
- Java 21/Spring Boot platform backend.
- Python/FastAPI AI, RAG, evaluation, and training services.
- PostgreSQL, pgvector, Redis, optional Kafka.
- DeepSeek V4 Flash teacher/reasoning/fallback model.
- Tool Gateway, approval workflow, simulator, RAG, dataset, SFT/DPO, Kubernetes, CI/CD, and observability.

The proposal is technically feasible but over-sequences infrastructure and breadth before validating the core product outcome.

## 2. Underlying Problem

Incident response is slow and inconsistent because evidence is fragmented across metrics, logs, traces, deployments, source code, runbooks, databases, and human memory. Existing AI assistants may summarize data but do not provide a durable, permission-aware, evidence-linked path from alert to verified root cause and safe action.

The primary problem is not lack of model intelligence. It is lack of governed evidence acquisition, reproducible reasoning, authorization, and outcome verification.

## 3. Target Users And Outcomes

| User | Primary outcome | Harm to prevent |
|---|---|---|
| On-call SRE | Faster verified root cause and next action | Hallucinated diagnosis; noisy tool loops |
| Incident commander | Shared timeline and decision state | Conflicting or lost investigation state |
| Security reviewer | Review exact high-risk action and evidence | Generic/stale approval; privilege escalation |
| Platform administrator | Controlled integrations, tenants, quotas, policies | Credential leakage; cross-tenant access |
| Developer | Understand regression and create safe patch/PR | Incorrect source interpretation; secret exposure |
| ML/evaluation engineer | Reproducible benchmark and curated data flywheel | Synthetic-data feedback loops; leakage |

North-star product metric: median time-to-verified-root-cause, measured against a human baseline and constrained by zero unauthorized actions.

## 4. Assumptions Challenged

### Microservices equal production readiness

Rejected. Service count increases failure modes and operational cost. Production readiness comes from explicit boundaries, safe contracts, recovery, observability, and verified controls. Start with a modular control plane and extract only when scaling, security, ownership, or release cadence requires it.

### A 1M-token model context removes the need for retrieval

Rejected. Large context increases cost, latency, distraction, and prompt-injection surface. Use source-aware retrieval, explicit context budgets, provenance, and citations.

### Model confidence is a reliable probability

Rejected. Self-reported confidence is one weak signal. Server-side confidence should combine calibrated historical performance, evidence coverage, contradictions, missing tools, and incident class.

### Human approval alone makes write actions safe

Rejected. Approval must bind exact normalized parameters, action digest, target identity, resource state/version, policy version, expiry, and approver separation. Reauthorize immediately before execution.

### Teacher output is training ground truth

Rejected. Teacher responses remain candidate labels. Deterministic checks, scenario ground truth, human review, licensing, PII checks, and held-out evaluation determine acceptance.

### Kafka, Kubernetes, and fine-tuning belong in the foundation

Rejected. They are target capabilities with graduation gates, not prerequisites for the first evidence-backed incident investigation.

## 5. Alternative Problem Framings

### Framing A: AI operations assistant

- Fastest interface concept.
- Weak authorization and reproducibility story.
- High risk of becoming a chat UI over observability APIs.

### Framing B: Incident investigation system of record

- Strong evidence, timeline, ownership, and audit foundations.
- Model becomes one replaceable investigator.
- Recommended base framing.

### Framing C: Autonomous remediation platform

- Highest theoretical MTTR reduction.
- Unacceptable initial blast radius and evaluation burden.
- Deferred until read-only RCA and reversible actions prove safety.

Final framing: an incident investigation system of record with an evidence-bounded AI investigator and progressively unlocked automation.

## 6. Evaluated Architecture Approaches

| Approach | Initial deployables | First real vertical slice | Operational burden | Long-term fit |
|---|---:|---|---|---|
| Microservices-first | 8–12 | Slow | Very high | Good only with larger teams and hard boundaries already known |
| Evolutionary modular platform | 4 | Medium-fast | Moderate | Best balance; recommended |
| Python-first monolith | 2–3 | Fastest | Low initially | High rewrite risk for IAM, policy, transactions, and governance |

### Recommended runtime boundaries

1. `apps/web`: Next.js operator experience and SSE client.
2. `services/platform-api`: Spring modular control plane; owner of IAM projection, incidents, approvals, audit, API, and cost governance.
3. `services/ai-runtime`: FastAPI model adapter, context builder, structured analysis, investigation loop, retrieval, and evaluation hooks.
4. `services/tool-gateway`: separate process/identity; typed connectors, policy enforcement, sandboxing, redaction, and evidence capture.
5. `services/incident-simulator`: test/development only.
6. `training`: offline curated dataset, experiments, and student pipeline; no direct production credentials.

### Data ownership

- PostgreSQL cluster initially; logical schemas and least-privilege roles.
- `tenant_id` on tenant-owned rows; application authorization plus forced RLS defense in depth.
- Large logs, traces, documents, and source snapshots stored as object artifacts; database stores metadata, checksums, ACL, and lineage.
- Redis is optional cache/rate-limit infrastructure, never source of truth.
- Transactional outbox/inbox before Kafka.
- Append-only incident timeline/evidence/audit ledger; do not event-source the entire product.

## 7. Design Patterns And Invariants

| Concern | Pattern | Constraint |
|---|---|---|
| Business ownership | DDD bounded contexts | No cross-module repository/table access |
| Domain isolation | Hexagonal architecture | Domain cannot import provider/connector SDKs |
| Initial topology | Modular monolith | Boundary tests mandatory |
| Incident lifecycle | Explicit state machine | Illegal transitions fail closed |
| Investigation/HITL | Process manager; durable workflow | Store references, not raw sensitive payloads, in history |
| Tools/actions | Command pattern | Schema, risk, permission, idempotency, timeout required |
| Providers/connectors | Strategy/Adapter + anti-corruption layer | Normalize external failures and evidence |
| Authorization | Policy/specification | Identity plus resource, tenant, environment, action risk |
| Async reliability | Transactional outbox/inbox | At-least-once handlers are idempotent |
| UI reads | CQRS-lite projections | No duplicate business authority in projections |
| Failure isolation | Bulkhead/circuit breaker/backpressure | Retry only categorized idempotent work |
| Service extraction | Strangler pattern | Extraction requires measured justification |

Hard invariants are maintained in the requirements traceability report.

## 8. Recommended Product Flow

1. Ingest and normalize alert.
2. Create or correlate incident.
3. Start versioned investigation run.
4. Form multiple hypotheses; distinguish symptom, contributing factor, root cause.
5. Plan required evidence.
6. Authorize each read-only tool independently.
7. Capture normalized evidence with provenance and trust classification.
8. Score supporting and counter-evidence.
9. Stop, continue, abstain, or escalate based on deterministic budgets and calibrated confidence.
10. Produce a cited RCA and action proposal.
11. Bind any write proposal to exact approval and current resource state.
12. Dry-run, execute with short-lived credentials, verify outcome, and compensate when supported.
13. Generate postmortem from the durable timeline.

The model never receives an unrestricted shell, database owner, cluster-admin role, or raw secret access.

## DeepSeek Integration Decisions

- Default model ID: `deepseek-v4-flash`; model/base URL remain externalized.
- Direct provider adapter; do not embed provider behavior throughout domain code.
- Use JSON mode plus application schema validation; handle empty/invalid responses explicitly.
- Validate every tool argument independently; strict beta behavior is defense in depth, not the sole control.
- Preserve `reasoning_content` only as required within tool sub-turns; do not expose or log raw chain-of-thought.
- Stable prompt prefixes and explicit token/context budgets; do not stuff the advertised context window.
- Opaque provider `user_id`; no email, name, incident text, or other PII.
- Error matrix distinguishes non-retryable validation/auth/balance failures from bounded 429/5xx retries.

Primary sources:

- https://api-docs.deepseek.com/
- https://api-docs.deepseek.com/updates/
- https://api-docs.deepseek.com/guides/thinking_mode
- https://api-docs.deepseek.com/guides/json_mode/
- https://api-docs.deepseek.com/guides/tool_calls

## Security Position

- Treat user input, telemetry, retrieved documents, source, tool output, and model output as mutually untrusted.
- Authorization is deterministic and external to the model.
- Read-only does not mean harmless: queries require target, range, cardinality, response-size, and cost limits.
- Approval never conveys credentials. Tool Gateway obtains scoped short-lived credentials after reauthorization.
- No default approval on timeout; rejection cannot silently rephrase/retry the same action indefinitely.
- Every connector uses outbound allowlists and blocks arbitrary URL/path/command construction.
- Sensitive GenAI telemetry content is opt-in, redacted, encrypted where retained, and short-lived.
- Training/evaluation plane receives curated de-identified exports, not live production access.

Security baselines:

- https://owasp.org/www-project-top-10-for-large-language-model-applications/
- https://www.nist.gov/publications/artificial-intelligence-risk-management-framework-generative-artificial-intelligence
- https://www.postgresql.org/docs/18/ddl-rowsecurity.html

## Success Metrics

Provisional release targets, calibrated after baseline:

- At least 40% median reduction in time-to-verified-root-cause.
- At least 80% top-1 RCA accuracy on versioned held-out scenarios.
- At least 95% evidence/citation precision.
- At least 99.5% structured-output validity after bounded recovery.
- Zero unauthorized/destructive/cross-tenant action in the release security suite.
- 100% write actions tied to valid exact-action approvals and audit records.
- Standard investigation p95 under 120 seconds subject to provider SLO.
- Configurable per-run/tenant token, cost, tool, time, and storage budgets.

## Delivery Implications

- Build one thin read-only incident investigation before broad integrations.
- Move simulator and evaluation immediately after that slice.
- Introduce durable workflow infrastructure before long waits or write-capable remediation.
- Build permission-aware RAG only after evidence contracts and tenant boundaries exist.
- Complete reversible dry-run/approval before any destructive category is considered.
- Treat training and DPO as data-quality programs, not core MVP features.
- Require disk-capacity preflight before Docker, dependency, dataset, model, or artifact-heavy work.

## Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Platform breadth overwhelms delivery | No validated product | Exit-gated vertical slices; explicit deferrals |
| Prompt injection influences tool choice | Unauthorized access/action | Deterministic policy, capability scope, isolated execution |
| Approval becomes rubber stamp | False safety | Exact diffs, risk summaries, expiry, separation of duty |
| Provider/API behavior changes | Broken investigations | Capability probe, adapter contracts, versioned prompts, rollback |
| Cross-tenant retrieval leak | Critical data breach | RLS, ACL-before-ranking, negative tests, non-owner DB roles |
| Synthetic training feedback loop | Silent quality degradation | Human review, ground truth, lineage, held-out contamination controls |
| Local disk exhaustion | Corrupt builds/artifacts | C:/D: monitor, preflight blocks, configurable artifact/cache roots |

## Next Steps

1. Finalize research reports and requirement/evidence matrix.
2. Create deep implementation plan and phase files through CK CLI.
3. Red-team architecture, security, data integrity, and delivery assumptions.
4. Validate all unresolved decisions and reconcile the whole plan.
5. Begin implementation only through the reviewed plan.

## Unresolved Questions

- Internal platform, SaaS multi-tenant, or customer-hosted deployment model?
- Cloud/on-prem target and data residency requirements?
- Identity provider and enterprise federation requirement?
- Initial observability/Git/Kubernetes integrations?
- Expected tenant count, incident concurrency, retention, and SLO?
- Whether production content may leave the organization for DeepSeek processing?
- Team size, delivery deadline, budget, and GPU capacity?
