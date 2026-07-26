# Evaluation

This directory owns versioned, secret-free evaluation contracts and synthetic
scenario inputs. It is not a training export and must never contain customer
telemetry, provider credentials, hidden reasoning, or production evidence.

## Current checkpoint

Phase 8B implements the deterministic contracts for all three smoke families:

| Scenario | Required behavior |
|---|---|
| A — `deployment-latency-regression` | One read-only metrics collection supports a completed latency-regression hypothesis. |
| B — `insufficient-evidence-abstain` | `ABSTAINED`, zero tools/citations/hypotheses, at least one missing-evidence item, and low confidence. |
| C — `conflicting-evidence-regression` | Two opposing read-only evidence collections, digest-bound counter-evidence, completed cautious hypothesis, and confidence no greater than `0.6`. |

Platform Flyway V008 is the expand step of a rolling migration: legacy V007
writers remain accepted, while response-aware writers bind each normalized
response to its immutable investigation event and validate it strictly. Legacy
events without `response` are readable but not evaluation-eligible; a later
contract migration may require `response` only after old writers are drained.
Tool Gateway Flyway V002 records the observed tool/action/risk, connector
ID/profile, and SHA-256 digest of the exact manifest bytes selected at runtime.
The cross-service harness can export exact
organization/project/incident/run/actor scope through a non-login view owner
and a non-inheriting, read-only evaluator. Security-barrier views expose only
bounded run/event, accepted-analysis, evidence-metadata, AI-invocation, and
receipt/audit fields. They expose no prompts, provider reasoning, credentials,
raw connector bodies, or evidence content.

The Node projector rejects duplicate keys, unsafe values, unknown fields,
identity drift, ambiguous analysis bindings, receipt/evidence drift, and
oversized exports. It rejects malformed UTF-8 and duplicate evidence digests,
binds invocation model/prompt/schema/token/tool/cost accounting to the accepted
response, and derives `toolExecutions` only from requested intents plus durable
receipt/audit/evidence bindings. Audit result, receipt evidence, and persisted
evidence digests must be identical. Scenario C identifies metric meaning from
canonical metric content rather than row or UUID order. Raw and canonical JSON
use separate typed, domain-separated digests. `.gitattributes` pins LF for
every digest-bound fixture, manifest, query, and evaluator source so Windows and
Linux checkouts attest identical bytes.

Managed paths reject reparse-point ancestors before secret or export writes.
Cleanup deletes ephemeral credentials and raw exports before process/container
cleanup, aggregates failures, and refuses unsafe recursive removal. Only a
strictly verified managed trace and redacted verification output may survive.

The independent earlier tester recorded 28/28 Node tests. The current
remediation rerun passes 33/33 Node tests and 40/40 targeted Python tests; the
semantic evidence-order test passes 50 shuffled trials and the junction-path
safety test passes. The validator reports
`Implemented=3 CanonicalResults=3 Errors=0 CheckpointResult=PASS
PhaseExit=BLOCK`. Fresh Docker/PostgreSQL A/B/C runs, executable attestation,
the exact pushed revision, and the uploaded CI artifact remain pending. This is
not a Phase 8B PASS or a production RCA, population p95, calibration, held-out,
or human-benefit claim.

## Run

After storage preflight, provide an existing managed Phase 7 trace:

```powershell
pnpm evaluate
```

`evaluate` validates the contracts and scores the trace selected by
`OPSMIND_EVALUATION_TRACE_PATH` (default
`.opsmind/reports/cross-service-trace.json`). It does not start Docker,
PostgreSQL, or the cross-service harness and does not generate a trace.

Generated reports remain under `OPS_ARTIFACT_ROOT/evaluation/phase-08/` and are
ignored by Git. The committed scenario fixtures stay small, deterministic, and
explicitly ineligible for training.
