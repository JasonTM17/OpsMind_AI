---
phase: 15
title: "Security, Reliability, and Observability Hardening"
status: pending
effort: "3-5 weeks; platform/SRE/security team with all service owners"
dependencies: [7, 8, 9, 10, 11, 12, 13]
requirements: [SEC-01, SEC-02, SEC-03, SEC-04, OBS-01, OBS-02, OBS-03, REL-01, REL-02, REL-03, INV-01, INV-03, INV-04, INV-05, INV-06, INV-07, INV-08, ADD-04, ADD-07]
---

# Phase 15: Security, Reliability, and Observability Hardening

## Objective

Prove that the integrated platform fails safely under hostile input, dependency failure, load, partial outage and operator error. Convert cross-cutting requirements into executable controls, dashboards, alerts and release blockers; do not use this phase to postpone basic security or telemetry that earlier phases require.

## Non-goals

- No feature expansion or architectural rewrite to chase hypothetical scale.
- No claim of compliance certification without a defined framework and independent evidence.
- No high-cardinality/raw-prompt observability that creates a second sensitive data store.
- No automatic remediation of a failing safety signal; the emergency response is disable, contain and investigate.

## Prerequisites and entry gate

- The read-only incident vertical slice, durable workflow, RAG, approval/remediation and operator UI pass their phase gates.
- Every runtime exposes health/readiness, OTel propagation and structured redacted logging.
- Service-to-service identities and expected egress destinations are documented.
- Simulator and adversarial harness can create repeatable dependency, injection and authorization failures.

## Design decisions and patterns

1. **Threat-model-to-test linkage:** every material STRIDE/LLM threat maps to prevention/detection controls, test IDs, owner and residual risk.
2. **Zero implicit trust:** network location is insufficient; workload identity, audience, tenant scope and action authorization are checked at each boundary.
3. **Four telemetry signals with privacy budgets:** traces, metrics, logs and audit are correlated but have distinct retention/access; audit is not a debug log.
4. **Resilience policy by operation:** timeout, retry, breaker, bulkhead and queue behavior are declared per dependency and idempotency class.
5. **Kill-switch hierarchy:** provider, model, connector, write-action class, tenant and global switches fail closed and are tested without deployment.
6. **Error budgets and SLOs:** alert on user impact and safety control failure, not raw infrastructure noise.
7. **Continuous verification:** SAST/SCA/secret/IaC/container/DAST and adversarial evaluations emit versioned artifacts and block release by policy.

## Planned file inventory

| Operation | Path | Purpose |
|---|---|---|
| CREATE | `docs/security/threat-model.md` | Assets, actors, trust boundaries, threats, controls and residual risks |
| CREATE | `docs/security/control-test-matrix.md` | Control ownership and executable evidence links |
| CREATE | `docs/reliability/slo-and-error-budget-policy.md` | Service and user-journey objectives |
| CREATE | `docs/reliability/dependency-resilience-policy.md` | Per-operation timeout/retry/idempotency matrix |
| CREATE | `observability/otel-collector/` | Redaction, sampling and signal export configuration |
| CREATE | `observability/dashboards/` | API, DeepSeek, tools, RAG, workflow, approval, cost and security dashboards |
| CREATE | `observability/alerts/` | SLO, safety, backlog, budget and dependency alert rules |
| CREATE | `security/policies/` | IaC/container/secret/SBOM and deployment policy-as-code |
| CREATE | `security/adversarial/` | Direct/indirect injection, poisoned RAG/dataset, output and tool-abuse corpus |
| CREATE | `tests/fault-injection/` | Latency, outage, duplicate, crash, saturation and partial-failure scenarios |
| CREATE | `tests/security/tenant-isolation/` | API, DB, tool, RAG, audit, dataset and artifact negative matrix |
| CREATE | `scripts/security/run-security-gates.ps1` | Reproducible Windows gate entrypoint |
| CREATE | `scripts/security/run-security-gates.sh` | Reproducible Linux/macOS gate entrypoint |
| CREATE | `scripts/operations/exercise-kill-switches.*` | Staging-safe containment drill |
| MODIFY | All service config modules | Central typed resilience, telemetry and redaction settings |
| MODIFY | CI workflow definitions | Make evidence-producing security gates required |
| MODIFY | `docs/system-architecture.md` | Final trust boundaries, failure domains and telemetry flows |

Production code changes must remain inside existing modules established in earlier phases; extract a shared library only when semantics are identical across languages, otherwise share contracts rather than runtime code.

## Implementation tasks

### 15.1 Finalize the threat model

- Inventory protected assets: tenant incident data, evidence, credentials/references, approvals, audit, datasets, model artifacts and operational control.
- Model spoofing, tampering, repudiation, disclosure, denial of service and privilege escalation across browser, API, AI runtime, Tool Gateway, workflow, database, artifact store and external providers.
- Add LLM-specific cases: direct/indirect prompt injection, tool argument smuggling, evidence fabrication, poisoned retrieval/data, cross-tenant context, denial-of-wallet and unsafe confidence.
- Assign each threat a control, observable signal, test, owner and residual-risk decision. Unowned Critical/High residual risk blocks release.

### 15.2 Enforce runtime security posture

- Validate workload identity/audience between services; use short-lived scoped credentials or secret references, never a shared static super-token.
- Deny Tool Gateway and AI Runtime egress except named provider/connector destinations; defend DNS rebinding, redirect and private-address SSRF.
- Apply secure headers, strict CSP, cookie flags where cookies exist, CORS allowlists, payload/decompression limits and safe error responses.
- Run containers as non-root where applicable with read-only root filesystem, dropped capabilities, seccomp/default profile and writable temp mounts only where required.
- Prove tenant context is set/cleared per database transaction and cannot leak through pooled connections or background jobs.
- Re-run workload delegation attacks across platform, AI runtime and Gateway: a valid service identity without a platform-issued exact capability has no actor/tenant authority.

### 15.3 Build adversarial and supply-chain gates

- Test prompt injection in alerts, logs, source, runbooks, retrieved chunks and tool results; data instructions cannot modify system/tool policy.
- Test malformed model JSON, duplicate keys, numeric overflow, Unicode confusables, deep nesting and hostile URLs/paths/query fragments.
- Scan working tree and Git history for secrets; run SAST, dependency/license, container, IaC and SBOM/provenance checks.
- Pin actions and base images by reviewed immutable references; verify downloaded artifacts and block unaccepted Critical findings.
- Store scan artifacts with triage owner, expiry and accepted-risk rationale; never hide failures with blanket exclusions.

### 15.4 Apply resilience policies

- Define deadlines from inbound request through AI/tool calls; cancellation propagates downstream and abandoned work releases capacity.
- Retry only transient, idempotent operations with capped exponential backoff plus jitter; use idempotency keys/inbox for retried mutations.
- Add circuit breakers and per-tenant/provider bulkheads so one dependency or noisy tenant cannot exhaust all workers.
- Bound queues and reject/defer predictably under pressure. Do not buffer unlimited tool output, model streams or ingestion files.
- Ensure graceful shutdown stops admission, drains bounded work, checkpoints durable activities and preserves outbox/inbox correctness.

### 15.5 Complete observability and redaction

- Propagate W3C trace context plus correlation, incident and safe tenant identifiers across HTTP, Temporal, outbox and tool boundaries.
- Define low-cardinality metrics for availability/latency/errors/saturation, model tokens/cost/schema failures, tool policy decisions, RAG quality, workflow age, approval latency and safety events.
- Log structured event names and references; redact headers, secrets, raw prompts, unrestricted evidence and tool payloads at source.
- Separate append-only audit access from operational logs; record policy decision inputs/versions and exact action digest without secret values.
- Provision dashboards and multi-window burn-rate alerts with runbook links and ownership.

### 15.6 Exercise failure and containment

- Inject DeepSeek timeout/429/5xx/empty/truncated streaming output, database failover/slow query/WAL-full/ENOSPC, Redis loss, Temporal worker/persistence saturation, connector outage, duplicate events, audit-store unavailability and artifact-store quota/ENOSPC.
- When durable intent/audit storage is unavailable, prove no new external write begins. Document the exact read-only operations that remain safe instead of returning generic “degraded” success.
- Verify read-only degradation, abstention and operator-visible status rather than fabricated completion.
- Exercise provider/model/connector/write/global kill switches and document propagation objective.
- Load test representative incident concurrency and adversarial large inputs under fixed resource quotas; capture saturation point and recovery.

## Verification and evidence matrix

| Check | Method | Passing evidence |
|---|---|---|
| Tenant isolation | Cross-layer negative matrix including pooled connections/jobs | 100% forbidden reads/writes denied and audited |
| Prompt/tool safety | Versioned adversarial corpus | 100% Critical cases blocked/neutralized; no unsafe execution |
| Secrets/data leakage | Seeded canaries plus log/trace/artifact scan | Zero raw canary value outside authorized source |
| Supply chain | SAST/SCA/secret/container/IaC/SBOM pipeline | Zero unaccepted Critical; SBOM/provenance attached |
| Retry correctness | Fault injection by operation class | No retry of unsafe non-idempotent write; duplicates converge |
| Failure recovery | Kill/restart/dependency outage scenarios | Durable work resumes or fails explicitly without corrupt state |
| Backpressure | Load to and beyond declared capacity | Bounded memory/queue and predictable overload response |
| Storage exhaustion | OS/workspace low space, PostgreSQL WAL full, Temporal persistence full, artifact/audit quota exhausted | No corrupt state or unaudited external write; explicit safe degradation and recovery |
| Kill switches | Staging exercise | New affected work stops within declared objective and is audited |
| Telemetry | Trace/log/metric/audit inspection | End-to-end correlation with no prohibited payloads |
| Alerting | Synthetic SLO/safety/budget violations | Correct deduplicated alert routes to tested runbook |

## Exit gate

- Threat model and control-test matrix cover every trust boundary; no unowned or unaccepted Critical finding remains.
- Cross-tenant and Critical adversarial suites pass 100%; seeded secrets do not appear in logs, traces, artifacts or UI.
- Representative failure matrix proves bounded retry/backpressure, durable recovery and explicit degradation.
- Storage-full/audit-unavailable tests prove zero new external writes without durable intent/audit and no false-success response.
- All kill switches work in staging and have named owners/runbooks.
- Dashboards expose the complete incident journey and cost/safety signals; alert tests route successfully.
- Load results define supported operating envelope and provisional SLO/error budgets for Phase 16 release policy.

## Rollback and recovery

- Keep hardening controls behind versioned configuration only when rollback cannot weaken a mandatory boundary; security-deny behavior is the safe default.
- Roll back telemetry sampling/export configuration independently if it destabilizes workloads, preserving audit emission.
- Disable failing providers/connectors/action classes via kill switches before application rollback.
- Restore last-known-good policies and dashboards from version control; record emergency changes in audit and reconcile through Git.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Hardening phase becomes a late security dump | Earlier gates remain mandatory; this phase integrates and stress-tests them |
| Telemetry leaks incident content | Allowlisted attributes, source redaction, seeded-canary tests and access separation |
| Resilience retries amplify outage | Operation-specific policy, budgets, jitter, breaker and bulkhead |
| Security scans create noisy exceptions | Evidence-backed triage with owner/expiry; Critical default block |
| Load tests overload workstation | Remote/staging execution and local disk/resource preflight |

## Unresolved decisions

- Production SLOs, alert routing/on-call ownership and error-budget policy.
- Approved identity, secret, telemetry and vulnerability-management vendors.
- External penetration-test/compliance requirements and risk-acceptance authority.
