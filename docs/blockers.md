# OpsMind AI Blockers

## Policy

A blocker is any missing decision, failed safety gate, unavailable dependency, insufficient capacity, or unresolved critical finding that makes downstream work unsafe or invalid. Blockers are not bypassed by lowering thresholds, substituting fixtures for live proof, or recording an assumption as a decision.

Cleanup, credential rotation, production deployment, external writes, and other materially state-changing actions remain operator-approved.

## Active Blockers

| ID | Scope stopped | Condition | Accountable role | Unblock evidence | State |
|---|---|---|---|---|---|
| B-004 | Live DeepSeek data | Approved redacted allowlist cannot be used until provider region, processing terms, retention behavior, and redaction controls are verified | Privacy + security + product | Provider-egress conformance evidence | Active |
| B-005 | G3 | Synthetic Prometheus checkpoint passes, but no named live non-production connector is authorized/proven | SRE/product owner | Named connector contract, policy, credentials boundary, and live non-production evidence | Active |
| B-006 | Evidence release | Approved S3-compatible/KMS boundary lacks lifecycle and restore implementation evidence | Platform + security owner | Encryption, lifecycle, authorization, and restore drill evidence | Active |
| B-007 | Release criteria | Approved load and service objectives have not been measured against the G3 system | Product + operations owner | Reproducible load/SLO/RTO/RPO measurement report | Active |
| B-008 | Data/RAG/model release | Approved retention, deletion, residency, and opt-in policy lacks enforceable runtime controls and purge receipts | Privacy/data owner | Lifecycle conformance, deletion, and lineage evidence | Active |
| B-011 | Phase 16 production promotion | Approved 120-minute service RTO is shorter than the approved four-hour artifact restore target | Product + operations + platform owners | Timed tiered-restore proof within 120 minutes, or an explicitly approved contract/ADR change | Active |
| B-012 | Production-readiness claim | Approved local MinIO adapter now has an archived upstream repository and no ongoing upstream maintenance commitment | Platform + security owners | Supported replacement/supply-chain decision or bounded local-only exception with pinned provenance and exit plan | Active |
| B-013 | Phase 8 exit | No held-out payloads are registered and no qualified human reviewer records/adjudication exist; calibration and human comparison are unavailable | Product + evaluation owners | Governed held-out case evidence plus qualified paired-review/adjudication report | Active |
| B-015 | Dependency security | GitHub Dependabot reports GHSA-mh99-v99m-4gvg high against `pnpm-lock.yaml`. The advisory range `<= 5.0.7` matches the patched `brace-expansion@1.1.16`, reached only through devDependencies as `eslint` to `@eslint/config-array` to `minimatch@3.1.5`. The risk is dispositioned in `pnpm-workspace.yaml` `auditConfig.ignoreGhsas` with an owner and a 2026-08-09 review, but Dependabot does not read that file, and its alerts are closed by dismissal rather than by configuration, so the alert cannot clear on its own | Security + owning maintainer | Alert dismissed with the recorded rationale, or the ESLint graph no longer resolving `minimatch@3`, after which the patch and the audit ignore are removed together | Active |
| B-016 | Tenant-isolation claim | Tool Gateway stores `tenant_id` and `project_id` in `tool_gateway.execution_receipts` with no row-level security, no policy, and no transaction-local tenant binding, while the security model states without exemption that application authorization and forced RLS are both required | Security + Tool Gateway owner | Either forced RLS with transaction-local tenant binding for the gateway schema, or a recorded decision stating why capability-derived authority alone is sufficient there | Active |

## Capacity Guard

Local heavyweight work is blocked whenever:

- `C:` free space is below 10 GB;
- `D:` free space is below 20 GB;
- a configured storage root is missing, duplicated/nested after canonicalization, symlinked, unwritable, relative, or outside the monitored fixed `D:` volume on Windows;
- evidence/audit/intent storage cannot durably accept required state.

Capacity is dynamic and therefore not represented as a permanently resolved blocker. Each heavyweight command must use a fresh preflight transcript. The recurring monitor is read-only.

## Unblock Procedure

1. Resolve the exact decision or technical condition.
2. Produce the evidence named above.
3. Obtain the accountable approval when the issue is a product/risk choice.
4. Update the authoritative machine-readable contract or runtime policy.
5. Re-run affected plan, security, and dependency validation.
6. Record the result in [Progress](./progress.md).

## Resolved Blockers

| ID | Resolution | Evidence | Resolved |
|---|---|---|---|
| B-001 | Internal single-organization deployment on managed Kubernetes in Singapore approved | [G0.5 contract](./decisions/product-production-contract.json) | 2026-07-19 |
| B-002 | Single organization, logical isolation, one organization and 100 projects approved | [G0.5 contract](./decisions/product-production-contract.json) | 2026-07-19 |
| B-009 | Role-level platform, SRE, security, privacy, integration, database, workflow, and spend owners approved | [Approval record](./decisions/g0-5-approval-2026-07-19.md) | 2026-07-19 |
| B-010 | Six contributors, nine-month target, approved budget, and required skill coverage approved | [Approval record](./decisions/g0-5-approval-2026-07-19.md) | 2026-07-19 |
| B-003 | Local/reference non-production IdP scope only: digest-pinned Keycloak 26.7 passed the browser/resource-server negative matrix; no production vendor or rollout was authorized | `artifacts/verification/phase-03/identity-delegation.txt` plus `scripts/validation/run-phase-03-keycloak-conformance.ps1` | 2026-07-21 |

Storage preflight can pass or fail at different times and is evaluated per
command rather than moved to this section.

B-003 resolution is deliberately narrow. Phase 3/G2 remains in progress:
production IdP selection/conformance, federation, break-glass, state/nonce
assurance, browser/BFF session ownership, and the
other Phase 3 exit criteria are not resolved by the local transcript.

## Unresolved Questions

No blocker remains for starting Phase 2. Local/reference IdP conformance is no
longer open; production identity and later-phase conformance/release gates
remain. Phase 9 infrastructure may proceed with provisional test-only values,
but no-progress/budget thresholds cannot be frozen and Phase 9 cannot exit
until the reviewed human pilot is available. An approved policy is not treated
as proof of its implementation.
