# Tool Gateway tenant isolation

Question raised while auditing migrations: `docs/security-model.md` states under
Tenant and Data Isolation that "application authorization and forced PostgreSQL
RLS are both required", with no service exempted. Does that hold everywhere
tenant data is stored?

## Method

Every migration across both services was parsed for `CREATE TABLE`,
`ENABLE ROW LEVEL SECURITY`, and `FORCE ROW LEVEL SECURITY`. The first parse
under-reported RLS because `V001__identity_tenant_foundation.sql` applies it
through a `DO $$ ... FOREACH ... EXECUTE format()` loop rather than literal
`ALTER TABLE` statements. The nine tables that loop covers are enabled, forced,
and given tenant-isolation policies.

That correction left five tables to examine individually.

## Finding

`tool_gateway.execution_receipts` declares `tenant_id uuid NOT NULL` and
`project_id uuid NOT NULL`. Neither it nor `tool_gateway.tool_audit_events` nor
`tool_gateway.capability_nonce_claims` has row-level security, a policy, or a
tenant-scoped grant. The gateway schema contains no occurrence of
`ROW LEVEL SECURITY` or `CREATE POLICY` at all.

Its isolation model is `REVOKE ALL ... FROM PUBLIC` on the schema plus
table-level grants to a single `opsmind_tool_gateway` role, with tenant
authority verified in application code from the signed capability.

The Platform API binds transaction-local tenant context through
`TenantContextSql`, which is what its policies read. The Tool Gateway has no
equivalent: no `set_config`, no `current_setting`, no tenant binding of any
kind. So the database holds every tenant's execution receipts behind one role,
and the only tenant boundary is the application path.

## Why this is recorded rather than fixed

Adding RLS here is not a patch. The policies would read a transaction-local
tenant the gateway never sets, so every lease, receipt, and audit query would
return zero rows. Making it work means giving the gateway a tenant-binding
mechanism, which is a design decision for its owner and carries real risk to the
lease and reconciliation paths.

The defensible positions are both legitimate:

- forced RLS with transaction-local binding, matching the platform, or
- a recorded decision that capability-derived authority at the boundary is
  sufficient for a single-role isolated service, with the security model amended
  to say so.

What is not defensible is the current state, where the security model asserts a
control the gateway does not have and nothing records the exemption. That is the
same shape as the human-baseline schema found earlier: a control described in
prose with nothing enforcing it.

Recorded as blocker B-016.

## Verified sound in the same pass

- Nine platform tables: enabled, forced, and policied through the V001 loop.
- No table anywhere is enabled without also being forced.
- `ai_runtime.provider_capability_probe_events` has no RLS by design and instead
  uses append-only column grants: `INSERT` plus column-scoped `SELECT`, with
  `UPDATE`, `DELETE`, and `TRUNCATE` revoked. That is a coherent alternative for
  an audit table and is visible in the migration.

## Unresolved questions

- Is `platform_users` intentionally outside the RLS set? It is a global identity
  directory scoped through memberships, which is a reasonable design, but the
  reasoning is not written down next to the table.
