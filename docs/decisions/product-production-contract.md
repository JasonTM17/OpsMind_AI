# Product and Production Contract — G0.5

## Purpose

This contract prevents infrastructure, identity, privacy, reliability, and delivery assumptions from being buried in implementation. Phase 2 cannot begin until all required decisions are approved by accountable roles and the machine-readable validator passes without `-AllowPending`.

Authoritative machine-readable record: [product-production-contract.json](./product-production-contract.json)  
Validation schema: [product-production-contract.schema.json](./product-production-contract.schema.json)

Decision and review records:

- [Concrete G0.5 recommended baseline](./g0-5-recommended-baseline.md)
- [Machine-readable pending proposal](./product-production-contract-recommended.json)
- [Full baseline approval record](./g0-5-approval-2026-07-19.md)

The proposal is deliberately separate from the authoritative record. Values may be copied into the authoritative contract only after an explicit accountable user/product/risk decision; generating or validating the proposal does not approve it.

## Current State

`approved` — the project owner approved all twelve typed baseline decisions at
`2026-07-19T16:03:27.175Z`. The strict validator returned `PASS` with exit code
`0`; Phase 2 is no longer blocked by G0.5.

## Recommended Simplest Starting Profile

The architecture team recommends an internal, single-organization, single-region deployment with logical tenant/project isolation. It minimizes delivery and compliance uncertainty while preserving the isolation contracts needed for later managed-service expansion.

Recommended supporting choices:

- OIDC with a production-grade identity provider; Keycloak is suitable for local/reference conformance when no enterprise IdP is mandated.
- Prometheus as the first live non-production read connector using synthetic metrics.
- MinIO locally behind an S3-compatible evidence port; production S3-compatible backend and KMS selected by target environment.
- DeepSeek egress only for explicitly allowlisted, minimized, redacted data classes.
- Read-only investigation first; production writes remain unavailable until exact-action Phase 11 passes.

These choices are now approved decisions. The separate proposal remains a
historical review aid and is not the authoritative contract.

## Required Decisions

| Key | Decision | Why blocking | Primary approver |
|---|---|---|---|
| `deploymentArchetype` | Internal, managed SaaS, or customer-hosted | Changes tenancy, operations, compliance, and delivery | Product/sponsor |
| `targetEnvironment` | Cloud/on-prem substrate, region, residency | Determines manifests, KMS, storage, networking, DR | Platform + security |
| `tenantModel` | Organization/tenant/project hierarchy and scale | Controls identity, RLS, quotas, billing boundaries | Product + security |
| `identityProfile` | IdP, federation, sessions, MFA/step-up, break-glass | Defines the authentication trust boundary | Security + identity |
| `deepseekEgressPolicy` | Allowed classes, geography, retention, fallback, spend | Controls whether incident data may leave the boundary | Privacy + security + product |
| `firstLiveIntegration` | First read connector and non-production endpoint | G3 requires real integration evidence | SRE/product |
| `evidenceArtifactStore` | Production object backend and key boundary | Evidence durability, lifecycle, and restore depend on it | Platform + security |
| `loadEnvelope` | Tenants, incidents, evidence rates, concurrency, quotas | Drives capacity and architecture validation | Product + operations |
| `serviceObjectives` | Availability, latency, RTO, RPO | Defines release and DR acceptance | Product + operations |
| `dataLifecycle` | Retention, deletion, residency, export, training eligibility | Controls RAG/dataset/model design | Privacy/data owner |
| `operationalOwnership` | Platform, on-call, security/risk, privacy, spend owners | Unowned controls are not operable | Sponsor/product |
| `deliveryCapacity` | Team, skills, budget, and schedule envelope | Makes roadmap commitment credible | Sponsor/product |

## Approval Requirements

Every machine-readable decision must contain:

- state `approved`;
- a concrete value;
- accountable owner identity;
- approval timestamp;
- evidence reference to the recorded user/product/risk decision;
- consequences or constraints encoded in the value or companion ADR.

Global contract status becomes `approved` only when every required decision is approved and `approvedBy`/`approvedAt` identify the final accountable approval.

Each `value` is a typed object, not free-form prose. `contractVersion` is canonical SemVer in both schema and validator. The validator rejects non-UTF-8 or non-standard JSON, missing/unknown/case-shifted/duplicate properties, placeholders, invalid enums, out-of-range/non-finite numbers, non-RFC-3339 timestamps, and non-URI evidence references. Dynamic transcript fields escape control characters, preventing a rejected contract from injecting a false result record.

Governance number lexemes use bounded canonical decimal form: optional minus, no leading plus or non-canonical leading zero, at most 15 integer digits and 6 fractional digits, and no exponent notation. Integer fields may use an integral decimal representation, matching JSON Schema's mathematical integer semantics; booleans never coerce to `0`/`1`.

| Decision | Required `value` fields and enforced bounds |
|---|---|
| `deploymentArchetype` | `mode`: `internal-single-organization`, `managed-saas`, or `customer-hosted`; `rationale`: at least 12 characters |
| `targetEnvironment` | `substrate`, `region`, `residency` (concrete strings); `environmentClass`: exactly `production` |
| `tenantModel` | `organizationMode`: `single`/`multi`; `isolationMode`: `logical`/`physical`/`hybrid`; positive integer organization/project maxima |
| `identityProfile` | `protocol`: `oidc`; concrete provider; `authorizationFlow`: `authorization-code-pkce`; MFA boolean; named break-glass owner |
| `deepseekEgressPolicy` | disabled or allowlisted/redacted mode; unique data classes; residency policy; retention boolean; redaction fixed `true`; non-negative monthly USD budget; fail-closed/local/human fallback |
| `firstLiveIntegration` | supported connector enum; endpoint class; `readOnly` fixed `true`; named owner |
| `evidenceArtifactStore` | local and production backends, KMS boundary, retention owner, positive restore target hours |
| `loadEnvelope` | positive organizations, concurrent investigations, evidence events/second, and model requests/minute |
| `serviceObjectives` | availability in `(0,100]`; positive p95 API milliseconds; non-negative RTO/RPO minutes |
| `dataLifecycle` | positive incident/evidence/audit retention days and deletion SLA hours; training fixed `opt-in-only`; concrete residency |
| `operationalOwnership` | named platform, on-call, security/risk, privacy, connector, database, workflow, and provider-spend owners |
| `deliveryCapacity` | positive contributors; 1–24 target months; budget approval fixed `true`; at least four unique enumerated skill areas |

For an approved row, `approvedBy` must identify an accountable person or role, `approvedAt` must be an RFC 3339 timestamp, and `evidence` must be an absolute scheme URI such as an immutable decision-record reference. A repository recommendation is not approval evidence.

## Validation

Structural validation while decisions are pending:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\governance\validate-product-production-contract.ps1 -AllowPending
```

`-AllowPending` performs authoring-time structural validation only and deliberately returns exit code `10` while approvals are incomplete. It is never a successful release/deployment gate. A production-ready contract must run without this switch and return `0`.

Phase 2 gate:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\governance\validate-product-production-contract.ps1
```

The second command must exit with code `0` and `Result=PASS`. Pending,
rejected, malformed, or approval-incomplete contracts continue to fail closed;
changing the exit code by suppressing decisions is prohibited.

## Change Control

- Approved changes update the JSON record and affected ADR/docs in the same change.
- Schema changes must update the validator's line-ending-normalized fingerprint, matching manual constraints, and mutation cases together; a drifted schema fails closed.
- A material post-implementation reversal requires a new ADR and impact review.
- A recommendation cannot be copied into `value` without an approver/evidence record.
- Secrets and provider credentials never appear in this contract.
- Production target changes trigger plan revalidation, hostile review, estimate update, and affected conformance tests.

## Verification Evidence

Validator output is written to
`artifacts/verification/phase-01/product-production-contract.txt`. The
2026-07-19 approval run records `ContractStatus=approved`, all twelve decision
keys as `approved`, and `Result=PASS`.

## Unresolved Questions

No G0.5 decision is unresolved. Provider terms, production-authorized identity conformance,
connector behavior, measured load/SLO results, lifecycle enforcement, and
restore evidence remain implementation gates in their owning phases.
