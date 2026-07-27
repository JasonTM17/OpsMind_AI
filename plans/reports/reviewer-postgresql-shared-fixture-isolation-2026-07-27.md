# PostgreSQL Shared-Fixture Isolation Follow-up Review

## Code Review Summary

### Scope

- Base: `51dbc583a898bc8e34aaf422aeb1f7067bf34cf9`
- Pending implementation file: `.github/workflows/pr-quality.yml`
- Pending implementation delta: +9/-9; step reorder only
- Context: updated
  `plans/reports/debugger-postgresql-shared-fixture-isolation-2026-07-27.md`
- Exact-SHA evidence: run `30255117698`, job `89943494044`
- Focus: reordered shared-PostgreSQL gates, failure propagation, and artifacts

### Overall Assessment

**PASS — no P0-P3 findings in the refreshed workflow diff.**

The previous review incorrectly claimed that
`run-phase-03-postgres-contract.sh` used a separate ephemeral database. It does
not. `OPSMIND_EPHEMERAL_DB=true` is only a mutation guard; the script selects
`${PGDATABASE:-opsmind}`, and the job sets `PGDATABASE=opsmind`. Exact-run
artifact `phase-03/migration-and-rls.txt:2` confirms
`DatabaseName=opsmind`.

The new order is the minimal correct repair:

1. pooled 17-test suite;
2. alternating-tenant/append-only contract;
3. mutating isolated three-test timeline suite;
4. always-run artifact upload.

All contracts that require the one-project shared fixture now execute before
the timeline class inserts `SAME_ORG_PROJECT` into the same database.

### Findings

- P0 Critical: none
- P1 High: none
- P2 Medium: none
- P3 Low: none

### Exact-Run Evidence

Run `30255117698` / job `89943494044` proves the pre-reorder state:

- V009 catalog and full evidence gate passed:
  - `phase-04/evidence-migration-upgrade.txt:209` records both indexes `:true`;
  - line 333 records `V009EvidenceResult=PASS`.
- Pooled suite passed 17/17 with zero failures/errors:
  - `phase-03/authz-rls-pool-matrix.txt:201`;
  - build success at line 204.
- Isolated timeline suite passed 3/3 with zero failures/errors:
  - `phase-04/incident-activity-timeline.txt:63-70`.
- The later alternating contract targeted the same database and failed:
  - `phase-03/migration-and-rls.txt:2` — `DatabaseName=opsmind`;
  - line 20 — `ERROR: expected exactly one project in tenant context`.

This sequence isolates the remaining failure to workflow ordering. It is not a
V009, pooled-suite, token, or timeline behavior failure.

### Root-Cause and Data-Flow Verification

- `run-phase-03-postgres-contract.sh:5-7` only checks the attestation variable.
  It creates no database and performs no reset or cleanup.
- The harness selects `db_name="${PGDATABASE:-opsmind}"` at line 16.
- Workflow job scope sets `PGDATABASE=opsmind`; timeline datasource also names
  `opsmind`.
- Timeline `@BeforeEach` inserts a second tenant-A project and has no teardown.
- The shell contract counts tenant-visible projects and requires exactly one at
  `run-phase-03-postgres-contract.sh:245-251`.
- The exact artifact's five `INSERT 0 0` results show base fixtures already
  existed; the extra project remained from the preceding timeline suite.

The prior report's “separate ephemeral database” statement is withdrawn.

### False-Green and Error-Boundary Review

- Pooled step remains unchanged and blocking with `set -euo pipefail` plus
  `tee`; exact-run evidence proves 17/17 execution.
- Alternating contract is now immediately after pooled
  (`pr-quality.yml:673-681`). It has `set -euo pipefail`, no
  `continue-on-error`, and redirects stdout/stderr to
  `phase-03/migration-and-rls.txt`.
- If the alternating command fails, shell `-e` stops before `cat`; the job stays
  failed. Redirection creates the evidence file before command execution, and
  the exact failed run proves always-upload retained it.
- Timeline is a normal required step at `pr-quality.yml:682-694`. Exact
  enablement, exact `-Dtest`, and `pipefail` prevent skip or pipeline masking.
- If alternating fails, timeline is skipped by normal fail-fast behavior. That
  cannot green the job; the alternating failure remains authoritative.
- If timeline fails, `tee` retains its combined output and `pipefail` fails the
  job.
- Upload remains after all three gates with `if: always()`
  (`pr-quality.yml:695-704`). It retains the phase-03 directory, V009 evidence,
  timeline evidence when that step ran, and Tool Gateway evidence.
- Upload success cannot clear an earlier failed step. Missing artifacts can only
  add failure through `if-no-files-found: error`.

No false-green, artifact-loss, swallowed-error, secret-log, concurrency, query,
authorization, or production-contract defect was introduced by the reorder.

### Adversarial Review

- Attempt: “ephemeral” attestation provides isolation. **Accepted as a prior
  review error, not a current-code claim.** Artifact and script prove shared
  `opsmind`.
- Attempt: pooled mutations could still break the reordered shell contract.
  **Rejected.** The exact run's pooled suite passed its one-project RLS check;
  the later pooled classes do not add projects. The new failure appeared only
  after timeline inserted the second project.
- Attempt: shell contract residue could break timeline. **Rejected.** Timeline
  reseeds/upserts shared membership state before each test and uses random,
  incident-scoped records. Its outbox and timeline identities do not collide
  with the shell harness's fixed Phase 3 records.
- Attempt: moving timeline last hides it on an earlier failure. **Rejected.**
  CI is fail-fast, not false-green; any earlier failure blocks the job, while a
  successful earlier contract proceeds to the required timeline step.
- Attempt: future shared-database checks added after timeline could regress.
  **Valid architectural risk, non-finding for this diff.** The reordered
  workflow has no later database contract. True per-suite databases remain the
  stronger long-term design.

### Verification

- Fresh actionlint 1.7.12: PASS
- Fresh repository layout validator: `Errors=0`, `Result=PASS`
- Fresh `git diff --check HEAD`: PASS
- Programmatic order check:
  `pooled < alternating < timeline < always-upload`: PASS
- Alternating failure boundary: `set -euo pipefail`, combined-output file,
  no `continue-on-error`: PASS
- Timeline failure boundary: exact env/test selector, `tee`, `pipefail`: PASS
- Upload includes phase-03 and timeline evidence and uses `if: always()`: PASS

### Metrics

- Review findings: P0=0, P1=0, P2=0, P3=0
- Exact-run proven suites: V009 PASS; pooled 17/17; timeline 3/3
- Exact-run remaining failure explained: alternating contract 0/1
- Static workflow/repository validation errors: 0

### Recommended Action

Rerun the PostgreSQL trust job on the exact reordered SHA. Expected evidence:

- pooled suite 17/17 PASS;
- `migration-and-rls.txt` ends `Result=PASS`;
- isolated timeline suite 3/3 PASS;
- always-upload contains all three evidence records.

### Unresolved Questions

- None for this reorder. Per-suite database isolation remains optional follow-up
  hardening; `OPSMIND_EPHEMERAL_DB` must not be interpreted as a selector.

Status: DONE

Summary: PASS; corrected prior database-isolation claim and verified that
pooled → alternating → timeline → always-upload is fail-closed and matches the
exact-run contamination evidence.

Concerns/Blockers: Fresh exact-SHA CI for the reordered workflow is still
required before landing.
