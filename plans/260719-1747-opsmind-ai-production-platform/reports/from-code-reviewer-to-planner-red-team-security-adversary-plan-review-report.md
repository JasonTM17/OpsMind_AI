# Red-Team Security Adversary Plan Review

## Scope

- Reviewed: `plan.md`, phases 01-16, all three files under `research/`, plus `reports/brainstorm-report.md` for decision context.
- Lens: trust boundaries, tenant isolation, prompt/tool/RAG/dataset abuse, approval replay/TOCTOU, data leakage, delivery and DR.
- Method: scout cross-file boundary claims first; two-pass adversarial review second. Greenfield plan review only; no build, lint, or code execution.
- Verdict: **BLOCKED — 8 accepted findings (2 Critical, 6 High).** Resolve in the plan before implementation.

## Critical Findings

### 1. Approval witness and dry-run are check-then-act, not an atomic precondition

**Evidence:** Phase 11 specifies a workflow-side witness/policy recheck before requesting execution, then has the Gateway perform dry-run and execution as later steps (`phase-11-exact-action-approval-and-reversible-remediation.md:51-63`). Phase 12 says the approver sees a dry-run before submitting the decision (`phase-12-operator-web-experience-completion.md:54`, `:63`, `:115-117`), contradicting Phase 11's post-approval dry-run order. No step requires the external mutation itself to be a conditional compare-and-set against the approved witness.

**Attack/failure sequence:** approver reviews preview for resource version N -> another actor changes target to N+1 -> workflow's separate recheck/dry-run completes -> target changes again or preview is stale -> Gateway executes an unconditional mutation against state the approver never saw. Feature flags, deployment revisions, or PR state can therefore differ from the approved object.

**Impact:** a valid approval authorizes a materially different production action; exact-action guarantee and `INV-03` fail.

**Required correction:** define one ordering and bind approval to a digest of action type, tenant, exact target, normalized parameters, dry-run output, policy version, and resource witness. Require executor-side conditional mutation (`If-Match`/CAS/provider precondition) in the same remote operation; mismatch must fail without side effect.

### 2. Temporal retry can execute the same approved write more than once

**Evidence:** remediation side effects run in Temporal Activities (`phase-11-exact-action-approval-and-reversible-remediation.md:85-93`, `:114-120`), while the action set includes non-idempotent or externally duplicating operations such as feature-flag "toggle" and PR creation (`:54`, `:89-91`). Planned proposal/decision/execution schemas name no stable execution nonce or provider idempotency key (`:76-79`). The earlier Gateway request contract also omits idempotency (`phase-06-safe-tool-gateway-and-read-only-connectors.md:131-137`). This conflicts with the research gate that writes must be idempotent or blocked (`research/researcher-02-delivery-evaluation.md:110-112`) and the Gateway must enforce idempotency (`research/researcher-01-architecture-security.md:141-143`).

**Attack/failure sequence:** external write succeeds -> response/audit callback is lost -> Temporal retries the Activity -> still-valid approval/digest passes again -> toggle reverses itself, rollback is applied twice, or duplicate PRs/webhooks are created.

**Impact:** duplicate or reversed production changes with an apparently valid audit chain.

**Required correction:** add immutable `execution_id`/approval-consumption nonce to every contract; atomically claim it before dispatch; pass it to providers with native idempotency/CAS where available; persist outcome receipts; treat ambiguous non-idempotent outcomes as reconciliation-required and never blind-retry. Express flag actions as `set desired value`, not `toggle`.

## High Findings

### 3. Workload identity is not bound to delegated actor/tenant authority

**Evidence:** the AI endpoint accepts requests from the platform API or a local caller and validates caller-supplied tenant/run/context fields, but its contract contains no authenticated workload identity, audience, or delegated principal (`phase-05-deepseek-ai-runtime-and-model-gateway.md:48-55`, `:119-143`). The Gateway verifies an allowed service signature while also accepting caller-supplied `actor_subject` and scopes (`phase-06-safe-tool-gateway-and-read-only-connectors.md:48-55`, `:131-137`); whether the AI runtime may sign is unresolved (`:207-211`). Zero-trust workload identity is deferred to Phase 15 (`phase-15-security-reliability-and-observability-hardening.md:32-33`, `:73-79`).

**Attack/failure sequence:** an internal attacker, compromised AI runtime, or prompt-controlled orchestration submits victim `tenant_id` plus an admin `actor_subject` -> service signature authenticates only the workload -> Gateway evaluates permissions for the asserted actor -> privileged connector reads or later writes execute as the victim.

**Impact:** confused-deputy privilege escalation and cross-tenant evidence access.

**Required correction:** make platform API the sole delegation issuer. Use short-lived signed capabilities binding issuer, audience, subject, tenant/project/environment, incident/run, exact resources/actions, budgets, nonce, and expiry. Gateway must derive actor/scope only from the verified capability, never request fields.

### 4. RAG gives the AI runtime direct ACL and database authority without a hard enforcement boundary

**Evidence:** the plan says platform API owns ACL authority but AI runtime writes chunks/index rows and performs retrieval (`phase-10-permission-aware-rag-and-knowledge-lifecycle.md:44`, `:48-55`, `:57-65`, `:87-94`). Retrieval requests merely carry tenant/project scope and allowed selectors (`:111-113`). No Phase 10 requirement binds those selectors to a control-plane authorization decision or forces RLS on knowledge/vector tables; pooled transaction context proof appears only in Phase 15 (`phase-15-security-reliability-and-observability-hardening.md:79`).

**Attack/failure sequence:** forged internal request or compromised parser/model worker changes `tenant_id`/allowed selectors -> AI runtime's database role queries victim chunks before ranking -> citations/snippets are returned or sent to DeepSeek. Application-level ACL tests do not constrain a broadly privileged DB credential.

**Impact:** cross-tenant document, source, and incident leakage through vector or lexical retrieval.

**Required correction:** resolve ACLs in the control plane and issue a signed per-query capability, or expose retrieval through an authoritative service. Apply forced RLS to catalog/chunk/vector tables with a non-owner role and transaction-local tenant context; join ACL state inside the query and test compromised-worker/forged-selector cases.

### 5. External provider egress is enabled before data-export policy is enforceable

**Evidence:** AI runtime sends incident context to DeepSeek and records provider results (`phase-05-deepseek-ai-runtime-and-model-gateway.md:48-59`); phase exit requires a live provider call when staging credentials exist (`:147-149`, `:177-184`). Yet whether production telemetry/source may leave the organization remains unresolved in both the phase and master plan (`:196-200`; `plan.md:203-208`). The accepted security position says sensitive GenAI telemetry must be opt-in and redacted (`reports/brainstorm-report.md:178-187`), but Phase 5 defines no outbound content classification/purpose/residency gate.

**Attack/failure sequence:** a log, source excerpt, runbook, or incident field contains a credential/PII -> it enters model context as evidence -> presence of a configured provider key enables the call -> prohibited data leaves the organization before tenant consent or residency policy is checked.

**Impact:** credential/PII disclosure to an external processor and residency/contract breach.

**Required correction:** make external-provider mode fail closed per tenant and data class until an owned policy decision exists. Add outbound DLP/classification, purpose/consent, provider/region allowlist, retention contract, and test canaries at the provider request boundary; credentials alone must never enable egress.

### 6. Runtime, contract, and migration authority forks into parallel implementations

**Evidence:** the locked topology calls for a Spring Tool Gateway (`plan.md:23`, `:31`) and Phase 2 creates its Maven build (`phase-02-monorepo-and-developer-platform-foundation.md:73-75`), but Phase 6 replaces it with a Python/FastAPI tree (`phase-06-safe-tool-gateway-and-read-only-connectors.md:87-119`). Phase 3 forbids duplicate contract sources and declares `packages/api-contracts/**` plus `packages/shared-schemas/**` authoritative (`phase-03-contracts-data-identity-and-tenant-foundation.md:23`, `:89-100`), while later phases create `contracts/ai-runtime/**`, `contracts/tool-gateway/**`, `contracts/investigation-orchestration/**`, and `packages/contracts/**` (`phase-05-deepseek-ai-runtime-and-model-gateway.md:101-104`; `phase-06-safe-tool-gateway-and-read-only-connectors.md:113-116`; `phase-07-thin-evidence-backed-incident-vertical-slice.md:88-89`; `phase-09-durable-investigation-workflow.md:77-81`).

**Attack/failure sequence:** CI/build/deployment follows the Spring/Maven and authoritative-package plan while policy code and schemas land in Python/alternate trees -> the deployed Gateway lacks the reviewed filters or validates a different schema -> attacker sends fields accepted by one contract but not covered by the security suite/migration path.

**Impact:** security controls can be tested in one implementation and absent from the artifact actually deployed; DB/RLS migrations can also be skipped or ordered differently.

**Required correction:** amend ADR-0001 before Phase 2: choose one Gateway runtime/build, one canonical contract tree/generator, and one migration owner/order. Rewrite every phase inventory and CI/release path to that decision; add a gate rejecting duplicate schemas, endpoints, and migrations.

### 7. DR omits Temporal history and cross-store recovery consistency

**Evidence:** Temporal owns durable run state, retries, approval waits, and control flow (`phase-09-durable-investigation-workflow.md:20-30`, `:48-65`). Phase 16 classifies PostgreSQL, object artifacts, identity/config, and caches for backup but does not include Temporal persistence/history (`phase-16-delivery-disaster-recovery-and-final-verification.md:104-109`); restore validation checks API data and citations but not active workflow/approval replay (`:108-109`, `:143-150`).

**Attack/failure sequence:** disaster occurs after approval or remote execution but before all projections/audit callbacks converge -> PostgreSQL/object storage restore to one point while Temporal is absent or at another -> workflows are recreated, orphaned, or replayed -> an approved action can execute again, or audit/projection state falsely reports its outcome.

**Impact:** duplicate remediation, lost human-wait state, and an unreconstructable audit trail after recovery.

**Required correction:** add Temporal namespace/history/config/encryption-key recovery (or documented Temporal Cloud guarantees), coordinated recovery points, admission freeze, and deterministic reconciliation across Temporal, Postgres, object storage, outbox, and external side effects. Drill active, paused, approved-not-run, and executed-but-unacknowledged workflows.

### 8. Dataset withdrawal does not revoke immutable snapshots or derived models

**Evidence:** published datasets are immutable and stored externally (`phase-13-dataset-flywheel-and-governance.md:31-40`); consumers pin immutable snapshot IDs (`:94-100`). Tenant deletion creates a new redacted snapshot/tombstone rather than modifying the old artifact (`:101-105`), and rollback merely repoints consumers (`:131-136`). Phase 14 trains from pinned snapshots and has no lineage-triggered invalidation/retraining rule when a source example is withdrawn (`phase-14-student-model-training-shadow-and-promotion.md:24-40`, `:131-136`).

**Attack/failure sequence:** tenant withdraws an incident -> new snapshot excludes it -> stale or malicious job still resolves the old immutable snapshot ID, or an already trained student retains the example -> deleted content remains downloadable or inferable from the deployed model.

**Impact:** retention/deletion promises fail across artifacts and model lineage; sensitive tenant data survives withdrawal.

**Required correction:** make authorization reject withdrawn snapshot IDs, revoke URLs/keys, define physical purge or cryptographic erasure, and propagate lineage tombstones to every training run/model. Affected models must be quarantined and retrained or receive an explicit approved exception before routing.

## Scout Findings Applied

- Boundary dependents traced across platform API -> AI runtime -> Tool Gateway -> Temporal -> PostgreSQL/pgvector -> object store/provider.
- Contradictions verified for Gateway language/build, contract roots, migration roots, dry-run ordering, and durable-state backup ownership.
- Suppressed: generic style, speculative compliance frameworks, and concerns already blocked by the Phase 16 no-production gate.

## Metrics

- Type coverage: N/A (greenfield plan)
- Test coverage: N/A (greenfield plan)
- Lint/build issues: not run by task constraint
- Findings: 8 accepted; 2 Critical, 6 High, 0 Medium

## Recommended Actions

1. Rewrite Phase 11 contracts and flow for atomic witness enforcement and exactly-once-effective execution.
2. Lock workload/delegation identity and RAG authorization boundaries before Phases 3-6.
3. Reconcile ADR/runtime/contract/migration ownership before repository bootstrap.
4. Add provider egress policy, Temporal-consistent DR, and lineage-complete deletion gates.
5. Re-run hostile plan review before implementation begins.

## Unresolved Questions

- Is the Tool Gateway Spring or Python?
- Is AI runtime ever an authorized direct Gateway caller, or must all calls carry a platform-issued delegated capability?
- Which component owns canonical authorization for retrieval and the forced-RLS database session?
- What is the authoritative recovery model for Temporal Cloud/self-hosted Temporal and external side effects?

Status: DONE_WITH_CONCERNS

Summary: Eight evidence-backed plan blockers found; two can cause stale or duplicate production writes, six undermine tenant/data/runtime/DR boundaries.

Blockers: plan must be revised and re-reviewed before implementation.
