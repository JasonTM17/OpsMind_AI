---
title: Phase 9 legacy Temporal ambiguity follow-up
date: 2026-07-28
scope: 9fd1d82..5487238
status: done
---

# Phase 9 Legacy Temporal Ambiguity Follow-up

## Summary

Both High findings raised after the first V012 integration review are closed.
No new Critical, High, or Medium source defect was found.

## Verified Fixes

### V011 legacy ambiguity cannot become a false rejection

V012 now normalizes only canonical workflow-start rows that:

- have at least one dispatch attempt;
- retain the legacy `workflow.temporal-unavailable` marker;
- are unpublished and unpoisoned; and
- have the exact matching workflow binding still `PENDING`.

Preflight also recognizes both the legacy and current ambiguity markers before
lease-window, deadline, dispatcher-eligibility, or authorization terminal
branches. The upgrade harness seeds the legacy V011 state before V012 and
asserts normalization, exact claim, reconciliation-required preflight, safe
parking, and a still-`PENDING` binding after migration.

### Durable ambiguity is fenced before payload terminalization

`InvestigationWorkflowStartDispatcher` now executes durable preflight before
payload digest verification or decode. A reconciliation-required or
out-of-budget ambiguous row is parked before a corrupt or undecodable payload
can trigger terminal rejection. Unit regressions cover SQL-directed
reconciliation and exhausted ambiguity with a digest-valid malformed payload.

## Integration

| Item | Revision |
|---|---|
| Java preflight/decode fix | `6e7fe98` |
| Legacy migration/upgrade fix | `9594b17` |
| Java merge | `44c3861` |
| Migration merge / reviewed integration head | `5487238` |

## Verification

| Check | Result | Limit |
|---|---|---|
| Independent follow-up review | PASS; no Critical/High/Medium | Source review |
| Phase 9 validator | PASS; 0 errors | Static contract gate |
| Upgrade script `bash -n` | PASS | Syntax only |
| `git diff --check` | PASS | Formatting only |
| V012 apply in transaction against V011 diagnostic DB | PASS then ROLLBACK | Migration execution, no legacy fixture |
| Upgrade harness legacy fixture | Added | Full harness not executed locally |
| Maven/Flyway full runtime and exact-head CI | NOT RUN | D: capacity guard remains BLOCK |

## Remaining Release Gates

- Execute the full fresh and V001-to-current Flyway role/atomicity matrix.
- Run focused Java/Maven tests and exact-head CI.
- Capture predecessor-query `EXPLAIN` and accepted latency threshold evidence.
- Implement and prove the separately authorized no-Start reconciliation/alert
  lane for `workflow.reconciliation-required`.

## Unresolved Questions

1. Which production component owns the reconciliation credential and bounded
   aging alert?
2. What predecessor-query latency threshold is accepted for production?

Status: DONE

Summary: Both legacy-upgrade and corrupt-payload ambiguity defects are closed
in source and independently re-reviewed.

Concerns/Blockers: Full configured runtime/upgrade evidence and B-017
reconciliation/performance gates remain pending.
