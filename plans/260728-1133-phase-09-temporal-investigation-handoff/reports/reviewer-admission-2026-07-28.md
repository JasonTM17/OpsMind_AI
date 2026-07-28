## Code Review Summary

### Scope

- Baseline: af4550497ff153129ba8c8ec09766b0ab5886c5c
- Files: 9 admission-workstream files; 6 modified and 3 new
- LOC: +522 / -68, including untracked files
- Focus: uncommitted Phase 6 Workstream A / Phase 9 admission authorization
- Scout findings: durable-write call paths, tenant dispatcher state races, authorization revocation timing, idempotent retry behavior, and conditional bean wiring

### Overall Assessment

Block merge. The corrected contextual path reauthorizes within the outer handoff transaction before its first durable write, and the preflight bean is now scoped to the same persistence/PostgreSQL/Temporal configuration as the repository. Two paths still violate the Phase 6 admission guarantee: dispatcher eligibility is checked without a lock, and a public no-context writer bypasses the new fence.

### Critical Issues

- None.

### High Priority

#### [High] Dispatcher eligibility can be revoked after its check and before handoff commit

Evidence: InvestigationWorkflowAdmissionPreflight.java:65-78 runs a non-locking SELECT EXISTS over service_accounts. JdbcInvestigationWorkflowHandoffRepository.java:113-124 invokes it inside the handoff transaction, then creates the initial run, binding, and outbox event at lines 151-172. Nothing locks the selected service-account row.

A concurrent update can suspend that account or remove its dispatcher scope/audience after EXISTS evaluates true but before the handoff commits. The caller can therefore receive a successful admission while the tenant has no eligible dispatcher, contrary to Phase 6's “no unschedulable 202” acceptance requirement (phase-06-post-audit-authorization-and-dispatch-hardening.md:28-31). The sequential tests do not exercise this interleaving.

Narrow fix: replace the boolean EXISTS probe with a selection of one matching service_accounts.id using FOR SHARE, retaining that row lock in the existing outer transaction until the run, binding, and outbox commit. FOR SHARE is intentional: it conflicts with status/capability UPDATEs and DELETEs; FOR KEY SHARE would not protect ordinary status/scope updates. Keep the current safe 503 when no matching row is selected. Do not wrap EXISTS in a locking subquery, because that need not lock the candidate row.

Required proof: add a two-connection PostgreSQL integration test for a suspension and a capability-removal update. It must show that revocation cannot commit between successful admission and the durable handoff commit; if revocation commits first, admission returns the existing safe 503 and leaves no run, binding, or outbox row.

#### [High] The public legacy writer bypasses fresh authorization and dispatcher admission

Evidence: JdbcInvestigationWorkflowHandoffRepository.java:91-97 exposes createOrLoad(Start, AuthorizedIncidentAnalysisEvidence) and delegates with context = null. The null branch at lines 135-144 only sets tenant context and returns the supplied authorization snapshot; it does not invoke InvestigationWorkflowAdmissionPreflight. The class implements DurableInvestigationAdmissionRepository, which extends the legacy writer interface, so the Spring bean remains injectable through that public type.

No current src/main caller of this overload was found. That does not make the boundary safe: any future injected consumer can create a run, binding, and outbox event using stale evidence after either authorization or dispatcher revocation. The Phase 6 invariant is optional at the writer rather than enforced.

Narrow fix: do not infer a principal from AuthorizedIncidentAnalysisEvidence. Make the context-bearing admission interface the only production contract able to create a new handoff. Split the legacy interface into read/idempotency access and move any raw no-context creation used for fixtures into explicit test support. If the legacy method must remain source/binary-visible, make it fail closed for a missing binding rather than create one, then migrate known consumers. First run a repository-wide consumer audit; this review found no production caller, but this is a compatibility change that must be made deliberately. Retain the legacy read/existing-run behavior so idempotent retries do not regress.

### Medium Priority

- None.

### Low Priority

- None.

### Edge Cases Found by Scout

- Default health-only startup lacks persistence dependencies. The live diff now correctly gates the preflight with the repository's persistence profile and PostgreSQL/Temporal properties; the ConditionalOnProperty import is present.
- Authorization revocation is rechecked in the contextual transaction. The existing incident access SQL locks the authorization tuple with FOR SHARE (V003__incident_control_plane.sql:148-149), and the added integration test verifies a sequential membership revocation leaves no durable rows.
- A tenant dispatcher account can still be suspended, deleted, or capability-restricted between the current unprotected eligibility read and commit.
- Existing-run retries intentionally return before readiness/deadline/admission checks. The updated starter tests now assert no further repository interaction, preserving the no-write idempotency contract.

### Positive Observations

- The contextual create path invokes fresh authorization and dispatcher admission inside the outer TransactionTemplate before the initial run write.
- Missing or inactive tenant dispatcher accounts map to the intended non-disclosing 503 in the sequential path, with integration coverage for no persisted handoff rows.
- Temporal configuration tests now register DurableInvestigationAdmissionRepository, so the conditional admission dependency is exercised instead of being hidden by a base-interface mock.

### Recommended Actions

1. Lock an eligible tenant dispatcher row with FOR SHARE for the full handoff transaction; add the two-connection revocation proof.
2. Remove or fail-close the no-context new-write route after a consumer audit; keep context-bearing admission as the sole production writer.
3. Run the focused admission, persistence, and configuration tests once disk capacity permits, then the normal type/lint/build gates.
4. Leave Phase 6 Workstream A marked in-progress. This review does not mutate plan state.

### Metrics

- Type Coverage: not measured
- Test Coverage: not measured
- Linting Issues: git diff --check passed; only repository line-ending warnings were emitted
- Tests/build: not run because the explicit D: safety constraint reports less than 20 GiB free

### Unresolved Questions

- None within the reviewed scope. Phase 6 Workstreams B and C were outside this review.
