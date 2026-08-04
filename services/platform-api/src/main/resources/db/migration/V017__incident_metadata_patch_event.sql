-- Authoritative incident metadata patching and owner assignment.

CREATE OR REPLACE FUNCTION opsmind_lock_eligible_incident_owner(
    p_organization_id uuid,
    p_owner_id uuid
) RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF session_user <> 'opsmind_app' THEN
        RAISE EXCEPTION 'application identity is required for incident owner assignment'
            USING ERRCODE = '42501';
    END IF;
    IF p_organization_id IS NULL OR p_owner_id IS NULL THEN
        RAISE EXCEPTION 'incident owner assignment requires organization and owner identity'
            USING ERRCODE = '22023';
    END IF;

    PERFORM member.id
      FROM public.platform_users member
      JOIN public.organization_memberships membership
        ON membership.user_id = member.id
       AND membership.organization_id = p_organization_id
     WHERE member.id = p_owner_id
       AND member.status = 'active'
       AND membership.status = 'active'
     FOR SHARE OF member, membership;
    RETURN FOUND;
END
$$;
ALTER FUNCTION public.opsmind_lock_eligible_incident_owner(uuid, uuid)
    OWNER TO opsmind_context_resolver;
REVOKE ALL ON FUNCTION public.opsmind_lock_eligible_incident_owner(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.opsmind_lock_eligible_incident_owner(uuid, uuid)
    TO opsmind_app;

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
           OR NEW.owner_id IS NOT NULL
           OR NEW.root_cause IS NOT NULL
           OR NEW.resolution_summary IS NOT NULL THEN
            RAISE EXCEPTION 'incident must start OPEN and unassigned at version zero without resolution'
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
        IF NEW.root_cause IS DISTINCT FROM OLD.root_cause
           OR NEW.resolution_summary IS DISTINCT FROM OLD.resolution_summary THEN
            RAISE EXCEPTION 'metadata patch cannot change incident resolution fields'
                USING ERRCODE = 'P4003';
        END IF;
        IF NEW.owner_id IS DISTINCT FROM OLD.owner_id
           AND NEW.owner_id IS NOT NULL
           AND NOT public.opsmind_lock_eligible_incident_owner(
               NEW.organization_id, NEW.owner_id
           ) THEN
            RAISE EXCEPTION 'incident owner must be an active organization member'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.title IS DISTINCT FROM OLD.title
       OR NEW.description IS DISTINCT FROM OLD.description
       OR NEW.severity IS DISTINCT FROM OLD.severity
       OR NEW.owner_id IS DISTINCT FROM OLD.owner_id THEN
        RAISE EXCEPTION 'status transition cannot change incident metadata'
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

GRANT UPDATE (title, description, severity, owner_id) ON incidents TO opsmind_app;

ALTER TABLE incident_timeline_events
    DROP CONSTRAINT incident_timeline_events_event_kind_check;
ALTER TABLE incident_timeline_events
    ADD CONSTRAINT incident_timeline_events_event_kind_check
    CHECK (event_kind IN (
        'INCIDENT_CREATED',
        'INCIDENT_STATUS_TRANSITIONED',
        'INCIDENT_METADATA_PATCHED'
    ));

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

    SELECT stored.version, stored.status, stored.title, stored.description,
           stored.severity, stored.owner_id, stored.root_cause,
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
       OR (NEW.incident_version > 0 AND NEW.event_kind NOT IN (
            'INCIDENT_STATUS_TRANSITIONED', 'INCIDENT_METADATA_PATCHED'
       )) THEN
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
            'rootCause', 'resolutionSummary', 'metadata'
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

    IF NEW.event_kind = 'INCIDENT_METADATA_PATCHED' THEN
        IF jsonb_typeof(NEW.payload -> 'metadata') <> 'object'
           OR NOT (NEW.payload -> 'metadata') ?& ARRAY[
                'title', 'summary', 'severity', 'ownerId'
           ]
           OR (NEW.payload -> 'metadata') - ARRAY[
                'title', 'summary', 'severity', 'ownerId'
           ] <> '{}'::jsonb
           OR NEW.payload #>> '{metadata,title}' IS DISTINCT FROM incident_row.title
           OR NEW.payload #>> '{metadata,summary}' IS DISTINCT FROM incident_row.description
           OR NEW.payload #>> '{metadata,severity}' IS DISTINCT FROM incident_row.severity
           OR NEW.payload #>> '{metadata,ownerId}' IS DISTINCT FROM incident_row.owner_id::text THEN
            RAISE EXCEPTION 'timeline metadata does not match the authoritative incident'
                USING ERRCODE = 'P4005';
        END IF;
    ELSIF NEW.payload ? 'metadata' THEN
        RAISE EXCEPTION 'non-metadata timeline events cannot carry metadata'
            USING ERRCODE = 'P4005';
    END IF;
    RETURN NEW;
END
$$;

ALTER TABLE audit_events DROP CONSTRAINT audit_events_incident_contract;
ALTER TABLE audit_events
    ADD CONSTRAINT audit_events_incident_contract
    CHECK (
        schema_version <> 'incident-audit-v1'
        OR (
            actor_id IS NOT NULL
            AND action IN (
                'INCIDENT_CREATED',
                'INCIDENT_STATUS_TRANSITIONED',
                'INCIDENT_METADATA_PATCHED'
            )
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
            AND (
                action <> 'INCIDENT_METADATA_PATCHED'
                OR jsonb_typeof(payload -> 'metadata') = 'object'
            )
        )
    );
