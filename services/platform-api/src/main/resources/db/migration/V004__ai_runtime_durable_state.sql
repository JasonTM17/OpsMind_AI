-- Durable, tenant-scoped AI runtime replay and accounting state.
-- Raw prompts, capabilities, credentials, provider reasoning, and provider
-- error bodies are deliberately absent from this schema.

DO $$
DECLARE
    runtime_super boolean;
    runtime_bypass boolean;
    runtime_login boolean;
    runtime_inherit boolean;
BEGIN
    SELECT rolsuper, rolbypassrls, rolcanlogin, rolinherit
      INTO runtime_super, runtime_bypass, runtime_login, runtime_inherit
      FROM pg_roles
     WHERE rolname = 'opsmind_ai_runtime';
    IF runtime_super IS NULL THEN
        RAISE EXCEPTION 'required non-owner runtime role opsmind_ai_runtime is missing';
    END IF;
    IF runtime_super IS DISTINCT FROM false
       OR runtime_bypass IS DISTINCT FROM false
       OR runtime_login IS DISTINCT FROM true
       OR runtime_inherit IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'runtime role opsmind_ai_runtime has unsafe attributes';
    END IF;
END
$$;

CREATE SCHEMA ai_runtime;
REVOKE ALL ON SCHEMA ai_runtime FROM PUBLIC;

CREATE FUNCTION ai_runtime.current_tenant_id() RETURNS uuid
LANGUAGE sql STABLE
SET search_path = pg_catalog, ai_runtime, pg_temp AS $$
    SELECT CASE
        WHEN current_setting('opsmind.ai_runtime_tenant_id', true) ~
            '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$'
        THEN current_setting('opsmind.ai_runtime_tenant_id', true)::uuid
        ELSE NULL
    END
$$;

CREATE TABLE ai_runtime.capability_nonces (
    nonce_digest        varchar(71) PRIMARY KEY,
    organization_id     uuid NOT NULL,
    run_id              uuid NOT NULL,
    request_digest      varchar(71) NOT NULL,
    consumed_at         timestamptz NOT NULL DEFAULT transaction_timestamp(),
    expires_at          timestamptz NOT NULL,
    CHECK (nonce_digest ~ '^sha256:[0-9a-f]{64}$'),
    CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    CHECK (expires_at > consumed_at)
);

CREATE TABLE ai_runtime.analysis_run_budgets (
    organization_id         uuid NOT NULL,
    incident_id             uuid NOT NULL,
    run_id                  uuid NOT NULL,
    token_limit             integer NOT NULL CHECK (token_limit > 0),
    tool_limit              integer NOT NULL CHECK (tool_limit >= 0),
    cost_limit_usd          numeric(20, 8) NOT NULL CHECK (cost_limit_usd > 0),
    committed_tokens        integer NOT NULL DEFAULT 0 CHECK (committed_tokens >= 0),
    committed_tools         integer NOT NULL DEFAULT 0 CHECK (committed_tools >= 0),
    committed_cost_usd      numeric(20, 8) NOT NULL DEFAULT 0
        CHECK (committed_cost_usd >= 0),
    active_invocation_id    uuid,
    active_reserved_tokens  integer CHECK (active_reserved_tokens > 0),
    active_reserved_cost_usd numeric(20, 8) CHECK (active_reserved_cost_usd >= 0),
    active_lease_expires_at timestamptz,
    created_at              timestamptz NOT NULL DEFAULT transaction_timestamp(),
    updated_at              timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (organization_id, run_id),
    CHECK (committed_tokens <= token_limit),
    CHECK (committed_tools <= tool_limit),
    CHECK (committed_cost_usd <= cost_limit_usd),
    CHECK (
        (active_invocation_id IS NULL
            AND active_reserved_tokens IS NULL
            AND active_reserved_cost_usd IS NULL
            AND active_lease_expires_at IS NULL)
        OR
        (active_invocation_id IS NOT NULL
            AND active_reserved_tokens IS NOT NULL
            AND active_reserved_cost_usd IS NOT NULL
            AND active_lease_expires_at IS NOT NULL)
    )
);

CREATE TABLE ai_runtime.analysis_invocations (
    invocation_id            uuid PRIMARY KEY,
    organization_id          uuid NOT NULL,
    incident_id              uuid NOT NULL,
    run_id                   uuid NOT NULL,
    capability_nonce_digest  varchar(71) NOT NULL UNIQUE
        REFERENCES ai_runtime.capability_nonces(nonce_digest),
    request_digest           varchar(71) NOT NULL,
    provider                 varchar(64) NOT NULL,
    model_id                 varchar(160) NOT NULL,
    prompt_version           varchar(128) NOT NULL,
    schema_version           varchar(128) NOT NULL,
    state                    varchar(24) NOT NULL,
    response_status          varchar(32),
    response_payload         jsonb,
    provider_error_code      varchar(128),
    reserved_tokens          integer NOT NULL CHECK (reserved_tokens > 0),
    reserved_cost_usd        numeric(20, 8) NOT NULL CHECK (reserved_cost_usd >= 0),
    actual_tokens            integer CHECK (actual_tokens >= 0),
    actual_tools             integer CHECK (actual_tools >= 0),
    actual_cost_usd          numeric(20, 8) CHECK (actual_cost_usd >= 0),
    latency_ms               integer CHECK (latency_ms >= 0),
    request_deadline_at      timestamptz NOT NULL,
    lease_expires_at         timestamptz NOT NULL,
    started_at               timestamptz NOT NULL DEFAULT transaction_timestamp(),
    finished_at              timestamptz,
    retain_until             timestamptz NOT NULL,
    FOREIGN KEY (organization_id, run_id)
        REFERENCES ai_runtime.analysis_run_budgets(organization_id, run_id),
    CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    CHECK (length(trim(provider)) > 0),
    CHECK (length(trim(model_id)) > 0),
    CHECK (length(trim(prompt_version)) > 0),
    CHECK (length(trim(schema_version)) > 0),
    CHECK (state IN ('reserved', 'succeeded', 'failed', 'ambiguous')),
    CHECK (lease_expires_at >= request_deadline_at),
    CHECK (retain_until > started_at),
    CHECK (response_payload IS NULL OR (
        jsonb_typeof(response_payload) = 'object'
        AND pg_column_size(response_payload) <= 1048576
    )),
    CHECK (
        (state = 'reserved'
            AND finished_at IS NULL
            AND response_status IS NULL
            AND response_payload IS NULL
            AND provider_error_code IS NULL
            AND actual_tokens IS NULL
            AND actual_tools IS NULL
            AND actual_cost_usd IS NULL)
        OR
        (state = 'succeeded'
            AND finished_at IS NOT NULL
            AND response_status IS NOT NULL
            AND response_payload IS NOT NULL
            AND provider_error_code IS NULL
            AND actual_tokens IS NOT NULL
            AND actual_tools IS NOT NULL
            AND actual_cost_usd IS NOT NULL)
        OR
        (state IN ('failed', 'ambiguous')
            AND finished_at IS NOT NULL
            AND response_status IS NULL
            AND response_payload IS NULL
            AND provider_error_code IS NOT NULL)
    )
);

CREATE UNIQUE INDEX analysis_invocations_success_replay_idx
    ON ai_runtime.analysis_invocations (organization_id, run_id, request_digest)
    WHERE state = 'succeeded';
CREATE INDEX analysis_invocations_run_history_idx
    ON ai_runtime.analysis_invocations (organization_id, run_id, started_at DESC);
CREATE INDEX analysis_invocations_retention_idx
    ON ai_runtime.analysis_invocations (retain_until, invocation_id);
CREATE INDEX capability_nonces_expiry_idx
    ON ai_runtime.capability_nonces (expires_at, nonce_digest);

ALTER TABLE ai_runtime.capability_nonces ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_runtime.capability_nonces FORCE ROW LEVEL SECURITY;
ALTER TABLE ai_runtime.analysis_run_budgets ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_runtime.analysis_run_budgets FORCE ROW LEVEL SECURITY;
ALTER TABLE ai_runtime.analysis_invocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_runtime.analysis_invocations FORCE ROW LEVEL SECURITY;

CREATE POLICY capability_nonces_tenant_isolation
    ON ai_runtime.capability_nonces
    USING (organization_id = ai_runtime.current_tenant_id())
    WITH CHECK (organization_id = ai_runtime.current_tenant_id());
CREATE POLICY analysis_run_budgets_tenant_isolation
    ON ai_runtime.analysis_run_budgets
    USING (organization_id = ai_runtime.current_tenant_id())
    WITH CHECK (organization_id = ai_runtime.current_tenant_id());
CREATE POLICY analysis_invocations_tenant_isolation
    ON ai_runtime.analysis_invocations
    USING (organization_id = ai_runtime.current_tenant_id())
    WITH CHECK (organization_id = ai_runtime.current_tenant_id());

DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO opsmind_ai_runtime', current_database());
END
$$;
GRANT USAGE ON SCHEMA ai_runtime TO opsmind_ai_runtime;
GRANT EXECUTE ON FUNCTION ai_runtime.current_tenant_id() TO opsmind_ai_runtime;
GRANT SELECT, INSERT ON ai_runtime.capability_nonces TO opsmind_ai_runtime;
GRANT SELECT, INSERT ON ai_runtime.analysis_run_budgets TO opsmind_ai_runtime;
GRANT UPDATE (
    committed_tokens, committed_tools, committed_cost_usd,
    active_invocation_id, active_reserved_tokens, active_reserved_cost_usd,
    active_lease_expires_at, updated_at
) ON ai_runtime.analysis_run_budgets TO opsmind_ai_runtime;
GRANT SELECT, INSERT ON ai_runtime.analysis_invocations TO opsmind_ai_runtime;
GRANT UPDATE (
    state, response_status, response_payload, provider_error_code,
    actual_tokens, actual_tools, actual_cost_usd, latency_ms, finished_at
) ON ai_runtime.analysis_invocations TO opsmind_ai_runtime;

REVOKE ALL ON FUNCTION ai_runtime.current_tenant_id() FROM PUBLIC;
REVOKE UPDATE, DELETE, TRUNCATE ON ai_runtime.capability_nonces FROM opsmind_ai_runtime;
REVOKE DELETE, TRUNCATE ON ai_runtime.analysis_run_budgets FROM opsmind_ai_runtime;
REVOKE DELETE, TRUNCATE ON ai_runtime.analysis_invocations FROM opsmind_ai_runtime;

COMMENT ON SCHEMA ai_runtime IS
    'Tenant-scoped AI invocation replay and cumulative budget accounting.';
COMMENT ON TABLE ai_runtime.capability_nonces IS
    'One-way capability nonce digests; bearer material is never persisted.';
COMMENT ON TABLE ai_runtime.analysis_run_budgets IS
    'Authoritative cumulative run allowance and single active provider reservation.';
COMMENT ON TABLE ai_runtime.analysis_invocations IS
    'Retention-bounded provider invocation metadata and validated normalized successes.';
COMMENT ON COLUMN ai_runtime.analysis_invocations.response_payload IS
    'Application-schema-validated normalized response; never raw provider reasoning or prompt content.';
