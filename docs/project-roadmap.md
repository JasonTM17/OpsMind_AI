# OpsMind AI Project Roadmap

## Delivery Model

The roadmap uses sequential evidence gates. A later phase can begin only when its dependencies are actually proven. Parallel work is allowed only where the plan dependency graph permits it and file ownership does not overlap.

The detailed executable plan is [plans/260719-1747-opsmind-ai-production-platform/plan.md](../plans/260719-1747-opsmind-ai-production-platform/plan.md).

## Gate Summary

| Gate | Meaning | Required proof |
|---|---|---|
| G0.5 | Product/production contract approved | Machine-readable decisions plus accountable approval |
| G1 | Developer platform repeatable | Storage preflight, pinned tools, CI scaffold, secret-free bootstrap |
| G2 | Identity/data/contracts isolated | OIDC conformance, forced RLS, migrations, contract tests |
| G3 | Thin live investigation slice works | Synthetic live connector, evidence, AI, UI, audit, cost trace |
| G4 | Durable workflow safe | Crash/replay/versioning, bounded provider continuation, and exact-workflow ambiguity reconciliation |
| G5 | RAG lifecycle safe | ACL-before-ranking, provenance, revoke/delete receipts |
| G6 | Exact action safe | Bound approval, CAS, idempotency, ambiguity reconciliation |
| G7 | Product/evaluation usable | Operator UX, held-out evaluation, human baseline |
| G8 | Production ready | Security, reliability, DR, runbooks, delivery and final DoD audit |

## Phases

| Phase | Outcome | Main gate contribution | Status |
|---:|---|---|---|
| 1 | Operating envelope and architecture governance | G0.5/G1 | Completed; strict contract gate passed |
| 2 | Monorepo and developer platform foundation | G1 | Completed; clean Ubuntu/Windows bootstrap and Compose evidence passed |
| 3 | Contracts, data, identity, and tenant foundation | G2 | In progress |
| 4 | Incident control plane, evidence lifecycle, and audit | G2/G3 | In progress; checkpoint 4A local proof complete |
| 5 | DeepSeek AI runtime and provider gateway | G3 | In progress; static checkpoint passed, exit gate blocked |
| 6 | Safe Tool Gateway and read-only connectors | G3 | In progress; B-016 tenant isolation resolved by immutable CI, broader Phase 6 exit remains BLOCK |
| 7 | Evidence-backed incident vertical slice | G3 | In progress; metadata activity route and V009 CI fixture gates pass; external G3 blockers remain |
| 8 | Simulator and evaluation baseline | A-Z G4 / roadmap G7 | In progress; Phase 8B complete, held-out/human/calibration evidence unavailable, exit BLOCK |
| 9 | Durable Temporal investigation workflow | G4 | In progress; default-off start handoff and V012 containment source exist, but B-017 blocks admission pending full fresh/upgrade real-role, atomicity, predecessor-latency, and no-Start reconciliation/alert proof; B-013 remains active |
| 10 | Permission-aware RAG and knowledge lifecycle | G5 | Pending |
| 11 | Exact-action approval and reversible remediation | G6 | Pending |
| 12 | Operator web experience completion | G7 | Pending |
| 13 | Dataset flywheel and governance | G7 | Pending |
| 14 | Student training, shadow, and promotion | G7; promotion conditional | Pending |
| 15 | Security, reliability, and observability hardening | G8 | Pending |
| 16 | Delivery, disaster recovery, and final verification | G8 | Pending |

The delivery foundation now includes a SHA-pinned four-image OCI publication
workflow with manual main-only authorization, exact-revision CI gates,
multi-architecture build-once candidates, vulnerability/secret/license scans,
runtime health probes, SBOM, max-level provenance, protected aggregate
promotion, signed registry attestations, GHCR linkage/visibility checks, digest
verification, and release-set receipts. This is an implementation checkpoint,
not Phase 16 completion: immutable registry execution evidence, Docker Hub
credential/parity proof, production manifests, staged rollout, DR, and final
DoD audit remain required.

G1 and Phase 2 are complete. PR Quality run `30327014212` is bound to feature
tree `25d83c9a19669542c94ca915ed96b20fe3bea8ac`, which is identical to squash
merge `659ba823a1dd8bc867a6fe9cca5187f475dec979`. Its immutable artifacts prove
zero-error repository layout, zero secret findings over working tree/history,
actionlint and ShellCheck, clean Ubuntu and Windows bootstrap with capacity/
storage governance, Keycloak reference conformance, and Compose build, healthy
startup, and cleanup. This closes the developer-platform gate only; it does not
close G2, G3, staging, production, provider, connector, lifecycle, or release
gates.

Phase 3 and G2 remain in progress. Local Windows and revision-bound Linux CI
Keycloak 26.7 runs satisfy the reference non-production IdP integration
criterion; the local artifact remains marked
`REFERENCE_CONFORMANCE_NOT_PRODUCTION`. Production IdP selection/conformance
and the broader G2 exit conditions remain open.

Phase 4 is in progress. Checkpoint 4A now has local, source/JAR-bound proof for
the nested incident create/detail/transition/timeline contract, forced RLS,
authorization revocation serialization, idempotency, concurrency, atomic
rollback, immutable timeline, database-computed audit chaining, and fresh plus
upgrade migrations. Remote PostgreSQL/Java gates exercise the revision-bound
contracts, but the evidence-object lifecycle, remaining incident breadth, and
production gates remain open, so Phase 4 and G2 are not complete.

Phase 5 is in progress. The provider-neutral runtime, delegated capability and
egress controls, durable PostgreSQL state, V005 append-only synthetic-probe
audit, and Platform API integration checks are present. The static checkpoint
passes; revision-bound CI reports 159 Python tests passed with five PostgreSQL-gated skips,
Ruff/mypy are clean, and the full Maven suite passes. The Phase 5 exit gate is
blocked, not passed: B-004 still requires provider region, processing terms,
retention behavior, and redaction verification, and the externally rotated-key
synthetic DeepSeek smoke has not run. No live or production provider egress is
claimed.

## Critical Path

1. Record and strictly validate G0.5 approval. **Completed 2026-07-19.**
2. Establish reproducible workspace and CI.
3. Prove identity, tenant isolation, and canonical contracts.
4. Build the smallest live, read-only incident investigation.
5. Establish deterministic evaluation before durable orchestration expands complexity.
6. Add Temporal, permission-aware RAG, and exact-action remediation with adversarial tests.
7. Complete operator workflows and data/model governance.
8. Harden security/reliability, prove DR, and audit every Definition of Done item.

Student promotion is not on the Phase 15 critical path. Bounded training smoke is mandatory, while shadow/canary promotion proceeds only when the candidate beats the approved gates.

Phase 6 has a deterministic Spring Tool Gateway checkpoint. The contract gate
validates four Tool Gateway schemas, canonical fixtures,
canonical request/evidence digests, manifest/OpenAPI ownership, source-level
generic-executor prohibitions, dedicated workload-vs-capability token domains,
exact workload scope, canonical request-body capability binding, resource-bound
selectors, recursive DLP, bounded connector execution, and fail-closed readiness.
Platform capability issuer conformance now passes. The dedicated Tool Gateway
schema/roles, hashed nonce claims, fenced receipts, transactional audit
finalization, and exact read-only Prometheus connector are implemented with
guarded PostgreSQL and Compose conformance gates. GitHub Actions run
`29987371420` proves the exact durable/live checkpoint. This is not a Phase 6
exit: the large-evidence artifact adapter, remaining connector families, and
provider-specific cancellation plus tenant-bulkhead proof remain open.
The B-016 branch adds capability-derived tenant/project propagation, forced RLS
for receipts and verified audits, a tenant-free pre-verification audit lane,
exact-policy/schema-privilege readiness, lease-vs-manifest safety, authenticated
delivery-rejection audit, single-connection pool-reuse tests, and a V002-to-V003
upgrade proof. PR Quality run `30279072972` and cross-service run
`30279067839` are terminal green for source
`269bd39e626836607fe66ed7eb050e1aa309044a7`; PostgreSQL artifact `8658901958`
and cross-service artifact `8658216777` are digest-recorded in
[Progress](./progress.md). B-016 is resolved. This is not a Phase 6 exit:
the large-evidence artifact adapter, remaining connector families, provider
cancellation, and tenant-bulkhead proof remain open.

Phase 7 now includes a pure command/event reducer, bounded in-process runner,
fixture-only `metrics.query` path, cited-completion guard, duplicate/no-progress
detection, budget terminal states, tenant/run projections, canonical contracts,
and the investigation OpenAPI operations. Additive Flyway V006 persists run
snapshots, contiguous immutable investigation events, and matching
`investigation-audit-v1` rows with forced RLS and optimistic revision checks.
The static and revision-bound validator reports `CheckpointResult=PASS` and
`PhaseExit=PASS` for the local integration checkpoint; the PostgreSQL CI gate
exercises migration, persistence, and direct SQL integrity tests. This is not
G3: orchestration has no restart/resume semantics. Platform now has the immutable
intent catalog, tool capability issuer, and bounded OAuth workload-token adapter,
and the non-fixture investigation AI client now re-authorizes each round's
evidence, publishes selector-only prompts, signs the exact canonical request,
and reuses the hardened AI Runtime transport. The non-fixture Tool Gateway
client now resolves immutable selectors before credential acquisition, binds a
one-use capability to exact canonical bytes, sends a separate workload bearer,
and verifies identity, provenance, bounds, and evidence digest before
persistence. The durable Gateway and selected Prometheus implementation now
reserve with a database-clock lease, execute HTTP outside transactions, and
atomically finalize audit plus receipt. Revision-bound PostgreSQL/synthetic
Prometheus evidence now passes for 100 warm runs with p95 below the local
threshold. CK/Stitch UI/browser E2E also passes. G3 still requires a named live
non-production connector, provider/legal conformance, and BFF/session proof.

The current worktree closes the read-linkage implementation gap through an
opt-in, ANALYZE-only metadata representation on the existing incident timeline
route. Legacy JSON remains on the unchanged READ/version-cursor path. The
vendor path unions both ledgers with exact organization/project/incident
filters and exposes only eight possible entry fields; it reads no payload or
free text. Its tuple cursor is a forward-only live view, so late backdated rows
require a fresh traversal. No investigation event is copied into the incident
ledger.

V009 builds two ordering indexes concurrently and outside a Flyway transaction,
before new application code is rolled out. Timeline-plan Phases 2 and 3 are
completed for this plan slice by PR Quality run `30257587569`, PostgreSQL job
`89950772823`, and artifact `8650178111`. Fresh/upgrade, failed-build recovery,
bounded plans, 60,600/61,206 source rows, append/vendor latency, index storage,
legacy writes, cleanup, and the 3/3 activity HTTP matrix pass. The measured
fixture gates are not production SLO or rollout evidence. Migration-before-
code and forward recovery remain mandatory.

Active program blockers remain explicit: `B-004`
(provider/legal), `B-005` (named live connector), `B-006` (evidence lifecycle),
`B-007` (load/SLO), `B-008` (retention/deletion), `B-011` (RTO/restore),
`B-012` (object-store supply chain), `B-013` (held-out/human evaluation), and
`B-017` (Temporal dispatcher containment/reconciliation).
See [Blockers](./blockers.md) for accountable owners and unblock evidence.

Phase 8B now implements three secret-free, training-ineligible contracts:
Scenario A detects a deployment-correlated latency regression, Scenario B is
`ABSTAINED` without tools when evidence is insufficient, and Scenario C binds
two opposing read-only evidence results to counter-evidence and cautious
confidence. V008 persists accepted normalized responses; least-privilege
security-barrier views and a strict bounded projector harvest only scoped
analysis/timeline/evidence/receipt metadata with typed byte/canonical digests
and derive trusted tool executions from durable observed connector provenance.
V008 is explicitly the rolling expand step: legacy writers remain temporarily
writable, response-aware writes are strict, and only the latter are
evaluation-eligible; contract enforcement waits for old-writer drain.
Every accepted invocation contributes to run cost; durable audit/receipt/
evidence digests are exact-bound. Scenario C is independent of evidence
row/UUID order. Reparse-safe managed paths and cleanup-first secret/export
deletion protect failure paths. The exact CI evaluation command passes 61/61.
PR-quality run `30257587569` and cross-service run `30257587543` are terminal
green for revision `a975f922`; fresh A/B/C score `PASS` on all eight metrics
with samples `100/1/1` and `GitTree=0`. Artifact `8649696519` attests the run
and preserves the Phase 7 regression. Two blocking process-supervision reviews
pass.
The earlier run `30200584275` on `134d63c` remains historical first-green
evidence. Held-out governance, preregistered Wilson reporting, and the human
protocol are implemented, but held-out payloads and qualified human records/
adjudication are unavailable. This is deterministic smoke evidence on authored
scenarios, so parent Phase 8 and its A-Z G4 exit remain `BLOCK` on held-out
quality, calibration, and human comparison.

Phase 9 is in progress. The current worktree adds Platform V010-V012 and a
default-off handoff that atomically creates the initial run, immutable Temporal
target/request binding, and canonical outbox start event. The opt-in dispatcher
uses a dedicated datasource and commits its claim before the Temporal RPC.
V012 removes V011's inherited direct-DML bypass for the Phase 9 binding, inbox,
and canonical outbox event, introduces a dedicated workflow claim, preserves
same-aggregate ordering, and rejects unsafe role memberships. Static validation
and rollback-only PostgreSQL probes pass; full fresh/upgrade real-role,
atomicity, and latency evidence is still pending.

An `AlreadyStarted` response is accepted only after exact target, memo digest,
and first-history input verification. A retryable post-RPC result can still
mean the remote workflow was accepted: V012 retries within the bounded policy
and then holds the row `PENDING` with `workflow.reconciliation-required`
instead of unconditional `REJECTED`. B-017 requires a separately
authorized read-only Describe-plus-first-history lane that cannot Start a
workflow before admission can open. Inline remains the default `200` path;
Temporal admission is additive `202 + Location` and requires a compatible
task-queue poller.

This is not the Phase 9 or roadmap G4 exit. No live/production Temporal cluster
or namespace, Compose service, workflow worker, or restart/resume execution
exists. V010 performs no automatic legacy backfill. Exact-head CI and full
Flyway/PostgreSQL runtime evidence are also missing, and B-013 and B-017 remain
active.

## Staffing Scenarios

G0.5 selected six cross-functional contributors and a nine-month target. The
other scenarios remain planning comparisons, not approved delivery targets:

| Team | Indicative elapsed time | Trade-off |
|---|---:|---|
| 3–4 core engineers | 10–15 months | Lowest coordination, longest path, specialist bottlenecks |
| 6–8 cross-functional | 6–9 months | Recommended balance of ownership and parallel proof |
| 9–12 cross-functional | 5–8 months | Faster parallelism but higher integration and governance cost |

The approved capacity envelope is six cross-functional contributors over nine
months with budget approval. Detailed phase forecasts remain evidence-gated and
must be re-estimated after G3 measurements and external-provider/legal lead-time
evidence.

## Change Control

- A phase status changes only from current evidence.
- Material topology, identity, data-egress, public-contract, or remediation changes require ADR and plan updates.
- Security or evaluation gates are not weakened without explicit product/risk decision and documented consequence.
- A blocked decision is recorded in [Blockers](./blockers.md).
- Progress increments and evidence are recorded in [Progress](./progress.md).

## Verification Evidence

`ck plan validate --strict` verifies plan structure. Phase-specific commands, CI artifacts, evaluation reports, security evidence, and DR transcripts prove implementation. The roadmap is not itself completion evidence.

## Unresolved Questions

No G0.5 decision remains unresolved; Phase 2 is complete and Phase 3 remains in progress. Later release
questions are the supported local S3 adapter after MinIO's upstream archive,
reconciliation of the 120-minute service RTO with a four-hour artifact restore
target, production-authorized enterprise-IdP conformance, and provider
processing terms.

Phase 9 handoff infrastructure is now in progress with provisional test-only
values. The reviewed human pilot remains mandatory before no-progress/budget
thresholds are frozen and before Phase 9 can exit. B-017 also keeps Temporal
admission and roadmap G4 disabled until V012 has full real-role fresh/upgrade
and atomicity runtime proof and the read-only exact-workflow reconciliation/
alert lane exists. This
reconciles preparatory engineering with the stricter parent Phase 8 evidence
gate.

Gate labels also drift between documents: the parent A-Z plan names Phase 8
exit `G4`, while this roadmap's gate summary names durable workflow `G4` and
product/evaluation `G7`. The parent A-Z `G4` remains canonical for Phase 8 until
the roadmap taxonomy is explicitly reconciled.
