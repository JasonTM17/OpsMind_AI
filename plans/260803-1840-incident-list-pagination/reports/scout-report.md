# Incident List Pagination Scout Report

## Summary

| Scope | Current state | Decision |
|---|---|---|
| Contracts | Create/detail/transition/timeline exist; collection GET absent | Add strict summary/page schemas and fixtures |
| Runtime | Phase 4A auth/transaction/RLS patterns reusable; V003 order/index shape does not fit both queries | New no-persistent-write list service/repository plus online additive V016 indexes |
| Evidence | Static, focused, disposable PostgreSQL, and remote CI gates exist | TDD, then broad and revision-bound verification |

## Boundaries

- Resolve/close already works through transitions; exercise later without new route.
- Free-text search lacks locked semantics/index and is not part of this slice.
- Patch/owner needs a forward migration; alert/postmortem contracts are undefined.
- Artifact reference/lifecycle remains externally gated by B-006/B-008/B-012.

## Unresolved Questions

- Exact free-text search semantics and index strategy.
- Owner and alert identity/cardinality models.
- Postmortem versioning/approval lifecycle.
- Incident-facing evidence reference timing relative to artifact lifecycle gates.
