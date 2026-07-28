-- Durable, tenant-scoped handoff from an investigation run to a Temporal
-- workflow.  The binding is immutable until the dedicated dispatcher
-- reconciles one PENDING row to STARTED or REJECTED.

CREATE OR REPLACE FUNCTION opsmind_investigation_workflow_start_event_id(
    p_organization_id uuid,
    p_run_id uuid
) RETURNS uuid
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
SET search_path = pg_catalog, public, pg_temp AS $$
    WITH raw AS (
        SELECT decode(md5(
            'investigation.workflow-start.requested:'
            || p_organization_id::text || ':' || p_run_id::text
        ), 'hex') AS bytes
    ),
    versioned AS (
        SELECT set_byte(
            set_byte(bytes, 6, (get_byte(bytes, 6) & 15) | 48),
            8, (get_byte(bytes, 8) & 63) | 128
        ) AS bytes
          FROM raw
    ),
    hex AS (
        SELECT encode(bytes, 'hex') AS value
          FROM versioned
    )
    SELECT format(
        '%s-%s-%s-%s-%s',
        substr(value, 1, 8),
        substr(value, 9, 4),
        substr(value, 13, 4),
        substr(value, 17, 4),
        substr(value, 21, 12)
    )::uuid
      FROM hex
$$;

CREATE TABLE investigation_workflow_bindings (
    organization_id       uuid NOT NULL,
    run_id                uuid NOT NULL,
    project_id            uuid NOT NULL,
    incident_id           uuid NOT NULL,
    actor_id              uuid NOT NULL,
    client_request_digest bytea NOT NULL
        CHECK (octet_length(client_request_digest) = 32),
    start_payload_digest  bytea NOT NULL
        CHECK (octet_length(start_payload_digest) = 32),
    start_event_id        uuid NOT NULL UNIQUE,
    temporal_cluster_id   varchar(128) NOT NULL
        CHECK (temporal_cluster_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'),
    temporal_namespace    varchar(255) NOT NULL
        CHECK (length(trim(temporal_namespace)) BETWEEN 1 AND 255),
    workflow_id           varchar(255) NOT NULL,
    workflow_type         varchar(128) NOT NULL
        CHECK (length(trim(workflow_type)) BETWEEN 1 AND 128),
    task_queue             varchar(255) NOT NULL
        CHECK (length(trim(task_queue)) BETWEEN 1 AND 255),
    authorization_revision bigint NOT NULL CHECK (authorization_revision >= 0),
    status                 varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'STARTED', 'REJECTED')),
    temporal_run_id        varchar(255),
    rejection_code         varchar(128),
    started_at             timestamptz NOT NULL,
    deadline_at            timestamptz NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at             timestamptz NOT NULL DEFAULT clock_timestamp(),
    temporal_started_at    timestamptz,
    rejected_at            timestamptz,
    PRIMARY KEY (organization_id, run_id),
    FOREIGN KEY (organization_id, run_id)
        REFERENCES investigation_runs (organization_id, run_id),
    FOREIGN KEY (incident_id, organization_id, project_id)
        REFERENCES incidents (id, organization_id, project_id),
    FOREIGN KEY (project_id, organization_id)
        REFERENCES projects (id, organization_id),
    FOREIGN KEY (organization_id, actor_id)
        REFERENCES organization_memberships (organization_id, user_id),
    CHECK (deadline_at > started_at),
    CHECK (updated_at >= created_at),
    CHECK (workflow_id =
        'opsmind-investigation/' || organization_id::text || '/' || run_id::text),
    CHECK (rejection_code IS NULL OR
        rejection_code ~ '^[a-z0-9][a-z0-9._-]{0,127}$'),
    CHECK (
        (status = 'PENDING'
            AND temporal_run_id IS NULL
            AND rejection_code IS NULL
            AND temporal_started_at IS NULL
            AND rejected_at IS NULL)
        OR
        (status = 'STARTED'
            AND temporal_run_id IS NOT NULL
            AND length(trim(temporal_run_id)) BETWEEN 1 AND 255
            AND rejection_code IS NULL
            AND temporal_started_at IS NOT NULL
            AND rejected_at IS NULL)
        OR
        (status = 'REJECTED'
            AND temporal_run_id IS NULL
            AND rejection_code IS NOT NULL
            AND temporal_started_at IS NULL
            AND rejected_at IS NOT NULL)
    ),
    CHECK (temporal_started_at IS NULL OR temporal_started_at >= created_at),
    CHECK (rejected_at IS NULL OR rejected_at >= created_at)
);

CREATE UNIQUE INDEX investigation_workflow_bindings_temporal_target_idx
    ON investigation_workflow_bindings (
        temporal_cluster_id, temporal_namespace, workflow_id
    );
CREATE INDEX investigation_workflow_bindings_status_idx
    ON investigation_workflow_bindings (organization_id, status, updated_at, run_id);

CREATE OR REPLACE FUNCTION opsmind_validate_investigation_workflow_binding()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    run_row record;
    actor_id uuid;
BEGIN
    IF TG_OP = 'INSERT' THEN
        actor_id := public.opsmind_current_actor_id();
        IF session_user = 'opsmind_app'
           AND (
               NEW.organization_id IS DISTINCT FROM public.opsmind_current_tenant_id()
               OR NEW.actor_id IS DISTINCT FROM actor_id
           ) THEN
            RAISE EXCEPTION 'workflow binding insert requires the bound tenant and actor'
                USING ERRCODE = '42501';
        END IF;

        SELECT stored.organization_id, stored.project_id, stored.incident_id,
               stored.actor_id, stored.status, stored.revision, stored.event_count,
               stored.rounds, stored.tool_calls, stored.total_tokens,
               stored.requested_fingerprints_state, stored.evidence_ids_state,
               stored.pending_intents_state, stored.final_response,
               stored.terminal_reason, stored.started_at, stored.deadline_at,
               stored.ended_at
          INTO run_row
          FROM public.investigation_runs stored
         WHERE stored.organization_id = NEW.organization_id
           AND stored.run_id = NEW.run_id
         FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'workflow binding requires an authoritative investigation run'
                USING ERRCODE = 'P7006';
        END IF;
        IF NEW.project_id IS DISTINCT FROM run_row.project_id
           OR NEW.incident_id IS DISTINCT FROM run_row.incident_id
           OR NEW.actor_id IS DISTINCT FROM run_row.actor_id
           OR NEW.started_at IS DISTINCT FROM run_row.started_at
           OR NEW.deadline_at IS DISTINCT FROM run_row.deadline_at THEN
            RAISE EXCEPTION 'workflow binding identities and time bounds must match the authoritative run'
                USING ERRCODE = 'P7006';
        END IF;
        IF NEW.authorization_revision IS DISTINCT FROM (
            SELECT incident.version
              FROM public.incidents incident
             WHERE incident.id = NEW.incident_id
               AND incident.organization_id = NEW.organization_id
               AND incident.project_id = NEW.project_id
        ) THEN
            RAISE EXCEPTION 'workflow authorization revision must match the incident snapshot'
                USING ERRCODE = 'P7006';
        END IF;
        IF run_row.status IS DISTINCT FROM 'CREATED'
           OR run_row.revision IS DISTINCT FROM 0
           OR run_row.event_count IS DISTINCT FROM 1
           OR run_row.rounds IS DISTINCT FROM 0
           OR run_row.tool_calls IS DISTINCT FROM 0
           OR run_row.total_tokens IS DISTINCT FROM 0
           OR run_row.requested_fingerprints_state IS DISTINCT FROM '[]'::jsonb
           OR run_row.evidence_ids_state IS DISTINCT FROM '[]'::jsonb
           OR run_row.pending_intents_state IS DISTINCT FROM '[]'::jsonb
           OR run_row.final_response IS NOT NULL
           OR run_row.terminal_reason IS NOT NULL
           OR run_row.ended_at IS NOT NULL THEN
            RAISE EXCEPTION 'workflow binding requires the initial CREATED reducer state'
                USING ERRCODE = 'P7006';
        END IF;
        IF NEW.status IS DISTINCT FROM 'PENDING'
           OR NEW.temporal_run_id IS NOT NULL
           OR NEW.rejection_code IS NOT NULL
           OR NEW.temporal_started_at IS NOT NULL
           OR NEW.rejected_at IS NOT NULL THEN
            RAISE EXCEPTION 'workflow binding must be inserted in PENDING state'
                USING ERRCODE = 'P7006';
        END IF;
        IF NEW.workflow_id IS DISTINCT FROM
               'opsmind-investigation/' || NEW.organization_id::text || '/' || NEW.run_id::text
           OR NEW.start_event_id IS DISTINCT FROM
               public.opsmind_investigation_workflow_start_event_id(
                   NEW.organization_id, NEW.run_id
               ) THEN
            RAISE EXCEPTION 'workflow binding identifiers are not deterministic'
                USING ERRCODE = 'P7006';
        END IF;
        RETURN NEW;
    END IF;

    IF session_user <> 'opsmind_dispatcher' THEN
        RAISE EXCEPTION 'workflow binding reconciliation requires opsmind_dispatcher'
            USING ERRCODE = '42501';
    END IF;
    IF NEW.organization_id IS DISTINCT FROM OLD.organization_id
       OR NEW.run_id IS DISTINCT FROM OLD.run_id
       OR NEW.project_id IS DISTINCT FROM OLD.project_id
       OR NEW.incident_id IS DISTINCT FROM OLD.incident_id
       OR NEW.actor_id IS DISTINCT FROM OLD.actor_id
       OR NEW.client_request_digest IS DISTINCT FROM OLD.client_request_digest
       OR NEW.start_payload_digest IS DISTINCT FROM OLD.start_payload_digest
       OR NEW.start_event_id IS DISTINCT FROM OLD.start_event_id
       OR NEW.temporal_cluster_id IS DISTINCT FROM OLD.temporal_cluster_id
       OR NEW.temporal_namespace IS DISTINCT FROM OLD.temporal_namespace
       OR NEW.workflow_id IS DISTINCT FROM OLD.workflow_id
       OR NEW.workflow_type IS DISTINCT FROM OLD.workflow_type
       OR NEW.task_queue IS DISTINCT FROM OLD.task_queue
       OR NEW.authorization_revision IS DISTINCT FROM OLD.authorization_revision
       OR NEW.started_at IS DISTINCT FROM OLD.started_at
       OR NEW.deadline_at IS DISTINCT FROM OLD.deadline_at
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'workflow binding identity and target fields are immutable'
            USING ERRCODE = 'P7006';
    END IF;
    IF OLD.status IS DISTINCT FROM 'PENDING'
       OR NEW.status NOT IN ('STARTED', 'REJECTED') THEN
        RAISE EXCEPTION 'workflow binding has no legal reconciliation transition'
            USING ERRCODE = 'P7006';
    END IF;
    IF NEW.status = 'STARTED'
       AND (
           NEW.temporal_run_id IS NULL
           OR NEW.temporal_started_at IS NULL
           OR NEW.rejection_code IS NOT NULL
           OR NEW.rejected_at IS NOT NULL
       ) THEN
        RAISE EXCEPTION 'STARTED workflow binding requires Temporal run metadata'
            USING ERRCODE = 'P7006';
    END IF;
    IF NEW.status = 'REJECTED'
       AND (
           NEW.temporal_run_id IS NOT NULL
           OR NEW.temporal_started_at IS NOT NULL
           OR NEW.rejection_code IS NULL
           OR NEW.rejected_at IS NULL
       ) THEN
        RAISE EXCEPTION 'REJECTED workflow binding requires bounded rejection metadata'
            USING ERRCODE = 'P7006';
    END IF;
    IF NEW.updated_at IS DISTINCT FROM OLD.updated_at
       AND NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'workflow binding updated_at cannot move backwards'
            USING ERRCODE = 'P7006';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER investigation_workflow_bindings_validate_write
    BEFORE INSERT OR UPDATE ON investigation_workflow_bindings
    FOR EACH ROW EXECUTE FUNCTION opsmind_validate_investigation_workflow_binding();

CREATE OR REPLACE FUNCTION opsmind_validate_investigation_workflow_start_outbox()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    binding_row record;
    run_row record;
    expected_keys text[] := ARRAY[
        'organization_id', 'project_id', 'incident_id', 'run_id', 'actor_id',
        'max_rounds', 'max_tool_calls', 'max_evidence_items', 'max_tokens',
        'started_at', 'deadline_at', 'temporal_cluster_id', 'temporal_namespace',
        'workflow_id', 'workflow_type', 'task_queue', 'authorization_revision',
        'request_digest'
    ];
BEGIN
    -- All ordinary outbox events retain V001/V002 behaviour.
    IF NEW.event_type IS DISTINCT FROM 'investigation.workflow-start.requested' THEN
        RETURN NEW;
    END IF;
    IF NEW.schema_version IS DISTINCT FROM '1'
       OR NEW.aggregate_type IS DISTINCT FROM 'investigation-workflow'
       OR NEW.aggregate_sequence IS DISTINCT FROM 1
       OR NEW.correlation_id IS DISTINCT FROM NEW.aggregate_id THEN
        RAISE EXCEPTION 'workflow-start outbox metadata is not canonical'
            USING ERRCODE = 'P7007';
    END IF;

    SELECT binding.*
      INTO binding_row
      FROM public.investigation_workflow_bindings binding
     WHERE binding.organization_id = NEW.organization_id
       AND binding.run_id = NEW.aggregate_id
       AND binding.start_event_id = NEW.event_id;
    IF NOT FOUND
       OR binding_row.status IS DISTINCT FROM 'PENDING' THEN
        RAISE EXCEPTION 'workflow-start outbox event must bind a pending workflow row'
            USING ERRCODE = 'P7007';
    END IF;

    SELECT stored.*
      INTO run_row
      FROM public.investigation_runs stored
     WHERE stored.organization_id = NEW.organization_id
       AND stored.run_id = NEW.aggregate_id;
    IF NOT FOUND
       OR NEW.payload_digest IS DISTINCT FROM binding_row.start_payload_digest
       OR public.digest(NEW.payload_bytes, 'sha256')
            IS DISTINCT FROM NEW.payload_digest
       OR convert_from(NEW.payload_bytes, 'UTF8')::jsonb IS DISTINCT FROM NEW.payload
       OR public.opsmind_json_object_has_exact_keys(NEW.payload, expected_keys)
            IS NOT TRUE THEN
        RAISE EXCEPTION 'workflow-start outbox payload digest or shape is invalid'
            USING ERRCODE = 'P7007';
    END IF;
    IF jsonb_typeof(NEW.payload -> 'request_digest') IS DISTINCT FROM 'string'
       OR NEW.payload ->> 'request_digest' !~ '^[0-9a-f]{64}$'
       OR NEW.payload ->> 'request_digest'
            IS DISTINCT FROM encode(binding_row.client_request_digest, 'hex')
       OR jsonb_typeof(NEW.payload -> 'max_rounds') IS DISTINCT FROM 'number'
       OR jsonb_typeof(NEW.payload -> 'max_tool_calls') IS DISTINCT FROM 'number'
       OR jsonb_typeof(NEW.payload -> 'max_evidence_items') IS DISTINCT FROM 'number'
       OR jsonb_typeof(NEW.payload -> 'max_tokens') IS DISTINCT FROM 'number'
       OR jsonb_typeof(NEW.payload -> 'authorization_revision')
            IS DISTINCT FROM 'number'
       OR jsonb_typeof(NEW.payload -> 'started_at') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.payload -> 'deadline_at') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.payload -> 'organization_id') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.payload -> 'project_id') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.payload -> 'incident_id') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.payload -> 'run_id') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.payload -> 'actor_id') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.payload -> 'temporal_cluster_id')
            IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.payload -> 'temporal_namespace')
            IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.payload -> 'workflow_id') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.payload -> 'workflow_type') IS DISTINCT FROM 'string'
       OR jsonb_typeof(NEW.payload -> 'task_queue') IS DISTINCT FROM 'string'
       OR NEW.payload ->> 'organization_id'
            IS DISTINCT FROM binding_row.organization_id::text
       OR NEW.payload ->> 'project_id' IS DISTINCT FROM binding_row.project_id::text
       OR NEW.payload ->> 'incident_id' IS DISTINCT FROM binding_row.incident_id::text
       OR NEW.payload ->> 'run_id' IS DISTINCT FROM binding_row.run_id::text
       OR NEW.payload ->> 'actor_id' IS DISTINCT FROM binding_row.actor_id::text
       OR NEW.payload ->> 'temporal_cluster_id'
            IS DISTINCT FROM binding_row.temporal_cluster_id
       OR NEW.payload ->> 'temporal_namespace'
            IS DISTINCT FROM binding_row.temporal_namespace
       OR NEW.payload ->> 'workflow_id' IS DISTINCT FROM binding_row.workflow_id
       OR NEW.payload ->> 'workflow_type' IS DISTINCT FROM binding_row.workflow_type
       OR NEW.payload ->> 'task_queue' IS DISTINCT FROM binding_row.task_queue
       OR (NEW.payload ->> 'authorization_revision')::bigint
            IS DISTINCT FROM binding_row.authorization_revision
       OR NEW.payload ->> 'max_rounds' IS DISTINCT FROM run_row.max_rounds::text
       OR NEW.payload ->> 'max_tool_calls' IS DISTINCT FROM run_row.max_tool_calls::text
       OR NEW.payload ->> 'max_evidence_items'
            IS DISTINCT FROM run_row.max_evidence_items::text
       OR NEW.payload ->> 'max_tokens' IS DISTINCT FROM run_row.max_tokens::text
       OR (NEW.payload ->> 'started_at')::timestamptz
            IS DISTINCT FROM run_row.started_at
       OR (NEW.payload ->> 'deadline_at')::timestamptz
            IS DISTINCT FROM run_row.deadline_at THEN
        RAISE EXCEPTION 'workflow-start outbox payload identity does not match its run and binding'
            USING ERRCODE = 'P7007';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER outbox_events_validate_investigation_workflow_start
    BEFORE INSERT ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION opsmind_validate_investigation_workflow_start_outbox();

-- The resolver executes this selector cross-tenant, while session_user remains
-- the authenticated dispatcher login. It intentionally shares the ready,
-- lease-expiry, poison, and predecessor predicates used by claim code.
CREATE OR REPLACE FUNCTION opsmind_list_investigation_workflow_start_tenants(p_limit integer)
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
      JOIN public.investigation_workflow_bindings binding
        ON binding.organization_id = event_row.organization_id
       AND binding.run_id = event_row.aggregate_id
       AND binding.start_event_id = event_row.event_id
       AND binding.status = 'PENDING'
      JOIN public.organizations organization_row
        ON organization_row.id = event_row.organization_id
       AND organization_row.status = 'active'
      JOIN public.service_accounts account_row
        ON account_row.organization_id = event_row.organization_id
       AND account_row.status = 'active'
       AND account_row.database_principal = session_user
       AND account_row.allowed_audiences @> '["opsmind-outbox-dispatcher"]'::jsonb
       AND account_row.allowed_scopes @> '["outbox:dispatch"]'::jsonb
     WHERE event_row.event_type = 'investigation.workflow-start.requested'
       AND event_row.schema_version = '1'
       AND event_row.aggregate_type = 'investigation-workflow'
       AND event_row.aggregate_sequence = 1
       AND event_row.published_at IS NULL
       AND event_row.poisoned_at IS NULL
       AND event_row.next_attempt_at <= statement_timestamp()
       AND (
           event_row.lease_expires_at IS NULL
           OR event_row.lease_expires_at <= statement_timestamp()
       )
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
ALTER FUNCTION public.opsmind_list_investigation_workflow_start_tenants(integer)
    OWNER TO opsmind_dispatch_resolver;

CREATE INDEX outbox_investigation_workflow_start_ready_idx
    ON outbox_events (organization_id, next_attempt_at, occurred_at, event_id)
    WHERE event_type = 'investigation.workflow-start.requested'
      AND schema_version = '1'
      AND aggregate_type = 'investigation-workflow'
      AND aggregate_sequence = 1
      AND published_at IS NULL
      AND poisoned_at IS NULL;

ALTER TABLE investigation_workflow_bindings ENABLE ROW LEVEL SECURITY;
ALTER TABLE investigation_workflow_bindings FORCE ROW LEVEL SECURITY;
CREATE POLICY investigation_workflow_bindings_tenant_isolation
    ON investigation_workflow_bindings
    USING (organization_id = public.opsmind_current_tenant_id())
    WITH CHECK (organization_id = public.opsmind_current_tenant_id());
CREATE POLICY investigation_workflow_bindings_dispatch_resolution
    ON investigation_workflow_bindings
    FOR SELECT TO opsmind_dispatch_resolver
    USING (true);

REVOKE ALL ON investigation_workflow_bindings FROM opsmind_app, opsmind_dispatcher, PUBLIC;
GRANT SELECT, INSERT ON investigation_workflow_bindings TO opsmind_app;
GRANT SELECT ON investigation_workflow_bindings TO opsmind_dispatcher;
GRANT UPDATE (
    status, temporal_run_id, rejection_code, updated_at,
    temporal_started_at, rejected_at
) ON investigation_workflow_bindings TO opsmind_dispatcher;
GRANT SELECT, INSERT ON inbox_events TO opsmind_dispatcher;
GRANT UPDATE (status, processed_at, attempts, last_error)
    ON inbox_events TO opsmind_dispatcher;
GRANT SELECT (
    organization_id, run_id, start_event_id, status, updated_at
) ON investigation_workflow_bindings TO opsmind_dispatch_resolver;
GRANT SELECT (event_id, event_type, schema_version)
    ON outbox_events TO opsmind_dispatch_resolver;

REVOKE ALL ON FUNCTION public.opsmind_investigation_workflow_start_event_id(uuid, uuid)
    FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_validate_investigation_workflow_binding()
    FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_validate_investigation_workflow_start_outbox()
    FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_list_investigation_workflow_start_tenants(integer)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.opsmind_investigation_workflow_start_event_id(uuid, uuid)
    TO opsmind_app;
GRANT EXECUTE ON FUNCTION public.opsmind_list_investigation_workflow_start_tenants(integer)
    TO opsmind_dispatcher;

COMMENT ON TABLE investigation_workflow_bindings IS
    'Immutable tenant/run identity and deterministic Temporal target for a durable investigation start.';
COMMENT ON COLUMN investigation_workflow_bindings.client_request_digest IS
    'SHA-256 digest of the canonical client request; retries must match exactly.';
COMMENT ON COLUMN investigation_workflow_bindings.start_payload_digest IS
    'SHA-256 digest of the exact workflow-start outbox payload bytes.';
COMMENT ON COLUMN investigation_workflow_bindings.start_event_id IS
    'Deterministic outbox event identity for this organization/run workflow start.';
COMMENT ON COLUMN investigation_workflow_bindings.authorization_revision IS
    'Authorization snapshot revision captured at admission; workers must reauthorize before external work.';
COMMENT ON FUNCTION opsmind_validate_investigation_workflow_binding() IS
    'Binds app inserts to the initial investigation snapshot and permits only dispatcher reconciliation.';
COMMENT ON FUNCTION opsmind_validate_investigation_workflow_start_outbox() IS
    'Validates the canonical workflow-start event identity, payload keys, and SHA-256 binding.';
COMMENT ON FUNCTION opsmind_list_investigation_workflow_start_tenants(integer) IS
    'Event-type-scoped, authorized dispatcher tenant enumeration with ready/lease/predecessor fencing.';
