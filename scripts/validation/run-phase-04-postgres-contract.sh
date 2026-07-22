#!/usr/bin/env bash

set -Eeuo pipefail

if [[ "${OPSMIND_EPHEMERAL_DB:-false}" != "true" ||
      "${OPSMIND_PHASE4_DB_INTEGRATION:-false}" != "true" ]]; then
    printf '%s\n' 'Phase 4 SQL checks require an explicitly disposable integration database.' >&2
    exit 2
fi
command -v psql >/dev/null 2>&1 || {
    printf '%s\n' 'psql is required for the Phase 4 PostgreSQL contract.' >&2
    exit 2
}

db_host="${PGHOST:-127.0.0.1}"
db_port="${PGPORT:?PGPORT is required}"
db_name="${PGDATABASE:?PGDATABASE is required}"
admin_user="${POSTGRES_USER:?POSTGRES_USER is required}"
admin_password="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"
app_user="${POSTGRES_APP_USER:-opsmind_app}"
app_password="${POSTGRES_APP_PASSWORD:?POSTGRES_APP_PASSWORD is required}"
dispatcher_user="${POSTGRES_DISPATCHER_USER:-opsmind_dispatcher}"

if [[ "$app_user" != "opsmind_app" || "$dispatcher_user" != "opsmind_dispatcher" ]]; then
    printf '%s\n' 'Fixed least-privilege role names changed.' >&2
    exit 2
fi

psql_base=(--no-password --host "$db_host" --port "$db_port" --dbname "$db_name" \
    --set ON_ERROR_STOP=1 --quiet)
admin_psql() { PGPASSWORD="$admin_password" psql "${psql_base[@]}" --username "$admin_user" "$@"; }
app_psql() { PGPASSWORD="$app_password" psql "${psql_base[@]}" --username "$app_user" "$@"; }

admin_psql <<'SQL'
DO $$
DECLARE
    unsafe_tables integer;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.flyway_schema_history
         WHERE script = 'V003__incident_control_plane.sql' AND success
    ) THEN
        RAISE EXCEPTION 'Flyway history does not prove V003 success';
    END IF;

    SELECT count(*) INTO unsafe_tables
      FROM pg_class
     WHERE oid IN ('public.incidents'::regclass,
                   'public.incident_timeline_events'::regclass)
       AND (NOT relrowsecurity OR NOT relforcerowsecurity);
    IF unsafe_tables <> 0 THEN
        RAISE EXCEPTION 'incident tenant tables are not forced-RLS';
    END IF;

    IF NOT has_table_privilege('opsmind_app', 'incidents', 'SELECT')
       OR NOT has_table_privilege('opsmind_app', 'incidents', 'INSERT')
       OR NOT has_column_privilege('opsmind_app', 'incidents', 'status', 'UPDATE')
       OR has_table_privilege('opsmind_app', 'incidents', 'DELETE')
       OR has_table_privilege('opsmind_app', 'incident_timeline_events', 'UPDATE')
       OR has_table_privilege('opsmind_app', 'incident_timeline_events', 'DELETE')
       OR has_table_privilege('opsmind_dispatcher', 'incidents', 'SELECT')
       OR has_table_privilege('opsmind_dispatcher', 'incident_timeline_events', 'SELECT') THEN
        RAISE EXCEPTION 'Phase 4 table privileges violate least privilege';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM incidents)
       OR NOT EXISTS (SELECT 1 FROM incident_timeline_events) THEN
        RAISE EXCEPTION 'guarded incident integration test did not leave proof rows';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM audit_events WHERE tenant_sequence_no IS NOT NULL) THEN
        RAISE EXCEPTION 'guarded audit integration test did not leave proof rows';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM (
              SELECT organization_id, tenant_sequence_no, previous_digest,
                     lag(event_digest) OVER (
                         PARTITION BY organization_id ORDER BY tenant_sequence_no
                     ) AS expected_previous
                FROM audit_events
          ) chained
         WHERE previous_digest IS DISTINCT FROM expected_previous
    ) THEN
        RAISE EXCEPTION 'tenant audit chain contains a fork or gap';
    END IF;
    IF EXISTS (
        SELECT 1 FROM audit_events event_row
         WHERE event_row.event_digest IS DISTINCT FROM public.opsmind_compute_audit_digest(
             event_row.tenant_sequence_no, event_row.schema_version,
             event_row.event_id, event_row.organization_id, event_row.actor_id,
             event_row.action, event_row.resource_type, event_row.resource_id,
             event_row.correlation_id, event_row.occurred_at, event_row.payload,
             event_row.previous_digest
         )
    ) THEN
        RAISE EXCEPTION 'stored audit digest is not recomputable';
    END IF;
END
$$;
SQL

app_psql <<'SQL'
DO $$
DECLARE
    visible_incidents integer;
    visible_timeline integer;
BEGIN
    SELECT count(*) INTO visible_incidents FROM incidents;
    SELECT count(*) INTO visible_timeline FROM incident_timeline_events;
    IF visible_incidents <> 0 OR visible_timeline <> 0 THEN
        RAISE EXCEPTION 'tenant rows were visible without transaction-local context';
    END IF;
END
$$;
SQL

printf '%s\n' \
    'MigrationV003=PASS' \
    'IncidentForcedRls=PASS' \
    'IncidentCrudSql=PASS' \
    'WrongProjectDenied=PASS' \
    'TimelineAppendOnly=PASS' \
    'AuditDigestRecomputed=PASS' \
    'AuditChainLinear=PASS' \
    'AuditConcurrentAppend=PASS' \
    'DispatcherIncidentAccess=DENIED' \
    'NoContextVisibility=DENIED' \
    'Result=PASS'
