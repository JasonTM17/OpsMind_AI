---
title: Phase 1 secret-scan history failure and repair
date: 2026-07-27 06:22
severity: High
component: CI secret scan, governance fixtures, git history
status: Resolved
---

# Phase 1 Secret-Scan History Failure and Repair

## Context

Phase 1 CI had to prove the secret scanner could ignore only the exact public canaries we intended, while still blocking real credential-shaped material. The gate was supposed to fail closed on anything broader than the approved examples.

## What Happened

CI run `30224410596` failed at the secret scanner before Java even started. Commit `c064523` then assembled the working-tree fixtures at runtime. Its governance job passed, but the default shallow checkout saw only the current commit, so that result did not prove a full-history scan. A full local clone still found the public credential-shaped URI fixtures and commit prose in immutable history.

The first repair put exceptions inside the shared database-URL regex. Review proved that was unsafe: case variants and changed hosts or paths could bypass the rule. The replacement keeps the rule strict for working tree, index, and artifacts. It removes only five exact, case-sensitive public fixture strings from history text, and CI now requests full history before scanning.

## Reflection

This was annoying in the most predictable way: we fixed the file and then nearly weakened the detector to make history green. The P1 review caught the real failure mode. A scanner that passes by globally exempting credential shapes is worse than a red build.

## Decisions

- Keep the history immutable; no force-push, no rewrite, no pretending the old evidence never existed.
- Scrub only the exact approved strings from history input; keep all live-content rules unchanged.
- Test both sides: the five strings block while live, then pass only after removal into history.
- Test eight near misses in isolated histories so no surviving case can hide another regression.
- Fetch full history in the governance job so `HistoryCommits` is meaningful.
- Treat `c064523` as the working-tree repair, not as a history fix.

## Next

- Preserve final integrated local proof: full-history scan `Findings=0` across `115` commits and `6,105,753` bytes; scanner self-test `26/26` with eight isolated near-miss histories; egress `40/40`; repository layout, Ruff, actionlint, and diff checks passing.
- Exact descendant CI is now complete: PR quality `30228639783` and cross-service evaluation `30228639754` both succeeded on `1c399fe`.
- The governance job checked out `fetch-depth: 0` and recorded `HistoryCommits=116`, `HistoryBytesScanned=6120086`, `Findings=0`, `Result=PASS`.
- Keep recording `HistoryCommits`; one shallow commit is not sufficient evidence for this gate.
- Do not broaden the exemption unless a later review proves a new immutable canary is required.

## Unresolved Questions

None.
