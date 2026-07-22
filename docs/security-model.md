# OpsMind AI Security Model

## Security Objective

OpsMind assists privileged operators without becoming an uncontrolled privileged principal. The platform must preserve confidentiality, integrity, availability, tenant isolation, decision traceability, and human authority over external effects even when inputs, models, dependencies, or operators behave unexpectedly.

## Protected Assets

- Tenant identities, memberships, policies, incidents, and evidence.
- Credentials, signing keys, capability keys, sessions, and provider secrets.
- Infrastructure topology, logs, source snippets, security findings, and runbooks.
- Approvals, execution intents, target versions, receipts, and audit history.
- Retrieval indexes, datasets, model artifacts, evaluations, and lineage.
- Availability, storage capacity, budgets, and recovery material.

## Adversaries and Failure Sources

- Cross-tenant user or compromised session.
- Over-privileged service or leaked credential.
- Prompt injection embedded in evidence, logs, tickets, or runbooks.
- Malicious or compromised connector/target system.
- Model hallucination, schema drift, provider outage, or data retention outside policy.
- Insider misuse or approval coercion.
- Supply-chain compromise in dependencies, images, models, or CI.
- Replay, duplicate delivery, race condition, timeout, partial failure, or storage exhaustion.

## Security Principles

1. Deny by default.
2. Authenticate workload and user separately.
3. Derive scope from verified claims and authoritative membership.
4. Minimize data before every trust-boundary crossing.
5. Treat model output and connector content as untrusted input.
6. Separate recommendation, approval, and execution authority.
7. Bind approval to an immutable exact action.
8. Make important state and effects replay-safe and auditable.
9. Preserve safe degraded modes.
10. Fail closed when audit, intent, policy, authorization, or storage durability is unavailable.

## Identity and Session Security

- The approved production browser policy is enterprise OIDC Authorization Code
  with PKCE, mandatory MFA, and a `security-operations`-owned break-glass path;
  approval is not implementation proof.
- Keycloak 26.7 is a local/reference non-production conformance target only.
  Production vendor selection, federation, break-glass, and session ownership
  still require implementation and conformance evidence.
- The resource server accepts only RS256 and validates issuer/JWKS signature,
  audience, `iat`/`exp`, a configured maximum lifetime of `PT5M`, clock skew,
  bounded subject, and mandatory MFA `amr`.
- OIDC discovery/JWKS calls use 500-millisecond connect/read timeouts and a
  per-exact-target, per-Platform-API-instance request bound. The default
  interval is `PT1S` and the validated range is 100 milliseconds–1 minute.
  This is not a cluster-wide bound: a same-target request inside the interval
  fails closed, and a genuine signing-key rotation may be rejected until the
  interval elapses.
- The local reference run proves PKCE S256, MFA/TOTP negative paths,
  RP-initiated logout, refresh-after-logout denial, JWKS rotation refresh,
  old refresh-token reuse denial after rotation, an independent refresh family
  for the revocation positive control, refresh-token revocation, and
  disabled-user new-login denial. It does not
  prove state/nonce assurance, production browser/BFF session ownership,
  federation, break-glass, or general bearer-token replay prevention.
- The live schema-v2 conformance contract proves a successful refresh from an
  independent session immediately before refresh-token revocation
  (`RefreshTokenIndependentSessions=PASS` and
  `RefreshTokenPreRevocationControl=PASS`). The independent family avoids
  confusing replay-driven family invalidation with revocation behavior.
- With persistence enabled, every authenticated API request rechecks the
  issuer/subject mapping. Unknown and deprovisioned users are denied; identity
  authority failure fails closed.
- Platform-user deprovisioning therefore takes effect on the next request.
  Upstream IdP disablement blocks new login but does not immediately revoke an
  already issued stateless access JWT. Issuance lifetime is 300 seconds;
  timestamp enforcement also includes configured skew (`PT30S` in the harness,
  `PT60S` in checked-in defaults), yielding policy upper bounds of 330 and 360
  seconds respectively. The reference run proves immediate post-disable
  acceptance, not the live disable-to-denial horizon.
- Tenant/project membership is loaded from platform authority, not trusted from arbitrary token claims.
- Sensitive operations can require step-up authentication and a fresh session.
- Break-glass access is time-bounded, separately approved, alerted, and reviewed.
- Service-to-service calls use workload identity plus narrow audience and authorization.

## Delegated Capabilities

The delegated-capability contract requires short-lived signed capabilities for Tool Gateway operations. Claims bind audience, issuer, actor/workload, tenant, project, incident, connector, operation class, resource constraints, query/time bounds, policy version, budget, nonce, and expiry. Phase 3 currently has contract/validator scaffolding; the Keycloak evidence is not delegated-capability proof.

The Tool Gateway must derive authority only from verified claims, apply its own connector policy, and record denial/usage. Capabilities are not general bearer credentials and cannot be widened by request parameters.

## DeepSeek and Model Egress

- Provider credentials live only in the AI Runtime secret boundary.
- Credential presence never enables egress by itself.
- The approved egress mode is `allowlisted-redacted`: only redacted metrics and
  redacted log summaries may cross the provider boundary.
- Redaction is mandatory, provider retention is prohibited, approved provider
  region and processing terms are required, and the monthly provider budget is
  USD 1,000.
- Evidence is minimized and redacted before provider transmission.
- Prompt injection content cannot grant tools, change policy, or suppress audit.
- Response content, JSON, citations, and tool arguments are validated by application code.
- Hidden chain-of-thought is not stored as product evidence.
- Provider outage, denial, or budget exhaustion falls back to human-only
  evidence workflows, not an unapproved provider.

## Tenant and Data Isolation

- Application authorization and forced PostgreSQL RLS are both required.
- Transaction-local security context prevents connection-pool carryover.
- Separate database roles prevent normal services from bypassing policies.
- The web role may append outbox events but cannot lease or acknowledge them.
  The dispatcher uses a separate non-bypass login, sees zero rows without a
  transaction-local tenant binding, and cannot read identity/service-account
  authority directly. A non-login resolver exposes only bounded tenant
  scheduling and workload binding from active audience/scope metadata.
- Retrieval filters authoritative ACL before vector or lexical ranking.
- Object identifiers are opaque references, not authorization grants.
- Exports, jobs, caches, telemetry, errors, and evaluation artifacts are tenant-aware.
- Cross-tenant negative tests run at every relevant boundary.

Checkpoint 4A now applies these rules to incident create, detail, transition,
and timeline routes. Scope is checked first, but verified issuer/subject plus
active user, organization, project, membership, and role rows remain the
authority. A SECURITY DEFINER resolver owned by a NOLOGIN/non-bypass role locks
that complete tuple inside the incident transaction; the runtime can execute
the resolver but cannot update authority tables. Live PostgreSQL tests prove a
concurrent membership revocation blocks until the authorized transaction ends
and denies the next access. Invisible resource responses do not reflect scoped
identifiers in either detail or `instance`.

The AI runtime uses a dedicated non-owner `NOBYPASSRLS` login and transaction-
local tenant scope. This RLS boundary fails closed when application queries omit
tenant predicates or a pooled connection is reused without scope. It does not
claim to resist arbitrary SQL executed with a compromised runtime credential,
because that credential can set its own custom tenant GUC. Network isolation,
parameterized SQL, credential rotation, least privilege, and service compromise
detection remain required controls. Delegated capability nonces are attempt
tokens: consumption commits before reservation, so a crash may burn one attempt
but can never make bearer authority reusable; the hashed nonce row preserves
that fact without storing the capability.

## Evidence and Retrieval Security

- Evidence is content-addressed and encrypted through an object-storage port.
  The approved adapters are MinIO for local use and S3-compatible production
  storage behind the `production-kms` boundary.
- Metadata includes source, classification, digest, authorization epoch, retention, and incident relation.
- Untrusted evidence is scanned and rendered safely.
- Retrieval results preserve exact source/version provenance.
- Revocation blocks reads immediately; purge removes derived content and produces receipts.
- RAG context never contains unauthorized neighboring candidates.

## Exact-Action Security

A write requires:

1. an authorized dry-run against current target state;
2. normalized action parameters and deterministic digest;
3. policy approval for that actor, target, connector, and effect class;
4. human approval bound to preview, target version, policy version, expiry, and nonce;
5. durable intent and execution lease before dispatch;
6. target-side compare-and-set and idempotency where supported;
7. reconciliation of ambiguous outcomes before retry;
8. guarded compensation within separately authorized scope.

The model cannot approve, sign, or execute its own recommendation.

## Secrets and Supply Chain

- Secrets are injected at runtime from approved stores and rotated.
- Source, history, artifacts, images, logs, prompts, tests, and client bundles are scanned.
- Dependencies use lockfiles, provenance, vulnerability, and license gates.
- Images run non-root where supported, use minimal bases, and are signed with SBOM/provenance.
- CI credentials are short-lived and environment-scoped.
- Model and dataset artifacts are digested, scanned, and lineage-bound.

The Phase 1 built-in gate scans product working-tree and ignored files, an exact Git-index snapshot, the configured artifact tree (inside or outside the repository), bounded Git patch content, and historical path names. It detects provider tokens, environment/config/YAML credential assignments, credential-bearing database URLs, bearer/JWT values, private-key headers, tracked or historical sensitive filenames, and deleted binary history. Invalid UTF-8, unsupported UTF-32, unreadable, oversized, reparse-routed, current binary, or historical binary candidates fail closed. Candidate paths are deduplicated before scanning. Agent/tooling state and dependency caches are excluded from this product-source gate. A Git history over the built-in 20 MB bound blocks; a maintained external scanner must replace this bootstrap heuristic before runtime delivery, and the bound is never a release bypass.

## Audit and Non-Repudiation

Audit entries include actor/workload, tenant, incident, action, policy, capability/approval digest, target, request/result digest, correlation, time, and outcome. Append-only ordering and integrity controls make tampering detectable. Raw secrets and unnecessary evidence bodies are excluded.

Audit availability is a prerequisite for external writes. Audit export access is separately authorized and retention-controlled.

For incident events, V003 requires the audit action, actor, resource,
correlation, timestamp, and payload to exactly match the authoritative timeline
row. PostgreSQL serializes each tenant chain, assigns the tenant sequence and
previous digest, and computes the stored SHA-256 digest. The runtime has no
insert authority over chain fields and neither runtime nor migration paths may
update, delete, or truncate history. A real outbox conflict test proves that an
append failure rolls back the incident, timeline, audit, and idempotency effects
as one transaction.

## Availability and Abuse Controls

- Per-tenant quotas and budgets limit provider, retrieval, connector, workflow, storage, and export use.
- The initial approved envelope is one organization, 25 concurrent
  investigations, 500 evidence events per second, and 120 model requests per
  minute. Runtime enforcement and measured capacity remain later gates.
- Rate limits and concurrency controls resist accidental or malicious amplification.
- Circuit breakers and bulkheads isolate external dependencies.
- Backpressure prevents queues from consuming unbounded storage.
- ENOSPC, database read-only, object-store outage, provider outage, and connector timeout tests verify fail-closed behavior.

## Security Verification

The release program includes threat modeling, SAST, dependency, secret, container, IaC, API, authorization, tenant-isolation, prompt-injection, capability-replay, approval-substitution, RAG poisoning, lineage deletion, workflow replay, and DR reconciliation tests. Any evidenced critical issue blocks release.

## Incident Response

Security runbooks must support credential rotation, provider-egress disable, connector disable, remediation kill switch, tenant containment, model quarantine, artifact hold, audit export, deletion pause, and recovery reconciliation. Runbooks are exercised in staging and owned by named roles.

The approved role-level accountability assigns platform operations to
`platform-team`, on-call to `site-reliability-team`, security risk to
`security-team`, privacy to `privacy-team`, connectors to `integrations-team`,
database to `database-team`, workflow to `workflow-team`, and provider spend to
`product-finance-owner`.

## Verification Evidence

Phase 1 establishes boundaries and secret-free defaults. The 2026-07-21 local
Keycloak transcript is a passing reference result, records no persisted runtime
secrets, and is explicitly not production or immutable release evidence because
the repository is unborn/dirty and the artifact is ignored. Later phases must
map every threat to controls and executable evidence under `artifacts/security`,
with release decisions linked to immutable CI artifacts.

## Remaining Security Gates

G0.5 is approved in the
[authoritative contract](./decisions/product-production-contract.json). Vendor
conformance, session/federation details, provider processing terms, detailed
KMS topology, compliance mapping, and executable control evidence remain
required before production promotion. Approval of policy is not proof that any
runtime control exists.
