#!/usr/bin/env bash

set -Eeuo pipefail

: "${OPSMIND_TOOL_GATEWAY_UPGRADE_DATABASE:?upgrade database name is required}"
: "${OPSMIND_EPHEMERAL_DB:?OPSMIND_EPHEMERAL_DB must be true}"
: "${POSTGRES_USER:?PostgreSQL administrator user is required}"
: "${POSTGRES_PASSWORD:?PostgreSQL administrator password is required}"
: "${POSTGRES_TOOL_GATEWAY_MIGRATOR_USER:?Tool Gateway migrator user is required}"
: "${POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD:?Tool Gateway migrator password is required}"
: "${POSTGRES_TOOL_GATEWAY_USER:?Tool Gateway runtime user is required}"
: "${POSTGRES_TOOL_GATEWAY_PASSWORD:?Tool Gateway runtime password is required}"

if [[ "$OPSMIND_EPHEMERAL_DB" != "true" ]]; then
    printf '%s\n' 'Upgrade proof requires an explicitly ephemeral database.' >&2
    exit 2
fi
if [[ ! "$OPSMIND_TOOL_GATEWAY_UPGRADE_DATABASE" =~ ^opsmind_tool_gateway_rls_[a-z0-9_]+$ ]]; then
    printf '%s\n' 'Upgrade database name is outside the managed prefix.' >&2
    exit 2
fi

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
gateway_jar="$repository_root/services/tool-gateway/target/tool-gateway.jar"
if [[ ! -f "$gateway_jar" ]]; then
    printf 'Required Tool Gateway jar is missing: %s\n' "$gateway_jar" >&2
    exit 2
fi

database_name="$OPSMIND_TOOL_GATEWAY_UPGRADE_DATABASE"
host="${PGHOST:-127.0.0.1}"
port="${PGPORT:-5432}"
database_url="jdbc:postgresql://${host}:${port}/${database_name}"
legacy_execution_id="11111111-1111-4111-8111-111111111119"
legacy_audit_id="22222222-2222-4222-8222-222222222229"
tenant_id="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
project_id="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
foreign_project_id="cccccccc-cccc-4ccc-8ccc-cccccccccccc"
database_created=false

cleanup() {
    local status=$?
    if [[ "$database_created" == "true" ]] \
        && [[ "$OPSMIND_EPHEMERAL_DB" == "true" ]] \
        && [[ "$database_name" =~ ^opsmind_tool_gateway_rls_[a-z0-9_]+$ ]]; then
        PGPASSWORD="$POSTGRES_PASSWORD" dropdb \
            --host "$host" \
            --port "$port" \
            --username "$POSTGRES_USER" \
            --if-exists \
            "$database_name" >/dev/null 2>&1 || true
    fi
    exit "$status"
}
trap cleanup EXIT

PGPASSWORD="$POSTGRES_PASSWORD" createdb \
    --host "$host" \
    --port "$port" \
    --username "$POSTGRES_USER" \
    --owner "$POSTGRES_USER" \
    "$database_name"
database_created=true

PGPASSWORD="$POSTGRES_PASSWORD" psql \
    --host "$host" \
    --port "$port" \
    --username "$POSTGRES_USER" \
    --dbname "$database_name" \
    --set=ON_ERROR_STOP=1 <<SQL
CREATE SCHEMA tool_gateway AUTHORIZATION opsmind_tool_gateway_migrator;
REVOKE ALL ON SCHEMA tool_gateway FROM PUBLIC;
SQL

run_migration() {
    local target="$1"
    if [[ "$target" == "latest" ]]; then
        SPRING_PROFILES_ACTIVE=persistence \
        TOOL_GATEWAY_DATABASE_URL="$database_url" \
        TOOL_GATEWAY_DATABASE_USER="$POSTGRES_TOOL_GATEWAY_MIGRATOR_USER" \
        TOOL_GATEWAY_DATABASE_PASSWORD="$POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD" \
        TOOL_GATEWAY_PERSISTENCE_ENABLED=true \
        TOOL_GATEWAY_FLYWAY_ENABLED=true \
            java -jar "$gateway_jar" --spring.main.web-application-type=none
        return
    fi

    SPRING_PROFILES_ACTIVE=persistence \
    TOOL_GATEWAY_DATABASE_URL="$database_url" \
    TOOL_GATEWAY_DATABASE_USER="$POSTGRES_TOOL_GATEWAY_MIGRATOR_USER" \
    TOOL_GATEWAY_DATABASE_PASSWORD="$POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD" \
    TOOL_GATEWAY_PERSISTENCE_ENABLED=true \
    TOOL_GATEWAY_FLYWAY_ENABLED=true \
    SPRING_FLYWAY_TARGET="$target" \
        java -jar "$gateway_jar" --spring.main.web-application-type=none
}

run_migration 2

PGPASSWORD="$POSTGRES_TOOL_GATEWAY_PASSWORD" psql \
    --host "$host" \
    --port "$port" \
    --username "$POSTGRES_TOOL_GATEWAY_USER" \
    --dbname "$database_name" \
    --set=ON_ERROR_STOP=1 <<SQL
INSERT INTO tool_gateway.execution_receipts (
    execution_id, tenant_id, project_id, incident_id, run_id, request_digest,
    status, lease_token, lease_expires_at
) VALUES (
    '$legacy_execution_id', '$tenant_id', '$project_id',
    '33333333-3333-4333-8333-333333333339',
    '44444444-4444-4444-8444-444444444449',
    repeat('a', 64), 'IN_PROGRESS',
    '55555555-5555-4555-8555-555555555559',
    transaction_timestamp() + INTERVAL '1 hour'
);
INSERT INTO tool_gateway.tool_audit_events (
    audit_event_id, execution_id, outcome, request_digest, denial_code
) VALUES (
    '$legacy_audit_id', '$legacy_execution_id', 'DENIED',
    repeat('b', 64), 'action.disabled'
);
SQL

run_migration latest

PGPASSWORD="$POSTGRES_PASSWORD" psql \
    --host "$host" \
    --port "$port" \
    --username "$POSTGRES_USER" \
    --dbname "$database_name" \
    --set=ON_ERROR_STOP=1 <<SQL
DO \$\$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM tool_gateway.flyway_schema_history
        WHERE script = 'V003__tenant_project_row_security.sql' AND success
    ) THEN
        RAISE EXCEPTION 'V003 Flyway history is missing';
    END IF;
    IF (
        SELECT count(*) FROM tool_gateway.execution_receipts
        WHERE execution_id = '$legacy_execution_id'
          AND tenant_id = '$tenant_id'
          AND project_id = '$project_id'
    ) <> 1 THEN
        RAISE EXCEPTION 'legacy receipt was not preserved';
    END IF;
    IF (
        SELECT count(*) FROM tool_gateway.tool_audit_events
        WHERE audit_event_id = '$legacy_audit_id'
          AND tenant_id IS NULL AND project_id IS NULL
    ) <> 1 THEN
        RAISE EXCEPTION 'legacy audit attribution changed';
    END IF;
    IF (
        SELECT count(*)
        FROM pg_class table_state
        JOIN pg_namespace schema_state
          ON schema_state.oid = table_state.relnamespace
        WHERE schema_state.nspname = 'tool_gateway'
          AND table_state.relname IN ('execution_receipts', 'tool_audit_events')
          AND table_state.relrowsecurity
          AND table_state.relforcerowsecurity
    ) <> 2 THEN
        RAISE EXCEPTION 'forced RLS state is incomplete';
    END IF;
    IF (
        SELECT count(*) FROM pg_policies
        WHERE schemaname = 'tool_gateway'
          AND (
              (
                  tablename = 'execution_receipts'
                  AND policyname = 'execution_receipts_tenant_project_isolation'
              )
              OR
              (
                  tablename = 'tool_audit_events'
                  AND policyname = 'tool_audit_events_tenant_project_isolation'
              )
          )
          AND permissive = 'PERMISSIVE'
          AND roles = ARRAY['public']::name[]
          AND cmd = 'ALL'
          AND regexp_replace(qual, '[[:space:]()]', '', 'g') =
              'tenant_id=tool_gateway.current_tenant_idANDproject_id=tool_gateway.current_project_id'
          AND regexp_replace(with_check, '[[:space:]()]', '', 'g') =
              'tenant_id=tool_gateway.current_tenant_idANDproject_id=tool_gateway.current_project_id'
    ) <> 2 THEN
        RAISE EXCEPTION 'tenant/project policy definitions are incomplete';
    END IF;
END;
\$\$;
SQL

PGPASSWORD="$POSTGRES_TOOL_GATEWAY_MIGRATOR_PASSWORD" psql \
    --host "$host" \
    --port "$port" \
    --username "$POSTGRES_TOOL_GATEWAY_MIGRATOR_USER" \
    --dbname "$database_name" \
    --set=ON_ERROR_STOP=1 <<SQL
DO \$\$
BEGIN
    IF (SELECT count(*) FROM tool_gateway.execution_receipts) <> 0 THEN
        RAISE EXCEPTION 'FORCE RLS did not constrain the table-owning migrator';
    END IF;
END;
\$\$;
SQL

PGPASSWORD="$POSTGRES_TOOL_GATEWAY_PASSWORD" psql \
    --host "$host" \
    --port "$port" \
    --username "$POSTGRES_TOOL_GATEWAY_USER" \
    --dbname "$database_name" \
    --set=ON_ERROR_STOP=1 <<SQL
DO \$\$
BEGIN
    IF (SELECT count(*) FROM tool_gateway.execution_receipts) <> 0 THEN
        RAISE EXCEPTION 'missing context exposed tenant receipts';
    END IF;
END;
\$\$;
BEGIN;
SELECT tool_gateway.set_tenant_context('$tenant_id', '$project_id');
DO \$\$
BEGIN
    IF (
        SELECT count(*) FROM tool_gateway.execution_receipts
        WHERE execution_id = '$legacy_execution_id'
    ) <> 1 THEN
        RAISE EXCEPTION 'bound runtime scope cannot read its receipt';
    END IF;
END;
\$\$;
SELECT tool_gateway.set_tenant_context('$tenant_id', '$foreign_project_id');
DO \$\$
BEGIN
    IF (
        SELECT count(*) FROM tool_gateway.execution_receipts
        WHERE execution_id = '$legacy_execution_id'
    ) <> 0 THEN
        RAISE EXCEPTION 'same-tenant foreign-project scope exposed a receipt';
    END IF;
END;
\$\$;
ROLLBACK;
INSERT INTO tool_gateway.unverified_tool_audit_events (
    audit_event_id, execution_id, outcome, request_digest, denial_code
) VALUES (
    '66666666-6666-4666-8666-666666666669',
    '$legacy_execution_id', 'DENIED', repeat('c', 64), 'capability.invalid'
);
SQL

if PGPASSWORD="$POSTGRES_TOOL_GATEWAY_PASSWORD" psql \
    --host "$host" \
    --port "$port" \
    --username "$POSTGRES_TOOL_GATEWAY_USER" \
    --dbname "$database_name" \
    --set=ON_ERROR_STOP=1 \
    --command "INSERT INTO tool_gateway.tool_audit_events (
        audit_event_id, execution_id, outcome, request_digest, denial_code
    ) VALUES (
        '77777777-7777-4777-8777-777777777779',
        '$legacy_execution_id', 'DENIED', repeat('d', 64), 'action.disabled'
    );" >/dev/null 2>&1; then
    printf '%s\n' 'Runtime appended an unscoped verified audit row.' >&2
    exit 1
fi

printf '%s\n' \
    'ToolGatewayRlsUpgrade=PASS' \
    'UpgradePath=V002_TO_V003' \
    'LegacyReceipt=PASS' \
    'LegacyAuditPreservedUnattributed=PASS' \
    'ForcedRls=PASS' \
    'MigratorOwnerForcedRls=PASS' \
    'NoContextDeny=PASS' \
    'ScopedRuntimeRead=PASS' \
    'SameTenantForeignProjectDeny=PASS' \
    'UnverifiedAuditLane=PASS' \
    'Result=PASS'
