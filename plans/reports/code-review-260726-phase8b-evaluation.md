# Phase 8B Production-Readiness Code Review

## Code Review Summary

### Scope

- Focus: current uncommitted Phase 8B production-path evaluation snapshot.
- Files: 88 changed/untracked files (55 tracked modifications, 33 untracked), covering evaluation contracts/scoring, cross-service harness and cleanup, PostgreSQL evaluator views, V008 accepted-analysis persistence, Java/Python response contracts, CI, tests, plans, and delivery docs.
- Size: tracked diff `+2168/-332`; untracked files approximately 4,361 LOC.
- Review protocol: edge-case scout first, then critical trust-boundary pass and informational correctness/maintainability pass. Base and API pre-landing checklists applied.
- Scout findings: live trace/scorer shape drift, dynamic identity drift, unstable Scenario C evidence ordering, incomplete provenance binding, cleanup/path failures, and missing migration-upgrade proof.

### Overall Assessment

**BLOCK.** Local deterministic tests pass, but they do not exercise the trace shape emitted by the production-path harness. Fresh Scenario A cannot satisfy tool scoring, Scenario B crashes the scorer, and Scenario C cannot match authored identity/digest bindings. Separate trust-boundary defects can redirect secret writes or recursive cleanup through a junction and can retain credentials/raw exports when cleanup fails.

The accepted-response status validation defect found during review was fixed in the current snapshot across SQL, Java, and Pydantic. It is not listed as an open finding.

Pre-landing result: **8 blocking implementation findings, 3 informational/evidence findings.**

## Critical Issues

### C1. Managed paths are validated after writes and do not reject reparse-point ancestors

**Evidence**

- `scripts/validation/cross-service/run-cross-service-verification.ps1:21-29` performs only a lexical report-prefix check.
- `scripts/validation/cross-service/run-cross-service-verification.ps1:113-130` creates the run/report roots, moves an existing report, and establishes credential paths before any reparse-safe validation.
- The first `Assert-CrossServiceManagedPath` call for these new artifacts is only at `scripts/validation/cross-service/run-cross-service-verification.ps1:629-632`.
- `scripts/validation/cross-service/cleanup-cross-service-run.ps1:29-42` checks the final run directory and descendants, but not the existing `.opsmind` or `.opsmind/cross-service` ancestors.
- `scripts/validation/cross-service/cleanup-cross-service-run.ps1:190-209` recursively removes that lexically derived run directory.

**Impact**

A junction at `.opsmind/cross-service` or `.opsmind/reports` can redirect private-key/token/report writes outside the repository. Because the final child need not itself be a reparse point, cleanup can recursively delete an external target. This is a trust-boundary and data-loss defect.

**Required fix**

Validate every existing ancestor from repository root through the managed root before creating, moving, writing, or deleting. Revalidate immediately before publication and recursive deletion. Refuse any reparse point/junction in the chain and use one shared helper for run, report, archive, secret, export, and cleanup paths. Add junction-ancestor tests for write and delete paths.

### C2. Cleanup is not unconditional and can retain credentials and raw exports

**Evidence**

- `scripts/validation/cross-service/cleanup-cross-service-run.ps1:29-42` can throw on a reparse point before deleting anything.
- Docker discovery/removal can throw at `scripts/validation/cross-service/cleanup-cross-service-run.ps1:89-105`.
- Private keys, the operator token, and `postgres.env` are deleted only at `scripts/validation/cross-service/cleanup-cross-service-run.ps1:107-130`.
- Raw evaluator exports are deleted later at `scripts/validation/cross-service/cleanup-cross-service-run.ps1:132-148`.
- `scripts/validation/cross-service/run-cross-service-verification.ps1:762-789` can replace the original harness error with `Cross-service cleanup helper failed`.

**Impact**

A missing/unhealthy Docker CLI, unexpected container state, or path-validation failure leaves credentials and raw exports on disk. The secondary cleanup exception also hides the primary incident.

**Required fix**

Perform best-effort secret/raw-export deletion in a `finally` path independent of process/container cleanup. Aggregate cleanup errors and preserve the original exception. Verify zero survivors without skipping later cleanup stages when an earlier stage fails.

## High Priority

### H1. The fresh A/B/C harness output is incompatible with the scorer

This is an implementation defect, not merely missing CI evidence.

**Scenario A/C — tool proof is absent**

- `scripts/validation/cross-service/run-investigation-slice.mjs:254-266` returns no `toolExecutions`; the emitted report also contains none.
- `evaluation/runner/cross-service-evaluation-projection.mjs:57-66` adds the projection/raw analysis but does not materialize projected receipts as `toolExecutions`.
- `evaluation/runner/score-phase-07-projection.mjs:161-166` requires `run.toolExecutions`.
- Direct reproduction with the runner-shaped Scenario A returned `INCOMPLETE` with `tool_selection=UNAVAILABLE`.

**Scenario B — live abstention crashes**

- The live contract intentionally requires `operatorProjection.analysis === null` at `scripts/validation/cross-service/run-investigation-slice.mjs:231-233`.
- `evaluation/runner/score-phase-07-projection.mjs:83-92` eagerly dereferences `displayAnalysis.missing_evidence`.
- Direct reproduction with the canonical B trace changed to the live null shape threw `TypeError: Cannot read properties of null`.
- `evaluation/fixtures/phase-07-trace.scenario-b.valid.json:45-68` fabricates a display analysis object and hides the API contract mismatch.

**All scenarios — tenant identity drift**

- The harness uses organization/project IDs at `scripts/validation/cross-service/run-cross-service-verification.ps1:79-80`.
- The scorer compares them to authored `truth.tenant_context` at `evaluation/runner/score-phase-07-projection.mjs:57-64`.
- Current ground truths use different fixed IDs.

**Scenario C — dynamic evidence cannot equal authored truth**

- Run IDs are random at `scripts/validation/cross-service/run-investigation-slice.mjs:162-166`.
- Evidence IDs derive from organization/run/intent at `services/platform-api/src/main/java/ai/opsmind/platform/evidence/EvidenceIdentity.java:14-35`.
- `evaluation/scenarios/conflicting-evidence-regression/ground-truth.json:45-50` binds a fixed evidence UUID and digest; line 30 also contains a placeholder manifest digest.
- Exact comparisons occur at `evaluation/runner/score-phase-07-projection.mjs:123-126,184-188`.

**Why tests miss it**

`evaluation/runner/score-phase-07-trace.test.mjs:80-88` calls the projector but overlays its result on an authored trace that already contains fixture `toolExecutions` and compatible fixed identities. It does not feed the runner-produced trace through enrichment and scoring.

**Required fix**

Define one production trace contract. Populate trusted tool executions from validated receipt projections; score Scenario B abstention semantics from the durable accepted response while accepting the documented null display projection; pass runtime scope as independently trusted run metadata rather than authored tenant UUIDs; resolve semantic counter-evidence bindings against the fresh projected evidence. Add runner-shaped A/B/C integration tests from trace producer output through export enrichment and scoring.

### H2. Scenario C assigns evidence meaning by unstable UUID order

**Evidence**

- `scripts/validation/cross-service/fixture-provider.py:122-156` treats `evidence[0]` as causal and `evidence[1]` as counter-evidence.
- `services/platform-api/src/main/java/ai/opsmind/platform/investigation/integration/AuthorizedInvestigationAiRuntimeClient.java:77-85` sorts evidence by the dynamically derived UUID.
- `EvidenceIdentity.java:14-35` includes the random run ID in that derivation.
- Scout reproduction over 20 derived run identities produced both orders (12/8).

**Impact**

The latency and error evidence can swap positions between runs. The provider then attaches causal/counter claims to the wrong evidence while structural citation validation still passes. This makes Scenario C nondeterministic and semantically false.

**Required fix**

Bind evidence by stable semantic identity (intent selector, source identity, or target/metric field), never array position. Add repeated randomized-run tests proving stable causal and counter-evidence assignment.

### H3. Invocation accounting and provenance are not bound to the accepted response

**Evidence**

- `evaluation/runner/cross-service-evaluation-export-rows.mjs:84-117` validates invocation fields independently but does not require response model/prompt/schema to equal invocation values, response usage to equal `actual_tokens`, response cost to equal `actual_cost_usd`, tool count coherence, or `finished_at >= started_at`.
- `evaluation/runner/cross-service-evaluation-projector.mjs:51-60` discards the invocation `actual_*`, model, prompt, and schema fields.
- Scout mutation with a different model/prompt plus `actual_tokens=999999` and `actual_tools=20` was accepted while the response still reported the original identity and 20 tokens.

**Impact**

The evidence artifact can attest one accepted model response while its durable invocation record says another model, budget, or cost. Aggregate budget claims are therefore not trustworthy.

**Required fix**

Enforce equality/coherence at export validation, retain the authoritative accounting fields in the typed projection, and verify timestamps and aggregate totals. Add mismatch tests for every field.

### H4. Connector provenance is self-asserted instead of observed

**Evidence**

- `scripts/validation/cross-service/create-evaluation-export-roles.sql:187-202` emits hard-coded `prometheus-read-only`, `prometheus`, and a harness-injected expected manifest digest.
- Durable receipt/audit rows expose manifest version but not the asserted connector ID/profile/digest.
- `evaluation/runner/cross-service-evaluation-export-rows.mjs:176-180` only checks that the emitted constants are allowlisted.
- The connector-substitution test mutates the already-authored export (`evaluation/runner/cross-service-evaluation-projection.test.mjs:93-100`); it does not prove the values came from the executed connector.

**Impact**

A substituted execution path can still receive the expected connector provenance because the exporter manufactures it. The gate proves configuration expectation, not actual connector identity.

**Required fix**

Persist or derive connector identity/profile/manifest byte digest from the executed request/receipt and compare that observed value against an independently pinned expectation. Do not inject the expected value into the observed side of the assertion.

## Medium Priority

### M1. Runtime JSON handling accepts malformed UTF-8

`evaluation/runner/evaluation-value-safety.mjs:102-114` decodes with `Buffer.toString("utf8")`, which replacement-decodes invalid byte sequences. The contract says UTF-8 bytes and should reject malformed input. Use a fatal `TextDecoder("utf-8", { fatal: true })` and normalize its error to a stable contract failure. Add invalid-byte tests.

### M2. Runtime receipt validation is weaker than its schema

`evaluation/runner/cross-service-evaluation-export-rows.mjs:183-185` accepts duplicate `evidence_digests`, while the JSON schema requires uniqueness. The CLI currently applies schema validation too, but direct module callers can observe a different contract. Enforce uniqueness in runtime validation and test it.

## Low Priority

None. Minor style findings omitted.

## Edge Cases Found by Scout

- Runner-shaped Scenario A: `INCOMPLETE`, tool denominator zero.
- Live-shaped Scenario B: scorer throws on null display analysis.
- Random Scenario C identities reorder evidence and invalidate authored evidence bindings.
- Invocation provenance mutations survive validation.
- Docker/path failures occur before secret and raw-export deletion.
- Junction ancestors redirect writes/deletes while final-child checks still pass.

## Plan and Evidence Status

- Phase 1: local strict-contract work largely present; durable accepted-response status coherence now enforced across SQL/Java/Python. Production durability remains unproved because heavy DB tests were not run.
- Phase 2: least-privilege views/projector are implemented, but live trace enrichment omits tool executions and connector provenance is self-asserted.
- Phase 3: authored A/B/C fixtures pass, but fresh B/C contracts are incompatible with the live runner/scorer and Scenario C ordering is unstable.
- Phase 4: workflow/docs are present. Exit remains correctly blocked: no exact-revision terminal-green CI artifact, worktree is dirty, and this review has blocking findings.
- Backward compatibility evidence gap: `scripts/validation/run-phase-04b-migration-upgrade.sh:81-91` stops at V007 and `.github/workflows/pr-quality.yml:644-653` still proves only V006→V007. `MigrationContractTest.java:150-170` string-inspects V008 rather than seeding V007 history and upgrading. Existing history is not rewritten, but an actual V007→V008 upgrade/read test is still required evidence.

## Verification

- `node --test evaluation/runner/*.test.mjs`: **PASS, 30/30**.
- `node scripts/validation/validate-phase-08-evaluation-foundation.mjs`: **Checkpoint PASS; PhaseExit BLOCK**.
- `git diff --check`: **PASS** apart from line-ending conversion warnings.
- Tester-reported `actionlint`: **PASS**.
- Heavy Docker/PostgreSQL, Maven, pnpm, uv, and full GitHub Actions paths: **not run locally due the stated C: storage restriction**. These are CI evidence blockers, not counted as local implementation failures.

## Metrics

- Type coverage: not measured; no repository type-coverage metric available across Java/Python/JavaScript.
- Test coverage: evaluator runner suite 90.72% lines, 75.89% branches, 98.30% functions per independent tester report.
- Linting issues: 0 from reported `actionlint`; project-wide lint not run.
- Query efficiency: export queries are scope- and row-bounded and use existing run/evidence/receipt keys; no N+1 loop found.
- Auth/authz: evaluator roles are NOBYPASSRLS and view-scoped; no identity-only sensitive operation found. Connector attestation remains untrusted per H4.

## Recommended Actions

1. Fix C1/C2 before any harness execution on shared or persistent infrastructure.
2. Establish and test the real runner→export→projection→scorer A/B/C contract (H1).
3. Replace Scenario C positional/fixture identity assumptions with semantic runtime bindings (H2).
4. Bind invocation and connector provenance to durable observed state (H3/H4).
5. Add strict UTF-8/runtime uniqueness checks and a real V007→V008 upgrade test.
6. Rerun lightweight gates, then obtain terminal-green exact-revision CI with uploaded A/B/C evidence before changing Phase 8 exit status.

## Unresolved Questions

- What durable field is intended to identify the actually executed connector when current receipt/audit rows do not store connector ID or manifest bytes?
- Should Scenario C counter-evidence truth bind by intent selector, source identity, or a stable semantic evidence role?

Status: DONE_WITH_CONCERNS

Summary: 8 blocking implementation findings; fresh A/B/C cannot currently satisfy the production scorer, and cleanup/path handling can expose credentials or delete outside the managed root.

Concerns/Blockers: C1-C2 and H1-H4 must close; exact-revision heavy CI evidence remains pending.

---

## Final Re-review — 2026-07-26

### Scope

- Focus: current Phase 8B worktree after all remediation rounds.
- Worktree: 108 status entries; 68 tracked files in the diff and 40 untracked files.
- Tracked diff: 3,254 insertions, 444 deletions. Untracked files: 5,381 physical lines.
- Review protocol: scout-first adversarial review plus the `ck:code-review` base and API pre-landing checklists, in blocking and informational passes.
- Re-tested: original C1/C2/H1-H4/M1-M2, audit/result/evidence binding, aggregate cost, Scenario C semantics, LF portability, Platform V008 rolling compatibility, Tool Gateway V002 rolling compatibility, Java call-site risk, SQL migration intent, and workflow syntax.

### Final Assessment

**Implementation verdict: PASS — no open Critical or High implementation findings.**

**Pre-landing / Phase 8 exit verdict: BLOCK — evidence only.** A fresh, clean, exact-revision Docker/PostgreSQL cross-service run and the heavy Java/Python/DB CI jobs have not been executed in this storage-constrained local environment. The repository validator correctly reports `CheckpointResult=PASS` and `PhaseExit=BLOCK` until that artifact exists.

This supersedes the original blocking verdict above for the current worktree. It does not erase the original findings or relax the phase-exit evidence contract.

### Critical Issues

None open.

#### C1 closed: reparse-point ancestor traversal

`Assert-CrossServiceNoReparseAncestors` now rejects redirected ancestors, not only the final child (`cross-service-harness-support.ps1:303`). The harness calls it before and after run-root creation and before export deletion (`run-cross-service-verification.ps1:75-151,726`). Cleanup revalidates before sensitive deletion and recursive run-directory deletion (`cleanup-cross-service-run.ps1:25,59,84,244`).

The dedicated path-safety test passes under the available Windows PowerShell host:

```text
CrossServicePathSafety=PASS ReparseAncestor=BLOCKED
```

#### C2 closed: cleanup ordering and error propagation

Cleanup removes secret files and transient exports before process/container operations (`cleanup-cross-service-run.ps1:59-97`), aggregates failures instead of stopping at the first secondary error (`:27-37,149-180,271-272`), verifies surviving managed resources (`:204-238`), and only removes the run directory after successful zero-resource verification (`:242-267`). The runner preserves the primary harness failure while reporting a secondary cleanup failure (`run-cross-service-verification.ps1:815-843`).

### High Priority

None open.

#### H1 closed: production A/B/C contract

The harness now creates scenario-specific scope/run identities, derives trusted tool executions from durable receipt/evidence rows, treats Scenario B's durable abstention as authoritative when the display projection is null, and resolves Scenario C evidence bindings against the fresh projected evidence rather than authored UUIDs. The runner-shaped Scenario A path passes. Fresh production B/C traversal remains part of the pending heavy evidence run, not an observed local implementation defect.

#### H2 closed: Scenario C ordering

The fixture provider resolves evidence by semantic label instead of array position. `test_fixture_provider.py` shuffles evidence 50 times and verifies the same causal and counter-evidence assignments; it passes.

#### H3 closed: invocation and aggregate accounting

Export validation binds model, prompt, schema, tokens, tool count, cost, and timestamps. The projector retains the authoritative values and the verifier checks snapshot aggregates. Per-run cost is the sum of every accepted invocation's `actualCostUsd`, not the last response (`score-phase-07-projection.mjs:115-123`). The regression that would previously undercount `0.01 + final 0.00` now fails the configured budget as intended.

#### H4 closed: observed connector provenance

Tool Gateway persists the runtime-resolved tool, action, risk class, connector ID/profile, and exact manifest-byte SHA-256. The exporter reads these observed columns; it no longer authors expected constants as observed data. Runtime manifest digest `sha256:2954739ada4a59714b4a06f9329bac2548cb47a1551f09f0453a6f8151884dc2` matches all three ground truths.

### Adjacent Adversarial Findings Closed

1. **Audit/result/evidence split:** a receipt must contain exactly one unique evidence digest and `result_digest` must equal it (`cross-service-evaluation-export-rows.mjs:205-215`). Binding also requires unique execution/audit identities and exact audit-result, receipt-result, and evidence-content equality. A mutation that changes evidence and receipt while leaving audit unchanged is rejected with `RECEIPT_BINDING`.
2. **Aggregate cost undercount:** scorer sums all accepted invocation costs; dedicated regression passes.
3. **Scenario C semantic drift:** canonical truth, Prometheus fixture, provider output, authored trace, and digests now agree that deployment-correlated latency supports the hypothesis while flat HTTP error counts oppose it. Random-order semantic verification passes.
4. **Invalid UTF-8 and duplicate receipt digests:** runtime parsing is fatal on malformed UTF-8; receipt evidence digests are exact-one and unique. Both have negative tests.
5. **LF portability:** scoped `.gitattributes` pins evaluation JSON/YAML/MJS, manifest JSON, and cross-service MJS/Python/SQL to LF. `git check-attr` returns `text=set` and `eol=lf`; the Phase 8 validator checks both attributes and bytes. All canonical fixture digests match their ground truths.

### Backwards Compatibility

#### Platform V008: expand/contract rolling safety verified statically

- V008 is explicitly an expand migration (`V008__accepted_analysis_event_binding.sql:1-4`).
- `ANALYSIS_ACCEPTED` permits either the exact legacy V007 key set or the exact response-bearing key set.
- New response-bearing rows validate normalized response status/shape; a completed response must equal the reducer snapshot's `final_response`.
- Existing legacy rows remain unchanged and intentionally unscorable because the evaluator rejects missing accepted analysis.
- The upgrade script seeds V006/V007 history, verifies its digest after V008, rejects malformed abstention, and proves a legacy writer can still insert after V008 (`run-phase-04b-migration-upgrade.sh:256,381-421`).
- Contracting to response-required events is correctly deferred until legacy writers drain.

No impossible deployment order remains. PostgreSQL execution is still required in CI.

#### Tool Gateway V002: legacy/new writer safety verified statically

- V001 writers may omit all six provenance columns.
- The V002 check accepts either all six null or all six present and valid (`V002__durable_tool_execution_provenance.sql:22-51`); partial provenance is rejected.
- New writers construct full provenance from the resolved manifest and persist it atomically with the audit row.
- Evaluation requires complete observed provenance for scored executions, so legacy rows fail closed rather than receiving fabricated identity.
- Integration tests cover a legacy post-V002 insert and partial-provenance rejection.

No schema-first rolling incompatibility found. PostgreSQL execution is still required in CI.

### Blocking Checklist Pass

- Concurrency: no new shared mutable state; durable writes remain transaction/constraint protected. Harness cleanup aggregates errors and preserves primary failure ordering.
- Error boundaries: cleanup, export validation, parsing, and scoring fail closed; no catch-and-swallow path found.
- API contracts: Java/Python/JSON status coherence and nullability are aligned. Stricter abstention/failure rules are intentional Phase 8 contract scope and have negative tests.
- Backwards compatibility: V008 and V002 are expand-safe as described above.
- Input validation: JSON duplicate keys, malformed UTF-8, unknown fields, scope drift, digest drift, timestamps, accounting, and external filesystem paths are validated at boundaries.
- Auth/authz: export roles remain `NOBYPASSRLS`, view-scoped, and run-scope registered. No new sensitive endpoint or identity-only authorization path found.
- Query efficiency: export operations are bounded by registered scope/run. The per-run harness export loop is bounded test orchestration, not an unbounded production N+1 query path.
- Data exposure: export safety rejects secrets/reasoning; cleanup prioritizes secret/raw-export deletion; no stack trace or credential exposure found.

### Informational Checklist Pass

No actionable informational issue remains. Style-only observations and concerns already resolved in the current diff are suppressed by the checklist.

Future note: any later durable cross-process transition-resume mechanism must define canonical replay behavior across legacy and response-bearing `ANALYSIS_ACCEPTED` shapes before V008 contracts. The current synchronous orchestration has no external per-transition resume boundary, so this is not a present rolling blocker.

### Final Verification

- `node --test evaluation/runner/*.test.mjs`: **PASS, 33/33**.
- `python scripts/validation/cross-service/test_fixture_provider.py`: **PASS, 1/1 with 50 randomized evidence orders**.
- `test-cross-service-path-safety.ps1`: **PASS; reparse ancestor blocked**.
- `validate-phase-08-evaluation-foundation.mjs`: **Checkpoint PASS; PhaseExit BLOCK; 0 errors**.
- `validate-repository-layout.mjs`: **PASS; 979 files checked; 0 errors**.
- `validate-phase-07-investigation-slice.mjs`: **expected exit 1; operator checkpoint PASS; phase exit BLOCK only because the report is not bound to a clean current revision**.
- Node syntax: **PASS, 41 files**.
- PowerShell parser: **PASS, 5 files**.
- `bash -n scripts/validation/run-phase-04b-migration-upgrade.sh`: **PASS**.
- `actionlint 1.7.12`: **PASS**.
- `git diff --check`: **PASS**; Windows line-ending conversion warnings only.
- Canonical fixture SHA-256 bindings: **PASS, 3/3**.
- Java constructor/interface call-site audit: all current `AnalysisAccepted`, `ToolManifest`, and `ToolAuditWriter` production call sites updated; no static omission found.

Heavy Docker/PostgreSQL, Maven, pnpm, uv, and complete GitHub Actions execution were not run locally due the stated C: storage restriction. Java compilation and SQL execution therefore remain evidence obligations.

### Plan Status Recommendation

- Phase 1: implementation complete locally; DB-backed accepted-response proof pending CI.
- Phase 2: implementation complete locally; fresh production Scenario A artifact pending CI.
- Phase 3: local A/B/C contracts and semantics complete; fresh production B/C artifacts pending CI.
- Phase 4: lightweight verification and blocker remediation complete; push, clean exact revision, terminal-green workflows, and uploaded artifact remain pending.

Do not mark Phase 8 complete until the heavy evidence is bound to the exact clean revision.

### Metrics

- Type coverage: not measured; no unified repository metric.
- Test coverage: prior independent evaluator report — 90.72% lines, 75.89% branches, 98.30% functions.
- Current evaluator tests: 33 passed, 0 failed.
- Linting issues: 0 from `actionlint`; full language lint deferred to heavy CI.
- Open implementation findings: Critical 0, High 0, Medium 0, Low 0.

### Required Delivery Actions

1. Run the full heavy CI matrix on a clean pushed revision, including Maven/DB integration and V006/V007-to-V008 plus Tool Gateway V001-to-V002 upgrade coverage.
2. Run fresh production-path Scenarios A/B/C and upload the exact-revision evaluation artifact.
3. Confirm all required workflows are terminal green and rerun the phase-exit validators against that clean revision.

### Unresolved Questions

None for implementation. Evidence still missing: exact commit SHA, CI run URL, and uploaded A/B/C artifact digest.

Status: DONE_WITH_CONCERNS

Summary: no open Critical/High implementation findings; all original blockers and adjacent adversarial defects are closed, with 33/33 local evaluator tests and all lightweight gates passing.

Concerns/Blockers: Phase 8 delivery remains blocked only on fresh clean-revision heavy CI and exact-revision cross-service evidence.
