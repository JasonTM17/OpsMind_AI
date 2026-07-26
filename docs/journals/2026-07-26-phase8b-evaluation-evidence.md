# Phase 8B Evaluation Evidence Was Testing the Wrong Contract

**Date**: 2026-07-26 12:21
**Severity**: Critical
**Component**: Evaluation runner, cross-service harness, Tool Gateway provenance, Platform V008
**Status**: Ongoing

## What Happened

Authored fixtures passed while hiding that the production trace omitted `toolExecutions` and Scenario B legitimately returned `operatorProjection.analysis: null`. A runner-shaped Scenario A scored `INCOMPLETE` with `tool_selection=UNAVAILABLE`; live-shaped B crashed with `TypeError: Cannot read properties of null`. Scenario C also depended on authored UUIDs/digests that fresh runs cannot reproduce.

## The Brutal Truth

We tested the story we wrote, not the system we built. The fixtures smuggled in proof the production path never emitted, then let green tests manufacture confidence. That is maddening because an evidence-first platform had false evidence in its own evaluation gate. Worse, cleanup could follow a junction outside the managed root or stop before deleting private keys, tokens, `postgres.env`, and raw exports.

## Technical Details

The review exposed invocation fields that could disagree with the accepted response, hardcoded connector provenance instead of the digest of runtime-loaded manifest bytes, split audit-result/receipt/persisted-evidence digests, and final-round-only cost that undercounted multi-round runs. Scenario C assigned meaning by UUID order; 20 random identities produced both orders, so the fixture could cite latency as counter-evidence. V008 had string-level checks but no real V007→V008 history upgrade proof.

## What We Tried

- Rejected authored trace overlays; [the projector](../../evaluation/runner/cross-service-evaluation-projection.mjs) now derives tool executions from accepted intents, durable receipts, and persisted evidence.
- Rejected self-asserted provenance; Tool Gateway V002 persists connector ID/profile and SHA-256 of the bytes loaded by `ToolManifestResourceLoader`.
- Rejected positional Scenario C mapping; `fixture-provider.py` selects `http_request_duration_seconds` and `http_errors_total` semantically.
- Rejected Scenario C's contradictory timing story; fixture, ground truth, provider, trace, and digest now describe the same flat-error counter-signal.
- Rejected lexical-only path checks and late cleanup; every existing ancestor is reparse-checked before writes, and credentials/raw exports are deleted before process or container cleanup.
- Rejected final-response cost and migration string inspection; scoring sums all accepted invocation costs, while the upgrade script seeds V007 history, migrates to V008, and proves the legacy payload digest is unchanged.
- Rejected the impossible old-app/new-schema deployment order; V008 is now an expand migration that accepts exact legacy writes during rollout and strictly validates response-bearing writes. Contract enforcement waits for old-writer drain.

## Root Cause Analysis

We treated canonical fixtures as integration evidence and failed to make the producer→export→projection→scorer shape one contract. Trust claims were copied from expectations instead of derived from runtime state.

## Lessons Learned

Evaluation fixtures are test inputs, never production-path proof. Every identity, cost, digest, and provenance claim must come from an independently observed durable record. Cleanup is security code and needs adversarial filesystem tests.

## Next Steps

- CI owner, before review closure: run fresh disposable Docker/PostgreSQL A/B/C on one clean pushed revision; publish executable attestation and the `phase-08b-cross-service-evaluation` artifact.
- Review owner, after that run: re-audit the original blockers and verify cleanup leaves zero secrets/raw exports.
- Phase owner: keep Phase 8 and the parent gate blocked until terminal-green heavy CI exists.

Current lightweight evidence only: 33/33 Node tests, 40/40 targeted Python tests, 50 shuffled semantic-order trials, and the junction-path safety test pass. Heavy CI remains pending.

## Unresolved Questions

- Which gate label is canonical for this exit: parent-plan G4 or roadmap G7?
- What exact pushed revision and workflow run will become the authoritative Phase 8B artifact?
