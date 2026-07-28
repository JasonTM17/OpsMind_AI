# Linked-worktree secret-scan investigation

## Root cause

The scanner assumed both generated artifact evidence and its temporary
`checkout-index` snapshot lived below the current checkout. A linked worktree has
a `.git` file and normally has no local `artifacts/` directory, so the scanner
reported `artifact-root-unavailable` and could not create its index snapshot.
The failures were infrastructure findings, not detected credentials.

## Fix

- Resolve the repository storage root from Git's absolute common directory.
- Keep scanning the linked worktree as the working-tree boundary.
- Resolve the default artifact tree and temporary index snapshot below the
  common repository storage root.
- Preserve explicit external artifact-root validation and all reparse, bounded
  file, index, history, cleanup, and secret-pattern checks.
- Add linked-worktree regression cases for the clean baseline, shared artifacts,
  staged-index-only content, and history-only content.

## Evidence

- `scripts/governance/test-project-secret-scan.ps1`: `PASS (30/30)`.
- Project scan: 1,965 candidate files, 1,961 text files, 4 reviewed binary
  files, 146 history commits, 7,189,197 history bytes, zero findings.
- Project scan result: `PASS`.

## Unresolved questions

None for the linked-worktree failure. External credential provisioning remains
outside this fix.
