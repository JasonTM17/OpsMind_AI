-- Expand phase: response-aware writers bind normalized analysis to immutable
-- events, while the legacy V007 shape remains writable during rolling deploy.
-- Existing rows remain unchanged. A later contract migration may require the
-- response only after every legacy writer has been drained.

CREATE OR REPLACE FUNCTION opsmind_json_number_between(
    document jsonb,
    minimum_value numeric,
    maximum_value numeric
) RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
STRICT
PARALLEL SAFE
SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF jsonb_typeof(document) IS DISTINCT FROM 'number' THEN
        RETURN false;
    END IF;
    RETURN (document::text)::numeric BETWEEN minimum_value AND maximum_value;
END
$$;

CREATE OR REPLACE FUNCTION opsmind_json_nonnegative_integer(document jsonb)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
STRICT
PARALLEL SAFE
SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
    IF jsonb_typeof(document) IS DISTINCT FROM 'number'
       OR document::text !~ '^(0|[1-9][0-9]*)$' THEN
        RETURN false;
    END IF;
    RETURN (document::text)::numeric <= 2147483647;
END
$$;

CREATE OR REPLACE FUNCTION opsmind_valid_accepted_analysis_response(
    document jsonb,
    expected_run_id uuid,
    expected_status text
) RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
STRICT
PARALLEL SAFE
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    item jsonb;
    nested_item jsonb;
    prompt_tokens numeric;
    completion_tokens numeric;
    total_tokens numeric;
BEGIN
    IF expected_status <> ALL (ARRAY[
           'complete', 'need_more_evidence', 'abstain',
           'provider_unavailable', 'budget_exceeded'
       ])
       OR public.opsmind_json_object_has_exact_keys(document, ARRAY[
           'status', 'run_id', 'model_id', 'prompt_version', 'schema_version',
           'hypotheses', 'counter_evidence', 'missing_evidence', 'citations',
           'confidence', 'usage', 'cost_estimate', 'requested_tool_calls'
       ]) IS NOT TRUE
       OR jsonb_typeof(document -> 'status') IS DISTINCT FROM 'string'
       OR document ->> 'status' IS DISTINCT FROM expected_status
       OR jsonb_typeof(document -> 'run_id') IS DISTINCT FROM 'string'
       OR document ->> 'run_id' IS DISTINCT FROM expected_run_id::text
       OR jsonb_typeof(document -> 'model_id') IS DISTINCT FROM 'string'
       OR length(document ->> 'model_id') NOT BETWEEN 1 AND 256
       OR jsonb_typeof(document -> 'prompt_version') IS DISTINCT FROM 'string'
       OR length(document ->> 'prompt_version') NOT BETWEEN 1 AND 256
       OR jsonb_typeof(document -> 'schema_version') IS DISTINCT FROM 'string'
       OR document ->> 'schema_version' IS DISTINCT FROM 'analysis-v1'
       OR jsonb_typeof(document -> 'hypotheses') IS DISTINCT FROM 'array'
       OR jsonb_typeof(document -> 'counter_evidence') IS DISTINCT FROM 'array'
       OR jsonb_typeof(document -> 'missing_evidence') IS DISTINCT FROM 'array'
       OR jsonb_typeof(document -> 'citations') IS DISTINCT FROM 'array'
       OR jsonb_typeof(document -> 'requested_tool_calls') IS DISTINCT FROM 'array' THEN
        RETURN false;
    END IF;
    IF jsonb_array_length(document -> 'hypotheses') > 20
       OR jsonb_array_length(document -> 'counter_evidence') > 100
       OR jsonb_array_length(document -> 'missing_evidence') > 100
       OR jsonb_array_length(document -> 'citations') > 100
       OR jsonb_array_length(document -> 'requested_tool_calls') > 20 THEN
        RETURN false;
    END IF;
    IF public.opsmind_json_number_between(
           document -> 'confidence', 0, 1
       ) IS NOT TRUE THEN
        RETURN false;
    END IF;

    FOREACH item IN ARRAY ARRAY[
        document -> 'counter_evidence',
        document -> 'missing_evidence'
    ] LOOP
        FOR nested_item IN SELECT value FROM jsonb_array_elements(item) LOOP
            IF jsonb_typeof(nested_item) IS DISTINCT FROM 'string'
               OR length(nested_item #>> '{}') NOT BETWEEN 1 AND 1024 THEN
                RETURN false;
            END IF;
        END LOOP;
    END LOOP;

    FOR item IN SELECT value FROM jsonb_array_elements(document -> 'citations') LOOP
        IF public.opsmind_json_object_has_exact_keys(
               item, ARRAY['evidence_id', 'digest', 'claim']
           ) IS NOT TRUE
           OR jsonb_typeof(item -> 'evidence_id') IS DISTINCT FROM 'string'
           OR item ->> 'evidence_id'
                !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
           OR jsonb_typeof(item -> 'digest') IS DISTINCT FROM 'string'
           OR item ->> 'digest' !~ '^sha256:[0-9a-f]{64}$'
           OR jsonb_typeof(item -> 'claim') IS DISTINCT FROM 'string'
           OR length(item ->> 'claim') NOT BETWEEN 1 AND 1024 THEN
            RETURN false;
        END IF;
    END LOOP;

    FOR item IN SELECT value FROM jsonb_array_elements(document -> 'hypotheses') LOOP
        IF public.opsmind_json_object_has_exact_keys(
               item, ARRAY['title', 'explanation', 'confidence', 'citations']
           ) IS NOT TRUE
           OR jsonb_typeof(item -> 'title') IS DISTINCT FROM 'string'
           OR length(item ->> 'title') NOT BETWEEN 1 AND 256
           OR jsonb_typeof(item -> 'explanation') IS DISTINCT FROM 'string'
           OR length(item ->> 'explanation') NOT BETWEEN 1 AND 4096
           OR public.opsmind_json_number_between(
               item -> 'confidence', 0, 1
           ) IS NOT TRUE
           OR jsonb_typeof(item -> 'citations') IS DISTINCT FROM 'array' THEN
            RETURN false;
        END IF;
        IF jsonb_array_length(item -> 'citations') > 50
           OR (
               expected_status = 'complete'
               AND jsonb_array_length(item -> 'citations') = 0
           ) THEN
            RETURN false;
        END IF;
        FOR nested_item IN SELECT value FROM jsonb_array_elements(item -> 'citations') LOOP
            IF public.opsmind_json_object_has_exact_keys(
                   nested_item, ARRAY['evidence_id', 'digest', 'claim']
               ) IS NOT TRUE
               OR jsonb_typeof(nested_item -> 'evidence_id') IS DISTINCT FROM 'string'
               OR nested_item ->> 'evidence_id'
                    !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
               OR jsonb_typeof(nested_item -> 'digest') IS DISTINCT FROM 'string'
               OR nested_item ->> 'digest' !~ '^sha256:[0-9a-f]{64}$'
               OR jsonb_typeof(nested_item -> 'claim') IS DISTINCT FROM 'string'
               OR length(nested_item ->> 'claim') NOT BETWEEN 1 AND 1024
               OR (
                   expected_status = 'complete'
                   AND NOT (document -> 'citations' @> jsonb_build_array(nested_item))
               ) THEN
                RETURN false;
            END IF;
        END LOOP;
    END LOOP;

    IF public.opsmind_json_object_has_exact_keys(
           document -> 'usage',
           ARRAY['prompt_tokens', 'completion_tokens', 'total_tokens']
       ) IS NOT TRUE
       OR public.opsmind_json_nonnegative_integer(
           document -> 'usage' -> 'prompt_tokens'
       ) IS NOT TRUE
       OR public.opsmind_json_nonnegative_integer(
           document -> 'usage' -> 'completion_tokens'
       ) IS NOT TRUE
       OR public.opsmind_json_nonnegative_integer(
           document -> 'usage' -> 'total_tokens'
       ) IS NOT TRUE THEN
        RETURN false;
    END IF;
    prompt_tokens := (document -> 'usage' -> 'prompt_tokens')::text::numeric;
    completion_tokens := (document -> 'usage' -> 'completion_tokens')::text::numeric;
    total_tokens := (document -> 'usage' -> 'total_tokens')::text::numeric;
    IF total_tokens IS DISTINCT FROM prompt_tokens + completion_tokens THEN
        RETURN false;
    END IF;

    IF public.opsmind_json_object_has_exact_keys(
           document -> 'cost_estimate', ARRAY['currency', 'amount']
       ) IS NOT TRUE
       OR jsonb_typeof(document -> 'cost_estimate' -> 'currency')
            IS DISTINCT FROM 'string'
       OR document -> 'cost_estimate' ->> 'currency' IS DISTINCT FROM 'USD'
       OR public.opsmind_json_number_between(
           document -> 'cost_estimate' -> 'amount', 0, 1000000
       ) IS NOT TRUE THEN
        RETURN false;
    END IF;

    FOR item IN
        SELECT value FROM jsonb_array_elements(document -> 'requested_tool_calls')
    LOOP
        IF public.opsmind_json_object_has_exact_keys(
               item,
               ARRAY['intent_id', 'connector', 'operation', 'arguments_digest', 'rationale']
           ) IS NOT TRUE
           OR jsonb_typeof(item -> 'intent_id') IS DISTINCT FROM 'string'
           OR item ->> 'intent_id'
                !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
           OR jsonb_typeof(item -> 'connector') IS DISTINCT FROM 'string'
           OR item ->> 'connector' NOT IN (
               'metrics', 'logs', 'traces', 'changes', 'runbooks'
           )
           OR jsonb_typeof(item -> 'operation') IS DISTINCT FROM 'string'
           OR length(item ->> 'operation') NOT BETWEEN 1 AND 256
           OR jsonb_typeof(item -> 'arguments_digest') IS DISTINCT FROM 'string'
           OR item ->> 'arguments_digest' !~ '^sha256:[0-9a-f]{64}$'
           OR jsonb_typeof(item -> 'rationale') IS DISTINCT FROM 'string'
           OR length(item ->> 'rationale') NOT BETWEEN 1 AND 1024 THEN
            RETURN false;
        END IF;
    END LOOP;

    IF expected_status = 'complete' THEN
        RETURN jsonb_array_length(document -> 'hypotheses') BETWEEN 1 AND 20
           AND jsonb_array_length(document -> 'citations') BETWEEN 1 AND 100
           AND jsonb_array_length(document -> 'requested_tool_calls') = 0;
    ELSIF expected_status = 'need_more_evidence' THEN
        RETURN jsonb_array_length(document -> 'requested_tool_calls') > 0
            OR jsonb_array_length(document -> 'missing_evidence') > 0;
    ELSIF expected_status = 'abstain' THEN
        RETURN jsonb_array_length(document -> 'hypotheses') = 0
           AND jsonb_array_length(document -> 'citations') = 0
           AND jsonb_array_length(document -> 'requested_tool_calls') = 0
           AND jsonb_array_length(document -> 'missing_evidence') > 0;
    END IF;
    RETURN jsonb_array_length(document -> 'hypotheses') = 0
       AND jsonb_array_length(document -> 'citations') = 0
       AND jsonb_array_length(document -> 'requested_tool_calls') = 0;
END
$$;

CREATE OR REPLACE FUNCTION opsmind_validate_investigation_event_append() RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    run_row record;
    prior_sequence bigint;
    expected_sequence bigint;
    details jsonb;
    actor_id uuid := public.opsmind_current_actor_id();
BEGIN
    IF session_user = 'opsmind_app' AND (
        NEW.organization_id IS DISTINCT FROM public.opsmind_current_tenant_id()
        OR NEW.actor_id IS DISTINCT FROM actor_id
    ) THEN
        RAISE EXCEPTION 'investigation event append requires the bound tenant and actor'
            USING ERRCODE = '42501';
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(
        NEW.organization_id::text || ':' || NEW.run_id::text,
        0
    ));
    SELECT stored.organization_id, stored.project_id, stored.incident_id,
           stored.actor_id, stored.status, stored.event_count, stored.rounds,
           stored.total_tokens, stored.max_rounds, stored.max_tool_calls,
           stored.max_evidence_items, stored.max_tokens,
           stored.pending_intents_state, stored.evidence_ids_state,
           stored.final_response, stored.terminal_reason,
           stored.started_at, stored.ended_at
      INTO run_row
      FROM public.investigation_runs stored
     WHERE stored.run_id = NEW.run_id
       AND stored.organization_id = NEW.organization_id
     FOR UPDATE;
    IF NOT FOUND
       OR NEW.project_id IS DISTINCT FROM run_row.project_id
       OR NEW.incident_id IS DISTINCT FROM run_row.incident_id
       OR NEW.actor_id IS DISTINCT FROM run_row.actor_id THEN
        RAISE EXCEPTION 'investigation event identities must match the authoritative run'
            USING ERRCODE = 'P7004';
    END IF;

    SELECT max(stored.sequence_no)
      INTO prior_sequence
      FROM public.investigation_run_events stored
     WHERE stored.organization_id = NEW.organization_id
       AND stored.run_id = NEW.run_id;
    expected_sequence := coalesce(prior_sequence, 0) + 1;
    IF NEW.sequence_no IS DISTINCT FROM expected_sequence
       OR NEW.sequence_no > run_row.event_count THEN
        RAISE EXCEPTION 'investigation event sequence must be contiguous and covered by the snapshot'
            USING ERRCODE = 'P7003';
    END IF;
    IF (NEW.sequence_no = 1 AND NEW.event_type <> 'RUN_STARTED')
       OR (NEW.sequence_no > 1 AND NEW.event_type = 'RUN_STARTED') THEN
        RAISE EXCEPTION 'only the first investigation event may start the run'
            USING ERRCODE = 'P7004';
    END IF;

    IF NOT NEW.payload ?& ARRAY[
           'eventId', 'organizationId', 'projectId', 'incidentId', 'runId',
           'sequenceNo', 'eventType', 'actorId', 'occurredAt', 'details'
       ]
       OR NEW.payload - ARRAY[
           'eventId', 'organizationId', 'projectId', 'incidentId', 'runId',
           'sequenceNo', 'eventType', 'actorId', 'occurredAt', 'details'
       ] <> '{}'::jsonb
       OR jsonb_typeof(NEW.payload -> 'sequenceNo') <> 'number'
       OR jsonb_typeof(NEW.payload -> 'occurredAt') <> 'string'
       OR jsonb_typeof(NEW.payload -> 'details') <> 'object'
       OR jsonb_typeof(NEW.payload -> 'details' -> 'occurredAt') <> 'string'
       OR NEW.payload ->> 'eventId' IS DISTINCT FROM NEW.event_id::text
       OR NEW.payload ->> 'organizationId' IS DISTINCT FROM NEW.organization_id::text
       OR NEW.payload ->> 'projectId' IS DISTINCT FROM NEW.project_id::text
       OR NEW.payload ->> 'incidentId' IS DISTINCT FROM NEW.incident_id::text
       OR NEW.payload ->> 'runId' IS DISTINCT FROM NEW.run_id::text
       OR (NEW.payload ->> 'sequenceNo')::bigint IS DISTINCT FROM NEW.sequence_no
       OR NEW.payload ->> 'eventType' IS DISTINCT FROM NEW.event_type
       OR NEW.payload ->> 'actorId' IS DISTINCT FROM NEW.actor_id::text
       OR (NEW.payload ->> 'occurredAt')::timestamptz IS DISTINCT FROM NEW.occurred_at
       OR NEW.payload -> 'details' ->> 'runId' IS DISTINCT FROM NEW.run_id::text
       OR (NEW.payload -> 'details' ->> 'occurredAt')::timestamptz
            IS DISTINCT FROM NEW.occurred_at THEN
        RAISE EXCEPTION 'investigation event payload does not match its authoritative metadata'
            USING ERRCODE = 'P7005';
    END IF;

    details := NEW.payload -> 'details';
    CASE NEW.event_type
        WHEN 'RUN_STARTED' THEN
            IF (
                public.opsmind_json_object_has_exact_keys(
                    details, ARRAY['runId', 'incidentId', 'budget', 'occurredAt']
                )
                AND public.opsmind_json_object_has_exact_keys(
                    details -> 'budget',
                    ARRAY['maxRounds', 'maxToolCalls', 'maxEvidenceItems', 'maxTokens']
                )
                AND details ->> 'incidentId' = run_row.incident_id::text
                AND jsonb_typeof(details -> 'budget' -> 'maxRounds') = 'number'
                AND details -> 'budget' ->> 'maxRounds' = run_row.max_rounds::text
                AND jsonb_typeof(details -> 'budget' -> 'maxToolCalls') = 'number'
                AND details -> 'budget' ->> 'maxToolCalls' = run_row.max_tool_calls::text
                AND jsonb_typeof(details -> 'budget' -> 'maxEvidenceItems') = 'number'
                AND details -> 'budget' ->> 'maxEvidenceItems' = run_row.max_evidence_items::text
                AND jsonb_typeof(details -> 'budget' -> 'maxTokens') = 'number'
                AND details -> 'budget' ->> 'maxTokens' = run_row.max_tokens::text
                AND run_row.status = 'CREATED'
                AND run_row.event_count = 1
                AND run_row.started_at = NEW.occurred_at
            ) IS NOT TRUE THEN
                RAISE EXCEPTION 'run-started event does not match its reducer snapshot'
                    USING ERRCODE = 'P7005';
            END IF;
        WHEN 'ANALYSIS_ACCEPTED' THEN
            IF (
                (
                    public.opsmind_json_object_has_exact_keys(
                        details,
                        ARRAY['runId', 'status', 'round', 'totalTokens', 'occurredAt']
                    )
                    OR (
                        public.opsmind_json_object_has_exact_keys(
                            details,
                            ARRAY[
                                'runId', 'status', 'round', 'totalTokens',
                                'response', 'occurredAt'
                            ]
                        )
                        AND public.opsmind_valid_accepted_analysis_response(
                            details -> 'response', NEW.run_id, details ->> 'status'
                        )
                        AND (
                            run_row.status <> 'COMPLETED'
                            OR details -> 'response' = run_row.final_response
                        )
                    )
                )
                AND details ->> 'status' IN (
                    'complete', 'need_more_evidence', 'abstain',
                    'provider_unavailable', 'budget_exceeded'
                )
                AND jsonb_typeof(details -> 'round') = 'number'
                AND details ->> 'round' = run_row.rounds::text
                AND jsonb_typeof(details -> 'totalTokens') = 'number'
                AND details ->> 'totalTokens' = run_row.total_tokens::text
                AND NEW.sequence_no < run_row.event_count
            ) IS NOT TRUE THEN
                RAISE EXCEPTION 'analysis-accepted event does not match its reducer snapshot'
                    USING ERRCODE = 'P7005';
            END IF;
        WHEN 'TOOL_REQUESTED' THEN
            IF (
                public.opsmind_json_object_has_exact_keys(
                    details, ARRAY['runId', 'intents', 'occurredAt']
                )
                AND jsonb_typeof(details -> 'intents') = 'array'
                AND jsonb_array_length(details -> 'intents') BETWEEN 1 AND 20
                AND details -> 'intents' = run_row.pending_intents_state
                AND run_row.status = 'WAITING_FOR_EVIDENCE'
                AND NEW.sequence_no = run_row.event_count
            ) IS NOT TRUE THEN
                RAISE EXCEPTION 'tool-requested event does not match its reducer snapshot'
                    USING ERRCODE = 'P7005';
            END IF;
        WHEN 'EVIDENCE_APPENDED' THEN
            IF (
                public.opsmind_json_object_has_exact_keys(
                    details,
                    ARRAY['runId', 'intentId', 'evidenceId', 'digest', 'sourceType', 'occurredAt']
                )
                AND details ->> 'intentId'
                    ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                AND run_row.evidence_ids_state ? (details ->> 'evidenceId')
                AND details ->> 'digest' ~ '^sha256:[0-9a-f]{64}$'
                AND details ->> 'sourceType' IN (
                    'metric', 'log_summary', 'trace', 'change', 'runbook'
                )
                AND run_row.status IN ('ANALYZING', 'WAITING_FOR_EVIDENCE')
                AND NEW.sequence_no = run_row.event_count
            ) IS NOT TRUE THEN
                RAISE EXCEPTION 'evidence-appended event does not match its reducer snapshot'
                    USING ERRCODE = 'P7005';
            END IF;
        WHEN 'COMPLETED' THEN
            IF (
                public.opsmind_json_object_has_exact_keys(
                    details, ARRAY['runId', 'response', 'occurredAt']
                )
                AND public.opsmind_valid_analysis_response(
                    details -> 'response', NEW.run_id, 'complete'
                )
                AND details -> 'response' = run_row.final_response
                AND run_row.status = 'COMPLETED'
                AND run_row.ended_at = NEW.occurred_at
                AND NEW.sequence_no = run_row.event_count
            ) IS NOT TRUE THEN
                RAISE EXCEPTION 'completed event does not match its reducer snapshot'
                    USING ERRCODE = 'P7005';
            END IF;
        WHEN 'ABSTAINED', 'BUDGET_EXCEEDED', 'NO_PROGRESS', 'FAILED' THEN
            IF (
                public.opsmind_json_object_has_exact_keys(
                    details, ARRAY['runId', 'reason', 'occurredAt']
                )
                AND jsonb_typeof(details -> 'reason') = 'string'
                AND length(trim(details ->> 'reason')) BETWEEN 1 AND 2000
                AND details ->> 'reason' = run_row.terminal_reason
                AND run_row.status = NEW.event_type
                AND run_row.ended_at = NEW.occurred_at
                AND NEW.sequence_no = run_row.event_count
            ) IS NOT TRUE THEN
                RAISE EXCEPTION 'terminal event does not match its reducer snapshot'
                    USING ERRCODE = 'P7005';
            END IF;
        ELSE
            RAISE EXCEPTION 'unsupported investigation event type'
                USING ERRCODE = 'P7005';
    END CASE;
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION public.opsmind_json_number_between(jsonb, numeric, numeric)
    FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_json_nonnegative_integer(jsonb)
    FROM PUBLIC;
REVOKE ALL ON FUNCTION public.opsmind_valid_accepted_analysis_response(jsonb, uuid, text)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.opsmind_json_number_between(jsonb, numeric, numeric)
    TO opsmind_app;
GRANT EXECUTE ON FUNCTION public.opsmind_json_nonnegative_integer(jsonb)
    TO opsmind_app;
GRANT EXECUTE ON FUNCTION public.opsmind_valid_accepted_analysis_response(jsonb, uuid, text)
    TO opsmind_app;

COMMENT ON FUNCTION opsmind_valid_accepted_analysis_response(jsonb, uuid, text) IS
    'Validates the exact normalized analysis-v1 response bound to a new ANALYSIS_ACCEPTED event.';
