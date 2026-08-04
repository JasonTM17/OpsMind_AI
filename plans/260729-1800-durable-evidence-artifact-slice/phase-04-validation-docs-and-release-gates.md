---
phase: 4
title: "Validation docs and release gates"
status: pending
effort: "3h"
---

# Phase 4: Validation docs and release gates

## Overview

Add the validation scripts, contract runners, and documentation updates that
bind the slice to evidence without over-claiming production readiness.

## Context Links

- Blockers:
  `docs/blockers.md:15-19`
- Existing V007 static gate:
  `scripts/validation/validate-phase-04b-evidence-records.mjs:330`
- Parent documentation duties:
  `plans/260719-1747-opsmind-ai-production-platform/phase-04-incident-control-plane-and-audit-ledger.md:236-238`
- Restore mismatch:
  `docs/deployment-guide.md:373-378`

## Requirements

- Add a dedicated validator for the artifact slice.
- Keep the historical 4B validator scoped to V007 rather than banning later
  additive migrations.
- Add disposable PostgreSQL contract runners for the new metadata/lifecycle
  tables.
- Update architecture/testing/progress docs only to proven facts.
- Keep B-006, B-008, B-011, and B-012 explicit unless their required evidence
  actually exists.

## Related Code Files

### Create

- `scripts/validation/validate-phase-04-artifact-slice.mjs`
- `scripts/validation/run-phase-04-artifact-contract.ps1`
- `scripts/validation/run-phase-04-artifact-contract.sh`

### Modify

- `scripts/validation/validate-phase-04b-evidence-records.mjs`
- `docs/system-architecture.md`
- `docs/testing-strategy.md`
- `docs/deployment-guide.md`
- `docs/progress.md`
- `docs/codebase-summary.md`

### File Ownership

Phase 4 exclusively owns the validation scripts and documentation sync. It does
not change application code except when a validator needs an exact file path or
symbol reference.

## Implementation Steps

1. Create a dedicated artifact-slice validator and disposable PostgreSQL
   contract runner.
2. Narrow the 4B validator so it continues to assert V007 inline-only semantics
   without rejecting V014.
3. Add migration, authorization, lifecycle, and reconciliation checks to the
   new artifact contract runner.
4. Update architecture/testing/deployment/progress docs to match the proven
   slice and still name remaining blockers.
5. Bind each completed claim to a test command or CI artifact path.

## Test Matrix

| Scope | Validation |
|---|---|
| Static | V007 remains inline-only, V014 exists, file inventories are present |
| Disposable DB | fresh/upgrade, RLS, lifecycle transitions, reconciliation outcomes |
| Docs | links, dates, blocker statements, gate wording |

## Success Criteria

- [ ] A dedicated artifact-slice validator exists and passes.
- [ ] The 4B validator still protects V007 without blocking additive V014 work.
- [ ] Disposable PostgreSQL artifact-lifecycle tests pass.
- [ ] Docs state exactly what the slice proves and what remains blocked.
- [ ] No documentation or validator text implies B-012/B-011 closure.

## Risk Assessment

- High: validators over-claim production readiness. Mitigation: map every claim
  to explicit missing blocker evidence.
- Medium: documentation drifts from actual code paths. Mitigation: cite exact
  files and line references while updating docs.

## Rollback

- Revert validator/doc updates with the code that made them true; never rewrite
  prior migration history or CI artifacts.
