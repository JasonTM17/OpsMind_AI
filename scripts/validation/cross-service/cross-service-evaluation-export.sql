\set ON_ERROR_STOP on
\pset format unaligned
\pset tuples_only on
\pset pager off
\set QUIET on

BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '15s';
SET LOCAL lock_timeout = '2s';
SET LOCAL idle_in_transaction_session_timeout = '20s';
SELECT set_config('opsmind.tenant_id', :'scope_organization_id', true) AS tenant_context
\gset
SELECT set_config(
    'opsmind.ai_runtime_tenant_id', :'scope_organization_id', true
) AS runtime_tenant_context
\gset
SELECT set_config(
    'opsmind.tool_gateway_tenant_id', :'scope_organization_id', true
) AS gateway_tenant_context
\gset
SELECT set_config('opsmind.actor_id', :'scope_actor_id', true) AS actor_context
\gset
SELECT set_config(
    'opsmind.evaluation_project_id', :'scope_project_id', true
) AS project_context
\gset
SELECT set_config(
    'opsmind.tool_gateway_project_id', :'scope_project_id', true
) AS gateway_project_context
\gset
SELECT set_config(
    'opsmind.evaluation_incident_id', :'scope_incident_id', true
) AS incident_context
\gset
SELECT set_config('opsmind.evaluation_run_id', :'scope_run_id', true) AS run_context
\gset

WITH
run_rows AS MATERIALIZED (
    SELECT * FROM opsmind_evaluation.scoped_runs LIMIT 2
),
event_rows AS MATERIALIZED (
    SELECT * FROM opsmind_evaluation.scoped_events
    ORDER BY sequence_no
    LIMIT 129
),
evidence_rows AS MATERIALIZED (
    SELECT * FROM opsmind_evaluation.scoped_evidence_records
    ORDER BY created_at, evidence_id
    LIMIT 201
),
invocation_rows AS MATERIALIZED (
    SELECT * FROM opsmind_evaluation.scoped_analysis_invocations
    ORDER BY started_at, invocation_id
    LIMIT 22
),
receipt_rows AS MATERIALIZED (
    SELECT * FROM opsmind_evaluation.scoped_tool_receipts
    ORDER BY completed_at, execution_id
    LIMIT 21
),
bounded AS (
    SELECT
        (SELECT count(*) FROM run_rows) AS run_count,
        (SELECT count(*) FROM event_rows) AS event_count,
        (SELECT count(*) FROM evidence_rows) AS evidence_count,
        (SELECT count(*) FROM invocation_rows) AS invocation_count,
        (SELECT count(*) FROM receipt_rows) AS receipt_count,
        (SELECT count(*) FROM event_rows
          WHERE event_type = 'ANALYSIS_ACCEPTED') AS accepted_count,
        (SELECT count(*) FROM event_rows event
          WHERE event.event_type = 'ANALYSIS_ACCEPTED'
            AND (
                event.accepted_analysis IS NULL
                OR octet_length(convert_to(event.accepted_analysis::text, 'UTF8')) > 262144
                OR 1 <> (
                    SELECT count(*)
                    FROM invocation_rows invocation
                    WHERE invocation.state = 'succeeded'
                      AND invocation.response_status =
                            event.accepted_analysis ->> 'status'
                      AND invocation.response_payload = event.accepted_analysis
                )
            )) AS unmatched_accepted_count
),
document AS (
    SELECT jsonb_build_object(
        'schema_version', 'opsmind-cross-service-evaluation-export-v1',
        'evidence_classification', 'TRANSIENT_SYNTHETIC_CROSS_SERVICE_EXPORT',
        'query_manifest', jsonb_build_object(
            'reference',
            'repository://scripts/validation/cross-service/cross-service-evaluation-export.sql',
            'byte_digest', :'query_manifest_byte_digest'
        ),
        'scope', jsonb_build_object(
            'organization_id', :'scope_organization_id',
            'project_id', :'scope_project_id',
            'incident_id', :'scope_incident_id',
            'run_id', :'scope_run_id',
            'actor_id', :'scope_actor_id'
        ),
        'run', (
            SELECT jsonb_build_object(
                'run_id', run_id,
                'organization_id', organization_id,
                'project_id', project_id,
                'incident_id', incident_id,
                'actor_id', actor_id,
                'status', status,
                'rounds', rounds,
                'tool_calls', tool_calls,
                'total_tokens', total_tokens,
                'event_count', event_count,
                'evidence_ids', evidence_ids_state,
                'pending_intents', pending_intents_state,
                'terminal_reason', terminal_reason,
                'started_at', started_at,
                'deadline_at', deadline_at,
                'ended_at', ended_at
            )
            FROM run_rows
        ),
        'events', coalesce((
            SELECT jsonb_agg(jsonb_build_object(
                'event_id', event_id,
                'sequence_no', sequence_no,
                'event_type', event_type,
                'occurred_at', occurred_at,
                'accepted_analysis', accepted_analysis
            ) ORDER BY sequence_no)
            FROM event_rows
        ), '[]'::jsonb),
        'evidence_records', coalesce((
            SELECT jsonb_agg(to_jsonb(evidence_rows) ORDER BY created_at, evidence_id)
            FROM evidence_rows
        ), '[]'::jsonb),
        'analysis_invocations', coalesce((
            SELECT jsonb_agg(to_jsonb(invocation_rows) ORDER BY started_at, invocation_id)
            FROM invocation_rows
        ), '[]'::jsonb),
        'tool_receipts', coalesce((
            SELECT jsonb_agg(to_jsonb(receipt_rows) ORDER BY completed_at, execution_id)
            FROM receipt_rows
        ), '[]'::jsonb)
    ) AS value
    FROM bounded
    WHERE run_count = 1
      AND event_count BETWEEN 2 AND 128
      AND evidence_count <= 200
      AND invocation_count <= 21
      AND receipt_count <= 20
      AND accepted_count = (SELECT rounds FROM run_rows)
      AND unmatched_accepted_count = 0
      AND event_count = (SELECT event_count FROM run_rows)
      AND evidence_count =
            jsonb_array_length((SELECT evidence_ids_state FROM run_rows))
      AND receipt_count = (SELECT tool_calls FROM run_rows)
      AND invocation_count = (SELECT rounds FROM run_rows)
)
SELECT value
FROM document
WHERE octet_length(convert_to(value::text, 'UTF8')) BETWEEN 2 AND 4194304;

COMMIT;
