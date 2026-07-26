---
phase: 1
title: "Held-out corpus and family specification"
status: pending
priority: P1
dependencies: []
effort: "1 day"
---

# Phase 1: Held-out corpus and family specification

## Overview

Give the evaluation a corpus it was not built against, and name the ten scenario
families precisely enough that a later family cannot silently duplicate an
earlier one.

## Requirements

- Functional: a held-out manifest references cases by digest, resolves them from
  a configured root outside Git, and fails closed when a referenced case is
  absent, altered, or unreadable.
- Functional: a benchmark manifest lists ten families, each with an id, a
  discriminating question, the failure it must expose, and its implementation
  status.
- Non-functional: no held-out payload, customer telemetry, or incident text
  enters the repository. The manifest is the only committed artifact.
- Non-functional: the existing three scenarios keep passing unchanged.

## Architecture

`evaluation/held-out/manifest.yaml` lists case entries of
`{case_id, family_id, content_digest, byte_size, added_at, contamination_tag}`.
Payloads live under `OPS_EVALUATION_HELDOUT_ROOT`, which defaults to unset. When
unset, held-out scoring reports `UNAVAILABLE` rather than passing vacuously —
the same fail-closed shape the scorer already uses for `root_cause_semantic`.

`evaluation/benchmark-manifest.yaml` records the ten families. Three carry
`status: implemented` and point at the existing `evaluation/scenarios/`
directories; seven carry `status: specified` and belong to Phase 16.

A resolver module validates a manifest, resolves payload paths under the
configured root, verifies digests, and refuses reparse-point ancestors, reusing
the path rules the projector already applies.

## Related Code Files

- Create: `evaluation/held-out/manifest.yaml`
- Create: `evaluation/held-out/README.md`
- Create: `evaluation/benchmark-manifest.yaml`
- Create: `evaluation/schemas/held-out-manifest.schema.json`
- Create: `evaluation/schemas/benchmark-manifest.schema.json`
- Create: `evaluation/runner/held-out-corpus.mjs`
- Create: `evaluation/runner/held-out-corpus.test.mjs`
- Modify: `scripts/validation/validate-phase-08-evaluation-foundation.mjs`
- Modify: `scripts/validation/validate-repository-layout.mjs`
- Modify: `plans/260719-1747-opsmind-ai-production-platform/phase-08-simulator-and-evaluation-baseline.md`

## Implementation Steps

1. Write both JSON schemas, rejecting unknown fields and requiring digests of
   the form the repository already uses.
2. Write `held-out-corpus.mjs`: parse and schema-validate a manifest, resolve
   the configured root, verify each digest, and return either resolved cases or
   an explicit unavailable reason. Refuse absolute traversal and reparse
   ancestors.
3. Author `benchmark-manifest.yaml` with ten families. Give each a
   discriminating question that no other family answers the same way.
4. Author an empty-but-valid `evaluation/held-out/manifest.yaml` with zero cases
   and a README stating how a reviewer adds one without committing payloads.
5. Extend the Phase 8 validator: both manifests parse, validate, and stay
   consistent with `evaluation/scenarios/`; exactly three families are
   implemented; every implemented family resolves to a real ground truth.
6. Record the simulator scope decision in the parent phase file so the inventory
   stops listing unbuilt work as pending.

## Success Criteria

- [ ] Both manifests validate against their schemas and the validator fails when
      a family id, digest, or status is inconsistent.
- [ ] A missing, altered, or oversized held-out payload produces `UNAVAILABLE`
      or a hard failure, never a pass.
- [ ] Ten families are specified with distinct discriminating questions; three
      are implemented and resolve to existing ground truths.
- [ ] No payload bytes are committed; the secret scanner and layout validator
      both pass.
- [ ] The parent phase file states the simulator decision with its reason.

## Risk Assessment

A manifest that references nothing can look like coverage. The validator must
assert the difference between "zero held-out cases configured" and "held-out
cases passed", and the scorer must never treat the first as evidence.

Ten families invented in one sitting risk being restatements of each other. The
discriminating question per family is the control: if two questions can be
answered by the same trace, the families are not distinct and the validator
should not be the only thing that notices.
