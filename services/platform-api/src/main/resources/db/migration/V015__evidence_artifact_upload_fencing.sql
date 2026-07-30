-- Phase 4C: fenced object-upload claims and the first durable STORED transition.
-- Object I/O remains outside PostgreSQL. These capabilities only own leases,
-- metadata settlement, and the atomic lifecycle-event/audit obligation.

CREATE OR REPLACE FUNCTION opsmind_evidence_artifact_lifecycle_event_id(
    p_organization_id uuid,
    p_artifact_id uuid,
    p_lifecycle_version bigint,
    p_upload_attempt_id uuid
) RETURNS uuid
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
SET search_path = pg_catalog, public, pg_temp AS $$
    WITH raw AS (
        SELECT public.digest(convert_to(
            'opsmind:artifact-event:v2:' || p_organization_id::text || ':'
            || p_artifact_id::text || ':' || p_lifecycle_version::text || ':'
            || p_upload_attempt_id::text,
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

ALTER TABLE evidence_artifacts
    ADD COLUMN storage_version_reference varchar(1024),
    DROP CONSTRAINT evidence_artifacts_phase_1_pending_only,
    ADD CONSTRAINT evidence_artifacts_storage_version_reference_safe CHECK (
        storage_version_reference IS NULL
        OR (
            lower(storage_version_reference) <> 'null'
            AND btrim(storage_version_reference) <> ''
            AND octet_length(storage_version_reference) <= 1024
        )
    ),
    ADD CONSTRAINT evidence_artifacts_phase_2_lifecycle_fence CHECK (
        (
            lifecycle_state = 'PENDING_UPLOAD'
            AND lifecycle_version = 1
            AND storage_generation = 0
            AND storage_version_reference IS NULL
            AND encryption_metadata_reference IS NULL
            AND upload_attempt_count BETWEEN 0 AND 8
            AND (
                (
                    upload_attempt_count = 0
                    AND upload_attempt_id IS NULL
                    AND upload_lease_expires_at IS NULL
                    AND last_failure_code IS NULL
                )
                OR
                (
                    upload_attempt_count BETWEEN 1 AND 8
                    AND upload_attempt_id IS NOT NULL
                    AND upload_lease_expires_at IS NOT NULL
                )
            )
            AND lifecycle_updated_at = created_at
        )
        OR
        (
            lifecycle_state = 'STORED'
            AND lifecycle_version = 2
            AND storage_generation = 1
            AND storage_version_reference IS NOT NULL
            AND encryption_metadata_reference IS NOT NULL
            AND upload_attempt_id IS NOT NULL
            AND upload_lease_expires_at IS NOT NULL
            AND upload_attempt_count BETWEEN 1 AND 8
            AND last_failure_code IS NULL
            AND lifecycle_updated_at >= created_at
        )
    );

CREATE TABLE evidence_artifact_upload_attempts (
    organization_id     uuid NOT NULL REFERENCES organizations(id),
    artifact_id         uuid NOT NULL,
    upload_attempt_id   uuid NOT NULL,
    lifecycle_version   bigint NOT NULL,
    attempt_number      integer NOT NULL,
    status              varchar(32) NOT NULL,
    claimed_at          timestamptz NOT NULL,
    lease_expires_at    timestamptz NOT NULL,
    settled_at          timestamptz,
    failure_code        varchar(128),
    PRIMARY KEY (organization_id, upload_attempt_id),
    UNIQUE (organization_id, artifact_id, upload_attempt_id),
    UNIQUE (organization_id, artifact_id, attempt_number),
    FOREIGN KEY (organization_id, artifact_id)
        REFERENCES evidence_artifacts(organization_id, artifact_id),
    CHECK (lifecycle_version = 1),
    CHECK (attempt_number BETWEEN 1 AND 8),
    CHECK (status IN ('CLAIMED', 'FAILED', 'UNCERTAIN', 'ORPHANED', 'STORED')),
    CHECK (lease_expires_at > claimed_at),
    CHECK (failure_code IS NULL OR failure_code ~ '^[a-z0-9][a-z0-9._-]{0,127}$'),
    CHECK (
        (status = 'CLAIMED' AND settled_at IS NULL AND failure_code IS NULL)
        OR
        (status = 'STORED' AND settled_at IS NOT NULL AND failure_code IS NULL)
        OR
        (status IN ('FAILED', 'UNCERTAIN', 'ORPHANED')
            AND settled_at IS NOT NULL
            AND failure_code IS NOT NULL)
    )
);

CREATE INDEX evidence_artifact_upload_attempts_artifact_order_idx
    ON evidence_artifact_upload_attempts (
        organization_id, artifact_id, attempt_number DESC
    );
CREATE INDEX evidence_artifact_upload_attempts_active_lease_idx
    ON evidence_artifact_upload_attempts (
        organization_id, lease_expires_at, artifact_id
    )
    WHERE status IN ('CLAIMED', 'UNCERTAIN');

ALTER TABLE evidence_artifacts
    ADD CONSTRAINT evidence_artifacts_current_upload_attempt_fk
        FOREIGN KEY (organization_id, artifact_id, upload_attempt_id)
        REFERENCES evidence_artifact_upload_attempts(
            organization_id, artifact_id, upload_attempt_id
        );

ALTER TABLE evidence_artifact_events
    ADD COLUMN upload_attempt_id uuid,
    DROP CONSTRAINT evidence_artifact_events_phase_1_initial_only,
    ADD CONSTRAINT evidence_artifact_events_upload_attempt_fk
        FOREIGN KEY (organization_id, artifact_id, upload_attempt_id)
        REFERENCES evidence_artifact_upload_attempts(
            organization_id, artifact_id, upload_attempt_id
        ),
    ADD CONSTRAINT evidence_artifact_events_phase_2_transition_fence CHECK (
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
    );

CREATE OR REPLACE FUNCTION opsmind_validate_evidence_artifact_update() RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    actor_id uuid := public.opsmind_current_actor_id();
BEGIN
    IF TG_OP IS DISTINCT FROM 'UPDATE'
       OR session_user <> 'opsmind_app'
       OR NEW.organization_id IS DISTINCT FROM public.opsmind_current_tenant_id()
       OR NEW.actor_id IS DISTINCT FROM actor_id THEN
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

    IF OLD.lifecycle_state = 'PENDING_UPLOAD'
       AND NEW.lifecycle_state = 'PENDING_UPLOAD' THEN
        IF NEW.lifecycle_version IS DISTINCT FROM OLD.lifecycle_version
           OR NEW.storage_generation IS DISTINCT FROM OLD.storage_generation
           OR NEW.storage_version_reference IS DISTINCT FROM OLD.storage_version_reference
           OR NEW.encryption_metadata_reference
                IS DISTINCT FROM OLD.encryption_metadata_reference
           OR NEW.lifecycle_updated_at IS DISTINCT FROM OLD.lifecycle_updated_at
           OR NEW.upload_attempt_count NOT BETWEEN OLD.upload_attempt_count
                AND OLD.upload_attempt_count + 1
           OR (
                NEW.upload_attempt_count = OLD.upload_attempt_count + 1
                AND (
                    NEW.upload_attempt_id IS NULL
                    OR NEW.upload_attempt_id IS NOT DISTINCT FROM OLD.upload_attempt_id
                    OR NEW.upload_lease_expires_at IS NULL
                    OR NEW.upload_lease_expires_at <= clock_timestamp()
                    OR NEW.last_failure_code IS NOT NULL
                )
           )
           OR (
                NEW.upload_attempt_count = OLD.upload_attempt_count
                AND (
                    NEW.upload_attempt_id IS DISTINCT FROM OLD.upload_attempt_id
                    OR NEW.upload_lease_expires_at IS NULL
                    OR NEW.upload_lease_expires_at > OLD.upload_lease_expires_at
                    OR NEW.last_failure_code IS NULL
                )
           ) THEN
            RAISE EXCEPTION 'artifact pending-upload claim mutation is invalid'
                USING ERRCODE = 'P7106';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.lifecycle_state = 'PENDING_UPLOAD'
       AND NEW.lifecycle_state = 'STORED'
       AND OLD.lifecycle_version = 1
       AND NEW.lifecycle_version = 2
       AND OLD.storage_generation = 0
       AND NEW.storage_generation = 1
       AND OLD.storage_version_reference IS NULL
       AND NEW.storage_version_reference IS NOT NULL
       AND OLD.encryption_metadata_reference IS NULL
       AND NEW.encryption_metadata_reference IS NOT NULL
       AND NEW.upload_attempt_id IS NOT NULL
       AND NEW.upload_attempt_id IS NOT DISTINCT FROM OLD.upload_attempt_id
       AND NEW.upload_lease_expires_at IS NOT DISTINCT FROM OLD.upload_lease_expires_at
       AND NEW.upload_attempt_count IS NOT DISTINCT FROM OLD.upload_attempt_count
       AND NEW.last_failure_code IS NULL
       AND NEW.lifecycle_updated_at >= OLD.lifecycle_updated_at THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'artifact lifecycle transition is not admitted'
        USING ERRCODE = 'P7106';
END
$$;

DROP TRIGGER evidence_artifacts_no_update ON evidence_artifacts;
CREATE TRIGGER evidence_artifacts_validate_update
    BEFORE UPDATE ON evidence_artifacts
    FOR EACH ROW EXECUTE FUNCTION opsmind_validate_evidence_artifact_update();
CREATE TRIGGER evidence_artifacts_no_delete
    BEFORE DELETE ON evidence_artifacts
    FOR EACH ROW EXECUTE FUNCTION opsmind_reject_evidence_artifact_mutation();

CREATE OR REPLACE FUNCTION opsmind_claim_evidence_artifact_upload(
    p_organization_id uuid,
    p_project_id uuid,
    p_incident_id uuid,
    p_run_id uuid,
    p_artifact_id uuid,
    p_upload_attempt_id uuid,
    p_expected_lifecycle_version bigint,
    p_lease_duration_ms bigint
) RETURNS TABLE (
    artifact_id uuid,
    storage_key varchar,
    expected_content_digest bytea,
    expected_byte_count bigint,
    authorization_epoch bigint,
    lifecycle_version bigint,
    upload_attempt_id uuid,
    upload_attempt_count integer,
    upload_lease_expires_at timestamptz,
    probe_required boolean,
    reconciliation_required boolean
)
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    artifact_row record;
    active_attempt record;
    supplied_attempt record;
    bound_actor_id uuid := public.opsmind_current_actor_id();
    db_now timestamptz;
    lease_deadline timestamptz;
    requires_probe boolean := false;
BEGIN
    IF session_user <> 'opsmind_app'
       OR p_organization_id IS DISTINCT FROM public.opsmind_current_tenant_id()
       OR bound_actor_id IS NULL THEN
        RAISE EXCEPTION 'artifact upload claim requires the bound application identity'
            USING ERRCODE = '42501';
    END IF;
    IF p_project_id IS NULL
       OR p_incident_id IS NULL
       OR p_run_id IS NULL
       OR p_artifact_id IS NULL
       OR p_upload_attempt_id IS NULL
       OR p_expected_lifecycle_version IS NULL
       OR p_lease_duration_ms IS NULL
       OR p_lease_duration_ms NOT BETWEEN 5000 AND 300000 THEN
        RAISE EXCEPTION 'artifact upload claim input is outside policy'
            USING ERRCODE = '22023';
    END IF;

    SELECT artifact.*,
           incident.version AS current_authorization_epoch,
           run_row.actor_id AS run_actor_id
      INTO artifact_row
      FROM public.evidence_artifacts artifact
      JOIN public.incidents incident
        ON incident.id = artifact.incident_id
       AND incident.organization_id = artifact.organization_id
       AND incident.project_id = artifact.project_id
      JOIN public.investigation_runs run_row
        ON run_row.run_id = artifact.run_id
       AND run_row.organization_id = artifact.organization_id
       AND run_row.project_id = artifact.project_id
       AND run_row.incident_id = artifact.incident_id
     WHERE artifact.organization_id = p_organization_id
       AND artifact.project_id = p_project_id
       AND artifact.incident_id = p_incident_id
       AND artifact.run_id = p_run_id
       AND artifact.artifact_id = p_artifact_id
       AND artifact.actor_id = bound_actor_id
     FOR UPDATE OF artifact, incident;
    IF NOT FOUND
       OR artifact_row.run_actor_id IS DISTINCT FROM bound_actor_id
       OR artifact_row.authorization_epoch
            IS DISTINCT FROM artifact_row.current_authorization_epoch
       OR artifact_row.lifecycle_state IS DISTINCT FROM 'PENDING_UPLOAD'
       OR artifact_row.lifecycle_version IS DISTINCT FROM p_expected_lifecycle_version THEN
        RAISE EXCEPTION 'artifact upload claim does not match authorized metadata'
            USING ERRCODE = 'P7103';
    END IF;
    db_now := clock_timestamp();

    SELECT attempt.*
      INTO supplied_attempt
      FROM public.evidence_artifact_upload_attempts attempt
     WHERE attempt.organization_id = p_organization_id
       AND attempt.upload_attempt_id = p_upload_attempt_id
     FOR UPDATE;
    IF FOUND THEN
        IF supplied_attempt.artifact_id IS DISTINCT FROM p_artifact_id
           OR supplied_attempt.lifecycle_version
                IS DISTINCT FROM p_expected_lifecycle_version
           OR supplied_attempt.status IS DISTINCT FROM 'CLAIMED'
           OR artifact_row.upload_attempt_id IS DISTINCT FROM p_upload_attempt_id
           OR supplied_attempt.attempt_number
                IS DISTINCT FROM artifact_row.upload_attempt_count
           OR supplied_attempt.lease_expires_at
                IS DISTINCT FROM artifact_row.upload_lease_expires_at
           OR artifact_row.upload_lease_expires_at <= db_now THEN
            RAISE EXCEPTION 'artifact upload attempt identifier cannot be reused'
                USING ERRCODE = 'P7107';
        END IF;
        SELECT EXISTS (
            SELECT 1
              FROM public.evidence_artifact_upload_attempts prior
             WHERE prior.organization_id = p_organization_id
               AND prior.artifact_id = p_artifact_id
               AND prior.attempt_number < supplied_attempt.attempt_number
               AND prior.status = 'UNCERTAIN'
        ) INTO requires_probe;
        RETURN QUERY
        SELECT artifact_row.artifact_id,
               artifact_row.storage_key,
               artifact_row.expected_content_digest,
               artifact_row.expected_byte_count,
               artifact_row.authorization_epoch,
               artifact_row.lifecycle_version,
               artifact_row.upload_attempt_id,
               artifact_row.upload_attempt_count,
               artifact_row.upload_lease_expires_at,
               requires_probe,
               false;
        RETURN;
    END IF;

    IF artifact_row.upload_attempt_id IS NOT NULL THEN
        SELECT attempt.*
          INTO active_attempt
          FROM public.evidence_artifact_upload_attempts attempt
         WHERE attempt.organization_id = p_organization_id
           AND attempt.artifact_id = p_artifact_id
           AND attempt.upload_attempt_id = artifact_row.upload_attempt_id
         FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'artifact upload fence references no durable attempt'
                USING ERRCODE = 'P7107';
        END IF;
        IF active_attempt.attempt_number
                IS DISTINCT FROM artifact_row.upload_attempt_count
           OR active_attempt.lease_expires_at
                IS DISTINCT FROM artifact_row.upload_lease_expires_at THEN
            RAISE EXCEPTION 'artifact upload fence does not match its durable attempt'
                USING ERRCODE = 'P7107';
        END IF;
        IF active_attempt.status = 'ORPHANED' THEN
            RAISE EXCEPTION 'orphaned artifact object requires operator reconciliation'
                USING ERRCODE = 'P7107';
        END IF;
        IF active_attempt.status = 'STORED' THEN
            RAISE EXCEPTION 'stored artifact cannot be reclaimed'
                USING ERRCODE = 'P7107';
        END IF;
        IF active_attempt.status IN ('CLAIMED', 'UNCERTAIN')
           AND artifact_row.upload_lease_expires_at > db_now THEN
            RAISE EXCEPTION 'artifact upload already has an active lease'
                USING ERRCODE = 'P7107';
        END IF;
        IF active_attempt.status = 'CLAIMED' THEN
            UPDATE public.evidence_artifact_upload_attempts AS attempt
               SET status = 'ORPHANED',
                   settled_at = db_now,
                   failure_code = 'artifact.lease-expired-unsettled'
             WHERE attempt.organization_id = p_organization_id
               AND attempt.upload_attempt_id = active_attempt.upload_attempt_id;
            UPDATE public.evidence_artifacts AS artifact
               SET last_failure_code = 'artifact.lease-expired-unsettled'
             WHERE artifact.organization_id = p_organization_id
               AND artifact.artifact_id = p_artifact_id;
            RETURN QUERY
            SELECT artifact_row.artifact_id,
                   artifact_row.storage_key,
                   artifact_row.expected_content_digest,
                   artifact_row.expected_byte_count,
                   artifact_row.authorization_epoch,
                   artifact_row.lifecycle_version,
                   active_attempt.upload_attempt_id,
                   active_attempt.attempt_number,
                   active_attempt.lease_expires_at,
                   false,
                   true;
            RETURN;
        END IF;
        requires_probe := active_attempt.status = 'UNCERTAIN';
    END IF;

    IF artifact_row.upload_attempt_count >= 8 THEN
        RAISE EXCEPTION 'artifact upload attempt limit is exhausted'
            USING ERRCODE = 'P7107';
    END IF;

    lease_deadline := db_now + p_lease_duration_ms * interval '1 millisecond';
    INSERT INTO public.evidence_artifact_upload_attempts (
        organization_id, artifact_id, upload_attempt_id, lifecycle_version,
        attempt_number, status, claimed_at, lease_expires_at
    ) VALUES (
        p_organization_id, p_artifact_id, p_upload_attempt_id,
        p_expected_lifecycle_version, artifact_row.upload_attempt_count + 1,
        'CLAIMED', db_now, lease_deadline
    );

    UPDATE public.evidence_artifacts AS artifact
       SET upload_attempt_id = p_upload_attempt_id,
           upload_lease_expires_at = lease_deadline,
           upload_attempt_count = artifact_row.upload_attempt_count + 1,
           last_failure_code = NULL
     WHERE artifact.organization_id = p_organization_id
       AND artifact.artifact_id = p_artifact_id
    RETURNING * INTO artifact_row;

    RETURN QUERY
    SELECT artifact_row.artifact_id,
           artifact_row.storage_key,
           artifact_row.expected_content_digest,
           artifact_row.expected_byte_count,
           artifact_row.authorization_epoch,
           artifact_row.lifecycle_version,
           artifact_row.upload_attempt_id,
           artifact_row.upload_attempt_count,
           artifact_row.upload_lease_expires_at,
           requires_probe,
           false;
END
$$;

CREATE OR REPLACE FUNCTION opsmind_settle_evidence_artifact_upload(
    p_organization_id uuid,
    p_project_id uuid,
    p_incident_id uuid,
    p_run_id uuid,
    p_artifact_id uuid,
    p_upload_attempt_id uuid,
    p_expected_lifecycle_version bigint,
    p_outcome varchar,
    p_observed_digest bytea,
    p_observed_byte_count bigint,
    p_storage_version_reference varchar,
    p_encryption_metadata_reference varchar,
    p_failure_code varchar
) RETURNS TABLE (
    transition_applied boolean,
    lifecycle_state varchar,
    lifecycle_version bigint,
    storage_generation bigint,
    lifecycle_updated_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    artifact_row record;
    attempt_row record;
    bound_actor_id uuid := public.opsmind_current_actor_id();
    db_now timestamptz;
    transition_at timestamptz;
BEGIN
    IF session_user <> 'opsmind_app'
       OR p_organization_id IS DISTINCT FROM public.opsmind_current_tenant_id()
       OR bound_actor_id IS NULL THEN
        RAISE EXCEPTION 'artifact upload settlement requires the bound application identity'
            USING ERRCODE = '42501';
    END IF;
    IF p_project_id IS NULL
       OR p_incident_id IS NULL
       OR p_run_id IS NULL
       OR p_artifact_id IS NULL
       OR p_upload_attempt_id IS NULL
       OR p_expected_lifecycle_version IS NULL
       OR p_outcome IS NULL
       OR p_outcome NOT IN ('STORED', 'FAILED', 'UNCERTAIN', 'ORPHANED') THEN
        RAISE EXCEPTION 'artifact upload settlement input is invalid'
            USING ERRCODE = '22023';
    END IF;
    IF p_outcome = 'STORED' THEN
        IF p_observed_digest IS NULL
           OR octet_length(p_observed_digest) <> 32
           OR p_observed_byte_count IS NULL
           OR p_observed_byte_count < 1
           OR p_storage_version_reference IS NULL
           OR lower(p_storage_version_reference) = 'null'
           OR btrim(p_storage_version_reference) = ''
           OR octet_length(p_storage_version_reference) > 1024
           OR p_encryption_metadata_reference IS NULL
           OR p_encryption_metadata_reference
                !~ '^[A-Za-z0-9][A-Za-z0-9_.:@/-]{0,255}$'
           OR p_failure_code IS NOT NULL THEN
            RAISE EXCEPTION 'stored artifact settlement metadata is invalid'
                USING ERRCODE = '22023';
        END IF;
    ELSIF p_observed_digest IS NOT NULL
       OR p_observed_byte_count IS NOT NULL
       OR p_storage_version_reference IS NOT NULL
       OR p_encryption_metadata_reference IS NOT NULL
       OR p_failure_code IS NULL
       OR p_failure_code !~ '^[a-z0-9][a-z0-9._-]{0,127}$' THEN
        RAISE EXCEPTION 'failed artifact settlement metadata is invalid'
            USING ERRCODE = '22023';
    END IF;

    SELECT artifact.*,
           incident.version AS current_authorization_epoch,
           run_row.actor_id AS run_actor_id
      INTO artifact_row
      FROM public.evidence_artifacts artifact
      JOIN public.incidents incident
        ON incident.id = artifact.incident_id
       AND incident.organization_id = artifact.organization_id
       AND incident.project_id = artifact.project_id
      JOIN public.investigation_runs run_row
        ON run_row.run_id = artifact.run_id
       AND run_row.organization_id = artifact.organization_id
       AND run_row.project_id = artifact.project_id
       AND run_row.incident_id = artifact.incident_id
     WHERE artifact.organization_id = p_organization_id
       AND artifact.project_id = p_project_id
       AND artifact.incident_id = p_incident_id
       AND artifact.run_id = p_run_id
       AND artifact.artifact_id = p_artifact_id
       AND artifact.actor_id = bound_actor_id
     FOR UPDATE OF artifact, incident;
    IF NOT FOUND
       OR artifact_row.run_actor_id IS DISTINCT FROM bound_actor_id
       OR artifact_row.authorization_epoch
            IS DISTINCT FROM artifact_row.current_authorization_epoch THEN
        RAISE EXCEPTION 'artifact upload settlement does not match authorized metadata'
            USING ERRCODE = 'P7103';
    END IF;

    SELECT attempt.*
      INTO attempt_row
      FROM public.evidence_artifact_upload_attempts attempt
     WHERE attempt.organization_id = p_organization_id
       AND attempt.artifact_id = p_artifact_id
       AND attempt.upload_attempt_id = p_upload_attempt_id
     FOR UPDATE;
    IF NOT FOUND
       OR attempt_row.lifecycle_version IS DISTINCT FROM p_expected_lifecycle_version THEN
        RAISE EXCEPTION 'artifact upload settlement attempt is stale'
            USING ERRCODE = 'P7107';
    END IF;
    db_now := clock_timestamp();

    IF artifact_row.lifecycle_state = 'STORED'
       AND p_outcome = 'STORED'
       AND artifact_row.lifecycle_version = p_expected_lifecycle_version + 1
       AND artifact_row.upload_attempt_id IS NOT DISTINCT FROM p_upload_attempt_id
       AND attempt_row.status = 'STORED'
       AND artifact_row.expected_content_digest IS NOT DISTINCT FROM p_observed_digest
       AND artifact_row.expected_byte_count IS NOT DISTINCT FROM p_observed_byte_count
       AND artifact_row.storage_version_reference
            IS NOT DISTINCT FROM p_storage_version_reference
       AND artifact_row.encryption_metadata_reference
            IS NOT DISTINCT FROM p_encryption_metadata_reference THEN
        RETURN QUERY
        SELECT false,
               artifact_row.lifecycle_state,
               artifact_row.lifecycle_version,
               artifact_row.storage_generation,
               artifact_row.lifecycle_updated_at;
        RETURN;
    END IF;

    IF artifact_row.lifecycle_state IS DISTINCT FROM 'PENDING_UPLOAD'
       OR artifact_row.lifecycle_version IS DISTINCT FROM p_expected_lifecycle_version
       OR artifact_row.upload_attempt_id IS DISTINCT FROM p_upload_attempt_id
       OR artifact_row.upload_lease_expires_at IS NULL
       OR artifact_row.upload_lease_expires_at <= db_now
       OR artifact_row.upload_lease_expires_at
            IS DISTINCT FROM attempt_row.lease_expires_at
       OR artifact_row.upload_attempt_count
            IS DISTINCT FROM attempt_row.attempt_number
       OR attempt_row.status IS DISTINCT FROM 'CLAIMED' THEN
        RAISE EXCEPTION 'artifact upload settlement lost its active fence'
            USING ERRCODE = 'P7107';
    END IF;

    IF p_outcome = 'STORED' THEN
        IF p_observed_digest IS DISTINCT FROM artifact_row.expected_content_digest
           OR p_observed_byte_count IS DISTINCT FROM artifact_row.expected_byte_count THEN
            RAISE EXCEPTION 'stored artifact does not match its immutable expectation'
                USING ERRCODE = 'P7108';
        END IF;
        -- Lease validity uses wall time above. The durable lifecycle clock must
        -- remain nondecreasing and match the attempt/event settlement time.
        transition_at := GREATEST(db_now, artifact_row.lifecycle_updated_at);
        UPDATE public.evidence_artifact_upload_attempts AS attempt
           SET status = 'STORED',
               settled_at = transition_at,
               failure_code = NULL
         WHERE attempt.organization_id = p_organization_id
           AND attempt.upload_attempt_id = p_upload_attempt_id;
        UPDATE public.evidence_artifacts AS artifact
           SET lifecycle_state = 'STORED',
               lifecycle_version = artifact.lifecycle_version + 1,
               storage_generation = artifact.storage_generation + 1,
               storage_version_reference = p_storage_version_reference,
               encryption_metadata_reference = p_encryption_metadata_reference,
               last_failure_code = NULL,
               lifecycle_updated_at = transition_at
         WHERE artifact.organization_id = p_organization_id
           AND artifact.artifact_id = p_artifact_id
        RETURNING * INTO artifact_row;
        RETURN QUERY
        SELECT true,
               artifact_row.lifecycle_state,
               artifact_row.lifecycle_version,
               artifact_row.storage_generation,
               artifact_row.lifecycle_updated_at;
        RETURN;
    END IF;

    UPDATE public.evidence_artifact_upload_attempts AS attempt
       SET status = p_outcome,
           settled_at = db_now,
           failure_code = p_failure_code
     WHERE attempt.organization_id = p_organization_id
       AND attempt.upload_attempt_id = p_upload_attempt_id;
    UPDATE public.evidence_artifacts AS artifact
       SET last_failure_code = p_failure_code
      WHERE artifact.organization_id = p_organization_id
        AND artifact.artifact_id = p_artifact_id
    RETURNING * INTO artifact_row;
    RETURN QUERY
    SELECT false,
           artifact_row.lifecycle_state,
           artifact_row.lifecycle_version,
           artifact_row.storage_generation,
           artifact_row.lifecycle_updated_at;
END
$$;

CREATE OR REPLACE FUNCTION opsmind_validate_evidence_artifact_event_append() RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
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

    SELECT artifact.organization_id, artifact.project_id, artifact.incident_id,
           artifact.run_id, artifact.actor_id, artifact.lifecycle_state,
           artifact.lifecycle_version, artifact.storage_generation,
           artifact.upload_attempt_id AS authoritative_attempt_id,
           artifact.created_at, artifact.lifecycle_updated_at,
           attempt.status AS attempt_status, attempt.settled_at AS attempt_settled_at
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

    IF NEW.lifecycle_version = 1
       AND NEW.event_id = public.opsmind_evidence_artifact_initial_event_id(
            NEW.organization_id, NEW.artifact_id
       )
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
            NEW.organization_id, NEW.artifact_id, NEW.lifecycle_version,
            NEW.upload_attempt_id
       )
       AND NEW.lifecycle_from_state = 'PENDING_UPLOAD'
       AND NEW.lifecycle_to_state = 'STORED'
       AND NEW.upload_attempt_id IS NOT NULL
       AND NEW.upload_attempt_id
            IS NOT DISTINCT FROM artifact_row.authoritative_attempt_id
       AND artifact_row.lifecycle_state = 'STORED'
       AND artifact_row.lifecycle_version = NEW.lifecycle_version
       AND artifact_row.storage_generation = 1
       AND artifact_row.attempt_status = 'STORED'
       AND artifact_row.attempt_settled_at
            IS NOT DISTINCT FROM artifact_row.lifecycle_updated_at
       AND NEW.occurred_at
            IS NOT DISTINCT FROM artifact_row.lifecycle_updated_at THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'artifact lifecycle event is not an admitted transition'
        USING ERRCODE = 'P7103';
END
$$;

CREATE OR REPLACE FUNCTION opsmind_require_evidence_artifact_stored_event() RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF OLD.lifecycle_state = 'PENDING_UPLOAD' AND NEW.lifecycle_state = 'STORED'
       AND NOT EXISTS (
            SELECT 1
              FROM public.evidence_artifact_events event_row
              JOIN public.audit_events audit_row
                ON audit_row.event_id = event_row.audit_event_id
               AND audit_row.organization_id = event_row.organization_id
             WHERE event_row.organization_id = NEW.organization_id
               AND event_row.artifact_id = NEW.artifact_id
               AND event_row.event_id =
                    public.opsmind_evidence_artifact_lifecycle_event_id(
                        NEW.organization_id,
                        NEW.artifact_id,
                        NEW.lifecycle_version,
                        NEW.upload_attempt_id
                    )
               AND event_row.audit_event_id = event_row.event_id
               AND event_row.lifecycle_version = NEW.lifecycle_version
               AND event_row.lifecycle_from_state = 'PENDING_UPLOAD'
               AND event_row.lifecycle_to_state = 'STORED'
               AND event_row.upload_attempt_id = NEW.upload_attempt_id
               AND event_row.occurred_at = NEW.lifecycle_updated_at
               AND audit_row.action = 'ARTIFACT_STORED'
               AND audit_row.schema_version = 'evidence-artifact-audit-v1'
       ) THEN
        RAISE EXCEPTION 'stored artifact metadata requires its lifecycle event and audit row'
            USING ERRCODE = 'P7104';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER evidence_artifacts_require_stored_event
    AFTER UPDATE ON evidence_artifacts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION opsmind_require_evidence_artifact_stored_event();

ALTER TABLE audit_events
    DROP CONSTRAINT audit_events_evidence_artifact_contract,
    ADD CONSTRAINT audit_events_evidence_artifact_contract CHECK (
        schema_version <> 'evidence-artifact-audit-v1'
        OR (
            actor_id IS NOT NULL
            AND action IN ('ARTIFACT_PENDING_UPLOAD', 'ARTIFACT_STORED')
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
            )
        )
    );

CREATE OR REPLACE FUNCTION opsmind_evidence_artifact_audit_matches(
    p_audit public.audit_events
) RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
STABLE
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    artifact_row record;
BEGIN
    SELECT event_row.event_id, event_row.organization_id, event_row.project_id,
           event_row.incident_id, event_row.run_id, event_row.artifact_id,
           event_row.actor_id, event_row.lifecycle_version,
           event_row.lifecycle_from_state, event_row.lifecycle_to_state,
           event_row.occurred_at, event_row.audit_event_id,
           event_row.upload_attempt_id,
           artifact.expected_content_digest, artifact.expected_byte_count,
           artifact.data_classification, artifact.retention_class,
           artifact.lifecycle_state AS authoritative_state,
           artifact.lifecycle_version AS authoritative_version,
           artifact.storage_generation,
           artifact.upload_attempt_id AS authoritative_attempt_id,
           attempt.status AS attempt_status
      INTO artifact_row
      FROM public.evidence_artifact_events event_row
      JOIN public.evidence_artifacts artifact
        ON artifact.organization_id = event_row.organization_id
       AND artifact.artifact_id = event_row.artifact_id
      LEFT JOIN public.evidence_artifact_upload_attempts attempt
        ON attempt.organization_id = event_row.organization_id
       AND attempt.artifact_id = event_row.artifact_id
       AND attempt.upload_attempt_id = event_row.upload_attempt_id
     WHERE event_row.event_id = p_audit.event_id
       AND event_row.organization_id = p_audit.organization_id;
    IF NOT FOUND
       OR p_audit.actor_id IS DISTINCT FROM artifact_row.actor_id
       OR p_audit.resource_type IS DISTINCT FROM 'evidence_artifact'
       OR p_audit.resource_id IS DISTINCT FROM artifact_row.artifact_id::text
       OR p_audit.correlation_id IS DISTINCT FROM artifact_row.artifact_id
       OR p_audit.occurred_at IS DISTINCT FROM artifact_row.occurred_at
       OR artifact_row.audit_event_id IS DISTINCT FROM p_audit.event_id
       OR jsonb_typeof(p_audit.payload -> 'eventId') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'organizationId') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'projectId') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'incidentId') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'runId') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'artifactId') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'actorId') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'lifecycleVersion') IS DISTINCT FROM 'number'
       OR jsonb_typeof(p_audit.payload -> 'lifecycleState') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'contentDigest') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'byteCount') IS DISTINCT FROM 'number'
       OR jsonb_typeof(p_audit.payload -> 'dataClassification') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'retentionClass') IS DISTINCT FROM 'string'
       OR jsonb_typeof(p_audit.payload -> 'occurredAt') IS DISTINCT FROM 'string'
       OR p_audit.payload ->> 'eventId' IS DISTINCT FROM artifact_row.event_id::text
       OR p_audit.payload ->> 'organizationId'
            IS DISTINCT FROM artifact_row.organization_id::text
       OR p_audit.payload ->> 'projectId'
            IS DISTINCT FROM artifact_row.project_id::text
       OR p_audit.payload ->> 'incidentId'
            IS DISTINCT FROM artifact_row.incident_id::text
       OR p_audit.payload ->> 'runId' IS DISTINCT FROM artifact_row.run_id::text
       OR p_audit.payload ->> 'artifactId'
            IS DISTINCT FROM artifact_row.artifact_id::text
       OR p_audit.payload ->> 'actorId' IS DISTINCT FROM artifact_row.actor_id::text
       OR p_audit.payload ->> 'lifecycleVersion'
            IS DISTINCT FROM artifact_row.lifecycle_version::text
       OR p_audit.payload ->> 'lifecycleState'
            IS DISTINCT FROM artifact_row.lifecycle_to_state
       OR p_audit.payload ->> 'contentDigest'
            IS DISTINCT FROM 'sha256:'
                || encode(artifact_row.expected_content_digest, 'hex')
       OR p_audit.payload ->> 'byteCount'
            IS DISTINCT FROM artifact_row.expected_byte_count::text
       OR p_audit.payload ->> 'dataClassification'
            IS DISTINCT FROM artifact_row.data_classification
       OR p_audit.payload ->> 'retentionClass'
            IS DISTINCT FROM artifact_row.retention_class
       OR (p_audit.payload ->> 'occurredAt')::timestamptz
            IS DISTINCT FROM artifact_row.occurred_at THEN
        RETURN false;
    END IF;

    IF artifact_row.lifecycle_version = 1 THEN
        RETURN p_audit.action = 'ARTIFACT_PENDING_UPLOAD'
            AND artifact_row.lifecycle_from_state IS NULL
            AND artifact_row.lifecycle_to_state = 'PENDING_UPLOAD'
            AND artifact_row.upload_attempt_id IS NULL
            AND public.opsmind_json_object_has_exact_keys(p_audit.payload, ARRAY[
                'eventId', 'organizationId', 'projectId', 'incidentId', 'runId',
                'artifactId', 'actorId', 'lifecycleVersion', 'lifecycleState',
                'contentDigest', 'byteCount', 'dataClassification', 'retentionClass',
                'occurredAt'
            ]);
    END IF;

    IF artifact_row.lifecycle_version = 2 THEN
        RETURN p_audit.action = 'ARTIFACT_STORED'
            AND artifact_row.lifecycle_from_state = 'PENDING_UPLOAD'
            AND artifact_row.lifecycle_to_state = 'STORED'
            AND artifact_row.upload_attempt_id IS NOT NULL
            AND artifact_row.upload_attempt_id
                IS NOT DISTINCT FROM artifact_row.authoritative_attempt_id
            AND artifact_row.event_id =
                public.opsmind_evidence_artifact_lifecycle_event_id(
                    artifact_row.organization_id,
                    artifact_row.artifact_id,
                    artifact_row.lifecycle_version,
                    artifact_row.upload_attempt_id
                )
            AND artifact_row.authoritative_state = 'STORED'
            AND artifact_row.authoritative_version = 2
            AND artifact_row.storage_generation = 1
            AND artifact_row.attempt_status = 'STORED'
            AND jsonb_typeof(p_audit.payload -> 'storageGeneration') = 'number'
            AND p_audit.payload ->> 'storageGeneration'
                = artifact_row.storage_generation::text
            AND public.opsmind_json_object_has_exact_keys(p_audit.payload, ARRAY[
                'eventId', 'organizationId', 'projectId', 'incidentId', 'runId',
                'artifactId', 'actorId', 'lifecycleVersion', 'lifecycleState',
                'contentDigest', 'byteCount', 'dataClassification', 'retentionClass',
                'storageGeneration', 'occurredAt'
            ]);
    END IF;
    RETURN false;
END
$$;

-- Preserve V003/V006 audit validation and digest chaining. Artifact rows use
-- the exact validator above so both PENDING_UPLOAD and STORED stay authoritative.
CREATE OR REPLACE FUNCTION opsmind_assign_audit_chain() RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    prior_sequence bigint;
    prior_digest bytea;
    timeline_row record;
    investigation_row record;
    actor_id uuid := public.opsmind_current_actor_id();
BEGIN
    IF session_user = 'opsmind_app' THEN
        IF NEW.organization_id IS DISTINCT FROM public.opsmind_current_tenant_id()
           OR NEW.actor_id IS DISTINCT FROM actor_id
           OR NEW.schema_version IS NULL
           OR NEW.schema_version NOT IN (
                'incident-audit-v1', 'investigation-audit-v1',
                'evidence-artifact-audit-v1'
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
        IF NOT public.opsmind_evidence_artifact_audit_matches(NEW) THEN
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

ALTER TABLE evidence_artifact_upload_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence_artifact_upload_attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY evidence_artifact_upload_attempts_tenant_isolation
    ON evidence_artifact_upload_attempts
    USING (organization_id = public.opsmind_current_tenant_id())
    WITH CHECK (organization_id = public.opsmind_current_tenant_id());

REVOKE ALL ON evidence_artifact_upload_attempts
    FROM opsmind_app, opsmind_dispatcher, PUBLIC;
REVOKE UPDATE, DELETE, TRUNCATE ON evidence_artifacts, evidence_artifact_events
    FROM opsmind_app;
REVOKE ALL ON FUNCTION public.opsmind_evidence_artifact_lifecycle_event_id(
    uuid, uuid, bigint, uuid
) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_validate_evidence_artifact_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_claim_evidence_artifact_upload(
    uuid, uuid, uuid, uuid, uuid, uuid, bigint, bigint
) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_settle_evidence_artifact_upload(
    uuid, uuid, uuid, uuid, uuid, uuid, bigint, varchar, bytea, bigint,
    varchar, varchar, varchar
) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_validate_evidence_artifact_event_append()
    FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_require_evidence_artifact_stored_event()
    FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_evidence_artifact_audit_matches(
    public.audit_events
) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_assign_audit_chain() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.opsmind_evidence_artifact_lifecycle_event_id(
    uuid, uuid, bigint, uuid
) TO opsmind_app;
GRANT EXECUTE ON FUNCTION public.opsmind_claim_evidence_artifact_upload(
    uuid, uuid, uuid, uuid, uuid, uuid, bigint, bigint
) TO opsmind_app;
GRANT EXECUTE ON FUNCTION public.opsmind_settle_evidence_artifact_upload(
    uuid, uuid, uuid, uuid, uuid, uuid, bigint, varchar, bytea, bigint,
    varchar, varchar, varchar
) TO opsmind_app;

COMMENT ON TABLE evidence_artifact_upload_attempts IS
    'Tenant-fenced upload attempts. Runtime callers receive only claim and settlement capabilities.';
COMMENT ON COLUMN evidence_artifacts.storage_version_reference IS
    'Opaque immutable object generation reference; never projected as a credential or URL.';
COMMENT ON FUNCTION public.opsmind_claim_evidence_artifact_upload(
    uuid, uuid, uuid, uuid, uuid, uuid, bigint, bigint
) IS 'Claims one bounded upload lease; explicit ambiguity forces a probe and an expired unsettled claim requires reconciliation.';
COMMENT ON FUNCTION public.opsmind_settle_evidence_artifact_upload(
    uuid, uuid, uuid, uuid, uuid, uuid, bigint, varchar, bytea, bigint,
    varchar, varchar, varchar
) IS 'Settles the current fenced attempt; STORED commits only with its deferred event and audit.';
