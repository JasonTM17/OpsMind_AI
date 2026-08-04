-- Phase 4C: lifecycle control-plane transitions. Object bytes remain outside PostgreSQL.

CREATE OR REPLACE FUNCTION opsmind_evidence_artifact_control_event_id(
    p_organization_id uuid,
    p_artifact_id uuid,
    p_lifecycle_version bigint
) RETURNS uuid
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
SET search_path = pg_catalog, public, pg_temp AS $$
    WITH raw AS (
        SELECT public.digest(convert_to(
            'opsmind:artifact-control-event:v1:' || p_organization_id::text || ':'
            || p_artifact_id::text || ':' || p_lifecycle_version::text,
            'UTF8'
        ), 'sha256') AS bytes
    ), versioned AS (
        SELECT set_byte(set_byte(bytes, 6, (get_byte(bytes, 6) & 15) | 128),
                        8, (get_byte(bytes, 8) & 63) | 128) AS bytes
          FROM raw
    ), hex AS (SELECT encode(bytes, 'hex') AS value FROM versioned)
    SELECT format('%s-%s-%s-%s-%s', substr(value, 1, 8), substr(value, 9, 4),
        substr(value, 13, 4), substr(value, 17, 4), substr(value, 21, 12))::uuid
      FROM hex
$$;

ALTER TABLE evidence_artifacts
    DROP CONSTRAINT evidence_artifacts_lifecycle_state_check,
    ADD CONSTRAINT evidence_artifacts_lifecycle_state_check CHECK (lifecycle_state IN (
        'PENDING_UPLOAD', 'STORED', 'SCANNING', 'AVAILABLE', 'QUARANTINED', 'HELD',
        'TOMBSTONED', 'DELETION_REQUESTED', 'EXPIRED', 'PURGED', 'RECEIPT_RECORDED',
        'ORPHANED', 'FAILED'
    ));

ALTER TABLE evidence_artifact_events
    DROP CONSTRAINT evidence_artifact_events_lifecycle_from_state_check,
    DROP CONSTRAINT evidence_artifact_events_lifecycle_to_state_check,
    ADD CONSTRAINT evidence_artifact_events_lifecycle_from_state_check CHECK (
        lifecycle_from_state IS NULL OR lifecycle_from_state IN (
            'PENDING_UPLOAD', 'STORED', 'SCANNING', 'AVAILABLE', 'QUARANTINED', 'HELD',
            'TOMBSTONED', 'DELETION_REQUESTED', 'EXPIRED', 'PURGED', 'RECEIPT_RECORDED',
            'ORPHANED', 'FAILED'
        )
    ),
    ADD CONSTRAINT evidence_artifact_events_lifecycle_to_state_check CHECK (
        lifecycle_to_state IN (
            'PENDING_UPLOAD', 'STORED', 'SCANNING', 'AVAILABLE', 'QUARANTINED', 'HELD',
            'TOMBSTONED', 'DELETION_REQUESTED', 'EXPIRED', 'PURGED', 'RECEIPT_RECORDED',
            'ORPHANED', 'FAILED'
        )
    );

ALTER TABLE evidence_artifacts
    DROP CONSTRAINT evidence_artifacts_phase_2_lifecycle_fence,
    ADD CONSTRAINT evidence_artifacts_phase_3_lifecycle_fence CHECK (
        (
            lifecycle_state = 'PENDING_UPLOAD'
            AND lifecycle_version = 1
            AND storage_generation = 0
            AND storage_version_reference IS NULL
            AND encryption_metadata_reference IS NULL
            AND upload_attempt_count BETWEEN 0 AND 8
            AND (
                (upload_attempt_count = 0 AND upload_attempt_id IS NULL
                    AND upload_lease_expires_at IS NULL AND last_failure_code IS NULL)
                OR
                (upload_attempt_count BETWEEN 1 AND 8 AND upload_attempt_id IS NOT NULL
                    AND upload_lease_expires_at IS NOT NULL)
            )
            AND lifecycle_updated_at = created_at
        )
        OR
        (
            lifecycle_state <> 'PENDING_UPLOAD'
            AND lifecycle_version >= 2
            AND storage_generation >= 1
            AND storage_version_reference IS NOT NULL
            AND encryption_metadata_reference IS NOT NULL
            AND upload_attempt_id IS NOT NULL
            AND upload_lease_expires_at IS NOT NULL
            AND upload_attempt_count BETWEEN 1 AND 8
            AND lifecycle_updated_at >= created_at
        )
    );

ALTER TABLE evidence_artifact_events
    DROP CONSTRAINT evidence_artifact_events_phase_2_transition_fence,
    ADD CONSTRAINT evidence_artifact_events_phase_3_transition_fence CHECK (
        (
            lifecycle_version = 1
            AND lifecycle_from_state IS NULL
            AND lifecycle_to_state = 'PENDING_UPLOAD'
            AND upload_attempt_id IS NULL
        )
        OR
        (
            lifecycle_version = 2
            AND lifecycle_from_state = 'PENDING_UPLOAD'
            AND lifecycle_to_state = 'STORED'
            AND upload_attempt_id IS NOT NULL
        )
        OR
        (
            lifecycle_version >= 3
            AND lifecycle_from_state IS NOT NULL
            AND lifecycle_to_state IN (
                'AVAILABLE', 'SCANNING', 'QUARANTINED', 'HELD', 'TOMBSTONED',
                'DELETION_REQUESTED', 'EXPIRED', 'PURGED', 'RECEIPT_RECORDED',
                'ORPHANED', 'FAILED'
            )
            AND upload_attempt_id IS NULL
        )
    );

ALTER FUNCTION public.opsmind_validate_evidence_artifact_update()
    RENAME TO opsmind_validate_evidence_artifact_update_v015;

CREATE OR REPLACE FUNCTION opsmind_validate_evidence_artifact_update() RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF TG_OP IS DISTINCT FROM 'UPDATE'
       OR session_user <> 'opsmind_app'
       OR NEW.organization_id IS DISTINCT FROM public.opsmind_current_tenant_id()
       OR NEW.actor_id IS DISTINCT FROM public.opsmind_current_actor_id() THEN
        RAISE EXCEPTION 'artifact metadata mutation requires the bound application tenant and actor'
            USING ERRCODE = '42501';
    END IF;
    IF NEW.artifact_id IS DISTINCT FROM OLD.artifact_id
       OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
       OR NEW.project_id IS DISTINCT FROM OLD.project_id
       OR NEW.incident_id IS DISTINCT FROM OLD.incident_id
       OR NEW.run_id IS DISTINCT FROM OLD.run_id
       OR NEW.actor_id IS DISTINCT FROM OLD.actor_id
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.source_type IS DISTINCT FROM OLD.source_type
       OR NEW.source_identity IS DISTINCT FROM OLD.source_identity
       OR NEW.source_version IS DISTINCT FROM OLD.source_version
       OR NEW.data_classification IS DISTINCT FROM OLD.data_classification
       OR NEW.expected_content_digest IS DISTINCT FROM OLD.expected_content_digest
       OR NEW.expected_byte_count IS DISTINCT FROM OLD.expected_byte_count
       OR NEW.authorization_epoch IS DISTINCT FROM OLD.authorization_epoch
       OR NEW.retention_class IS DISTINCT FROM OLD.retention_class
       OR NEW.residency_class IS DISTINCT FROM OLD.residency_class
       OR NEW.deletion_class IS DISTINCT FROM OLD.deletion_class
       OR NEW.storage_key IS DISTINCT FROM OLD.storage_key
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'artifact identity, policy, and content expectation are immutable'
            USING ERRCODE = 'P7106';
    END IF;

    IF OLD.lifecycle_state <> 'PENDING_UPLOAD'
       AND NEW.lifecycle_state IS DISTINCT FROM OLD.lifecycle_state
       AND NEW.lifecycle_version = OLD.lifecycle_version + 1
       AND NEW.storage_generation IS NOT DISTINCT FROM OLD.storage_generation
       AND NEW.storage_version_reference IS NOT DISTINCT FROM OLD.storage_version_reference
       AND NEW.encryption_metadata_reference IS NOT DISTINCT FROM OLD.encryption_metadata_reference
       AND NEW.upload_attempt_id IS NOT DISTINCT FROM OLD.upload_attempt_id
       AND NEW.upload_lease_expires_at IS NOT DISTINCT FROM OLD.upload_lease_expires_at
       AND NEW.upload_attempt_count IS NOT DISTINCT FROM OLD.upload_attempt_count
       AND NEW.last_failure_code IS NOT DISTINCT FROM OLD.last_failure_code
       AND NEW.lifecycle_updated_at >= OLD.lifecycle_updated_at
       AND (
           (OLD.lifecycle_state = 'STORED' AND NEW.lifecycle_state IN ('SCANNING', 'FAILED', 'ORPHANED'))
           OR (OLD.lifecycle_state = 'SCANNING' AND NEW.lifecycle_state IN ('AVAILABLE', 'QUARANTINED', 'FAILED'))
           OR (OLD.lifecycle_state = 'AVAILABLE' AND NEW.lifecycle_state IN ('HELD', 'TOMBSTONED', 'DELETION_REQUESTED', 'EXPIRED'))
           OR (OLD.lifecycle_state = 'QUARANTINED' AND NEW.lifecycle_state IN ('TOMBSTONED', 'DELETION_REQUESTED', 'PURGED'))
           OR (OLD.lifecycle_state = 'HELD' AND NEW.lifecycle_state IN ('AVAILABLE', 'TOMBSTONED', 'DELETION_REQUESTED'))
           OR (OLD.lifecycle_state = 'TOMBSTONED' AND NEW.lifecycle_state IN ('AVAILABLE', 'HELD', 'PURGED'))
           OR (OLD.lifecycle_state IN ('DELETION_REQUESTED', 'EXPIRED')
               AND NEW.lifecycle_state IN ('TOMBSTONED', 'PURGED', 'HELD'))
           OR (OLD.lifecycle_state = 'PURGED' AND NEW.lifecycle_state = 'RECEIPT_RECORDED')
           OR (OLD.lifecycle_state IN ('ORPHANED', 'FAILED')
               AND NEW.lifecycle_state IN ('TOMBSTONED', 'DELETION_REQUESTED', 'PURGED'))
       ) THEN
        RETURN NEW;
    END IF;

    IF OLD.lifecycle_state = 'PENDING_UPLOAD'
       AND NEW.lifecycle_state = 'PENDING_UPLOAD' THEN
        IF NEW.lifecycle_version IS DISTINCT FROM OLD.lifecycle_version
           OR NEW.storage_generation IS DISTINCT FROM OLD.storage_generation
           OR NEW.storage_version_reference IS DISTINCT FROM OLD.storage_version_reference
           OR NEW.encryption_metadata_reference IS DISTINCT FROM OLD.encryption_metadata_reference
           OR NEW.lifecycle_updated_at IS DISTINCT FROM OLD.lifecycle_updated_at
           OR NEW.upload_attempt_count NOT BETWEEN OLD.upload_attempt_count AND OLD.upload_attempt_count + 1
           OR (NEW.upload_attempt_count = OLD.upload_attempt_count + 1 AND (
               NEW.upload_attempt_id IS NULL OR NEW.upload_attempt_id IS NOT DISTINCT FROM OLD.upload_attempt_id
               OR NEW.upload_lease_expires_at IS NULL OR NEW.upload_lease_expires_at <= clock_timestamp()
               OR NEW.last_failure_code IS NOT NULL))
           OR (NEW.upload_attempt_count = OLD.upload_attempt_count AND (
               NEW.upload_attempt_id IS DISTINCT FROM OLD.upload_attempt_id
               OR NEW.upload_lease_expires_at IS NULL
               OR NEW.upload_lease_expires_at > OLD.upload_lease_expires_at
               OR NEW.last_failure_code IS NULL)) THEN
            RAISE EXCEPTION 'artifact pending-upload claim mutation is invalid' USING ERRCODE = 'P7106';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.lifecycle_state = 'PENDING_UPLOAD'
       AND NEW.lifecycle_state = 'STORED'
       AND OLD.lifecycle_version = 1 AND NEW.lifecycle_version = 2
       AND OLD.storage_generation = 0 AND NEW.storage_generation = 1
       AND OLD.storage_version_reference IS NULL AND NEW.storage_version_reference IS NOT NULL
       AND OLD.encryption_metadata_reference IS NULL AND NEW.encryption_metadata_reference IS NOT NULL
       AND NEW.upload_attempt_id IS NOT NULL
       AND NEW.upload_attempt_id IS NOT DISTINCT FROM OLD.upload_attempt_id
       AND NEW.upload_lease_expires_at IS NOT DISTINCT FROM OLD.upload_lease_expires_at
       AND NEW.upload_attempt_count IS NOT DISTINCT FROM OLD.upload_attempt_count
       AND NEW.last_failure_code IS NULL
       AND NEW.lifecycle_updated_at >= OLD.lifecycle_updated_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'artifact lifecycle transition is not admitted' USING ERRCODE = 'P7106';
END
$$;

DROP TRIGGER evidence_artifacts_validate_update ON evidence_artifacts;
CREATE TRIGGER evidence_artifacts_validate_update
    BEFORE UPDATE ON evidence_artifacts
    FOR EACH ROW EXECUTE FUNCTION opsmind_validate_evidence_artifact_update();

ALTER FUNCTION public.opsmind_validate_evidence_artifact_event_append()
    RENAME TO opsmind_validate_evidence_artifact_event_append_v015;

CREATE OR REPLACE FUNCTION opsmind_validate_evidence_artifact_event_append() RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    artifact_row record;
BEGIN
    IF session_user = 'opsmind_app' AND (
        NEW.organization_id IS DISTINCT FROM public.opsmind_current_tenant_id()
        OR NEW.actor_id IS DISTINCT FROM public.opsmind_current_actor_id()
    ) THEN
        RAISE EXCEPTION 'artifact lifecycle event requires the bound tenant and actor'
            USING ERRCODE = '42501';
    END IF;
    SELECT artifact.project_id, artifact.incident_id, artifact.run_id, artifact.actor_id,
           artifact.lifecycle_state, artifact.lifecycle_version, artifact.storage_generation,
           artifact.upload_attempt_id AS authoritative_attempt_id,
           artifact.created_at, artifact.lifecycle_updated_at, attempt.status AS attempt_status,
           attempt.settled_at AS attempt_settled_at
      INTO artifact_row
      FROM public.evidence_artifacts artifact
      LEFT JOIN public.evidence_artifact_upload_attempts attempt
        ON attempt.organization_id = artifact.organization_id
       AND attempt.artifact_id = artifact.artifact_id
       AND attempt.upload_attempt_id = NEW.upload_attempt_id
     WHERE artifact.organization_id = NEW.organization_id
       AND artifact.artifact_id = NEW.artifact_id
     FOR KEY SHARE OF artifact;
    IF NOT FOUND
       OR NEW.project_id IS DISTINCT FROM artifact_row.project_id
       OR NEW.incident_id IS DISTINCT FROM artifact_row.incident_id
       OR NEW.run_id IS DISTINCT FROM artifact_row.run_id
       OR NEW.actor_id IS DISTINCT FROM artifact_row.actor_id
       OR NEW.audit_event_id IS DISTINCT FROM NEW.event_id THEN
        RAISE EXCEPTION 'artifact lifecycle event does not match authoritative metadata'
            USING ERRCODE = 'P7103';
    END IF;
    IF NEW.lifecycle_version >= 3
       AND NEW.event_id = public.opsmind_evidence_artifact_control_event_id(
           NEW.organization_id, NEW.artifact_id, NEW.lifecycle_version
       )
       AND NEW.lifecycle_from_state IS DISTINCT FROM NULL
       AND NEW.lifecycle_from_state IS DISTINCT FROM NEW.lifecycle_to_state
       AND NEW.lifecycle_version IS NOT DISTINCT FROM artifact_row.lifecycle_version
       AND NEW.lifecycle_to_state IS NOT DISTINCT FROM artifact_row.lifecycle_state
       AND (
           (NEW.lifecycle_from_state = 'STORED' AND NEW.lifecycle_to_state IN ('SCANNING', 'FAILED', 'ORPHANED'))
           OR (NEW.lifecycle_from_state = 'SCANNING' AND NEW.lifecycle_to_state IN ('AVAILABLE', 'QUARANTINED', 'FAILED'))
           OR (NEW.lifecycle_from_state = 'AVAILABLE' AND NEW.lifecycle_to_state IN ('HELD', 'TOMBSTONED', 'DELETION_REQUESTED', 'EXPIRED'))
           OR (NEW.lifecycle_from_state = 'QUARANTINED' AND NEW.lifecycle_to_state IN ('TOMBSTONED', 'DELETION_REQUESTED', 'PURGED'))
           OR (NEW.lifecycle_from_state = 'HELD' AND NEW.lifecycle_to_state IN ('AVAILABLE', 'TOMBSTONED', 'DELETION_REQUESTED'))
           OR (NEW.lifecycle_from_state = 'TOMBSTONED' AND NEW.lifecycle_to_state IN ('AVAILABLE', 'HELD', 'PURGED'))
           OR (NEW.lifecycle_from_state IN ('DELETION_REQUESTED', 'EXPIRED')
               AND NEW.lifecycle_to_state IN ('TOMBSTONED', 'PURGED', 'HELD'))
           OR (NEW.lifecycle_from_state = 'PURGED' AND NEW.lifecycle_to_state = 'RECEIPT_RECORDED')
           OR (NEW.lifecycle_from_state IN ('ORPHANED', 'FAILED')
               AND NEW.lifecycle_to_state IN ('TOMBSTONED', 'DELETION_REQUESTED', 'PURGED'))
       ) THEN
        RETURN NEW;
    END IF;
    IF NEW.lifecycle_version = 1
       AND NEW.event_id = public.opsmind_evidence_artifact_initial_event_id(
           NEW.organization_id, NEW.artifact_id)
       AND NEW.lifecycle_from_state IS NULL
       AND NEW.lifecycle_to_state = 'PENDING_UPLOAD'
       AND NEW.upload_attempt_id IS NULL
       AND artifact_row.lifecycle_state = 'PENDING_UPLOAD'
       AND artifact_row.lifecycle_version = 1
       AND NEW.occurred_at IS NOT DISTINCT FROM artifact_row.created_at THEN
        RETURN NEW;
    END IF;
    IF NEW.lifecycle_version = 2
       AND NEW.event_id = public.opsmind_evidence_artifact_lifecycle_event_id(
           NEW.organization_id, NEW.artifact_id, NEW.lifecycle_version, NEW.upload_attempt_id)
       AND NEW.lifecycle_from_state = 'PENDING_UPLOAD'
       AND NEW.lifecycle_to_state = 'STORED'
       AND NEW.upload_attempt_id IS NOT NULL
       AND NEW.upload_attempt_id IS NOT DISTINCT FROM artifact_row.authoritative_attempt_id
       AND artifact_row.lifecycle_state = 'STORED'
       AND artifact_row.lifecycle_version = 2
       AND artifact_row.storage_generation = 1
       AND artifact_row.attempt_status = 'STORED'
       AND artifact_row.attempt_settled_at IS NOT DISTINCT FROM artifact_row.lifecycle_updated_at
       AND NEW.occurred_at IS NOT DISTINCT FROM artifact_row.lifecycle_updated_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'artifact lifecycle event is not an admitted transition' USING ERRCODE = 'P7103';
END
$$;

DROP TRIGGER evidence_artifact_events_validate_append ON evidence_artifact_events;
CREATE TRIGGER evidence_artifact_events_validate_append
    BEFORE INSERT ON evidence_artifact_events
    FOR EACH ROW EXECUTE FUNCTION opsmind_validate_evidence_artifact_event_append();

ALTER FUNCTION public.opsmind_evidence_artifact_audit_matches(public.audit_events)
    RENAME TO opsmind_evidence_artifact_audit_matches_v015;

CREATE OR REPLACE FUNCTION opsmind_evidence_artifact_audit_matches(
    p_audit public.audit_events
) RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
STABLE
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    artifact_row record;
    base_payload_keys text[] := ARRAY[
        'eventId', 'organizationId', 'projectId', 'incidentId', 'runId',
        'artifactId', 'actorId', 'lifecycleVersion', 'lifecycleState',
        'fromState', 'toState', 'occurredAt'
    ];
BEGIN
    IF p_audit.action IN ('ARTIFACT_PENDING_UPLOAD', 'ARTIFACT_STORED') THEN
        RETURN public.opsmind_evidence_artifact_audit_matches_v015(p_audit);
    END IF;
    IF p_audit.action IS DISTINCT FROM 'ARTIFACT_LIFECYCLE_CHANGED' THEN
        RETURN false;
    END IF;

    SELECT event_row.event_id, event_row.organization_id, event_row.project_id,
           event_row.incident_id, event_row.run_id, event_row.artifact_id,
           event_row.actor_id, event_row.lifecycle_version,
           event_row.lifecycle_from_state, event_row.lifecycle_to_state,
           event_row.occurred_at, event_row.audit_event_id,
           event_row.upload_attempt_id,
           artifact.lifecycle_state AS authoritative_state,
           artifact.lifecycle_version AS authoritative_version
      INTO artifact_row
      FROM public.evidence_artifact_events event_row
      JOIN public.evidence_artifacts artifact
        ON artifact.organization_id = event_row.organization_id
       AND artifact.artifact_id = event_row.artifact_id
     WHERE event_row.event_id = p_audit.event_id
       AND event_row.organization_id = p_audit.organization_id;

    IF NOT FOUND
       OR p_audit.actor_id IS DISTINCT FROM artifact_row.actor_id
       OR p_audit.resource_type IS DISTINCT FROM 'evidence_artifact'
       OR p_audit.resource_id IS DISTINCT FROM artifact_row.artifact_id::text
       OR p_audit.correlation_id IS DISTINCT FROM artifact_row.artifact_id
       OR p_audit.occurred_at IS DISTINCT FROM artifact_row.occurred_at
       OR artifact_row.audit_event_id IS DISTINCT FROM p_audit.event_id
       OR artifact_row.lifecycle_version < 3
       OR artifact_row.lifecycle_from_state IS NULL
       OR artifact_row.lifecycle_from_state = artifact_row.lifecycle_to_state
       OR artifact_row.upload_attempt_id IS NOT NULL
       OR artifact_row.event_id IS DISTINCT FROM
            public.opsmind_evidence_artifact_control_event_id(
                artifact_row.organization_id,
                artifact_row.artifact_id,
                artifact_row.lifecycle_version
            )
       OR artifact_row.authoritative_state IS DISTINCT FROM artifact_row.lifecycle_to_state
       OR artifact_row.authoritative_version IS DISTINCT FROM artifact_row.lifecycle_version
       OR jsonb_typeof(p_audit.payload -> 'eventId') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'organizationId') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'projectId') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'incidentId') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'runId') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'artifactId') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'actorId') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'lifecycleVersion') IS DISTINCT FROM 'number'
       OR jsonb_typeof(p_audit.payload -> 'lifecycleState') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'fromState') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'toState') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'occurredAt') IS DISTINCT FROM 'string'
       OR p_audit.payload ->> 'eventId' IS DISTINCT FROM artifact_row.event_id::text
       OR p_audit.payload ->> 'organizationId'
            IS DISTINCT FROM artifact_row.organization_id::text
       OR p_audit.payload ->> 'projectId' IS DISTINCT FROM artifact_row.project_id::text
       OR p_audit.payload ->> 'incidentId' IS DISTINCT FROM artifact_row.incident_id::text
       OR p_audit.payload ->> 'runId' IS DISTINCT FROM artifact_row.run_id::text
       OR p_audit.payload ->> 'artifactId' IS DISTINCT FROM artifact_row.artifact_id::text
       OR p_audit.payload ->> 'actorId' IS DISTINCT FROM artifact_row.actor_id::text
       OR p_audit.payload ->> 'lifecycleVersion'
            IS DISTINCT FROM artifact_row.lifecycle_version::text
       OR p_audit.payload ->> 'lifecycleState'
            IS DISTINCT FROM artifact_row.lifecycle_to_state
       OR p_audit.payload ->> 'fromState'
            IS DISTINCT FROM artifact_row.lifecycle_from_state
       OR p_audit.payload ->> 'toState' IS DISTINCT FROM artifact_row.lifecycle_to_state
       OR (p_audit.payload ->> 'occurredAt')::timestamptz
            IS DISTINCT FROM artifact_row.occurred_at
       OR (
            p_audit.payload ? 'reason'
            AND (
                jsonb_typeof(p_audit.payload -> 'reason') IS DISTINCT FROM 'string'
                OR p_audit.payload ->> 'reason' !~ '^[a-z0-9][a-z0-9._-]{0,127}$'
            )
       ) THEN
        RETURN false;
    END IF;

    RETURN public.opsmind_json_object_has_exact_keys(
        p_audit.payload,
        CASE WHEN p_audit.payload ? 'reason'
            THEN array_append(base_payload_keys, 'reason')
            ELSE base_payload_keys
        END
    );
END
$$;

CREATE OR REPLACE FUNCTION opsmind_require_evidence_artifact_control_event() RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF OLD.lifecycle_state <> 'PENDING_UPLOAD'
       AND NEW.lifecycle_state IS DISTINCT FROM OLD.lifecycle_state
       AND NOT EXISTS (
            SELECT 1
              FROM public.evidence_artifact_events event_row
              JOIN public.audit_events audit_row
                ON audit_row.event_id = event_row.audit_event_id
               AND audit_row.organization_id = event_row.organization_id
             WHERE event_row.organization_id = NEW.organization_id
               AND event_row.artifact_id = NEW.artifact_id
               AND event_row.event_id = public.opsmind_evidence_artifact_control_event_id(
                   NEW.organization_id, NEW.artifact_id, NEW.lifecycle_version
               )
               AND event_row.lifecycle_version = NEW.lifecycle_version
               AND event_row.lifecycle_from_state = OLD.lifecycle_state
               AND event_row.lifecycle_to_state = NEW.lifecycle_state
               AND event_row.upload_attempt_id IS NULL
               AND event_row.occurred_at = NEW.lifecycle_updated_at
               AND audit_row.action = 'ARTIFACT_LIFECYCLE_CHANGED'
               AND audit_row.schema_version = 'evidence-artifact-audit-v1'
       ) THEN
        RAISE EXCEPTION 'artifact lifecycle metadata requires its control event and audit row'
            USING ERRCODE = 'P7104';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER evidence_artifacts_require_control_event
    AFTER UPDATE ON evidence_artifacts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION opsmind_require_evidence_artifact_control_event();

ALTER TABLE audit_events
    DROP CONSTRAINT audit_events_evidence_artifact_contract,
    ADD CONSTRAINT audit_events_evidence_artifact_contract CHECK (
        schema_version <> 'evidence-artifact-audit-v1'
        OR (
            actor_id IS NOT NULL
            AND resource_type = 'evidence_artifact'
            AND resource_id = correlation_id::text
            AND payload ->> 'eventId' = event_id::text
            AND payload ->> 'organizationId' = organization_id::text
            AND payload ->> 'artifactId' = resource_id
            AND payload ->> 'actorId' = actor_id::text
            AND (
                (
                    action = 'ARTIFACT_PENDING_UPLOAD'
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
                )
                OR
                (
                    action = 'ARTIFACT_STORED'
                    AND payload ?& ARRAY[
                        'eventId', 'organizationId', 'projectId', 'incidentId', 'runId',
                        'artifactId', 'actorId', 'lifecycleVersion', 'lifecycleState',
                        'contentDigest', 'byteCount', 'dataClassification', 'retentionClass',
                        'storageGeneration', 'occurredAt'
                    ]
                    AND payload - ARRAY[
                        'eventId', 'organizationId', 'projectId', 'incidentId', 'runId',
                        'artifactId', 'actorId', 'lifecycleVersion', 'lifecycleState',
                        'contentDigest', 'byteCount', 'dataClassification', 'retentionClass',
                        'storageGeneration', 'occurredAt'
                    ] = '{}'::jsonb
                )
                OR
                (
                    action = 'ARTIFACT_LIFECYCLE_CHANGED'
                    AND payload ?& ARRAY[
                        'eventId', 'organizationId', 'projectId', 'incidentId', 'runId',
                        'artifactId', 'actorId', 'lifecycleVersion', 'lifecycleState',
                        'fromState', 'toState', 'occurredAt'
                    ]
                    AND payload - ARRAY[
                        'eventId', 'organizationId', 'projectId', 'incidentId', 'runId',
                        'artifactId', 'actorId', 'lifecycleVersion', 'lifecycleState',
                        'fromState', 'toState', 'occurredAt', 'reason'
                    ] = '{}'::jsonb
                )
            )
        )
    );

-- The existing deferred trigger remains authoritative for PENDING_UPLOAD -> STORED.
-- Generic lifecycle transitions are admitted only after their event and audit rows exist.

REVOKE ALL ON FUNCTION public.opsmind_evidence_artifact_control_event_id(
    uuid, uuid, bigint
) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_validate_evidence_artifact_update_v015() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_validate_evidence_artifact_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_validate_evidence_artifact_event_append_v015() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_validate_evidence_artifact_event_append() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_evidence_artifact_audit_matches_v015(
    public.audit_events
) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_evidence_artifact_audit_matches(
    public.audit_events
) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_require_evidence_artifact_control_event() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.opsmind_evidence_artifact_control_event_id(
    uuid, uuid, bigint
) TO opsmind_app;
