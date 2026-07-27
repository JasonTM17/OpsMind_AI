# Incident Activity Timeline Phase 2 Re-review

Date: 2026-07-27  
Focus: current Phase 2/root changes after review fixes  
Excluded: still-running Phase 3 gate and documentation worker changes

## Code Review Summary

### Scope

- 12 tracked files plus 3 untracked implementation/test files.
- Tracked diff at review time: 596 insertions, 17 deletions.
- Reviewed negotiation, controller/service authorization, union SQL, migration contract, OpenAPI, CI wiring, and focused tests.
- Worker report was evidence input only, not reviewed as implementation.

### Overall Assessment

No remaining P0, P1, or P2 defect found in the reviewed Phase 2/root implementation. The prior negotiation, CI coverage, live-cursor documentation, branch-isolation coverage, representation-security, role-matrix, and 406-test findings are resolved.

Production landing still depends on the separate Phase 3 database migration/recovery/performance evidence. That work was explicitly excluded from this re-review and is not counted as a Phase 2 code finding.

### Remaining P0-P2 Findings

None.

### Prior Finding Resolution

1. **Resolved — RFC 9110 Accept specificity.** `IncidentController.java:170-237` now computes quality independently for each representation using the most-specific matching range. `IncidentControllerHttpTest.java:450-477` covers `application/*`, exact vendor override, and explicit-exclusion precedence.
2. **Resolved — q=0 contract conflict.** `phase-02-unified-read-projection.md:26` now matches the Phase 1 rule: q=0 excludes only the matched representation. Tests at `IncidentControllerHttpTest.java:461-468` prove vendor-q0 JSON fallback and JSON-q0 wildcard vendor fallback.
3. **Resolved — database integration test omitted from CI.** `.github/workflows/pr-quality.yml:670` explicitly includes `IncidentActivityTimelineHttpIntegrationTest` in the PostgreSQL job, whose environment enables the suite.
4. **Open / Phase 3 gate — V009 executable evidence.** `phase-02-unified-read-projection.md:86` still requires fresh/upgrade, catalog-validity, forward-recovery, EXPLAIN, append-latency, and storage-budget proof. This is assigned to the still-running Phase 3 gate worker and was excluded from this review. It remains a landing gate, not a demonstrated defect in the Phase 2 SQL.
5. **Resolved — forward-only/non-lossless semantics.** `opsmind-v1.yaml:303-310` states the public behavior. `IncidentActivityTimelineHttpIntegrationTest.java:147-172` proves a backdated append is omitted from continuation and discoverable by fresh traversal.
6. **Resolved — branch-local same-tenant isolation coverage.** `IncidentActivityTimelineHttpIntegrationTest.java:208-256` executes the target union and asserts rows from another incident and another project are absent.
7. **Resolved — representation-dependent authorization expression.** `opsmind-v1.yaml:296-302` adds machine-readable `x-opsmind-representation-security`; runtime ANALYZE enforcement remains at `IncidentQueryService.java:124-136`.
8. **Resolved — phantom 406 assertion.** `IncidentControllerHttpTest.java:411-420` requires Problem+JSON, error code, and status when the client accepts the error representation.
9. **Resolved — ANALYZE role matrix.** `IncidentAnalysisAuthorizerTest.java:126-136` covers denied VIEWER/DEVELOPER/SECURITY_REVIEWER/AI_AGENT roles and allowed ADMIN/SRE roles.

### Production-readiness Checklist

- Concurrency: no new shared mutable state; authorization, existence check, and union query remain in one transaction.
- Error boundaries: malformed/unsupported Accept values propagate as sanitized 406 responses; database exceptions retain existing mapped boundaries.
- API contracts: legacy JSON remains the equal-quality/default representation; v1 cursor and body contracts remain unchanged.
- Backwards compatibility: no exported legacy repository or HTTP contract was removed.
- Input validation: IDs, page size, cursor, and media ranges are validated before database query execution.
- Auth/authz: vendor route requires both `incident:analyze` scope and ANALYZE project access; hidden denial ordering remains intact.
- Query efficiency: both union branches have organization/project/incident and tuple predicates, use parameterized SQL, and apply one bounded limit. Representative EXPLAIN proof remains the Phase 3 gate.
- Data exposure: vendor SQL selects only the eight allowlisted metadata fields and does not select either source payload.

### Verification

- `git diff --check`: PASS; only line-ending conversion warnings.
- `node scripts/validation/validate-phase-04-incident-contracts.mjs`: PASS, 0 errors, 10 operations, 217 resolved references.
- Focused Maven run: BUILD SUCCESS; 45 discovered, 42 executed/passed, 0 failures, 0 errors, 3 skipped.
- Skipped tests: all three `IncidentActivityTimelineHttpIntegrationTest` cases because the local `OPSMIND_PHASE7_DB_INTEGRATION` variable was absent. CI wiring now supplies the variable and explicitly selects the class.

### Metrics

- Type coverage: not separately measured; Java compilation succeeded in the focused Maven run.
- Test coverage percentage: not measured.
- Linting issues: no dedicated lint run; diff check reported no whitespace errors.

### Unresolved Questions

None for the Phase 2/root implementation. Outstanding evidence is the explicitly separate Phase 3 landing gate.

Status: PASS_WITH_PENDING_PHASE3_GATES

