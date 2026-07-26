# Phase 8B Evaluation Design Research
Date: 2026-07-26

## Decision

Recommended path after red-team verification: **persist the exact accepted
normalized response in the immutable investigation event ledger, then query
disposable cross-service PostgreSQL through least-privilege allowlisted views**.

Rank:
1. Minimal production-ledger acceptance binding + disposable normalized export.
2. Disposable export without ledger change (insufficient for abstention proof).
3. Expose an internal evaluation API.

The initial migration-free recommendation below was rejected after code-level
verification: V006 stores `final_response` only for `COMPLETED`; its
`ABSTAINED` event stores only a reason. V004 has normalized provider responses
but no authoritative binding to the response Platform accepted. A minimal V008
event binding is therefore product integrity, not evaluation-only pollution.

## Why

The repo already proves the key ingredients for this route:
- Phase 8A is explicitly fixture-only and still blocks on Scenario B/C, timeline/receipt harvesting, and release-scale evaluation.
- Phase 7 cross-service verification already runs against a disposable PostgreSQL container and records durable counts from `investigation_runs`, `evidence_records`, `ai_runtime.analysis_invocations`, `tool_gateway.execution_receipts`, and `tool_gateway.tool_audit_events`.
- Production tables already enforce the integrity model we want to observe, not copy: immutable investigation ledgers in V006/V007 and bounded tool receipts in tool-gateway V001.
- The evaluation docs already forbid hidden chain-of-thought and treat raw sensitive evidence as access-controlled, redacted, or out of band.

This makes the revised hybrid the simplest truthful path: V008 closes the
accepted-response audit gap, while the evaluator still observes only a
disposable database and introduces no production evaluation API.

## Trade-off Matrix

| Option | Blast radius | Security risk | Maintainability | Architecture fit | Verdict |
|---|---:|---:|---:|---:|---|
| 1. Internal evaluation API | Medium-high | High | Medium-low | Weak | Avoid |
| 2. Disposable cross-service PostgreSQL + normalized artifacts | Low | Low | Medium-high | Strong | Insufficient alone |
| 3. Minimal accepted-response event binding + disposable export | Medium | Low-medium | High | Strong | **Best** |

### 1) Internal evaluation API

- Pros: quick to prototype, easy to centralize logic, no production schema migration.
- Cons: new surface area, new authz rules, easy place to leak raw analysis or prompt material, and it duplicates logic that already exists in the runner and cross-service harness.
- Risk: evaluation becomes a separate trust zone instead of a projection of the existing system.

### 2) Disposable cross-service PostgreSQL + normalized artifacts

- Pros: no live customer data, no new runtime API, and easy rollback of the
  harness code path.
- Pros: aligns with current cross-service verification, current durable tables, and current `prepareValidationEvidence`/`publishValidationEvidence` artifact flow.
- Cons: you still need a carefully versioned query manifest, canonical serialization, and a little more code than a simple API call.
- Blocker: cannot prove accepted abstention because V006 discards its normalized
  response; V004 invocation rows are not authoritatively bound to Platform
  acceptance.
- Risk: if the allowlist or string-value scanner is sloppy, sensitive fields
  can still leak into the artifact.

### 3) Minimal accepted-response event binding + disposable export

- Pros: immutable proof of the exact response accepted at every round, including
  abstention; improves audit/replay truth independently of evaluation.
- Cons: forward-only migration and application/event contract changes.
- Risk: event payload expansion. Mitigate with the existing 1 MiB bound, exact
  keys, response validation, replay tests and forward-only rollback.

## Normalized Artifact Contract

Use a versioned, secret-free artifact schema. Recommended top-level shape:

```json
{
  "schema_version": "opsmind-evaluation-artifact-v1",
  "benchmark_id": "phase-08b-cross-service-eval",
  "scenario": {
    "family_id": "SIM-01",
    "scenario_id": "deployment-latency-regression",
    "scenario_version": "1.0.0",
    "seed": 73001,
    "fixture_digest": "sha256:..."
  },
  "source": {
    "git_head": "40-char commit",
    "working_tree_clean": true,
    "db_snapshot_fingerprint": "sha256:...",
    "query_manifest_digest": "sha256:...",
    "generated_at": "2026-07-26T00:00:00Z"
  },
  "run": {
    "run_id": "...",
    "organization_id": "...",
    "project_id": "...",
    "incident_id": "...",
    "status": "COMPLETED",
    "rounds": 2,
    "tool_calls": 1,
    "total_tokens": 1234,
    "terminal_reason": null
  },
  "evidence_records": [],
  "tool_receipts": [],
  "analysis_summary": {},
  "safety": {},
  "verdict": "PASS",
  "raw_artifact_refs": []
}
```

Allowlist the following field classes only:
- Scenario binding: ids, version, seed, fixture digest.
- Source binding: git head, working-tree cleanliness, query manifest digest, snapshot fingerprint, generation time.
- Run summary: ids, terminal state, rounds, tool count, token count, terminal reason.
- Evidence summary: `evidence_id`, `content_digest`, `source_type`, `source_identity`, `target_identity`, `created_at`, `trust_class`, `manifest_version`, `policy_version`, `redacted_fields`, `truncated`, `gateway_duplicate`.
- Tool receipt summary: `execution_id`, `request_digest`, `status`, `completed_at`, `result_digest`, `denial_code`, `manifest_version`, `policy_version`.
- Analysis summary: only display-safe outputs, citation ids/digests, confidence, counter-evidence ids, missing-evidence ids, and tool-intent counts.

Do not store:
- raw prompts
- provider hidden reasoning / chain-of-thought
- bearer tokens or capability secrets
- unredacted connector payloads
- raw tool response bodies
- customer telemetry
- anything not needed for scoring or provenance

If semantic RCA needs a trusted raw artifact, keep only a reference plus digest in the normalized artifact. Do not inline the raw body.

## Integrity Binding

Bind artifacts with layered digests:
- `artifact_digest = sha256(canonical_json)`
- `query_manifest_digest = sha256(canonical_sql_and_projection_allowlist)`
- `fixture_digest` from scenario ground truth
- `git_head` from the exact revision
- `db_snapshot_fingerprint` from the disposable cross-service database state

Per nested record, bind:
- source primary key
- row digest over the canonical allowlisted projection
- source table name
- schema version

This matches the repo’s current pattern: canonical JSON, digest checks, and fail-closed publication.

## Scenario Semantics

### Scenario B: insufficient evidence, abstain

Recommended contract:
- `abstain_allowed = true`
- terminal verdict should be `ABSTAINED`
- no unsupported RCA label is accepted
- no write, approval, or cross-tenant action is accepted
- citations may be sparse or empty, but they must not invent evidence
- confidence should be low or explicitly withheld

Scoring rule:
- Pass only if the artifact explicitly abstains and the output stays read-only and unsupported-claim free.

### Scenario C: conflicting evidence, counter-evidence handling

Recommended contract:
- do **not** collapse this into Scenario B
- require explicit counter-evidence material
- reward a cautious completion that names the contradiction and cites both sides
- permit abstain only if the evidence remains genuinely insufficient after allowed sources are exhausted
- penalize confident single-cause assertions that ignore the contradiction

Scoring rule:
- Pass only if the artifact surfaces the conflict, cites counter-evidence, and stays conservative about certainty.

## Test Matrix

1. Schema tests: artifact schema rejects unknown fields, raw prompt/body fields, and missing digests.
2. Digest tests: canonical serialization is stable; tampering with any nested row digest or source digest fails.
3. Query allowlist tests: only approved SQL/views/functions can populate the artifact.
4. Security tests: no secret, CoT, bearer token, or unredacted connector payload survives projection.
5. Scenario B tests: insufficient evidence yields `ABSTAINED` and no fabricated RCA.
6. Scenario C tests: conflicting evidence yields counter-evidence handling and cautious output.
7. Integration tests: disposable PostgreSQL reads bind correctly to V006/V007/V001-backed data.
8. Rerun tests: same scenario/seed/revision produces the same artifact shape and compatible verdict.
9. Rollback tests: deleting or reverting the harness leaves production schemas untouched.

## Migration And Rollback

### Revised hybrid

- Add V008 to require the normalized response in new `ANALYSIS_ACCEPTED`
  events; retain existing event history unchanged.
- Add versioned evaluation schema and runner changes.
- If the artifact contract changes, bump the artifact schema version and keep old artifacts readable.
- Roll back projector/harness independently. Roll back event writers only
  through a forward migration that first relaxes the new trigger contract.

### Option 3

- Requires forward-only Flyway changes in production-owned tables.
- Rollback is expensive or impossible once live data is written under the new shape.
- Evaluation-only columns become permanent maintenance debt.

### Option 1

- No DB migration, but a new API surface must be authz-guarded and versioned.
- Rollback is easier than option 3, but the security surface is worse than option 2.

## Final Call

Pick the revised hybrid.

It is the lowest-blast-radius path that can actually prove which normalized
response Platform accepted. The production change is an audit invariant, while
evaluation-only roles/views/artifacts remain disposable and secret-free.

## Sources

- `README.md`
- `docs/evaluation-strategy.md`
- `docs/testing-strategy.md`
- `docs/system-architecture.md`
- `plans/260719-1747-opsmind-ai-production-platform/phase-08-simulator-and-evaluation-baseline.md`
- `evaluation/runner/score-phase-07-trace-core.mjs`
- `evaluation/runner/score-phase-07-projection.mjs`
- `evaluation/runner/raw-analysis-contract.mjs`
- `scripts/validation/cross-service/run-cross-service-verification.ps1`
- `scripts/validation/cross-service/run-investigation-slice.mjs`
- `scripts/validation/cross-service/finalize-cross-service-report.mjs`
- `scripts/validation/validate-phase-08-evaluation-foundation.mjs`
- `services/platform-api/src/main/resources/db/migration/V006__investigation_run_persistence.sql`
- `services/platform-api/src/main/resources/db/migration/V007__bounded_evidence_records.sql`
- `services/tool-gateway/src/main/resources/db/migration/V001__durable_tool_gateway_state.sql`
- `services/ai-runtime/src/opsmind_ai_runtime/adapters/persistence/postgres_runtime_state_prepare.py`
- `services/ai-runtime/src/opsmind_ai_runtime/adapters/persistence/postgres_runtime_state_finish.py`
- `services/ai-runtime/tests/integration/test_postgres_runtime_state.py`
