---
date: 2026-07-22
scope: github-actions-foundation-portability
status: resolved-remote-verified
---

# CI Foundation Portability Investigation

## Executive summary

The first Phase 7 pushes exposed six independent CI defects in the repository
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
6. Two consecutive runs reached the 60-minute Java-job timeout after Maven
   verification had already passed. Run `29923961768` artifacts prove
   Platform API verification completed in 30.818 seconds (135 tests, zero
   failures/errors) and Tool Gateway verification completed in 24.231 seconds
   (24 tests, zero failures/errors). Each ephemeral runner then independently
   started an unauthenticated OWASP Dependency-Check import of 369,410 NVD
   records and reached only 11 percent before cancellation. The workflow now
   keeps Maven verification in bounded 20-minute matrix jobs and performs one
   shared, checksum-pinned OSV 2.4.0 scan over two CycloneDX 2.9.2 SBOMs. A
   tested fail-closed evaluator requires exactly two SBOM sources, non-empty
   package coverage, known numeric severity, and blocks CVSS 7 or greater.

## Verification

- Secret regression suite: 14/14.
- Full local secret scan after the Maven installer changes: 1,073 candidates,
  0 findings.
- Pinned actionlint 1.7.12: PASS.
- Repository layout validator: PASS.
- Portable command surface: 24/24 PASS under Git Bash.
- Maven installer: verified release download and cache-hit paths on Windows
  PowerShell 5.1, Maven 3.9.12 reported by the installed binary. Run
  `29923961768` passed both Ubuntu and Windows clean-bootstrap jobs with that
  installer.
- OSV policy regression suite: 7/7; no-finding, below-threshold, blocking,
  malformed/unknown severity, missing severity groups, missing sources, and
  malformed-output cases covered.
- OSV installer: release SHA-256 and version 2.4.0 verified, including the
  cache-hit path; 2/2 negative tests reject relative roots and tampered cache
  binaries before execution.
- Current Java verification: Platform API 138 tests, zero failures/errors (16
  environment-gated skips); Tool Gateway 24 tests, zero failures/errors/skips.
- Fresh CycloneDX SBOMs: 111 Platform API components and 97 Tool Gateway
  components; both resolve patched Jackson Databind 3.1.5.
- End-to-end OSV scan: two sources, 208 packages, zero vulnerability groups,
  `ScannerExit=0`, `Result=PASS` at CVSS 7.
- Pinned actionlint 1.7.12 and repository layout validation: PASS after the
  workflow/security changes.
- GitHub Actions run `29930327761` completed `success` on revision
  `8a6bd398bd821be900abd2e1bc31882a9533fafa`. Every executable job passed;
  dependency review was correctly skipped on the direct push. The Java security
  job completed in 32 seconds and its artifact records two sources, 208
  packages, zero vulnerability groups, and `Result=PASS`.

## Unresolved questions

- Thirteen Dependabot alerts (9 high, 4 moderate) remain in copied CK tooling
  manifests under `.agents`, `.claude`, and `.codex`; they are outside the
  runtime dependency graph but should be patched or retired in a dedicated
  repository-hygiene change.
