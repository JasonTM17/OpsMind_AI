# Held-out corpus

This directory registers evaluation cases the system was not built against. It
holds references only. No case payload is committed here, and none may be.

`manifest.yaml` carries JSON under a `.yaml` name, matching the convention
`evaluation/benchmark-manifest.yaml` already sets.

## Why the payloads stay out of Git

A case stops being held out the moment it is visible to anyone tuning the
system. Committing payloads would also place incident text and customer
telemetry in a public repository, which the security model forbids outright.

## Where payloads live

Payloads are read from the directory named by `OPS_EVALUATION_HELDOUT_ROOT`.
That variable is unset by default. While it is unset, the corpus reports
`UNAVAILABLE`, and no metric derived from it may be scored. An empty corpus is
recorded as an absence of evidence, never as a pass.

## Registering a case

1. Place the case JSON under the payload root at the `relative_path` you intend
   to record. Paths are resolved beneath that root; a path that escapes it, or
   that is reachable only through a symlink or junction, is refused.
2. Compute its digest and byte size:

   ```sh
   sha256sum "$OPS_EVALUATION_HELDOUT_ROOT/<relative-path>"
   wc -c < "$OPS_EVALUATION_HELDOUT_ROOT/<relative-path>"
   ```

3. Append an entry to `cases` with `case_id`, the owning `family_id` from
   `evaluation/benchmark-manifest.yaml`, `relative_path`, `content_digest` as
   `sha256:<hex>`, `byte_size`, `added_at` in UTC, and a `contamination_tag`.
4. Run `node scripts/validation/validate-phase-08-evaluation-foundation.mjs`.
   A digest that does not match the payload, a family id that does not exist, or
   an unknown field fails the gate.

## Contamination tags

| Tag | Meaning |
|---|---|
| `never-trained` | Never used for training or fine-tuning. |
| `never-tuned` | Never used to select prompts, thresholds, or model versions. |
| `quarantined` | Suspected leakage. Registered for tracking, excluded from scoring. |

A case that was ever used to tune anything is not held out. Retag it
`quarantined` rather than deleting the entry, so the leak stays visible.
