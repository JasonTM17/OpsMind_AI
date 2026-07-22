---
phase: 10
title: "Permission-aware RAG and Knowledge Lifecycle"
status: pending
priority: P1
dependencies: [3, 6, 8, 9]
effort: "2-3 weeks"
---

# Phase 10: Permission-aware RAG and Knowledge Lifecycle

## Context Links

- [Master plan](./plan.md)
- Retrieval and provenance product constraints: `plans/260719-1747-opsmind-ai-production-platform/reports/brainstorm-report.md:56`, `:153`, `:213`, `:226`
- Knowledge/RAG obligations: `plans/260719-1747-opsmind-ai-production-platform/research/master-prompt-requirements-traceability.md:51`, `:52`, `:53`, `:115`
- Architecture and ACL/provenance rules: `plans/260719-1747-opsmind-ai-production-platform/research/researcher-01-architecture-security.md:58`, `:98`, `:146`
- Delivery/order guidance: `plans/260719-1747-opsmind-ai-production-platform/research/researcher-02-delivery-evaluation.md:101`, `:102`, `:177`

## Overview

Build the first governed knowledge plane: ingest source material safely, keep tenant/project ACLs authoritative, create reproducible citations, and expose retrieval results that help investigations without becoming a cross-tenant leak path. This phase turns evidence search from ad hoc context stuffing into permission-aware infrastructure.

## Objective

Deliver an end-to-end knowledge lifecycle that can:

- register and version knowledge sources with tenant/project/environment scope
- ingest artifacts through a scanned, normalized, and deduplicated pipeline
- build hybrid lexical/vector retrieval over ACL-filtered candidate sets
- produce citation bundles that point to stable source/version/chunk lineage

## Non-Goals

- No unrestricted "ask the docs" chat surface; retrieval remains investigation-scoped
- No separate standalone RAG service extraction; keep control-plane and AI-runtime split only
- No destructive source deletion without tombstones and reindex audit trail
- No UI-heavy knowledge management work beyond API/readiness for phase 12 screens

## Prerequisites and Dependencies

- Must follow Phases 3, 6, 8 and 9 and their compatible evidence manifests/contracts as declared in frontmatter and the master graph.
- Must preserve `INV-04` tenant/project boundaries across API, tools, RAG, and datasets from `master-prompt-requirements-traceability.md:21`.
- Must inherit the runtime split where platform API owns relational authority and AI runtime owns retrieval orchestration, from `brainstorm-report.md:107`, `:108` and `researcher-01-architecture-security.md:58`.
- Entry validation confirms upstream evidence manifests, canonical contract versions and required service roots; missing outputs block entry rather than trigger a parallel bootstrap.
- This phase blocks phase 11 and 12 because approvals and operator screens need stable citation bundles, knowledge-source status, and permission-filtered retrieval APIs.

## Architecture and Design Decisions

1. Platform API owns source registration, ACL/version/generation state, forced-RLS candidate retrieval and lifecycle commands. AI runtime owns parsing/embedding/reranking/citation orchestration but never receives a broad database credential. Source basis: `researcher-01-architecture-security.md:54`, `:58`.
2. ACL filtering must happen before ranking, not after ranking, to avoid leaking candidate existence across tenants/projects. Source basis: `brainstorm-report.md:226` and `researcher-01-architecture-security.md:98`.
3. Every citation must carry source ID, source version, chunk ID, retrieval timestamp, and snippet boundaries so RCA claims are replayable. Source basis: `master-prompt-requirements-traceability.md:53`, `:115`.
4. Ingestion is an asynchronous pipeline with explicit states: registered, fetching, scanned, parsed, chunked, embedded, indexed, active, tombstoned, deleted. This is an implementation decision derived from `master-prompt-requirements-traceability.md:51`, `:53`.
5. pgvector stays inside PostgreSQL for initial delivery to keep transactional metadata and retrieval state in one system, per `researcher-01-architecture-security.md:58` and `master-prompt-requirements-traceability.md:157`.
6. Retrieved text is data, never authority; prompt-injection markers, oversized chunks, secret hits, and malformed parser output must reduce trust or stop ingestion. Source basis: `brainstorm-report.md:180` and `master-prompt-requirements-traceability.md:82`.
7. Revocation is synchronous at query/read time. Async reindex/purge is cleanup only and may not define when access stops.
8. Every source has a monotonic ACL revision and generation epoch. Stale workers cannot write or reactivate an older generation; inactive generation builds swap atomically after completeness checks.

## Explicit Data Flow

1. Operator or system registers a source with tenant/project scope, connector type, retention policy, and expected ACL owner.
2. Platform API stores source metadata and queues ingestion work with a stable source version ID.
3. Tool Gateway fetches source artifacts using read-only scoped access and returns canonical artifact references plus provenance metadata.
4. AI runtime scans, normalizes, chunks and computes embeddings, then submits versioned batches to a platform internal endpoint under a narrowly scoped ingestion capability; platform compare-and-swaps the expected source epoch before writing.
5. Investigation asks platform retrieval with a signed query capability. Platform rechecks authoritative ACL/tombstone state inside the forced-RLS transaction and returns only authorized lexical/vector candidates; AI runtime may rerank only that set.
6. Citation builder returns source/version/chunk/offset/hash references. Durable workflow/projection stores citation IDs, not snippet bodies; UI resolves renderable snippets through an authorized current read so revocation is immediate.
7. ACL change/tombstone increments revision/epoch synchronously, making old generations and cache keys unusable before async cleanup. Reindex builds an inactive generation and atomically swaps after validation.

## File Ownership

- Exclusive ownership for this phase: knowledge-source schemas, ingestion/retrieval modules, citation contracts, source lifecycle migration, and RAG evaluation tests.
- Shared additive files only: `packages/contracts/openapi/opsmind-v1.yaml` and investigation query payloads that gain citation fields.
- Explicitly out of scope: approval write executors and broad `apps/web/**` UI work.

## File Inventory

| Action | Path | Purpose | Notes |
|---|---|---|---|
| MODIFY | `packages/contracts/openapi/opsmind-v1.yaml` | Add knowledge-source lifecycle APIs and retrieval/citation response contracts | Additive only |
| CREATE | `packages/contracts/json-schema/knowledge/knowledge-source.schema.json` | Source, ACL revision, generation and lifecycle payload | Shared across API/UI |
| CREATE | `packages/contracts/json-schema/knowledge/retrieval-result.schema.json` | Authorized candidate + citation contract | Required for investigations |
| CREATE | `packages/contracts/json-schema/knowledge/citation-bundle.schema.json` | Stable reference/offset/provenance; no durable snippet body | No raw secrets |
| MODIFY | `services/platform-api/pom.xml` | Add pgvector and ingestion orchestration dependencies | Stop if file missing; upstream blocker |
| CREATE | `services/platform-api/src/main/resources/db/migration/V006__knowledge_catalog_and_pgvector.sql` | Source, artifact, chunk, embedding, ACL, epoch and tombstone tables/indexes | Additive migration |
| CREATE | `services/platform-api/src/main/java/ai/opsmind/platform/knowledge/application/KnowledgeCatalogService.java` | Register, activate, tombstone, and delete source versions | Authority layer |
| CREATE | `services/platform-api/src/main/java/ai/opsmind/platform/knowledge/api/KnowledgeSourceController.java` | Source lifecycle endpoints | Problem Details on errors |
| CREATE | `services/platform-api/src/main/java/ai/opsmind/platform/knowledge/query/CitationBundleView.java` | Read model for investigation/UI consumers | Read-only |
| CREATE | `services/platform-api/src/main/java/ai/opsmind/platform/knowledge/query/AuthorizedRetrievalService.java` | Forced-RLS ACL-before-ranking candidates | Authoritative boundary |
| CREATE | `services/platform-api/src/main/java/ai/opsmind/platform/knowledge/api/KnowledgeIngestionInternalController.java` | Epoch-CAS chunk/embedding batches | Delegated internal only |
| MODIFY | `services/tool-gateway/src/main/java/com/opsmind/toolgateway/connectors/{git,runbooks}/` | Canonical artifact export metadata | Spring Gateway path |
| MODIFY | `services/ai-runtime/pyproject.toml` | Add sandboxed parser, embedding and reranking dependencies; no broad DB client role | Stop if file missing; upstream blocker |
| CREATE | `services/ai-runtime/app/retrieval/ingestion_pipeline.py` | Fetch-scan-parse-chunk orchestrator | Activity-safe |
| CREATE | `services/ai-runtime/app/retrieval/embedding_indexer.py` | Embedding generation and capability-scoped batch submission | No DB credential |
| CREATE | `services/ai-runtime/app/retrieval/retrieval_orchestrator.py` | Platform-authorized candidate request and rerank | Cannot expand candidates |
| CREATE | `services/ai-runtime/app/retrieval/citation_builder.py` | Snippet extraction and provenance bundle assembly | Stable offsets |
| MODIFY | `services/ai-runtime/app/workflows/investigation_workflow.py` | Add retrieval plan and citation reference states | Additive workflow change with version marker |
| CREATE | `services/platform-api/src/test/java/ai/opsmind/platform/knowledge/KnowledgeSourceControllerIT.java` | Lifecycle API and ACL integration tests | Required gate |
| CREATE | `services/ai-runtime/tests/retrieval/test_retrieval_orchestrator.py` | ACL-negative, citation, dedupe and injection tests | Required gate |
| DELETE | `None planned` | Prefer tombstones/versioning over physical delete of active structures | Physical delete only for expired artifacts |

## Implementation Tasks

1. Define source, chunk, and citation contracts.
Contract: source payloads must include owner scope, retention, sensitivity classification, connector type, and version status.
Behavior: retrieval results without citations are invalid for any investigation response that claims grounding.

2. Add relational catalog and vector-aware storage.
Contract: source metadata, chunk lineage, ACL revision, generation epoch and tombstones live in PostgreSQL with explicit FKs, forced RLS and query-driven indexes.
Behavior: vector rows are bound to source version/generation; app and ingestion roles are non-owner/no-BYPASSRLS, and stale workers fail epoch CAS.

3. Build asynchronous ingestion pipeline.
Contract: pipeline stages are explicit and observable, with retryable states only for idempotent steps such as fetch, parse, embed, and index.
Behavior: secret/PII hits, parser bombs, and oversized artifacts fail closed and emit actionable status codes for operators.

4. Build hybrid retrieval with ACL-first filtering.
Contract: retrieval request carries a platform-issued query capability binding tenant/project/incident/purpose and resources; request selectors cannot add authority.
Behavior: platform transaction checks current ACL/tombstone state and forced RLS before lexical/vector candidate ranking; AI runtime reranks only returned candidates.

5. Assemble stable citation bundles.
Contract: citation bundle contains source version, chunk ID, snippet boundaries, retrieval score components, and provenance hash/reference.
Behavior: workflow/projection store bundle IDs and stable offsets/hashes, not copied snippet bodies. UI/model context resolves content through a fresh authorized read and egress policy.

6. Add lifecycle commands for reindex, tombstone, and delete.
Contract: ACL/content changes atomically increment revision/epoch; reindex jobs build an inactive generation and can swap only with expected epoch.
Behavior: tombstone/revoke immediately denies query and snippet reads, invalidates cache keys, then propagates purge to artifacts/projections/evaluation/datasets/backups with receipts or explicit retention exceptions.

7. Extend investigation workflow with retrieval state.
Contract: workflow consumes retrieval summaries and citation IDs only; no raw document bodies flow into durable state.
Behavior: missing or denied retrieval results become explicit counter-evidence or abstention inputs, not silent nulls.

8. Lock evaluation and security gates before operator adoption.
Contract: ACL-negative suite, citation-correctness suite, injection-defense suite, and dedupe/versioning suite must all pass before feature flag enablement.
Behavior: phase 8 benchmark artifacts gain retrieval precision/recall and citation-precision metrics tied to this implementation.

## Migration Strategy

- Enable `vector` extension and relational catalog migration before ingestion workers start.
- Roll out source-registration APIs disabled for broad users first; seed only simulator/test fixtures until ACL-negative tests pass.
- Introduce workflow citation fields additively using Temporal version markers or continue-as-new boundaries when the workflow payload shape changes.
- Backfill existing phase 7/8 fixture evidence into the knowledge catalog only after dedupe and provenance rules are implemented.

## Rollback Plan

- Disable ingestion and retrieval feature flags while leaving catalog tables read-only for forensic inspection.
- Stop new reindex jobs and drain running workers; preserve tombstone and source-version records so replay remains possible.
- If vector/index logic misbehaves, fall back to lexical-only retrieval inside the same ACL-filtered candidate set rather than bypassing ACL or citations.
- Never hard-delete source metadata during rollback; forward-fix broken rows and reindex later.

## Test and Evidence Matrix

| Layer | Required checks | Evidence artifact |
|---|---|---|
| Unit | chunk normalization, dedupe keys, citation offsets, capability/body reduction | `test_retrieval_orchestrator.py` and companion suites |
| Integration | source lifecycle, forced-RLS retrieval, generation swap, tombstone/delete | `KnowledgeSourceControllerIT.java` |
| Security | forged selectors/capabilities, pool reuse, injection, malicious parser, cross-tenant retrieval | security report |
| Concurrency/deletion | read vs revoke, delayed stale writer, reindex swap, cache invalidation, copied-reference purge | lifecycle race and purge-receipt report |
| Evaluation | retrieval precision/recall/MRR, citation precision, denied-source behavior | phase-8 benchmark artifact |
| Durability | reindex retry, DLQ behavior, versioned source replay | ingestion pipeline run log |
| Performance | ingest throughput, retrieval latency, vector index health | benchmark report with raw timings |

## Quantitative Exit Gate

- [ ] `RAG-01` evidence exists: ingestion pipeline handles allowed fixtures plus malicious fixtures with 100% expected pass/fail outcomes and no silent partial success.
- [ ] `RAG-02` evidence exists: cross-tenant/ACL-negative retrieval suite shows zero unauthorized hits across at least 100 mixed-scope test queries.
- [ ] `RAG-03` evidence exists: citation-precision benchmark reaches at least 95% on phase-8 held-out scenarios and every grounded answer returns a citation bundle.
- [ ] Retrieval latency stays below 1.5 seconds p95 for top-10 results on benchmark corpus under local/staging constraints, excluding embedding generation.
- [ ] Reindex and tombstone replay succeeds in 10 consecutive runs with no orphaned chunk rows or stale active-source pointers.
- [ ] Concurrent revoke/read/reindex/delete and delayed-retry suite produces zero post-revocation content reads and zero stale-generation resurrection; derived-copy purge receipts or policy exceptions are complete.
- [ ] AI runtime has no broad knowledge database credential and cannot expand platform-authorized candidates in forged-worker tests.

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---:|---:|---|
| ACL leak via post-ranking filter | Medium | Critical | Enforce ACL-before-ranking, add negative tests, and review SQL explain plans |
| Stale embeddings after source or ACL change | Medium | High | Versioned source rows, tombstones, reindex queue, and stale-row detectors |
| Parser/normalizer exploit or runaway cost | Medium | High | Size caps, parser sandboxing, secret/PII scan, and per-stage budget limits |
| Citation mismatch after snippet regeneration | Low | High | Persist snippet boundaries and source-version references at retrieval time |
| Phase 9 workflow payload drift breaks replay | Medium | Medium | Additive contracts, Temporal version markers, and contract tests across both runtimes |

## Unresolved Decisions

- Whether AI runtime continues to own retrieval end-to-end long term or whether ingestion volume later justifies a dedicated service remains open per `researcher-01-architecture-security.md:178`.
- The exact embedding model/provider is intentionally deferred; phase 10 should hide it behind configuration and contract tests.
- Retention and physical-purge timings are fixed by G0.5 before production ingestion; only test defaults may remain configurable here.
