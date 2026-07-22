-- Authoritative incident aggregate, immutable timeline, and tenant audit chain.
--
-- Runtime writes remain bound to the transaction-local tenant and actor context
-- established by V001. The dispatcher receives no authority over these tables.

CREATE TABLE incidents (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    project_id          uuid NOT NULL,
    title               varchar(160) NOT NULL,
    description         varchar(4000) NOT NULL,
    severity            varchar(8) NOT NULL
        CHECK (severity IN ('SEV1', 'SEV2', 'SEV3', 'SEV4')),
    status              varchar(32) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN (
            'OPEN', 'INVESTIGATING', 'AWAITING_APPROVAL',
            'MITIGATING', 'RESOLVED', 'CLOSED'
        )),
    owner_id            uuid,
    root_cause          varchar(8000),
    resolution_summary varchar(8000),
    created_by          uuid NOT NULL,
    updated_by          uuid NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    version             bigint NOT NULL DEFAULT 0
        CHECK (version BETWEEN 0 AND 2147483647),
    UNIQUE (id, organization_id, project_id),
    FOREIGN KEY (project_id, organization_id)
        REFERENCES projects(id, organization_id),
    FOREIGN KEY (organization_id, owner_id)
        REFERENCES organization_memberships(organization_id, user_id),
    FOREIGN KEY (organization_id, created_by)
        REFERENCES organization_memberships(organization_id, user_id),
    FOREIGN KEY (organization_id, updated_by)
        REFERENCES organization_memberships(organization_id, user_id),
    CHECK (length(trim(title)) > 0),
    CHECK (length(trim(description)) > 0),
    CHECK (updated_at >= created_at),
    CHECK (
        (status IN ('RESOLVED', 'CLOSED')
            AND root_cause IS NOT NULL
            AND length(trim(root_cause)) > 0
            AND resolution_summary IS NOT NULL
            AND length(trim(resolution_summary)) > 0)
        OR
        (status NOT IN ('RESOLVED', 'CLOSED')
            AND root_cause IS NULL
            AND resolution_summary IS NULL)
    )
);

CREATE TABLE incident_timeline_events (
    event_id            uuid PRIMARY KEY,
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    project_id          uuid NOT NULL,
    incident_id         uuid NOT NULL,
    incident_version    bigint NOT NULL
        CHECK (incident_version BETWEEN 0 AND 2147483647),
    event_kind          varchar(64) NOT NULL
        CHECK (event_kind IN (
            'INCIDENT_CREATED', 'INCIDENT_STATUS_TRANSITIONED'
        )),
    actor_id            uuid NOT NULL,
    operation_id        uuid NOT NULL,
    external_trace_id   varchar(128),
    reason              varchar(1000) NOT NULL,
    payload             jsonb NOT NULL,
    occurred_at         timestamptz NOT NULL,
    UNIQUE (organization_id, incident_id, incident_version),
    FOREIGN KEY (incident_id, organization_id, project_id)
        REFERENCES incidents(id, organization_id, project_id),
    FOREIGN KEY (organization_id, actor_id)
        REFERENCES organization_memberships(organization_id, user_id),
    CHECK (length(trim(reason)) > 0),
    CHECK (jsonb_typeof(payload) = 'object'),
    CHECK (octet_length(convert_to(payload::text, 'UTF8')) <= 65536),
    CHECK (external_trace_id IS NULL OR
        external_trace_id ~ '^[A-Za-z0-9_-]{8,128}$')
);

CREATE INDEX incidents_organization_project_status_idx
    ON incidents (organization_id, project_id, status, updated_at DESC, id);
CREATE INDEX incidents_organization_project_id_idx
    ON incidents (organization_id, project_id, id);
CREATE INDEX incident_timeline_organization_incident_version_idx
    ON incident_timeline_events (
        organization_id, project_id, incident_id, incident_version
    );
CREATE UNIQUE INDEX incident_timeline_organization_operation_idx
    ON incident_timeline_events (organization_id, operation_id);

CREATE OR REPLACE FUNCTION opsmind_current_actor_id() RETURNS uuid
LANGUAGE sql STABLE AS $$
    SELECT CASE
        WHEN current_setting('opsmind.actor_id', true) ~
            '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$'
        THEN current_setting('opsmind.actor_id', true)::uuid
        ELSE NULL
    END
$$;

-- Resolve and lock the complete authorization tuple in one snapshot. The row
-- locks make a concurrent deprovision/suspension/role change serialize after an
-- already-authorized incident transaction instead of taking effect mid-write.
CREATE OR REPLACE FUNCTION opsmind_resolve_incident_access(
    p_issuer varchar,
    p_subject varchar,
    p_organization_id uuid,
    p_project_id uuid
) RETURNS TABLE (
    user_id uuid,
    user_status varchar,
    organization_status varchar,
    organization_membership_status varchar,
    organization_role varchar,
    project_status varchar,
    project_membership_status varchar,
    project_role varchar
)
LANGUAGE sql
SECURITY DEFINER
VOLATILE
SET search_path = pg_catalog, public, pg_temp AS $$
    SELECT user_row.id,
           user_row.status,
           organization_row.status,
           organization_membership.status,
           organization_membership.role,
           project_row.status,
           project_membership.status,
           project_membership.role
      FROM public.platform_users user_row
      JOIN public.organization_memberships organization_membership
        ON organization_membership.user_id = user_row.id
       AND organization_membership.organization_id = p_organization_id
      JOIN public.organizations organization_row
        ON organization_row.id = organization_membership.organization_id
      JOIN public.projects project_row
        ON project_row.id = p_project_id
       AND project_row.organization_id = organization_row.id
      JOIN public.project_memberships project_membership
        ON project_membership.organization_id = organization_row.id
       AND project_membership.project_id = project_row.id
       AND project_membership.user_id = user_row.id
     WHERE user_row.issuer = p_issuer
       AND user_row.subject = p_subject
     FOR SHARE OF user_row, organization_membership, organization_row,
         project_row, project_membership
$$;
ALTER FUNCTION public.opsmind_resolve_incident_access(varchar, varchar, uuid, uuid)
    OWNER TO opsmind_context_resolver;

-- PostgreSQL requires UPDATE privilege for rows selected with a locking clause.
-- This authority belongs only to the NOLOGIN resolver role; the runtime role can
-- invoke the narrow definer function but cannot update authority tables.
GRANT SELECT ON projects, project_memberships TO opsmind_context_resolver;
GRANT UPDATE (status) ON organizations, platform_users, organization_memberships,
    projects, project_memberships TO opsmind_context_resolver;
CREATE POLICY projects_incident_resolver_select ON projects
    FOR SELECT TO opsmind_context_resolver USING (true);
CREATE POLICY project_memberships_incident_resolver_select ON project_memberships
    FOR SELECT TO opsmind_context_resolver USING (true);

CREATE OR REPLACE FUNCTION opsmind_validate_incident_write() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    actor_id uuid := public.opsmind_current_actor_id();
BEGIN
    IF session_user = 'opsmind_app' THEN
        IF NEW.organization_id IS DISTINCT FROM public.opsmind_current_tenant_id()
           OR actor_id IS NULL THEN
            RAISE EXCEPTION 'incident write requires bound tenant and actor context'
                USING ERRCODE = '42501';
        END IF;
        IF TG_OP = 'INSERT' AND (
            NEW.created_by IS DISTINCT FROM actor_id
            OR NEW.updated_by IS DISTINCT FROM actor_id
        ) THEN
            RAISE EXCEPTION 'incident creator must match the bound actor'
                USING ERRCODE = '42501';
        END IF;
        IF TG_OP = 'UPDATE' AND NEW.updated_by IS DISTINCT FROM actor_id THEN
            RAISE EXCEPTION 'incident updater must match the bound actor'
                USING ERRCODE = '42501';
        END IF;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status IS DISTINCT FROM 'OPEN'
           OR NEW.version IS DISTINCT FROM 0
           OR NEW.updated_by IS DISTINCT FROM NEW.created_by
           OR NEW.root_cause IS NOT NULL
           OR NEW.resolution_summary IS NOT NULL THEN
            RAISE EXCEPTION 'incident must start OPEN at version zero without resolution'
                USING ERRCODE = 'P4001';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
       OR NEW.project_id IS DISTINCT FROM OLD.project_id
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'incident identity fields are immutable'
            USING ERRCODE = 'P4001';
    END IF;
    IF NEW.version IS DISTINCT FROM OLD.version + 1 THEN
        RAISE EXCEPTION 'incident version must increase by exactly one'
            USING ERRCODE = 'P4002';
    END IF;
    IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
        RAISE EXCEPTION 'incident update must perform an explicit status transition'
            USING ERRCODE = 'P4003';
    END IF;
    IF OLD.status = 'CLOSED' OR NOT (
        (OLD.status = 'OPEN' AND NEW.status = 'INVESTIGATING')
        OR (OLD.status = 'INVESTIGATING' AND NEW.status IN (
            'AWAITING_APPROVAL', 'MITIGATING', 'RESOLVED'
        ))
        OR (OLD.status = 'AWAITING_APPROVAL' AND NEW.status IN (
            'INVESTIGATING', 'MITIGATING'
        ))
        OR (OLD.status = 'MITIGATING' AND NEW.status IN (
            'INVESTIGATING', 'RESOLVED'
        ))
        OR (OLD.status = 'RESOLVED' AND NEW.status IN (
            'INVESTIGATING', 'CLOSED'
        ))
    ) THEN
        RAISE EXCEPTION 'illegal incident status transition from % to %',
            OLD.status, NEW.status
            USING ERRCODE = 'P4003';
    END IF;
    IF OLD.status = 'RESOLVED' AND NEW.status = 'INVESTIGATING'
       AND (NEW.root_cause IS NOT NULL OR NEW.resolution_summary IS NOT NULL) THEN
        RAISE EXCEPTION 'reopening an incident must clear the current resolution'
            USING ERRCODE = 'P4003';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION opsmind_validate_timeline_append() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    incident_row record;
    prior_status varchar;
    actor_id uuid := public.opsmind_current_actor_id();
BEGIN
    IF session_user = 'opsmind_app' AND (
        NEW.organization_id IS DISTINCT FROM public.opsmind_current_tenant_id()
        OR NEW.actor_id IS DISTINCT FROM actor_id
    ) THEN
        RAISE EXCEPTION 'timeline append requires the bound tenant and actor'
            USING ERRCODE = '42501';
    END IF;

    SELECT stored.version, stored.status, stored.root_cause,
           stored.resolution_summary, stored.updated_by, stored.updated_at
      INTO incident_row
      FROM public.incidents stored
     WHERE stored.id = NEW.incident_id
       AND stored.organization_id = NEW.organization_id
       AND stored.project_id = NEW.project_id;
    IF NOT FOUND
       OR NEW.incident_version IS DISTINCT FROM incident_row.version THEN
        RAISE EXCEPTION 'timeline version must match the current incident version'
            USING ERRCODE = 'P4004';
    END IF;
    IF (NEW.incident_version = 0 AND NEW.event_kind <> 'INCIDENT_CREATED')
       OR (NEW.incident_version > 0
            AND NEW.event_kind <> 'INCIDENT_STATUS_TRANSITIONED') THEN
        RAISE EXCEPTION 'timeline event kind does not match incident version'
            USING ERRCODE = 'P4004';
    END IF;

    IF NEW.incident_version = 0 THEN
        prior_status := NULL;
    ELSE
        SELECT prior.payload ->> 'toStatus'
          INTO prior_status
          FROM public.incident_timeline_events prior
         WHERE prior.organization_id = NEW.organization_id
           AND prior.project_id = NEW.project_id
           AND prior.incident_id = NEW.incident_id
           AND prior.incident_version = NEW.incident_version - 1;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'timeline append requires the preceding incident version'
                USING ERRCODE = 'P4004';
        END IF;
    END IF;

    IF NEW.actor_id IS DISTINCT FROM incident_row.updated_by
       OR NEW.occurred_at IS DISTINCT FROM incident_row.updated_at
       OR NOT NEW.payload ?& ARRAY[
           'eventId', 'organizationId', 'projectId', 'incidentId',
           'incidentVersion', 'eventType', 'actorId', 'operationId',
           'occurredAt', 'reason', 'fromStatus', 'toStatus',
           'rootCause', 'resolutionSummary'
       ]
       OR NEW.payload - ARRAY[
           'eventId', 'organizationId', 'projectId', 'incidentId',
           'incidentVersion', 'eventType', 'actorId', 'operationId',
           'occurredAt', 'reason', 'fromStatus', 'toStatus',
           'rootCause', 'resolutionSummary'
       ] <> '{}'::jsonb
       OR jsonb_typeof(NEW.payload -> 'incidentVersion') <> 'number'
       OR jsonb_typeof(NEW.payload -> 'occurredAt') <> 'string'
       OR NEW.payload ->> 'eventId' IS DISTINCT FROM NEW.event_id::text
       OR NEW.payload ->> 'organizationId' IS DISTINCT FROM NEW.organization_id::text
       OR NEW.payload ->> 'projectId' IS DISTINCT FROM NEW.project_id::text
       OR NEW.payload ->> 'incidentId' IS DISTINCT FROM NEW.incident_id::text
       OR (NEW.payload ->> 'incidentVersion')::bigint IS DISTINCT FROM NEW.incident_version
       OR NEW.payload ->> 'eventType' IS DISTINCT FROM NEW.event_kind
       OR NEW.payload ->> 'actorId' IS DISTINCT FROM NEW.actor_id::text
       OR NEW.payload ->> 'operationId' IS DISTINCT FROM NEW.operation_id::text
       OR (NEW.payload ->> 'occurredAt')::timestamptz IS DISTINCT FROM NEW.occurred_at
       OR NEW.payload ->> 'reason' IS DISTINCT FROM NEW.reason
       OR NEW.payload ->> 'fromStatus' IS DISTINCT FROM prior_status
       OR NEW.payload ->> 'toStatus' IS DISTINCT FROM incident_row.status
       OR NEW.payload ->> 'rootCause' IS DISTINCT FROM incident_row.root_cause
       OR NEW.payload ->> 'resolutionSummary' IS DISTINCT FROM incident_row.resolution_summary THEN
        RAISE EXCEPTION 'timeline payload does not match the authoritative incident event'
            USING ERRCODE = 'P4005';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER incidents_validate_write
    BEFORE INSERT OR UPDATE ON incidents
    FOR EACH ROW EXECUTE FUNCTION opsmind_validate_incident_write();

CREATE TRIGGER incident_timeline_validate_append
    BEFORE INSERT ON incident_timeline_events
    FOR EACH ROW EXECUTE FUNCTION opsmind_validate_timeline_append();

-- Reuse the V001 append-only guard and extend it to timeline rows and TRUNCATE.
CREATE OR REPLACE FUNCTION opsmind_reject_audit_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME
        USING ERRCODE = '42501';
END
$$;

CREATE TRIGGER incident_timeline_no_update
    BEFORE UPDATE OR DELETE ON incident_timeline_events
    FOR EACH ROW EXECUTE FUNCTION opsmind_reject_audit_mutation();
CREATE TRIGGER incident_timeline_no_truncate
    BEFORE TRUNCATE ON incident_timeline_events
    FOR EACH STATEMENT EXECUTE FUNCTION opsmind_reject_audit_mutation();
CREATE TRIGGER audit_events_no_truncate
    BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION opsmind_reject_audit_mutation();

-- Upgrade caller-supplied V001 audit digests into a database-computed chain.
ALTER TABLE audit_events
    ADD COLUMN tenant_sequence_no bigint,
    ADD COLUMN schema_version varchar(64);

CREATE OR REPLACE FUNCTION opsmind_compute_audit_digest(
    p_tenant_sequence_no bigint,
    p_schema_version varchar,
    p_event_id uuid,
    p_organization_id uuid,
    p_actor_id uuid,
    p_action varchar,
    p_resource_type varchar,
    p_resource_id varchar,
    p_correlation_id uuid,
    p_occurred_at timestamptz,
    p_payload jsonb,
    p_previous_digest bytea
) RETURNS bytea
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog, public, pg_temp AS $$
    SELECT public.digest(
        convert_to(
            jsonb_build_object(
                'tenantSequenceNo', p_tenant_sequence_no,
                'schemaVersion', p_schema_version,
                'eventId', p_event_id,
                'organizationId', p_organization_id,
                'actorId', p_actor_id,
                'action', p_action,
                'resourceType', p_resource_type,
                'resourceId', p_resource_id,
                'correlationId', p_correlation_id,
                'occurredAtEpochMicros',
                    round(extract(epoch FROM p_occurred_at) * 1000000),
                'payload', p_payload,
                'previousDigest', CASE
                    WHEN p_previous_digest IS NULL THEN NULL
                    ELSE encode(p_previous_digest, 'hex')
                END
            )::text,
            'UTF8'
        ),
        'sha256'
    )
$$;

-- The dedicated migration owner must re-chain existing rows even though V001
-- forced owner RLS. This transactional DDL lock temporarily restores the
-- ordinary owner bypass, then FORCE RLS is reinstated before V003 can commit.
ALTER TABLE audit_events NO FORCE ROW LEVEL SECURITY;
ALTER TABLE audit_events DISABLE TRIGGER audit_events_no_update;
UPDATE audit_events
   SET schema_version = 'legacy-v1'
 WHERE schema_version IS NULL;
DO $$
DECLARE
    audit_row record;
    current_organization uuid;
    next_tenant_sequence bigint := 0;
    chain_previous_digest bytea;
BEGIN
    FOR audit_row IN
        SELECT sequence_no, schema_version, event_id, organization_id, actor_id,
               action, resource_type, resource_id, correlation_id, occurred_at, payload
          FROM public.audit_events
         ORDER BY organization_id, occurred_at, sequence_no
    LOOP
        IF current_organization IS DISTINCT FROM audit_row.organization_id THEN
            current_organization := audit_row.organization_id;
            next_tenant_sequence := 0;
            chain_previous_digest := NULL;
        END IF;
        next_tenant_sequence := next_tenant_sequence + 1;

        UPDATE public.audit_events event_row
           SET tenant_sequence_no = next_tenant_sequence,
               previous_digest = chain_previous_digest,
               event_digest = public.opsmind_compute_audit_digest(
                   next_tenant_sequence,
                   event_row.schema_version,
                   event_row.event_id,
                   event_row.organization_id,
                   event_row.actor_id,
                   event_row.action,
                   event_row.resource_type,
                   event_row.resource_id,
                   event_row.correlation_id,
                   event_row.occurred_at,
                   event_row.payload,
                   chain_previous_digest
               )
         WHERE event_row.sequence_no = audit_row.sequence_no
         RETURNING event_row.event_digest INTO chain_previous_digest;
    END LOOP;
END
$$;
ALTER TABLE audit_events ENABLE TRIGGER audit_events_no_update;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM (
              SELECT organization_id, tenant_sequence_no, previous_digest,
                     lag(event_digest) OVER (
                         PARTITION BY organization_id ORDER BY tenant_sequence_no
                     ) AS expected_previous
                FROM public.audit_events
          ) chained
         WHERE tenant_sequence_no IS NULL
            OR previous_digest IS DISTINCT FROM expected_previous
    ) OR EXISTS (
        SELECT 1
          FROM public.audit_events event_row
         WHERE event_row.event_digest IS DISTINCT FROM
             public.opsmind_compute_audit_digest(
                 event_row.tenant_sequence_no,
                 event_row.schema_version,
                 event_row.event_id,
                 event_row.organization_id,
                 event_row.actor_id,
                 event_row.action,
                 event_row.resource_type,
                 event_row.resource_id,
                 event_row.correlation_id,
                 event_row.occurred_at,
                 event_row.payload,
                 event_row.previous_digest
             )
    ) THEN
        RAISE EXCEPTION 'audit backfill did not produce a linear recomputable chain';
    END IF;
END
$$;
ALTER TABLE audit_events FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_catalog.pg_class
         WHERE oid = 'public.audit_events'::regclass
           AND relrowsecurity
           AND relforcerowsecurity
    ) THEN
        RAISE EXCEPTION 'audit_events FORCE RLS was not restored';
    END IF;
END
$$;

ALTER TABLE audit_events
    ALTER COLUMN tenant_sequence_no SET NOT NULL,
    ALTER COLUMN schema_version SET NOT NULL,
    ADD CONSTRAINT audit_events_tenant_sequence_positive
        CHECK (tenant_sequence_no > 0),
    ADD CONSTRAINT audit_events_organization_tenant_sequence_unique
        UNIQUE (organization_id, tenant_sequence_no),
    ADD CONSTRAINT audit_events_schema_version_known
        CHECK (schema_version IN ('legacy-v1', 'incident-audit-v1')),
    ADD CONSTRAINT audit_events_payload_object
        CHECK (jsonb_typeof(payload) = 'object'),
    ADD CONSTRAINT audit_events_incident_contract
        CHECK (
            schema_version <> 'incident-audit-v1'
            OR (
                actor_id IS NOT NULL
                AND action IN ('INCIDENT_CREATED', 'INCIDENT_STATUS_TRANSITIONED')
                AND resource_type = 'incident'
                AND payload ?& ARRAY[
                    'eventId', 'organizationId', 'projectId', 'incidentId',
                    'incidentVersion', 'eventType', 'actorId', 'operationId',
                    'occurredAt', 'reason', 'fromStatus', 'toStatus',
                    'rootCause', 'resolutionSummary'
                ]
                AND payload ->> 'eventId' = event_id::text
                AND payload ->> 'organizationId' = organization_id::text
                AND payload ->> 'incidentId' = resource_id
                AND payload ->> 'eventType' = action
                AND payload ->> 'actorId' = actor_id::text
                AND payload ->> 'operationId' = correlation_id::text
            )
        );

CREATE INDEX audit_events_organization_tenant_sequence_idx
    ON audit_events (organization_id, tenant_sequence_no DESC);

CREATE OR REPLACE FUNCTION opsmind_assign_audit_chain() RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    prior_sequence bigint;
    prior_digest bytea;
    timeline_row record;
    actor_id uuid := public.opsmind_current_actor_id();
BEGIN
    IF session_user = 'opsmind_app' THEN
        IF NEW.organization_id IS DISTINCT FROM public.opsmind_current_tenant_id()
           OR NEW.actor_id IS DISTINCT FROM actor_id
           OR NEW.schema_version IS DISTINCT FROM 'incident-audit-v1' THEN
            RAISE EXCEPTION 'audit append requires the bound tenant, actor, and schema'
                USING ERRCODE = '42501';
        END IF;
    END IF;

    IF NEW.schema_version = 'incident-audit-v1' THEN
        SELECT timeline.event_kind, timeline.actor_id, timeline.incident_id,
               timeline.operation_id, timeline.occurred_at, timeline.payload
          INTO timeline_row
          FROM public.incident_timeline_events timeline
         WHERE timeline.event_id = NEW.event_id
           AND timeline.organization_id = NEW.organization_id;
        IF NOT FOUND
           OR NEW.action IS DISTINCT FROM timeline_row.event_kind
           OR NEW.actor_id IS DISTINCT FROM timeline_row.actor_id
           OR NEW.resource_type IS DISTINCT FROM 'incident'
           OR NEW.resource_id IS DISTINCT FROM timeline_row.incident_id::text
           OR NEW.correlation_id IS DISTINCT FROM timeline_row.operation_id
           OR NEW.occurred_at IS DISTINCT FROM timeline_row.occurred_at
           OR NEW.payload IS DISTINCT FROM timeline_row.payload THEN
            RAISE EXCEPTION 'incident audit payload must match its authoritative timeline event'
                USING ERRCODE = 'P4005';
        END IF;
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.organization_id::text, 0));
    SELECT event_row.tenant_sequence_no, event_row.event_digest
      INTO prior_sequence, prior_digest
      FROM public.audit_events event_row
     WHERE event_row.organization_id = NEW.organization_id
     ORDER BY event_row.tenant_sequence_no DESC
     LIMIT 1;

    NEW.tenant_sequence_no := coalesce(prior_sequence, 0) + 1;
    NEW.previous_digest := prior_digest;
    NEW.event_digest := public.opsmind_compute_audit_digest(
        NEW.tenant_sequence_no,
        NEW.schema_version,
        NEW.event_id,
        NEW.organization_id,
        NEW.actor_id,
        NEW.action,
        NEW.resource_type,
        NEW.resource_id,
        NEW.correlation_id,
        NEW.occurred_at,
        NEW.payload,
        NEW.previous_digest
    );
    RETURN NEW;
END
$$;

CREATE TRIGGER audit_events_assign_chain
    BEFORE INSERT ON audit_events
    FOR EACH ROW EXECUTE FUNCTION opsmind_assign_audit_chain();

ALTER TABLE incidents ENABLE ROW LEVEL SECURITY;
ALTER TABLE incidents FORCE ROW LEVEL SECURITY;
CREATE POLICY incidents_tenant_isolation ON incidents
    USING (organization_id = opsmind_current_tenant_id())
    WITH CHECK (organization_id = opsmind_current_tenant_id());

ALTER TABLE incident_timeline_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE incident_timeline_events FORCE ROW LEVEL SECURITY;
CREATE POLICY incident_timeline_events_tenant_isolation
    ON incident_timeline_events
    USING (organization_id = opsmind_current_tenant_id())
    WITH CHECK (organization_id = opsmind_current_tenant_id());

GRANT SELECT, INSERT ON incidents TO opsmind_app;
GRANT UPDATE (status, root_cause, resolution_summary, updated_by, updated_at, version)
    ON incidents TO opsmind_app;
GRANT SELECT, INSERT ON incident_timeline_events TO opsmind_app;

-- V001's table-level INSERT could be combined with OVERRIDING SYSTEM VALUE
-- to choose the global identity input. Limit runtime appends to trusted
-- business columns; the trigger alone owns every chain field.
REVOKE INSERT ON audit_events FROM opsmind_app;
GRANT INSERT (
    event_id, organization_id, actor_id, action, schema_version, resource_type,
    resource_id, correlation_id, occurred_at, payload
) ON audit_events TO opsmind_app;

REVOKE DELETE, TRUNCATE ON incidents, incident_timeline_events FROM opsmind_app;
REVOKE UPDATE, DELETE, TRUNCATE ON incident_timeline_events FROM opsmind_app;
REVOKE ALL ON incidents, incident_timeline_events FROM opsmind_dispatcher;
REVOKE TRUNCATE ON audit_events FROM opsmind_app, opsmind_dispatcher;

REVOKE ALL ON FUNCTION public.opsmind_current_actor_id() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_resolve_incident_access(
    varchar, varchar, uuid, uuid
) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_compute_audit_digest(
    bigint, varchar, uuid, uuid, uuid, varchar, varchar, varchar,
    uuid, timestamptz, jsonb, bytea
) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_assign_audit_chain() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.opsmind_current_actor_id() TO opsmind_app;
GRANT EXECUTE ON FUNCTION public.opsmind_resolve_incident_access(
    varchar, varchar, uuid, uuid
) TO opsmind_app;

COMMENT ON TABLE incidents IS
    'Tenant-owned incident aggregate; mutations use optimistic version transitions.';
COMMENT ON TABLE incident_timeline_events IS
    'Immutable incident history ordered by aggregate version.';
COMMENT ON COLUMN audit_events.tenant_sequence_no IS
    'Database-assigned contiguous sequence within one organization audit chain.';
COMMENT ON FUNCTION opsmind_compute_audit_digest(
    bigint, varchar, uuid, uuid, uuid, varchar, varchar, varchar,
    uuid, timestamptz, jsonb, bytea
) IS 'Schema-versioned SHA-256 over tenant-visible audit fields and the previous tenant digest.';
