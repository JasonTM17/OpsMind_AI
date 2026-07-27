# Deployment Guide

## Current State

OpsMind has no production deployment. Current CI proves the local/CI checkpoint:
identity/data and incident ledgers, AI Runtime, durable Tool Gateway with
synthetic Prometheus, bounded Operator Web, Compose health, and Phase 8B A/B/C
evaluation. Promotion still requires a production-authorized IdP, approved live
provider/legal terms, a named live non-production connector, object lifecycle,
staging, DR, and release evidence. Keycloak 26.7 remains non-production
reference conformance and does not authorize a vendor or rollout.

G0.5 approves managed Kubernetes in `ap-southeast-1` with Singapore residency,
an enterprise OIDC profile, MinIO locally, S3-compatible production storage
behind `production-kms`, and explicit recovery targets. Specific cloud
resources and runtime conformance do not yet exist.

## Environment Model

| Environment | Purpose | Data policy | External effects |
|---|---|---|---|
| Local | Development and deterministic smoke tests | Synthetic or explicitly approved developer data | Simulator/read-only only |
| CI | Build, contract, security, and deterministic verification | Synthetic fixtures; no production credential | None |
| Staging | Provider/IdP/live connector conformance and release rehearsal | Synthetic or approved non-production data | Read-only; isolated write simulator |
| Production | Approved operator workloads | Classified by tenant policy | Exact-action approval only |

Artifacts move forward; mutable environment state does not. The same image digest and contract bundle tested in staging are promoted to production.

## Canonical Delivery Units

The initial release produces independently versioned images for:

- `operator-web`;
- `platform-api`;
- `ai-runtime`;
- `tool-gateway`.

The repository has one root `compose.yaml` for local/CI integration. Production
manifests do not yet exist; they will target the
approved managed-Kubernetes environment and must reference immutable image
digests. Simulator services are never included in production overlays.

Release images are dual-published by digest to Docker Hub and GitHub Container
Registry (GHCR). GHCR packages are linked to this repository so the repository
exposes a Package for each shipped runtime. Both registries must resolve to the
same multi-architecture OCI manifest digest; mutable tags are convenience
pointers, not promotion authority. Docker Hub/GitHub credentials are protected
release secrets or short-lived workload identities and never checked in.

## Configuration and Secrets

- Non-secret configuration is schema-validated at startup.
- Secrets are referenced from the selected secret manager or runtime injection mechanism.
- Images, source, `.env.example`, logs, traces, crash dumps, manifests, fixtures, prompts, and evidence artifacts contain no secret values.
- DeepSeek key presence does not enable egress; policy must also allow the tenant and data class.
- Provider egress is limited to redacted metrics and redacted log summaries,
  with no provider retention, approved region/terms, a USD 1,000 monthly
  budget, and human-only fallback.
- `B-004` remains active: do not run a live DeepSeek smoke or permit production
  egress until provider region, processing terms, retention behavior, and
  redaction controls are verified. The required synthetic smoke also needs an
  externally injected rotated staging key and immutable passing evidence.
- Connector credentials exist only in the Tool Gateway trust zone.
- Platform's Tool Gateway workload client secret and tool-capability PKCS8 key
  are separate secret-manager bindings; neither reuses the AI capability key.
  Enabling the workload adapter requires a same-origin issuer/token endpoint,
  exact `tool.execute` scope, bounded timeouts/body size, and an externally
  injected secret. Incomplete or partial configuration fails before network I/O.
- Tool Gateway persistence uses fixed `opsmind_tool_gateway_migrator` and
  `opsmind_tool_gateway` roles with distinct passwords. They must also differ
  from Platform migration, application, dispatcher, and AI Runtime passwords.
  The one-shot database provisioner upgrades fresh or existing local volumes;
  runtime startup waits for both provision and the Tool Gateway migration.
- The local `prometheus` service is digest pinned, synthetic, loopback exposed,
  and ephemeral. Tool Gateway accepts its explicit internal HTTP origin only
  when `TOOL_GATEWAY_PROMETHEUS_ALLOW_INTERNAL_CLEARTEXT=true`; staging and
  production connectors require an HTTPS origin and server-owned query catalog.
- Key rotation and revocation procedures are exercised before production release.
- Checked-in Platform API, Compose, and environment defaults cap access-token
  lifetime at `PT5M`. Per-request platform-user deprovisioning is immediate;
  upstream IdP disablement denies new login but an existing stateless access
  JWT remains accepted. Its issuance lifetime is 300 seconds, while timestamp
  enforcement includes configured clock skew. The reference configuration has
  a 330-second policy upper bound; checked-in defaults have a 360-second upper
  bound. Neither is a live-measured disable-to-denial result.
- The current decoder is RS256-only. OIDC discovery/JWKS calls have
  500-millisecond connect/read timeouts and a per-exact-target, per-instance
  minimum interval configured by `OIDC_JWKS_REFRESH_MINIMUM_INTERVAL` (`PT1S`
  default; validated 100 milliseconds–1 minute). Replicas do not share this
  bound. A same-target call inside the interval fails closed, so rollout and
  key-rotation procedures must account for temporary token rejection until the
  interval elapses.

## Release Pipeline Gates

1. Repository and dependency integrity checks.
2. Format, lint, type, compile, unit, and architecture-boundary tests.
3. Contract generation and breaking-change detection.
4. Database migration validation against supported upgrade paths.
5. Container build with SBOM, provenance, signature, vulnerability, secret, and license scans.
6. Multi-architecture digest publication to Docker Hub and GHCR, cross-registry
   parity/signature verification, and repository Package metadata verification.
7. Integration tests using PostgreSQL, object storage, identity profile, Tool Gateway, and provider stub.
8. Live non-production conformance for enterprise OIDC, DeepSeek, and the
   approved read-only Prometheus connector against synthetic metrics.
9. Tenant isolation, ACL/revocation, approval binding, idempotency, replay, and failure-injection gates.
10. Held-out evaluation and regression budget.
11. Staging deploy, smoke, load, degraded-mode, rollback, and restore rehearsal.
12. Human release approval with immutable evidence links.
13. Progressive production rollout with automated health halt criteria.

A failed gate cannot be converted to a warning solely to meet a schedule.

## Database and Workflow Changes

- Each service owns its migration sequence.
- Before applying V004 or V005 to an existing database, the platform provisioner must
  create the exact `opsmind_ai_runtime` login with `NOSUPERUSER`, `NOCREATEDB`,
  `NOCREATEROLE`, `NOINHERIT`, `NOREPLICATION`, and `NOBYPASSRLS`, then inject
  its distinct password through the approved secret channel. Fresh local
  databases obtain the same role through the bootstrap script. Migration aborts
  if the role is absent or has broader attributes.
- Before applying Tool Gateway V001, the provisioner creates the exact dedicated
  migration and runtime logins plus the `tool_gateway` schema owned by the
  migration login. Flyway must run as that owner. The runtime login has no
  schema ownership and no read/update/delete/truncate access to audit history.
- Tool Gateway receipt leases use PostgreSQL transaction time. Connector I/O
  occurs between the claim and finalization transactions; successful audit and
  receipt completion commit together. Rollback preserves the reclaimable lease
  and never publishes an unaudited success. The effective expiry is bounded by
  both the configured lease and request deadline plus the fixed completion
  margin; do not remove the margin from claim/reclaim SQL.
- Apply Platform V008 before deploying response-aware investigation writers.
  V008 is the expand step and accepts both exact legacy and strictly validated
  response-bearing `ANALYSIS_ACCEPTED` events. Drain every legacy writer and
  verify mixed-version histories before a later forward-only contract migration
  requires `response`; never deploy that contract step in the same rollout.
- Apply Tool Gateway V002 before deploying provenance-aware gateway writers.
  V002 permits the exact legacy null tuple during the rolling window and
  requires all six observed-provenance fields together from new writers.
  Evaluation-eligible runs require the complete tuple.
- Treat Tool Gateway V003 as a controlled security boundary, not a mixed-writer
  expand window. Drain V002 runtime, apply V003 as
  `opsmind_tool_gateway_migrator`, then start only a scope-aware runtime. V003
  forces tenant/project RLS for receipts and verified audits; an old runtime
  fails closed because it cannot bind transaction-local context.
- Pre-verification denials append only to
  `tool_gateway.unverified_tool_audit_events`. Do not copy request tenant/project
  values into that lane. Historical V001/V002 audit rows retain NULL scope and
  must not be backfilled with guessed attribution.
- Promotion requires the V002-to-V003 disposable upgrade transcript plus the
  PostgreSQL pool-reuse/cross-tenant matrix. On failure, restore a
  V003-compatible runtime or add a forward migration; never disable forced RLS,
  edit V001/V002, or reuse the migration login in the application pool.
- Keep `TOOL_GATEWAY_EXECUTION_LEASE_DURATION` at least as long as the maximum
  enabled manifest timeout plus the runtime's five-second finalization margin.
  Startup rejects unsafe shorter values. Readiness must also fail after schema
  `USAGE` loss, extra/replaced policies, or any drift from the exact
  tenant-and-project `USING` and `WITH CHECK` definition.
- Apply Platform V009 before enabling the incident activity vendor media type.
  V009 creates the two activity ordering indexes concurrently and is configured
  outside a Flyway transaction. The persistence profile also disables Flyway's
  PostgreSQL transactional advisory lock so the concurrent build cannot wait on
  Flyway's own history-lock transaction; Flyway retains a session-level advisory
  lock to serialize migration runners. PR Quality run `30257587569`,
  PostgreSQL job `89950772823`, and artifact `8650178111` prove fresh/upgrade,
  valid-index catalog state, failed-build recovery, bounded query plans,
  legacy-write compatibility, cleanup, and the 3/3 activity HTTP matrix on
  `a975f922`. The CI fixture passed append/vendor <=500 ms, append regression
  <=20%, and index <=256 bytes/row and <=100% table-byte gates. This evidence
  qualifies the plan slice, not production latency, SLOs, or rollout.
  If a concurrent build fails, stop rollout, retain Flyway history and exact
  index-catalog evidence, use the approved recovery path to remove both
  V009-owned indexes concurrently, repair through the approved Flyway seam,
  then retry and capture the resulting artifact. Do not alter applied migration
  bytes or delete Flyway history blindly.
- Schema compatibility covers the rolling window used by deployment.
- Destructive transformations use expand/migrate/contract and verified backup/restore evidence.
- Temporal workflows use version/build routing; golden histories replay before worker promotion.
- Outbox/inbox schemas and consumers remain compatible through the rollout window.

## Progressive Delivery

The default production progression is disabled-by-default feature flag, internal operator cohort, bounded tenant cohort, and then wider availability. Provider, connector, remediation, and model capabilities have independent kill switches.

Health decisions use error rate, saturation, latency, policy denials, invalid model responses, tenant isolation probes, ambiguous effects, queue depth, audit durability, and operator-reported safety. Cost alone cannot override a safety stop. For the AI runtime, use `/health` only for process liveness and `/ready` for dependency readiness: degraded readiness is a `503`, while the liveness response remains a non-sensitive status body.

Capability-probe capacity is an explicit deployment setting. At the default
300–330 second healthy interval, budget at least `12 * replica_count` calls per
provider/model/region per hour, plus rollout headroom, and keep the result below
the provider's own rate limit. Set `AI_PROVIDER_PROBE_MAX_CALLS_PER_HOUR`
accordingly; PostgreSQL makes the limit global and atomic across replicas.
Startup/retry jitter reduces rollout bursts but does not replace this capacity
calculation. Operators can find cancelled/orphaned probes with a bounded query
that selects `started` rows older than the retry interval without a matching
`finished` row; this is an audit-recovery signal, never a readiness override.

## Rollback and Forward Fix

- Roll back application images by immutable digest when schemas remain compatible.
- Disable provider egress or remediation through server-side policy when those boundaries fail.
- Do not replay an external write until reconciliation proves its target state.
- Prefer forward-fix for already-applied data migrations unless a tested rollback path exists.
- Quarantine a model alias rather than deleting lineage evidence.
- Preserve audit and incident evidence during rollback.

## Disaster Recovery

A recovery point must identify a consistent cut across:

- PostgreSQL backup and WAL position;
- Temporal persistence and namespace state;
- object-store version/watermark;
- outbox/inbox positions;
- active model and prompt aliases;
- policy/configuration versions;
- external execution intents and receipts.

Restore into a fenced environment with workflow workers, connectors, and external writes disabled. Reconcile missing artifacts, incomplete outbox delivery, workflow histories, approval expiry, leases, and ambiguous target effects before resuming traffic.

The approved service objectives are a 120-minute RTO and 15-minute RPO. The
artifact-store restore target is four hours. Phase 16 must prove the timed
recovery path and resolve this mismatch before production promotion rather
than treating either approved number as measured evidence.

## Capacity and Admission Control

Local heavy commands must call the Phase 1 storage preflight. Production admission rejects new investigations or writes when critical durable stores cannot safely accept audit, intent, evidence metadata, or workflow state. Storage-full injection is a release gate.

The initial approved envelope is one organization, 25 concurrent
investigations, 500 evidence events per second, and 120 model requests per
minute. Load tests must validate these bounds before release.

## Operational Ownership

Approved accountability assigns platform operations to `platform-team`,
on-call to `site-reliability-team`, security risk to `security-team`, privacy to
`privacy-team`, connectors to `integrations-team`, database to `database-team`,
workflows to `workflow-team`, and provider spend to `product-finance-owner`.
Environment-specific escalation and release approvers must be bound to these
roles before deployment.

## Verification Evidence

Later phases publish signed build provenance, scan reports, contract diffs, migration logs, conformance results, rollout metrics, rollback transcript, restore transcript, and reconciliation report under the configured artifact root and CI artifact store.

The local `artifacts/verification/phase-03/identity-delegation.txt` remains
ignored/reference evidence. Revision-bound PR-quality run `30257587569` passes
Linux/Windows bootstrap, secret scan, actionlint, Operator Web, AI Runtime,
both Java services, dependency security, PostgreSQL trust, Keycloak reference
conformance, and Compose build/health on
`a975f922fcd93c71479b9e15563643a9ea1aa04f`. PostgreSQL artifact `8650178111`
adds the V009/activity evidence above. This is CI fixture/checkpoint evidence,
not production identity, latency, SLO, or deployment proof.

Evidence schema v2 now binds the source/profile manifest and packaged Platform
API JAR digests, verifies cleanup before atomic publication, and has a separate
staleness verifier. The 2026-07-21 local schema-v2 run and verifier passed, but
the artifact remains revision-unborn/dirty reference evidence and cannot be
used for production promotion. A failed execution instead emits a bounded,
sanitized `identity-delegation-failure.txt`; CI uploads it for diagnosis, and
the success verifier always rejects a missing success artifact.

The AI Runtime static checkpoint also passes; revision-bound CI reports 159
Python tests passed with five PostgreSQL-gated skips, Ruff and mypy clean, and the full Maven
suite passes. This does not replace the blocked Phase 5 exit gate or authorize
provider traffic.

The durable Tool Gateway and synthetic Prometheus checkpoint is proven by
successful revision-bound GitHub Actions run `29987371420` for commit
`ace3642f9f94293e2ff10f580c6028eb997ec036`. It includes PostgreSQL role, lease,
rollback, append-only, dependency-security, promtool, live query-range, health,
and cleanup checks. This remains CI non-production evidence, not staging or
production conformance.

Revision-bound cross-service run `30257587543` proves fresh Phase 8B A/B/C with
samples `100/1/1`, all eight metrics `PASS`, and `GitTree=0`. Artifact
`8649696519` also records the Phase 7 OperatorWorkspace/CrossService/
Checkpoint/PhaseExit regression as `PASS`. This does not prove held-out
quality, calibration, human benefit, live DeepSeek, a named live connector,
staging, or production.

## Remaining Deployment Decisions

The production substrate, region, identity profile, object-store class, KMS
boundary, service objectives, and owners are approved in the
[Product/Production Contract](./decisions/product-production-contract.json).
Specific cloud vendor, Docker Hub namespace/repository names, protected registry
publisher identity, secret manager, cluster and bucket topology,
ingress, certificate authority, production IdP vendor, observability backend, availability
topology, and named release approvers remain implementation decisions. The
120-minute service RTO versus four-hour artifact restore target is an explicit
pre-production reconciliation gate.

## Active Release Blockers

`B-004`, `B-005`, `B-006`, `B-007`, `B-008`, `B-011`, `B-012`, `B-013`,
and `B-015` remain active. They respectively block provider/legal
egress, live-connector proof, evidence lifecycle, load/SLO, data lifecycle,
RTO/restore alignment, object-store supply-chain posture, held-out/human
evaluation, and Dependabot disposition. B-016 tenant isolation is resolved by
the immutable CI evidence recorded in [Progress](./progress.md), but no
deployment gate may treat this slice as resolving the remaining blockers.
