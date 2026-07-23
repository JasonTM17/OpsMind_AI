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
| G4 | Durable workflow safe | Crash/replay/versioning and bounded provider continuation |
| G5 | RAG lifecycle safe | ACL-before-ranking, provenance, revoke/delete receipts |
| G6 | Exact action safe | Bound approval, CAS, idempotency, ambiguity reconciliation |
| G7 | Product/evaluation usable | Operator UX, held-out evaluation, human baseline |
| G8 | Production ready | Security, reliability, DR, runbooks, delivery and final DoD audit |

## Phases

| Phase | Outcome | Main gate contribution | Status |
|---:|---|---|---|
| 1 | Operating envelope and architecture governance | G0.5/G1 | Completed; strict contract gate passed |
| 2 | Monorepo and developer platform foundation | G1 | In progress |
| 3 | Contracts, data, identity, and tenant foundation | G2 | In progress |
| 4 | Incident control plane, evidence lifecycle, and audit | G2/G3 | In progress; checkpoint 4A local proof complete |
| 5 | DeepSeek AI runtime and provider gateway | G3 | In progress; static checkpoint passed, exit gate blocked |
| 6 | Safe Tool Gateway and read-only connectors | G3 | In progress; checkpoint PASS, PhaseExitGate BLOCK |
| 7 | Evidence-backed incident vertical slice | G3 | In progress; durable persistence checkpoint PASS, PhaseExitGate BLOCK |
| 8 | Simulator and evaluation baseline | G3/G7 | Pending |
| 9 | Durable Temporal investigation workflow | G4 | Pending |
| 10 | Permission-aware RAG and knowledge lifecycle | G5 | Pending |
| 11 | Exact-action approval and reversible remediation | G6 | Pending |
| 12 | Operator web experience completion | G7 | Pending |
| 13 | Dataset flywheel and governance | G7 | Pending |
| 14 | Student training, shadow, and promotion | G7; promotion conditional | Pending |
| 15 | Security, reliability, and observability hardening | G8 | Pending |
| 16 | Delivery, disaster recovery, and final verification | G8 | Pending |

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
passes; the Python suite reports 149 passed with five PostgreSQL-gated skips,
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

Phase 6 has a deterministic Spring Tool Gateway checkpoint. Twenty-nine Maven
tests pass; the contract gate validates four Tool Gateway schemas, five fixtures,
canonical request/evidence digests, manifest/OpenAPI ownership, source-level
generic-executor prohibitions, dedicated workload-vs-capability token domains,
exact workload scope, canonical request-body capability binding, resource-bound
selectors, recursive DLP, bounded connector execution, and fail-closed readiness.
Platform capability issuer conformance now passes. This is not a Phase 6 exit:
durable atomic nonce/receipt/audit/artifact adapters, three
fixture connector families, one live non-production read-only target, and
provider-specific cancellation/tenant-bulkhead proof remain open.

Phase 7 now includes a pure command/event reducer, bounded in-process runner,
fixture-only `metrics.query` path, cited-completion guard, duplicate/no-progress
detection, budget terminal states, tenant/run projections, two canonical
contracts, and two OpenAPI operations. Additive Flyway V006 persists run
snapshots, contiguous immutable investigation events, and matching
`investigation-audit-v1` rows with forced RLS and optimistic revision checks.
The static validator reports `CheckpointResult=PASS` and `PhaseExit=BLOCK`; the
PostgreSQL CI gate exercises migration, persistence, and direct SQL integrity
tests. This is not G3: orchestration has no restart/resume semantics, events are
not yet linked to `incident_timeline_events`; Platform now has the immutable
intent catalog, tool capability issuer, and bounded OAuth workload-token adapter,
but the orchestration clients and selected live connector are absent. The
CK/Stitch UI/browser E2E plus cross-service trace/p95 evidence remain open.

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

No G0.5 decision remains unresolved and Phases 2 and 3 are in progress. Later release
questions are the supported local S3 adapter after MinIO's upstream archive,
reconciliation of the 120-minute service RTO with a four-hour artifact restore
target, production-authorized enterprise-IdP conformance, and provider
processing terms.
