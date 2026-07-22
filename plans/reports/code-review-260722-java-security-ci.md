---
date: 2026-07-22
scope: pending-java-security-ci-and-phase-07-docs
status: pass-pending-remote-ci
---

# Java Security CI Review

## Scope

- CI workflow, pinned OSV installer, fail-closed policy evaluator/tests.
- Java dependency override and repository layout contract.
- Phase 7 checkpoint/architecture/progress documentation.
- Review mode: sequential controller review because the CK subagent service
  quota was unavailable; full spec, base/API/web checklist, edge-case, and
  adversarial protocols were still applied.

## Spec compliance

PASS. The change preserves the existing CVSS 7 Java failure threshold, removes
duplicate unbounded NVD imports, emits two reviewable CycloneDX SBOMs and raw
OSV evidence, keeps Java verification independent, pins every added executable
or Maven goal version, and does not claim Phase 7 completion.

## Edge-case and quality review

- Scanner exits `0` and `1` are the only accepted protocol outcomes; other
  exits block before policy evaluation.
- Evaluator requires exactly two unique SBOM results, at least one package per
  result, valid package identity, and known CVSS values in `[0, 10]`.
- Installer bounds redirects/download time, payload size, child-process time,
  and output size; verifies platform-specific SHA-256 before execution; rejects
  symlink ancestors and corrupt cache entries.
- Java jobs and the security job have independent 20-minute ceilings. The
  security job scans both SBOMs once and always uploads available diagnostics.
- Spring Boot compatibility risk is bounded to a Jackson patch release and both
  Java suites pass after the override.
- Documentation distinguishes durable data from durable workflow and records
  the cancelled run rather than presenting it as fully green.

## Adversarial findings and verdicts

1. **Accepted and fixed — policy bypass through JavaScript coercion.** `null`,
   `false`, or an empty string could coerce to severity `0`. Parsing now accepts
   only non-empty string/number inputs, validates finiteness/range, and rejects
   vulnerability details without severity groups. Regression coverage includes
   all malformed variants.
2. **Accepted and fixed — dependency patch rollback not guarded.** Both POMs
   resolved Jackson 3.1.5, but the repository layout contract only locked
   Log4j/Tomcat floors. It now requires Jackson BOM 3.1.5 in both services.
3. **Rejected — executable replacement through cache symlink.** The installer
   checks the target directory's complete ancestor chain before and after
   creation, and lstat-checks the executable as a regular non-symlink file
   before hashing/execution. CI uses an isolated ephemeral workspace.
4. **Rejected — medium advisory silently ignored.** Below-threshold findings are
   emitted into evidence with package, severity, identifiers, and source. Only
   the established CVSS 7 threshold controls blocking; PR dependency review
   separately fails at moderate severity.

## Verification

- Security tooling tests: 9/9 pass (2 installer, 7 evaluator).
- Platform API: 138 tests, zero failures/errors, 16 environment-gated skips.
- Tool Gateway: 24 tests, zero failures/errors/skips.
- SBOM/OSV: 111 + 97 components; 208 packages; zero vulnerability groups;
  Jackson 3.1.5; policy PASS.
- Actionlint 1.7.12, layout validator, Phase 7 validator, diff check: PASS.
- Secret scan: 1,098 files and 16 commits, zero findings.

## Unresolved questions

- Fresh revision-bound GitHub Actions proof is required before changing status
  from `pass-pending-remote-ci`.
- Thirteen Dependabot alerts in copied CK tooling remain separate hygiene work.
