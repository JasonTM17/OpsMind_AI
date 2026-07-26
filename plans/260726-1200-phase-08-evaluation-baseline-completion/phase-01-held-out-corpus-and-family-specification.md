---
phase: 1
title: Held-out corpus governance
status: completed
priority: P1
dependencies: []
effort: 1 day
---

# Phase 1: Held-out corpus governance

## Overview

Give the evaluation a way to reference cases the system was not built against,
without those cases entering the repository, and make the absence of such cases
report as absence rather than as coverage.

## Scope Correction

The ten scenario families are already specified in
`evaluation/benchmark-manifest.yaml` as `SIM-01` through `SIM-10`, three
implemented and seven reserved, and the Phase 8 validator already schema-checks
that file. This phase does not touch it beyond adding the held-out reference.

## Requirements

- Functional: a held-out manifest references cases by content digest and byte
  size, and resolves payloads from a root configured outside Git.
- Functional: resolution fails closed when a payload is missing, altered,
  oversized, or reachable only through a reparse point.
- Functional: with no root configured, the corpus reports an explicit
  unavailable reason. It never reports zero failures as success.
- Non-functional: no case payload, incident text, or customer telemetry is
  committed. The manifest and its README are the only committed artifacts.
- Non-functional: existing scenario scoring is unchanged.

## Architecture

`evaluation/held-out/manifest.yaml` carries JSON under a `.yaml` name, matching
the convention `evaluation/benchmark-manifest.yaml` already set. Entries are
`{case_id, family_id, content_digest, byte_size, added_at, contamination_tag}`.

Payload bytes live under `OPS_EVALUATION_HELDOUT_ROOT`, unset by default. The
resolver returns one of three states, mirroring the shape the scorer already
uses for an unavailable metric:

- `UNAVAILABLE` when the root is unset or the manifest lists no cases;
- resolved cases when every referenced payload matches its digest and size;
- a hard contract failure when any referenced payload is missing or drifted.

Path handling reuses the rules the projector applies: absolute resolution,
containment under the configured root, and rejection of reparse-point ancestors.

## Related Code Files

- Create: `evaluation/held-out/manifest.yaml`
- Create: `evaluation/held-out/README.md`
- Create: `evaluation/schemas/held-out-manifest.schema.json`
- Create: `evaluation/runner/held-out-corpus.mjs`
- Create: `evaluation/runner/held-out-corpus.test.mjs`
- Modify: `evaluation/benchmark-manifest.yaml`
- Modify: `evaluation/schemas/benchmark-manifest.schema.json`
- Modify: `scripts/validation/validate-phase-08-evaluation-foundation.mjs`
- Modify: `scripts/validation/validate-repository-layout.mjs`
- Modify: `plans/260719-1747-opsmind-ai-production-platform/phase-08-simulator-and-evaluation-baseline.md`

## Implementation Steps

1. Write `held-out-manifest.schema.json` rejecting unknown fields, requiring the
   `sha256:` digest form already used across the repository, and bounding case
   count and byte size.
2. Author an empty-but-valid `evaluation/held-out/manifest.yaml` and a README
   explaining how a reviewer registers a case without committing its bytes.
3. Implement `held-out-corpus.mjs` returning the three states above, with
   containment and reparse checks before any read.
4. Add a `held_out_manifest_path` field to the benchmark manifest and its schema
   so the two are linked rather than independently discoverable.
5. Extend the Phase 8 validator: the held-out manifest parses and validates,
   every referenced `family_id` exists in the benchmark manifest, and the
   unavailable state is reported rather than skipped.
6. Record the simulator scope decision in the parent phase file so its inventory
   stops listing unbuilt work as pending.

## Success Criteria

- [x] The manifest validates, and an unknown field, a malformed digest, or a
      family id absent from the benchmark manifest each fail the validator.
- [x] A missing, altered, or oversized payload produces a contract failure; an
      unset root produces `UNAVAILABLE` with its reason.
- [x] A payload reachable only through a symlink or junction is refused.
- [x] No payload bytes are committed; secret scan and layout validator pass.
- [x] The parent phase file states the simulator decision and its reason.

## Risk Assessment

An empty manifest can read as coverage. The validator must distinguish "no
held-out cases configured" from "held-out cases passed", and the reported state
must carry the reason so a later reader cannot mistake silence for evidence.

Adding a field to the benchmark manifest touches a file the Phase 8 validator
asserts against. The change is additive and validated in the same commit, so a
mismatch fails locally before it reaches CI.
