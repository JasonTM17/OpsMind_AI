# OpsMind AI Architecture and Security Recommendation

Date: 2026-07-19

## Findings

1. `DeepSeek-V4-Flash` is a real current model, not a placeholder. DeepSeek's docs say it supports both thinking and non-thinking modes, JSON output, and tool calls, and legacy `deepseek-chat` / `deepseek-reasoner` are deprecated on `2026-07-24 15:59 UTC`. The adapter must treat model names as configuration, not code.
2. DeepSeek thinking mode supports tool calls, but `reasoning_content` must be passed back on subsequent tool-call turns. JSON output can return empty content, so structured output needs app-side validation, not blind trust in the provider.
3. Temporal is the right durability layer for human-in-the-loop investigation and remediation. Workflows are deterministic, Activities hold non-deterministic work, and Signals / Queries / Updates are the intended approval and state-control interface.
4. Spring Modulith is a better fit than early microservices for the Java control plane. Spring Boot itself recommends Spring Modulith when you want domain-based structure, and Modulith adds structural validation, module-level integration tests, runtime observation, and event-driven module decoupling.
5. PostgreSQL row-level security is usable for tenant isolation, but only if it is enforced correctly. RLS must be enabled per table, default-deny applies when enabled with no policy, and table owners bypass RLS unless `FORCE ROW LEVEL SECURITY` is set.
6. pgvector is a good first vector store for this platform because it keeps embeddings with the rest of the relational data and supports ANN search plus Postgres ACID/joins. That matches the project's preference for PostgreSQL/pgvector and reduces system sprawl.
7. OWASP and NIST both point to the same risk shape: prompt injection, insecure output handling, tool abuse, supply-chain issues, provenance, and lifecycle risk management. The platform must assume all model output is untrusted.

## Recommendation

### Ranked choice

1. **Recommended: evolutionary modular architecture**
   - Spring Boot control plane built as a Modulith-style modular monolith.
   - Separate Python AI runtime for DeepSeek-facing analysis, RAG, evaluation, and dataset generation.
   - Separate Tool Gateway process for all external actions and sensitive read/write operations.
   - Temporal for durable investigation, HITL approvals, retries, timers, and remediation orchestration.
   - PostgreSQL as system of record, with pgvector for embeddings and RLS for tenant isolation.
2. **Rejected: microservices-first**
   - Too much distributed-systems overhead for a greenfield product.
   - Premature service boundaries would split incident, approval, audit, and provenance data before boundaries are proven.
   - Higher operational risk: more failure modes, more authn/authz surfaces, harder local development, slower iteration.
3. **Rejected: single-process everything**
   - Wrong trust model for AI and tools.
   - AI runtime and Tool Gateway need separate blast radii and separate credential sets.
   - You would end up with one large process that is hard to secure, hard to observe, and hard to scale independently.

### Why this wins

- Best fit for the current stack: Java for control plane, Python for AI, Postgres for source of truth, Temporal for durable orchestration.
- Lowest operational cost that still respects trust boundaries.
- Easiest path to evolve into more services later if load or organizational boundaries force it.
- Strongest security posture without over-committing to distributed architecture too early.

## Trade-off Matrix

| Option | Complexity | Ops cost | Maintainability | Security fit | Fit for this project |
|---|---:|---:|---:|---:|---|
| Modular control plane + Python AI runtime + Tool Gateway | Medium | Medium | High | High | Best |
| Microservices-first | High | High | Medium/Low | Medium | Poor early fit |
| Single monolith | Low upfront | Low upfront | Medium/Low over time | Low | Insufficient trust separation |

## Bounded Context / Data Ownership Map

| Context | System of record | Owns | Boundary rule |
|---|---|---|---|
| Identity and tenancy | PostgreSQL | org, project, environment, roles, permissions, service accounts, policy grants | Every query must carry tenant/project scope; no cross-tenant reads by default |
| Incident management | PostgreSQL | incident, severity, alert links, evidence, hypotheses, root cause, timeline, remediation, postmortem | Incident graph is append-heavy and auditable; updates are commands, not ad hoc edits |
| Durable workflow | Temporal + PostgreSQL projections | workflow instance, timers, retries, approval wait states, execution history | Workflow code stays deterministic; side effects live in Activities |
| AI analysis | Python runtime + PostgreSQL audit/projections | model invocation, prompt version, reasoning metadata, confidence, token usage, evaluation traces | Model output is advisory until validated and attached to evidence |
| Tool execution | Tool Gateway + PostgreSQL audit ledger | tool registry, risk class, permission checks, execution results, idempotency keys | No direct model-to-prod access; all writes require policy enforcement |
| RAG / provenance | PostgreSQL + pgvector | source inventory, ACLs, chunks, embeddings, retrieval results, citations | Retrieval must respect source ACL and document version; citations are mandatory |

## Design-Pattern Map

| Pattern | Use it for | Why it matters |
|---|---|---|
| Modulith / modular monolith | Java control plane | Enforces boundaries without distributed complexity |
| Ports and adapters | DeepSeek, tools, storage, observability | Keeps provider churn and infrastructure out of domain code |
| Outbox / transactional event publication | module-to-module and module-to-broker events | Prevents lost domain events and supports retries |
| Append-only audit ledger | approvals, tool calls, model decisions, evidence | Makes incident reconstruction possible |
| Temporal workflows | long-running investigations and HITL remediation | Durable state, replay, retries, signals, timers |
| Signal / Query / Update separation | approval, read state, controlled writes | Clear semantics for human approval and workflow control |
| Optimistic locking | incident and approval state | Prevents double-apply and concurrent approval races |
| RLS + tenant claims | tenant isolation | DB-enforced backstop against app bugs |
| Schema-validated tool contracts | all external tools | Stops tool-argument drift and malicious model output |

### Anti-patterns to avoid

- Direct model calls to databases, shells, or cloud APIs.
- One generic `ai-service` that mixes inference, tools, retrieval, and approvals.
- Shared mutable tables with tenant filters only in application code.
- Storing raw model output as truth without validation.
- Treating prompt text as trusted input.
- Using the database owner role for the application runtime.
- Early decomposition into many services before boundaries are proven.

## Security Invariants and Failure Modes

1. **All tool calls go through the Tool Gateway**
   - Invariant: the model never gets direct network, shell, DB-owner, or cluster-admin power.
   - Failure mode: prompt injection or model hallucination turns into real execution if the gateway is bypassed.
2. **All model output is untrusted**
   - Invariant: JSON, tool args, recommendations, and citations are validated before use.
   - Failure mode: insecure output handling becomes code execution, privilege escalation, or data leakage.
3. **Tenant isolation is enforced in the database**
   - Invariant: RLS is enabled on tenant-scoped tables, and app roles do not bypass RLS.
   - Failure mode: a bug in query construction becomes cross-tenant leakage.
4. **Approval is durable and replay-safe**
   - Invariant: approval state is stored in a workflow + audited ledger, not in an ephemeral request thread.
   - Failure mode: duplicate approvals, expired approvals, or human races cause unsafe remediation.
5. **Retrieval respects provenance and ACL**
   - Invariant: chunk metadata stores source, version, tenant, project, and permission state.
   - Failure mode: stale embeddings or shared indexes leak content across projects.
6. **Provider behavior is abstracted**
   - Invariant: DeepSeek model names, limits, and beta features are config, not assumptions.
   - Failure mode: legacy model names break after deprecation; unsupported JSON/tool behavior silently corrupts workflows.

## DeepSeek Adapter Requirements

- Externalize `base_url`, `api_key`, `model`, and thinking mode.
- Support both `deepseek-v4-flash` and `deepseek-v4-pro`.
- Treat `deepseek-chat` / `deepseek-reasoner` as migration-only names until `2026-07-24`.
- Validate structured output even when JSON mode is enabled.
- Validate tool schemas server-side and client-side.
- Preserve `reasoning_content` only where necessary; keep it out of broad logs and analytics.
- Handle `429` rate limit, `402` insufficient balance, `500`, and `503` with explicit mapping and backoff.
- Respect concurrency ceilings: Flash `2500`, Pro `500`.

## Workflow Semantics

- Use Temporal Workflow for the incident lifecycle.
- Use Activities for model calls, tool execution, retrieval, parsing, and remediation side effects.
- Use Signals for human approvals and external state changes.
- Use Queries for UI reads of workflow state.
- Use Updates only when synchronous validated write semantics are needed.
- Persist every decision point to the audit ledger with incident ID, workflow ID, tenant ID, and actor identity.

## Phase Implications

### Phase 0
- Lock bounded contexts, database ownership, tenant claims, and event schema before code.
- Decide the approval policy matrix now, especially destructive vs reversible operations.
- Define the audit ledger and provenance schema before any AI logic ships.

### Phase 1
- Build the Spring Modulith control plane first, not a service zoo.
- Add RLS, migrations, and audit tables early.
- Put DeepSeek, tool, and retrieval integrations behind ports.

### Phase 2
- Add the Python AI runtime as a separate process boundary.
- Keep it stateless except for workflow/state access through persisted contracts.

### Phase 3
- Introduce the Tool Gateway as the only place that can talk to privileged external systems.
- Enforce risk class, timeout, idempotency key, and approval requirements there.

### Phase 4
- Add RAG ingestion and retrieval with ACL filtering and citations.
- Do not expose retrieval results without provenance metadata.

### Phase 5
- Introduce evaluation early, before broad feature expansion.
- Use evaluation to decide when a module should remain internal or split out.

## Adoption Risk

- **Spring Modulith / Spring Boot:** low risk, mature ecosystem, good long-term support.
- **Temporal:** medium risk, but strong fit for durable workflows and HITL. Main risk is workflow determinism discipline.
- **PostgreSQL + RLS + pgvector:** low to medium risk, mature core tech. Main risk is misconfigured ownership or RLS bypass.
- **DeepSeek provider:** medium risk because model/API behavior can move. The adapter must absorb change.
- **Python AI runtime:** medium risk because fast iteration is a strength, but it needs strict contract testing to avoid drift.

## Source URLs

- DeepSeek models, thinking mode, JSON output, tool calls, rate limits, and deprecations: <https://api-docs.deepseek.com/quick_start/pricing/>, <https://api-docs.deepseek.com/guides/thinking_mode/>, <https://api-docs.deepseek.com/guides/json_mode/>, <https://api-docs.deepseek.com/api/create-chat-completion/>, <https://api-docs.deepseek.com/quick_start/rate_limit/>, <https://api-docs.deepseek.com/updates>, <https://api-docs.deepseek.com/quick_start/error_codes/>
- Spring Modulith and Spring Boot structure guidance: <https://spring.io/projects/spring-modulith/>, <https://docs.spring.io/spring-modulith/reference/index.html>, <https://docs.spring.io/spring-modulith/reference/fundamentals.html>, <https://docs.spring.io/spring-modulith/reference/events.html>, <https://docs.spring.io/spring-modulith/reference/testing.html>, <https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html>, <https://docs.spring.io/spring-boot/reference/features/external-config.html>
- Temporal durable workflows and human approval: <https://docs.temporal.io/encyclopedia/temporal-sdks>, <https://docs.temporal.io/encyclopedia/event-history/event-history-java>, <https://docs.temporal.io/ai-cookbook/human-in-the-loop-python>, <https://docs.temporal.io/encyclopedia/workflow-message-passing>, <https://docs.temporal.io/sending-messages>, <https://docs.temporal.io/handling-messages>, <https://docs.temporal.io/design-patterns/delayed-callback>
- PostgreSQL RLS: <https://www.postgresql.org/docs/current/ddl-rowsecurity.html>, <https://www.postgresql.org/docs/current/sql-createpolicy.html>, <https://www.postgresql.org/docs/current/sql-altertable.html>
- pgvector: <https://github.com/pgvector/pgvector>
- OWASP and NIST AI security: <https://genai.owasp.org/resource/agentic-ai-threats-and-mitigations/>, <https://owasp.org/www-project-top-10-for-large-language-model-applications/>, <https://genai.owasp.org/llmrisk/llm01-prompt-injection/>, <https://genai.owasp.org/llmrisk2023-24/llm02-insecure-output-handling/>, <https://www.nist.gov/itl/ai-risk-management-framework>, <https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.600-1.pdf>, <https://nvlpubs.nist.gov/nistpubs/ai/nist.ai.100-1.pdf>

## Limitations

- No repository implementation existed yet, so this is an architecture and security recommendation, not a code review.
- I did not benchmark Temporal, DeepSeek, or pgvector in this environment.
- I did not validate the exact final deployment topology against actual infra code because none exists yet.

## Unresolved Questions

- Should the Python AI runtime own RAG end-to-end, or should RAG become a separate service once ingestion volume grows?
- Which approval classes are reversible-write vs destructive, and which require two-person approval from day one?
- Do we want append-only audit rows only, or append-only plus hash chaining for tamper evidence?
- Will the first release use Temporal Cloud, self-hosted Temporal, or a deferred workflow engine decision?
