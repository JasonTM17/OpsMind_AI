# OpsMind AI Progress

## Reporting Rules

- Report only behavior and artifacts verified in the current worktree.
- Link each completed claim to a command, file, or immutable CI artifact.
- Keep planned behavior separate from implemented behavior.
- Record blockers explicitly and leave downstream phases pending.
- Do not include secrets, raw credentials, or sensitive evidence.

## 2026-08-04 — Artifact runtime merge and repository documentation refresh

- PR #70 merged into `main` as `0788ee3ab6b189d5b08aaf78d0b4d6418951ada4`.
  Required checks passed, including PostgreSQL trust contracts, Compose health,
  Java platform/tool-gateway, Operator Web, AI Runtime, CodeQL, and Phase 8B.
- The merged slice includes V018/V019 lifecycle capability, run-bound
  authorized-read probing, disposable PostgreSQL lifecycle proof, validation
  wrappers, and synchronized architecture/deployment/blocker summaries.
- The repository guide was refreshed with a canonical documentation map and
  evidence/status rules. Local showcase/media edits remain working-tree state
  and are not release evidence.
- Production readiness is still blocked by the active conditions in
  [Blockers](./blockers.md); this merge closes the artifact runtime slice, not
  the entire A–Z product roadmap.

## 2026-08-04 — Child checkpoints reconciled to current CI

- The Phase 3 OIDC state/nonce child plan is complete. PR #64 head
  `f29638e81b483c3c95cfe995ce5ba729681793e8` passed PR Quality run
  `30881416141`, including Keycloak job `91904344586`, and merged as
  `57ac8498529ef9c093f65ee77fbc579a515359ca`. Production IdP selection,
  BFF/session conformance, federation, break-glass, and parent Phase 3/G2 remain
  open.
- Phase 4C V014/V015 default-off bounded upload-port Phase 2 is complete. PR
  #46 head `95c0b6ba203aba5c280aa9223ee6b4a369de6d7d`, merge
  `1f87187aff1ce56d577ad8df944ccf74dbfc3fdf`, and run `30777514150` pass;
  current run `30881416141`, PostgreSQL job `91904344606`, keeps the integrated
  contract green. Controlled ingress, scanning, `AVAILABLE`, hold/delete/purge,
  restore, supported backend/KMS conformance, Phase 3 of the artifact plan, and
  B-006/B-008/B-012 remain open.
- Phase 8B is revision-bound complete, and current cross-service run
  `30881416173` passes. Parent Phase 8 remains `BLOCK` on B-013 held-out cases,
  qualified human records/adjudication, calibration, and human comparison.

## 2026-08-03 — Phase 9 repository-owned runtime conformance complete

PR #45 head `96edefc16a5a92442888725ca31c6db2e4b2a0c6`, merge
`e03c5b39eb3511ac276113087f958c6117658140`, and PR Quality run
`30775354989` supersede the repository-owned gaps recorded below. Revision-bound
CI proved pinned local Temporal restart/replay with zero skips, a fresh
compatible workflow poller, five bounded reconciliation series from a healthy
live scrape, and one sanitized CI-local Alertmanager callback.

PR #56 head `23d2e1de7820ffca8bb69292145eb78950d9f288`, merge
`c1137c3c686b1e8582e6ef0140f2e12bbe803f2b`, and run `30802636501`
added explicit pinned `promtool check rules`; its artifact reports 10 recording
and seven alert rules successful alongside config and deterministic rule tests.
The repository-owned Phase 8 runtime-conformance slice is complete. B-017 stays
active for production database query-plan/latency and DR, live Temporal
retention/read-only authorization, and external paging delivery. B-013 remains
active; no production admission, G4/Phase 9 exit, or release is claimed.

## 2026-07-29 — Exact-workflow reconciliation source and local contract integrated

The integration branch now adds V013 and a default-off read-only reconciliation
lane without adding another Temporal start surface:

- `opsmind_workflow_reconciler` has no direct table/sequence/function access
  after blanket revocation and receives `EXECUTE` on exactly claim, settle, and
  aggregate-status functions. A separate NOLOGIN resolver owns them.
- Claim is one-row, lease-fenced, ordered, and does not increment normal
  workflow-start attempts. Settlement supports exact match, two qualified
  absence samples, dispatcher reactivation, mismatch, retry, and blocked
  uncertainty while preserving canonical `PENDING` state when proof is absent.
- `TemporalInvestigationWorkflowObserver` calls
  `DescribeWorkflowExecution` by workflow ID, pins history to the returned first
  run ID, and reads one first-history event. For a Continue-As-New chain, that
  first-run event—not mutable current-execution description fields—is the source
  for workflow type, task queue, memo digest, and decoded start input. The
  observer interface exposes no Start, signal, update, or query method.
- The Java pre-observation fence maps a row older than the configured maximum
  handoff age to `workflow.reconciliation-handoff-age-exceeded`; settlement
  keeps the canonical binding/outbox `PENDING` and blocks the reconciler epoch.
- `.env.example` declares the fixed reconciler role and disabled datasource.
  Windows/portable launchers reject its password in `.env`, require a distinct
  process-scoped value for application Compose, and reject username drift or
  password reuse. The datasource defaults to a 3,000 ms connection timeout
  bounded to 250-30,000 ms and a 1-second query timeout bounded to 1-30
  seconds. That query bound is applied to JDBC statements, the PostgreSQL
  socket, and JDBC transactions; startup rejects the configuration unless
  connection acquisition plus query timeout is strictly shorter than both the
  settlement margin and `lease duration - settlement margin`.
- Management uses port `8082`; checked-in defaults expose health only. Compose
  explicitly enables `health,prometheus` on the internal network for a
  reconciliation scrape that keeps only bounded aggregate series.
- Seven alert rules cover blocked, exhausted, retention-ineligible, warning and
  critical lag, not-ready/scrape-down, and no-progress conditions. Every rule
  links the cutover runbook.

Verified local evidence:

- `scripts/validation/run-phase-09-reconciliation-postgres-contract.sh` ended
  `ReconciliationPostgresContract=PASS` against disposable PostgreSQL after
  V001-V013, followed by `ContractCleanup=PASS`. Its corrected 55-marker run
  proved direct read/write and trigger-function denial, the global exact-three
  executable set, membership-drift denial, match, two-sample absence,
  reactivation, mismatch, retry, blocked/retention/exhaustion `PENDING`
  preservation, lease fencing/takeover, cross-tenant denial, and atomic rollback
  at four settlement failpoints.
- The local V012-to-V013 upgrade probe also passed the exact-three executable
  set and PUBLIC trigger-function denial. The same reconciliation contract and
  V006-to-V013 upgrade/recovery checks are wired into PR Quality; no
  revision-bound run of that updated job is claimed yet.
- Manual `javac` compile and test-compile of the changed reconciliation main and
  test surfaces passed against the cached dependency classpath.
- Phase 9 source validator reports `Errors=0` and
  `WorkflowHandoffResult=PASS`; the observability validator reports internal
  scrape, bounded labels, aggregate recordings, and seven alerts.

This does not close B-017. `D:` had 17.86 GiB free on 2026-07-29, below the
20 GiB heavy-work floor, so full Maven, Docker, exact-head CI, performance, and
DR gates remain deferred. No production/live Temporal namespace, retention
proof, compatible worker, or read-only credential conformance exists.
Prometheus still needs pinned `promtool` plus live-scrape proof, and the
repository has no Alertmanager receiver or page-delivery receipt. Phase 9 and
roadmap G4 remain in progress; B-013 and B-017 remain active.

## 2026-07-28 — G1 and Phase 2 immutable clean-runner evidence complete

PR #23 feature head `a3cd81b8912b288b340a82b6b31aecf8cc22dffd`
and squash merge `659ba823a1dd8bc867a6fe9cca5187f475dec979`
resolve to the same Git tree
`25d83c9a19669542c94ca915ed96b20fe3bea8ac`; the merge therefore preserves the
exact source state that passed review.

PR #23 reports 13 passing checks: 12 successful jobs in PR Quality run
`30327014212` plus the successful Cross-service workflow. Downloaded immutable
artifacts prove:

- foundation artifact `8676036033`
  (`sha256:bd85d9d33e89fd136711bd3a77c9a59927a5af2473bd69777afeb72f920d32e9`):
  repository layout `Errors=0`, working-tree/history secret findings `0`,
  actionlint 1.7.12 and ShellCheck `PASS`;
- Linux bootstrap artifact `8676068747`
  (`sha256:af3d41d4edac784377fb47a64db8fa29888a8fcf096682780d8c93e089e9eea3`):
  capacity/storage roots and clean setup finish `Result=PASS`;
- Windows bootstrap artifact `8676105801`
  (`sha256:bbbb05f49dfa80f50e7f9f5b5107ab4bd369fa4a63a020553ceed7e1b8447ff8`):
  storage governance, setup, and secret tests finish `Result=PASS`;
- Compose artifact `8676138049`
  (`sha256:ab3daed1fcb01e31e9a4e27af58479d4b1d4ea23826d5ccf951fdd721276fe5d`):
  all application services become healthy and cleanup finishes
  `CleanupResult=PASS`;
- identity artifact `8676144021`
  (`sha256:d3639aed8a3a8e4dfc8b5f74fce753055869404b97e8ba9c5431ac26be3449b7`):
  the pinned non-production Keycloak reference conformance job passes.

Cross-service run `30327014218` is also green on the same source state. The
clean-runner and Compose evidence that kept Phase 2 open now exists, so Phase 2
and G1 are complete. Phase 3+, production IdP, DeepSeek/legal egress, live
connector, evidence-object lifecycle, held-out/human evaluation, Temporal
workflow, RAG, remediation, staging/production, DR, and final release remain
open exactly as tracked in their owning gates.

## 2026-07-27 — Tool Gateway tenant-isolation immutable evidence passed

Implemented in the current branch:

- Trusted `TenantProjectScope` is created only after delegated capability
  verification and exact request binding. Receipt leases carry that scope
  through replay, success, completion, and abandon.
- Tool Gateway V003 adds transaction-local tenant/project context, forced RLS
  for receipts and verified audit events, and a distinct tenant-free,
  insert-only, append-only lane for pre-verification security decisions.
- Receipt claim/select/reclaim/complete/abandon SQL retains explicit
  tenant/project predicates. A global `execution_id` collision hidden by RLS
  returns the existing generic conflict rather than a storage failure.
- Connector I/O remains outside database transactions. Scoped success audit and
  receipt completion still commit atomically. Startup rejects an execution
  lease that cannot cover the longest enabled connector bound plus a fixed
  finalization margin. The stored lease expires at the earlier of the configured
  lease bound or request deadline plus that margin, so finalization immediately
  after the signed deadline remains fenced but does not lose its audit/receipt.
- Request digesting now sits inside the fail-closed decision boundary. Nested
  JSON nulls cannot escape it, non-canonicalizable input is audited before
  capability verification, and authenticated malformed-body/missing-capability
  delivery failures use the tenant-free lane.
- Readiness verifies schema usage and the exact single RLS policy definition,
  not only policy names. Adversarial PostgreSQL tests revoke schema usage and
  replace a policy with same-name `USING (true)` drift.
- Cross-service evaluation now binds Tool Gateway tenant/project context before
  its security-barrier views read receipt/audit state.
- A disposable V002-to-V003 script seeds legacy receipt/audit rows before the
  migration, then checks preservation, forced RLS, no-context denial, scoped
  access, table-owner enforcement, same-tenant/foreign-project denial, exact
  policy definitions, and the separate unverified lane.

Current local evidence:

- Tool Gateway compile and test-compile pass.
- Reviewer-remediation suite: 13/13 pass.
- Full Tool Gateway suite: 65 tests, 53 pass and 12 PostgreSQL-gated skips;
  zero failures/errors.
- Secret scan covers the working tree, Git index, configured artifacts, and
  129 historical commits: zero findings.
- Phase 6 and Phase 7 static checkpoints report zero implementation errors.
  Their broader phase exits remain blocked for the already documented external
  work.
- Local PostgreSQL/Docker evidence was not run because `C:` remained below the
  repository's 10 GiB heavyweight-work capacity guard. The exact PostgreSQL,
  upgrade, pool-reuse, and cross-service gates are wired into GitHub Actions.

Immutable CI evidence for source
`269bd39e626836607fe66ed7eb050e1aa309044a` is green:

- PR Quality run `30279072972` is terminal `success`; PostgreSQL job
  `90022080029` is terminal `success`.
- PostgreSQL artifact `8658901958`
  (`sha256:1e76f5c67d0abc726b6d0779022f5005d777fcea63e52123cc176d1baabf909e`)
  proves V002-to-V003 upgrade, legacy preservation, forced RLS including the
  migrator owner, no-context denial, scoped runtime reads, same-tenant/
  foreign-project denial, and the tenant-free unverified lane. The targeted
  persistence boundary runs 12 tests with zero failures/errors; the full local
  Tool Gateway suite remains 53 passed and 12 PostgreSQL-gated skips.
- Cross-service run `30279067839` and artifact `8658216777`
  (`sha256:a5e2798b5c3035bdca3992f083cefbafc8df0121f35bd1447dcf3d46478b27a7`)
  are terminal green. Scenarios A/B/C pass with 100/1/1 samples, cleanup
  passes, and the Phase 7 regression checkpoint is `PASS`.

Independent review round three confirms zero unresolved P0/P1 findings.
`B-016` is resolved. This slice does not change any other active provider,
connector, lifecycle, SLO, DR, human evaluation, dependency, staging,
production, or release blocker.

## 2026-07-27 — B-015 dependency advisory disposition

Dependabot alert #16 (`GHSA-mh99-v99m-4gvg`, CVE-2026-14257) is dismissed as
`tolerable_risk` with a recorded compensating-control rationale. The repository
still keeps the high finding visible to local audit policy: `pnpm audit` reports
one ignored high finding, while the then-current verifier passed with the
4,000,000-character bound and review date `2026-08-09`. At that point the
ESLint graph still required CommonJS `minimatch@3`, so the local
`brace-expansion@1.1.16` patch and the `5.0.7` to `5.0.8` override remained.
Dependency policy run `30282520687` is terminal green. This resolves B-015's
alert-disposition gate, not the future dependency-upgrade follow-up.

## 2026-08-01 — B-015 upstream dependency remediation

The former temporary disposition is superseded by the upstream fixed legacy
release `brace-expansion@1.1.17`. The lockfile no longer selects `1.1.16`, and
the workspace has neither a local patch nor an audit ignore for
`GHSA-mh99-v99m-4gvg`. The renamed resolution verifier proves the resolved
runtime version and bounded expansion behavior in CI. Exact head
`1ccdb33c9eec9fe37375902ef487a335f16c1cf0` passed all 13 PR Quality and
cross-service checks (`30693663371`, `30693663369`), then PR #42 merged as
`ba79d27bf41a3ed5d23bdf1d3fc08ecb0238c6e2`. Dependabot now reports alert #17
as `fixed`; this closes only the dependency advisory and does not close any
provider, connector, lifecycle, evaluation, staging, DR, or release gate.

## 2026-07-28 — DeepSeek V4 Flash endpoint alignment

The provider adapter already targets the approved `deepseek-v4-flash` model.
This slice aligns the checked-in non-secret defaults with DeepSeek's official
OpenAI-compatible origin, `https://api.deepseek.com`, in `.env.example` and
Compose. The runtime still defaults to `AI_PROVIDER=disabled` and
`OPS_ENABLE_DEEPSEEK_EGRESS=false`; a key or endpoint value alone cannot enable
egress. The model identifier and base URL are verified against the
[official DeepSeek model documentation](https://api-docs.deepseek.com/quick_start/pricing/).

Verification on the exact branch worktree:

- AI Runtime suite: `168 passed, 5 skipped` (the skips require disposable
  PostgreSQL).
- Ruff: pass.
- mypy: pass.
- `docker compose config --quiet`: pass.
- Phase 5 static checkpoint: `CheckpointResult=PASS`; phase exit remains
  blocked by B-004 and the missing externally injected rotated-key synthetic
  smoke evidence.

## 2026-07-27 — Incident activity timeline bridge evidence gate passed

Implemented in the current worktree:

- The existing incident timeline route retains legacy `application/json` as its
  default READ/version-cursor representation. The opt-in
  `application/vnd.opsmind.incident-activity-timeline.v1+json` representation
  requires `incident:analyze` and `ANALYZE` access.
- RFC-style `Accept` handling uses the most-specific matching range before
  quality, retains JSON on ties, returns `406` for malformed, unsupported-only,
  or parameterized vendor requests, sets `Vary: Accept` for both successful
  representations, and sets `Cache-Control: no-store` for the vendor response.
- The vendor view is a forward-only live `UNION ALL` of the incident and
  investigation ledgers. Both branches filter organization/project/incident,
  select only the eight metadata fields, and do not select JSON payloads or
  free text. Late backdated commits require a new traversal; no row is copied
  into the incident ledger.
- V009 defines two concurrent ordering indexes and sets
  `executeInTransaction=false`.

Immutable evidence for source
`a975f922fcd93c71479b9e15563643a9ea1aa04f`:

- PR Quality run `30257587569` is terminal green. PostgreSQL job `89950772823`
  and artifact `8650178111` (`postgres-trust-contracts`) prove V009
  fresh/upgrade, invalid-index recovery, query-plan, legacy-write, cleanup, and
  3/3 activity HTTP gates; pooled tests pass 17/17 and the alternating contract
  records `Result=PASS`.
- The fixture has 60,600 incident and 61,206 investigation rows. Append p95
  pre/post is 1.346/1.503 ms (+11.66%) and 2.265/2.356 ms (+4.02%). Vendor
  initial/rank-0/rank-1 p95 is 2.563/1.466/1.533 ms.
- Combined indexes are 11,657,216 bytes over 121,806 rows and 108,347,392
  table bytes: 95.70 bytes/row and 10.76%. All plan thresholds pass.
- Cross-service run `30257587543` and artifact `8649696519`
  (`phase-08b-cross-service-evaluation`) are terminal green. Phase 7 records
  OperatorWorkspace/CrossService/Checkpoint/PhaseExit `PASS`; A/B/C pass,
  Scenario A has 100 warm runs, and the exact CI evaluation command passes
  61/61.

These are CI fixture/test gates, not production latency, SLO, or rollout
evidence. This closes only the Incident Activity Timeline Bridge plan slice.

At the time of this bridge checkpoint, `B-016` was still open alongside
`B-004`, `B-005`, `B-006`, `B-007`, `B-008`, `B-011`, `B-012`, `B-013`, and
`B-015`. The later immutable Tool Gateway evidence entry above resolves only
`B-016`; this bridge does not close provider/legal, live connector, lifecycle,
load/SLO, DR, held-out/human evaluation, or dependency gates.

## 2026-07-26 — Phase 8B production-path evaluation delivered

Implemented:

- Three deterministic, training-ineligible families: A latency regression, B
  `ABSTAINED` with zero tools when evidence is unavailable, and C two opposing
  read-only evidence collections with digest-bound counter-evidence and
  cautious confidence.
- Platform V008 rolling-compatible expand migration: strict immutable binding
  for response-aware writers, exact legacy V007 shape retained temporarily, and
  upgrade proof for both byte-stable history and a post-V008 legacy append.
  Evaluator exports require response-bearing events; contract migration waits
  until old writers are drained.
- Tool Gateway V002 rolling expand with durable observed tool/action/risk,
  connector ID/profile, and runtime manifest-byte digest for new writers;
  exact legacy null tuples remain temporarily writable but are not
  evaluation-eligible.
- Strict bounded cross-service export/projector, typed
  raw-byte/domain-separated canonical digests, durable derivation of trusted
  tool executions, accepted-response invocation accounting and summed run cost,
  fatal UTF-8 decoding, duplicate-evidence rejection, and exact audit/receipt/
  persisted-evidence digest binding.
- Scenario C metric-semantic selection independent of row/UUID order, with
  selector-bound dynamic counter-evidence.
- Scoped `.gitattributes` LF contracts and Phase 8 EOL assertions keep
  raw-byte fixture/manifest/query provenance identical across Windows and
  Linux checkouts.
- Disposable least-privilege export roles: a non-login view owner with
  allowlisted source columns and a non-inheriting read-only evaluator restricted
  to security-barrier views under exact organization/project/incident/run/actor
  scope.
- Reparse-ancestor validation before managed writes; cleanup removes raw SQL
  exports and ephemeral credentials first, aggregates cleanup errors, and
  refuses unsafe recursive removal.
- Human-baseline ingestion validates the listing and every bounded ASCII-safe
  `.json` name before sorting/path access, forbids object coercion, and wraps
  record stat/read failures in controlled single-line contract errors.
- `.github/workflows/cross-service-evaluation.yml` wiring for same-job builds,
  executable/source attestation, fresh A/B/C scoring, cleanup verification, and
  artifact upload.

Verified:

- Current evaluation tests pass 60/60.
- `node scripts/validation/validate-phase-08-evaluation-foundation.mjs`:
  six schemas, ten families/three implemented, held-out `UNAVAILABLE` zero
  cases, human baseline `UNAVAILABLE` zero cases, three canonical results,
  eight metrics, four negative cases, zero errors, checkpoint `PASS`, phase
  exit `BLOCK`.
- PR-quality run `30209210001` and cross-service run `30209209999` pass for
  revision `df4620313a3f39721ef1bb521a9cf7ddcac5929c`.
- Cross-service A/B/C score `PASS` with samples `100/1/1`, all eight metrics
  passing, and `GitTree=0`.
- Artifact `8634029083` is 221,461 bytes, created
  2026-07-26T16:01:34Z, and expires 2026-10-24T15:54:25Z.
- Two independent process-supervision reviews pass after fixes.

Still open outside Phase 8B:

- No Phase 8/A-Z G4 completion, held-out accuracy, population
  p95, calibration, human benefit, live DeepSeek, or production connector
  conformance is claimed.
- External held-out payloads and qualified human records/adjudication remain
  unavailable. Phase 9 infrastructure may start, but threshold freeze and exit
  wait for the reviewed human pilot.

## 2026-07-26 — Historical Phase 8A evaluation contract foundation

This entry records the earlier Phase 8A state and is superseded by the Phase 8B
current-state entry above. Its Scenario-B/C and harvesting gaps were accurate
at that checkpoint, not current implementation claims.

Implemented:

- Versioned `scenario-ground-truth-v1`, `benchmark-result-v1`, and
  `benchmark-manifest-v1` schemas with strict additional-property rejection.
- Secret-free deployment-latency Scenario A fixture with SHA-256 digest binding,
  tenant/data-class context, read-only tool policy, budgets, model/prompt/schema
  versions, and explicit training ineligibility.
- Ten-family SIM registry with only `SIM-01` implemented; `SIM-02` through
  `SIM-10` remain visibly pending/reserved.
- Deterministic scorer for structured output, RCA label/confidence, citation
  binding, read-only safety, tool receipt linkage, latency, and cost. Missing
  raw projection/receipt artifacts return `INCOMPLETE`, never a false PASS.
- `evaluate` launcher wiring and D-backed evidence publication through the
  existing safe artifact-root helper. The CLI also rejects traces not bound to
  the current Git revision and clean-worktree state.

Verified:

- `node --test evaluation/runner/score-phase-07-trace.test.mjs`: 17/17 passed,
  including tenant/run identity, budget, selector, and semantic-RCA fail-closed
  cases.
- `node scripts/validation/validate-phase-08-evaluation-foundation.mjs`:
  `Errors=0`, 3 schemas, 10 families, 3 negative cases; checkpoint PASS,
  PhaseExit BLOCK.
- PowerShell command surface: 25/25; portable shell command surface: 24/24.
- The existing pre-change Phase 7 trace is intentionally rejected as stale
  before scoring; a fresh current-revision trace is required.

Not completed:

- No claim of Phase 8/G4 completion, held-out quality, 99% validity, population
  p95, calibration, human benefit, Scenario B/C, live DeepSeek, or production
  connector conformance.
- Docker/Java/provider runs were not repeated while C: remained below the
  documented 10 GiB floor and the local Docker daemon returned HTTP 500.

## 2026-07-19 — Architecture and A–Z plan

Completed:

- Parsed and traced the master requirements into 79 identifiers.
- Produced a sixteen-phase implementation plan with dependencies, risks, acceptance criteria, and 35 final Definition of Done items.
- Applied four hostile review lenses and integrated fifteen deduplicated corrections.
- Strict plan validation reports sixteen phases, zero errors, and zero warnings.
- Configured the Git `origin` remote for the project repository.

Evidence:

- [A-Z plan](../plans/260719-1747-opsmind-ai-production-platform/plan.md)
- [Requirements traceability](../plans/260719-1747-opsmind-ai-production-platform/research/master-prompt-requirements-traceability.md)
- [Red-team review section](../plans/260719-1747-opsmind-ai-production-platform/plan.md#red-team-review)

Not completed:

- No runtime service, database schema, provider call, connector, UI, deployment, or production control exists.

## 2026-07-19 — Phase 1 operating envelope

Implemented:

- Storage capacity checks for Windows C:/D: and every distinct portable filesystem containing the workspace or configured heavy-state roots.
- Storage-root validation for cache, evidence, data, and model directories.
- Fail-closed exit codes and bounded verification transcripts.
- Direct PowerShell tests for forced block, forced pass, writable D-backed roots, and Windows system-volume rejection.
- Canonical architecture, PDR, local development, deployment, testing, evaluation, dataset, security, code, roadmap, blocker, decision, and ADR documents.
- Secret-safe `.env.example` and generated-artifact ignore policy.

Verified:

- Windows storage guard tests pass `12/12`; portable storage tests pass `11/11`, including forced low-space, distinct-filesystem, unsafe artifact roots, missing external roots, Windows/POSIX path aliases, device/UNC, reparse, and overlap cases.
- Product/production contract mutation tests pass `34/34`, including typed values/bounds, strict UTF-8/JSON/canonical-number handling, SemVer schema parity, exact-case and duplicate-property rejection, transcript-injection defense, schema fingerprint drift, pending/rejected states, and evidence-source overwrite protection.
- Secret-scan canary tests pass `12/12`, including benign token-name controls, ignored configuration, UTF-16 and UTF-32 behavior, namespaced JSON/YAML/env credentials, configured external artifacts, exact staged index state, Git-history content and sensitive paths, and binary fail-closed handling.
- Default-evidence safety tests pass `6/6`, proving missing or repository-ancestor artifact roots cannot be created or modified by contract, documentation, or secret gates.
- Composite governance suite passes `10/10` against the approved contract after
  harness alignment; `test-product-production-contract.ps1` and
  `test-phase-01-governance.ps1` now reflect the validator's approved-state
  exit code of `0`.
- The concrete G0.5 recommendation proposal validates as `STRUCTURE_VALID_PENDING` with exit `10`; it remains separate from the authoritative contract and carries no approval metadata.
- Documentation validation checks 46 Markdown files and 93 local links with zero errors.
- Project-scoped secret-pattern scan checks 78 product/evidence/review files plus bounded Git history with zero findings.
- Independent tester reproduced the Phase 1 gates. Independent review findings drove strict type/JSON validation, complete scan surfaces, safe evidence publication, concurrent canary isolation, and portable path/root hardening. The final frozen-worktree controller review reports no unresolved P1/P2 defect in the Phase 1 boundary.

G0.5 approval and completion:

- The project owner approved the complete twelve-decision recommended baseline;
  the approval scope and source statement are preserved in the
  [approval record](./decisions/g0-5-approval-2026-07-19.md).
- The authoritative contract records typed approved values, accountable roles,
  one RFC 3339 timestamp, and one canonical evidence URI.
- Fresh storage checks passed with C: above the 10 GB floor and D: above the
  20 GB floor.
- The strict contract validator returned `Result=PASS` with exit code `0` and
  published the Phase 1 evidence transcript.
- The phase-01 governance wrapper reran clean after aligning the harness to the
  approved contract semantics.

Current phase state: **complete**, six of six exit criteria proven. Phase 2 is
authorized, subject to a fresh capacity/root preflight before each heavy command.

## 2026-07-20 — Phase 3 trust/data foundation in progress

Implemented locally:

- Versioned `/api/v1` OpenAPI and auth/problem-detail JSON Schema contracts with synthetic, secret-free fixtures.
- Spring Security fail-closed default and standards-based OIDC resource-server
  adapter with issuer/JWKS signature, audience, bounded lifetime/clock skew,
  subject, mandatory MFA AMR policy, and checked-in `PT5M` maximum lifetime;
  tenant claims are not treated as
  authority.
- Persistence-enabled `/api/v1` requests recheck the verified issuer/subject
  against platform identity authority; unknown or deprovisioned users deny and
  authority-store failure fails closed.
- Transaction-local tenant context, tenant-scoped project read path, opaque paging tokens, delegated-capability validation ports, and replay/budget checks.
- PostgreSQL V001 identity/tenant/outbox/inbox/audit foundation plus forward
  V002 dispatcher migration: forced RLS, append-only audit, explicit non-owner
  grants, and non-login context/dispatch resolvers.
- Compose role split: `platform-migrate` owns Flyway; `platform-api` runs with
  append-only `opsmind_app`; the dormant `opsmind_dispatcher` identity alone
  owns lease/ack state. Pairwise-distinct passwords remain process/secret-manager inputs.
- Event payload JSON-object and exact UTF-8 SHA-256 verification before outbox insertion.
- Bounded idempotency-key/request-digest persistence and strict `If-Match`/optimistic-version helpers; malformed verified JWT claims now fail as safe authentication errors instead of leaking a 500 path.
- Environment-gated Hikari/PostgreSQL test with a one-connection pool, plus a
  disposable local harness and matching CI evidence step.
- Crash-safe outbox lease/retry/poison primitives, transactional inbox
  completion/reclaim primitives, exact payload-byte preservation, and a
  database-level contiguous aggregate-sequence trigger.
- Bounded tenant scheduler and transaction-local dispatcher workload binding;
  tenants require an active service account with the exact dispatcher audience,
  scope, and database principal before their events become schedulable.

Verified in this worktree:

- `node scripts/validation/validate-phase-03-trust-foundation.mjs` — `Result=PASS`, 50 files checked.
- `node scripts/validation/validate-repository-layout.mjs` — `Result=PASS`, 230 files checked.
- `mvn verify` — PASS: 27 tests discovered, zero failures/errors; 22 normal
  tests passed and five live-database tests skipped outside their guarded harness.
- Vendor-neutral OIDC token-policy tests reject missing MFA, excessive token
  lifetime, invalid subject/audience/time claims, and unsafe configuration.
- Local PostgreSQL 18 migration/RLS/Hikari matrix — PASS; the one-connection
  runtime pool did not leak context after commit, rollback, invalid membership,
  statement timeout, or a missing-context/background transaction. The SQL
  contract proved role separation, cross-tenant denial, outbox order
  uniqueness, inbox deduplication, idempotency isolation, and append-only audit.
  The guarded identity test proved active-user resolution, immediate denial
  after deprovisioning, and denial of an unknown issuer/subject mapping.
  The two-role dispatcher test proved the web role cannot update dispatch
  state, the dispatcher sees zero rows before binding, cross-tenant switching
  in one transaction denies, fair bounded scheduling advances to tenant B, and
  tenant/workload context clears after transaction completion.
  Cleanup markers were `PackageExit=0`, `PoolContractExit=0`,
  `ContractExit=0`, and `ResidualObjects=0`.
- Live outbox/inbox fault matrix — PASS: append and local state rolled back
  atomically before commit; expired claims were reclaimed; publish-before-ack
  replay produced two physical deliveries but one idempotent logical effect;
  stale lease acknowledgement was rejected; retry delay, poison, aggregate
  ordering/gap rejection, inbox rollback, acknowledgement loss, received-orphan
  reclaim, and poison denial all converged. Exact JSON payload bytes survived
  the `jsonb` round trip. Durable transcript:
  `artifacts/verification/phase-03/outbox-inbox.txt`.
- POSIX/PowerShell launcher syntax and focused command-surface tests — PASS
  (24/24 and 25/25); missing migration secrets exit deterministically with code 2.

## 2026-07-21 — Local Keycloak reference conformance

Verified locally on Windows:

- `pwsh -NoProfile -File .\scripts\validation\run-phase-03-keycloak-conformance.ps1`
  completed with `Result=PASS` against digest-pinned Keycloak 26.7.
- The isolated HTTPS profile passed PKCE S256; direct-grant and wrong-verifier
  denial; TOTP enrollment without MFA, MFA `amr`, and exact same-code/
  same-timestep replay denial; RP-initiated logout and refresh-after-logout
  denial; anonymous, missing-MFA, and tampered-signature Platform API denial;
  JWKS rotation refresh; old refresh-token reuse denial after rotation;
  a separate refresh family for the pre-revocation positive control;
  refresh-token revocation; and disabled-user new-login denial.
- The resource-server decoder is now RS256-only. Discovery/JWKS requests use
  500-millisecond connect/read timeouts and a per-exact-target, per-instance
  minimum interval (`PT1S` default; validated 100 milliseconds–1 minute). Five
  focused property/rate-limiter tests pass with zero failures or errors,
  including 16-way same-target concurrency and independent discovery/JWKS
  targets. This is not a cluster-wide request bound; key rotation can fail
  closed until the same-target interval elapses.
- After upstream user disable, a pre-issued stateless access JWT remained
  accepted. Its issuance lifetime is 300 seconds; timestamp enforcement also
  includes `PT30S` skew in the harness and `PT60S` in checked-in defaults. The
  corresponding policy upper bounds are 330 and 360 seconds. The run proves
  immediate post-disable acceptance and records the denial horizon as not
  live-measured. This is separate from platform-user deprovisioning, which the
  persistence filter checks on every request and the PostgreSQL harness proves
  denies immediately.
- The landed schema-v2 runner/verifier contract requires
  `ExistingJwtAfterIdpDisable=PREISSUED_JWT_STILL_ACCEPTED`,
  `RefreshTokenRotationReuseDenied=PASS`,
  `RefreshTokenIndependentSessions=PASS`,
  `RefreshTokenPreRevocationControl=PASS`,
  `AccessTokenLifetimeSeconds=300`, `ConfiguredClockSkewSeconds=30`,
  `MaximumResidualAcceptanceSeconds=330`, and
  `DisableToDenialHorizon=NOT_LIVE_MEASURED`. It binds the source/profile
  manifest and packaged Platform API JAR digests, verifies cleanup before
  atomic publication, and rejects stale evidence. The 124.694-second live
  schema-v2 run and the independent profile/JAR verifier both passed.
- A forced packaging-failure probe emitted no success artifact, verified
  cleanup, and produced a 573-byte bounded/sanitized failure artifact. The
  project secret scan then returned zero findings. CI uploads the mutually
  exclusive success/failure paths on every run; failure evidence cannot satisfy
  the success verifier.
- A fresh full JDK 21 `mvn verify` after the live run passed 40 tests with zero
  failures/errors; 35 normal tests passed and five guarded database tests were
  skipped outside their disposable PostgreSQL harness. Rebuilding the JAR did
  not invalidate the evidence artifact digest.
- The transcript records schema/contract/scenario versions, runtime identity,
  configuration digest, command, timestamps, and no persisted runtime secrets.
  It also records `EvidenceScope=REFERENCE_CONFORMANCE_NOT_PRODUCTION`,
  `CodeRevision=UNBORN`, and `WorkspaceDirty=YES`; the ignored local artifact is
  reproducible reference evidence, not immutable release evidence.
- `.github/workflows/pr-quality.yml` runs the Linux schema-v2 verifier and
  uploads its evidence. The later revision-bound run `29923961768` passed this
  Keycloak job and the Compose build/health smoke; the workstation transcript
  above remains local reference evidence rather than being retroactively
  promoted.

The schema-v2 checkpoint resolved B-003 only for the local/reference
non-production IdP scope. At that time it did not prove state/nonce assurance
or authorize a production IdP, federation, break-glass, browser/BFF session
ownership, general bearer replay prevention, delegated capabilities, or
immediate access-token revocation. The later schema-v3 state/nonce child proof
is recorded above; production BFF/session conformance remains open.

Still open:

- Production-authorized enterprise IdP selection/conformance remains open.
- No dispatcher polling process or external publish target is enabled. Phase 3
  now proves its database identity and scheduler boundary; Phase 9 must add and
  verify the externally authenticated runtime and deterministic target handoff.

## 2026-07-22 — Phase 4 checkpoint 4A incident write ledger

Implemented locally:

- Canonical nested incident create, detail, status-transition, and timeline
  routes plus OpenAPI, Draft 2020-12 schemas, positive/negative fixtures, safe
  Problem Details, a 32 KiB configurable JSON-body bound, idempotency keys, and
  strong numeric ETags.
- One transaction for authority resolution, tenant binding, idempotency,
  incident mutation, timeline, audit, outbox, and cached response completion.
- V003 incident/timeline persistence with forced RLS, state/version guards,
  exact authoritative timeline payload validation, append-only controls, and a
  database-assigned tenant audit chain whose inputs must match the timeline.
- A narrow SECURITY DEFINER authorization resolver that locks active user,
  organization, memberships, project, and role rows so revocation serializes
  with an already-authorized mutation.
- Hidden-resource `404` responses use correlation URNs and never reflect scoped
  organization/project/incident identifiers.
- Schema-versioned reference evidence runners with source/config/migration/JAR hashes,
  tool versions, timing, bounded diagnostics, atomic publication, and explicit
  local/non-release scope.

Verified in this worktree:

- Static incident contract gate: 11 schemas, 14 fixtures, 128 local references,
  six OpenAPI operations, zero diagnostics, `Result=PASS`.
- Focused domain gate: seven test classes, 25 tests, zero
  failures/errors/skips, `Result=PASS`.
- Full Maven suite: 86 discovered, zero failures/errors; 11 guarded integration
  cases skip only outside their dedicated harness.
- Disposable PostgreSQL 18 gate: package, V001/V002-to-V003 upgrade, fresh
  V001-V003, guarded integration matrix, and portable SQL contract all exited
  zero; cleanup reported zero residual containers.
- Live tests prove authorized CRUD/replay, actor mismatch, non-enumerating
  cross-tenant access, one 200/one 412 concurrent transition, immediate next-
  request denial after serialized membership revocation, forged timeline/audit
  rejection, linear concurrent audit append, caller-forged chain override, and
  update/delete/truncate denial.
- A real outbox primary-key conflict after timeline and audit append rolled back
  the incident, timeline, audit, and idempotency rows; the test ran once with
  zero failure/error/skip.
- Refreshed Keycloak 26.7 schema-v2 conformance and its independent verifier
  pass against the exact same packaged JAR digest as Phase 4 PostgreSQL evidence.
- Layout checked 333 files, trust foundation checked 50 files, and the project
  secret scan checked 330 text files with zero findings before the final docs
  sync; final counts are revalidated after this update.

Evidence:

- `artifacts/verification/phase-04/incident-contracts.txt`
- `artifacts/verification/phase-04/incident-domain.txt`
- `artifacts/verification/phase-04/incident-crud.txt`
- `artifacts/verification/phase-04/audit-and-concurrency.txt`
- `artifacts/verification/phase-03/identity-delegation.txt`

Checkpoint state: **4A locally complete**. Phase 4 remains **in progress**.
Generic patch/assignment, frontend resolution/closure UX, postmortems, and the
governed evidence-object upload/read/tombstone/restore/purge/reconciliation
lifecycle are not implemented. Local evidence records `CodeRevision=UNBORN` and
`WorkspaceDirty=YES`; later revision-bound CI verifies the repository contracts
without converting that historical local transcript into production evidence.

## 2026-07-22 — Phase 5 provider-neutral runtime checkpoint

Implemented offline in `services/ai-runtime/`:

- strict versioned analysis request/response/problem contracts and matching
  JSON Schema roots;
- disabled-by-default typed settings with `deepseek-v4-flash` default,
  legacy alias retirement guard, and opaque secret handling;
- platform-issued delegated capability scope matching, nonce replay protection,
  last-hop data-class/redaction policy, and bounded token/tool/cost guard;
- provider-neutral application port separating orchestration from the DeepSeek
  adapter, strict outbound URL/numeric config bounds, and stable post-call
  budget/invalid-response failures;
- signed exact-request digest and maximum capability lifetime, evidence-source
  classification/citation binding, bounded pre-parse/chunked HTTP ingress, and
  global queue/provider deadlines;
- cumulative per-run token/cost allowance translated into provider-side
  completion caps; live readiness rejects unknown zero pricing;
- DeepSeek transport/adapter, sanitized `400/401/402/422/429/500/503` error
  taxonomy, structured-output validation, and contiguous terminal-frame stream
  assembler;
- endpoint/contract/unit tests that use only synthetic redacted payloads.
- additive PostgreSQL V004 state tables for hashed nonce consumption,
  cumulative run budgets, bounded leases, normalized success replay, forced
  tenant RLS, and a dedicated non-bypass `opsmind_ai_runtime` role;
- Psycopg async state adapter with row-lock reservation, crash-to-ambiguous
  recovery, full-reservation charging, fail-closed provider-overage accounting,
  success replay, and a disposable local
  PostgreSQL integration runner.
- additive V005 append-only capability-probe lifecycle/usage audit. Each
  process proves its own provider path; PostgreSQL advisory locking enforces a
  bounded provider/model/region hourly quota using the database clock, with
  jittered startup/retry scheduling.

Verification: 149 offline Python tests passed with
`PYTHONPATH=services/ai-runtime/src`. No real provider key or external call was
used. Flyway V004/V005 also applied successfully in the PostgreSQL 18 Phase 5
disposable migration gate. The dedicated five-test Phase 5 database runner
first exposed the Windows Proactor/Psycopg incompatibility and a lease-recovery
rollback defect. Selector-loop execution and an independent recovery
transaction now resolve both; PostgreSQL 18.4, V004/V005, all five tests, and cleanup
pass in a capacity-qualified local run. This remains unborn/dirty local
reference evidence. Cross-service asymmetric capability conformance passes;
final adversarial re-review found no surviving Critical/High issue after
cross-language structured-secret redaction, per-process capability proof,
DB-clock quota locking, startup/retry jitter, monitor recovery, and concurrent
quota-race fixes. See
[`code-review-260722-phase-05-post-fix.md`](../plans/reports/code-review-260722-phase-05-post-fix.md).
provider conformance/live synthetic smoke and production egress remain open.
Phase 5 is **in progress**.

## 2026-07-22 — Phase 6 Tool Gateway checkpoint

Implemented the fail-closed execution boundary: independent workload and
delegated-capability JWT domains, exact body/scope binding, one-use nonce,
idempotent receipts, policy/manifest enforcement, bounded connector execution,
recursive redaction, normalized evidence, deterministic audit, and explicit
liveness/readiness separation. Four schemas, five fixtures, canonical digest
checks, and 24 Maven tests pass. Durable atomic stores, three connector families,
the Platform capability issuer/client path, a selected live target, and
provider-specific cancellation/bulkhead proof remain open. Phase 6 is **in
progress**; its checkpoint passes but its exit gate is blocked.

## 2026-07-29 — Phase 6 tenant-scoped connector bulkhead checkpoint

Added a dedicated `ConnectorBulkheadProperties` contract with global `32` and
per-tenant `4` defaults plus startup bounds, and wired
`BoundedConnectorExecutor` to a reference-counted tenant registry keyed from
verified `TenantProjectScope.tenantId()`. Admission is fail-fast and ordered
tenant then global; different projects of one tenant share the same allowance.
Permit ownership uses a queued/running/released state guard so cancellation
before task start releases capacity while an interrupted connector retains it
until its body exits. Focused and full Tool Gateway Maven suites pass, and the
Phase 6 validator reports `CheckpointResult=PASS`. Phase 6 remains **in
progress** and `PhaseExit=BLOCK` for the oversized-evidence artifact adapter,
remaining connector families, named live non-production connector proof, and
provider-specific cancellation.

## 2026-07-22 — Phase 7 durable investigation persistence checkpoint

Implemented in the Platform API:

- pure investigation reducer and bounded synchronous runner with visible
  completion, abstain, budget, duplicate, no-progress, and dependency failures;
- feature-gated fixture AI/Tool clients and investigation start/read endpoints;
- V006 `investigation_runs` snapshots plus contiguous immutable
  `investigation_run_events`, forced RLS, least-privilege grants, and optimistic
  revision/event-count concurrency;
- same-transaction `investigation-audit-v1` audit-chain writes and exact event,
  terminal-response, snapshot-parity, and append-only database triggers;
- direct-SQL integrity tests proving a runtime role cannot forge malformed
  completion state/events or mutate either ledger.

Verification:

- `validate-phase-07-investigation-slice.mjs`: `CheckpointResult=PASS`,
  `PhaseExit=BLOCK`;
- full local Platform API suite passed with zero failures/errors and database
  tests gated to their dedicated harness;
- GitHub Actions run `29923961768` at revision
  `0ec3cff944102b716dc098871384ba0534df06fd` passed governance, Ubuntu/Windows
  bootstrap, PostgreSQL migration/persistence/integrity, Keycloak, Operator Web,
  Compose, and AI Runtime jobs. Both Java suites also completed successfully,
  but their jobs were cancelled at the 60-minute limit while two independent
  unauthenticated OWASP Dependency-Check processes each imported the full NVD
  corpus. The replacement separates bounded Maven verification from one shared
  CycloneDX/OSV policy job.
- GitHub Actions run `29930327761` at revision `8a6bd398` completed successfully
  across every executable job. Java dependency security completed in 32 seconds;
  its artifact contains 111- and 97-component SBOMs, 208 scanned packages,
  checksum-pinned OSV 2.4.0, zero vulnerability groups after the Jackson
  Databind 3.1.5 upgrade, and `Result=PASS`. All nine local security-tool tests
  also pass (two installer and seven evaluator cases).

This checkpoint is durable data, not durable workflow. It does not resume an
in-flight orchestrator and does not append to `incident_timeline_events`.
Capability-backed clients, the allowlisted live connector, CK/Stitch UI/browser
E2E, and cross-service trace/p95 evidence remain required. Phase 7 is **in
progress**.

## 2026-07-22 — Checkpoint 4B bounded evidence records

Implemented an immutable small-record evidence control plane before enabling
real Phase 7 clients:

- V007 `evidence_records` with a 64 KiB canonical JSON limit, independent
  PostgreSQL SHA-256 verification, forced RLS, append-only triggers, constrained
  runtime grants, and one-to-one linkage to `EVIDENCE_APPENDED` run events;
- deterministic Platform-owned evidence/execution UUIDv8 identities scoped to
  organization, run, and tool intent;
- same-transaction run successor, run-event, evidence, and audit persistence;
- metadata-only event/audit serialization, with canonical content available
  only through a tenant/incident/run-authorized resolver that re-verifies the
  digest and preserves caller order;
- Gateway request/audit/provenance, redaction, truncation, and duplicate replay
  metadata retained without exposing credentials or raw provider payloads.

Local verification: Platform API `148` tests, `0` failures/errors and `20`
environment-gated integration skips; Phase 4B static checkpoint PASS; repository
layout, actionlint, diff, and working-tree/history secret scans PASS.

GitHub Actions run `29936897223` at revision
`77f7ab80edb64f7ac8a0a46b68c37a3ad2f043eb` completed successfully with 11
successful executable jobs and one expected push-only dependency-policy skip.
The run applied fresh V001–V007, passed 11 PostgreSQL integration cases with no
failure/error/skip, including evidence persistence and rollback, and passed the
full Compose health smoke. Run `29938632667` at revision
`3da19efcb23db60e4c42c7a849f5a34c790f1a32` subsequently passed every executable
job and proved a guarded disposable V006→V007 upgrade from table absence to
presence with cleanup PASS.

Exact transition replay is now accepted only when the successor snapshot,
deterministic event, tool execution/request digest, and complete evidence
provenance match; drift fails closed. A real final-step audit conflict now also
proves rollback after snapshot/event/evidence writes. GitHub Actions run
`29940796700` at revision `14eb8837b94f16933722954e7a03e55a73295d16`
passed all 11 executable jobs. Its PostgreSQL artifact reports 13 tests with zero
failure/error/skip, including replay `1/1`, rollback `2/2`, and the guarded
upgrade/cleanup proof. Bounded-record checkpoint 4B is **complete**. Phase 4 and
G2 remain open because the large/raw artifact lifecycle is still blocked by
B-006/B-008/B-012.

## 2026-07-29 — Phase 4C fenced object-upload implementation

The integration branch now combines three independently reviewed workstreams:

- V015 durable upload attempts, five-second-to-five-minute leases, current
  attempt foreign-key integrity, row-lock single-winner claims, stale and
  authorization-epoch fencing, and atomic STORED event/audit settlement;
- a default-off AWS SDK v2 S3-compatible adapter with one conditional bounded
  PUT, precomputed SHA-256, SSE-KMS, disabled SDK retry, exact EOF/digest, and
  probe-before-retry behavior for durably recorded ambiguity;
- application orchestration that performs object I/O between two independent
  ANALYZE authorization transactions and exposes no storage reference.

Review corrections preserve ambiguity on every denied/unavailable probe,
separate the request KMS key from its canonical response reference, reject
unversioned `null`, and accept opaque version IDs up to 1,024 UTF-8 bytes.
An expired `CLAIMED` attempt with no durable outcome is now conservatively
`ORPHANED`; it cannot be converted into a later matching-HEAD adoption.
Further adversarial review separated post-PUT source/response mismatches from
retryable transport failures: the former settle `ORPHANED` and cannot later be
HEAD-adopted, while a definitive `FAILED` attempt preserves its lease tuple and
can be reclaimed immediately. The disposable database runner now proves both
paths. The Phase 4C static validator passes with V014's normalized hash
unchanged. This historical CI gap is now closed by PR #46/run `30777514150`
and current run `30881416141`, whose PostgreSQL job `91904344606` passes the
V014-to-V015 real-role contract.
`STORED` remains unreadable; Phase 03 ingress, scanning, availability,
retention/deletion receipts, restore, and B-006/B-008/B-012 are still open.

## Next Allowed Work

1. Add controlled ingress, scanning, `AVAILABLE`, reconciliation, retention,
   deletion receipts, restore, supported backend/KMS conformance, and production
   IdP federation/session/break-glass/revocation conformance.
2. Authorize and prove the named live non-production connector plus provider/
   legal egress; fixture-backed Phase 7 evidence is not a substitute.
3. Register governed held-out cases and qualified human adjudication for B-013.
4. Complete B-017 production query-plan/latency and DR evidence, live namespace
   read-only authorization/retention, and external page-delivery proof.
5. Keep production Temporal and external paging disabled until those B-017
   gates pass; profile-gated local/CI conformance remains allowed.
6. Keep dependency downloads, container builds, and service startup behind a
   fresh capacity/root preflight. The 2026-07-29 preflight blocks local heavy
   work because `D:` is below its 20 GiB minimum.

## 2026-07-23 — Operator projection safety and CK/Stitch workspace

Implemented:

- Versioned `application/vnd.opsmind.operator-projection.v1+json` responses
  for incident and investigation detail reads, with `Accept` negotiation,
  `no-store`, `Vary: Accept`, and mandatory projection/redaction assurance
  headers.
- Structural Platform projections that withhold model-authored prose,
  rationale, citation claims, counter/missing-evidence text, and non-catalog
  operations from browser output; deterministic Unicode/control/secret/query
  redaction counts changed emitted leaves.
- Read authorization through `incident:read` and organization/project/
  incident/run-scoped JDBC lookup.
- CK/Stitch operator workspace with server-only bounded transport, safe-media
  validation, explicit degraded states, accessibility/responsive/reduced-motion
  coverage, and production standalone smoke.

Verified:

- Targeted Platform projection/controller/auth tests: 31 passed; full Platform
  Maven suite passed.
- Operator Web unit tests: 10 passed; lint, typecheck, production build,
  production standalone smoke, and Playwright Chromium flow: 28 passed.
- Phase validator: `CheckpointResult=PASS`; `PhaseExit=BLOCK` only for the
  still-missing cross-service trace and p95 benchmark evidence.

Historical remaining at time of the previous entry (superseded by the
revision-bound checkpoint below):

- A real capability-backed Platform → AI Runtime → Tool Gateway →
  Prometheus trace with 100 warm runs and p95 evidence.
- Incident-timeline linkage, production BFF/session proof, and final reviewed
  screenshot/GIF media manifest.

## 2026-07-23 — Revision-bound cross-service and media proof

The local Phase 7 integration checkpoint now has a disposable, independently
authenticated end-to-end harness. It provisions loopback OIDC/JWKS and
capability keys outside Git, isolated PostgreSQL roles with forced RLS, the
real Platform/AI Runtime/Tool Gateway adapters, and a synthetic Prometheus
response bounded by the immutable Platform catalog.

Revision `743b2c5f8d4bf8b6facc69111e34d855d3dcc163` completed 100 warm runs:

- terminal status `PASS`;
- p50/p95/max latency `776.78/1,434.60/2,586.57 ms` against a 5,000 ms p95 threshold;
- provider observations `probe=1`, `analysis=200`, Prometheus queries `100`;
- durable PostgreSQL state `runs=100`, `evidence=100`, `analysis=200`,
  `toolReceipts=100`, `toolAuditEvents=100`;
- zero prohibited log material, tagged process, disposable container, or
  managed secret after cleanup.

The report is written to ignored local state under
`.opsmind/reports/cross-service-trace.json`; the phase validator binds its
`gitHead` and clean-tree assertion to the revision under test. This closes the
local fixture-backed cross-service checkpoint, not G3. A named live
non-production connector, provider/legal conformance, incident-timeline
linkage, production BFF/session proof, and release-scale evaluation remain
explicit blockers.

## Historical first-green Phase 8B production-path evaluation

Revision-bound run `30200584275` on commit `134d63c` was the first terminal-green
execution of the deterministic evaluation contracts against the real service
path rather than authored fixtures. It provisions a disposable
Docker/PostgreSQL stack per scenario and runs each one through Platform, AI
Runtime, Tool Gateway, and the operator projection:

- Scenario A, deployment-correlated latency regression, 100 warm runs, verdict
  `PASS`;
- Scenario B, insufficient-evidence abstention with no tool use, verdict `PASS`;
- Scenario C, opposing read-only collections with counter-evidence, verdict
  `PASS`;
- all eight metrics pass in every scenario, including `root_cause_semantic`,
  which requires a raw analysis whose digest and reference resolve against the
  scored trace;
- the uploaded artifact records `GitHead=134d63c`, `GitTree=0`, and SHA-256
  digests for both service JARs, the connector manifest, the export SQL, and the
  projector.

Five defects were closed to reach it. A PowerShell argument boundary silently
split a probe command so a failing child reported success. The evidence identity
contract accepted only UUID versions 1 through 5 while the platform derives
version 8 identities from a domain-separated digest. The scenario cost budget was
zero while the runtime requires non-zero token prices to permit egress, making
the metric unreachable rather than strict. Reported cost was computed in binary
floating point and no longer equalled the `numeric(20, 8)` column it was stored
in, breaking the invocation binding. The artifact reference named the transient
working path instead of the published trace, leaving the raw analysis untrusted.

This historical run is superseded by executable confirmation in runs
`30208691371` and `30208691365` on `5dfbc00`, then revision-bound confirmation in
`30209210001` and `30209209999`. Artifact `8633891153` from the `5dfbc00` run
has independently recomputed ZIP SHA-256
`78d0f930e4cbcc55f6c2afd7e46fb5624642d485f62b579668e40eeefe903834`.
The historical first-green run remains deterministic smoke
evidence on three authored scenarios. It is not a
held-out quality, calibration, or human-benefit claim, and Phase 8 exit remains
BLOCK.

## 2026-08-03 — Tenant-scoped incident list pagination

Implemented the collection
`GET /api/v1/organizations/{organizationId}/projects/{projectId}/incidents`
with optional exact status, page sizes 1..100, `Cache-Control: no-store`, and a
closed summary containing only `id`, `title`, `severity`, `status`, `updatedAt`,
and `version`. Traversal uses the fixed `(updatedAt DESC, id DESC)` tuple. Its
unsigned versioned token is a bounded navigation hint bound after authorization
to organization, project, and filter; it is not authorization, a snapshot, or
a lossless feed.

V016 adds matching filtered and unfiltered indexes with concurrent DDL outside
a Flyway transaction. Tests cover HTTP boundaries, tied timestamps, exact and
final pages, arbitrary seek points, live-view updates, tenant/RLS isolation,
revoked membership, zero persistent side effects, sanitized failures, valid/
ready indexes, V015-to-V016 upgrade, non-superuser failed-build recovery, and
bounded plans. Static validation reports 24 schemas, 29 fixtures, 237 resolved
references, 11 operations, and zero errors. Implementation revision `139ab67`
passed CodeQL run `30815898281`, cross-service run `30815902504`, governance,
both clean bootstraps, Compose health, and the relevant build gates; final merge
admission remains revision-bound in PR #58.

This closes only the incident-list child checkpoint. Phase 4 and G2 remain in
progress; free-text search, patch/assignment, frontend closure UX/postmortem
breadth, and B-006/B-008/B-012 remain open. CI fixtures are not production SLO evidence,
and no frontend incident-list experience is claimed.

## 2026-08-04 — Incident resolution-to-closure verification

PR #59 merged as `3bad910` after exact-head revision `13a0224` passed PR
Quality run `30868733961`, CodeQL run `30868731708`, and cross-service run
`30868733964`. The PostgreSQL trust job executed
`IncidentHttpPersistenceIntegrationTest` with three tests and zero failures,
errors, or skips on a dedicated disposable database.

The existing public transition route is now contract- and PostgreSQL-proven for
`OPEN -> INVESTIGATING -> RESOLVED -> CLOSED`. A CLOSED request omits resolution
fields while response, detail, and timeline retain the authoritative root cause
and resolution summary. Exact replay preserves body, ETag, operation ID, and
durable counts; stale ETags and every outgoing CLOSED transition fail without
timeline, audit, outbox, or idempotency growth. Timeline, audit, and outbox event
IDs remain linked with aggregate sequence `1..4`.

This is non-production verification of an existing backend path, not a new
route, migration, frontend flow, or full Phase 4 exit. Generic PATCH,
owner/alert assignment, resolve/close frontend UX, postmortems, governed
artifact lifecycle, B-006/B-008/B-012, Phase 4, and G2 remain open.

## Unresolved Questions

Production IdP, provider/legal, named live connector, evidence-object lifecycle,
RAG, remediation, production Temporal namespace conformance,
staging/production, DR, and release conformance remain explicit gates. PR #45
and PR #56 close the repository-owned Phase 9 worker/restart, merged-head
database/runtime, live-scrape, local-routing, and pinned-rule-check gaps. B-017
remains active for production database query-plan/latency and DR, live Temporal
authorization/retention, and external alert delivery. B-013 requires reviewed
human pilot data; threshold freeze and Phase 9/G4 exit remain open. See
[Blockers](./blockers.md).
