---
date: 2026-07-22
scope: github-actions-foundation-portability
status: resolved-locally-pending-ci
---

# CI Foundation Portability Investigation

## Executive summary

The first Phase 7 push exposed four independent CI defects in the repository
governance surface. None came from the investigation reducer. Each was
reproduced from GitHub Actions logs, fixed at its source, and re-run locally
with the pinned tools.

## Evidence chain

1. Secret scanning failed on Ubuntu at `Get-Item` for `.dockerignore`. The file
   was enumerated with `-Force` but read without it; PowerShell on Linux hides
   dotfiles unless `-Force` is supplied. The scanner now uses `-Force` and the
   full Linux gate passed.
2. Portable command surface initially failed every direct CLI call because
   `scripts/dev/opsmind.sh` was stored as mode `100644`. Restoring mode `100755`
   changed the CI signature to three setup-only failures.
3. Setup then failed because both storage helpers invoked directly by the CLI
   were also `100644`. Restoring executable mode for
   `scripts/storage/check-capacity.sh` and
   `scripts/storage/assert-storage-roots.sh` produced 24/24 command-surface
   passes on Linux.
4. Actionlint reported ShellCheck `SC2034` for an unused PostgreSQL retry
   counter. The counter is now included in a retry diagnostic.
5. Clean bootstrap artifacts from both Ubuntu and Windows showed runner Maven
   `3.9.16` while `.maven-version` pins `3.9.12`. The project keeps the pin and
   installs the official Apache distribution with platform-specific SHA-512
   verification through `.github/actions/install-pinned-maven` before each
   Maven job. The first cross-runner attempt exposed a PowerShell naming
   collision with automatic read-only `$IsWindows`; the installer now uses the
   distinct `$hostIsWindows` name.

## Verification

- Secret regression suite: 14/14.
- Full local secret scan after the Maven installer changes: 1,073 candidates,
  0 findings.
- Pinned actionlint 1.7.12: PASS.
- Repository layout validator: PASS.
- Portable command surface: 24/24 PASS under Git Bash.
- Maven installer: verified release download and cache-hit paths on Windows
  PowerShell 5.1, Maven 3.9.12 reported by the installed binary. The latest
  GitHub run reached the installer on both OSes and failed only on the
  now-fixed `$IsWindows` collision; a fresh run is required for release proof.

## Unresolved questions

- The post-installer GitHub Actions run must still complete the full matrix;
  the latest run is the release evidence for the Maven provisioning change.
- Thirteen Dependabot alerts (9 high, 4 moderate) remain in copied CK tooling
  manifests under `.agents`, `.claude`, and `.codex`; they are outside the
  runtime dependency graph but should be patched or retired in a dedicated
  repository-hygiene change.
