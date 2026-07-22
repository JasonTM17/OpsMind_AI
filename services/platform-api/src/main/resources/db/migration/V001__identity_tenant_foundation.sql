-- OpsMind platform schema v1.
--
-- The application role is intentionally not created here. Deployment owns role
-- creation and grants; migrations run with a schema-owner role, while runtime
-- connections must use a non-owner role that cannot bypass row security.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- This non-login role owns only the narrow identity/context resolver functions.
-- It is deliberately not a table owner and has no row-security bypass privilege; the
-- authority-table policies below grant it an explicit, auditable read path.
DO $$
DECLARE
    resolver_super boolean;
    resolver_bypass boolean;
    resolver_login boolean;
    resolver_inherit boolean;
BEGIN
    SELECT rolsuper, rolbypassrls, rolcanlogin, rolinherit
      INTO resolver_super, resolver_bypass, resolver_login, resolver_inherit
      FROM pg_roles
     WHERE rolname = 'opsmind_context_resolver';
    IF resolver_super IS NULL THEN
        RAISE EXCEPTION 'required opsmind_context_resolver role is missing';
    END IF;
    IF resolver_super IS DISTINCT FROM false
       OR resolver_bypass IS DISTINCT FROM false
       OR resolver_login IS DISTINCT FROM false
       OR resolver_inherit IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'opsmind_context_resolver must be a non-login, non-owner, non-bypass role';
    END IF;
END
$$;

CREATE TABLE organizations (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug            varchar(64) NOT NULL,
    name            varchar(160) NOT NULL,
    status          varchar(24) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'suspended', 'deleted')),
    created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    version         bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (slug)
);

CREATE TABLE platform_users (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    issuer          varchar(1024) NOT NULL,
    subject         varchar(255) NOT NULL,
    email           varchar(320),
    display_name    varchar(255),
    status          varchar(24) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'suspended', 'deprovisioned')),
    last_seen_at    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (issuer, subject)
);

CREATE TABLE organization_memberships (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    user_id         uuid NOT NULL REFERENCES platform_users(id),
    role            varchar(64) NOT NULL,
    status          varchar(24) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'suspended', 'revoked')),
    created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    version         bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (organization_id, user_id),
    CHECK (length(trim(role)) > 0)
);

CREATE TABLE projects (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    slug            varchar(64) NOT NULL,
    name            varchar(160) NOT NULL,
    status          varchar(24) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'suspended', 'deleted')),
    created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    version         bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (organization_id, slug),
    UNIQUE (id, organization_id)
);

CREATE TABLE project_memberships (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    project_id      uuid NOT NULL,
    user_id         uuid NOT NULL REFERENCES platform_users(id),
    role            varchar(64) NOT NULL,
    status          varchar(24) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'suspended', 'revoked')),
    created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    version         bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (project_id, user_id),
    CHECK (length(trim(role)) > 0),
    FOREIGN KEY (project_id, organization_id) REFERENCES projects(id, organization_id)
);

CREATE TABLE environments (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id),
    project_id      uuid NOT NULL,
    slug            varchar(64) NOT NULL,
    name            varchar(160) NOT NULL,
    status          varchar(24) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'suspended', 'deleted')),
    created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    version         bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (project_id, slug),
    FOREIGN KEY (project_id, organization_id) REFERENCES projects(id, organization_id)
);

CREATE TABLE service_accounts (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    name                varchar(160) NOT NULL,
    credential_ref      varchar(512) NOT NULL,
    allowed_audiences   jsonb NOT NULL DEFAULT '[]'::jsonb,
    allowed_scopes      jsonb NOT NULL DEFAULT '[]'::jsonb,
    status              varchar(24) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'suspended', 'revoked')),
    created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    version             bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (jsonb_typeof(allowed_audiences) = 'array'),
    CHECK (jsonb_typeof(allowed_scopes) = 'array'),
    UNIQUE (organization_id, name)
);

CREATE TABLE idempotency_records (
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    idempotency_key     varchar(128) NOT NULL,
    actor_id            uuid REFERENCES platform_users(id),
    request_digest      bytea NOT NULL CHECK (octet_length(request_digest) = 32),
    status              varchar(24) NOT NULL CHECK (status IN ('in_progress', 'succeeded', 'failed')),
    response_status     integer CHECK (response_status BETWEEN 100 AND 599),
    response_body       jsonb,
    created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at        timestamptz,
    PRIMARY KEY (organization_id, idempotency_key)
);

CREATE TABLE outbox_events (
    event_id                uuid PRIMARY KEY,
    organization_id         uuid NOT NULL REFERENCES organizations(id),
    aggregate_type          varchar(128) NOT NULL,
    aggregate_id            uuid NOT NULL,
    aggregate_sequence      bigint NOT NULL CHECK (aggregate_sequence > 0),
    event_type              varchar(160) NOT NULL,
    schema_version          varchar(32) NOT NULL,
    causation_id             uuid,
    correlation_id           uuid NOT NULL,
    occurred_at              timestamptz NOT NULL,
    payload                 jsonb NOT NULL,
    payload_bytes           bytea NOT NULL CHECK (octet_length(payload_bytes) <= 1048576),
    payload_digest          bytea NOT NULL CHECK (octet_length(payload_digest) = 32),
    published_at             timestamptz,
    attempts                 integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error               varchar(1000),
    next_attempt_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    lease_token              uuid,
    lease_expires_at         timestamptz,
    poisoned_at              timestamptz,
    CHECK ((lease_token IS NULL) = (lease_expires_at IS NULL)),
    CHECK (published_at IS NULL OR
        (lease_token IS NULL AND lease_expires_at IS NULL AND poisoned_at IS NULL)),
    CHECK (poisoned_at IS NULL OR
        (published_at IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)),
    UNIQUE (organization_id, aggregate_type, aggregate_id, aggregate_sequence)
);

CREATE TABLE inbox_events (
    event_id                uuid NOT NULL,
    organization_id         uuid NOT NULL REFERENCES organizations(id),
    consumer                 varchar(160) NOT NULL,
    received_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
    processed_at             timestamptz,
    status                   varchar(24) NOT NULL DEFAULT 'received'
        CHECK (status IN ('received', 'processed', 'poisoned')),
    attempts                 integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error               varchar(1000),
    PRIMARY KEY (organization_id, event_id, consumer)
);

CREATE TABLE audit_events (
    sequence_no              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id                 uuid NOT NULL UNIQUE,
    organization_id          uuid NOT NULL REFERENCES organizations(id),
    actor_id                 uuid REFERENCES platform_users(id),
    action                   varchar(160) NOT NULL,
    resource_type            varchar(128) NOT NULL,
    resource_id              varchar(255) NOT NULL,
    correlation_id           uuid NOT NULL,
    occurred_at              timestamptz NOT NULL,
    payload                  jsonb NOT NULL DEFAULT '{}'::jsonb,
    previous_digest          bytea CHECK (previous_digest IS NULL OR octet_length(previous_digest) = 32),
    event_digest             bytea NOT NULL CHECK (octet_length(event_digest) = 32)
);

-- The API must resolve one verified issuer/subject pair without receiving
-- blanket read access to the global identity table. This definer is narrow,
-- schema-qualified, and revoked from PUBLIC below.
CREATE OR REPLACE FUNCTION opsmind_resolve_user(p_issuer varchar, p_subject varchar)
RETURNS TABLE(id uuid, status varchar)
LANGUAGE sql
SECURITY DEFINER
STABLE
SET search_path = pg_catalog, public, pg_temp AS $$
    SELECT user_row.id, user_row.status
      FROM public.platform_users user_row
     WHERE user_row.issuer = p_issuer
       AND user_row.subject = p_subject
$$;
ALTER FUNCTION public.opsmind_resolve_user(varchar, varchar) OWNER TO opsmind_context_resolver;

CREATE INDEX organization_memberships_user_idx
    ON organization_memberships (user_id, organization_id) WHERE status = 'active';
CREATE INDEX project_memberships_user_idx
    ON project_memberships (user_id, organization_id, project_id) WHERE status = 'active';
CREATE INDEX projects_organization_status_idx
    ON projects (organization_id, status, updated_at DESC);
CREATE INDEX environments_project_status_idx
    ON environments (organization_id, project_id, status, updated_at DESC);
CREATE INDEX outbox_dispatch_ready_idx
    ON outbox_events (organization_id, next_attempt_at, occurred_at, event_id)
    WHERE published_at IS NULL AND poisoned_at IS NULL;
CREATE INDEX inbox_pending_idx
    ON inbox_events (organization_id, consumer, received_at) WHERE processed_at IS NULL;
CREATE INDEX audit_organization_time_idx
    ON audit_events (organization_id, occurred_at DESC, sequence_no DESC);

-- A missing, malformed, or cross-tenant setting resolves to NULL. Policies
-- therefore deny rather than accidentally widening a pooled connection.
CREATE OR REPLACE FUNCTION opsmind_current_tenant_id() RETURNS uuid
LANGUAGE sql STABLE AS $$
    SELECT CASE
        WHEN current_setting('opsmind.tenant_id', true) ~
            '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$'
        THEN current_setting('opsmind.tenant_id', true)::uuid
        ELSE NULL
    END
$$;

CREATE OR REPLACE FUNCTION opsmind_set_tenant_context(p_tenant_id uuid, p_actor_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF p_tenant_id IS NULL OR p_actor_id IS NULL THEN
        RAISE EXCEPTION 'tenant and actor context are required';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM public.platform_users user_row
        JOIN public.organization_memberships membership
          ON membership.user_id = user_row.id
         AND membership.organization_id = p_tenant_id
         AND membership.status = 'active'
        JOIN public.organizations organization_row
          ON organization_row.id = membership.organization_id
         AND organization_row.status = 'active'
        WHERE user_row.id = p_actor_id
          AND user_row.status = 'active'
    ) THEN
        RAISE EXCEPTION 'active tenant membership is required';
    END IF;
    PERFORM set_config('opsmind.tenant_id', p_tenant_id::text, true);
    PERFORM set_config('opsmind.actor_id', p_actor_id::text, true);
END
$$;
ALTER FUNCTION public.opsmind_set_tenant_context(uuid, uuid) OWNER TO opsmind_context_resolver;

CREATE OR REPLACE FUNCTION opsmind_reject_audit_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only';
END
$$;

CREATE OR REPLACE FUNCTION opsmind_enforce_outbox_sequence() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    previous_sequence bigint;
    expected_sequence bigint;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(
        NEW.organization_id::text || ':' || NEW.aggregate_type || ':' || NEW.aggregate_id::text,
        0
    ));
    SELECT max(aggregate_sequence)
      INTO previous_sequence
      FROM public.outbox_events
     WHERE organization_id = NEW.organization_id
       AND aggregate_type = NEW.aggregate_type
       AND aggregate_id = NEW.aggregate_id;
    expected_sequence := CASE
        WHEN previous_sequence IS NULL THEN 1
        ELSE previous_sequence + 1
    END;
    IF NEW.aggregate_sequence IS DISTINCT FROM expected_sequence THEN
        RAISE EXCEPTION 'outbox aggregate sequence must be contiguous'
            USING ERRCODE = 'P3001';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER outbox_events_enforce_sequence
    BEFORE INSERT ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION opsmind_enforce_outbox_sequence();

CREATE TRIGGER audit_events_no_update
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION opsmind_reject_audit_mutation();

-- Force RLS even for the table owner. A superuser is intentionally outside the
-- runtime trust boundary and is reserved for controlled migrations/DR.
DO $$
DECLARE
    table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'organization_memberships', 'projects',
        'project_memberships', 'environments', 'service_accounts',
        'idempotency_records', 'outbox_events', 'inbox_events', 'audit_events'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        IF table_name = 'organization_memberships' THEN
            EXECUTE format(
                'CREATE POLICY %I ON %I USING (current_user = ''opsmind_context_resolver'' OR organization_id = opsmind_current_tenant_id()) WITH CHECK (current_user = ''opsmind_context_resolver'' OR organization_id = opsmind_current_tenant_id())',
                table_name || '_tenant_isolation', table_name
            );
        ELSE
            EXECUTE format(
                'CREATE POLICY %I ON %I USING (organization_id = opsmind_current_tenant_id()) WITH CHECK (organization_id = opsmind_current_tenant_id())',
                table_name || '_tenant_isolation', table_name
            );
        END IF;
    END LOOP;
END
$$;

ALTER TABLE organizations ENABLE ROW LEVEL SECURITY;
ALTER TABLE organizations FORCE ROW LEVEL SECURITY;
CREATE POLICY organizations_tenant_isolation ON organizations
    USING (current_user = 'opsmind_context_resolver' OR id = opsmind_current_tenant_id())
    WITH CHECK (current_user = 'opsmind_context_resolver' OR id = opsmind_current_tenant_id());

-- platform_users is looked up by issuer/subject before membership resolution;
-- it is not a tenant authority and therefore remains application-authorized.
COMMENT ON TABLE platform_users IS 'Global OIDC subject mapping; membership grants tenant authority.';
COMMENT ON COLUMN service_accounts.credential_ref IS 'Opaque secret-manager reference only; never a raw credential.';
COMMENT ON FUNCTION opsmind_current_tenant_id() IS 'Transaction-local tenant context used by every tenant-owned RLS policy.';

-- The runtime role is provisioned by deployment/bootstrap before this
-- migration runs. Keeping it separate from the migration owner is required:
-- an owner or superuser would bypass the RLS boundary even when policies are
-- syntactically present.
DO $$
DECLARE
    app_super boolean;
    app_bypass boolean;
    app_login boolean;
    app_inherit boolean;
BEGIN
    SELECT rolsuper, rolbypassrls, rolcanlogin, rolinherit
      INTO app_super, app_bypass, app_login, app_inherit
      FROM pg_roles
     WHERE rolname = 'opsmind_app';
    IF app_super IS NULL THEN
        RAISE EXCEPTION 'required non-owner runtime role opsmind_app is missing';
    END IF;
    IF app_super IS DISTINCT FROM false
       OR app_bypass IS DISTINCT FROM false
       OR app_login IS DISTINCT FROM true
       OR app_inherit IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'runtime role opsmind_app has unsafe attributes';
    END IF;
END
$$;

DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO opsmind_app', current_database());
END
$$;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO opsmind_app;
GRANT USAGE ON SCHEMA public TO opsmind_context_resolver;
GRANT SELECT ON organizations, platform_users, organization_memberships TO opsmind_context_resolver;
-- The web/runtime role is intentionally read-only for identity and membership
-- authority. Future domain migrations must grant only the operation they own;
-- do not turn this into a blanket CRUD role.
GRANT SELECT ON organizations, platform_users, organization_memberships,
    projects, project_memberships, environments, service_accounts TO opsmind_app;
GRANT SELECT, INSERT ON idempotency_records TO opsmind_app;
GRANT UPDATE (status, response_status, response_body, completed_at)
    ON idempotency_records TO opsmind_app;
GRANT SELECT, INSERT ON outbox_events TO opsmind_app;
GRANT UPDATE (published_at, attempts, last_error, next_attempt_at,
    lease_token, lease_expires_at, poisoned_at) ON outbox_events TO opsmind_app;
GRANT SELECT, INSERT ON inbox_events TO opsmind_app;
GRANT UPDATE (processed_at, status, attempts, last_error) ON inbox_events TO opsmind_app;
GRANT INSERT ON audit_events TO opsmind_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO opsmind_app;
REVOKE INSERT, UPDATE, DELETE ON organizations, platform_users,
    organization_memberships, project_memberships, service_accounts FROM opsmind_app;
REVOKE SELECT ON platform_users FROM opsmind_app;
REVOKE DELETE ON idempotency_records, outbox_events, inbox_events, audit_events FROM opsmind_app;
REVOKE UPDATE, DELETE ON audit_events FROM opsmind_app;
REVOKE ALL ON FUNCTION public.opsmind_current_tenant_id() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_set_tenant_context(uuid, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_resolve_user(varchar, varchar) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.opsmind_current_tenant_id() TO opsmind_app;
GRANT EXECUTE ON FUNCTION public.opsmind_current_tenant_id() TO opsmind_context_resolver;
GRANT EXECUTE ON FUNCTION public.opsmind_set_tenant_context(uuid, uuid) TO opsmind_app;
GRANT EXECUTE ON FUNCTION public.opsmind_resolve_user(varchar, varchar) TO opsmind_app;
