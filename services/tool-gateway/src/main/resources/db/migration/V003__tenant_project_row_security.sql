-- Capability-derived tenant/project authority is bound to each runtime
-- transaction. Capability nonces remain a deliberately global replay control.

DO $$
BEGIN
    IF current_user IS DISTINCT FROM 'opsmind_tool_gateway_migrator'
       OR pg_get_userbyid(
           (SELECT nspowner FROM pg_namespace WHERE nspname = 'tool_gateway')
       ) IS DISTINCT FROM current_user THEN
        RAISE EXCEPTION 'Tool Gateway migration must run as the dedicated schema owner';
    END IF;
END;
$$;

CREATE FUNCTION tool_gateway.current_tenant_id()
RETURNS uuid
LANGUAGE sql
STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT CASE
        WHEN current_setting('opsmind.tool_gateway_tenant_id', true) ~
            '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$'
        THEN current_setting('opsmind.tool_gateway_tenant_id', true)::uuid
        ELSE NULL
    END
$$;

CREATE FUNCTION tool_gateway.current_project_id()
RETURNS uuid
LANGUAGE sql
STABLE
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT CASE
        WHEN current_setting('opsmind.tool_gateway_project_id', true) ~
            '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$'
        THEN current_setting('opsmind.tool_gateway_project_id', true)::uuid
        ELSE NULL
    END
$$;

CREATE FUNCTION tool_gateway.set_tenant_context(
    p_tenant_id uuid,
    p_project_id uuid
)
RETURNS void
LANGUAGE plpgsql
VOLATILE
SECURITY INVOKER
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF current_user IS DISTINCT FROM 'opsmind_tool_gateway' THEN
        RAISE EXCEPTION 'Tool Gateway tenant context requires the runtime role';
    END IF;
    IF p_tenant_id IS NULL OR p_project_id IS NULL THEN
        RAISE EXCEPTION 'Tool Gateway tenant and project context are required';
    END IF;

    PERFORM set_config(
        'opsmind.tool_gateway_tenant_id',
        p_tenant_id::text,
        true
    );
    PERFORM set_config(
        'opsmind.tool_gateway_project_id',
        p_project_id::text,
        true
    );
END;
$$;

REVOKE ALL ON FUNCTION tool_gateway.current_tenant_id() FROM PUBLIC;
REVOKE ALL ON FUNCTION tool_gateway.current_project_id() FROM PUBLIC;
REVOKE ALL ON FUNCTION tool_gateway.set_tenant_context(uuid, uuid) FROM PUBLIC;

ALTER TABLE tool_gateway.tool_audit_events
    ADD COLUMN tenant_id uuid,
    ADD COLUMN project_id uuid,
    ADD CONSTRAINT tool_audit_scope_pair CHECK (
        (tenant_id IS NULL AND project_id IS NULL)
        OR
        (tenant_id IS NOT NULL AND project_id IS NOT NULL)
    );

CREATE INDEX tool_audit_tenant_project_execution_time_idx
    ON tool_gateway.tool_audit_events (
        tenant_id,
        project_id,
        execution_id,
        recorded_at
    )
    WHERE tenant_id IS NOT NULL AND project_id IS NOT NULL;

-- Historical V001/V002 rows have no trustworthy scope. They remain immutable
-- and invisible to the runtime RLS policy rather than receiving invented
-- attribution.
COMMENT ON COLUMN tool_gateway.tool_audit_events.tenant_id IS
    'Verified capability tenant. NULL only for immutable legacy V001/V002 rows.';
COMMENT ON COLUMN tool_gateway.tool_audit_events.project_id IS
    'Verified capability project. NULL only for immutable legacy V001/V002 rows.';

CREATE TABLE tool_gateway.unverified_tool_audit_events (
    audit_event_id uuid PRIMARY KEY,
    execution_id uuid,
    outcome text NOT NULL,
    request_digest text NOT NULL,
    denial_code text NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT unverified_tool_audit_outcome
        CHECK (outcome IN ('DENIED', 'FAILED')),
    CONSTRAINT unverified_tool_audit_request_digest_format
        CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT unverified_tool_audit_denial_code_bound
        CHECK (length(denial_code) BETWEEN 1 AND 64)
);

CREATE INDEX unverified_tool_audit_execution_time_idx
    ON tool_gateway.unverified_tool_audit_events (execution_id, recorded_at);

CREATE TRIGGER unverified_tool_audit_events_append_only
    BEFORE UPDATE OR DELETE ON tool_gateway.unverified_tool_audit_events
    FOR EACH ROW EXECUTE FUNCTION tool_gateway.reject_tool_audit_mutation();

CREATE TRIGGER unverified_tool_audit_events_reject_truncate
    BEFORE TRUNCATE ON tool_gateway.unverified_tool_audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION tool_gateway.reject_tool_audit_mutation();

COMMENT ON TABLE tool_gateway.unverified_tool_audit_events IS
    'Insert-only security decisions made before capability scope is trusted; request tenant/project values are never stored.';

CREATE POLICY execution_receipts_tenant_project_isolation
    ON tool_gateway.execution_receipts
    USING (
        tenant_id = tool_gateway.current_tenant_id()
        AND project_id = tool_gateway.current_project_id()
    )
    WITH CHECK (
        tenant_id = tool_gateway.current_tenant_id()
        AND project_id = tool_gateway.current_project_id()
    );

CREATE POLICY tool_audit_events_tenant_project_isolation
    ON tool_gateway.tool_audit_events
    USING (
        tenant_id = tool_gateway.current_tenant_id()
        AND project_id = tool_gateway.current_project_id()
    )
    WITH CHECK (
        tenant_id = tool_gateway.current_tenant_id()
        AND project_id = tool_gateway.current_project_id()
    );

ALTER TABLE tool_gateway.execution_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE tool_gateway.execution_receipts FORCE ROW LEVEL SECURITY;
ALTER TABLE tool_gateway.tool_audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE tool_gateway.tool_audit_events FORCE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE tool_gateway.unverified_tool_audit_events FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'opsmind_tool_gateway') THEN
        GRANT EXECUTE ON FUNCTION tool_gateway.current_tenant_id()
            TO opsmind_tool_gateway;
        GRANT EXECUTE ON FUNCTION tool_gateway.current_project_id()
            TO opsmind_tool_gateway;
        GRANT EXECUTE ON FUNCTION tool_gateway.set_tenant_context(uuid, uuid)
            TO opsmind_tool_gateway;
        GRANT INSERT ON tool_gateway.unverified_tool_audit_events
            TO opsmind_tool_gateway;
        REVOKE SELECT, UPDATE, DELETE, TRUNCATE
            ON tool_gateway.unverified_tool_audit_events
            FROM opsmind_tool_gateway;
    END IF;
END;
$$;

COMMENT ON FUNCTION tool_gateway.current_tenant_id() IS
    'Fail-closed transaction-local tenant context for Tool Gateway RLS.';
COMMENT ON FUNCTION tool_gateway.current_project_id() IS
    'Fail-closed transaction-local project context for Tool Gateway RLS.';
COMMENT ON FUNCTION tool_gateway.set_tenant_context(uuid, uuid) IS
    'Binds verified capability scope to the current runtime transaction only.';
COMMENT ON TABLE tool_gateway.capability_nonce_claims IS
    'Global one-use delegated-capability replay control; intentionally not tenant-scoped.';
