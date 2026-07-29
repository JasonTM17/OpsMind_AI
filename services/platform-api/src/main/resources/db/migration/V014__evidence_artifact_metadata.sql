-- Phase 4C: durable metadata authority for large evidence artifacts.
-- This migration records control-plane intent only. It neither uploads nor exposes artifact bodies.

CREATE OR REPLACE FUNCTION opsmind_evidence_artifact_id(
    p_organization_id uuid,
    p_run_id uuid,
    p_idempotency_key uuid
) RETURNS uuid
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
SET search_path = pg_catalog, public, pg_temp AS $$
    WITH raw AS (
        SELECT public.digest(convert_to(
            'opsmind:artifact:v1:' || p_organization_id::text || ':'
            || p_run_id::text || ':' || p_idempotency_key::text,
            'UTF8'
        ), 'sha256') AS bytes
    ),
    versioned AS (
        SELECT set_byte(
            set_byte(bytes, 6, (get_byte(bytes, 6) & 15) | 128),
            8, (get_byte(bytes, 8) & 63) | 128
        ) AS bytes
          FROM raw
    ),
    hex AS (
        SELECT encode(bytes, 'hex') AS value FROM versioned
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

CREATE OR REPLACE FUNCTION opsmind_evidence_artifact_initial_event_id(
    p_organization_id uuid,
    p_artifact_id uuid
) RETURNS uuid
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
SET search_path = pg_catalog, public, pg_temp AS $$
    WITH raw AS (
        SELECT public.digest(convert_to(
            'opsmind:artifact-event:v1:' || p_organization_id::text || ':'
            || p_artifact_id::text || ':' || p_artifact_id::text,
            'UTF8'
        ), 'sha256') AS bytes
    ),
    versioned AS (
        SELECT set_byte(
            set_byte(bytes, 6, (get_byte(bytes, 6) & 15) | 128),
            8, (get_byte(bytes, 8) & 63) | 128
        ) AS bytes
          FROM raw
    ),
    hex AS (
        SELECT encode(bytes, 'hex') AS value FROM versioned
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

CREATE TABLE evidence_artifacts (
    artifact_id                   uuid NOT NULL,
    organization_id               uuid NOT NULL REFERENCES organizations(id),
    project_id                    uuid NOT NULL,
    incident_id                   uuid NOT NULL,
    run_id                        uuid NOT NULL,
    actor_id                      uuid NOT NULL,
    idempotency_key               uuid NOT NULL,
    source_type                   varchar(32) NOT NULL,
    source_identity               varchar(256) NOT NULL,
    source_version                varchar(128) NOT NULL,
    data_classification           varchar(64) NOT NULL,
    expected_content_digest       bytea NOT NULL,
    expected_byte_count           bigint NOT NULL,
    authorization_epoch           bigint NOT NULL,
    retention_class               varchar(32) NOT NULL,
    residency_class               varchar(32) NOT NULL,
    deletion_class                varchar(32) NOT NULL,
    storage_key                   varchar(512) NOT NULL,
    storage_generation            bigint NOT NULL DEFAULT 0,
    encryption_metadata_reference varchar(256),
    lifecycle_state               varchar(32) NOT NULL,
    lifecycle_version             bigint NOT NULL,
    upload_attempt_id             uuid,
    upload_lease_expires_at       timestamptz,
    upload_attempt_count          integer NOT NULL DEFAULT 0,
    last_failure_code             varchar(128),
    created_at                    timestamptz NOT NULL,
    lifecycle_updated_at          timestamptz NOT NULL,
    PRIMARY KEY (organization_id, artifact_id),
    UNIQUE (organization_id, run_id, idempotency_key),
    FOREIGN KEY (run_id, organization_id, project_id, incident_id)
        REFERENCES investigation_runs(run_id, organization_id, project_id, incident_id),
    FOREIGN KEY (incident_id, organization_id, project_id)
        REFERENCES incidents(id, organization_id, project_id),
    FOREIGN KEY (project_id, organization_id)
        REFERENCES projects(id, organization_id),
    FOREIGN KEY (organization_id, actor_id)
        REFERENCES organization_memberships(organization_id, user_id),
    CHECK (artifact_id = public.opsmind_evidence_artifact_id(
        organization_id, run_id, idempotency_key
    )),
    CHECK (octet_length(expected_content_digest) = 32),
    CHECK (expected_byte_count BETWEEN 1 AND 1099511627776),
    CHECK (authorization_epoch >= 0),
    CHECK (source_type ~ '^[a-z][a-z0-9_-]{0,31}$'),
    CHECK (source_identity ~ '^[A-Za-z0-9][A-Za-z0-9_.:@/-]{0,255}$'),
    CHECK (source_version ~ '^[A-Za-z0-9][A-Za-z0-9_.:@/-]{0,127}$'),
    CHECK (data_classification ~ '^[a-z][a-z0-9-]{2,63}$'),
    CHECK (retention_class = 'evidence-90d'),
    CHECK (residency_class = 'singapore'),
    CHECK (deletion_class = 'delete-within-24h'),
    CHECK (storage_key = 'artifacts/v1/' || organization_id::text || '/'
        || artifact_id::text || '/' || encode(expected_content_digest, 'hex')),
    CHECK (storage_generation >= 0),
    CHECK (lifecycle_version >= 1),
    CHECK (lifecycle_state IN (
        'PENDING_UPLOAD', 'STORED', 'SCANNING', 'AVAILABLE', 'QUARANTINED', 'HELD',
        'DELETION_REQUESTED', 'EXPIRED', 'PURGED', 'RECEIPT_RECORDED', 'ORPHANED', 'FAILED'
    )),
    CHECK (encryption_metadata_reference IS NULL OR
        encryption_metadata_reference ~ '^[A-Za-z0-9][A-Za-z0-9_.:@/-]{0,255}$'),
    CHECK (last_failure_code IS NULL OR
        last_failure_code ~ '^[a-z0-9][a-z0-9._-]{0,127}$'),
    CHECK (
        (upload_attempt_id IS NULL AND upload_lease_expires_at IS NULL
            AND upload_attempt_count = 0)
        OR
        (upload_attempt_id IS NOT NULL AND upload_lease_expires_at IS NOT NULL
            AND upload_attempt_count > 0)
    ),
    CONSTRAINT evidence_artifacts_phase_1_pending_only CHECK (
        lifecycle_state = 'PENDING_UPLOAD'
        AND lifecycle_version = 1
        AND storage_generation = 0
        AND encryption_metadata_reference IS NULL
        AND upload_attempt_id IS NULL
        AND upload_lease_expires_at IS NULL
        AND upload_attempt_count = 0
        AND last_failure_code IS NULL
        AND lifecycle_updated_at = created_at
    )
);

CREATE INDEX evidence_artifacts_incident_lifecycle_idx
    ON evidence_artifacts (
        organization_id, project_id, incident_id, lifecycle_state, created_at, artifact_id
    );
CREATE INDEX evidence_artifacts_actor_created_idx
    ON evidence_artifacts (organization_id, actor_id, created_at DESC, artifact_id);

CREATE TABLE evidence_artifact_events (
    event_id               uuid PRIMARY KEY,
    organization_id        uuid NOT NULL REFERENCES organizations(id),
    project_id             uuid NOT NULL,
    incident_id            uuid NOT NULL,
    run_id                 uuid NOT NULL,
    artifact_id            uuid NOT NULL,
    actor_id               uuid NOT NULL,
    lifecycle_version      bigint NOT NULL,
    lifecycle_from_state   varchar(32),
    lifecycle_to_state     varchar(32) NOT NULL,
    occurred_at            timestamptz NOT NULL,
    audit_event_id         uuid NOT NULL,
    UNIQUE (organization_id, artifact_id, lifecycle_version),
    FOREIGN KEY (organization_id, artifact_id)
        REFERENCES evidence_artifacts(organization_id, artifact_id),
    FOREIGN KEY (run_id, organization_id, project_id, incident_id)
        REFERENCES investigation_runs(run_id, organization_id, project_id, incident_id),
    FOREIGN KEY (organization_id, actor_id)
        REFERENCES organization_memberships(organization_id, user_id),
    FOREIGN KEY (audit_event_id) REFERENCES audit_events(event_id)
        DEFERRABLE INITIALLY DEFERRED,
    CHECK (audit_event_id = event_id),
    CHECK (lifecycle_version >= 1),
    CHECK (lifecycle_from_state IS NULL OR lifecycle_from_state IN (
        'PENDING_UPLOAD', 'STORED', 'SCANNING', 'AVAILABLE', 'QUARANTINED', 'HELD',
        'DELETION_REQUESTED', 'EXPIRED', 'PURGED', 'RECEIPT_RECORDED', 'ORPHANED', 'FAILED'
    )),
    CHECK (lifecycle_to_state IN (
        'PENDING_UPLOAD', 'STORED', 'SCANNING', 'AVAILABLE', 'QUARANTINED', 'HELD',
        'DELETION_REQUESTED', 'EXPIRED', 'PURGED', 'RECEIPT_RECORDED', 'ORPHANED', 'FAILED'
    )),
    CONSTRAINT evidence_artifact_events_phase_1_initial_only CHECK (
        lifecycle_version = 1
        AND lifecycle_from_state IS NULL
        AND lifecycle_to_state = 'PENDING_UPLOAD'
    )
);

CREATE INDEX evidence_artifact_events_artifact_order_idx
    ON evidence_artifact_events (organization_id, artifact_id, lifecycle_version);

CREATE OR REPLACE FUNCTION opsmind_validate_evidence_artifact_insert() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    run_row record;
    actor_id uuid := public.opsmind_current_actor_id();
BEGIN
    IF session_user = 'opsmind_app' AND (
        NEW.organization_id IS DISTINCT FROM public.opsmind_current_tenant_id()
        OR NEW.actor_id IS DISTINCT FROM actor_id
    ) THEN
        RAISE EXCEPTION 'artifact metadata insert requires the bound tenant and actor'
            USING ERRCODE = '42501';
    END IF;

    SELECT stored.organization_id, stored.project_id, stored.incident_id, stored.run_id, stored.actor_id,
           incident.version AS authorization_epoch
      INTO run_row
      FROM public.investigation_runs stored
      JOIN public.incidents incident
        ON incident.id = stored.incident_id
       AND incident.organization_id = stored.organization_id
       AND incident.project_id = stored.project_id
     WHERE stored.organization_id = NEW.organization_id
       AND stored.project_id = NEW.project_id
       AND stored.incident_id = NEW.incident_id
       AND stored.run_id = NEW.run_id
     FOR KEY SHARE OF stored, incident;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'artifact metadata requires an authorized investigation run'
            USING ERRCODE = 'P7103';
    END IF;
    IF NEW.artifact_id IS DISTINCT FROM public.opsmind_evidence_artifact_id(
            NEW.organization_id, NEW.run_id, NEW.idempotency_key
        )
       OR NEW.actor_id IS DISTINCT FROM run_row.actor_id
       OR NEW.authorization_epoch IS DISTINCT FROM run_row.authorization_epoch
       OR NEW.storage_key IS DISTINCT FROM 'artifacts/v1/' || NEW.organization_id::text || '/'
            || NEW.artifact_id::text || '/' || encode(NEW.expected_content_digest, 'hex') THEN
        RAISE EXCEPTION 'artifact metadata identity or authorization epoch is invalid'
            USING ERRCODE = 'P7103';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION opsmind_validate_evidence_artifact_event_append() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    artifact_row record;
    actor_id uuid := public.opsmind_current_actor_id();
BEGIN
    IF session_user = 'opsmind_app' AND (
        NEW.organization_id IS DISTINCT FROM public.opsmind_current_tenant_id()
        OR NEW.actor_id IS DISTINCT FROM actor_id
    ) THEN
        RAISE EXCEPTION 'artifact lifecycle event requires the bound tenant and actor'
            USING ERRCODE = '42501';
    END IF;

    SELECT stored.organization_id, stored.project_id, stored.incident_id, stored.run_id,
           stored.actor_id, stored.lifecycle_state, stored.lifecycle_version,
           stored.created_at
      INTO artifact_row
      FROM public.evidence_artifacts stored
     WHERE stored.organization_id = NEW.organization_id
       AND stored.artifact_id = NEW.artifact_id
     FOR KEY SHARE;
    IF NOT FOUND
       OR NEW.project_id IS DISTINCT FROM artifact_row.project_id
       OR NEW.incident_id IS DISTINCT FROM artifact_row.incident_id
       OR NEW.run_id IS DISTINCT FROM artifact_row.run_id
       OR NEW.actor_id IS DISTINCT FROM artifact_row.actor_id
       OR NEW.event_id IS DISTINCT FROM public.opsmind_evidence_artifact_initial_event_id(
            NEW.organization_id, NEW.artifact_id
       )
       OR NEW.audit_event_id IS DISTINCT FROM NEW.event_id
       OR NEW.lifecycle_version IS DISTINCT FROM artifact_row.lifecycle_version
       OR NEW.lifecycle_from_state IS NOT NULL
       OR NEW.lifecycle_to_state IS DISTINCT FROM artifact_row.lifecycle_state
       OR NEW.occurred_at IS DISTINCT FROM artifact_row.created_at THEN
        RAISE EXCEPTION 'artifact lifecycle event does not match authoritative metadata'
            USING ERRCODE = 'P7103';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION opsmind_require_evidence_artifact_initial_event() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM public.evidence_artifact_events event_row
         WHERE event_row.organization_id = NEW.organization_id
           AND event_row.artifact_id = NEW.artifact_id
           AND event_row.event_id = public.opsmind_evidence_artifact_initial_event_id(
                NEW.organization_id, NEW.artifact_id
           )
           AND event_row.audit_event_id = event_row.event_id
           AND event_row.lifecycle_version = 1
           AND event_row.lifecycle_from_state IS NULL
           AND event_row.lifecycle_to_state = 'PENDING_UPLOAD'
    ) THEN
        RAISE EXCEPTION 'artifact metadata requires one initial pending-upload event'
            USING ERRCODE = 'P7104';
    END IF;
    RETURN NULL;
END
$$;

CREATE OR REPLACE FUNCTION opsmind_reject_evidence_artifact_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'evidence artifact metadata is append-only until a fenced lifecycle transition lands'
        USING ERRCODE = '42501';
END
$$;

CREATE TRIGGER evidence_artifacts_validate_insert
    BEFORE INSERT ON evidence_artifacts
    FOR EACH ROW EXECUTE FUNCTION opsmind_validate_evidence_artifact_insert();
CREATE TRIGGER evidence_artifacts_no_update
    BEFORE UPDATE OR DELETE ON evidence_artifacts
    FOR EACH ROW EXECUTE FUNCTION opsmind_reject_evidence_artifact_mutation();
CREATE TRIGGER evidence_artifacts_no_truncate
    BEFORE TRUNCATE ON evidence_artifacts
    FOR EACH STATEMENT EXECUTE FUNCTION opsmind_reject_evidence_artifact_mutation();
CREATE TRIGGER evidence_artifact_events_validate_append
    BEFORE INSERT ON evidence_artifact_events
    FOR EACH ROW EXECUTE FUNCTION opsmind_validate_evidence_artifact_event_append();
CREATE TRIGGER evidence_artifact_events_no_update
    BEFORE UPDATE OR DELETE ON evidence_artifact_events
    FOR EACH ROW EXECUTE FUNCTION opsmind_reject_evidence_artifact_mutation();
CREATE TRIGGER evidence_artifact_events_no_truncate
    BEFORE TRUNCATE ON evidence_artifact_events
    FOR EACH STATEMENT EXECUTE FUNCTION opsmind_reject_evidence_artifact_mutation();
CREATE CONSTRAINT TRIGGER evidence_artifacts_require_initial_event
    AFTER INSERT ON evidence_artifacts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION opsmind_require_evidence_artifact_initial_event();

ALTER TABLE audit_events
    DROP CONSTRAINT audit_events_schema_version_known,
    ADD CONSTRAINT audit_events_schema_version_known
        CHECK (schema_version IN (
            'legacy-v1', 'incident-audit-v1', 'investigation-audit-v1',
            'evidence-artifact-audit-v1'
        )),
    ADD CONSTRAINT audit_events_evidence_artifact_contract
        CHECK (
            schema_version <> 'evidence-artifact-audit-v1'
            OR (
                actor_id IS NOT NULL
                AND action = 'ARTIFACT_PENDING_UPLOAD'
                AND resource_type = 'evidence_artifact'
                AND resource_id = correlation_id::text
                AND payload ?& ARRAY[
                    'eventId', 'organizationId', 'projectId', 'incidentId', 'runId',
                    'artifactId', 'actorId', 'lifecycleVersion', 'lifecycleState',
                    'contentDigest', 'byteCount', 'dataClassification', 'retentionClass',
                    'occurredAt'
                ]
                AND payload - ARRAY[
                    'eventId', 'organizationId', 'projectId', 'incidentId', 'runId',
                    'artifactId', 'actorId', 'lifecycleVersion', 'lifecycleState',
                    'contentDigest', 'byteCount', 'dataClassification', 'retentionClass',
                    'occurredAt'
                ] = '{}'::jsonb
                AND payload ->> 'eventId' = event_id::text
                AND payload ->> 'organizationId' = organization_id::text
                AND payload ->> 'artifactId' = resource_id
                AND payload ->> 'actorId' = actor_id::text
            )
        );

-- Preserve V003/V006 audit-chain behavior and add an exact authoritative artifact branch.
CREATE OR REPLACE FUNCTION opsmind_assign_audit_chain() RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    prior_sequence bigint;
    prior_digest bytea;
    timeline_row record;
    investigation_row record;
    artifact_row record;
    actor_id uuid := public.opsmind_current_actor_id();
BEGIN
    IF session_user = 'opsmind_app' THEN
        IF NEW.organization_id IS DISTINCT FROM public.opsmind_current_tenant_id()
           OR NEW.actor_id IS DISTINCT FROM actor_id
           OR NEW.schema_version IS NULL
           OR NEW.schema_version NOT IN (
                'incident-audit-v1', 'investigation-audit-v1', 'evidence-artifact-audit-v1'
           ) THEN
            RAISE EXCEPTION 'audit append requires the bound tenant, actor, and supported schema'
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
    ELSIF NEW.schema_version = 'investigation-audit-v1' THEN
        SELECT event_row.event_type, event_row.actor_id, event_row.run_id,
               event_row.occurred_at, event_row.payload
          INTO investigation_row
          FROM public.investigation_run_events event_row
         WHERE event_row.event_id = NEW.event_id
           AND event_row.organization_id = NEW.organization_id;
        IF NOT FOUND
           OR NEW.action IS DISTINCT FROM investigation_row.event_type
           OR NEW.actor_id IS DISTINCT FROM investigation_row.actor_id
           OR NEW.resource_type IS DISTINCT FROM 'investigation_run'
           OR NEW.resource_id IS DISTINCT FROM investigation_row.run_id::text
           OR NEW.correlation_id IS DISTINCT FROM investigation_row.run_id
           OR NEW.occurred_at IS DISTINCT FROM investigation_row.occurred_at
           OR NEW.payload IS DISTINCT FROM investigation_row.payload THEN
            RAISE EXCEPTION 'investigation audit payload must match its authoritative run event'
                USING ERRCODE = 'P7005';
        END IF;
    ELSIF NEW.schema_version = 'evidence-artifact-audit-v1' THEN
        SELECT event_row.event_id, event_row.organization_id, event_row.project_id,
               event_row.incident_id, event_row.run_id, event_row.artifact_id,
               event_row.actor_id, event_row.lifecycle_version,
               event_row.lifecycle_from_state, event_row.lifecycle_to_state,
               event_row.occurred_at, event_row.audit_event_id,
               artifact.expected_content_digest, artifact.expected_byte_count,
               artifact.data_classification, artifact.retention_class
          INTO artifact_row
          FROM public.evidence_artifact_events event_row
          JOIN public.evidence_artifacts artifact
            ON artifact.organization_id = event_row.organization_id
           AND artifact.artifact_id = event_row.artifact_id
         WHERE event_row.event_id = NEW.event_id
           AND event_row.organization_id = NEW.organization_id;
        IF NOT FOUND
           OR NEW.action IS DISTINCT FROM 'ARTIFACT_PENDING_UPLOAD'
           OR NEW.actor_id IS DISTINCT FROM artifact_row.actor_id
           OR NEW.resource_type IS DISTINCT FROM 'evidence_artifact'
           OR NEW.resource_id IS DISTINCT FROM artifact_row.artifact_id::text
           OR NEW.correlation_id IS DISTINCT FROM artifact_row.artifact_id
           OR NEW.occurred_at IS DISTINCT FROM artifact_row.occurred_at
           OR artifact_row.audit_event_id IS DISTINCT FROM NEW.event_id
           OR artifact_row.lifecycle_version IS DISTINCT FROM 1
           OR artifact_row.lifecycle_from_state IS NOT NULL
           OR artifact_row.lifecycle_to_state IS DISTINCT FROM 'PENDING_UPLOAD'
           OR NOT public.opsmind_json_object_has_exact_keys(NEW.payload, ARRAY[
                'eventId', 'organizationId', 'projectId', 'incidentId', 'runId',
                'artifactId', 'actorId', 'lifecycleVersion', 'lifecycleState',
                'contentDigest', 'byteCount', 'dataClassification', 'retentionClass',
                'occurredAt'
           ])
           OR jsonb_typeof(NEW.payload -> 'eventId') IS DISTINCT FROM 'string'
           OR jsonb_typeof(NEW.payload -> 'organizationId') IS DISTINCT FROM 'string'
           OR jsonb_typeof(NEW.payload -> 'projectId') IS DISTINCT FROM 'string'
           OR jsonb_typeof(NEW.payload -> 'incidentId') IS DISTINCT FROM 'string'
           OR jsonb_typeof(NEW.payload -> 'runId') IS DISTINCT FROM 'string'
           OR jsonb_typeof(NEW.payload -> 'artifactId') IS DISTINCT FROM 'string'
           OR jsonb_typeof(NEW.payload -> 'actorId') IS DISTINCT FROM 'string'
           OR jsonb_typeof(NEW.payload -> 'lifecycleVersion') IS DISTINCT FROM 'number'
           OR jsonb_typeof(NEW.payload -> 'lifecycleState') IS DISTINCT FROM 'string'
           OR jsonb_typeof(NEW.payload -> 'contentDigest') IS DISTINCT FROM 'string'
           OR jsonb_typeof(NEW.payload -> 'byteCount') IS DISTINCT FROM 'number'
           OR jsonb_typeof(NEW.payload -> 'dataClassification') IS DISTINCT FROM 'string'
           OR jsonb_typeof(NEW.payload -> 'retentionClass') IS DISTINCT FROM 'string'
           OR jsonb_typeof(NEW.payload -> 'occurredAt') IS DISTINCT FROM 'string'
           OR NEW.payload ->> 'eventId' IS DISTINCT FROM artifact_row.event_id::text
           OR NEW.payload ->> 'organizationId' IS DISTINCT FROM artifact_row.organization_id::text
           OR NEW.payload ->> 'projectId' IS DISTINCT FROM artifact_row.project_id::text
           OR NEW.payload ->> 'incidentId' IS DISTINCT FROM artifact_row.incident_id::text
           OR NEW.payload ->> 'runId' IS DISTINCT FROM artifact_row.run_id::text
           OR NEW.payload ->> 'artifactId' IS DISTINCT FROM artifact_row.artifact_id::text
           OR NEW.payload ->> 'actorId' IS DISTINCT FROM artifact_row.actor_id::text
           OR NEW.payload ->> 'lifecycleVersion'
                IS DISTINCT FROM artifact_row.lifecycle_version::text
           OR NEW.payload ->> 'lifecycleState' IS DISTINCT FROM artifact_row.lifecycle_to_state
           OR NEW.payload ->> 'contentDigest'
                IS DISTINCT FROM 'sha256:' || encode(artifact_row.expected_content_digest, 'hex')
           OR NEW.payload ->> 'byteCount' IS DISTINCT FROM artifact_row.expected_byte_count::text
           OR NEW.payload ->> 'dataClassification'
                IS DISTINCT FROM artifact_row.data_classification
           OR NEW.payload ->> 'retentionClass' IS DISTINCT FROM artifact_row.retention_class
           OR (NEW.payload ->> 'occurredAt')::timestamptz
                IS DISTINCT FROM artifact_row.occurred_at THEN
            RAISE EXCEPTION 'artifact audit payload must match its authoritative lifecycle event'
                USING ERRCODE = 'P7105';
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

ALTER TABLE evidence_artifacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence_artifacts FORCE ROW LEVEL SECURITY;
CREATE POLICY evidence_artifacts_tenant_isolation ON evidence_artifacts
    USING (organization_id = opsmind_current_tenant_id())
    WITH CHECK (organization_id = opsmind_current_tenant_id());

ALTER TABLE evidence_artifact_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence_artifact_events FORCE ROW LEVEL SECURITY;
CREATE POLICY evidence_artifact_events_tenant_isolation ON evidence_artifact_events
    USING (organization_id = opsmind_current_tenant_id())
    WITH CHECK (organization_id = opsmind_current_tenant_id());

REVOKE ALL ON evidence_artifacts, evidence_artifact_events
    FROM opsmind_app, opsmind_dispatcher, PUBLIC;
GRANT SELECT (
    artifact_id, organization_id, project_id, incident_id, run_id, actor_id,
    idempotency_key, source_type, source_identity, source_version, data_classification,
    expected_content_digest, expected_byte_count, authorization_epoch, retention_class,
    residency_class, deletion_class, lifecycle_state, lifecycle_version, created_at
) ON evidence_artifacts TO opsmind_app;
GRANT INSERT (
    artifact_id, organization_id, project_id, incident_id, run_id, actor_id,
    idempotency_key, source_type, source_identity, source_version, data_classification,
    expected_content_digest, expected_byte_count, authorization_epoch, retention_class,
    residency_class, deletion_class, storage_key, lifecycle_state, lifecycle_version,
    storage_generation, upload_attempt_count, created_at, lifecycle_updated_at
) ON evidence_artifacts TO opsmind_app;
GRANT SELECT, INSERT ON evidence_artifact_events TO opsmind_app;
REVOKE UPDATE, DELETE, TRUNCATE ON evidence_artifacts, evidence_artifact_events FROM opsmind_app;
REVOKE ALL ON evidence_artifacts, evidence_artifact_events FROM opsmind_dispatcher;

REVOKE ALL ON FUNCTION public.opsmind_evidence_artifact_id(uuid, uuid, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_evidence_artifact_initial_event_id(uuid, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_validate_evidence_artifact_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_validate_evidence_artifact_event_append() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_require_evidence_artifact_initial_event() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_reject_evidence_artifact_mutation() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_assign_audit_chain() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.opsmind_evidence_artifact_id(uuid, uuid, uuid) TO opsmind_app;
GRANT EXECUTE ON FUNCTION public.opsmind_evidence_artifact_initial_event_id(uuid, uuid)
    TO opsmind_app;

COMMENT ON TABLE evidence_artifacts IS
    'Tenant-authoritative metadata for durable evidence artifacts; bodies remain outside PostgreSQL.';
COMMENT ON TABLE evidence_artifact_events IS
    'Immutable artifact lifecycle ledger; initial PENDING_UPLOAD event binds its audit row.';
COMMENT ON COLUMN evidence_artifacts.storage_key IS
    'Opaque internal storage reference. It is not an authorization credential and is never projected.';
COMMENT ON COLUMN evidence_artifacts.authorization_epoch IS
    'Conservative incident-version authorization epoch; current access is always rechecked before reads.';
COMMENT ON COLUMN evidence_artifacts.upload_attempt_id IS
    'Reserved for Phase 2 fenced upload claims; Phase 1 leaves it null.';
