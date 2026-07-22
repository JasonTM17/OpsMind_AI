---
phase: 13
title: "Dataset Flywheel and Governance"
status: pending
effort: "3-5 weeks; 2 AI/data engineers plus security and domain reviewers"
dependencies: [3, 5, 8, 9, 10, 12]
requirements: [DATA-01, DATA-02, INV-02, INV-04, INV-06, INV-08, ADD-02, ADD-06, ADD-07]
---

# Phase 13: Dataset Flywheel and Governance

## Objective

Create a reproducible, reviewable pipeline that turns simulator cases and explicitly approved incident traces into versioned training/evaluation datasets. A record is not accepted because a teacher model produced it: evidence linkage, safety, license, lineage, quality, and human-review rules decide acceptance.

## Non-goals

- No student-model training or serving; Phase 14 consumes only promoted snapshots.
- No automatic ingestion of production incidents, prompts, logs, source, or tool output.
- No copying provider chain-of-thought or hidden reasoning into datasets.
- No local bulk generation or model download while the disk-capacity gate is red.

## Prerequisites and entry gate

- Phase 8 owns a frozen held-out benchmark and deterministic simulator ground truth.
- Phase 9 persists structured investigation records with evidence references, not raw secrets.
- Phase 10 exposes ACL-aware document/evidence identifiers and deletion lifecycle events.
- Tenant data-processing purpose, retention, deletion, provider privacy, and residency defaults are documented. If these business decisions remain open, production-derived ingestion stays disabled.
- Dataset jobs pass the same tenant/project authorization boundary as interactive APIs.

## Design decisions and patterns

1. **Immutable snapshot pattern:** accepted datasets are content-addressed manifests. Published snapshots never mutate; corrections produce a new version and tombstone relation.
2. **Medallion-style zones with strict promotion:** `quarantine -> normalized -> reviewed -> published`. Only published records can train a model.
3. **Provenance graph:** every example links source type, source IDs, evidence IDs, schema/prompt/model versions, transformations, reviewer, consent/purpose, and parent snapshot.
4. **State machine plus outbox:** promotion/rejection/deletion are transactional domain transitions; asynchronous materialization consumes idempotent outbox events.
5. **Shared governed artifact port:** PostgreSQL keeps metadata and hashes; large JSONL/Parquet artifacts reuse the Phase 4 S3-compatible port under separate bucket/prefix policy, encryption, retention and short-lived access.
6. **Split-before-generation:** scenario family, incident lineage, duplicate cluster, and tenant boundary are assigned before augmentation so train/eval leakage cannot be created later.
7. **Teacher-as-candidate:** teacher output supplies a candidate label and confidence. Deterministic ground truth or reviewer adjudication remains authoritative.
8. **Privacy by construction:** raw secrets are rejected; stable one-way placeholders preserve referential usefulness without reversible pseudonym tables in dataset artifacts.

## Planned file inventory

| Operation | Path | Purpose |
|---|---|---|
| CREATE | `packages/contracts/json-schema/datasets/dataset-example-v1.schema.json` | Canonical evidence-grounded example contract |
| CREATE | `packages/contracts/json-schema/datasets/dataset-manifest-v1.schema.json` | Snapshot, split, lineage, checksum, license and policy metadata |
| CREATE | `services/platform-api/src/main/java/ai/opsmind/platform/datasets/` | Dataset project, snapshot, review and policy domain modules |
| CREATE | `services/platform-api/src/main/resources/db/migration/V008__dataset_governance.sql` | Metadata, review, lineage and RLS tables |
| CREATE | `services/ai-runtime/app/datasets/` | Normalize, validate, deduplicate, split and materialize stages |
| CREATE | `services/ai-runtime/app/datasets/policies/` | DLP, PII, license, evidence and quality rules |
| CREATE | `services/ai-runtime/tests/datasets/` | Property, leakage, retry and malicious-input tests |
| CREATE | `apps/web/src/features/datasets/` | Reviewer queue, diff, provenance and snapshot screens |
| MODIFY | `infra/compose/profiles/evidence-store.yaml` | Separate dataset bucket/prefix policy on configured D: root |
| CREATE | `docs/dataset-governance.md` | Purpose, retention, deletion, review, licensing and access policy |
| CREATE | `docs/templates/dataset-card.md` | Required dataset-card template |
| CREATE | `scripts/dataset/run-dataset-smoke.ps1` | Bounded Windows smoke path with disk preflight |
| CREATE | `scripts/dataset/run-dataset-smoke.sh` | Bounded Linux/macOS smoke path |
| MODIFY | `.gitignore` | Exclude raw, normalized and generated data artifacts |
| MODIFY | `docs/system-architecture.md` | Record data zones, trust boundaries and lineage flow |

Exact Java package prefixes and migration sequence must follow the conventions established in Phases 2-4; do not encode the phase number in production symbols.

## Implementation tasks

### 13.1 Lock contracts and lifecycle

- Define an example envelope containing `example_id`, tenant/project scope, task type, input references, evidence references, target structured output, source class, split, lineage, policy verdicts, and cryptographic content hash.
- Define legal lifecycle transitions and role matrix: producer can submit; reviewer can accept/reject; data steward can publish/withdraw; no actor approves their own production-derived example.
- Store reason codes for every rejection, waiver, withdrawal, and deletion. Waivers require expiry and named policy owner.
- Reject unknown schema versions and verify manifest/file checksums before any consumer reads a snapshot.

### 13.2 Build controlled source adapters

- Implement simulator export first; it supplies ground truth and scenario-family identifiers.
- Add teacher-generation jobs with pinned prompt/model/parameter/schema versions and bounded concurrency, retry, token and cost budgets.
- Add a production-derived adapter behind a default-off feature flag. It accepts only incidents explicitly marked exportable and references a recorded processing purpose.
- Never export raw authentication headers, credential fields, hidden reasoning, unrestricted source archives, or unrestricted tool payloads.

### 13.3 Normalize, scan and validate

- Canonicalize timestamps, identifiers, severity and evidence references without changing semantics.
- Run secret patterns, entropy checks, PII classifiers, malicious-format limits, license/attribution checks and evidence-reachability validation.
- Apply exact and near-duplicate clustering before split assignment. Fail a build if a cluster spans train and held-out evaluation.
- Validate causal labels against simulator ground truth when available; otherwise require two independent reviewer signals for high-severity examples.

### 13.4 Review and adjudication workflow

- Present source-vs-label diff, evidence coverage, counter-evidence, redaction summary, duplication cluster, license and policy decisions in the web queue.
- Blind reviewers to teacher identity where practical to reduce anchoring.
- Record inter-rater disagreement; route unresolved critical examples to domain adjudication rather than majority guessing.
- Enforce optimistic concurrency so a stale browser cannot accept a record that changed after review began.

### 13.5 Publish and consume snapshots

- Materialize deterministic JSONL and optional Parquet outputs ordered by stable example ID.
- Produce signed checksum manifest, dataset card, quality report, split statistics and rejected-record summary; rejected content itself remains restricted.
- Issue read-only short-lived artifact URLs only after service authorization; do not expose bucket credentials to training jobs.
- Provide a consumer API that resolves an immutable snapshot ID, never an unversioned `latest` in CI or training.
- Authorize snapshot resolution against current lineage tombstones. Immutability means bytes do not silently change; it does not grant perpetual access to a withdrawn snapshot.

### 13.6 Retention, deletion and cost controls

- Atomically revoke every snapshot containing withdrawn lineage, deny old snapshot IDs/URLs, create a new redacted snapshot, and issue purge/cryptographic-erasure work according to policy.
- Publish lineage invalidation events for every affected training run/model; Phase 14 must quarantine derived artifacts until retrained or an explicit time-bounded risk exception is approved.
- Define retention by source class and keep audit metadata longer than payload only when policy allows.
- Enforce per-tenant quotas for submitted records, retained bytes, generation tokens and daily jobs.
- Emit low-cardinality metrics for stage latency, rejection reasons, queue depth, accepted bytes and generation cost.

## Verification and evidence matrix

| Check | Method | Passing evidence |
|---|---|---|
| Contract integrity | JSON Schema fixtures and property tests | All accepted rows validate; unknown versions fail closed |
| Tenant isolation | API, PostgreSQL RLS and artifact URL negative matrix | Zero cross-tenant reads, reviews or downloads |
| DLP/privacy | Seeded secrets/PII and adversarial encodings | 100% critical seeded cases rejected; no raw value in logs |
| Evidence fidelity | Broken/mismatched evidence fixtures | Unsupported claims cannot enter reviewed state |
| Split leakage | Exact/near-duplicate and family-overlap tests | Zero lineage or duplicate cluster crossing held-out boundary |
| Idempotency | Duplicate event, crash and restart tests | Same input/version yields one logical record and stable hash |
| Review concurrency | Parallel accept/edit/withdraw tests | One valid transition; stale versions return conflict |
| Reproducibility | Two clean bounded materializations | Byte-identical ordered artifact and manifest checksums |
| Lifecycle | Publish, withdraw, tenant delete, stale URL/old snapshot use, derived-model notification | Old access denied, new snapshot clean, purge receipts and lineage invalidations complete |
| Governance | Dataset-card validation and manual sample audit | Required fields complete; sampled evidence and licenses verified |

## Exit gate

- A bounded simulator-plus-teacher job produces one immutable, reproducible published snapshot and populated dataset card.
- No accepted example contains seeded secrets/PII, unsupported evidence claims, unknown license status, or held-out leakage.
- Cross-tenant negative suite passes 100%; publish and artifact download are authorized and audited.
- Generation cost, token count, rejection rate and storage bytes are attributable by tenant and snapshot.
- Production-derived ingestion remains off until privacy, residency, retention and consent decisions have recorded owners and acceptance.
- Withdrawn lineage cannot be resolved through any old snapshot ID or signed URL; every affected model/training run is quarantined or has a recorded exception.

## Rollback and recovery

- Disable source adapters and generation workers with feature flags/kill switches.
- Withdraw a faulty snapshot, revoke its authorization, and repoint consumers only to a clean accepted snapshot; never edit published bytes in place or fall back to an affected older snapshot.
- Replay idempotent materialization from metadata and source references after fixing a processor.
- Database migrations are expand/contract; retain old readers until all active jobs use the new schema.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Teacher errors become labels | Ground truth/reviewer authority, disagreement sampling, evidence checks |
| Benchmark contamination | Preassigned splits, family/lineage grouping, hashed contamination scan |
| Sensitive production data escapes | Default-off adapter, explicit exportability, DLP, scoped artifacts, retention |
| License ambiguity | Reject unknown license for publish; record source and permitted use |
| Review queue becomes bottleneck | Risk-stratified sampling, batch diff UI, queue SLO without auto-accept |
| Dataset artifacts exhaust workstation | Remote/object storage, quotas, D: routing and mandatory disk preflight |

## Unresolved decisions

- Legal basis, residency regions and retention periods for production-derived examples.
- Approved object store/KMS and whether customer-hosted deployments bring their own store.
- Reviewer staffing and acceptable inter-rater agreement per task/severity class.
