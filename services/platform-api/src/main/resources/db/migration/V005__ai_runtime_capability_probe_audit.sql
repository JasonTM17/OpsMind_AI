-- Append-only lifecycle and usage metadata for synthetic provider capability probes.
-- This table intentionally has no tenant, prompt, evidence, credential, or response-body fields.

CREATE TABLE ai_runtime.provider_capability_probe_events (
    event_id            uuid PRIMARY KEY,
    probe_id            uuid NOT NULL,
    provider             varchar(64) NOT NULL,
    model_id            varchar(256) NOT NULL,
    provider_region     varchar(32) NOT NULL,
    event_type          varchar(16) NOT NULL,
    outcome             varchar(16),
    prompt_tokens       integer,
    completion_tokens   integer,
    total_tokens        integer,
    cost_usd            numeric(20, 8),
    error_code          varchar(128),
    occurred_at         timestamptz NOT NULL DEFAULT transaction_timestamp(),
    CHECK (length(trim(provider)) > 0),
    CHECK (length(trim(model_id)) > 0),
    CHECK (length(trim(provider_region)) > 0),
    CHECK (event_type IN ('started', 'finished')),
    CHECK (
        (event_type = 'started'
            AND outcome IS NULL
            AND prompt_tokens IS NULL
            AND completion_tokens IS NULL
            AND total_tokens IS NULL
            AND cost_usd IS NULL
            AND error_code IS NULL)
        OR
        (event_type = 'finished'
            AND outcome IN ('succeeded', 'failed')
            AND (
                (outcome = 'succeeded'
                    AND prompt_tokens IS NOT NULL
                    AND completion_tokens IS NOT NULL
                    AND total_tokens IS NOT NULL
                    AND cost_usd IS NOT NULL
                    AND error_code IS NULL)
                OR
                (outcome = 'failed'
                    AND prompt_tokens IS NULL
                    AND completion_tokens IS NULL
                    AND total_tokens IS NULL
                    AND cost_usd IS NULL
                    AND error_code IN (
                        'provider_capability_probe_failed',
                        'provider_capability_probe_cancelled'
                    ))
            ))
    ),
    CHECK (prompt_tokens IS NULL OR prompt_tokens >= 0),
    CHECK (completion_tokens IS NULL OR completion_tokens >= 0),
    CHECK (total_tokens IS NULL OR total_tokens = prompt_tokens + completion_tokens),
    CHECK (total_tokens IS NULL OR total_tokens <= 1024),
    CHECK (cost_usd IS NULL OR cost_usd >= 0)
);

CREATE UNIQUE INDEX provider_capability_probe_events_type_idx
    ON ai_runtime.provider_capability_probe_events (probe_id, event_type);
CREATE INDEX provider_capability_probe_events_started_quota_idx
    ON ai_runtime.provider_capability_probe_events (
        provider, model_id, provider_region, occurred_at
    )
    WHERE event_type = 'started';

GRANT INSERT ON ai_runtime.provider_capability_probe_events TO opsmind_ai_runtime;
GRANT SELECT (
    event_id, probe_id, provider, model_id, provider_region, event_type, occurred_at
) ON ai_runtime.provider_capability_probe_events TO opsmind_ai_runtime;
REVOKE UPDATE, DELETE, TRUNCATE ON ai_runtime.provider_capability_probe_events
    FROM opsmind_ai_runtime;

COMMENT ON TABLE ai_runtime.provider_capability_probe_events IS
    'Append-only provider probe lifecycle and bounded usage metadata; no incident or secret content.';
