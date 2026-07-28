-- Forward-only workflow-start dispatch isolation. V010 and V011 remain
-- immutable: V012 removes the dispatcher login's direct reconciliation path
-- and grants the resolver only one canonical workflow-start claim capability.

DO $$
DECLARE
    dispatcher_unsafe boolean;
    resolver_unsafe boolean;
BEGIN
    SELECT role_row.rolsuper
        OR role_row.rolbypassrls
        OR NOT role_row.rolcanlogin
        OR role_row.rolinherit
        OR EXISTS (
            SELECT 1
              FROM pg_catalog.pg_auth_members membership
             WHERE membership.member = role_row.oid
        )
        OR EXISTS (
            SELECT 1
              FROM pg_catalog.pg_auth_members membership
             WHERE membership.roleid = role_row.oid
        )
      INTO dispatcher_unsafe
      FROM pg_catalog.pg_roles role_row
     WHERE role_row.rolname = 'opsmind_dispatcher';
    IF dispatcher_unsafe IS NULL OR dispatcher_unsafe THEN
        RAISE EXCEPTION 'opsmind_dispatcher has unsafe attributes or role memberships';
    END IF;

    SELECT role_row.rolsuper
        OR role_row.rolbypassrls
        OR role_row.rolcanlogin
        OR role_row.rolinherit
        OR EXISTS (
            SELECT 1
              FROM pg_catalog.pg_auth_members membership
             WHERE membership.member = role_row.oid
        )
        OR EXISTS (
            SELECT 1
              FROM pg_catalog.pg_auth_members membership
              JOIN pg_catalog.pg_roles member_role
                ON member_role.oid = membership.member
             WHERE membership.roleid = role_row.oid
               AND (
                   member_role.rolname <> session_user
                   OR membership.admin_option
                   OR NOT membership.inherit_option
                   OR NOT membership.set_option
               )
        )
      INTO resolver_unsafe
      FROM pg_catalog.pg_roles role_row
     WHERE role_row.rolname = 'opsmind_dispatch_resolver';
    IF resolver_unsafe IS NULL OR resolver_unsafe THEN
        RAISE EXCEPTION 'opsmind_dispatch_resolver has unsafe attributes or role memberships';
    END IF;
END
$$;

-- V011-era dispatchers used temporal-unavailable for a retryable Temporal
-- transport result whose remote outcome was not known. Preserve that safety
-- meaning only for attempted, live canonical starts; ordinary events,
-- unattempted starts, and already terminalized rows keep their original code.
UPDATE public.outbox_events event_row
   SET last_error = 'workflow.temporal-outcome-ambiguous'
 WHERE event_row.event_type = 'investigation.workflow-start.requested'
   AND event_row.schema_version = '1'
   AND event_row.aggregate_type = 'investigation-workflow'
   AND event_row.aggregate_sequence = 1
   AND event_row.published_at IS NULL
   AND event_row.poisoned_at IS NULL
   AND event_row.attempts > 0
   AND event_row.last_error = 'workflow.temporal-unavailable'
   AND EXISTS (
       SELECT 1
         FROM public.investigation_workflow_bindings binding_row
        WHERE binding_row.organization_id = event_row.organization_id
          AND binding_row.run_id = event_row.aggregate_id
          AND binding_row.start_event_id = event_row.event_id
          AND binding_row.status = 'PENDING'
   );

CREATE OR REPLACE FUNCTION opsmind_has_unpublished_outbox_predecessor(
    p_organization_id uuid,
    p_aggregate_type varchar,
    p_aggregate_id uuid,
    p_aggregate_sequence bigint
) RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
STABLE
SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF session_user <> 'opsmind_dispatcher' THEN
        RAISE EXCEPTION 'dedicated dispatcher identity is required'
            USING ERRCODE = '42501';
    END IF;
    IF p_organization_id IS NULL
       OR p_aggregate_type IS NULL
       OR length(trim(p_aggregate_type)) NOT BETWEEN 1 AND 128
       OR p_aggregate_id IS NULL
       OR p_aggregate_sequence IS NULL
       OR p_aggregate_sequence < 1 THEN
        RAISE EXCEPTION 'outbox predecessor identity is invalid'
            USING ERRCODE = '22023';
    END IF;
    IF p_organization_id IS DISTINCT FROM public.opsmind_current_tenant_id() THEN
        RAISE EXCEPTION 'outbox predecessor lookup requires its bound tenant'
            USING ERRCODE = '42501';
    END IF;

    RETURN EXISTS (
        SELECT 1
          FROM public.outbox_events predecessor
         WHERE predecessor.organization_id = p_organization_id
           AND predecessor.aggregate_type = p_aggregate_type
           AND predecessor.aggregate_id = p_aggregate_id
           AND predecessor.aggregate_sequence < p_aggregate_sequence
           AND predecessor.published_at IS NULL
    );
END
$$;
ALTER FUNCTION public.opsmind_has_unpublished_outbox_predecessor(
    uuid, varchar, uuid, bigint
) OWNER TO opsmind_dispatch_resolver;
REVOKE ALL ON FUNCTION public.opsmind_has_unpublished_outbox_predecessor(
    uuid, varchar, uuid, bigint
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.opsmind_has_unpublished_outbox_predecessor(
    uuid, varchar, uuid, bigint
) TO opsmind_dispatcher;

REVOKE ALL ON TABLE public.investigation_workflow_bindings FROM opsmind_dispatcher;
REVOKE ALL ON TABLE public.inbox_events FROM opsmind_dispatcher;
-- Column ACLs survive a table-level REVOKE in PostgreSQL. V010 granted these
-- exact columns directly, so remove them explicitly as well.
REVOKE SELECT ON TABLE public.investigation_workflow_bindings FROM opsmind_dispatcher;
REVOKE UPDATE (
    status, temporal_run_id, rejection_code, updated_at, temporal_started_at, rejected_at
) ON TABLE public.investigation_workflow_bindings FROM opsmind_dispatcher;
REVOKE SELECT, INSERT ON TABLE public.inbox_events FROM opsmind_dispatcher;
REVOKE UPDATE (
    status, processed_at, attempts, last_error
) ON TABLE public.inbox_events FROM opsmind_dispatcher;

-- The generic dispatcher still owns ordinary outbox work. Exact canonical
-- workflow starts are invisible to its direct SELECT and UPDATE paths so only
-- the resolver-owned workflow functions can claim or settle them.
CREATE POLICY outbox_events_dispatcher_excludes_investigation_workflow_start
    ON outbox_events
    AS RESTRICTIVE
    FOR ALL TO opsmind_dispatcher
    USING (
        NOT (
            event_type = 'investigation.workflow-start.requested'
            AND schema_version = '1'
            AND aggregate_type = 'investigation-workflow'
            AND aggregate_sequence = 1
        )
    )
    WITH CHECK (
        NOT (
            event_type = 'investigation.workflow-start.requested'
            AND schema_version = '1'
            AND aggregate_type = 'investigation-workflow'
            AND aggregate_sequence = 1
        )
    );

-- Replace V002's generic tenant selector without changing its active tenant,
-- workload account, audience, scope, ready, lease, or predecessor fences.
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
       AND (
           event_row.lease_expires_at IS NULL
           OR event_row.lease_expires_at <= statement_timestamp()
       )
       AND NOT (
           event_row.event_type = 'investigation.workflow-start.requested'
           AND event_row.schema_version = '1'
           AND event_row.aggregate_type = 'investigation-workflow'
           AND event_row.aggregate_sequence = 1
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
ALTER FUNCTION public.opsmind_list_dispatch_tenants(integer)
    OWNER TO opsmind_dispatch_resolver;

-- Preflight is a context-free resolver function. The ambiguity marker must be
-- observed before any ordinary terminal fence so a possibly successful remote
-- start stays pending for reconciliation rather than being poisoned locally.
GRANT SELECT (last_error) ON outbox_events TO opsmind_context_resolver;

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
    ambiguous_outcome boolean := false;
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

    SELECT event_row.last_error IN (
        'workflow.temporal-outcome-ambiguous',
        'workflow.temporal-unavailable'
    )
      INTO ambiguous_outcome
      FROM public.outbox_events event_row
     WHERE event_row.organization_id = p_organization_id
       AND event_row.event_id = p_event_id
       AND event_row.lease_token = p_lease_token;
    ambiguous_outcome := COALESCE(ambiguous_outcome, false);

    IF EXISTS (
        SELECT 1
          FROM public.outbox_events event_row
         WHERE event_row.organization_id = p_organization_id
           AND event_row.event_id = p_event_id
           AND event_row.lease_token = p_lease_token
           AND event_row.lease_expires_at
                <= db_now + (p_required_rpc_window_ms * interval '1 millisecond')
    ) THEN
        RETURN CASE WHEN ambiguous_outcome
            THEN 'workflow.reconciliation-required'
            ELSE 'workflow.lease-window-exhausted'
        END;
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
        RETURN CASE WHEN ambiguous_outcome
            THEN 'workflow.reconciliation-required'
            ELSE 'workflow.deadline-exhausted'
        END;
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
        RETURN CASE WHEN ambiguous_outcome
            THEN 'workflow.reconciliation-required'
            ELSE 'workflow.dispatcher-ineligible'
        END;
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
        RETURN CASE WHEN ambiguous_outcome
            THEN 'workflow.reconciliation-required'
            ELSE 'workflow.authorization-revoked'
        END;
    END IF;

    RETURN CASE WHEN ambiguous_outcome
        THEN 'workflow.ambiguous-retry-allowed'
        ELSE 'workflow.preflight-allowed'
    END;
END
$$;
ALTER FUNCTION public.opsmind_preflight_investigation_workflow_start(
    uuid, uuid, uuid, bigint
) OWNER TO opsmind_context_resolver;

-- V002 already grants next_attempt_at and lease_expires_at. V012 adds only
-- fields required to reconstruct one leased envelope and advance its attempt
-- count. It intentionally never grants last_error update.
GRANT SELECT (
    causation_id, correlation_id, payload_bytes, payload_digest,
    lease_token, attempts, last_error
) ON outbox_events TO opsmind_dispatch_resolver;
GRANT UPDATE (attempts) ON outbox_events TO opsmind_dispatch_resolver;

CREATE OR REPLACE FUNCTION opsmind_list_investigation_workflow_start_tenants(
    p_limit integer
) RETURNS TABLE(organization_id uuid)
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
      JOIN public.investigation_workflow_bindings binding_row
        ON binding_row.organization_id = event_row.organization_id
       AND binding_row.run_id = event_row.aggregate_id
       AND binding_row.start_event_id = event_row.event_id
       AND binding_row.status = 'PENDING'
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
       AND event_row.last_error IS DISTINCT FROM 'workflow.reconciliation-required'
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

CREATE OR REPLACE FUNCTION opsmind_claim_investigation_workflow_start(
    p_organization_id uuid,
    p_lease_token uuid,
    p_lease_duration_ms bigint
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
    attempts integer
)
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
    IF p_organization_id IS NULL OR p_lease_token IS NULL THEN
        RAISE EXCEPTION 'workflow claim requires tenant and lease identity'
            USING ERRCODE = '22023';
    END IF;
    IF p_lease_duration_ms IS NULL
       OR p_lease_duration_ms < 5000
       OR p_lease_duration_ms > 300000 THEN
        RAISE EXCEPTION 'workflow claim lease duration is outside policy'
            USING ERRCODE = '22023';
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
        RETURN;
    END IF;

    RETURN QUERY
    WITH candidate AS (
        SELECT event_row.event_id
          FROM public.outbox_events event_row
          JOIN public.investigation_workflow_bindings binding_row
            ON binding_row.organization_id = event_row.organization_id
           AND binding_row.run_id = event_row.aggregate_id
           AND binding_row.start_event_id = event_row.event_id
           AND binding_row.status = 'PENDING'
         WHERE event_row.organization_id = p_organization_id
           AND event_row.event_type = 'investigation.workflow-start.requested'
           AND event_row.schema_version = '1'
           AND event_row.aggregate_type = 'investigation-workflow'
           AND event_row.aggregate_sequence = 1
           AND event_row.published_at IS NULL
           AND event_row.poisoned_at IS NULL
           AND event_row.last_error IS DISTINCT FROM 'workflow.reconciliation-required'
           AND event_row.next_attempt_at <= db_now
           AND (
               event_row.lease_expires_at IS NULL
               OR event_row.lease_expires_at <= db_now
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
         ORDER BY event_row.occurred_at, event_row.event_id
         FOR UPDATE OF event_row, binding_row SKIP LOCKED
         LIMIT 1
    ), claimed AS (
        UPDATE public.outbox_events event_row
           SET attempts = event_row.attempts + 1,
               lease_token = p_lease_token,
               lease_expires_at = db_now
                   + (p_lease_duration_ms * interval '1 millisecond')
          FROM candidate
         WHERE event_row.organization_id = p_organization_id
           AND event_row.event_id = candidate.event_id
        RETURNING event_row.event_id, event_row.organization_id,
                  event_row.aggregate_type, event_row.aggregate_id,
                  event_row.aggregate_sequence, event_row.event_type,
                  event_row.schema_version, event_row.causation_id,
                  event_row.correlation_id, event_row.occurred_at,
                  event_row.payload_bytes, event_row.payload_digest,
                  event_row.lease_token, event_row.lease_expires_at,
                  event_row.attempts
    )
    SELECT claimed.event_id, claimed.organization_id, claimed.aggregate_type,
           claimed.aggregate_id, claimed.aggregate_sequence, claimed.event_type,
           claimed.schema_version, claimed.causation_id, claimed.correlation_id,
           claimed.occurred_at, claimed.payload_bytes, claimed.payload_digest,
           claimed.lease_token, claimed.lease_expires_at, claimed.attempts
      FROM claimed;
END
$$;
ALTER FUNCTION public.opsmind_claim_investigation_workflow_start(
    uuid, uuid, bigint
) OWNER TO opsmind_dispatch_resolver;

REVOKE ALL ON FUNCTION public.opsmind_claim_investigation_workflow_start(
    uuid, uuid, bigint
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.opsmind_claim_investigation_workflow_start(
    uuid, uuid, bigint
) TO opsmind_dispatcher;

COMMENT ON FUNCTION public.opsmind_claim_investigation_workflow_start(uuid, uuid, bigint) IS
    'Claims at most one ready canonical workflow-start event with database time and preserves reconciliation markers.';
