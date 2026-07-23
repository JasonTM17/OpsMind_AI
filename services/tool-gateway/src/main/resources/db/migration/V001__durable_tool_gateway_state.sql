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

REVOKE ALL ON SCHEMA tool_gateway FROM PUBLIC;

CREATE TABLE tool_gateway.capability_nonce_claims (
    nonce_hash bytea PRIMARY KEY,
    expires_at timestamptz NOT NULL,
    claimed_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT capability_nonce_hash_length CHECK (octet_length(nonce_hash) = 32),
    CONSTRAINT capability_nonce_expiry_order CHECK (expires_at > claimed_at)
);

CREATE INDEX capability_nonce_expiry_idx
    ON tool_gateway.capability_nonce_claims (expires_at);

CREATE TABLE tool_gateway.execution_receipts (
    execution_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    project_id uuid NOT NULL,
    incident_id uuid NOT NULL,
    run_id uuid NOT NULL,
    request_digest text NOT NULL,
    status text NOT NULL,
    lease_token uuid,
    lease_expires_at timestamptz,
    response_json jsonb,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    completed_at timestamptz,
    CONSTRAINT execution_receipt_digest_format
        CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT execution_receipt_status
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT execution_receipt_state CHECK (
        (status = 'IN_PROGRESS' AND lease_token IS NOT NULL
            AND lease_expires_at IS NOT NULL AND response_json IS NULL
            AND completed_at IS NULL)
        OR
        (status = 'COMPLETED' AND lease_token IS NULL
            AND lease_expires_at IS NULL AND response_json IS NOT NULL
            AND completed_at IS NOT NULL)
    ),
    CONSTRAINT execution_receipt_response_bound
        CHECK (response_json IS NULL OR pg_column_size(response_json) <= 131072)
);

CREATE INDEX execution_receipt_lease_idx
    ON tool_gateway.execution_receipts (lease_expires_at)
    WHERE status = 'IN_PROGRESS';

CREATE TABLE tool_gateway.tool_audit_events (
    audit_event_id uuid PRIMARY KEY,
    execution_id uuid,
    outcome text NOT NULL,
    request_digest text NOT NULL,
    capability_id text,
    manifest_version text,
    result_digest text,
    policy_version text,
    denial_code text,
    recorded_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT tool_audit_outcome
        CHECK (outcome IN ('SUCCEEDED', 'DENIED', 'DUPLICATE', 'FAILED')),
    CONSTRAINT tool_audit_request_digest_format
        CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT tool_audit_result_digest_format
        CHECK (result_digest IS NULL OR result_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT tool_audit_capability_bound
        CHECK (capability_id IS NULL OR length(capability_id) BETWEEN 1 AND 128),
    CONSTRAINT tool_audit_metadata_bounds CHECK (
        (manifest_version IS NULL OR length(manifest_version) BETWEEN 1 AND 128)
        AND (policy_version IS NULL OR length(policy_version) BETWEEN 1 AND 64)
        AND (denial_code IS NULL OR length(denial_code) BETWEEN 1 AND 64)
    ),
    CONSTRAINT tool_audit_decision_shape CHECK (
        (
            outcome IN ('SUCCEEDED', 'DUPLICATE')
            AND result_digest IS NOT NULL
            AND denial_code IS NULL
        )
        OR
        (
            outcome IN ('DENIED', 'FAILED')
            AND result_digest IS NULL
            AND denial_code IS NOT NULL
        )
    )
);

CREATE INDEX tool_audit_execution_time_idx
    ON tool_gateway.tool_audit_events (execution_id, recorded_at);

CREATE FUNCTION tool_gateway.reject_tool_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'tool audit events are append-only';
END;
$$;

CREATE TRIGGER tool_audit_events_append_only
    BEFORE UPDATE OR DELETE ON tool_gateway.tool_audit_events
    FOR EACH ROW EXECUTE FUNCTION tool_gateway.reject_tool_audit_mutation();

CREATE TRIGGER tool_audit_events_reject_truncate
    BEFORE TRUNCATE ON tool_gateway.tool_audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION tool_gateway.reject_tool_audit_mutation();

REVOKE ALL ON ALL TABLES IN SCHEMA tool_gateway FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA tool_gateway FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'opsmind_tool_gateway') THEN
        GRANT USAGE ON SCHEMA tool_gateway TO opsmind_tool_gateway;
        GRANT SELECT, INSERT, DELETE
            ON tool_gateway.capability_nonce_claims TO opsmind_tool_gateway;
        GRANT SELECT, INSERT, UPDATE
            ON tool_gateway.execution_receipts TO opsmind_tool_gateway;
        GRANT INSERT ON tool_gateway.tool_audit_events TO opsmind_tool_gateway;
    END IF;
END;
$$;
