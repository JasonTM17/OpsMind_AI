---
phase: 1
title: Evaluation Evidence Contract
status: completed
priority: P1
dependencies: []
effort: 0.5-1 day
---

# Phase 1: Evaluation Evidence Contract

## Overview

Persist every accepted normalized response, then define one strict projection
contract for the disposable cross-service database. Preserve Scenario A while
making terminal semantics scenario-aware.

## Requirements

- The database export is an untrusted input even though its source is local.
- Only normalized accepted analysis and metadata required for scoring may pass.
- Raw prompt, provider reasoning, credentials, capability material, raw
  connector content and unknown fields are rejected recursively.
- Every free-text leaf is scanned/redacted; allowed keys alone are insufficient.
- Raw-byte SHA-256 and domain-separated canonical SHA-256 have distinct names
  and bind the SQL, export, projected fragments and benchmark references.
- Digest-bound fixtures, manifests, SQL, and evaluator sources are pinned to LF
  and validated so checkout EOL conversion cannot change provenance.
- Duplicate JSON keys are rejected from raw bytes before parsing.
- Existing `opsmind-cross-service-trace-v1` Scenario A remains readable.

## Architecture

Platform V008 is an expand migration. It accepts the exact response-less V007
shape during rolling deployment and the exact response-bearing shape from new
writers. When present, the normalized response is validated for
shape/status/run identity and, for a completed run, equality with
`final_response`. Evaluator export requires the response-bearing shape; the
later contract migration waits until old writers are drained. Tool Gateway V002 records
observed connector identity/profile and the digest of exact manifest bytes
selected at runtime. The SQL exporter also requires exactly one successful V004
invocation whose response equals each accepted event. A pure Node projector
validates bytes, exact keys and scope, maps manifest provenance, computes typed
digests, and attaches only:

- event IDs, sequence, type and timestamps;
- evidence IDs/digests plus provenance/policy metadata, never content;
- accepted normalized responses and invocation provenance, never prompts or
  provider reasoning;
- completed receipt/audit IDs, request/result digests, manifest/policy and
  cited evidence digests, connector ID/profile/exact manifest-byte digest,
  never raw response bodies.

## Current Evidence

V008/V002, the strict export schema, projection modules, raw-analysis
discriminator, scenario-aware scorer, and negative tests are implemented. The
projector now binds invocation accounting, sums all accepted invocation cost,
and exact-binds audit result, receipt evidence, and persisted evidence digests.
Historical local remediation passed 33/33 Node tests and 40/40 targeted Python
tests. The expanded evaluation suite now passes 52/52, and the Phase 8 validator
reports three implemented/canonical results with zero errors.
Cross-service run `30209209999` proves fresh migration application and exact
accepted-response/invocation/tool-proof matching for A/B/C on revision
`df4620313a3f39721ef1bb521a9cf7ddcac5929c`; all scenarios and all eight
metrics pass. This contract phase is
complete; broader statistical and human evidence remains outside its boundary.

## Related Code Files

| Action | Path | Purpose |
|---|---|---|
| Create | `evaluation/schemas/cross-service-evaluation-export.schema.json` | Strict transient export shape |
| Create | `evaluation/runner/cross-service-evaluation-projection.mjs` | Pure validation/canonical projection |
| Create | `evaluation/runner/cross-service-evaluation-projection.test.mjs` | TDD contract/security/digest cases |
| Create | `scripts/validation/cross-service/cross-service-evaluation-export.sql` | Allowlisted disposable DB query |
| Create | `services/platform-api/src/main/resources/db/migration/V008__accepted_analysis_event_binding.sql` | Durable accepted-response proof |
| Create | `services/tool-gateway/src/main/resources/db/migration/V002__durable_tool_execution_provenance.sql` | Durable observed connector provenance |
| Modify | `services/platform-api/src/main/java/ai/opsmind/platform/investigation/domain/InvestigationEvent.java` | Carry accepted normalized response |
| Modify | `services/platform-api/src/main/java/ai/opsmind/platform/investigation/domain/InvestigationTransitions.java` | Emit response-bound acceptance event |
| Modify | `services/platform-api/src/test/**/investigation/**` | Reducer/persistence/trigger compatibility |
| Modify | `evaluation/schemas/benchmark-result.schema.json` | Digest-bearing timeline/evidence/receipt references |
| Modify | `evaluation/schemas/scenario-ground-truth.schema.json` | Abstention/conflict bounds |
| Modify | `evaluation/runner/raw-analysis-contract.mjs` | Discriminated complete/abstain validation |
| Modify | `evaluation/runner/score-phase-07-projection.mjs` | Scenario-aware checks |
| Modify | `evaluation/runner/score-phase-07-trace-core.mjs` | Projection integrity and raw references |

## Implementation Steps

1. Write migration/domain tests proving complete/need-more/abstain responses
   are immutable in accepted events and status/run mismatches fail.
2. Write negative projector tests for duplicate keys, secrets inside allowed
   strings, extra fields, malformed digests, cross-run rows, event gaps,
   invocation ambiguity, connector substitution and receipt/audit mismatch.
3. Add exact-key/value validators and typed canonical serialization using
   `stableStringify`; cap SQL rows, individual responses and aggregate bytes.
4. Version the export schema without changing public runtime APIs.
5. Generalize analysis validation: `complete` requires cited hypotheses;
   `abstain` requires no hypotheses/citations/tool calls, non-empty evidence
   gaps and bounded low confidence.
6. Require `maximum_confidence`, `minimum_counter_evidence_items` and explicit
   counter-evidence note-digest/evidence-digest bindings in ground truth.
7. Include digest type/domain on every raw artifact reference; reject
   duplicates and ambiguous byte-vs-semantic claims.

## Test Matrix

| Case | Expected |
|---|---|
| Canonical Scenario A | PASS, same eight metric families |
| Unknown/nested prohibited field | reject before publication |
| Changed artifact byte | byte digest FAIL |
| Semantically changed analysis/receipt/event/evidence | canonical digest FAIL |
| Foreign tenant/project/incident/run row | reject |
| Secret inside a valid explanation/claim/rationale/note key | reject |
| Duplicate JSON key | reject before `JSON.parse` |
| Zero/multiple V004 matches for accepted event | reject |
| Fixture/Prometheus connector substituted | reject |
| Missing event/evidence/receipt/reference | `INCOMPLETE` |
| Audit result/receipt/persisted evidence digest split | reject |
| Earlier accepted invocation cost hidden by zero-cost final round | cost FAIL |
| Reordered source rows | canonical output unchanged |

## Success Criteria

- [x] Strict export schema and pure projector tests pass locally.
- [x] Scenario A remains backward compatible in the local fixture/scorer suite.
- [x] Secret/reasoning/raw-content keys or unsafe values fail local projection tests.
- [x] V008 and local migration/domain contracts bind every response-aware
      accepted event while retaining legacy-writer compatibility.
- [x] Fresh disposable PostgreSQL proves V008 application and accepted-response
      matching in all A/B/C production-path runs.
- [x] Local canonical projections emit exact fragments with typed digests.

## Risk Assessment

Main risk is widening immutable event payloads. V008 is additive for stored
history, strict when a response is present, and temporarily accepts the exact
legacy shape for rolling deployment. Upgrade tests cover unchanged history and
post-V008 legacy append. A later forward-only contract migration may require
responses after old instances are drained; no destructive down-migration.
