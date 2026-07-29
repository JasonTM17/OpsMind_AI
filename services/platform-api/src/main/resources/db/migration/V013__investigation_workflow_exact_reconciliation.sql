-- Read-only exact-workflow reconciliation for a previously claimed Temporal
-- start. The runtime login receives only the three fixed capabilities below;
-- it cannot establish tenant context or directly access the protected tables.

DO $$
DECLARE
    reconciler_unsafe boolean;
    resolver_unsafe boolean;
BEGIN
    SELECT role_row.rolsuper
        OR role_row.rolcreatedb
        OR role_row.rolcreaterole
        OR role_row.rolreplication
        OR role_row.rolbypassrls
        OR NOT role_row.rolcanlogin
        OR role_row.rolinherit
        OR EXISTS (
            SELECT 1
              FROM pg_catalog.pg_auth_members membership
             WHERE membership.member = role_row.oid
                OR membership.roleid = role_row.oid
        )
      INTO reconciler_unsafe
      FROM pg_catalog.pg_roles role_row
     WHERE role_row.rolname = 'opsmind_workflow_reconciler';
    IF reconciler_unsafe IS NULL OR reconciler_unsafe THEN
        RAISE EXCEPTION
            'opsmind_workflow_reconciler has unsafe attributes or role memberships';
    END IF;

    SELECT role_row.rolsuper
        OR role_row.rolcreatedb
        OR role_row.rolcreaterole
        OR role_row.rolreplication
        OR role_row.rolbypassrls
        OR role_row.rolcanlogin
        OR role_row.rolinherit
        OR EXISTS (
            SELECT 1
              FROM pg_catalog.pg_auth_members membership
             WHERE membership.member = role_row.oid
                OR membership.roleid = role_row.oid
        )
      INTO resolver_unsafe
      FROM pg_catalog.pg_roles role_row
     WHERE role_row.rolname = 'opsmind_workflow_reconciliation_resolver';
    IF resolver_unsafe IS NULL OR resolver_unsafe THEN
        RAISE EXCEPTION
            'opsmind_workflow_reconciliation_resolver has unsafe attributes or role memberships';
    END IF;
END
$$;

DO $$
BEGIN
    EXECUTE format(
        'GRANT CONNECT ON DATABASE %I TO opsmind_workflow_reconciler',
        current_database()
    );
END
$$;

GRANT USAGE ON SCHEMA public TO opsmind_workflow_reconciler;
GRANT USAGE ON SCHEMA public TO opsmind_workflow_reconciliation_resolver;

GRANT SELECT (id, status)
    ON organizations TO opsmind_workflow_reconciliation_resolver;
GRANT UPDATE (status)
    ON organizations TO opsmind_workflow_reconciliation_resolver;
GRANT SELECT (
    id, organization_id, allowed_audiences, allowed_scopes, status,
    database_principal
) ON service_accounts TO opsmind_workflow_reconciliation_resolver;
GRANT UPDATE (status)
    ON service_accounts TO opsmind_workflow_reconciliation_resolver;
GRANT SELECT (
    event_id, organization_id, aggregate_type, aggregate_id, aggregate_sequence,
    event_type, schema_version, causation_id, correlation_id, occurred_at,
    payload_bytes, payload_digest, published_at, attempts, last_error,
    next_attempt_at, lease_token, lease_expires_at, poisoned_at
) ON outbox_events TO opsmind_workflow_reconciliation_resolver;
GRANT UPDATE (
    published_at, last_error, next_attempt_at, lease_token, lease_expires_at,
    poisoned_at
) ON outbox_events TO opsmind_workflow_reconciliation_resolver;
GRANT SELECT (
    organization_id, run_id, start_payload_digest, start_event_id,
    temporal_cluster_id, temporal_namespace, workflow_id, workflow_type,
    task_queue, status, created_at
) ON investigation_workflow_bindings
    TO opsmind_workflow_reconciliation_resolver;
GRANT UPDATE (
    status, temporal_run_id, rejection_code, updated_at,
    temporal_started_at, rejected_at
) ON investigation_workflow_bindings
    TO opsmind_workflow_reconciliation_resolver;
GRANT SELECT (
    event_id, organization_id, consumer, received_at, processed_at,
    status, attempts, last_error
) ON inbox_events TO opsmind_workflow_reconciliation_resolver;
GRANT INSERT (
    event_id, organization_id, consumer, received_at, processed_at,
    status, attempts, last_error
) ON inbox_events TO opsmind_workflow_reconciliation_resolver;
GRANT UPDATE (
    received_at, processed_at, status, attempts, last_error
) ON inbox_events TO opsmind_workflow_reconciliation_resolver;

CREATE POLICY organizations_workflow_reconciliation
    ON organizations
    FOR SELECT TO opsmind_workflow_reconciliation_resolver
    USING (true);
CREATE POLICY organizations_workflow_reconciliation_lock
    ON organizations
    FOR UPDATE TO opsmind_workflow_reconciliation_resolver
    USING (true)
    WITH CHECK (true);
CREATE POLICY service_accounts_workflow_reconciliation
    ON service_accounts
    FOR SELECT TO opsmind_workflow_reconciliation_resolver
    USING (true);
CREATE POLICY service_accounts_workflow_reconciliation_lock
    ON service_accounts
    FOR UPDATE TO opsmind_workflow_reconciliation_resolver
    USING (true)
    WITH CHECK (true);
CREATE POLICY outbox_events_workflow_reconciliation_select
    ON outbox_events
    FOR SELECT TO opsmind_workflow_reconciliation_resolver
    USING (true);
CREATE POLICY outbox_events_workflow_reconciliation_update
    ON outbox_events
    FOR UPDATE TO opsmind_workflow_reconciliation_resolver
    USING (true)
    WITH CHECK (true);
CREATE POLICY investigation_workflow_bindings_reconciliation_select
    ON investigation_workflow_bindings
    FOR SELECT TO opsmind_workflow_reconciliation_resolver
    USING (true);
CREATE POLICY investigation_workflow_bindings_reconciliation_update
    ON investigation_workflow_bindings
    FOR UPDATE TO opsmind_workflow_reconciliation_resolver
    USING (true)
    WITH CHECK (true);
CREATE POLICY inbox_events_workflow_reconciliation_select
    ON inbox_events
    FOR SELECT TO opsmind_workflow_reconciliation_resolver
    USING (true);
CREATE POLICY inbox_events_workflow_reconciliation_insert
    ON inbox_events
    FOR INSERT TO opsmind_workflow_reconciliation_resolver
    WITH CHECK (true);
CREATE POLICY inbox_events_workflow_reconciliation_update
    ON inbox_events
    FOR UPDATE TO opsmind_workflow_reconciliation_resolver
    USING (true)
    WITH CHECK (true);

-- V010 admitted only the starter session in its UPDATE trigger. Preserve the
-- original immutable-field and state-machine checks while admitting the
-- separate exact-reconciliation session through its fixed definer function.
CREATE OR REPLACE FUNCTION opsmind_validate_investigation_workflow_binding_update()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF TG_OP IS DISTINCT FROM 'UPDATE'
       OR session_user NOT IN (
           'opsmind_dispatcher',
           'opsmind_workflow_reconciler'
       ) THEN
        RAISE EXCEPTION 'workflow binding reconciliation requires a dedicated identity'
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

DROP TRIGGER investigation_workflow_bindings_validate_write
    ON investigation_workflow_bindings;
CREATE TRIGGER investigation_workflow_bindings_validate_write
    BEFORE INSERT ON investigation_workflow_bindings
    FOR EACH ROW EXECUTE FUNCTION opsmind_validate_investigation_workflow_binding();
CREATE TRIGGER investigation_workflow_bindings_validate_reconciliation
    BEFORE UPDATE ON investigation_workflow_bindings
    FOR EACH ROW
    EXECUTE FUNCTION opsmind_validate_investigation_workflow_binding_update();

CREATE INDEX outbox_investigation_workflow_reconciliation_ready_idx
    ON outbox_events (next_attempt_at, occurred_at, event_id)
    WHERE event_type = 'investigation.workflow-start.requested'
      AND schema_version = '1'
      AND aggregate_type = 'investigation-workflow'
      AND aggregate_sequence = 1
      AND attempts > 0
      AND published_at IS NULL
      AND poisoned_at IS NULL;
CREATE INDEX inbox_investigation_workflow_reconciliation_idx
    ON inbox_events (status, received_at, event_id)
    WHERE consumer = 'investigation-workflow-reconciler-v1';

CREATE OR REPLACE FUNCTION opsmind_claim_investigation_workflow_reconciliation(
    p_lease_token uuid,
    p_lease_duration_ms bigint,
    p_maximum_attempts integer,
    p_maximum_age_ms bigint
) RETURNS TABLE(
    event_id uuid,
    organization_id uuid,
    aggregate_type varchar,
    aggregate_id uuid,
    aggregate_sequence bigint,
    event_type varchar,
    schema_version varchar,
    causation_id uuid,
    correlation_id uuid,
    occurred_at timestamptz,
    payload_bytes bytea,
    payload_digest bytea,
    lease_token uuid,
    lease_expires_at timestamptz,
    outbox_attempts integer,
    temporal_cluster_id varchar,
    temporal_namespace varchar,
    workflow_id varchar,
    workflow_type varchar,
    task_queue varchar,
    start_payload_digest bytea,
    reconciliation_attempt integer,
    reconciliation_received_at timestamptz,
    reconciliation_last_code varchar,
    reconciliation_last_observed_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    db_now timestamptz := clock_timestamp();
    candidate record;
    reconciliation_row record;
    affected_rows integer;
BEGIN
    IF session_user <> 'opsmind_workflow_reconciler' THEN
        RAISE EXCEPTION 'dedicated workflow reconciler identity is required'
            USING ERRCODE = '42501';
    END IF;
    IF p_lease_token IS NULL THEN
        RAISE EXCEPTION 'workflow reconciliation lease identity is required'
            USING ERRCODE = '22023';
    END IF;
    IF p_lease_duration_ms IS NULL
       OR p_lease_duration_ms < 5000
       OR p_lease_duration_ms > 300000 THEN
        RAISE EXCEPTION 'workflow reconciliation lease duration is outside policy'
            USING ERRCODE = '22023';
    END IF;
    IF p_maximum_attempts IS NULL
       OR p_maximum_attempts < 1
       OR p_maximum_attempts > 8 THEN
        RAISE EXCEPTION 'workflow reconciliation attempt limit is outside policy'
            USING ERRCODE = '22023';
    END IF;
    IF p_maximum_age_ms IS NULL
       OR p_maximum_age_ms < 1000
       OR p_maximum_age_ms > 604800000 THEN
        RAISE EXCEPTION 'workflow reconciliation age limit is outside policy'
            USING ERRCODE = '22023';
    END IF;

    -- A process can die after consuming its final lease but before recording
    -- BLOCKED. Convert expired, over-budget epochs to an alertable inbox state
    -- before selecting new work; never terminalize the binding or outbox.
    WITH exhausted AS (
        SELECT event_row.organization_id, event_row.event_id
          FROM public.outbox_events event_row
          JOIN public.investigation_workflow_bindings binding_row
            ON binding_row.organization_id = event_row.organization_id
           AND binding_row.run_id = event_row.aggregate_id
           AND binding_row.start_event_id = event_row.event_id
           AND binding_row.status = 'PENDING'
          JOIN public.inbox_events reconciliation_inbox
            ON reconciliation_inbox.organization_id = event_row.organization_id
           AND reconciliation_inbox.event_id = event_row.event_id
           AND reconciliation_inbox.consumer
                = 'investigation-workflow-reconciler-v1'
           AND reconciliation_inbox.status = 'received'
         WHERE event_row.event_type = 'investigation.workflow-start.requested'
           AND event_row.schema_version = '1'
           AND event_row.aggregate_type = 'investigation-workflow'
           AND event_row.aggregate_sequence = 1
           AND event_row.attempts > 0
           AND event_row.published_at IS NULL
           AND event_row.poisoned_at IS NULL
           AND (
               event_row.lease_expires_at IS NULL
               OR event_row.lease_expires_at <= db_now
           )
           AND (
               reconciliation_inbox.attempts >= p_maximum_attempts
               OR reconciliation_inbox.received_at
                    <= db_now - (p_maximum_age_ms * interval '1 millisecond')
           )
         ORDER BY event_row.occurred_at, event_row.event_id
         FOR UPDATE OF event_row, binding_row, reconciliation_inbox SKIP LOCKED
         LIMIT 100
    ), poisoned AS (
        UPDATE public.inbox_events reconciliation_inbox
           SET processed_at = db_now,
               status = 'poisoned',
               last_error = 'workflow.reconciliation-exhausted'
          FROM exhausted
         WHERE reconciliation_inbox.organization_id = exhausted.organization_id
           AND reconciliation_inbox.event_id = exhausted.event_id
           AND reconciliation_inbox.consumer
                = 'investigation-workflow-reconciler-v1'
           AND reconciliation_inbox.status = 'received'
        RETURNING reconciliation_inbox.organization_id,
                  reconciliation_inbox.event_id
    )
    UPDATE public.outbox_events event_row
       SET lease_token = NULL,
           lease_expires_at = NULL,
           last_error = 'workflow.reconciliation-required',
           next_attempt_at = db_now
      FROM poisoned
     WHERE event_row.organization_id = poisoned.organization_id
       AND event_row.event_id = poisoned.event_id
       AND event_row.published_at IS NULL
       AND event_row.poisoned_at IS NULL;

    SELECT event_row.event_id, event_row.organization_id,
           event_row.aggregate_type, event_row.aggregate_id,
           event_row.aggregate_sequence, event_row.event_type,
           event_row.schema_version, event_row.causation_id,
           event_row.correlation_id, event_row.occurred_at,
           event_row.payload_bytes, event_row.payload_digest,
           event_row.attempts AS outbox_attempts,
           binding_row.temporal_cluster_id, binding_row.temporal_namespace,
           binding_row.workflow_id, binding_row.workflow_type,
           binding_row.task_queue, binding_row.start_payload_digest
      INTO candidate
      FROM public.outbox_events event_row
      JOIN public.investigation_workflow_bindings binding_row
        ON binding_row.organization_id = event_row.organization_id
       AND binding_row.run_id = event_row.aggregate_id
       AND binding_row.start_event_id = event_row.event_id
       AND binding_row.status = 'PENDING'
      LEFT JOIN public.inbox_events reconciliation_inbox
        ON reconciliation_inbox.organization_id = event_row.organization_id
       AND reconciliation_inbox.event_id = event_row.event_id
       AND reconciliation_inbox.consumer = 'investigation-workflow-reconciler-v1'
     WHERE event_row.event_type = 'investigation.workflow-start.requested'
       AND event_row.schema_version = '1'
       AND event_row.aggregate_type = 'investigation-workflow'
       AND event_row.aggregate_sequence = 1
       AND event_row.attempts > 0
       AND event_row.published_at IS NULL
       AND event_row.poisoned_at IS NULL
       AND event_row.next_attempt_at <= db_now
       AND (
           event_row.lease_expires_at IS NULL
           OR event_row.lease_expires_at <= db_now
       )
       AND (
           reconciliation_inbox.event_id IS NULL
           OR (
               reconciliation_inbox.status = 'received'
               AND reconciliation_inbox.attempts < p_maximum_attempts
               AND reconciliation_inbox.received_at
                    > db_now - (p_maximum_age_ms * interval '1 millisecond')
           )
           OR reconciliation_inbox.status = 'processed'
       )
       AND (
           reconciliation_inbox.status = 'received'
           OR
           event_row.last_error = 'workflow.reconciliation-required'
           OR NOT EXISTS (
               SELECT 1
                 FROM public.organizations organization_row
                 JOIN public.service_accounts account_row
                   ON account_row.organization_id = organization_row.id
                  AND account_row.status = 'active'
                  AND account_row.database_principal = 'opsmind_dispatcher'
                  AND account_row.allowed_audiences
                        @> '["opsmind-outbox-dispatcher"]'::jsonb
                  AND account_row.allowed_scopes @> '["outbox:dispatch"]'::jsonb
                WHERE organization_row.id = event_row.organization_id
                  AND organization_row.status = 'active'
           )
       )
     ORDER BY event_row.occurred_at, event_row.event_id
     FOR UPDATE OF event_row, binding_row SKIP LOCKED
     LIMIT 1;
    IF NOT FOUND THEN
        RETURN;
    END IF;

    INSERT INTO public.inbox_events (
        event_id, organization_id, consumer, received_at, attempts
    ) VALUES (
        candidate.event_id,
        candidate.organization_id,
        'investigation-workflow-reconciler-v1',
        db_now,
        1
    )
    ON CONFLICT ON CONSTRAINT inbox_events_pkey DO UPDATE
       SET received_at = CASE
               WHEN public.inbox_events.status = 'processed'
                   THEN db_now
               ELSE public.inbox_events.received_at
           END,
           processed_at = CASE
               WHEN public.inbox_events.status = 'processed'
                   THEN NULL
               ELSE public.inbox_events.processed_at
           END,
           status = 'received',
           attempts = CASE
               WHEN public.inbox_events.status = 'processed'
                   THEN 1
               ELSE public.inbox_events.attempts + 1
           END,
           last_error = CASE
               WHEN public.inbox_events.status = 'processed'
                   THEN NULL
               ELSE public.inbox_events.last_error
           END
     WHERE public.inbox_events.status = 'processed'
        OR (
            public.inbox_events.status = 'received'
            AND public.inbox_events.attempts < p_maximum_attempts
            AND public.inbox_events.received_at
                > db_now - (p_maximum_age_ms * interval '1 millisecond')
        )
    RETURNING attempts, received_at, last_error, processed_at
         INTO reconciliation_row;
    IF NOT FOUND THEN
        RETURN;
    END IF;

    UPDATE public.outbox_events event_row
       SET lease_token = p_lease_token,
           lease_expires_at = db_now
               + (p_lease_duration_ms * interval '1 millisecond'),
           last_error = 'workflow.reconciliation-required'
     WHERE event_row.organization_id = candidate.organization_id
       AND event_row.event_id = candidate.event_id
       AND event_row.published_at IS NULL
       AND event_row.poisoned_at IS NULL
       AND (
           event_row.lease_expires_at IS NULL
           OR event_row.lease_expires_at <= db_now
       );
    GET DIAGNOSTICS affected_rows = ROW_COUNT;
    IF affected_rows <> 1 THEN
        RAISE EXCEPTION 'workflow reconciliation claim lost its candidate'
            USING ERRCODE = 'P7013';
    END IF;

    RETURN QUERY SELECT
        candidate.event_id,
        candidate.organization_id,
        candidate.aggregate_type,
        candidate.aggregate_id,
        candidate.aggregate_sequence,
        candidate.event_type,
        candidate.schema_version,
        candidate.causation_id,
        candidate.correlation_id,
        candidate.occurred_at,
        candidate.payload_bytes,
        candidate.payload_digest,
        p_lease_token,
        db_now + (p_lease_duration_ms * interval '1 millisecond'),
        candidate.outbox_attempts,
        candidate.temporal_cluster_id,
        candidate.temporal_namespace,
        candidate.workflow_id,
        candidate.workflow_type,
        candidate.task_queue,
        candidate.start_payload_digest,
        reconciliation_row.attempts,
        reconciliation_row.received_at,
        reconciliation_row.last_error,
        reconciliation_row.processed_at;
END
$$;
ALTER FUNCTION public.opsmind_claim_investigation_workflow_reconciliation(
    uuid, bigint, integer, bigint
) OWNER TO opsmind_workflow_reconciliation_resolver;

CREATE OR REPLACE FUNCTION opsmind_settle_investigation_workflow_reconciliation(
    p_organization_id uuid,
    p_event_id uuid,
    p_lease_token uuid,
    p_outcome varchar,
    p_temporal_first_run_id varchar,
    p_error_code varchar,
    p_retry_delay_ms bigint,
    p_absence_confirmation_delay_ms bigint,
    p_maximum_verifiable_age_ms bigint
) RETURNS varchar
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    db_now timestamptz := clock_timestamp();
    locked record;
    reconciliation_row record;
    organization_status varchar;
    eligible_dispatcher_id uuid;
    starter_event_id uuid;
    affected_rows integer;
BEGIN
    IF session_user <> 'opsmind_workflow_reconciler' THEN
        RAISE EXCEPTION 'dedicated workflow reconciler identity is required'
            USING ERRCODE = '42501';
    END IF;
    IF p_organization_id IS NULL OR p_event_id IS NULL OR p_lease_token IS NULL THEN
        RAISE EXCEPTION
            'workflow reconciliation settlement requires organization, event, and lease'
            USING ERRCODE = '22023';
    END IF;
    IF p_outcome IS NULL
       OR p_outcome NOT IN ('MATCH', 'ABSENT', 'MISMATCH', 'RETRY', 'BLOCKED') THEN
        RAISE EXCEPTION 'workflow reconciliation outcome is outside policy'
            USING ERRCODE = '22023';
    END IF;
    IF p_absence_confirmation_delay_ms IS NULL
       OR p_absence_confirmation_delay_ms < 1000
       OR p_absence_confirmation_delay_ms > 900000
       OR p_maximum_verifiable_age_ms IS NULL
       OR p_maximum_verifiable_age_ms < 10000
       OR p_maximum_verifiable_age_ms > 31536000000 THEN
        RAISE EXCEPTION 'workflow reconciliation evidence bounds are outside policy'
            USING ERRCODE = '22023';
    END IF;
    IF p_outcome = 'MATCH' AND (
        p_temporal_first_run_id IS NULL
        OR length(trim(p_temporal_first_run_id)) NOT BETWEEN 1 AND 255
        OR p_error_code IS NOT NULL
        OR p_retry_delay_ms IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'workflow reconciliation match inputs are invalid'
            USING ERRCODE = '22023';
    END IF;
    IF p_outcome = 'ABSENT' AND (
        p_temporal_first_run_id IS NOT NULL
        OR p_error_code IS DISTINCT FROM 'workflow.temporal-start-not-found'
        OR p_retry_delay_ms IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'workflow reconciliation absence inputs are invalid'
            USING ERRCODE = '22023';
    END IF;
    IF p_outcome = 'MISMATCH' AND (
        p_temporal_first_run_id IS NOT NULL
        OR p_error_code IS DISTINCT FROM 'workflow.existing-contract-mismatch'
        OR p_retry_delay_ms IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'workflow reconciliation mismatch inputs are invalid'
            USING ERRCODE = '22023';
    END IF;
    IF p_outcome = 'RETRY' AND (
        p_temporal_first_run_id IS NOT NULL
        OR p_error_code NOT IN (
            'workflow.temporal-unavailable',
            'workflow.temporal-deadline-exceeded',
            'workflow.temporal-resource-exhausted',
            'workflow.temporal-aborted',
            'workflow.temporal-unknown',
            'workflow.temporal-internal',
            'workflow.temporal-cancelled',
            'workflow.temporal-timeout',
            'workflow.temporal-io-failure'
        )
        OR p_retry_delay_ms IS NULL
        OR p_retry_delay_ms < 100
        OR p_retry_delay_ms > 900000
    ) THEN
        RAISE EXCEPTION 'workflow reconciliation retry inputs are invalid'
            USING ERRCODE = '22023';
    END IF;
    IF p_outcome = 'BLOCKED' AND (
        p_temporal_first_run_id IS NOT NULL
        OR p_error_code NOT IN (
            'workflow.reconciliation-permission-denied',
            'workflow.reconciliation-configuration-mismatch',
            'workflow.reconciliation-description-malformed',
            'workflow.reconciliation-history-malformed',
            'workflow.reconciliation-history-missing',
            'workflow.reconciliation-history-disappeared',
            'workflow.reconciliation-decode-failed',
            'workflow.reconciliation-observer-failed',
            'workflow.reconciliation-retention-unverifiable',
            'workflow.reconciliation-exhausted'
        )
        OR p_retry_delay_ms IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'workflow reconciliation blocked inputs are invalid'
            USING ERRCODE = '22023';
    END IF;

    SELECT event_row.organization_id, event_row.event_id, event_row.aggregate_id,
           event_row.lease_expires_at, event_row.attempts AS outbox_attempts,
           binding_row.run_id, binding_row.created_at
      INTO locked
      FROM public.outbox_events event_row
      JOIN public.investigation_workflow_bindings binding_row
        ON binding_row.organization_id = event_row.organization_id
       AND binding_row.run_id = event_row.aggregate_id
       AND binding_row.start_event_id = event_row.event_id
       AND binding_row.status = 'PENDING'
     WHERE event_row.organization_id = p_organization_id
       AND event_row.event_id = p_event_id
       AND event_row.event_type = 'investigation.workflow-start.requested'
       AND event_row.schema_version = '1'
       AND event_row.aggregate_type = 'investigation-workflow'
       AND event_row.aggregate_sequence = 1
       AND event_row.attempts > 0
       AND event_row.lease_token = p_lease_token
       AND event_row.lease_expires_at > db_now
       AND event_row.published_at IS NULL
       AND event_row.poisoned_at IS NULL
     FOR UPDATE OF event_row, binding_row;
    IF NOT FOUND THEN
        RETURN 'workflow.reconciliation-lease-lost';
    END IF;

    PERFORM 1
      FROM public.inbox_events starter_inbox
     WHERE starter_inbox.organization_id = p_organization_id
       AND starter_inbox.event_id = p_event_id
       AND starter_inbox.consumer = 'investigation-workflow-starter-v1'
     FOR UPDATE;

    SELECT reconciliation_inbox.received_at,
           reconciliation_inbox.processed_at,
           reconciliation_inbox.status,
           reconciliation_inbox.last_error
      INTO reconciliation_row
      FROM public.inbox_events reconciliation_inbox
     WHERE reconciliation_inbox.organization_id = p_organization_id
       AND reconciliation_inbox.event_id = p_event_id
       AND reconciliation_inbox.consumer = 'investigation-workflow-reconciler-v1'
       AND reconciliation_inbox.status = 'received'
     FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'workflow.reconciliation-lease-lost';
    END IF;

    SELECT organization_row.status
      INTO organization_status
      FROM public.organizations organization_row
     WHERE organization_row.id = p_organization_id
     FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'workflow.reconciliation-lease-lost';
    END IF;

    -- Lock every possible dispatcher identity, including inactive rows. This
    -- serializes reactivation with the absence decision instead of locking
    -- only the already-active row.
    PERFORM 1
      FROM public.service_accounts account_row
     WHERE account_row.organization_id = p_organization_id
       AND account_row.database_principal = 'opsmind_dispatcher'
     ORDER BY account_row.id
     FOR UPDATE;

    IF organization_status = 'active' THEN
        SELECT account_row.id
          INTO eligible_dispatcher_id
          FROM public.service_accounts account_row
         WHERE account_row.organization_id = p_organization_id
           AND account_row.status = 'active'
           AND account_row.database_principal = 'opsmind_dispatcher'
           AND account_row.allowed_audiences
                @> '["opsmind-outbox-dispatcher"]'::jsonb
           AND account_row.allowed_scopes @> '["outbox:dispatch"]'::jsonb
         ORDER BY account_row.id
         LIMIT 1;
    END IF;

    -- NOT_FOUND is not evidence once the original history could have aged
    -- out. Retention failure wins over reactivation: a normal start could
    -- otherwise reuse an expired workflow ID and duplicate accepted work.
    IF p_outcome = 'ABSENT'
       AND locked.created_at
            <= db_now - (p_maximum_verifiable_age_ms * interval '1 millisecond') THEN
        p_outcome := 'BLOCKED';
        p_error_code := 'workflow.reconciliation-retention-unverifiable';
    END IF;

    IF p_outcome = 'ABSENT' AND eligible_dispatcher_id IS NOT NULL THEN
        UPDATE public.inbox_events
           SET processed_at = db_now,
               status = 'processed',
               last_error = 'workflow.reconciliation-released-to-starter'
         WHERE organization_id = p_organization_id
           AND event_id = p_event_id
           AND consumer = 'investigation-workflow-reconciler-v1'
           AND status = 'received';
        UPDATE public.outbox_events
           SET lease_token = NULL,
               lease_expires_at = NULL,
               last_error = NULL,
               next_attempt_at = db_now
         WHERE organization_id = p_organization_id
           AND event_id = p_event_id
           AND lease_token = p_lease_token
           AND lease_expires_at > db_now;
        RETURN 'workflow.reconciliation-released-to-starter';
    END IF;

    IF p_outcome = 'ABSENT'
       AND (
           reconciliation_row.last_error
                IS DISTINCT FROM 'workflow.reconciliation-absence-candidate'
           OR reconciliation_row.processed_at IS NULL
           OR reconciliation_row.processed_at
                > db_now
                    - (p_absence_confirmation_delay_ms * interval '1 millisecond')
       ) THEN
        UPDATE public.inbox_events
           SET processed_at = CASE
                   WHEN last_error = 'workflow.reconciliation-absence-candidate'
                       THEN processed_at
                   ELSE db_now
               END,
               last_error = 'workflow.reconciliation-absence-candidate'
         WHERE organization_id = p_organization_id
           AND event_id = p_event_id
           AND consumer = 'investigation-workflow-reconciler-v1'
           AND status = 'received';
        UPDATE public.outbox_events
           SET lease_token = NULL,
               lease_expires_at = NULL,
               last_error = 'workflow.reconciliation-required',
               next_attempt_at = CASE
                   WHEN reconciliation_row.last_error
                            = 'workflow.reconciliation-absence-candidate'
                        AND reconciliation_row.processed_at IS NOT NULL
                       THEN reconciliation_row.processed_at
                           + (p_absence_confirmation_delay_ms
                                * interval '1 millisecond')
                   ELSE db_now
                       + (p_absence_confirmation_delay_ms * interval '1 millisecond')
               END
         WHERE organization_id = p_organization_id
           AND event_id = p_event_id
           AND lease_token = p_lease_token
           AND lease_expires_at > db_now;
        RETURN 'workflow.reconciliation-absence-candidate';
    END IF;

    IF p_outcome = 'RETRY' THEN
        UPDATE public.inbox_events
           SET processed_at = db_now,
               last_error = p_error_code
         WHERE organization_id = p_organization_id
           AND event_id = p_event_id
           AND consumer = 'investigation-workflow-reconciler-v1'
           AND status = 'received';
        UPDATE public.outbox_events
           SET lease_token = NULL,
               lease_expires_at = NULL,
               last_error = 'workflow.reconciliation-required',
               next_attempt_at = db_now
                   + (p_retry_delay_ms * interval '1 millisecond')
         WHERE organization_id = p_organization_id
           AND event_id = p_event_id
           AND lease_token = p_lease_token
           AND lease_expires_at > db_now;
        RETURN 'workflow.reconciliation-retry-scheduled';
    END IF;

    IF p_outcome = 'BLOCKED' THEN
        UPDATE public.inbox_events
           SET processed_at = db_now,
               status = 'poisoned',
               last_error = p_error_code
         WHERE organization_id = p_organization_id
           AND event_id = p_event_id
           AND consumer = 'investigation-workflow-reconciler-v1'
           AND status = 'received';
        UPDATE public.outbox_events
           SET lease_token = NULL,
               lease_expires_at = NULL,
               last_error = 'workflow.reconciliation-required',
               next_attempt_at = db_now
         WHERE organization_id = p_organization_id
           AND event_id = p_event_id
           AND lease_token = p_lease_token
           AND lease_expires_at > db_now;
        RETURN 'workflow.reconciliation-blocked';
    END IF;

    INSERT INTO public.inbox_events (
        event_id, organization_id, consumer, received_at, attempts
    ) VALUES (
        p_event_id,
        p_organization_id,
        'investigation-workflow-starter-v1',
        db_now,
        locked.outbox_attempts
    )
    ON CONFLICT (organization_id, event_id, consumer) DO NOTHING;
    SELECT starter_inbox.event_id
      INTO starter_event_id
      FROM public.inbox_events starter_inbox
     WHERE starter_inbox.organization_id = p_organization_id
       AND starter_inbox.event_id = p_event_id
       AND starter_inbox.consumer = 'investigation-workflow-starter-v1'
       AND starter_inbox.status = 'received';
    IF starter_event_id IS NULL THEN
        RAISE EXCEPTION 'workflow reconciliation starter inbox is not available'
            USING ERRCODE = 'P7013';
    END IF;

    IF p_outcome = 'MATCH' THEN
        UPDATE public.investigation_workflow_bindings
           SET status = 'STARTED',
               temporal_run_id = p_temporal_first_run_id,
               temporal_started_at = db_now,
               updated_at = db_now
         WHERE organization_id = p_organization_id
           AND run_id = locked.run_id
           AND start_event_id = p_event_id
           AND status = 'PENDING';
        GET DIAGNOSTICS affected_rows = ROW_COUNT;
        IF affected_rows <> 1 THEN
            RAISE EXCEPTION 'workflow reconciliation match binding transition failed'
                USING ERRCODE = 'P7013';
        END IF;
        UPDATE public.inbox_events
           SET status = 'processed', processed_at = db_now, last_error = NULL
         WHERE organization_id = p_organization_id
           AND event_id = p_event_id
           AND consumer IN (
               'investigation-workflow-starter-v1',
               'investigation-workflow-reconciler-v1'
           )
           AND status = 'received';
        UPDATE public.outbox_events
           SET published_at = db_now,
               lease_token = NULL,
               lease_expires_at = NULL,
               last_error = NULL
         WHERE organization_id = p_organization_id
           AND event_id = p_event_id
           AND lease_token = p_lease_token
           AND lease_expires_at > db_now
           AND published_at IS NULL
           AND poisoned_at IS NULL;
        GET DIAGNOSTICS affected_rows = ROW_COUNT;
        IF affected_rows <> 1 THEN
            RAISE EXCEPTION 'workflow reconciliation match publication failed'
                USING ERRCODE = 'P7013';
        END IF;
        RETURN 'workflow.reconciliation-started';
    END IF;

    UPDATE public.investigation_workflow_bindings
       SET status = 'REJECTED',
           rejection_code = CASE
               WHEN p_outcome = 'ABSENT'
                   THEN 'workflow.temporal-start-not-found'
               ELSE 'workflow.existing-contract-mismatch'
           END,
           rejected_at = db_now,
           updated_at = db_now
     WHERE organization_id = p_organization_id
       AND run_id = locked.run_id
       AND start_event_id = p_event_id
       AND status = 'PENDING';
    GET DIAGNOSTICS affected_rows = ROW_COUNT;
    IF affected_rows <> 1 THEN
        RAISE EXCEPTION 'workflow reconciliation rejection binding transition failed'
            USING ERRCODE = 'P7013';
    END IF;

    UPDATE public.inbox_events
       SET processed_at = db_now,
           status = CASE
               WHEN consumer = 'investigation-workflow-reconciler-v1'
                   THEN 'processed'
               ELSE 'poisoned'
           END,
           last_error = CASE
               WHEN p_outcome = 'ABSENT'
                   THEN 'workflow.temporal-start-not-found'
               ELSE 'workflow.existing-contract-mismatch'
           END
     WHERE organization_id = p_organization_id
       AND event_id = p_event_id
       AND consumer IN (
           'investigation-workflow-starter-v1',
           'investigation-workflow-reconciler-v1'
       )
       AND status = 'received';
    UPDATE public.outbox_events
       SET lease_token = NULL,
           lease_expires_at = NULL,
           last_error = CASE
               WHEN p_outcome = 'ABSENT'
                   THEN 'workflow.temporal-start-not-found'
               ELSE 'workflow.existing-contract-mismatch'
           END,
           next_attempt_at = db_now,
           poisoned_at = db_now
     WHERE organization_id = p_organization_id
       AND event_id = p_event_id
       AND lease_token = p_lease_token
       AND lease_expires_at > db_now
       AND published_at IS NULL
       AND poisoned_at IS NULL;
    GET DIAGNOSTICS affected_rows = ROW_COUNT;
    IF affected_rows <> 1 THEN
        RAISE EXCEPTION 'workflow reconciliation rejection publication failed'
            USING ERRCODE = 'P7013';
    END IF;
    RETURN CASE
        WHEN p_outcome = 'ABSENT'
            THEN 'workflow.reconciliation-verified-absence'
        ELSE 'workflow.reconciliation-contract-mismatch'
    END;
END
$$;
ALTER FUNCTION public.opsmind_settle_investigation_workflow_reconciliation(
    uuid, uuid, uuid, varchar, varchar, varchar, bigint, bigint, bigint
) OWNER TO opsmind_workflow_reconciliation_resolver;

CREATE OR REPLACE FUNCTION opsmind_get_investigation_workflow_reconciliation_status()
RETURNS TABLE(
    claim_ready_count bigint,
    pending_count bigint,
    blocked_count bigint,
    exhausted_count bigint,
    retention_ineligible_count bigint,
    oldest_pending_age_seconds bigint
)
LANGUAGE plpgsql
SECURITY DEFINER
STABLE
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    db_now timestamptz := statement_timestamp();
BEGIN
    IF session_user <> 'opsmind_workflow_reconciler' THEN
        RAISE EXCEPTION 'dedicated workflow reconciler identity is required'
            USING ERRCODE = '42501';
    END IF;

    RETURN QUERY
    WITH pending AS (
        SELECT event_row.event_id,
               event_row.organization_id,
               event_row.occurred_at,
               event_row.next_attempt_at,
               event_row.lease_expires_at,
               event_row.last_error AS outbox_last_error,
               reconciliation_inbox.status AS reconciliation_status,
               reconciliation_inbox.last_error AS reconciliation_last_error,
               NOT EXISTS (
                   SELECT 1
                     FROM public.organizations organization_row
                     JOIN public.service_accounts account_row
                       ON account_row.organization_id = organization_row.id
                      AND account_row.status = 'active'
                      AND account_row.database_principal = 'opsmind_dispatcher'
                      AND account_row.allowed_audiences
                            @> '["opsmind-outbox-dispatcher"]'::jsonb
                      AND account_row.allowed_scopes
                            @> '["outbox:dispatch"]'::jsonb
                    WHERE organization_row.id = event_row.organization_id
                      AND organization_row.status = 'active'
               ) AS dispatcher_ineligible
          FROM public.outbox_events event_row
          JOIN public.investigation_workflow_bindings binding_row
            ON binding_row.organization_id = event_row.organization_id
           AND binding_row.run_id = event_row.aggregate_id
           AND binding_row.start_event_id = event_row.event_id
           AND binding_row.status = 'PENDING'
          LEFT JOIN public.inbox_events reconciliation_inbox
            ON reconciliation_inbox.organization_id = event_row.organization_id
           AND reconciliation_inbox.event_id = event_row.event_id
           AND reconciliation_inbox.consumer
                = 'investigation-workflow-reconciler-v1'
         WHERE event_row.event_type = 'investigation.workflow-start.requested'
           AND event_row.schema_version = '1'
           AND event_row.aggregate_type = 'investigation-workflow'
           AND event_row.aggregate_sequence = 1
           AND event_row.attempts > 0
           AND event_row.published_at IS NULL
           AND event_row.poisoned_at IS NULL
    )
    SELECT
        count(*) FILTER (
            WHERE (
                    reconciliation_status IS NULL
                    OR reconciliation_status IN ('received', 'processed')
                  )
              AND next_attempt_at <= db_now
              AND (lease_expires_at IS NULL OR lease_expires_at <= db_now)
              AND (
                  reconciliation_status = 'received'
                  OR
                  outbox_last_error = 'workflow.reconciliation-required'
                  OR dispatcher_ineligible
              )
        )::bigint,
        count(*)::bigint,
        count(*) FILTER (
            WHERE reconciliation_status = 'poisoned'
              AND reconciliation_last_error NOT IN (
                  'workflow.reconciliation-exhausted',
                  'workflow.reconciliation-retention-unverifiable'
              )
        )::bigint,
        count(*) FILTER (
            WHERE reconciliation_status = 'poisoned'
              AND reconciliation_last_error = 'workflow.reconciliation-exhausted'
        )::bigint,
        count(*) FILTER (
            WHERE reconciliation_status = 'poisoned'
              AND reconciliation_last_error
                    = 'workflow.reconciliation-retention-unverifiable'
        )::bigint,
        COALESCE(
            GREATEST(
                0,
                floor(extract(epoch FROM db_now - min(occurred_at)))::bigint
            ),
            0
        )::bigint
      FROM pending;
END
$$;
ALTER FUNCTION public.opsmind_get_investigation_workflow_reconciliation_status()
    OWNER TO opsmind_workflow_reconciliation_resolver;

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM opsmind_workflow_reconciler;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM opsmind_workflow_reconciler;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM opsmind_workflow_reconciler;
REVOKE ALL ON FUNCTION
    public.opsmind_validate_investigation_workflow_binding_update()
    FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_claim_investigation_workflow_reconciliation(
    uuid, bigint, integer, bigint
) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_settle_investigation_workflow_reconciliation(
    uuid, uuid, uuid, varchar, varchar, varchar, bigint, bigint, bigint
) FROM PUBLIC;
REVOKE ALL ON FUNCTION
    public.opsmind_get_investigation_workflow_reconciliation_status()
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.opsmind_claim_investigation_workflow_reconciliation(
    uuid, bigint, integer, bigint
) TO opsmind_workflow_reconciler;
GRANT EXECUTE ON FUNCTION public.opsmind_settle_investigation_workflow_reconciliation(
    uuid, uuid, uuid, varchar, varchar, varchar, bigint, bigint, bigint
) TO opsmind_workflow_reconciler;
GRANT EXECUTE ON FUNCTION
    public.opsmind_get_investigation_workflow_reconciliation_status()
    TO opsmind_workflow_reconciler;

COMMENT ON FUNCTION public.opsmind_claim_investigation_workflow_reconciliation(
    uuid, bigint, integer, bigint
) IS
    'Claims at most one previously attempted exact workflow start without incrementing normal dispatch attempts.';
COMMENT ON FUNCTION public.opsmind_settle_investigation_workflow_reconciliation(
    uuid, uuid, uuid, varchar, varchar, varchar, bigint, bigint, bigint
) IS
    'Atomically settles exact observation evidence, two-sample absence, retry, blocking, or starter reactivation.';
COMMENT ON FUNCTION
    public.opsmind_get_investigation_workflow_reconciliation_status()
IS
    'Returns bounded aggregate workflow reconciliation counts without tenant-sensitive fields.';
