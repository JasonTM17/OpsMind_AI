# OpsMind Platform API

Spring modular monolith that owns tenant authority, the incident control plane,
durable investigation state, and the evidence ledger. It is the only service
that talks to the primary PostgreSQL database, and the only one that decides
what an actor may see or do.

## Local checks

From the repository root:

```powershell
mvn -f services/platform-api/pom.xml test
node scripts/validation/validate-phase-04-incident-contracts.mjs
node scripts/validation/validate-phase-07-investigation-slice.mjs
```

PostgreSQL-backed contract gates are separate because they need a live database:

```powershell
powershell.exe -NoProfile -File .\scripts\validation\run-phase-03-local-postgres-contract.ps1
powershell.exe -NoProfile -File .\scripts\validation\run-phase-04-local-postgres-contract.ps1
```

Heavy local work fails closed when the storage floors in
[`docs/blockers.md`](../../docs/blockers.md) are not met. That guard is a safety
control, not an obstacle to work around.

## API surface

Operations are defined in
[`packages/contracts/openapi/opsmind-v1.yaml`](../../packages/contracts/openapi/opsmind-v1.yaml),
which is authoritative. The service exposes identity (`/me`), the project and
incident control plane, the incident timeline, accepted analysis, and
investigation runs, all nested under
`/organizations/{organizationId}/projects/{projectId}`.

Nesting is not cosmetic. Tenant and project are path-bound so no request can
address an incident without naming the scope it belongs to, and the scope is
checked against verified claims rather than against anything the caller supplied.

## Persistence

Flyway owns the schema under `src/main/resources/db/migration/`, currently V001
through V008. Migrations are forward-only in shared environments and run under a
dedicated owner identity; the application pool uses a non-owner role that cannot
bypass row security.

Tenant-scoped tables carry forced row-level security and read a transaction-local
tenant context applied by `TenantContextSql`. A query that omits that context
sees zero rows rather than another tenant's.

## Boundaries

- Actor and tenant come from verified platform claims. No request header can
  assert either, and there is no code path that reads one.
- The AI Runtime and Tool Gateway are reached over HTTP with their own
  credentials. This service never hands a connector credential to a model.
- Event payloads are byte-digest bound before an outbox insert and again after a
  lease is claimed, because `jsonb` normalization is not byte preserving.

Never place provider keys, bearer tokens, database passwords, or personal data
in this directory, its fixtures, logs, or documentation. Secrets are injected
through the deployment secret manager only.
