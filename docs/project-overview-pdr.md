# OpsMind AI Product Development Requirements

## Purpose

OpsMind AI reduces the time and cognitive load required to investigate operational and security incidents while preserving human authority over production effects. It combines deterministic workflow control, authorized evidence collection, retrieval with provenance, model-assisted reasoning, and guarded remediation.

This document describes the product contract. It does not claim that planned runtime behavior already exists.

## Problem Statement

Incident response commonly fails because evidence is fragmented, context is lost between tools, hypotheses are not tied to sources, repetitive queries consume operator time, and remediation decisions are made under pressure. Adding an unrestricted AI agent would increase speed but create unacceptable risks: tenant data leakage, invented evidence, unauthorized commands, duplicated writes, and untraceable decisions.

OpsMind must improve investigation quality without turning probabilistic model output into infrastructure authority.

## Target Users

| Persona | Primary need | Required protection |
|---|---|---|
| On-call SRE | Build a reliable incident timeline and narrow root cause | No misleading evidence or unsafe automation |
| Platform engineer | Reuse connectors, workflows, and runbooks | Stable contracts and deterministic replay |
| Security operator | Investigate suspicious changes and access paths | Least privilege, data classification, immutable audit |
| Incident commander | Understand impact, confidence, decisions, and ownership | Clear state, escalation, and approval trail |
| Risk/compliance owner | Prove who accessed data and authorized effects | Retention, deletion, residency, and export controls |
| Product administrator | Configure tenants, policies, integrations, and quotas | Strong tenant boundary and safe defaults |

## Product Outcomes

1. Evidence from approved sources is normalized into an incident-scoped timeline.
2. The system separates observed facts from model-generated hypotheses.
3. Each hypothesis references provenance, confidence, contradictory evidence, and unresolved gaps.
4. Operators can approve a precisely previewed action rather than a vague natural-language intent.
5. Replays, retries, crashes, and ambiguous provider responses produce at most one effective external write.
6. Tenant revocation and data deletion propagate into retrieval indexes, derived datasets, and model eligibility.
7. Evaluation reports expose quality, safety, latency, cost, calibration, and operator usefulness without statistically unsupported claims.

## First Valuable Vertical Slice

The first end-to-end slice is intentionally narrow:

1. An authenticated operator creates an incident within an authorized project.
2. The platform collects read-only synthetic metrics through one live non-production Prometheus connector.
3. Evidence is normalized, content-addressed, and linked to an immutable audit sequence.
4. The deterministic investigation state machine requests a bounded DeepSeek analysis through the provider adapter.
5. The model returns structured hypotheses; application code validates the schema and preserves evidence references.
6. The operator sees timeline, evidence, hypothesis, confidence, cost, and provider status.
7. No production write is available in this slice.

Fixture-based tests support development but do not substitute for the live connector acceptance test.

## Functional Requirements

### Incident control plane

- Create, update, assign, and close incidents through versioned contracts.
- Maintain deterministic incident state transitions and reject invalid transitions.
- Record evidence, hypotheses, recommendations, approvals, execution intents, outcomes, and audit entries with tenant scope.
- Preserve causality and correlation identifiers across services and workflows.

### Evidence and investigation

- Collect only from allowlisted connectors and delegated read scopes.
- Store large evidence through an encrypted object-storage port; store digest and metadata transactionally.
- Normalize timestamps and source identity without erasing the original payload reference.
- Track contradictory and missing evidence instead of forcing a conclusion.

### AI runtime

- Use a provider-neutral interface with DeepSeek V4 Flash as the default configured implementation.
- Validate every structured response and tool argument outside the model.
- Bound token, cost, time, retries, tool turns, and evidence volume per investigation.
- Persist only the continuation state required for restart safety and according to data-class policy.
- Disable provider egress unless tenant and data-class policy explicitly permit it.
- Treat `/health` as liveness and `/ready` as dependency readiness; readiness
  must be non-routable while the provider capability probe or shared state is
  degraded.
- Do not inherit ambient proxy or CA configuration for provider HTTP traffic.

### Retrieval-augmented generation

- Authorize before candidate retrieval and before ranking.
- Use forced PostgreSQL RLS and transaction-local tenant context.
- Return citations with source identity, version, authorization epoch, and content digest.
- Revoke access immediately and purge derived copies through lineage-aware workflows.

### Tool execution and remediation

- Isolate connector credentials in the Tool Gateway.
- Accept only platform-issued, short-lived delegated capabilities.
- Default every connector to read-only.
- Require dry-run output, canonical action digest, policy decision, human approval, target-state compare-and-set, idempotency key, and durable execution lease for writes.
- Reconcile timeouts and ambiguous results before retrying.
- Define compensation only where the target operation is genuinely reversible.

### Operator experience

- Display evidence provenance and authorization failures explicitly.
- Stream progress without presenting partial model output as a final conclusion.
- Separate suggested, approved, executing, reconciled, succeeded, failed, and compensated states.
- Provide accessible keyboard, screen-reader, responsive, and degraded-mode behavior.

### Dataset and model lifecycle

- Admit examples only with provenance, consent/authority, redaction status, quality review, and dataset version.
- Prevent train/test contamination using incident-family and temporal separation.
- Link every model candidate to code, configuration, base model, dataset snapshot, evaluator, and security scan.
- Quarantine models affected by revoked or deleted lineage.
- Permit a safe decision not to promote a student model.

## Non-Functional Requirements

| Area | Requirement |
|---|---|
| Security | Deny by default; least privilege; no shared broad credential; secret-free source and artifacts |
| Isolation | Tenant scope enforced at API, database, retrieval, artifact, workflow, and connector boundaries |
| Reliability | Durable state, idempotent handling, bounded retries, backpressure, graceful degradation, replay proof |
| Auditability | Append-only decision/effect history with actor, policy, approval, digest, target, and outcome |
| Performance | Initial 99.9% availability and 500 ms API p95; one organization, 25 concurrent investigations, 500 evidence events/second, and 120 model requests/minute; measurements must revalidate these targets |
| Cost | Per-tenant and per-investigation budgets, quotas, cache accounting, and provider usage attribution |
| Privacy | Singapore residency; 365-day incident, 90-day evidence, and 730-day audit retention; 24-hour deletion SLA; training opt-in only; redacted allowlisted provider egress |
| Operability | Health, metrics, logs, traces, alerts, runbooks, restore drill, and capacity preflight |
| Portability | Local/CI/staging/production configuration through explicit adapters and environment contracts |

## Scope Boundaries

The first production tranche is not a general autonomous agent, arbitrary shell, universal integration marketplace, full SIEM replacement, or guaranteed root-cause oracle. It will not grant the model raw production credentials, auto-approve its own writes, train on tenant data by default, or claim statistical reliability from a small deterministic smoke suite.

Kafka, service decomposition, multi-region active-active, automated student promotion, and broad write connectors require measured evidence and separate ADRs.

## Product and Production Gate G0.5

The project owner approved all twelve G0.5 decisions on 2026-07-19. The
machine-readable source of truth is
[product-production-contract.json](./decisions/product-production-contract.json),
with provenance in the
[approval record](./decisions/g0-5-approval-2026-07-19.md).

| Decision | Approved baseline |
|---|---|
| Deployment | Internal single organization; managed Kubernetes production in `ap-southeast-1`, Singapore |
| Isolation | One organization, logical isolation, at most 100 projects |
| Identity | Enterprise OIDC Authorization Code with PKCE, MFA required, `security-operations` break-glass owner |
| DeepSeek egress | Allowlisted redacted metrics and log summaries only; no provider retention; approved region/terms required; USD 1,000 monthly budget; human-only fallback |
| First live integration | Read-only Prometheus against synthetic non-production metrics; owner `site-reliability-team` |
| Evidence store | MinIO local; S3-compatible production; `production-kms`; `platform-security` retention owner; four-hour artifact restore target |
| Load | One organization, 25 concurrent investigations, 500 evidence events/second, 120 model requests/minute |
| Service objectives | 99.9% availability, 500 ms API p95, 120-minute RTO, 15-minute RPO |
| Lifecycle | Incidents 365 days, evidence 90 days, audit 730 days, deletion within 24 hours, training opt-in only, Singapore residency |
| Ownership | `platform-team`, `site-reliability-team`, `security-team`, `privacy-team`, `integrations-team`, `database-team`, `workflow-team`, and `product-finance-owner` |
| Delivery | Six contributors, nine months, approved budget; product, frontend, backend, AI/ML, SRE/platform, and security/privacy coverage |

These values authorize planning and implementation. The 2026-07-21 Keycloak
26.7 run proves only the local/reference non-production identity profile; it
does not select or authorize the production IdP. Provider/production-IdP/
connector conformance, measured SLOs, and recovery remain release gates.

## Acceptance Model

Each roadmap phase has an exit gate with named evidence. Final acceptance requires:

- an end-to-end vertical slice across identity, tenant scope, live read connector, evidence, provider adapter, UI, and audit;
- adversarial tenant-isolation and approval-binding tests;
- crash/replay/idempotency and storage-full failure injection;
- permission-aware RAG with revoke/delete proof;
- provider conformance, degraded-mode, and budget enforcement tests;
- release evaluation against held-out cases and a qualified human baseline;
- restore drill and external-effect reconciliation;
- runbooks exercised by someone other than their author;
- secret, dependency, container, IaC, API, and data-governance gates.

## Success Measurement

Measurement categories are fixed now. G0.5 approved the starting load, service,
and provider-budget envelope; Phase 8 must preregister model-quality, safety,
calibration, human-study, and release thresholds:

- incident triage time and time to useful first hypothesis;
- root-cause ranking quality and evidence citation precision;
- unsupported-claim and unsafe-action rate;
- confidence calibration and abstention quality;
- end-to-end and stage-level latency distributions;
- provider and infrastructure cost per incident;
- operator task completion, correction rate, and trust calibration;
- tenant-isolation, revocation, deletion, replay, and DR pass rates.

Smoke scenarios prove deterministic mechanics only. Release claims require an independently held-out corpus with justified sample size and uncertainty reporting.

## External Dependencies

- DeepSeek API behavior and approved data-processing terms.
- A production-authorized enterprise OIDC provider conforming to Authorization Code with PKCE and MFA; local Keycloak remains a reference target only.
- The approved read-only Prometheus connector against synthetic non-production metrics.
- PostgreSQL with pgvector.
- S3-compatible object storage and production key management.
- Temporal after the thin vertical slice is measured.
- Managed Kubernetes in `ap-southeast-1` with Singapore residency controls.

## Verification Evidence

### Phase 5 checkpoint (not an exit or release gate)

The implemented checkpoint has a provider-neutral FastAPI runtime, DeepSeek
adapter, strict delegated-capability and redaction checks, durable PostgreSQL
replay/accounting, and an append-only V005 synthetic capability-probe audit.
The Python suite reports 149 passed and five PostgreSQL-gated skips; Ruff and
mypy are clean; the full Maven suite passes; and the static checkpoint passes.
The pgJDBC dependency is pinned to `42.7.13`.

This evidence does not authorize live provider traffic. `B-004` remains active
until provider region, processing terms, retention behavior, and redaction
controls are verified. The Phase 5 exit additionally requires a passing
synthetic, redacted smoke using an externally injected rotated staging key. No
such smoke or production egress is claimed.

The PDR is verified through requirements traceability, plan validation, ADR review, G0.5 approval, and later runtime evidence. Current identity evidence includes a passing local Keycloak reference transcript marked `REFERENCE_CONFORMANCE_NOT_PRODUCTION`; its unborn/dirty revision and ignored location prevent treating it as release proof. The source matrix is [master-prompt-requirements-traceability.md](../plans/260719-1747-opsmind-ai-production-platform/research/master-prompt-requirements-traceability.md).

## Remaining Product Gates

No G0.5 decision remains pending. Provider terms, production identity conformance, detailed
infrastructure selections, evaluation thresholds, and release evidence remain
later gates tracked in [Blockers](./blockers.md). A later phase may not claim an
approved policy is implemented without executable evidence.
