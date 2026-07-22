#!/usr/bin/env bash

set -Eeuo pipefail

if [[ "${OPSMIND_EPHEMERAL_DB:-false}" != "true" ]]; then
    printf '%s\n' 'Refusing to mutate a non-ephemeral database. Set OPSMIND_EPHEMERAL_DB=true only for a disposable CI/test database.' >&2
    exit 2
fi
command -v psql >/dev/null 2>&1 || {
    printf '%s\n' 'psql is required for the Phase 3 PostgreSQL contract.' >&2
    exit 2
}

db_host="${PGHOST:-127.0.0.1}"
db_port="${PGPORT:-5432}"
db_name="${PGDATABASE:-opsmind}"
admin_user="${POSTGRES_USER:-opsmind_migrator}"
admin_password="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"
app_user="${POSTGRES_APP_USER:-opsmind_app}"
app_password="${POSTGRES_APP_PASSWORD:?POSTGRES_APP_PASSWORD is required}"
dispatcher_user="${POSTGRES_DISPATCHER_USER:-opsmind_dispatcher}"
dispatcher_password="${POSTGRES_DISPATCHER_PASSWORD:?POSTGRES_DISPATCHER_PASSWORD is required}"

if [[ "$app_user" != "opsmind_app" || "$dispatcher_user" != "opsmind_dispatcher" ||
      "$admin_password" == "$app_password" || "$admin_password" == "$dispatcher_password" ||
      "$app_password" == "$dispatcher_password" ]]; then
    printf '%s\n' 'Runtime role/password separation contract failed.' >&2
    exit 2
fi

psql_args=(--no-password --host "$db_host" --port "$db_port" --username "$admin_user" --dbname "$db_name")
app_psql_args=(--no-password --host "$db_host" --port "$db_port" --username "$app_user" --dbname "$db_name")
dispatcher_psql_args=(--no-password --host "$db_host" --port "$db_port" --username "$dispatcher_user" --dbname "$db_name")

admin_psql() { PGPASSWORD="$admin_password" psql "${psql_args[@]}" "$@"; }
app_psql() { PGPASSWORD="$app_password" psql "${app_psql_args[@]}" "$@"; }
dispatcher_psql() { PGPASSWORD="$dispatcher_password" psql "${dispatcher_psql_args[@]}" "$@"; }

printf 'DatabaseHost=%s\nDatabaseName=%s\nRuntimeRole=%s\nDispatcherRole=%s\n' \
    "$db_host" "$db_name" "$app_user" "$dispatcher_user"

admin_psql <<'SQL'
\set ON_ERROR_STOP on
DO $$
DECLARE
    app_super boolean;
    app_bypass boolean;
    app_inherit boolean;
    resolver_super boolean;
    resolver_bypass boolean;
    resolver_login boolean;
    resolver_inherit boolean;
    dispatcher_unsafe boolean;
    dispatch_resolver_unsafe boolean;
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM public.flyway_schema_history
         WHERE script = 'V001__identity_tenant_foundation.sql'
           AND success
    ) THEN
        RAISE EXCEPTION 'Flyway migration history does not prove V001 success';
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM public.flyway_schema_history
         WHERE script = 'V002__outbox_dispatcher_workload.sql'
           AND success
    ) THEN
        RAISE EXCEPTION 'Flyway migration history does not prove V002 success';
    END IF;
    SELECT rolsuper, rolbypassrls, rolinherit
      INTO app_super, app_bypass, app_inherit
      FROM pg_roles
     WHERE rolname = 'opsmind_app';
    IF app_super IS DISTINCT FROM false
       OR app_bypass IS DISTINCT FROM false
       OR app_inherit IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'runtime role attributes are unsafe';
    END IF;
    SELECT rolsuper, rolbypassrls, rolcanlogin, rolinherit
      INTO resolver_super, resolver_bypass, resolver_login, resolver_inherit
      FROM pg_roles
     WHERE rolname = 'opsmind_context_resolver';
    IF resolver_super IS DISTINCT FROM false
       OR resolver_bypass IS DISTINCT FROM false
       OR resolver_login IS DISTINCT FROM false
       OR resolver_inherit IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'context resolver role attributes are unsafe';
    END IF;
    SELECT rolsuper OR rolbypassrls OR NOT rolcanlogin OR rolinherit
      INTO dispatcher_unsafe
      FROM pg_roles
     WHERE rolname = 'opsmind_dispatcher';
    IF dispatcher_unsafe IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'dispatcher role attributes are unsafe';
    END IF;
    SELECT rolsuper OR rolbypassrls OR rolcanlogin OR rolinherit
      INTO dispatch_resolver_unsafe
      FROM pg_roles
     WHERE rolname = 'opsmind_dispatch_resolver';
    IF dispatch_resolver_unsafe IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'dispatch resolver role attributes are unsafe';
    END IF;
    IF has_schema_privilege('opsmind_app', 'public', 'CREATE') THEN
        RAISE EXCEPTION 'runtime role can create objects in public schema';
    END IF;
    IF has_table_privilege('opsmind_app', 'platform_users', 'UPDATE') THEN
        RAISE EXCEPTION 'runtime role can mutate identity authority';
    END IF;
    IF has_table_privilege('opsmind_app', 'platform_users', 'SELECT') THEN
        RAISE EXCEPTION 'runtime role can dump global identity data';
    END IF;
    IF NOT has_table_privilege('opsmind_app', 'outbox_events', 'INSERT')
       OR NOT has_table_privilege('opsmind_app', 'audit_events', 'INSERT') THEN
        RAISE EXCEPTION 'runtime role lacks required append privileges';
    END IF;
    IF has_column_privilege('opsmind_app', 'outbox_events', 'published_at', 'UPDATE')
       OR has_column_privilege('opsmind_app', 'outbox_events', 'lease_token', 'UPDATE') THEN
        RAISE EXCEPTION 'web runtime role retained outbox dispatch privileges';
    END IF;
    IF NOT has_table_privilege('opsmind_dispatcher', 'outbox_events', 'SELECT')
       OR NOT has_column_privilege('opsmind_dispatcher', 'outbox_events', 'published_at', 'UPDATE')
       OR has_table_privilege('opsmind_dispatcher', 'outbox_events', 'INSERT')
       OR has_table_privilege('opsmind_dispatcher', 'service_accounts', 'SELECT') THEN
        RAISE EXCEPTION 'dispatcher table privileges violate least privilege';
    END IF;
    IF pg_get_userbyid((SELECT proowner FROM pg_proc WHERE oid = 'public.opsmind_set_tenant_context(uuid, uuid)'::regprocedure))
        IS DISTINCT FROM 'opsmind_context_resolver' THEN
        RAISE EXCEPTION 'tenant context function has an unexpected owner';
    END IF;
    IF pg_get_userbyid((SELECT proowner FROM pg_proc WHERE oid = 'public.opsmind_list_dispatch_tenants(integer)'::regprocedure))
        IS DISTINCT FROM 'opsmind_dispatch_resolver'
       OR pg_get_userbyid((SELECT proowner FROM pg_proc WHERE oid = 'public.opsmind_set_dispatcher_tenant_context(uuid)'::regprocedure))
        IS DISTINCT FROM 'opsmind_dispatch_resolver' THEN
        RAISE EXCEPTION 'dispatcher resolver functions have an unexpected owner';
    END IF;
END
$$;

INSERT INTO organizations (id, slug, name)
VALUES
    ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'phase3-a', 'Phase 3 Tenant A'),
    ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'phase3-b', 'Phase 3 Tenant B')
ON CONFLICT (id) DO NOTHING;

INSERT INTO platform_users (id, issuer, subject, display_name)
VALUES
    ('11111111-1111-4111-8111-111111111111',
     'https://idp.example.test/opsmind', 'phase3-operator-a', 'Phase 3 Operator A'),
    ('22222222-2222-4222-8222-222222222222',
     'https://idp.example.test/opsmind', 'phase3-operator-b', 'Phase 3 Operator B')
ON CONFLICT (id) DO NOTHING;

INSERT INTO organization_memberships (organization_id, user_id, role)
VALUES
    ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111111', 'operator'),
    ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', '22222222-2222-4222-8222-222222222222', 'operator')
ON CONFLICT (organization_id, user_id) DO NOTHING;

INSERT INTO service_accounts (
    id, organization_id, name, credential_ref, allowed_audiences,
    allowed_scopes, database_principal
) VALUES
    ('d15a0001-d15a-415a-815a-d15a00000001',
     'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'outbox-dispatcher',
     'secret-manager://phase3/tenant-a/dispatcher',
     '["opsmind-outbox-dispatcher"]'::jsonb, '["outbox:dispatch"]'::jsonb,
     'opsmind_dispatcher'),
    ('d15b0001-d15b-415b-815b-d15b00000001',
     'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'outbox-dispatcher',
     'secret-manager://phase3/tenant-b/dispatcher',
     '["opsmind-outbox-dispatcher"]'::jsonb, '["outbox:dispatch"]'::jsonb,
     'opsmind_dispatcher')
ON CONFLICT (id) DO NOTHING;

INSERT INTO projects (id, organization_id, slug, name)
VALUES
    ('aaaaaaa1-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'project-a', 'Project A'),
    ('bbbbbba1-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'project-b', 'Project B')
ON CONFLICT (id) DO NOTHING;

INSERT INTO audit_events (
    event_id, organization_id, actor_id, action, resource_type, resource_id,
    correlation_id, occurred_at, payload, event_digest
) VALUES (
    '12345678-1234-4234-8234-123456789abc'::uuid,
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid,
    '11111111-1111-4111-8111-111111111111'::uuid,
    'project.observed', 'project', 'aaaaaaa1-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    '87654321-4321-4321-8321-cba987654321'::uuid,
    clock_timestamp(), '{}'::jsonb,
    digest(convert_to('{}', 'UTF8'), 'sha256')
);

DO $$
DECLARE rejected boolean := false;
BEGIN
    BEGIN
        UPDATE audit_events SET action = 'migration-owner-mutation';
    EXCEPTION WHEN OTHERS THEN
        rejected := true;
    END;
    IF NOT rejected THEN
        RAISE EXCEPTION 'append-only audit trigger accepted an owner mutation';
    END IF;
END
$$;
SQL

app_psql <<'SQL'
\set ON_ERROR_STOP on
BEGIN;
DO $$
DECLARE resolved_users integer;
BEGIN
    SELECT count(*) INTO resolved_users
      FROM public.opsmind_resolve_user(
          'https://idp.example.test/opsmind', 'phase3-operator-a'
      );
    IF resolved_users <> 1 THEN
        RAISE EXCEPTION 'narrow identity resolver did not return the expected subject';
    END IF;
END
$$;
SELECT public.opsmind_set_tenant_context(
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid,
    '11111111-1111-4111-8111-111111111111'::uuid
);

DO $$
DECLARE visible_projects integer;
BEGIN
    SELECT count(*) INTO visible_projects FROM projects;
    IF visible_projects <> 1 THEN
        RAISE EXCEPTION 'expected exactly one project in tenant context';
    END IF;
    IF EXISTS (
        SELECT 1 FROM projects
         WHERE id = 'bbbbbba1-bbbb-4bbb-8bbb-bbbbbbbbbbbb'::uuid
    ) THEN
        RAISE EXCEPTION 'cross-tenant project became visible';
    END IF;
END
$$;

DO $$
DECLARE rejected boolean := false;
BEGIN
    BEGIN
        PERFORM public.opsmind_set_tenant_context(
            'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'::uuid,
            '11111111-1111-4111-8111-111111111111'::uuid
        );
    EXCEPTION WHEN OTHERS THEN
        rejected := true;
    END;
    IF NOT rejected THEN
        RAISE EXCEPTION 'context switched to a tenant without membership';
    END IF;
END
$$;

DO $$
DECLARE rejected boolean := false;
BEGIN
    BEGIN
        INSERT INTO outbox_events (
            event_id, organization_id, aggregate_type, aggregate_id,
            aggregate_sequence, event_type, schema_version, correlation_id,
            occurred_at, payload, payload_bytes, payload_digest
        ) VALUES (
            'ccccccc1-cccc-4ccc-8ccc-cccccccccccc'::uuid,
            'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'::uuid,
            'project', 'bbbbbba1-bbbb-4bbb-8bbb-bbbbbbbbbbbb'::uuid,
            1, 'project.created', '1',
            'ddddddd1-dddd-4ddd-8ddd-dddddddddddd'::uuid,
            clock_timestamp(), '{}'::jsonb, convert_to('{}', 'UTF8'),
            digest(convert_to('{}', 'UTF8'), 'sha256')
        );
    EXCEPTION WHEN insufficient_privilege THEN
        rejected := true;
    END;
    IF NOT rejected THEN
        RAISE EXCEPTION 'cross-tenant outbox insert was accepted';
    END IF;
END
$$;

INSERT INTO outbox_events (
    event_id, organization_id, aggregate_type, aggregate_id,
    aggregate_sequence, event_type, schema_version, correlation_id,
    occurred_at, payload, payload_bytes, payload_digest
) VALUES (
    'eeeeeee1-eeee-4eee-8eee-eeeeeeeeeeee'::uuid,
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid,
    'project', 'aaaaaaa1-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid,
    1, 'project.created', '1',
    'fffffff1-ffff-4fff-8fff-ffffffffffff'::uuid,
    clock_timestamp(), '{}'::jsonb, convert_to('{}', 'UTF8'),
    digest(convert_to('{}', 'UTF8'), 'sha256')
);

DO $$
DECLARE rejected boolean := false;
BEGIN
    BEGIN
        INSERT INTO outbox_events (
            event_id, organization_id, aggregate_type, aggregate_id,
            aggregate_sequence, event_type, schema_version, correlation_id,
            occurred_at, payload, payload_bytes, payload_digest
        ) VALUES (
            'eeeeeee2-eeee-4eee-8eee-eeeeeeeeeeee'::uuid,
            'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid,
            'project', 'aaaaaaa1-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid,
            3, 'project.renamed', '1',
            'fffffff2-ffff-4fff-8fff-ffffffffffff'::uuid,
            clock_timestamp(), '{}'::jsonb, convert_to('{}', 'UTF8'),
            digest(convert_to('{}', 'UTF8'), 'sha256')
        );
    EXCEPTION WHEN SQLSTATE 'P3001' THEN
        rejected := true;
    END;
    IF NOT rejected THEN
        RAISE EXCEPTION 'non-contiguous aggregate sequence was accepted';
    END IF;
END
$$;

DO $$
DECLARE rejected boolean := false;
BEGIN
    BEGIN
        INSERT INTO outbox_events (
            event_id, organization_id, aggregate_type, aggregate_id,
            aggregate_sequence, event_type, schema_version, correlation_id,
            occurred_at, payload, payload_bytes, payload_digest
        ) VALUES (
            'eeeeeee1-eeee-4eee-8eee-eeeeeeeeeeee'::uuid,
            'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid,
            'project', '77777777-7777-4777-8777-777777777777'::uuid,
            1, 'project.created', '1',
            'fffffff3-ffff-4fff-8fff-ffffffffffff'::uuid,
            clock_timestamp(), '{}'::jsonb, convert_to('{}', 'UTF8'),
            digest(convert_to('{}', 'UTF8'), 'sha256')
        );
    EXCEPTION WHEN unique_violation THEN
        rejected := true;
    END;
    IF NOT rejected THEN
        RAISE EXCEPTION 'duplicate event identity was accepted';
    END IF;
END
$$;

INSERT INTO inbox_events (event_id, organization_id, consumer)
VALUES (
    '76543210-1234-4234-8234-123456789abc'::uuid,
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid,
    'phase3-contract'
);
INSERT INTO inbox_events (event_id, organization_id, consumer)
VALUES (
    '76543210-1234-4234-8234-123456789abc'::uuid,
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid,
    'phase3-contract'
) ON CONFLICT (organization_id, event_id, consumer) DO NOTHING;

DO $$
DECLARE inbox_count integer;
BEGIN
    SELECT count(*) INTO inbox_count
      FROM inbox_events
     WHERE event_id = '76543210-1234-4234-8234-123456789abc'::uuid
       AND consumer = 'phase3-contract';
    IF inbox_count <> 1 THEN
        RAISE EXCEPTION 'inbox duplicate suppression failed';
    END IF;
END
$$;

INSERT INTO idempotency_records (
    organization_id, idempotency_key, actor_id, request_digest, status
) VALUES (
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid,
    'phase3-request-a',
    '11111111-1111-4111-8111-111111111111'::uuid,
    digest(convert_to('{"operation":"observe"}', 'UTF8'), 'sha256'),
    'in_progress'
);
UPDATE idempotency_records
   SET status = 'succeeded', response_status = 200,
       response_body = '{"result":"accepted"}'::jsonb,
       completed_at = clock_timestamp()
 WHERE organization_id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid
   AND idempotency_key = 'phase3-request-a';

DO $$
DECLARE rejected boolean := false;
BEGIN
    BEGIN
        INSERT INTO idempotency_records (
            organization_id, idempotency_key, actor_id, request_digest, status
        ) VALUES (
            'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'::uuid,
            'phase3-cross-tenant',
            '11111111-1111-4111-8111-111111111111'::uuid,
            digest(convert_to('{}', 'UTF8'), 'sha256'),
            'in_progress'
        );
    EXCEPTION WHEN insufficient_privilege THEN
        rejected := true;
    END;
    IF NOT rejected THEN
        RAISE EXCEPTION 'cross-tenant idempotency insert was accepted';
    END IF;
END
$$;

DO $$
DECLARE rejected boolean := false;
BEGIN
    BEGIN
        UPDATE audit_events SET action = 'mutated';
    EXCEPTION WHEN insufficient_privilege THEN
        rejected := true;
    END;
    IF NOT rejected THEN
        RAISE EXCEPTION 'audit mutation was accepted';
    END IF;
END
$$;
COMMIT;

DO $$
BEGIN
    IF public.opsmind_current_tenant_id() IS NOT NULL
       OR NULLIF(current_setting('opsmind.actor_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'transaction-local tenant context survived commit';
    END IF;
END
$$;

BEGIN;
SELECT public.opsmind_set_tenant_context(
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'::uuid,
    '22222222-2222-4222-8222-222222222222'::uuid
);
DO $$
DECLARE visible_projects integer;
BEGIN
    SELECT count(*) INTO visible_projects FROM projects;
    IF visible_projects <> 1 OR NOT EXISTS (
        SELECT 1 FROM projects
         WHERE id = 'bbbbbba1-bbbb-4bbb-8bbb-bbbbbbbbbbbb'::uuid
    ) THEN
        RAISE EXCEPTION 'alternating tenant session did not isolate tenant B';
    END IF;
END
$$;
ROLLBACK;

DO $$
BEGIN
    IF public.opsmind_current_tenant_id() IS NOT NULL
       OR NULLIF(current_setting('opsmind.actor_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'transaction-local tenant context survived rollback';
    END IF;
END
$$;
SQL

dispatcher_psql <<'SQL'
\set ON_ERROR_STOP on
BEGIN;
DO $$
DECLARE
    visible_events integer;
    ready_tenants integer;
BEGIN
    SELECT count(*) INTO visible_events
      FROM outbox_events
     WHERE event_id = 'eeeeeee1-eeee-4eee-8eee-eeeeeeeeeeee'::uuid;
    IF visible_events <> 0 THEN
        RAISE EXCEPTION 'dispatcher saw tenant rows before context binding';
    END IF;
    SELECT count(*) INTO ready_tenants
      FROM public.opsmind_list_dispatch_tenants(10);
    IF ready_tenants <> 1 THEN
        RAISE EXCEPTION 'dispatcher scheduler did not return exactly one ready tenant';
    END IF;
END
$$;

SELECT public.opsmind_set_dispatcher_tenant_context(
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid
);

DO $$
DECLARE
    visible_events integer;
BEGIN
    IF public.opsmind_current_tenant_id() IS DISTINCT FROM
       'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::uuid THEN
        RAISE EXCEPTION 'dispatcher tenant context was not applied';
    END IF;
    IF public.opsmind_current_workload_id() IS DISTINCT FROM
       'd15a0001-d15a-415a-815a-d15a00000001'::uuid THEN
        RAISE EXCEPTION 'dispatcher workload identity was not bound';
    END IF;
    SELECT count(*) INTO visible_events
      FROM outbox_events
     WHERE event_id = 'eeeeeee1-eeee-4eee-8eee-eeeeeeeeeeee'::uuid;
    IF visible_events <> 1 THEN
        RAISE EXCEPTION 'dispatcher tenant context exposed an unexpected event set';
    END IF;
END
$$;

DO $$
DECLARE rejected boolean := false;
BEGIN
    BEGIN
        PERFORM public.opsmind_set_dispatcher_tenant_context(
            'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'::uuid
        );
    EXCEPTION WHEN insufficient_privilege THEN
        rejected := true;
    END;
    IF NOT rejected THEN
        RAISE EXCEPTION 'dispatcher switched tenants inside one transaction';
    END IF;
END
$$;
ROLLBACK;

DO $$
BEGIN
    IF public.opsmind_current_tenant_id() IS NOT NULL
       OR public.opsmind_current_workload_id() IS NOT NULL THEN
        RAISE EXCEPTION 'dispatcher context survived transaction rollback';
    END IF;
END
$$;
SQL

printf '%s\n' 'RoleSeparation=PASS' 'ContextResolverRole=PASS' 'CrossTenantRead=PASS' \
    'ContextMembershipGuard=PASS' 'AlternatingTenantSession=PASS' \
    'CrossTenantOutboxWrite=PASS' 'OutboxSequenceUniqueness=PASS' \
    'OutboxSequenceContiguity=PASS' \
    'InboxDuplicateSuppression=PASS' 'IdempotencyIsolation=PASS' \
    'AuditTrigger=PASS' 'AuditImmutability=PASS' \
    'DispatcherRoleSeparation=PASS' 'DispatcherTenantBinding=PASS' \
    'DispatcherContextReset=PASS' \
    'TransactionContextReset=PASS' 'Result=PASS'
