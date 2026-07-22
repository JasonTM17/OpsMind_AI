---
type: research
date: 2026-07-19
status: draft
source: master-prompt
---

# OpsMind AI Requirements Traceability

## Summary

This matrix converts the master prompt and active goal into testable obligations. A requirement is complete only when its listed evidence exists and was inspected. File presence, a passing narrow unit test, or an implementation claim is insufficient for broader system requirements.

## Scope Invariants

| ID | Invariant | Required evidence |
|---|---|---|
| INV-01 | AI never holds unrestricted production credentials | Tool Gateway integration tests; credential-scope review; deployment identities |
| INV-02 | Every claim in a final RCA links to captured evidence | RCA schema tests; held-out benchmark scoring; incident UI inspection |
| INV-03 | Every write action is policy-checked and bound to exact approval | Approval digest tests; stale-state/TOCTOU tests; audit event chain |
| INV-04 | Tenant/project boundaries apply to API, tools, RAG, audit, and datasets | Negative authorization matrix; PostgreSQL RLS tests; cross-tenant red-team suite |
| INV-05 | Model output is untrusted input | Schema validation tests; malicious argument tests; fail-closed execution traces |
| INV-06 | Logs, documents, source, and tool results are data, never authority | Indirect prompt-injection suite; policy-decision evidence |
| INV-07 | Every asynchronous boundary is idempotent and recoverable | Outbox/inbox tests; crash/restart tests; duplicate-delivery tests |
| INV-08 | Raw secrets, credentials, and sensitive prompt content never enter Git or default telemetry | Secret scans; log assertions; artifact inspection; SBOM/provenance report |
| INV-09 | Evaluation precedes autonomous or write-capable behavior | Release-gate records; benchmark reports; feature-flag configuration |
| INV-10 | Heavy local operations require disk-capacity preflight | C:/D: monitor evidence; preflight script tests; operator runbook |

## Functional Requirement Ownership

| ID | Requirement | Planned owner | Proof required |
|---|---|---|---|
| IAM-01 | Secure login via standards-based identity provider | Identity & tenant foundation | OIDC integration test; invalid/expired token tests |
| IAM-02 | RBAC plus resource-level tenant/project/environment authorization | Identity & tenant foundation | Full role/action authorization matrix |
| IAM-03 | Service accounts and API credential metadata without storing raw credentials | Identity & tenant foundation | Schema review; vault/reference integration test |
| IAM-04 | Refresh/revocation behavior delegated to IdP or implemented without custom cryptography | Identity & tenant foundation | Token replay/revocation tests |
| INC-01 | Incident create/read/update, severity, owner, alert, evidence, state transitions | Incident control plane | API/controller/service/repository tests; E2E workflow |
| INC-02 | Immutable timeline, final root cause, resolution, closure, postmortem | Incident control plane | Transition tests; timeline audit; E2E postmortem |
| AI-01 | Configurable server-side DeepSeek V4 Flash adapter | AI runtime vertical slice | Live opt-in smoke test; contract fixtures; configuration failure test |
| AI-02 | Thinking/non-thinking mode, streaming, usage/cost, timeouts, error mapping | AI runtime vertical slice | Provider contract tests; 400/401/402/422/429/5xx matrix |
| AI-03 | Structured incident analysis with hypotheses, counter-evidence, missing evidence, actions, confidence | Investigation engine | Pydantic/JSON schema tests; benchmark validity metrics |
| AI-04 | Bounded investigation loop with stop, abstain, budget, duplicate/no-progress controls | Investigation engine | Loop-bound tests; timeout/cancellation/crash recovery tests |
| AI-05 | Prompt/model/schema version registry and reproducible invocation record | AI runtime vertical slice | Persistence tests; replay metadata inspection |
| TOOL-01 | Typed Tool Gateway with schema, policy, timeout, rate limit, audit, risk class | Safe tool plane | Connector contract tests; policy-denial tests |
| TOOL-02 | Read-only observability, Kubernetes, deployment, Git, source, runbook, DB, Kafka tools | Safe tool plane | Per-tool integration tests against simulator/fixtures |
| TOOL-03 | No arbitrary shell, URL, path, query, or credential authority | Safe tool plane | SSRF/path/command/query abuse suite |
| APR-01 | Expiring approval with reason, role, optimistic locking, idempotency | Approval/remediation | Concurrency, replay, expiry, rejection tests |
| APR-02 | Two-person approval for critical actions | Approval/remediation | Separation-of-duty tests |
| APR-03 | Approval binds action digest and resource state witness | Approval/remediation | Mutation-after-approval/TOCTOU tests |
| REM-01 | Reversible write actions support dry-run, verify, and compensation/rollback | Approval/remediation | Fault-injection E2E and rollback evidence |
| RAG-01 | Permission-aware ingestion, parsing, normalization, secret/PII scan, chunking, metadata | Knowledge/RAG | Ingestion tests; malicious file/parser tests |
| RAG-02 | Hybrid lexical/vector retrieval, metadata and ACL filtering, reranking | Knowledge/RAG | Recall/precision/MRR benchmark; cross-tenant negative tests |
| RAG-03 | Versioning, deduplication, citation, delete/re-index, retry/DLQ | Knowledge/RAG | Lifecycle and citation-correctness tests |
| SIM-01 | Reproducible incident simulator with machine-readable ground truth | Simulator/evaluation | Scenario reset/test scripts; generated evidence inspection |
| SIM-02 | At least ten fully working scenarios for final release | Simulator/evaluation | Per-scenario E2E report; deterministic rerun evidence |
| DATA-01 | Teacher generation pipeline with validation, safety, dedupe, review, lineage | Data flywheel | Dataset validation report and dataset card |
| DATA-02 | No secret/PII/unlicensed content in accepted datasets | Data flywheel | DLP/license scan; manual sample review |
| TRAIN-01 | Configurable open-weight student SFT/LoRA/QLoRA smoke pipeline | Student model | CPU/bounded-GPU smoke run; artifact/model card |
| TRAIN-02 | Preference pairs and DPO only after accepted SFT/evaluation baseline | Student model | Gate record; preference schema/quality report |
| EVAL-01 | Ground-truth, deterministic, human, and versioned LLM-judge evaluation | Simulator/evaluation | Reproducible benchmark report |
| EVAL-02 | RCA, classification, evidence, schema, hallucination, calibration metrics | Simulator/evaluation | Held-out metrics artifact |
| EVAL-03 | Tool, RAG, production, safety, latency, token, and cost metrics | Simulator/evaluation | Dashboard/report with raw run references |
| UI-01 | Login, overview, incident list/detail/timeline, live investigation | Product experience | Component/accessibility/E2E tests; rendered inspection |
| UI-02 | Evidence, tool call, approval, knowledge, evaluation, usage, cost, audit, IAM screens | Product experience | Permission-aware UI E2E matrix |
| UI-03 | Technical, responsive, accessible UI without exposing chain-of-thought or secrets | Product experience | WCAG checks; content/security review |

## Platform and Non-Functional Requirements

| ID | Requirement | Planned owner | Proof required |
|---|---|---|---|
| API-01 | Versioned `/api/v1`, OpenAPI, request validation, Problem Details | Contract foundation | Generated spec; schema/negative contract tests |
| API-02 | Cursor pagination, filtering, sorting, correlation, rate limits | Contract foundation | Contract/load tests |
| API-03 | Idempotency keys bound to request hash; optimistic concurrency via version/ETag | Contract foundation | replay/conflict/concurrency tests |
| DB-01 | PostgreSQL migrations with FK, unique constraints, query-driven indexes | Data foundation | fresh/upgrade migration tests; query-plan review |
| DB-02 | Tenant isolation with app-layer authorization and RLS defense in depth | Data foundation | RLS owner/bypass tests; pool-context leakage tests |
| DB-03 | JSONB only for schema-flexible/versioned payloads; large artifacts external | Data foundation | schema/architecture review |
| EVT-01 | Transactional outbox/inbox and versioned events before Kafka | Data foundation | atomicity, duplicate, retry tests |
| WF-01 | Durable incident workflow survives worker/process/network failure | Investigation engine | kill/restart/resume E2E evidence |
| SEC-01 | Threat model covers prompt injection, tool abuse, SSRF, injection, IDOR, tenant leakage | Security hardening | threat-model review and test linkage |
| SEC-02 | Least privilege, egress allowlist, sandbox, CSP, headers, secure cookies where used | Security hardening | configuration tests; DAST/security report |
| SEC-03 | SAST, dependency, secret, container, IaC scans and SBOM | Supply-chain pipeline | CI artifacts; zero unaccepted Critical findings |
| SEC-04 | Red-team direct/indirect injection, poisoned RAG/dataset, malicious output | Security hardening | versioned adversarial evaluation report |
| OBS-01 | End-to-end OTel trace propagation and structured redacted logs | Observability foundation | trace/log integration test |
| OBS-02 | Low-cardinality metrics for API, model, tools, RAG, incident, approval, safety | Observability foundation | metrics scrape and cardinality review |
| OBS-03 | Dashboards and alerts for health, DeepSeek, tokens/cost, tools, incidents, RAG, security | Production operations | dashboard provisioning and alert tests |
| REL-01 | Timeout, jittered retry, circuit breaker, bulkhead, rate limit, backpressure | Reliability hardening | fault-injection matrix |
| REL-02 | No blind retry for non-idempotent writes | Reliability hardening | retry-policy tests |
| REL-03 | Graceful shutdown, health/readiness/liveness, connection-pool tuning | Reliability hardening | deployment and load-test evidence |
| DR-01 | Backup, point-in-time recovery where available, restore drill, RTO/RPO | Production operations | timestamped restore-drill report |
| DEV-01 | Windows, Linux/macOS, Docker Compose local workflows | Developer experience | clean-machine/clean-clone scripts and reports |
| DEV-02 | Standard setup/dev/test/lint/build/up/down/migrate/seed/evaluate/security commands | Developer experience | command smoke matrix |
| DISK-01 | Monitor C:/D: and block heavyweight local tasks below safe thresholds | Developer experience | automation configuration; preflight tests |
| K8S-01 | Helm/Kubernetes resources, probes, limits, PDB, NetworkPolicy, service account | Production delivery | manifest validation; staging smoke test |
| K8S-02 | No plaintext secrets; external secret references and workload identity | Production delivery | manifest/secret scan |
| CI-01 | PR quality gates for Java, Python, frontend, migrations, security, build | CI/CD | protected-branch required checks |
| CI-02 | Versioned images, provenance/SBOM, release notes, staging smoke, rollback | CI/CD | signed release artifacts and rollback drill |
| GIT-01 | Small conventional commits, no force push/history destruction | Delivery governance | Git history audit |
| DOC-01 | Architecture, security, testing, evaluation, dataset, deployment, local, runbooks | Documentation | link/claim validation against code and commands |

## Definition of Done Evidence Map

| DoD | Master-prompt obligation | Authoritative evidence |
|---:|---|---|
| 1 | Build from clean clone | Fresh-directory CI job and command transcript |
| 2 | Complete local instructions | README validation on Windows and Linux runner |
| 3 | Docker Compose starts system | Compose health/smoke report |
| 4 | Authentication and RBAC work | Identity integration and authorization matrix |
| 5 | Incident CRUD works | API integration and UI E2E tests |
| 6 | DeepSeek integration works with key | Opt-in live provider smoke report, redacted |
| 7 | Missing key behavior explicit | Configuration-startup/fallback tests |
| 8 | Valid structured AI output | Schema-validity benchmark |
| 9 | Tool Gateway works | Tool contract and policy E2E suite |
| 10 | Read-only tools validated/audited | Tool execution and audit inspection |
| 11 | Approval workflow works | expiry/replay/concurrency/TOCTOU suite |
| 12 | RAG citations and permissions work | retrieval benchmark and tenant isolation tests |
| 13 | Ten simulator scenarios work | scenario-by-scenario deterministic E2E report |
| 14 | Dataset generation runs | accepted dataset artifact and card |
| 15 | Training smoke runs | bounded compute run and model card |
| 16 | Evaluation benchmark runs | versioned benchmark artifact |
| 17 | Frontend covers primary workflow | browser E2E and accessibility report |
| 18 | Unit/integration quality reasonable | coverage by risk, mutation/behavior assertions where useful |
| 19 | Complete incident E2E passes | alert-to-postmortem run artifact |
| 20 | No repository secret | full-history and working-tree secret scan |
| 21 | No unresolved Critical security issue | triaged scan/red-team report |
| 22 | Containers run non-root where applicable | runtime security-context inspection |
| 23 | Kubernetes limits/probes exist | policy-as-code manifest validation |
| 24 | CI passes | required status checks on release commit |
| 25 | README architecture diagram | rendered README/link validation |
| 26 | API documentation exists | generated OpenAPI published and tested |
| 27 | Deployment guide exists | staging deployment performed from guide |
| 28 | Security model exists | threat model mapped to controls/tests |
| 29 | Test strategy exists | documented strategy matching actual suites |
| 30 | Dataset/model card templates exist | templates plus one populated smoke artifact |
| 31 | Demo script exists | timed dry run from clean environment |
| 32 | Changelog exists | release pipeline-generated/validated changelog |
| 33 | Professional Git history | commit-size/scope audit |
| 34 | No hidden important TODO | source/doc scan and known-limitations review |
| 35 | No unimplemented completion claim | requirement-by-requirement final audit |

## Additional Completion Gates Missing From the Master Prompt

| ID | Gate | Why required |
|---|---|---|
| ADD-01 | Restore drill with explicit RTO/RPO | Backups without restore evidence do not provide recovery |
| ADD-02 | Data retention, deletion, residency, and provider privacy decision | Incident data may contain source, credentials, and customer telemetry |
| ADD-03 | Prompt/model rollback and shadow/canary procedure | Model/provider behavior changes independently of application code |
| ADD-04 | Emergency kill switch for model calls and write-capable tools | Limits blast radius during provider or policy failure |
| ADD-05 | Approval bound to action digest and resource state | Prevents stale approval and time-of-check/time-of-use attacks |
| ADD-06 | Benchmark contamination and leakage controls | Teacher/student evaluation is invalid if train/test overlap |
| ADD-07 | Cost and storage quotas per tenant | Prevents model/tool/document denial of wallet/service |
| ADD-08 | Accessibility and operator usability evidence | Incident tooling fails if responders cannot use it under pressure |

## Assumptions

- Scope mode: HOLD. Full A–Z goal retained; delivery remains gate-driven.
- Initial runtime shape: Next.js web, Spring modular control plane, Python AI runtime, isolated Tool Gateway.
- PostgreSQL plus full-text search and pgvector is the initial persistent/retrieval platform.
- Kafka, Kubernetes, dedicated RAG/evaluation services, and student serving require explicit graduation gates.
- DeepSeek V4 Flash is the default provider through an adapter; no API key is stored in this repository.
- Current local disk state prohibits Docker builds, dependency/model downloads, and large generated artifacts until C: capacity is restored.

## Unresolved Questions

- Deployment model: internal single-company platform, managed SaaS, or customer-hosted?
- Cloud/on-prem target and data residency requirements?
- Identity provider and enterprise federation requirements?
- Initial observability integrations and their authentication models?
- Expected tenant count, event rate, incident concurrency, SLO, and retention period?
- Whether production telemetry/source content may be sent to an external DeepSeek endpoint?
- Team size, delivery deadline, infrastructure budget, and GPU access?
