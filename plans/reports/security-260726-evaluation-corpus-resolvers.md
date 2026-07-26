# Security audit — evaluation corpus resolvers

Scope: `evaluation/runner/held-out-corpus.mjs`,
`evaluation/runner/human-baseline.mjs`, and their wiring in
`scripts/validation/validate-phase-08-evaluation-foundation.mjs`.

Reason for scope: both modules read files from roots configured *outside* the
repository, which is the only place in the evaluation subsystem that crosses
that boundary. Both were written in this same session, so they had no prior
independent review.

Method: STRIDE per module, then empirical probes. Every finding below was
reproduced before being reported and re-probed after the fix.

## Summary

| Severity | Count | Status |
|---|---|---|
| High | 1 | Fixed |
| Medium | 2 | Fixed |
| Low | 1 | Fixed |

Fixed in `62f8c89`. Local gates and revision-bound CI green after the fix.

## Findings

### 1. HIGH — Record contract was documented but never enforced

Category: STRIDE Tampering / OWASP A04 Insecure Design.
Location: `evaluation/runner/human-baseline.mjs` ingestion loop.

`evaluation/human-baseline-protocol.md` states that a submission cannot carry
incident narrative, customer identifiers, or reviewer commentary "because there
is nowhere to put it". `evaluation/schemas/human-baseline-record.schema.json`
sets `additionalProperties: false` and defines no free-text field, but no code
path applied that schema. Ingestion performed ad hoc field checks only.

Probe: a record carrying `notes: "customer ACME outage, db password was
hunter2"` was accepted and counted toward a resolved case.

Impact: the control the protocol advertises did not exist. Incident narrative
and credential material could enter the evidence path through a field nobody
declared, and the phase criterion asserting the schema rejects such fields was
satisfied by tests of the ad hoc checks rather than of the schema.

Fix: ingestion validates each record against the schema before use. Re-probe
returns `HUMAN_BASELINE_RECORD` for the same input.

### 2. MEDIUM — Record path was not constrained to its configured root

Category: STRIDE Tampering / OWASP A01 Broken Access Control.
Location: `evaluation/runner/human-baseline.mjs`.

The sibling held-out resolver checks containment with `isWithin`; this one
joined the listing entry to the root and read it. Real directory entries cannot
contain separators, so the production path was not exploitable, but the listing
seam accepts any name and the asymmetry meant a future caller supplying its own
listing would read outside the root.

Probe: a listing of `["../escaped-<pid>.json"]` read a file outside the root.

Fix: containment is now required, matching the held-out resolver. Re-probe
returns `HUMAN_BASELINE_PATH`.

### 3. MEDIUM — Record was read before being bounded

Category: STRIDE Denial of Service.
Location: `evaluation/runner/human-baseline.mjs`.

`readFileSync` ran with no size check. The 4 MiB bound inside
`parseUntrustedJsonExport` applies to bytes already in memory, so an oversized
file was read in full before rejection. The held-out resolver bounds size before
reading; this one did not.

Fix: a 64 KiB bound is checked before the read.

### 4. LOW — Identifiers reached failure messages unvalidated

Category: STRIDE Repudiation / log injection.
Location: both resolvers.

Failure messages embedded `case_id` and file names directly. Those messages land
in redacted evidence transcripts, where the harness already treats forged status
lines as a threat and tests for them. A manifest identifier containing newlines
or terminal escapes could inject transcript lines.

Fix: identifiers are restricted to a printable single-line shape before use in a
message, otherwise replaced with a placeholder.

## Not findings

- **Held-out payload substitution after the link check.** A time-of-check to
  time-of-use swap is possible in principle, but the digest comparison follows
  the read, so substituted content fails closed. Whoever controls the payload
  root is the corpus owner in this threat model.
- **Hard links into a held-out payload.** Indistinguishable from a regular file
  by design, but content still has to match the registered digest, so a hard
  link supplies nothing an attacker did not already need to know.
- **Env-controlled roots.** An attacker able to set `OPS_EVALUATION_HELDOUT_ROOT`
  can point it anywhere, but every payload must match a digest recorded in the
  committed manifest, so redirection fails closed rather than injecting cases.

## Follow-up applied

The held-out manifest is now schema-validated inside the resolver as well as in
the validator. Enforcing a contract in only one of two places is exactly how
finding 1 arose, and the divergence would have recurred the first time either
side was updated alone. A traversing path is consequently refused by the
contract before any filesystem work; containment remains as the second layer for
paths the contract accepts.

## Unresolved questions

None.
