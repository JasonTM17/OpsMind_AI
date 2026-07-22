## Adversarial Review — Phase 2 Landing Audit

Date: 2026-07-20
Scope: current working tree after the independent Phase 2 review fixes.

### Review posture

The review re-checked the six high-severity findings and four medium-severity findings from the independent review against the current files, then exercised the failure-oriented local probes. No production code was changed by this review.

### Re-verified controls

| Area | Current control | Evidence |
|---|---|---|
| Secret coverage | Foundation CI scans before dependency installation; scanner uses platform-appropriate path comparison and bounded Git readers | `test-project-secret-scan.ps1` PASS (13/13) at 2026-07-20T02:21Z; Linux case-distinct canary is part of workflow |
| Actionlint cache | Official archive digest is retained and cache hits are re-extracted and re-hashed; symbolic-link ancestors are rejected | cache-hit run PASS; deliberate executable tamper exits 2 with checksum mismatch |
| Docker storage | Compose job measures `DockerRootDir` and its free space before exporting the attestation flag | workflow/actionlint/layout checks PASS; real Docker runner evidence remains pending |
| Capacity ordering | Foundation, bootstrap, frontend, Python, Java and Compose jobs preflight their own runner before downloads/builds | actionlint and layout contract checks PASS |
| Image reproducibility | Active Compose/base images use immutable manifest digests; review-only invalid MinIO sentinel is excluded intentionally | repository-layout validator PASS |
| POSIX lock lifecycle | Cleanup verifies owner, file removal, and directory removal; failure is reported | shell parse and command-surface suites PASS |
| Crash recovery | Recovery requires an exact repository lock path and explicit confirmation, validates owner PID and unexpected entries | synthetic dead-PID recovery PASS; live/unsafe paths are fail-closed by code inspection |
| History/index safety | Git output is bounded; index snapshots have owner metadata, validated cleanup, and conservative stale handling | scanner regression PASS; no residual snapshot directories after run |

### Residual risks and evidence gaps

1. **Remote CI has not run in this session.** The clean Linux/Windows bootstrap jobs, Docker build/health smoke, and artifact upload are workflow-defined but not locally reproducible without the corresponding hosted runners. Phase 2 must remain in progress until a protected-branch CI run produces those artifacts.
2. **Docker-root policy is measured, not identity-attested.** The CI step proves free space on Docker's root filesystem and the workspace preflight proves its own filesystem. A production deployment still needs an environment-specific approved-volume binding and monitoring record.
3. **Same-user race hardening is bounded, not absolute.** Cache and lock paths reject existing symlink ancestors and use atomic directory creation, but a privileged or same-user attacker able to mutate the filesystem concurrently can still race path checks. This is an operational boundary, not a tenant/runtime trust boundary.
4. **No live identity, database, connector, or provider behavior belongs to Phase 2.** Those are deliberate Phase 3–7 gates and must not be inferred from the health-only scaffold.

### Verdict

The previously reported Phase 2 implementation defects are addressed and the local static/regression evidence is green. The phase is **not eligible for completion** until remote CI evidence covers clean Windows/Linux bootstrap and Compose smoke, and until the evidence is retained under the configured artifact policy.

Unresolved questions: none for the local fixes; remote CI runner availability and the production Docker-volume binding remain phase-owned operational gates.
