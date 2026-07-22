---
phase: 11
title: "Exact-action Approval and Reversible Remediation"
status: pending
priority: P1
dependencies: [3, 4, 6, 8, 9, 10]
effort: "3-5 weeks"
---

# Phase 11: Exact-action Approval and Reversible Remediation

## Objective

Introduce the first write-capable path without granting the model, browser or workflow implicit authority. The approved object must include the exact action/target/desired state, pre-approval dry-run digest, policy and resource witness, expiry and approvers. Execution must be one-effective-write under retry, use a target-side atomic precondition, reconcile ambiguous outcomes, verify the result, and compensate only inside pre-authorized safety bounds.

## Non-goals

- No destructive/irreversible production actions and no generic shell, SQL, URL, kubectl or provider command executor.
- No direct UI or AI-runtime call to Tool Gateway; platform is delegation issuer.
- No “toggle” semantics whose duplicate reverses the intended state; actions express a desired value/revision.
- No automatic compensation when current state differs from the expected post-state or compensation was not approved.
- Production may ship approval plus dry-run with execution disabled if the business risk owner chooses that release posture.

## Prerequisites and entry gate

- Phase 3 supplies real actor/workload identity, step-up/separation-of-duty data, delegated capability and transaction/inbox semantics.
- Phase 4 supplies incident/evidence/audit records and immutable evidence referenced by proposals.
- Phase 6 Spring Gateway is proven read-only and owns typed provider adapters/credentials.
- Phase 8 defines safety/evaluation gates; Phase 9 durable workflow/versioning is replay-safe; Phase 10 citations are authorization-safe.
- Exact first action providers, sandbox identities, risk classes and production write posture are approved. Unselected actions remain hard-denied.

## Design decisions and invariants

1. **Preview before approval:** Gateway dry-run observes target state and produces normalized diff, resource witness, provider precondition capability and preview digest before an approval is created.
2. **Canonical exact-action binding:** RFC 8785/JCS-style canonical JSON plus SHA-256 fixtures bind tenant, action type, exact target, desired params, evidence IDs, dry-run digest, resource witness, policy version, compensation bounds and expiry.
3. **Target-side atomicity:** the actual remote mutation must carry `If-Match`, resource version, compare-and-set or equivalent provider precondition in the same operation. A separate precheck is insufficient.
4. **One-effective-write protocol:** immutable `execution_id`, approval-consumption nonce, durable intent/lease, provider idempotency key where available, and discover/reconcile before retry.
5. **Ambiguity is a state:** response loss after possible remote commit becomes `reconcile_required`; never blind-retry a non-idempotent write.
6. **Guarded compensation:** approval binds the compensation plan. Automatic compensation uses compare-and-set against the exact expected post-state; any later mutation forces human escalation.
7. **Model-independent policy:** model confidence cannot approve, waive, expand or execute an action.
8. **Kill switch supremacy:** per-action, tenant and global write switches are rechecked immediately before durable intent claim and before remote send.

## Explicit data flow

1. Investigation creates a typed action proposal with exact desired state, target ID, cited evidence, risk class and proposed compensation boundary.
2. Platform authorizes a preview request and issues a single-purpose read/dry-run capability to Gateway.
3. Gateway obtains current target witness, performs provider-specific dry-run, normalizes the exact diff, and returns signed/digested preview metadata; no write credential is used if provider permits separation.
4. Platform canonicalizes proposal + preview + policy + witness + expiry and persists immutable approval request.
5. Qualified approver(s) inspect the same object. Platform enforces tenant/resource role, MFA/step-up where required, reason, expiry and two-person separation for Critical risk.
6. Workflow receives approval signal and asks platform to claim an execution. Platform rechecks policy/kill switch/current approval state, atomically consumes the nonce and persists an execution intent/lease.
7. Platform issues a short-lived Gateway execution capability binding `execution_id`, exact digest, target witness/precondition and allowed compensation.
8. Gateway recomputes digest, claims/deduplicates `execution_id`, and executes the desired state with target-side CAS/idempotency.
9. Gateway persists/reports provider receipt, discovers actual state, verifies outcome and returns `succeeded`, `failed_without_effect`, `reconcile_required`, or a guarded compensation outcome.
10. Workflow/platform reconcile audit/timeline/projection. Newer external state is never overwritten merely to make verification green.

## File inventory

### Create

- `packages/contracts/json-schema/remediation/action-proposal.schema.json`
- `packages/contracts/json-schema/remediation/dry-run-preview.schema.json`
- `packages/contracts/json-schema/remediation/approval-decision.schema.json`
- `packages/contracts/json-schema/remediation/execution-intent.schema.json`
- `packages/contracts/json-schema/remediation/execution-result.schema.json`
- `packages/contracts/fixtures/remediation/canonicalization/`
- `services/platform-api/src/main/resources/db/migration/V007__approval_and_remediation.sql`
- `services/platform-api/src/main/java/ai/opsmind/platform/approval/application/ApprovalService.java`
- `services/platform-api/src/main/java/ai/opsmind/platform/approval/application/ExecutionIntentService.java`
- `services/platform-api/src/main/java/ai/opsmind/platform/approval/api/ApprovalController.java`
- `services/platform-api/src/main/java/ai/opsmind/platform/remediation/application/RemediationCommandService.java`
- `services/platform-api/src/main/java/ai/opsmind/platform/remediation/security/ExecutionCapabilityIssuer.java`
- `services/platform-api/src/test/java/ai/opsmind/platform/approval/ApprovalControllerIT.java`
- `services/platform-api/src/test/java/ai/opsmind/platform/remediation/ExecutionIntentFaultIT.java`
- `services/ai-runtime/app/activities/remediation_activities.py`
- `services/tool-gateway/src/main/java/com/opsmind/toolgateway/remediation/{ActionRegistry,CanonicalActionDigest,ExecutionLedger}.java`
- `services/tool-gateway/src/main/java/com/opsmind/toolgateway/remediation/executors/{SetFeatureFlagValue,RollbackDeploymentRevision,CreatePullRequest}.java`
- `services/tool-gateway/src/test/java/com/opsmind/toolgateway/remediation/`

### Modify

- `packages/contracts/openapi/opsmind-v1.yaml` — preview, approval, decision and execution views/commands.
- `services/platform-api/pom.xml` — canonicalization/signing/policy dependencies.
- `services/ai-runtime/app/workflows/investigation_workflow.py` — versioned approval wait/reconcile branches.
- `services/tool-gateway/pom.xml` — write-provider SDKs only for enabled action profiles.
- `services/tool-gateway/src/main/resources/tool-manifests/` — explicit write manifests disabled by default.

### Delete

- None. Unsupported actions remain explicit policy denials; no generic fallback is created.

## Implementation tasks

### 11.1 Define canonical proposal and preview

- Require exact provider/action version, tenant/target, desired state, evidence IDs, risk, reversibility and compensation bounds.
- Standardize canonical JSON and digests with shared fixtures consumed by platform and Gateway; duplicate keys, Unicode ambiguity, number normalization and unknown fields fail closed.
- Dry-run output includes observed resource witness, exact normalized diff, limitations and expiry; an unverifiable preview cannot be approved.

### 11.2 Build durable approval policy

- Approval object is immutable; decisions append with actor identity, role/claims, step-up evidence, reason and timestamp.
- Optimistic locking and idempotency handle parallel approve/reject/rescind/expire attempts.
- Critical risk requires two distinct qualified humans; proposer/service/model cannot count as an approver.
- Any target/params/preview/policy/witness/expiry change creates a new approval ID.

### 11.3 Build execution intent and delegated grant

- Atomically claim approval consumption nonce and create one execution intent keyed by approval ID + canonical digest + action class.
- Use a lease with fencing token so only one Gateway worker dispatches; expired workers cannot later commit status.
- Grant binds issuer/audience, execution ID, digest, target/witness, action, credential profile, compensation bounds, nonce and short expiry.

### 11.4 Implement typed executors

- `SetFeatureFlagValue` sets an explicit desired boolean/value with expected current version, never toggles.
- `RollbackDeploymentRevision` targets a pinned revision and verifies rollout identity/health; compensation is only a pre-approved pinned transition.
- `CreatePullRequest` uses deterministic branch/request identity and provider idempotency/discovery; duplicate webhooks do not create duplicate PRs.
- Every executor supports preview, conditional execute, discover/reconcile, verify and an explicitly classified compensation path or stays disabled.

### 11.5 Handle remote uncertainty and compensation

- Persist intent before remote send and provider receipt immediately when available.
- On timeout/crash/response loss, query provider by idempotency key/desired state before deciding; ambiguous non-discoverable action remains `reconcile_required`.
- Compensation may run automatically only if approval covers it and current state equals expected post-state under CAS. Otherwise disable further writes and page a human.

### 11.6 Integrate Temporal safely

- Signals carry decision IDs only; workflow queries authoritative approval/execution projections through Activities.
- Version signal/query/Activity schemas and replay old/approval-wait golden histories before rollout.
- Activity retry delegates to intent reconciliation; it never reissues an unclaimed provider mutation.

### 11.7 Audit and containment

- Record proposal, preview, view, decisions, digest, policy, nonce claim, intent lease, remote send/receipt, discover, verify and compensation status.
- Never log credentials or raw sensitive evidence.
- Test global/tenant/action kill-switch races at intent claim and remote-send boundaries.

## Migration and rollout

- Deploy schema and read-only approval views first; all write manifests/global remediation disabled.
- Enable preview only in simulator/provider sandboxes; validate canonical fixtures and target preconditions.
- Enable approval UX with execution disabled, then one action at a time for an internal test tenant after fault tests.
- Production write enablement is a separate risk-owner release decision; dry-run-only is a valid first production posture.
- Keep old Temporal workers for compatible open histories; contract changes are additive and versioned.

## Rollback and recovery

- Disable per-action/tenant/global execution without hiding approval/audit history.
- Drain or route open workflow histories to compatible workers; do not replay external mutations after app rollback.
- Reconcile all `intent_created`, `sent` and `reconcile_required` records against target state before re-enable.
- Never delete approval/execution evidence during rollback.

## Verification matrix

| Scope | Required evidence |
|---|---|
| Canonicalization | Java/Gateway fixtures agree; Unicode/number/key-order/unknown-field attacks fail |
| Approval | expiry, optimistic conflict, replay, rescind, role, step-up and two-person separation |
| TOCTOU | change before preview, before approval, before send and during CAS; zero unintended effects |
| Crash windows | before/after intent, before send, after remote commit, before receipt, during verify/compensation |
| One-effective-write | duplicate Temporal Activity, concurrent workers and acknowledgement loss yield one effect or explicit reconciliation |
| Compensation | expected-post-state CAS success; concurrent legitimate mutation blocks compensation |
| Delegation | forged grant/digest/target/audience/nonce/expiry and credential-scope attacks denied |
| E2E | simulator proposal -> preview -> approvals -> intent -> execute -> verify plus reconcile/compensate branches |

## Quantitative exit gate

- `APR-01`, `APR-02`, `APR-03` and `REM-01` suites pass 100%; zero replay, separation, expiry, digest or stale-witness bypass.
- 100% of test writes link one approval, preview digest, canonical digest, execution ID, resource witness and provider receipt/reconciliation record.
- Every crash boundary produces one externally observed effect, confirmed no effect, or explicit `reconcile_required`; zero blind retries.
- Every enabled executor passes target-side CAS, successful verify, ambiguous-response reconciliation and concurrent-mutation compensation tests.
- Critical actions require two distinct qualified humans in all cases; kill switches prevent new sends within the declared objective.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Approval authorizes stale/different state | Preview-bound digest plus target-side CAS in the remote mutation |
| Retry duplicates/reverses action | Desired-state semantics, durable intent/lease, provider idempotency/discovery |
| Compensation overwrites a newer operator change | Approved compensation bounds plus expected-post-state CAS |
| Human rubber-stamps | Exact diff/evidence/risk/expiry, step-up and separation of duty |
| Provider lacks idempotency or preconditions | Keep action disabled or dry-run-only; never emulate unsafe guarantees with retries |

## Unresolved decisions

- Which write actions, if any, are enabled in the first production release versus approval + dry-run only.
- Provider-specific CAS/idempotency/discovery capabilities and sandbox identities for each proposed action.
