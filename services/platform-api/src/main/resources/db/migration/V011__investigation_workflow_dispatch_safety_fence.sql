-- Forward-only dispatcher hardening for the Phase 9 Temporal workflow-start
-- handoff. V010 remains immutable. This migration separates active-account
-- claim authority from the narrow capability needed to settle one live lease.

-- The read-only context resolver evaluates a context-free preflight. It can
-- return only a bounded code and never exposes the joined identity rows.
GRANT SELECT (id, organization_id, project_id, version)
    ON incidents TO opsmind_context_resolver;
GRANT SELECT (
    id, organization_id, status, allowed_audiences, allowed_scopes, database_principal
)
    ON service_accounts TO opsmind_context_resolver;
GRANT SELECT (
    event_id, organization_id, aggregate_type, aggregate_id, aggregate_sequence,
    event_type, schema_version, published_at, lease_token, lease_expires_at,
    poisoned_at
) ON outbox_events TO opsmind_context_resolver;
GRANT SELECT (
    organization_id, run_id, project_id, incident_id, actor_id, start_event_id,
    authorization_revision, status, deadline_at
) ON investigation_workflow_bindings TO opsmind_context_resolver;
-- PostgreSQL locking reads require UPDATE privilege. This capability belongs
-- only to the existing NOLOGIN resolver role and is reachable from the runtime
-- solely through the fixed function below.
GRANT UPDATE (status) ON service_accounts TO opsmind_context_resolver;

CREATE POLICY incidents_context_resolution
    ON incidents
    FOR SELECT TO opsmind_context_resolver
    USING (true);
CREATE POLICY service_accounts_context_resolution
    ON service_accounts
    FOR SELECT TO opsmind_context_resolver
    USING (true);
CREATE POLICY outbox_events_context_resolution
    ON outbox_events
    FOR SELECT TO opsmind_context_resolver
    USING (true);
CREATE POLICY investigation_workflow_bindings_context_resolution
    ON investigation_workflow_bindings
    FOR SELECT TO opsmind_context_resolver
    USING (true);

CREATE OR REPLACE FUNCTION opsmind_lock_eligible_investigation_dispatcher(
    p_organization_id uuid
) RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    eligible_account_id uuid;
BEGIN
    IF session_user <> 'opsmind_app' THEN
        RAISE EXCEPTION 'application identity is required for workflow admission'
            USING ERRCODE = '42501';
    END IF;
    IF p_organization_id IS NULL THEN
        RAISE EXCEPTION 'workflow admission requires an organization'
            USING ERRCODE = '22023';
    END IF;

    SELECT account_row.id
      INTO eligible_account_id
      FROM public.organizations organization_row
      JOIN public.service_accounts account_row
        ON account_row.organization_id = organization_row.id
       AND account_row.status = 'active'
       AND account_row.database_principal = 'opsmind_dispatcher'
       AND account_row.allowed_audiences @> '["opsmind-outbox-dispatcher"]'::jsonb
       AND account_row.allowed_scopes @> '["outbox:dispatch"]'::jsonb
     WHERE organization_row.id = p_organization_id
       AND organization_row.status = 'active'
     ORDER BY account_row.id
     LIMIT 1
     FOR SHARE OF organization_row, account_row;

    RETURN eligible_account_id;
END
$$;
ALTER FUNCTION public.opsmind_lock_eligible_investigation_dispatcher(uuid)
    OWNER TO opsmind_context_resolver;

CREATE OR REPLACE FUNCTION opsmind_preflight_investigation_workflow_start(
    p_organization_id uuid,
    p_event_id uuid,
    p_lease_token uuid,
    p_required_rpc_window_ms bigint
) RETURNS varchar(128)
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    db_now timestamptz := clock_timestamp();
BEGIN
    IF session_user <> 'opsmind_dispatcher' THEN
        RAISE EXCEPTION 'dedicated dispatcher identity is required'
            USING ERRCODE = '42501';
    END IF;
    IF p_organization_id IS NULL OR p_event_id IS NULL OR p_lease_token IS NULL THEN
        RAISE EXCEPTION 'workflow dispatch preflight requires tenant, event, and lease identity'
            USING ERRCODE = '22023';
    END IF;
    IF p_required_rpc_window_ms IS NULL
       OR p_required_rpc_window_ms < 1
       OR p_required_rpc_window_ms > 600000 THEN
        RAISE EXCEPTION 'workflow dispatch preflight RPC window is outside policy'
            USING ERRCODE = '22023';
    END IF;

    IF NOT EXISTS (
        SELECT 1
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
           AND event_row.lease_token = p_lease_token
           AND event_row.lease_expires_at > db_now
           AND event_row.published_at IS NULL
           AND event_row.poisoned_at IS NULL
    ) THEN
        RETURN 'workflow.lease-lost';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM public.outbox_events event_row
         WHERE event_row.organization_id = p_organization_id
           AND event_row.event_id = p_event_id
           AND event_row.lease_token = p_lease_token
           AND event_row.lease_expires_at
                <= db_now + (p_required_rpc_window_ms * interval '1 millisecond')
    ) THEN
        RETURN 'workflow.lease-window-exhausted';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM public.investigation_workflow_bindings binding_row
         WHERE binding_row.organization_id = p_organization_id
           AND binding_row.start_event_id = p_event_id
           AND binding_row.status = 'PENDING'
           AND binding_row.deadline_at
                <= db_now + (p_required_rpc_window_ms * interval '1 millisecond')
    ) THEN
        RETURN 'workflow.deadline-exhausted';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM public.organizations organization_row
          JOIN public.service_accounts account_row
            ON account_row.organization_id = organization_row.id
           AND account_row.status = 'active'
           AND account_row.database_principal = session_user
           AND account_row.allowed_audiences @> '["opsmind-outbox-dispatcher"]'::jsonb
           AND account_row.allowed_scopes @> '["outbox:dispatch"]'::jsonb
         WHERE organization_row.id = p_organization_id
           AND organization_row.status = 'active'
    ) THEN
        RETURN 'workflow.dispatcher-ineligible';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM public.investigation_workflow_bindings binding_row
          JOIN public.incidents incident_row
            ON incident_row.id = binding_row.incident_id
           AND incident_row.organization_id = binding_row.organization_id
           AND incident_row.project_id = binding_row.project_id
           AND incident_row.version = binding_row.authorization_revision
          JOIN public.organizations organization_row
            ON organization_row.id = binding_row.organization_id
           AND organization_row.status = 'active'
          JOIN public.platform_users user_row
            ON user_row.id = binding_row.actor_id
           AND user_row.status = 'active'
          JOIN public.organization_memberships organization_membership
            ON organization_membership.organization_id = binding_row.organization_id
           AND organization_membership.user_id = binding_row.actor_id
           AND organization_membership.status = 'active'
           AND organization_membership.role IN ('ADMIN', 'SRE')
          JOIN public.projects project_row
            ON project_row.id = binding_row.project_id
           AND project_row.organization_id = binding_row.organization_id
           AND project_row.status = 'active'
          JOIN public.project_memberships project_membership
            ON project_membership.organization_id = binding_row.organization_id
           AND project_membership.project_id = binding_row.project_id
           AND project_membership.user_id = binding_row.actor_id
           AND project_membership.status = 'active'
           AND project_membership.role IN ('ADMIN', 'SRE')
         WHERE binding_row.organization_id = p_organization_id
           AND binding_row.start_event_id = p_event_id
           AND binding_row.status = 'PENDING'
    ) THEN
        RETURN 'workflow.authorization-revoked';
    END IF;

    RETURN 'workflow.preflight-allowed';
END
$$;
ALTER FUNCTION public.opsmind_preflight_investigation_workflow_start(
    uuid, uuid, uuid, bigint
) OWNER TO opsmind_context_resolver;

-- The existing dispatch resolver owns the mutating functions. It is a
-- NOLOGIN/NOBYPASSRLS role established by V002, so callers only gain the
-- fixed event-and-lease transition below, never a tenant-wide context.
GRANT SELECT (
    event_id, organization_id, aggregate_type, aggregate_id, aggregate_sequence,
    event_type, schema_version, occurred_at, published_at, attempts, lease_token,
    lease_expires_at, poisoned_at
) ON outbox_events TO opsmind_dispatch_resolver;
GRANT UPDATE (
    published_at, last_error, next_attempt_at, lease_token, lease_expires_at,
    poisoned_at
) ON outbox_events TO opsmind_dispatch_resolver;
GRANT SELECT (
    organization_id, run_id, start_event_id, status, deadline_at
) ON investigation_workflow_bindings TO opsmind_dispatch_resolver;
GRANT UPDATE (
    status, temporal_run_id, rejection_code, updated_at,
    temporal_started_at, rejected_at
) ON investigation_workflow_bindings TO opsmind_dispatch_resolver;
GRANT SELECT (
    organization_id, event_id, consumer, status, attempts, last_error
) ON inbox_events TO opsmind_dispatch_resolver;
GRANT INSERT (event_id, organization_id, consumer, attempts)
    ON inbox_events TO opsmind_dispatch_resolver;
GRANT UPDATE (status, processed_at, attempts, last_error)
    ON inbox_events TO opsmind_dispatch_resolver;

CREATE POLICY outbox_events_dispatch_settlement
    ON outbox_events
    FOR UPDATE TO opsmind_dispatch_resolver
    USING (true)
    WITH CHECK (true);
CREATE POLICY investigation_workflow_bindings_dispatch_settlement
    ON investigation_workflow_bindings
    FOR UPDATE TO opsmind_dispatch_resolver
    USING (true)
    WITH CHECK (true);
CREATE POLICY inbox_events_dispatch_settlement_select
    ON inbox_events
    FOR SELECT TO opsmind_dispatch_resolver
    USING (true);
CREATE POLICY inbox_events_dispatch_settlement_insert
    ON inbox_events
    FOR INSERT TO opsmind_dispatch_resolver
    WITH CHECK (true);
CREATE POLICY inbox_events_dispatch_settlement_update
    ON inbox_events
    FOR UPDATE TO opsmind_dispatch_resolver
    USING (true)
    WITH CHECK (true);

CREATE OR REPLACE FUNCTION opsmind_settle_investigation_workflow_start(
    p_organization_id uuid,
    p_event_id uuid,
    p_lease_token uuid,
    p_outcome varchar(16),
    p_temporal_run_id varchar(255),
    p_error_code varchar(128),
    p_retry_delay_ms bigint
) RETURNS varchar(128)
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    locked record;
    db_now timestamptz;
    effective_outcome varchar(16) := p_outcome;
    effective_error_code varchar(128) := p_error_code;
    claimed_inbox_event_id uuid;
    affected_rows integer;
BEGIN
    IF session_user <> 'opsmind_dispatcher' THEN
        RAISE EXCEPTION 'dedicated dispatcher identity is required'
            USING ERRCODE = '42501';
    END IF;
    IF p_organization_id IS NULL OR p_event_id IS NULL OR p_lease_token IS NULL THEN
        RAISE EXCEPTION 'workflow settlement requires tenant, event, and lease identity'
            USING ERRCODE = '22023';
    END IF;
    IF p_outcome IS NULL OR p_outcome NOT IN ('STARTED', 'RETRY', 'REJECTED') THEN
        RAISE EXCEPTION 'workflow settlement outcome is outside policy'
            USING ERRCODE = '22023';
    END IF;
    IF p_outcome = 'STARTED' AND (
        p_temporal_run_id IS NULL
        OR length(trim(p_temporal_run_id)) NOT BETWEEN 1 AND 255
        OR p_error_code IS NOT NULL
        OR p_retry_delay_ms IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'started settlement inputs are invalid'
            USING ERRCODE = '22023';
    END IF;
    IF p_outcome = 'REJECTED' AND (
        p_temporal_run_id IS NOT NULL
        OR p_retry_delay_ms IS NOT NULL
        OR p_error_code IS NULL
        OR p_error_code !~ '^workflow\.[a-z0-9][a-z0-9._-]{0,118}$'
    ) THEN
        RAISE EXCEPTION 'rejected settlement inputs are invalid'
            USING ERRCODE = '22023';
    END IF;
    IF p_outcome = 'RETRY' AND (
        p_temporal_run_id IS NOT NULL
        OR p_error_code IS NULL
        OR p_error_code !~ '^workflow\.[a-z0-9][a-z0-9._-]{0,118}$'
        OR p_retry_delay_ms IS NULL
        OR p_retry_delay_ms < 100
        OR p_retry_delay_ms > 900000
    ) THEN
        RAISE EXCEPTION 'retry settlement inputs are invalid'
            USING ERRCODE = '22023';
    END IF;

    SELECT event_row.organization_id, event_row.event_id, event_row.aggregate_id,
           event_row.lease_expires_at, binding_row.run_id
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
       AND event_row.lease_token = p_lease_token
       AND event_row.published_at IS NULL
       AND event_row.poisoned_at IS NULL
     FOR UPDATE OF event_row, binding_row;
    IF NOT FOUND THEN
        RETURN 'workflow.lease-lost';
    END IF;

    db_now := clock_timestamp();
    IF locked.lease_expires_at IS NULL OR locked.lease_expires_at <= db_now THEN
        RETURN 'workflow.lease-lost';
    END IF;

    IF effective_outcome = 'RETRY' THEN
        UPDATE public.outbox_events
           SET lease_token = NULL,
               lease_expires_at = NULL,
               last_error = effective_error_code,
               next_attempt_at = db_now + (p_retry_delay_ms * interval '1 millisecond')
         WHERE organization_id = p_organization_id
           AND event_id = p_event_id
           AND lease_token = p_lease_token
           AND lease_expires_at > db_now
           AND published_at IS NULL
           AND poisoned_at IS NULL;
        GET DIAGNOSTICS affected_rows = ROW_COUNT;
        IF affected_rows <> 1 THEN
            RAISE EXCEPTION 'workflow retry settlement lost its live lease'
                USING ERRCODE = 'P7010';
        END IF;
        RETURN 'workflow.retry-scheduled';
    END IF;

    INSERT INTO public.inbox_events (event_id, organization_id, consumer, attempts)
    VALUES (p_event_id, p_organization_id, 'investigation-workflow-starter-v1', 1)
    ON CONFLICT (organization_id, event_id, consumer) DO UPDATE
       SET attempts = public.inbox_events.attempts + 1,
           last_error = NULL
     WHERE public.inbox_events.status = 'received'
    RETURNING event_id INTO claimed_inbox_event_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'workflow settlement inbox claim is not available'
            USING ERRCODE = 'P7010';
    END IF;

    IF effective_outcome = 'STARTED' THEN
        UPDATE public.investigation_workflow_bindings
           SET status = 'STARTED',
               temporal_run_id = p_temporal_run_id,
               temporal_started_at = db_now,
               updated_at = db_now
         WHERE organization_id = p_organization_id
           AND run_id = locked.run_id
           AND start_event_id = p_event_id
           AND status = 'PENDING';
        GET DIAGNOSTICS affected_rows = ROW_COUNT;
        IF affected_rows <> 1 THEN
            RAISE EXCEPTION 'workflow started settlement binding transition failed'
                USING ERRCODE = 'P7010';
        END IF;

        UPDATE public.inbox_events
           SET status = 'processed', processed_at = db_now, last_error = NULL
         WHERE organization_id = p_organization_id
           AND event_id = p_event_id
           AND consumer = 'investigation-workflow-starter-v1'
           AND status = 'received';
        GET DIAGNOSTICS affected_rows = ROW_COUNT;
        IF affected_rows <> 1 THEN
            RAISE EXCEPTION 'workflow started settlement inbox transition failed'
                USING ERRCODE = 'P7010';
        END IF;

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
            RAISE EXCEPTION 'workflow started settlement publication failed'
                USING ERRCODE = 'P7010';
        END IF;
        RETURN 'workflow.started';
    END IF;

    UPDATE public.investigation_workflow_bindings
       SET status = 'REJECTED',
           rejection_code = effective_error_code,
           rejected_at = db_now,
           updated_at = db_now
     WHERE organization_id = p_organization_id
       AND run_id = locked.run_id
       AND start_event_id = p_event_id
       AND status = 'PENDING';
    GET DIAGNOSTICS affected_rows = ROW_COUNT;
    IF affected_rows <> 1 THEN
        RAISE EXCEPTION 'workflow rejected settlement binding transition failed'
            USING ERRCODE = 'P7010';
    END IF;

    UPDATE public.inbox_events
       SET status = 'poisoned', last_error = effective_error_code
     WHERE organization_id = p_organization_id
       AND event_id = p_event_id
       AND consumer = 'investigation-workflow-starter-v1'
       AND status = 'received';
    GET DIAGNOSTICS affected_rows = ROW_COUNT;
    IF affected_rows <> 1 THEN
        RAISE EXCEPTION 'workflow rejected settlement inbox transition failed'
            USING ERRCODE = 'P7010';
    END IF;

    UPDATE public.outbox_events
       SET lease_token = NULL,
           lease_expires_at = NULL,
           last_error = effective_error_code,
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
        RAISE EXCEPTION 'workflow rejected settlement publication failed'
            USING ERRCODE = 'P7010';
    END IF;
    RETURN 'workflow.rejected';
END
$$;
ALTER FUNCTION public.opsmind_settle_investigation_workflow_start(
    uuid, uuid, uuid, varchar, varchar, varchar, bigint
) OWNER TO opsmind_dispatch_resolver;

-- Events never claimed cannot have reached Temporal. When their only
-- dispatcher account has already become ineligible, terminalize them without
-- creating a tenant context. Previously claimed/expired leases are excluded:
-- their remote outcome is ambiguous and needs an explicit reconciliation path.
CREATE OR REPLACE FUNCTION opsmind_terminalize_unclaimed_ineligible_workflow_starts(
    p_limit integer
) RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    candidate record;
    db_now timestamptz;
    claimed_inbox_event_id uuid;
    affected_rows integer;
    terminalized integer := 0;
BEGIN
    IF session_user <> 'opsmind_dispatcher' THEN
        RAISE EXCEPTION 'dedicated dispatcher identity is required'
            USING ERRCODE = '42501';
    END IF;
    IF p_limit IS NULL OR p_limit < 1 OR p_limit > 100 THEN
        RAISE EXCEPTION 'workflow ineligible terminalizer limit is outside policy'
            USING ERRCODE = '22023';
    END IF;

    FOR candidate IN
        SELECT event_row.organization_id, event_row.event_id, event_row.aggregate_id
          FROM public.outbox_events event_row
          JOIN public.investigation_workflow_bindings binding_row
            ON binding_row.organization_id = event_row.organization_id
           AND binding_row.run_id = event_row.aggregate_id
           AND binding_row.start_event_id = event_row.event_id
           AND binding_row.status = 'PENDING'
         WHERE event_row.event_type = 'investigation.workflow-start.requested'
           AND event_row.schema_version = '1'
           AND event_row.aggregate_type = 'investigation-workflow'
           AND event_row.aggregate_sequence = 1
           AND event_row.published_at IS NULL
           AND event_row.poisoned_at IS NULL
           AND event_row.attempts = 0
           AND event_row.lease_token IS NULL
           AND event_row.lease_expires_at IS NULL
           AND NOT EXISTS (
               SELECT 1
                 FROM public.organizations organization_row
                 JOIN public.service_accounts account_row
                   ON account_row.organization_id = organization_row.id
                  AND account_row.status = 'active'
                  AND account_row.database_principal = session_user
                  AND account_row.allowed_audiences
                        @> '["opsmind-outbox-dispatcher"]'::jsonb
                  AND account_row.allowed_scopes @> '["outbox:dispatch"]'::jsonb
                WHERE organization_row.id = event_row.organization_id
                  AND organization_row.status = 'active'
           )
         ORDER BY event_row.occurred_at, event_row.event_id
         FOR UPDATE OF event_row, binding_row SKIP LOCKED
         LIMIT p_limit
    LOOP
        IF EXISTS (
            SELECT 1
              FROM public.organizations organization_row
              JOIN public.service_accounts account_row
                ON account_row.organization_id = organization_row.id
               AND account_row.status = 'active'
               AND account_row.database_principal = session_user
               AND account_row.allowed_audiences
                    @> '["opsmind-outbox-dispatcher"]'::jsonb
               AND account_row.allowed_scopes @> '["outbox:dispatch"]'::jsonb
             WHERE organization_row.id = candidate.organization_id
               AND organization_row.status = 'active'
        ) THEN
            CONTINUE;
        END IF;

        db_now := clock_timestamp();
        INSERT INTO public.inbox_events (event_id, organization_id, consumer, attempts)
        VALUES (
            candidate.event_id,
            candidate.organization_id,
            'investigation-workflow-starter-v1',
            1
        )
        ON CONFLICT (organization_id, event_id, consumer) DO UPDATE
           SET attempts = public.inbox_events.attempts + 1,
               last_error = NULL
         WHERE public.inbox_events.status = 'received'
        RETURNING event_id INTO claimed_inbox_event_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'workflow terminalizer inbox claim is not available'
                USING ERRCODE = 'P7010';
        END IF;

        UPDATE public.investigation_workflow_bindings
           SET status = 'REJECTED',
               rejection_code = 'workflow.dispatcher-ineligible',
               rejected_at = db_now,
               updated_at = db_now
         WHERE organization_id = candidate.organization_id
           AND run_id = candidate.aggregate_id
           AND start_event_id = candidate.event_id
           AND status = 'PENDING';
        GET DIAGNOSTICS affected_rows = ROW_COUNT;
        IF affected_rows <> 1 THEN
            RAISE EXCEPTION 'workflow terminalizer binding transition failed'
                USING ERRCODE = 'P7010';
        END IF;

        UPDATE public.inbox_events
           SET status = 'poisoned', last_error = 'workflow.dispatcher-ineligible'
         WHERE organization_id = candidate.organization_id
           AND event_id = candidate.event_id
           AND consumer = 'investigation-workflow-starter-v1'
           AND status = 'received';
        GET DIAGNOSTICS affected_rows = ROW_COUNT;
        IF affected_rows <> 1 THEN
            RAISE EXCEPTION 'workflow terminalizer inbox transition failed'
                USING ERRCODE = 'P7010';
        END IF;

        UPDATE public.outbox_events
           SET lease_token = NULL,
               lease_expires_at = NULL,
               last_error = 'workflow.dispatcher-ineligible',
               next_attempt_at = db_now,
               poisoned_at = db_now
         WHERE organization_id = candidate.organization_id
           AND event_id = candidate.event_id
           AND attempts = 0
           AND lease_token IS NULL
           AND lease_expires_at IS NULL
           AND published_at IS NULL
           AND poisoned_at IS NULL;
        GET DIAGNOSTICS affected_rows = ROW_COUNT;
        IF affected_rows <> 1 THEN
            RAISE EXCEPTION 'workflow terminalizer outbox transition failed'
                USING ERRCODE = 'P7010';
        END IF;
        terminalized := terminalized + 1;
    END LOOP;
    RETURN terminalized;
END
$$;
ALTER FUNCTION public.opsmind_terminalize_unclaimed_ineligible_workflow_starts(integer)
    OWNER TO opsmind_dispatch_resolver;

REVOKE ALL ON FUNCTION public.opsmind_preflight_investigation_workflow_start(
    uuid, uuid, uuid, bigint
) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_lock_eligible_investigation_dispatcher(
    uuid
) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_settle_investigation_workflow_start(
    uuid, uuid, uuid, varchar, varchar, varchar, bigint
) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_terminalize_unclaimed_ineligible_workflow_starts(
    integer
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.opsmind_preflight_investigation_workflow_start(
    uuid, uuid, uuid, bigint
) TO opsmind_dispatcher;
GRANT EXECUTE ON FUNCTION public.opsmind_lock_eligible_investigation_dispatcher(
    uuid
) TO opsmind_app;
GRANT EXECUTE ON FUNCTION public.opsmind_settle_investigation_workflow_start(
    uuid, uuid, uuid, varchar, varchar, varchar, bigint
) TO opsmind_dispatcher;
GRANT EXECUTE ON FUNCTION public.opsmind_terminalize_unclaimed_ineligible_workflow_starts(
    integer
) TO opsmind_dispatcher;

COMMENT ON FUNCTION opsmind_preflight_investigation_workflow_start(
    uuid, uuid, uuid, bigint
) IS
    'Returns a bounded point-in-time decision for one live workflow-start lease without setting tenant context.';
COMMENT ON FUNCTION opsmind_lock_eligible_investigation_dispatcher(uuid) IS
    'Locks and returns one exact eligible dispatcher identity for application admission without granting runtime UPDATE authority.';
COMMENT ON FUNCTION opsmind_settle_investigation_workflow_start(
    uuid, uuid, uuid, varchar, varchar, varchar, bigint
) IS
    'Atomically records one live workflow-start lease outcome; it grants no general tenant capability.';
COMMENT ON FUNCTION opsmind_terminalize_unclaimed_ineligible_workflow_starts(integer) IS
    'Atomically poisons only never-claimed workflow starts whose dispatcher account is currently ineligible.';
