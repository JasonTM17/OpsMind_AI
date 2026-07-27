CREATE TABLE phase_v009_samples (
    sample_kind text NOT NULL,
    sample_phase text NOT NULL,
    sample_no integer NOT NULL,
    duration_ms numeric NOT NULL CHECK (duration_ms >= 0),
    PRIMARY KEY (sample_kind, sample_phase, sample_no)
);

BEGIN;
INSERT INTO incidents (
    id, organization_id, project_id, title, description, severity, status,
    created_by, updated_by, created_at, updated_at
) VALUES (
    '70000000-0000-4000-8000-000000000014',
    '70000000-0000-4000-8000-000000000001',
    '70000000-0000-4000-8000-000000000003',
    'V009 high-cardinality activity ledger',
    'Valid incident aggregate used by the disposable V009 evidence gate.',
    'SEV3',
    'OPEN',
    '70000000-0000-4000-8000-000000000002',
    '70000000-0000-4000-8000-000000000002',
    '2031-01-01T00:00:00Z',
    '2031-01-01T00:00:00Z'
);
INSERT INTO incident_timeline_events (
    event_id, organization_id, project_id, incident_id, incident_version,
    event_kind, actor_id, operation_id, external_trace_id, reason, payload, occurred_at
) VALUES (
    '70000000-0000-4000-8000-000000000015',
    '70000000-0000-4000-8000-000000000001',
    '70000000-0000-4000-8000-000000000003',
    '70000000-0000-4000-8000-000000000014',
    0,
    'INCIDENT_CREATED',
    '70000000-0000-4000-8000-000000000002',
    '70000000-0000-4000-8000-000000000016',
    NULL,
    'V009 valid ledger seed',
    jsonb_build_object(
        'eventId', '70000000-0000-4000-8000-000000000015',
        'organizationId', '70000000-0000-4000-8000-000000000001',
        'projectId', '70000000-0000-4000-8000-000000000003',
        'incidentId', '70000000-0000-4000-8000-000000000014',
        'incidentVersion', 0,
        'eventType', 'INCIDENT_CREATED',
        'actorId', '70000000-0000-4000-8000-000000000002',
        'operationId', '70000000-0000-4000-8000-000000000016',
        'occurredAt', '2031-01-01T00:00:00Z',
        'reason', 'V009 valid ledger seed',
        'fromStatus', NULL,
        'toStatus', 'OPEN',
        'rootCause', NULL,
        'resolutionSummary', NULL
    ),
    '2031-01-01T00:00:00Z'
);

DO $$
DECLARE
    event_version integer;
    event_value uuid;
    operation_value uuid;
    occurred_value timestamptz;
    prior_status text := 'OPEN';
    next_status text;
    root_cause_value text;
    resolution_value text;
BEGIN
    FOR event_version IN 1..49999 LOOP
        IF prior_status = 'OPEN' THEN
            next_status := 'INVESTIGATING';
        ELSIF prior_status = 'INVESTIGATING' THEN
            next_status := 'RESOLVED';
        ELSE
            next_status := 'INVESTIGATING';
        END IF;
        IF next_status = 'RESOLVED' THEN
            root_cause_value := 'V009 benchmark root cause';
            resolution_value := 'V009 benchmark resolution';
        ELSE
            root_cause_value := NULL;
            resolution_value := NULL;
        END IF;

        event_value := gen_random_uuid();
        operation_value := gen_random_uuid();
        occurred_value := '2031-01-01T00:00:00Z'::timestamptz
            + event_version * interval '1 microsecond';
        UPDATE incidents
           SET status = next_status,
               root_cause = root_cause_value,
               resolution_summary = resolution_value,
               updated_by = '70000000-0000-4000-8000-000000000002',
               updated_at = occurred_value,
               version = event_version
         WHERE organization_id = '70000000-0000-4000-8000-000000000001'
           AND project_id = '70000000-0000-4000-8000-000000000003'
           AND id = '70000000-0000-4000-8000-000000000014';
        INSERT INTO incident_timeline_events (
            event_id, organization_id, project_id, incident_id, incident_version,
            event_kind, actor_id, operation_id, external_trace_id, reason,
            payload, occurred_at
        ) VALUES (
            event_value,
            '70000000-0000-4000-8000-000000000001',
            '70000000-0000-4000-8000-000000000003',
            '70000000-0000-4000-8000-000000000014',
            event_version,
            'INCIDENT_STATUS_TRANSITIONED',
            '70000000-0000-4000-8000-000000000002',
            operation_value,
            NULL,
            'V009 valid ledger seed',
            jsonb_build_object(
                'eventId', event_value,
                'organizationId', '70000000-0000-4000-8000-000000000001',
                'projectId', '70000000-0000-4000-8000-000000000003',
                'incidentId', '70000000-0000-4000-8000-000000000014',
                'incidentVersion', event_version,
                'eventType', 'INCIDENT_STATUS_TRANSITIONED',
                'actorId', '70000000-0000-4000-8000-000000000002',
                'operationId', operation_value,
                'occurredAt', occurred_value,
                'reason', 'V009 valid ledger seed',
                'fromStatus', prior_status,
                'toStatus', next_status,
                'rootCause', root_cause_value,
                'resolutionSummary', resolution_value
            ),
            occurred_value
        );
        prior_status := next_status;
    END LOOP;
END
$$;

INSERT INTO investigation_runs (
    run_id, organization_id, project_id, incident_id, actor_id, status,
    max_rounds, max_tool_calls, max_evidence_items, max_tokens,
    event_count, started_at, deadline_at
) VALUES (
    '70000000-0000-4000-8000-000000000017',
    '70000000-0000-4000-8000-000000000001',
    '70000000-0000-4000-8000-000000000003',
    '70000000-0000-4000-8000-000000000014',
    '70000000-0000-4000-8000-000000000002',
    'CREATED',
    1, 0, 1, 1, 1,
    '2031-01-01T00:00:00.000001Z',
    '2031-01-01T00:05:00.000001Z'
);
INSERT INTO investigation_run_events (
    event_id, organization_id, project_id, incident_id, run_id, sequence_no,
    event_type, actor_id, occurred_at, payload
) VALUES (
    '70000000-0000-4000-8000-000000000018',
    '70000000-0000-4000-8000-000000000001',
    '70000000-0000-4000-8000-000000000003',
    '70000000-0000-4000-8000-000000000014',
    '70000000-0000-4000-8000-000000000017',
    1,
    'RUN_STARTED',
    '70000000-0000-4000-8000-000000000002',
    '2031-01-01T00:00:00.000001Z',
    jsonb_build_object(
        'eventId', '70000000-0000-4000-8000-000000000018',
        'organizationId', '70000000-0000-4000-8000-000000000001',
        'projectId', '70000000-0000-4000-8000-000000000003',
        'incidentId', '70000000-0000-4000-8000-000000000014',
        'runId', '70000000-0000-4000-8000-000000000017',
        'sequenceNo', 1,
        'eventType', 'RUN_STARTED',
        'actorId', '70000000-0000-4000-8000-000000000002',
        'occurredAt', '2031-01-01T00:00:00.000001Z',
        'details', jsonb_build_object(
            'runId', '70000000-0000-4000-8000-000000000017',
            'incidentId', '70000000-0000-4000-8000-000000000014',
            'budget', jsonb_build_object(
                'maxRounds', 1,
                'maxToolCalls', 0,
                'maxEvidenceItems', 1,
                'maxTokens', 1
            ),
            'occurredAt', '2031-01-01T00:00:00.000001Z'
        )
    )
);

WITH runs AS (
    INSERT INTO investigation_runs (
        run_id, organization_id, project_id, incident_id, actor_id, status,
        max_rounds, max_tool_calls, max_evidence_items, max_tokens,
        event_count, started_at, deadline_at
    )
    SELECT gen_random_uuid(),
           '70000000-0000-4000-8000-000000000001',
           '70000000-0000-4000-8000-000000000003',
           '70000000-0000-4000-8000-000000000014',
           '70000000-0000-4000-8000-000000000002',
           'CREATED',
           1, 0, 1, 1, 1,
           '2031-01-01T00:00:00Z'::timestamptz
               + sample_no * interval '1 microsecond',
           '2031-01-01T00:00:00Z'::timestamptz
               + interval '5 minutes'
               + sample_no * interval '1 microsecond'
      FROM generate_series(2, 50000) AS series(sample_no)
    RETURNING run_id, organization_id, project_id, incident_id, actor_id, started_at
), events AS (
    SELECT gen_random_uuid() AS event_id,
           run_id, organization_id, project_id, incident_id, actor_id, started_at
      FROM runs
)
INSERT INTO investigation_run_events (
    event_id, organization_id, project_id, incident_id, run_id, sequence_no,
    event_type, actor_id, occurred_at, payload
)
SELECT event_id, organization_id, project_id, incident_id, run_id, 1,
       'RUN_STARTED', actor_id, started_at,
       jsonb_build_object(
           'eventId', event_id,
           'organizationId', organization_id,
           'projectId', project_id,
           'incidentId', incident_id,
           'runId', run_id,
           'sequenceNo', 1,
           'eventType', 'RUN_STARTED',
           'actorId', actor_id,
           'occurredAt', started_at,
           'details', jsonb_build_object(
               'runId', run_id,
               'incidentId', incident_id,
               'budget', jsonb_build_object(
                   'maxRounds', 1,
                   'maxToolCalls', 0,
                   'maxEvidenceItems', 1,
                   'maxTokens', 1
               ),
               'occurredAt', started_at
           )
       )
  FROM events;

-- Same-tenant, same-project distractors prove that the incident_id portion of
-- each V009 index is selective. Their later timestamps keep target page
-- assertions deterministic while still influencing planner statistics.
CREATE TEMP TABLE phase_v009_distractors ON COMMIT DROP AS
SELECT sample_no,
       md5('v009-distractor-incident-' || sample_no)::uuid AS incident_id,
       md5('v009-distractor-incident-event-' || sample_no)::uuid AS incident_event_id,
       md5('v009-distractor-operation-' || sample_no)::uuid AS operation_id,
       md5('v009-distractor-run-' || sample_no)::uuid AS run_id,
       md5('v009-distractor-run-event-' || sample_no)::uuid AS run_event_id,
       '2032-01-01T00:00:00Z'::timestamptz
           + sample_no * interval '1 microsecond' AS occurred_at
  FROM generate_series(1, 10000) AS series(sample_no);

INSERT INTO incidents (
    id, organization_id, project_id, title, description, severity, status,
    created_by, updated_by, created_at, updated_at
)
SELECT incident_id,
       '70000000-0000-4000-8000-000000000001',
       '70000000-0000-4000-8000-000000000003',
       'V009 distractor incident ' || sample_no,
       'Same-tenant, same-project cardinality fixture.',
       'SEV4',
       'OPEN',
       '70000000-0000-4000-8000-000000000002',
       '70000000-0000-4000-8000-000000000002',
       occurred_at,
       occurred_at
  FROM phase_v009_distractors;

INSERT INTO incident_timeline_events (
    event_id, organization_id, project_id, incident_id, incident_version,
    event_kind, actor_id, operation_id, external_trace_id, reason, payload, occurred_at
)
SELECT incident_event_id,
       '70000000-0000-4000-8000-000000000001',
       '70000000-0000-4000-8000-000000000003',
       incident_id,
       0,
       'INCIDENT_CREATED',
       '70000000-0000-4000-8000-000000000002',
       operation_id,
       NULL,
       'V009 valid distractor ledger seed',
       jsonb_build_object(
           'eventId', incident_event_id,
           'organizationId', '70000000-0000-4000-8000-000000000001',
           'projectId', '70000000-0000-4000-8000-000000000003',
           'incidentId', incident_id,
           'incidentVersion', 0,
           'eventType', 'INCIDENT_CREATED',
           'actorId', '70000000-0000-4000-8000-000000000002',
           'operationId', operation_id,
           'occurredAt', occurred_at,
           'reason', 'V009 valid distractor ledger seed',
           'fromStatus', NULL,
           'toStatus', 'OPEN',
           'rootCause', NULL,
           'resolutionSummary', NULL
       ),
       occurred_at
  FROM phase_v009_distractors;

INSERT INTO investigation_runs (
    run_id, organization_id, project_id, incident_id, actor_id, status,
    max_rounds, max_tool_calls, max_evidence_items, max_tokens,
    event_count, started_at, deadline_at
)
SELECT run_id,
       '70000000-0000-4000-8000-000000000001',
       '70000000-0000-4000-8000-000000000003',
       incident_id,
       '70000000-0000-4000-8000-000000000002',
       'CREATED',
       1, 0, 1, 1, 1,
       occurred_at,
       occurred_at + interval '5 minutes'
  FROM phase_v009_distractors;

INSERT INTO investigation_run_events (
    event_id, organization_id, project_id, incident_id, run_id, sequence_no,
    event_type, actor_id, occurred_at, payload
)
SELECT run_event_id,
       '70000000-0000-4000-8000-000000000001',
       '70000000-0000-4000-8000-000000000003',
       incident_id,
       run_id,
       1,
       'RUN_STARTED',
       '70000000-0000-4000-8000-000000000002',
       occurred_at,
       jsonb_build_object(
           'eventId', run_event_id,
           'organizationId', '70000000-0000-4000-8000-000000000001',
           'projectId', '70000000-0000-4000-8000-000000000003',
           'incidentId', incident_id,
           'runId', run_id,
           'sequenceNo', 1,
           'eventType', 'RUN_STARTED',
           'actorId', '70000000-0000-4000-8000-000000000002',
           'occurredAt', occurred_at,
           'details', jsonb_build_object(
               'runId', run_id,
               'incidentId', incident_id,
               'budget', jsonb_build_object(
                   'maxRounds', 1,
                   'maxToolCalls', 0,
                   'maxEvidenceItems', 1,
                   'maxTokens', 1
               ),
               'occurredAt', occurred_at
           )
       )
  FROM phase_v009_distractors;
COMMIT;

ANALYZE incident_timeline_events;
ANALYZE investigation_run_events;
