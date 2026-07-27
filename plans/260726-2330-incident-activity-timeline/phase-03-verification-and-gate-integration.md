---
phase: 3
title: "Verification and Gate Integration"
status: pending
priority: P1
dependencies: [2]
---

# Phase 3: Verification and Gate Integration

## Overview

Finish the bridge with validator, doc, and review gates only. Current project docs and validators still describe timeline linkage as missing (`plans/260719-1747-opsmind-ai-production-platform/phase-07-thin-evidence-backed-incident-vertical-slice.md:66-80`, `docs/project-roadmap.md:104-119`); this phase updates those statements without overstating Phase 7 or Phase 9 completion.

## Requirements

- Functional: Phase 4 contract validation must recognize the vendor timeline representation and its two new schemas (`scripts/validation/validate-phase-04-incident-contracts.mjs:52-60`, `scripts/validation/phase-04-incident-contracts/openapi-static-contract-validator.mjs:84-106`).
- Functional: Phase 7 validation must assert that ANALYZE-only timeline linkage now exists but still forbid payload, free text, reasoning, credential, evidence/tool identifiers, or evidence-content leakage (`scripts/validation/validate-phase-07-investigation-slice.mjs:81-190`).
- Functional: the migration-upgrade gate must execute V008-to-V009 and fresh installs, prove both indexes valid, preserve old application writes, and capture recovery/query-plan/append/storage evidence (`scripts/validation/run-phase-04b-migration-upgrade.sh:262-382`). The evidence fixture has at least 50,000 rows per ledger; 300 matched append samples per ledger (50 warm-up, 250 measured); and 100 warm vendor reads (50 warm-up, 50 measured). Vendor-read and post-index append p95 are each ≤500 ms, append p95 regression is ≤20%, and combined V009 index bytes are ≤256 bytes per source row and ≤100% of combined source-ledger `pg_table_size`.
- Functional: architecture and roadmap docs must close only the incident-timeline-linkage gap; they must keep live connector, provider/legal, held-out human pilot, and BFF/session blockers intact (`docs/project-roadmap.md:104-131`, `plans/260719-1747-opsmind-ai-production-platform/phase-08-simulator-and-evaluation-baseline.md:274-277`).
- Non-functional: finish with a focused code-review gate because the change crosses public contract, SQL, RLS, and migration boundaries.

## Related Code Files

- Modify: `scripts/validation/validate-phase-04-incident-contracts.mjs`
- Modify: `scripts/validation/phase-04-incident-contracts/openapi-static-contract-validator.mjs`
- Modify: `scripts/validation/validate-phase-04b-evidence-records.mjs`
- Modify: `scripts/validation/run-phase-04b-migration-upgrade.sh`
- Create: `services/platform-api/src/test/java/ai/opsmind/platform/persistence/FlywayRecoveryHarnessTest.java`
- Modify: `scripts/validation/validate-phase-07-investigation-slice.mjs`
- Modify: `docs/system-architecture.md`
- Modify: `docs/codebase-summary.md`
- Modify: `docs/project-roadmap.md`
- Modify: `docs/progress.md`
- Modify: `docs/deployment-guide.md`

## TDD First

1. Add valid and invalid incident-activity timeline fixtures, then wire them into the Phase 4 contract validator before broadening runtime tests.
2. Extend the Phase 7 validator with positive markers for the vendor media type, ANALYZE authorization, exact column projection, and negative markers that reject payload/free-text/evidence/tool projection.
3. Extend the upgrade harness first so V009's concurrent/non-transactional execution, valid-index catalog state, mixed-version writes, forward recovery checks, and query-plan evidence are executable rather than prose.
4. Run the focused Platform API suite first, then validator/migration scripts, then one broader Platform API pass.

## Implementation Steps

1. Update Phase 4 validation for representation v1, both closed source variants, 406, exact headers, fixtures, and unchanged legacy markers (`scripts/validation/validate-phase-04-incident-contracts.mjs:108-173`, `scripts/validation/phase-04-incident-contracts/openapi-static-contract-validator.mjs:47-150`).
2. Update Phase 7 validation for ANALYZE-only linkage, eight allowed fields, branch-local org/project/incident predicates, no JSON payload read, and forbidden-byte sentinels (`scripts/validation/validate-phase-07-investigation-slice.mjs:104-190`).
3. Extend Phase 4B migration validation and upgrade script for V009 fresh/upgrade execution, `.sql.conf`, valid indexes, migration-before-code compatibility, bounded EXPLAIN evidence, exact row/latency/storage thresholds, and recovery instructions. Recovery must capture failed Flyway history plus both V009-owned index rows, drop both exact names concurrently (valid or invalid), invoke Flyway `repair` through `FlywayRecoveryHarnessTest`/the approved Flyway Java API seam, and retry. Do not treat index-name markers alone as proof.
4. Update architecture, codebase summary, roadmap, progress, and deployment guide. State: forward-only live view (not snapshot/feed), ANALYZE authorization, concurrent migration/recovery procedure, measured index cost, and remaining live connector/provider/BFF/human-pilot gates.
5. Run a blocking code-review pass focused on v1 compatibility, authorization/data classification, same-tenant isolation, cursor semantics, content negotiation/cache, query-plan bounds, and online migration recovery.

## Validation

- `node scripts/validation/validate-phase-04-incident-contracts.mjs`
- `node scripts/validation/validate-phase-04b-evidence-records.mjs`
- `node scripts/validation/validate-phase-07-investigation-slice.mjs`
- With the disposable PostgreSQL/JAR environment from PR quality: `bash scripts/validation/run-phase-04b-migration-upgrade.sh`
- `mvn -f services/platform-api/pom.xml "-Dtest=FlywayRecoveryHarnessTest" test` in the disposable recovery database
- `mvn -f services/platform-api/pom.xml test`
- Review gate: `ck code-review` or repository-standard code review checklist against the timeline diff

## Risk Assessment

- Medium: validators pass on marker strings while runtime behavior drifts. Mitigation: keep HTTP/integration tests from Phase 2 mandatory before claiming success.
- Medium: docs overclaim Phase 7 or Phase 9 completion. Mitigation: update only the timeline-linkage statements and keep the human-pilot, live connector, and BFF/session blockers unchanged.
- High: migration evidence hides invalid indexes or unbounded union scans. Mitigation: executable catalog and EXPLAIN assertions plus captured append/storage measurements.

## Rollback

- Revert validator and doc updates with the code rollback from Phases 1-2.
- Keep valid V009 indexes only while measured budgets justify them; otherwise use a later forward migration to drop them concurrently. Continue to describe Phase 7 as blocked if bridge code is disabled.

## Success Criteria

- [ ] Phase 4/4B/7 validators pass with the representation v1 contract, ANALYZE-only eight-field bridge, and V009 fresh/upgrade/recovery evidence.
- [ ] Docs state that incident activity timeline linkage is delivered, while the remaining Phase 7/8/9 blockers stay explicit.
- [ ] A focused review confirms legacy v1 compatibility, data classification, same-tenant isolation, live-view cursor semantics, negotiation/cache safety, bounded query plan, migration recovery, and no forbidden-byte leakage.
