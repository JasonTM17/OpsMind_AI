---
title: Phase 8B Process-Supervision Blocking Review
status: passed
reviewed: 2026-07-26
scope: cross-service process supervision and evidence transport
---

# Phase 8B Process-Supervision Blocking Review

## Summary

Two independent reviews reached `PASS` after the supervision fixes. No
blocking finding remains in the Phase 8B process/transport boundary. This
review closes Phase 8B delivery only; parent Phase 8 remains `BLOCK` on its
statistical and human-evidence gates.

## Findings

- Windows assigns the process tree to a Job Object. Path-safety probes report
  `LargeTransport=PASS` and `LateCleanupStatus=PASS`.
- Linux starts an isolated session with `setsid`, adopts descendants as a
  subreaper, and uses pidfds plus fail-closed `/proc` identity metadata.
- Linux probes show no detached-child leak and no leak after controller
  `SIGKILL`.
- The controller authenticates terminal status, keeps an EOF ownership lease,
  and rejects unauthenticated or late status rather than inferring success.
- Standard output and error drain concurrently. The 4 MiB transport probe
  completes in approximately 5-8 seconds without deadlock.

## Closed Blockers

- Child exit status can no longer be lost through a PowerShell pipeline.
- Detached descendants can no longer outlive the supervised process tree in
  the reviewed Windows or Linux paths.
- PID reuse or unverifiable Linux process metadata fails closed.
- Late cleanup status and full-pipe transport no longer create false success or
  indefinite waits.

## Evidence Boundary

PR-quality run `30209210001` and cross-service run `30209209999` pass for
revision `df4620313a3f39721ef1bb521a9cf7ddcac5929c`. The preceding executable
revision `5dfbc00e0c45494dae8d55a24f63b07b301926c5` also passed and its artifact
ZIP hash was independently recomputed; this report does not infer that ZIP hash
for the distinct artifact from run `30209209999`.

## Unresolved Questions

None within the reviewed process-supervision scope.
