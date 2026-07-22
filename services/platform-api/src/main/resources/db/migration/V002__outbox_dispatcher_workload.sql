-- Dedicated outbox dispatcher identity and tenant scheduler.
--
-- The web role remains append-only. The dispatcher is a distinct login role
-- with no RLS bypass. A non-login resolver owns only the narrow cross-tenant
-- metadata functions needed to discover eligible tenants and bind one tenant
-- per transaction.

DO $$
DECLARE
    dispatcher_unsafe boolean;
    resolver_unsafe boolean;
BEGIN
    SELECT rolsuper OR rolbypassrls OR NOT rolcanlogin OR rolinherit
      INTO dispatcher_unsafe
      FROM pg_roles
     WHERE rolname = 'opsmind_dispatcher';
    IF dispatcher_unsafe IS NULL THEN
        RAISE EXCEPTION 'required outbox role opsmind_dispatcher is missing';
    END IF;
    IF dispatcher_unsafe THEN
        RAISE EXCEPTION 'outbox role opsmind_dispatcher has unsafe attributes';
    END IF;

    SELECT rolsuper OR rolbypassrls OR rolcanlogin OR rolinherit
      INTO resolver_unsafe
      FROM pg_roles
     WHERE rolname = 'opsmind_dispatch_resolver';
    IF resolver_unsafe IS NULL THEN
        RAISE EXCEPTION 'required opsmind_dispatch_resolver role is missing';
    END IF;
    IF resolver_unsafe THEN
        RAISE EXCEPTION 'opsmind_dispatch_resolver has unsafe attributes';
    END IF;
END
$$;

ALTER TABLE service_accounts
    ADD COLUMN database_principal varchar(63),
    ADD CONSTRAINT service_accounts_database_principal_format
        CHECK (database_principal IS NULL OR
            database_principal ~ '^[a-z][a-z0-9_]{2,62}$');

CREATE UNIQUE INDEX service_accounts_database_principal_idx
    ON service_accounts (organization_id, database_principal)
    WHERE database_principal IS NOT NULL;

CREATE OR REPLACE FUNCTION opsmind_current_workload_id() RETURNS uuid
LANGUAGE sql STABLE AS $$
    SELECT CASE
        WHEN current_setting('opsmind.workload_id', true) ~
            '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$'
        THEN current_setting('opsmind.workload_id', true)::uuid
        ELSE NULL
    END
$$;

-- These policies apply only while a SECURITY DEFINER function executes as the
-- non-login resolver. Column grants below further restrict what it can read.
CREATE POLICY organizations_dispatch_resolution ON organizations
    FOR SELECT TO opsmind_dispatch_resolver USING (true);
CREATE POLICY service_accounts_dispatch_resolution ON service_accounts
    FOR SELECT TO opsmind_dispatch_resolver USING (true);
CREATE POLICY outbox_events_dispatch_resolution ON outbox_events
    FOR SELECT TO opsmind_dispatch_resolver USING (true);

CREATE OR REPLACE FUNCTION opsmind_list_dispatch_tenants(p_limit integer)
RETURNS TABLE(organization_id uuid)
LANGUAGE plpgsql
SECURITY DEFINER
STABLE
SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF session_user <> 'opsmind_dispatcher' THEN
        RAISE EXCEPTION 'dedicated dispatcher identity is required'
            USING ERRCODE = '42501';
    END IF;
    IF p_limit IS NULL OR p_limit < 1 OR p_limit > 100 THEN
        RAISE EXCEPTION 'dispatcher tenant limit must be between 1 and 100'
            USING ERRCODE = '22023';
    END IF;

    RETURN QUERY
    SELECT event_row.organization_id
      FROM public.outbox_events event_row
      JOIN public.organizations organization_row
        ON organization_row.id = event_row.organization_id
       AND organization_row.status = 'active'
      JOIN public.service_accounts account_row
        ON account_row.organization_id = event_row.organization_id
       AND account_row.status = 'active'
       AND account_row.database_principal = session_user
       AND account_row.allowed_audiences @> '["opsmind-outbox-dispatcher"]'::jsonb
       AND account_row.allowed_scopes @> '["outbox:dispatch"]'::jsonb
     WHERE event_row.published_at IS NULL
       AND event_row.poisoned_at IS NULL
       AND event_row.next_attempt_at <= statement_timestamp()
       AND (event_row.lease_expires_at IS NULL OR
            event_row.lease_expires_at <= statement_timestamp())
       AND NOT EXISTS (
           SELECT 1
             FROM public.outbox_events predecessor
            WHERE predecessor.organization_id = event_row.organization_id
              AND predecessor.aggregate_type = event_row.aggregate_type
              AND predecessor.aggregate_id = event_row.aggregate_id
              AND predecessor.aggregate_sequence < event_row.aggregate_sequence
              AND predecessor.published_at IS NULL
       )
     GROUP BY event_row.organization_id
     ORDER BY min(event_row.occurred_at), event_row.organization_id
     LIMIT p_limit;
END
$$;
ALTER FUNCTION public.opsmind_list_dispatch_tenants(integer)
    OWNER TO opsmind_dispatch_resolver;

CREATE OR REPLACE FUNCTION opsmind_set_dispatcher_tenant_context(p_tenant_id uuid)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    account_id uuid;
    existing_tenant uuid;
BEGIN
    IF session_user <> 'opsmind_dispatcher' THEN
        RAISE EXCEPTION 'dedicated dispatcher identity is required'
            USING ERRCODE = '42501';
    END IF;
    IF p_tenant_id IS NULL THEN
        RAISE EXCEPTION 'dispatcher tenant is required'
            USING ERRCODE = '22023';
    END IF;

    existing_tenant := public.opsmind_current_tenant_id();
    IF existing_tenant IS NOT NULL AND existing_tenant <> p_tenant_id THEN
        RAISE EXCEPTION 'dispatcher transaction is already bound to another tenant'
            USING ERRCODE = '42501';
    END IF;

    SELECT account_row.id
      INTO account_id
      FROM public.service_accounts account_row
      JOIN public.organizations organization_row
        ON organization_row.id = account_row.organization_id
       AND organization_row.status = 'active'
     WHERE account_row.organization_id = p_tenant_id
       AND account_row.status = 'active'
       AND account_row.database_principal = session_user
       AND account_row.allowed_audiences @> '["opsmind-outbox-dispatcher"]'::jsonb
       AND account_row.allowed_scopes @> '["outbox:dispatch"]'::jsonb;

    IF account_id IS NULL THEN
        RAISE EXCEPTION 'active tenant-scoped dispatcher account is required'
            USING ERRCODE = '42501';
    END IF;

    PERFORM set_config('opsmind.tenant_id', p_tenant_id::text, true);
    PERFORM set_config('opsmind.workload_id', account_id::text, true);
    PERFORM set_config('opsmind.actor_id', '', true);
    RETURN account_id;
END
$$;
ALTER FUNCTION public.opsmind_set_dispatcher_tenant_context(uuid)
    OWNER TO opsmind_dispatch_resolver;

DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO opsmind_dispatcher', current_database());
END
$$;

GRANT USAGE ON SCHEMA public TO opsmind_dispatcher;
GRANT USAGE ON SCHEMA public TO opsmind_dispatch_resolver;
GRANT SELECT (id, status) ON organizations TO opsmind_dispatch_resolver;
GRANT SELECT (id, organization_id, allowed_audiences, allowed_scopes, status,
    database_principal) ON service_accounts TO opsmind_dispatch_resolver;
GRANT SELECT (organization_id, aggregate_type, aggregate_id,
    aggregate_sequence, occurred_at, published_at, next_attempt_at,
    lease_expires_at, poisoned_at) ON outbox_events TO opsmind_dispatch_resolver;

REVOKE UPDATE (published_at, attempts, last_error, next_attempt_at,
    lease_token, lease_expires_at, poisoned_at) ON outbox_events FROM opsmind_app;
GRANT SELECT ON outbox_events TO opsmind_dispatcher;
GRANT UPDATE (published_at, attempts, last_error, next_attempt_at,
    lease_token, lease_expires_at, poisoned_at) ON outbox_events TO opsmind_dispatcher;
REVOKE INSERT, DELETE ON outbox_events FROM opsmind_dispatcher;
REVOKE ALL ON service_accounts, platform_users, organization_memberships,
    project_memberships, idempotency_records, inbox_events, audit_events
    FROM opsmind_dispatcher;

REVOKE ALL ON FUNCTION public.opsmind_current_workload_id() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_list_dispatch_tenants(integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_set_dispatcher_tenant_context(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.opsmind_current_tenant_id() TO opsmind_dispatcher;
GRANT EXECUTE ON FUNCTION public.opsmind_current_tenant_id() TO opsmind_dispatch_resolver;
GRANT EXECUTE ON FUNCTION public.opsmind_current_workload_id() TO opsmind_dispatcher;
GRANT EXECUTE ON FUNCTION public.opsmind_list_dispatch_tenants(integer) TO opsmind_dispatcher;
GRANT EXECUTE ON FUNCTION public.opsmind_set_dispatcher_tenant_context(uuid)
    TO opsmind_dispatcher;

COMMENT ON COLUMN service_accounts.database_principal IS
    'Fixed database login bound to this tenant-scoped workload metadata; never a credential.';
COMMENT ON FUNCTION opsmind_list_dispatch_tenants(integer) IS
    'Returns only tenants with authorized dispatcher metadata and claimable outbox work.';
COMMENT ON FUNCTION opsmind_set_dispatcher_tenant_context(uuid) IS
    'Binds one dispatcher transaction to one authorized tenant and workload identity.';
