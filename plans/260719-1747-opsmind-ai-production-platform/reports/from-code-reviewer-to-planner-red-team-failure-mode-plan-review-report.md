# Red-Team Failure-Mode Plan Review

## Code Review Summary

### Scope

- Review target: `plan.md`, all 16 phase documents, and all three files under `research/`.
- Files reviewed: 20 Markdown files, 3,628 lines.
- Focus: production failure modes across durability, async ordering, idempotency, Temporal replay, provider loops, RLS, RAG lifecycle, remediation, rollout/restore, and storage exhaustion.
- Method: line-numbered scout of all target documents, dependency/gate cross-check, then adversarial failure-timeline review.
- Not run by instruction: code, build, lint, tests.
- Citation base: paths below are relative to `plans/260719-1747-opsmind-ai-production-platform/`.

### Overall Assessment

**BLOCKED.** The plan names most required invariants, but several implementation tasks and exit gates do not prove them at the failure boundaries where production breaks. Four gaps can cause cross-tenant disclosure, duplicated external writes, stale-data disclosure, or unrecoverable disagreement between Temporal and PostgreSQL. Four more can orphan work, strand workflow histories during rollout, loop or duplicate provider/tool work, and let the orchestration order violate the phase documents.

The plan should remain `pending`. Do not start implementation from the current dependency graph or claim gates G2, G5, or G7 until the changes below are incorporated.

## Critical Issues

### 1. Critical — RLS tenant context can leak through pooled connections and background jobs

**Evidence**

- Phase 3 puts tenant scope into DB session state (`phase-03-contracts-data-identity-and-tenant-foundation.md:58`) and requires scoped queries (`phase-03-contracts-data-identity-and-tenant-foundation.md:83`).
- Its task and exit gate cover tenant mismatch and owner bypass, but do not define transaction-local context lifecycle or pool-reuse coverage (`phase-03-contracts-data-identity-and-tenant-foundation.md:99`, `phase-03-contracts-data-identity-and-tenant-foundation.md:113`).
- The authoritative traceability file explicitly requires pool-context leakage tests (`research/master-prompt-requirements-traceability.md:75`). The concrete pooled-connection/background-job proof is deferred to Phase 15 (`phase-15-security-reliability-and-observability-hardening.md:79`, `phase-15-security-reliability-and-observability-hardening.md:116`), after tenant-owned tables and consumers have already shipped.

**Failure timeline**

1. Tenant A request checks out connection C1 and sets a session-scoped tenant variable.
2. Request fails or returns C1 without a guaranteed reset.
3. Tenant B request, outbox consumer, Temporal Activity, or ingestion job receives C1 before setting a valid scope, or its scope-setting statement fails.
4. RLS evaluates with Tenant A's stale session state. Tenant B reads or mutates Tenant A data; application authorization cannot repair the DB-side leak.

**Required fix**

- Make the Phase 3 contract require transaction-scoped `SET LOCAL`-equivalent context after checkout, default-deny when scope is absent, and guaranteed cleanup on commit, rollback, cancellation, and exceptions.
- Ban owner/BYPASSRLS application roles and define separate least-privilege roles for APIs, outbox jobs, workflow Activities, and retrieval jobs.
- Add alternating-tenant pool-reuse tests, failed-context-set tests, async/background-job tests, and transaction-boundary assertions to the Phase 3 exit gate. G2 must block on this evidence; Phase 15 may re-run it but cannot be its first proof.

### 2. Critical — Remediation assumes an exactly-once external write and unsafe compensation semantics

**Evidence**

- Tool Gateway executes the external action and only afterward emits execution/compensation metadata (`phase-11-exact-action-approval-and-reversible-remediation.md:63`); workflow/audit state is recorded afterward (`phase-11-exact-action-approval-and-reversible-remediation.md:64`).
- Phase 9 acknowledges duplicate Activity delivery as a required failure case (`phase-09-durable-investigation-workflow.md:122`), but Phase 11 specifies idempotency only for approval decisions (`phase-11-exact-action-approval-and-reversible-remediation.md:104`), not external execution.
- Verify failure automatically triggers compensation (`phase-11-exact-action-approval-and-reversible-remediation.md:114`, `phase-11-exact-action-approval-and-reversible-remediation.md:116`). The exit gate asks for one compensation rehearsal per action class, not crash-window or concurrent-mutation proof (`phase-11-exact-action-approval-and-reversible-remediation.md:158`).

**Failure timeline**

1. Approval and witness checks pass; a Temporal Activity calls a remote provider.
2. The provider changes the feature flag, rolls back a deployment, or creates a PR.
3. The worker crashes or loses the response before persisting the execution result.
4. Temporal retries the Activity. The external action runs again because no provider operation token/discovery protocol is required.
5. Verification fails or times out; compensation restores the captured pre-state after another operator has legitimately changed the resource, overwriting the newer change.

**Required fix**

- Persist a unique execution intent before the remote call, keyed by approval ID + canonical digest + action class, with a lease and stable provider idempotency token.
- Require every executor to support native idempotency or a deterministic discover/reconcile operation before retry. Ambiguous outcomes must enter `reconcile_required`, not blind retry.
- Capture pre- and expected post-state. Compensation must use compare-and-swap against the expected post-state and refuse to overwrite later mutations.
- Expand the Phase 11 gate with crash injection before send, after remote commit, before local record, after verify, and during compensation; include concurrent external mutation and kill-switch races. Passing means one externally observed effect or an explicitly reconciled ambiguous state, not merely one audit row.

### 3. Critical — ACL revocation, reindex, deletion, and cached citations have no atomic generation barrier

**Evidence**

- Ingestion is asynchronous across nine lifecycle states (`phase-10-permission-aware-rag-and-knowledge-lifecycle.md:53`). ACL changes and deletes enqueue new versions, tombstones, and reindex work (`phase-10-permission-aware-rag-and-knowledge-lifecycle.md:65`, `phase-10-permission-aware-rag-and-knowledge-lifecycle.md:119`).
- Physical purge is asynchronous (`phase-10-permission-aware-rag-and-knowledge-lifecycle.md:121`), while renderable snippets are materialized into citation bundles rather than reconstructed from the authoritative source (`phase-10-permission-aware-rag-and-knowledge-lifecycle.md:117`).
- The exit gate checks ten replay runs for orphan rows/stale active pointers, but not concurrent read/revoke/reindex, stale-worker resurrection, cache invalidation, or downstream snippet erasure (`phase-10-permission-aware-rag-and-knowledge-lifecycle.md:162`).

**Failure timeline**

1. A source version is active and its chunks/snippets are cached or copied into projections.
2. An administrator revokes ACL access or tombstones the source; an async event is queued.
3. A retrieval request races ahead of reindex/purge and searches the old active generation. An in-flight embedding retry can also write old-version rows after the tombstone.
4. Unauthorized text is returned and persisted in a citation bundle. Later physical deletion removes the source but not the copied snippet in workflow/UI/audit/evaluation artifacts.

**Required fix**

- Make ACL authorization synchronous against authoritative source/version state on every retrieval; tombstoned or revoked versions must become unqueryable before async cleanup.
- Add a monotonic source generation/tombstone epoch. Workers must compare-and-swap that epoch before every write; stale jobs cannot reactivate or append rows.
- Build reindexes into an inactive generation and atomically swap the active pointer only after completeness checks. Define cache keys and invalidation by tenant, ACL version, source version, and generation.
- Define deletion propagation for materialized snippets, projections, datasets, evaluation artifacts, and backups, with purge receipts or explicit retention exceptions.
- Gate Phase 10 on concurrent read/revoke/reindex/delete tests, delayed retry resurrection tests, cache tests, and citation-copy deletion tests.

### 4. Critical — Backup/restore has no consistent cut across Temporal, PostgreSQL, artifacts, and external effects; disk-full semantics are absent

**Evidence**

- Temporal owns control flow, retries, timers, and wait states (`phase-09-durable-investigation-workflow.md:50`), yet the final Compose topology calls Temporal optional (`phase-16-delivery-disaster-recovery-and-final-verification.md:92`).
- The backup classification names PostgreSQL, object artifacts, identity/config, and caches but omits Temporal namespace/history, outbox/inbox watermarks, model/dataset registry pointers, and external side-effect reconciliation (`phase-16-delivery-disaster-recovery-and-final-verification.md:106`).
- Restore validation covers schema, checksums, auth, incidents, audit, RAG, and a representative E2E, not active workflows or mid-remediation state (`phase-16-delivery-disaster-recovery-and-final-verification.md:108`).
- Disk protection is a pre-command workstation check (`phase-16-delivery-disaster-recovery-and-final-verification.md:87`). The fault matrix injects provider/DB latency and artifact-store errors but not ENOSPC, PostgreSQL WAL exhaustion, Temporal persistence exhaustion, or audit-store saturation (`phase-15-security-reliability-and-observability-hardening.md:107`). Tool execution precedes audit persistence (`phase-06-safe-tool-gateway-and-read-only-connectors.md:56`).

**Failure timeline**

1. PostgreSQL backup is taken at T1, object artifacts at T2, and Temporal history at T3 without a shared recovery fence.
2. Between T1 and T3, an approved action executes and Temporal records success, while PostgreSQL/audit is still pending or its disk is full.
3. Restore produces a DB view where the action is pending or absent but a workflow history where it completed, or vice versa.
4. Workers are enabled before reconciliation. The Activity retries the external action, or the system suppresses required compensation, while the audit trail cannot explain the real target state.

**Required fix**

- Define a system-of-record/recovery matrix covering PostgreSQL, Temporal history/visibility, object versions, outbox/inbox offsets, audit, model/dataset aliases, secrets/config references, and external actions.
- Establish a consistent-cut protocol: stop admission, fence write executors, checkpoint/drain bounded work, record watermarks/version IDs, then back up each store to a declared recovery point.
- Restore with workflow and write workers disabled. Reconcile histories, inbox/outbox, artifact hashes, approval executions, and external targets before reopening admission.
- Add ENOSPC/WAL-full/object-quota/Temporal-persistence saturation tests. If the durable intent/audit store is unavailable, no new external write may begin. Read-only degradation must state exactly which operations remain safe.
- Phase 16 must restore an active investigation and a mid-remediation case and demonstrate no duplicate effect, lost compensation, or unexplained state before G7 passes.

## High Priority

### 5. High — DB commit to Temporal start is an unclosed dual-write; event ordering/dedup is underspecified

**Evidence**

- Phase 3 claims outbox/inbox foundations (`phase-03-contracts-data-identity-and-tenant-foundation.md:45`) but inventories only an outbox module/table (`phase-03-contracts-data-identity-and-tenant-foundation.md:73`, `phase-03-contracts-data-identity-and-tenant-foundation.md:96`).
- Phase 9 first commits an investigation row plus outbox event, then separately starts Temporal (`phase-09-durable-investigation-workflow.md:59`, `phase-09-durable-investigation-workflow.md:60`).
- Its integration gate checks API idempotency, projection writes, and outbox emission, but not crash-after-commit/before-start, lost acknowledgements, out-of-order events, or consumer transactionality (`phase-09-durable-investigation-workflow.md:148`).
- The plan-level invariant promises outbox/inbox and duplicate-delivery proof (`plan.md:108`), which the phase gates do not specify.

**Failure timeline**

1. API commits `investigation_run` and its outbox row.
2. Process crashes before `StartWorkflow` reaches Temporal. The run remains `starting` forever.
3. Client retries with the same idempotency key and receives the existing row, but no workflow exists.
4. Alternatively, both the request path and an outbox dispatcher start work; an acknowledgement loss republishes the event. Without a deterministic workflow ID/inbox key, two workflows or duplicate consumers append conflicting checkpoints. Out-of-order delivery can apply `completed` before a delayed evidence event.

**Required fix**

- Make one path the sole Temporal starter, preferably an outbox dispatcher. Derive Temporal Workflow ID from immutable `investigationRunId` and define conflict/reuse policy.
- Define the versioned event envelope now: event ID, tenant, aggregate ID, aggregate sequence/version, causation/correlation, occurred-at, schema version, and payload digest.
- Add consumer inbox uniqueness on `(consumer, event_id)`, transactional inbox + local side effect, per-aggregate ordering/gap policy, poison handling, and orphan reconciliation.
- Gate Phase 3/9 on crash at every handoff, acknowledgement loss, duplicate delivery, out-of-order delivery, and reconciliation of a committed run with no Temporal execution.

### 6. High — Phase 7 to Temporal cutover and later workflow upgrades lack executable replay compatibility

**Evidence**

- Phase 7 creates `InvestigationRunController.java` (`phase-07-thin-evidence-backed-incident-vertical-slice.md:80`); Phase 9 incorrectly lists the same path as `CREATE`, not a migration/modification (`phase-09-durable-investigation-workflow.md:84`). No task migrates active Phase 7 process-manager runs.
- Phase 9 requires patch/version markers (`phase-09-durable-investigation-workflow.md:55`) and deploys a single named workflow/task queue (`phase-09-durable-investigation-workflow.md:131`, `phase-09-durable-investigation-workflow.md:133`).
- Phase 10 and Phase 11 modify the same workflow definition (`phase-10-permission-aware-rag-and-knowledge-lifecycle.md:92`, `phase-11-exact-action-approval-and-reversible-remediation.md:85`). Phase 11 rollback says compatible workers remain, but defines no worker build routing or compatibility window (`phase-11-exact-action-approval-and-reversible-remediation.md:141`).
- The Phase 9 exit gate proves worker kill/restart, not replay of histories produced by old code through rolling upgrade and rollback (`phase-09-durable-investigation-workflow.md:156`).

**Failure timeline**

1. A Phase 7 bounded process-manager run is active when Phase 9 replaces the controller/projection path. It is neither imported nor terminalized; a retry starts a second Temporal run.
2. A Phase 9 workflow remains open during Phase 10 deployment.
3. The new worker replays old history through changed retrieval branches or state schemas and throws nondeterminism/deserialization errors.
4. Rollback starts an older worker that cannot read Phase 10/11 signal or Activity payloads. The run cannot progress, cancel cleanly, or expose a reliable projection.

**Required fix**

- Add an explicit Phase 7 cutover: freeze starts, classify/finish/import active runs, map IDs/statuses, backfill projections, and prove no duplicate run.
- Use Temporal Worker Versioning/build IDs or immutable versioned task queues and workflow types; route existing histories to compatible workers and new starts to the new build.
- Version signal/query/update and Activity payloads. Define compatibility duration, continue-as-new policy, active-history inventory, and rollback routing.
- Keep a sanitized golden history corpus from each released workflow version and replay it in every Phase 9-11 build. Gate each rollout and rollback with open investigation and approval-wait histories, not only fresh test workflows.

### 7. High — Provider continuation and streaming state conflict with durable restart and loop controls

**Evidence**

- DeepSeek requires `reasoning_content` on subsequent thinking/tool-call turns (`research/researcher-01-architecture-security.md:8`).
- Phase 5 retains it only in process memory keyed by run ID (`phase-05-deepseek-ai-runtime-and-model-gateway.md:53`, `phase-05-deepseek-ai-runtime-and-model-gateway.md:67`) while Phase 9 promises survival across worker restarts (`phase-09-durable-investigation-workflow.md:22`, `phase-09-durable-investigation-workflow.md:29`).
- Sync and streaming endpoints are planned (`phase-05-deepseek-ai-runtime-and-model-gateway.md:82`, `phase-05-deepseek-ai-runtime-and-model-gateway.md:129`), but the contract matrix covers empty JSON and HTTP statuses, not truncated/duplicated/out-of-order stream frames (`phase-05-deepseek-ai-runtime-and-model-gateway.md:171`).
- Phase 7 stops only when the same tool with the same arguments repeats (`phase-07-thin-evidence-backed-incident-vertical-slice.md:123`); it does not require canonical semantic hashing or atomic budget reservation across parallel intents.

**Failure timeline**

1. Provider emits a thinking-mode tool call. Runtime stores continuation state only in memory and Tool Gateway obtains evidence.
2. Worker restarts before the follow-up provider turn; the required continuation state is gone.
3. Retry either fails provider validation or restarts the turn, requesting the same tool again and spending the budget twice.
4. A truncated SSE response or duplicated tool-call delta is assembled as a partial intent. Equivalent arguments with reordered keys/defaults evade exact duplicate detection, so the loop continues until cost/time exhaustion.

**Required fix**

- Do not make durable progress depend on run-keyed process memory. Either encapsulate a provider multi-turn exchange inside one bounded Activity and restart it as a new attempt from persisted evidence references, or approve an encrypted, access-controlled, TTL continuation artifact with explicit privacy/deletion rules.
- Require a streaming state machine with sequence handling, terminal-frame requirement, complete tool-call assembly, cancellation propagation, and no state mutation before final schema validation.
- Canonicalize normalized tool intent + scope + query window into a digest, dedupe accepted/completed intents, and reserve round/tool/token/cost budgets atomically before fan-out.
- Add tests for HTTP 200 empty body, truncated stream, duplicate/out-of-order deltas, disconnect after tool intent, crash between tool result and follow-up, semantically equivalent arguments, and concurrent intent budget races.

### 8. High — Dependency metadata and phase gates permit invalid ordering and overlapping ownership

**Evidence**

- The plan graph starts Phase 8 and Phase 9 in parallel after Phase 7 (`plan.md:75`, `plan.md:76`), while Phase 9 declares Phase 8 as a dependency (`phase-09-durable-investigation-workflow.md:6`) and says its thresholds must come from Phase 8 calibration (`phase-09-durable-investigation-workflow.md:176`).
- The plan graph gives Phase 13 dependencies on 5/8/9/10 only (`plan.md:85`, `plan.md:88`), while Phase 13 declares a Phase 12 dependency (`phase-13-dataset-flywheel-and-governance.md:6`). Phase 12 owns all `apps/web/**` (`phase-12-operator-web-experience-completion.md:69`), and Phase 13 also creates web files (`phase-13-dataset-flywheel-and-governance.md:53`).
- Phases 1-12 use frontmatter key `dependencies`; Phases 13-16 switch to `depends_on` (`phase-12-operator-web-experience-completion.md:6`, `phase-13-dataset-flywheel-and-governance.md:6`). An orchestrator that recognizes one schema can silently treat later phases as unblocked.

**Failure timeline**

1. Scheduler follows `plan.md` or parses only `dependencies`.
2. Phase 9 starts before Phase 8 has calibrated no-progress/budget thresholds, locking workflow behavior around guesses and forcing replay-sensitive changes later.
3. Phase 13 starts before or alongside Phase 12. Both modify `apps/web/**`; generated clients/routes and ownership assumptions diverge or overwrite each other.
4. A later phase reports its own exit gate green even though the upstream evidence artifact it relies on never existed.

**Required fix**

- Choose one canonical dependency key and generate the Mermaid graph from it. Add a validator that rejects graph/frontmatter divergence, unknown phase IDs, cycles, and shared-file ownership overlap.
- Add edges `P8 -> P9` and `P12 -> P13`, or explicitly remove the corresponding prerequisites/files after redesign.
- Make entry gates verify immutable upstream evidence-manifest IDs and contract versions, not status text alone. A phase must fail closed when its named artifact or compatible schema is absent.

## Medium Priority

No separate Medium findings. Lower-severity wording/style issues were intentionally omitted.

## Edge Cases Found by Scout

- Connection returned to pool after exception, cancellation, or failed tenant-context initialization.
- Crash after DB commit but before Temporal start; duplicate/out-of-order outbox delivery.
- Activity success followed by lost acknowledgement; retry after externally committed remediation.
- Old Temporal history replayed by Phase 10/11 worker code or by rolled-back workers.
- Provider HTTP 200 with empty/truncated stream, duplicate tool chunks, or lost thinking continuation.
- ACL revocation racing retrieval, reindex, cache use, stale embedding retry, and physical purge.
- Compensation racing a legitimate external mutation after the approved action.
- Restore of skewed PostgreSQL/Temporal/object-store points and restart before reconciliation.
- ENOSPC/WAL-full after external side effect but before audit/projection persistence.

## Positive Observations

The plan correctly names forced RLS, outbox/inbox, Temporal determinism, exact-action approval, tombstones, restore drills, and fail-visible degradation as invariants. This lowers design ambiguity, but the current tasks and exit gates do not yet prove those invariants at the crash boundaries above.

## Recommended Actions

1. Amend G2/Phase 3 first: make pooled RLS lifecycle and full event/inbox semantics blocking foundations.
2. Define the DB-to-Temporal start protocol and Phase 7-to-9 cutover before any durable workflow implementation.
3. Add worker/build versioning and cross-version replay/rollback gates before Phase 10 or 11 can modify workflow code.
4. Specify action execution/reconciliation/compensation as a durable state machine before enabling any write executor.
5. Specify RAG generation epochs, immediate revocation, and deletion propagation before ingestion starts.
6. Expand Phase 15/16 fault and restore gates for ENOSPC and consistent multi-store recovery with active workflows.
7. Normalize dependency metadata and generate/validate the graph before assigning parallel work.

## Dependency and Exit-Gate Verdict

- Dependency ordering: **FAIL** — graph contradicts phase metadata for Phase 8 -> 9 and Phase 12 -> 13; dependency-key schema changes mid-plan.
- G2 Trust/data base: **INSUFFICIENT** — pooled RLS and inbox/consumer semantics are not Phase 3 blockers.
- G5 Durable/safe actions: **INSUFFICIENT** — no rollout replay corpus, external-effect idempotency protocol, or crash-safe compensation proof.
- G6 Knowledge lifecycle: **INSUFFICIENT** — no immediate revocation/generation barrier or downstream snippet-deletion proof.
- G7 Production readiness: **INSUFFICIENT** — restore is not a consistent multi-store recovery test and storage-full behavior is unspecified.

## Metrics

- Type coverage: N/A — plan-only review.
- Test coverage: N/A — no implementation exists in review scope.
- Linting issues: N/A — lint/build explicitly out of scope.
- Findings: 8 accepted — 4 Critical, 4 High, 0 Medium.

## Unresolved Questions

- Is Temporal Cloud or self-hosted Temporal the production target, and what history/visibility backup and restore guarantees are available?
- What deletion SLA applies to citation snippets and derived copies in workflow projections, audit, evaluation, datasets, and backups?
- Will first production release enable real remediation writes or ship approval + dry-run only?
- Which deployment model and data-class RTO/RPO define the required consistency fence?

Status: DONE

Summary: Reviewed all requested plan/research files. Plan is blocked by four Critical and four High failure-mode gaps; concrete gate and ordering corrections are above.

Concerns/Blockers: No implementation evidence exists by design. Production target, Temporal deployment, deletion guarantees, and RTO/RPO remain unresolved and affect the final recovery protocol.
