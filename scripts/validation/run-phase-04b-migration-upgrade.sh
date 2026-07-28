#!/usr/bin/env bash
set -euo pipefail

if [[ "${OPSMIND_EPHEMERAL_DB:-}" != "true" ]]; then
  echo "OPSMIND_EPHEMERAL_DB=true is required for the disposable upgrade proof." >&2
  exit 2
fi

for required_name in PGHOST PGPORT PGDATABASE POSTGRES_USER POSTGRES_PASSWORD; do
  if [[ -z "${!required_name:-}" ]]; then
    echo "${required_name} is required." >&2
    exit 2
  fi
done

upgrade_database="${OPSMIND_PHASE4B_UPGRADE_DATABASE:-opsmind_phase4b_upgrade}"
if [[ ! "$upgrade_database" =~ ^opsmind_phase4b_upgrade(_[a-z0-9_]+)?$ ]]; then
  echo "Upgrade database must use the opsmind_phase4b_upgrade prefix." >&2
  exit 2
fi
if [[ "$upgrade_database" == "$PGDATABASE" ]]; then
  echo "Upgrade database must differ from the primary database." >&2
  exit 2
fi

platform_jar="${OPSMIND_PLATFORM_JAR:-services/platform-api/target/platform-api.jar}"
if [[ ! -f "$platform_jar" ]]; then
  echo "Packaged Platform API JAR is required: ${platform_jar}" >&2
  exit 2
fi

database_created=false
cleanup() {
  local original_status=$?
  local cleanup_status=0
  trap - EXIT

  if [[ "$database_created" == "true" ]]; then
    PGPASSWORD="$POSTGRES_PASSWORD" dropdb --no-password \
      --host "$PGHOST" --port "$PGPORT" --username "$POSTGRES_USER" \
      "$upgrade_database" || cleanup_status=$?
  fi

  if [[ "$cleanup_status" -eq 0 ]]; then
    echo "CleanupResult=PASS"
  else
    echo "CleanupResult=BLOCK" >&2
    if [[ "$original_status" -eq 0 ]]; then original_status=$cleanup_status; fi
  fi
  exit "$original_status"
}
trap cleanup EXIT

PGPASSWORD="$POSTGRES_PASSWORD" createdb --no-password \
  --host "$PGHOST" --port "$PGPORT" --username "$POSTGRES_USER" \
  "$upgrade_database"
database_created=true

database_url="jdbc:postgresql://${PGHOST}:${PGPORT}/${upgrade_database}"
migrate_to() {
  local target="$1"
  SPRING_PROFILES_ACTIVE=persistence \
  SPRING_DATASOURCE_URL="$database_url" \
  SPRING_DATASOURCE_USERNAME="$POSTGRES_USER" \
  SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD" \
  OPSMIND_FLYWAY_ENABLED=true \
    java -jar "$platform_jar" \
      --spring.main.web-application-type=none \
      --opsmind.persistence.enabled=false \
      "--spring.flyway.target=${target}"
}

query_upgrade_database() {
  local sql="$1"
  PGPASSWORD="$POSTGRES_PASSWORD" psql --no-password --no-psqlrc \
    --host "$PGHOST" --port "$PGPORT" --username "$POSTGRES_USER" \
    --dbname "$upgrade_database" --tuples-only --no-align --set ON_ERROR_STOP=1 \
    --command "$sql" | tr -d '\r'
}

migrate_to 6
version_before="$(query_upgrade_database "SELECT max(version::integer) FROM flyway_schema_history WHERE success;")"
table_before="$(query_upgrade_database "SELECT CASE WHEN to_regclass('public.evidence_records') IS NULL THEN 'ABSENT' ELSE 'PRESENT' END;")"
[[ "$version_before" == "6" ]]
[[ "$table_before" == "ABSENT" ]]

migrate_to 7
version_seven="$(query_upgrade_database "SELECT max(version::integer) FROM flyway_schema_history WHERE success;")"
table_after_seven="$(query_upgrade_database "SELECT CASE WHEN to_regclass('public.evidence_records') IS NULL THEN 'ABSENT' ELSE 'PRESENT' END;")"
[[ "$version_seven" == "7" ]]
[[ "$table_after_seven" == "PRESENT" ]]

query_upgrade_database "
BEGIN;
INSERT INTO organizations (id, slug, name)
VALUES ('70000000-0000-4000-8000-000000000001', 'upgrade-proof', 'Upgrade proof');
INSERT INTO platform_users (id, issuer, subject, display_name)
VALUES (
  '70000000-0000-4000-8000-000000000002',
  'https://upgrade.opsmind.invalid',
  'upgrade-proof',
  'Upgrade proof'
);
INSERT INTO organization_memberships (organization_id, user_id, role)
VALUES (
  '70000000-0000-4000-8000-000000000001',
  '70000000-0000-4000-8000-000000000002',
  'SRE'
);
INSERT INTO projects (id, organization_id, slug, name)
VALUES (
  '70000000-0000-4000-8000-000000000003',
  '70000000-0000-4000-8000-000000000001',
  'upgrade-api',
  'Upgrade API'
);
INSERT INTO project_memberships (organization_id, project_id, user_id, role)
VALUES (
  '70000000-0000-4000-8000-000000000001',
  '70000000-0000-4000-8000-000000000003',
  '70000000-0000-4000-8000-000000000002',
  'SRE'
);
INSERT INTO incidents (
  id, organization_id, project_id, title, description, severity, status,
  created_by, updated_by
) VALUES (
  '70000000-0000-4000-8000-000000000004',
  '70000000-0000-4000-8000-000000000001',
  '70000000-0000-4000-8000-000000000003',
  'V007 accepted event',
  'Proves V008 preserves historical accepted events.',
  'SEV3',
  'OPEN',
  '70000000-0000-4000-8000-000000000002',
  '70000000-0000-4000-8000-000000000002'
);
INSERT INTO investigation_runs (
  run_id, organization_id, project_id, incident_id, actor_id, status,
  max_rounds, max_tool_calls, max_evidence_items, max_tokens, event_count,
  started_at, deadline_at
) VALUES (
  '70000000-0000-4000-8000-000000000005',
  '70000000-0000-4000-8000-000000000001',
  '70000000-0000-4000-8000-000000000003',
  '70000000-0000-4000-8000-000000000004',
  '70000000-0000-4000-8000-000000000002',
  'CREATED', 2, 0, 1, 100, 1,
  '2030-01-01T00:00:00Z', '2030-01-01T00:02:00Z'
);
INSERT INTO investigation_run_events (
  event_id, organization_id, project_id, incident_id, run_id, sequence_no,
  event_type, actor_id, occurred_at, payload
) VALUES (
  '70000000-0000-4000-8000-000000000006',
  '70000000-0000-4000-8000-000000000001',
  '70000000-0000-4000-8000-000000000003',
  '70000000-0000-4000-8000-000000000004',
  '70000000-0000-4000-8000-000000000005',
  1, 'RUN_STARTED',
  '70000000-0000-4000-8000-000000000002',
  '2030-01-01T00:00:00Z',
  jsonb_build_object(
    'eventId', '70000000-0000-4000-8000-000000000006',
    'organizationId', '70000000-0000-4000-8000-000000000001',
    'projectId', '70000000-0000-4000-8000-000000000003',
    'incidentId', '70000000-0000-4000-8000-000000000004',
    'runId', '70000000-0000-4000-8000-000000000005',
    'sequenceNo', 1,
    'eventType', 'RUN_STARTED',
    'actorId', '70000000-0000-4000-8000-000000000002',
    'occurredAt', '2030-01-01T00:00:00Z',
    'details', jsonb_build_object(
      'runId', '70000000-0000-4000-8000-000000000005',
      'incidentId', '70000000-0000-4000-8000-000000000004',
      'budget', jsonb_build_object(
        'maxRounds', 2, 'maxToolCalls', 0, 'maxEvidenceItems', 1, 'maxTokens', 100
      ),
      'occurredAt', '2030-01-01T00:00:00Z'
    )
  )
);
COMMIT;
BEGIN;
UPDATE investigation_runs
SET status = 'ABSTAINED',
    rounds = 1,
    total_tokens = 2,
    revision = 1,
    event_count = 3,
    terminal_reason = 'Historical V007 abstention.',
    ended_at = '2030-01-01T00:00:02Z'
WHERE organization_id = '70000000-0000-4000-8000-000000000001'
  AND run_id = '70000000-0000-4000-8000-000000000005';
INSERT INTO investigation_run_events (
  event_id, organization_id, project_id, incident_id, run_id, sequence_no,
  event_type, actor_id, occurred_at, payload
)
SELECT
  event_id, organization_id, project_id, incident_id,
  '70000000-0000-4000-8000-000000000005'::uuid,
  sequence_no, event_type,
  '70000000-0000-4000-8000-000000000002'::uuid,
  occurred_at,
  jsonb_build_object(
    'eventId', event_id,
    'organizationId', '70000000-0000-4000-8000-000000000001',
    'projectId', '70000000-0000-4000-8000-000000000003',
    'incidentId', '70000000-0000-4000-8000-000000000004',
    'runId', '70000000-0000-4000-8000-000000000005',
    'sequenceNo', sequence_no,
    'eventType', event_type,
    'actorId', '70000000-0000-4000-8000-000000000002',
    'occurredAt', occurred_at,
    'details', details
  )
FROM (
  VALUES
    (
      '70000000-0000-4000-8000-000000000007'::uuid,
      '70000000-0000-4000-8000-000000000001'::uuid,
      '70000000-0000-4000-8000-000000000003'::uuid,
      '70000000-0000-4000-8000-000000000004'::uuid,
      2::bigint,
      'ANALYSIS_ACCEPTED'::text,
      '2030-01-01T00:00:01Z'::timestamptz,
      jsonb_build_object(
        'runId', '70000000-0000-4000-8000-000000000005',
        'status', 'abstain',
        'round', 1,
        'totalTokens', 2,
        'occurredAt', '2030-01-01T00:00:01Z'
      )
    ),
    (
      '70000000-0000-4000-8000-000000000008'::uuid,
      '70000000-0000-4000-8000-000000000001'::uuid,
      '70000000-0000-4000-8000-000000000003'::uuid,
      '70000000-0000-4000-8000-000000000004'::uuid,
      3::bigint,
      'ABSTAINED'::text,
      '2030-01-01T00:00:02Z'::timestamptz,
      jsonb_build_object(
        'runId', '70000000-0000-4000-8000-000000000005',
        'reason', 'Historical V007 abstention.',
        'occurredAt', '2030-01-01T00:00:02Z'
      )
    )
) AS events(
  event_id, organization_id, project_id, incident_id,
  sequence_no, event_type, occurred_at, details
)
ORDER BY sequence_no;
COMMIT;
"
legacy_digest_before="$(query_upgrade_database "
SELECT encode(digest(convert_to(payload::text, 'UTF8'), 'sha256'), 'hex')
FROM investigation_run_events
WHERE event_id = '70000000-0000-4000-8000-000000000007';
")"

migrate_to 8
query_upgrade_database "
BEGIN;
INSERT INTO investigation_runs (
  run_id, organization_id, project_id, incident_id, actor_id, status,
  max_rounds, max_tool_calls, max_evidence_items, max_tokens, event_count,
  started_at, deadline_at
) VALUES (
  '70000000-0000-4000-8000-000000000009',
  '70000000-0000-4000-8000-000000000001',
  '70000000-0000-4000-8000-000000000003',
  '70000000-0000-4000-8000-000000000004',
  '70000000-0000-4000-8000-000000000002',
  'CREATED', 2, 0, 1, 100, 1,
  '2030-01-02T00:00:00Z', '2030-01-02T00:02:00Z'
);
INSERT INTO investigation_run_events (
  event_id, organization_id, project_id, incident_id, run_id, sequence_no,
  event_type, actor_id, occurred_at, payload
) VALUES (
  '70000000-0000-4000-8000-000000000010',
  '70000000-0000-4000-8000-000000000001',
  '70000000-0000-4000-8000-000000000003',
  '70000000-0000-4000-8000-000000000004',
  '70000000-0000-4000-8000-000000000009',
  1, 'RUN_STARTED',
  '70000000-0000-4000-8000-000000000002',
  '2030-01-02T00:00:00Z',
  jsonb_build_object(
    'eventId', '70000000-0000-4000-8000-000000000010',
    'organizationId', '70000000-0000-4000-8000-000000000001',
    'projectId', '70000000-0000-4000-8000-000000000003',
    'incidentId', '70000000-0000-4000-8000-000000000004',
    'runId', '70000000-0000-4000-8000-000000000009',
    'sequenceNo', 1,
    'eventType', 'RUN_STARTED',
    'actorId', '70000000-0000-4000-8000-000000000002',
    'occurredAt', '2030-01-02T00:00:00Z',
    'details', jsonb_build_object(
      'runId', '70000000-0000-4000-8000-000000000009',
      'incidentId', '70000000-0000-4000-8000-000000000004',
      'budget', jsonb_build_object(
        'maxRounds', 2, 'maxToolCalls', 0, 'maxEvidenceItems', 1, 'maxTokens', 100
      ),
      'occurredAt', '2030-01-02T00:00:00Z'
    )
  )
);
COMMIT;
BEGIN;
UPDATE investigation_runs
SET status = 'ABSTAINED',
    rounds = 1,
    total_tokens = 2,
    revision = 1,
    event_count = 3,
    terminal_reason = 'Legacy writer remained compatible after V008.',
    ended_at = '2030-01-02T00:00:02Z'
WHERE organization_id = '70000000-0000-4000-8000-000000000001'
  AND run_id = '70000000-0000-4000-8000-000000000009';
INSERT INTO investigation_run_events (
  event_id, organization_id, project_id, incident_id, run_id, sequence_no,
  event_type, actor_id, occurred_at, payload
) VALUES
(
  '70000000-0000-4000-8000-000000000011',
  '70000000-0000-4000-8000-000000000001',
  '70000000-0000-4000-8000-000000000003',
  '70000000-0000-4000-8000-000000000004',
  '70000000-0000-4000-8000-000000000009',
  2, 'ANALYSIS_ACCEPTED',
  '70000000-0000-4000-8000-000000000002',
  '2030-01-02T00:00:01Z',
  jsonb_build_object(
    'eventId', '70000000-0000-4000-8000-000000000011',
    'organizationId', '70000000-0000-4000-8000-000000000001',
    'projectId', '70000000-0000-4000-8000-000000000003',
    'incidentId', '70000000-0000-4000-8000-000000000004',
    'runId', '70000000-0000-4000-8000-000000000009',
    'sequenceNo', 2,
    'eventType', 'ANALYSIS_ACCEPTED',
    'actorId', '70000000-0000-4000-8000-000000000002',
    'occurredAt', '2030-01-02T00:00:01Z',
    'details', jsonb_build_object(
      'runId', '70000000-0000-4000-8000-000000000009',
      'status', 'abstain',
      'round', 1,
      'totalTokens', 2,
      'occurredAt', '2030-01-02T00:00:01Z'
    )
  )
),
(
  '70000000-0000-4000-8000-000000000012',
  '70000000-0000-4000-8000-000000000001',
  '70000000-0000-4000-8000-000000000003',
  '70000000-0000-4000-8000-000000000004',
  '70000000-0000-4000-8000-000000000009',
  3, 'ABSTAINED',
  '70000000-0000-4000-8000-000000000002',
  '2030-01-02T00:00:02Z',
  jsonb_build_object(
    'eventId', '70000000-0000-4000-8000-000000000012',
    'organizationId', '70000000-0000-4000-8000-000000000001',
    'projectId', '70000000-0000-4000-8000-000000000003',
    'incidentId', '70000000-0000-4000-8000-000000000004',
    'runId', '70000000-0000-4000-8000-000000000009',
    'sequenceNo', 3,
    'eventType', 'ABSTAINED',
    'actorId', '70000000-0000-4000-8000-000000000002',
    'occurredAt', '2030-01-02T00:00:02Z',
    'details', jsonb_build_object(
      'runId', '70000000-0000-4000-8000-000000000009',
      'reason', 'Legacy writer remained compatible after V008.',
      'occurredAt', '2030-01-02T00:00:02Z'
    )
  )
);
COMMIT;
"
version_eight="$(query_upgrade_database "SELECT max(version::integer) FROM flyway_schema_history WHERE success;")"
legacy_digest_after="$(query_upgrade_database "
SELECT encode(digest(convert_to(payload::text, 'UTF8'), 'sha256'), 'hex')
FROM investigation_run_events
WHERE event_id = '70000000-0000-4000-8000-000000000007'
  AND NOT (payload -> 'details' ? 'response');
")"
invalid_abstain_rejected="$(query_upgrade_database "
SELECT NOT opsmind_valid_accepted_analysis_response(
  jsonb_build_object(
    'status', 'abstain',
    'run_id', '70000000-0000-4000-8000-000000000005',
    'model_id', 'deepseek-v4-flash',
    'prompt_version', 'prompt-incident-investigation-v1',
    'schema_version', 'analysis-v1',
    'hypotheses', '[]'::jsonb,
    'counter_evidence', '[]'::jsonb,
    'missing_evidence', '[]'::jsonb,
    'citations', '[]'::jsonb,
    'confidence', 0.1,
    'usage', jsonb_build_object(
      'prompt_tokens', 1, 'completion_tokens', 1, 'total_tokens', 2
    ),
    'cost_estimate', jsonb_build_object('currency', 'USD', 'amount', 0),
    'requested_tool_calls', '[]'::jsonb
  ),
  '70000000-0000-4000-8000-000000000005',
  'abstain'
);
")"
rolling_legacy_write_count="$(query_upgrade_database "
SELECT count(*)
FROM investigation_run_events
WHERE event_id = '70000000-0000-4000-8000-000000000011'
  AND event_type = 'ANALYSIS_ACCEPTED'
  AND NOT (payload -> 'details' ? 'response');
")"
[[ "$version_eight" == "8" ]]
[[ -n "$legacy_digest_before" ]]
[[ "$legacy_digest_after" == "$legacy_digest_before" ]]
[[ "$invalid_abstain_rejected" == "t" ]]
[[ "$rolling_legacy_write_count" == "1" ]]

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/phase-04b-evidence-records/incident-timeline-v009-evidence.sh"
run_incident_timeline_v009_evidence "$upgrade_database" "$database_url"
version_nine="$(query_upgrade_database "SELECT max(version::integer) FROM flyway_schema_history WHERE success;")"
[[ "$version_nine" == "9" ]]

query_upgrade_database "
BEGIN;
INSERT INTO investigation_runs (
  run_id, organization_id, project_id, incident_id, actor_id, status,
  max_rounds, max_tool_calls, max_evidence_items, max_tokens, event_count,
  started_at, deadline_at
) VALUES (
  '70000000-0000-4000-8000-000000000013',
  '70000000-0000-4000-8000-000000000001',
  '70000000-0000-4000-8000-000000000003',
  '70000000-0000-4000-8000-000000000004',
  '70000000-0000-4000-8000-000000000002',
  'CREATED', 2, 0, 1, 100, 1,
  '2030-01-03T00:00:00Z', '2030-01-03T00:02:00Z'
);
INSERT INTO investigation_run_events (
  event_id, organization_id, project_id, incident_id, run_id, sequence_no,
  event_type, actor_id, occurred_at, payload
) VALUES (
  '70000000-0000-4000-8000-000000000014',
  '70000000-0000-4000-8000-000000000001',
  '70000000-0000-4000-8000-000000000003',
  '70000000-0000-4000-8000-000000000004',
  '70000000-0000-4000-8000-000000000013',
  1, 'RUN_STARTED',
  '70000000-0000-4000-8000-000000000002',
  '2030-01-03T00:00:00Z',
  jsonb_build_object(
    'eventId', '70000000-0000-4000-8000-000000000014',
    'organizationId', '70000000-0000-4000-8000-000000000001',
    'projectId', '70000000-0000-4000-8000-000000000003',
    'incidentId', '70000000-0000-4000-8000-000000000004',
    'runId', '70000000-0000-4000-8000-000000000013',
    'sequenceNo', 1,
    'eventType', 'RUN_STARTED',
    'actorId', '70000000-0000-4000-8000-000000000002',
    'occurredAt', '2030-01-03T00:00:00Z',
    'details', jsonb_build_object(
      'runId', '70000000-0000-4000-8000-000000000013',
      'incidentId', '70000000-0000-4000-8000-000000000004',
      'budget', jsonb_build_object(
        'maxRounds', 2, 'maxToolCalls', 0, 'maxEvidenceItems', 1, 'maxTokens', 100
      ),
      'occurredAt', '2030-01-03T00:00:00Z'
    )
  )
);
COMMIT;
"

migrate_to 10
version_ten="$(query_upgrade_database "SELECT max(version::integer) FROM flyway_schema_history WHERE success;")"
binding_table_after_ten="$(query_upgrade_database "
SELECT CASE
  WHEN to_regclass('public.investigation_workflow_bindings') IS NULL
    THEN 'ABSENT'
  ELSE 'PRESENT'
END;
")"
workflow_event_function_after_ten="$(query_upgrade_database "
SELECT CASE
  WHEN to_regprocedure(
    'public.opsmind_investigation_workflow_start_event_id(uuid,uuid)'
  ) IS NULL THEN 'ABSENT'
  ELSE 'PRESENT'
END;
")"
legacy_terminal_runs_after_ten="$(query_upgrade_database "
SELECT count(*)
FROM investigation_runs
WHERE run_id IN (
  '70000000-0000-4000-8000-000000000005',
  '70000000-0000-4000-8000-000000000009'
)
  AND status = 'ABSTAINED';
")"
legacy_binding_count_after_ten="$(query_upgrade_database "
SELECT count(*)
FROM investigation_workflow_bindings
WHERE run_id IN (
  '70000000-0000-4000-8000-000000000005',
  '70000000-0000-4000-8000-000000000009'
);
")"
nonterminal_orphans_after_ten="$(query_upgrade_database "
SELECT count(*)
FROM investigation_runs run
LEFT JOIN investigation_workflow_bindings binding
  ON binding.organization_id = run.organization_id
 AND binding.run_id = run.run_id
WHERE run.status IN ('CREATED', 'ANALYZING', 'WAITING_FOR_EVIDENCE')
  AND binding.run_id IS NULL;
")"
[[ "$version_ten" == "10" ]]
[[ "$binding_table_after_ten" == "PRESENT" ]]
[[ "$workflow_event_function_after_ten" == "PRESENT" ]]
[[ "$legacy_terminal_runs_after_ten" == "2" ]]
[[ "$legacy_binding_count_after_ten" == "0" ]]
[[ "$nonterminal_orphans_after_ten" == "1" ]]

migrate_to 11
version_eleven="$(query_upgrade_database "SELECT max(version::integer) FROM flyway_schema_history WHERE success;")"
workflow_preflight_function_after_eleven="$(query_upgrade_database "
SELECT CASE
  WHEN to_regprocedure(
    'public.opsmind_preflight_investigation_workflow_start(uuid,uuid,uuid,bigint)'
  ) IS NULL THEN 'ABSENT'
  ELSE 'PRESENT'
END;
")"
workflow_settlement_function_after_eleven="$(query_upgrade_database "
SELECT CASE
  WHEN to_regprocedure(
    'public.opsmind_settle_investigation_workflow_start(uuid,uuid,uuid,character varying,character varying,character varying,bigint)'
  ) IS NULL THEN 'ABSENT'
  ELSE 'PRESENT'
END;
")"
workflow_terminalizer_function_after_eleven="$(query_upgrade_database "
SELECT CASE
  WHEN to_regprocedure(
    'public.opsmind_terminalize_unclaimed_ineligible_workflow_starts(integer)'
  ) IS NULL THEN 'ABSENT'
  ELSE 'PRESENT'
END;
")"
workflow_settlement_owner_after_eleven="$(query_upgrade_database "
SELECT pg_get_userbyid(proowner)
FROM pg_proc
WHERE oid = 'public.opsmind_settle_investigation_workflow_start(uuid,uuid,uuid,character varying,character varying,character varying,bigint)'::regprocedure;
")"
[[ "$version_eleven" == "11" ]]
[[ "$workflow_preflight_function_after_eleven" == "PRESENT" ]]
[[ "$workflow_settlement_function_after_eleven" == "PRESENT" ]]
[[ "$workflow_terminalizer_function_after_eleven" == "PRESENT" ]]
[[ "$workflow_settlement_owner_after_eleven" == "opsmind_dispatch_resolver" ]]

cutover_block_output="${TMPDIR:-/tmp}/opsmind-phase9-cutover-block-${upgrade_database}.txt"
set +e
PGPASSWORD="$POSTGRES_PASSWORD" psql --no-password --no-psqlrc \
  --host "$PGHOST" --port "$PGPORT" --username "$POSTGRES_USER" \
  --dbname "$upgrade_database" \
  --file scripts/operations/investigation-workflow-cutover-inventory.sql \
  > "$cutover_block_output" 2>&1
cutover_block_status=$?
set -e
cat "$cutover_block_output"
rm -f "$cutover_block_output"
[[ "$cutover_block_status" == "3" ]]

query_upgrade_database "
BEGIN;
UPDATE investigation_runs
SET status = 'FAILED',
    revision = revision + 1,
    event_count = event_count + 1,
    terminal_reason = 'Phase 9 upgrade proof reconciled the legacy orphan.',
    ended_at = '2030-01-03T00:01:00Z'
WHERE organization_id = '70000000-0000-4000-8000-000000000001'
  AND run_id = '70000000-0000-4000-8000-000000000013'
  AND status = 'CREATED'
  AND revision = 0
  AND event_count = 1;
INSERT INTO investigation_run_events (
  event_id, organization_id, project_id, incident_id, run_id, sequence_no,
  event_type, actor_id, occurred_at, payload
) VALUES (
  '70000000-0000-4000-8000-000000000015',
  '70000000-0000-4000-8000-000000000001',
  '70000000-0000-4000-8000-000000000003',
  '70000000-0000-4000-8000-000000000004',
  '70000000-0000-4000-8000-000000000013',
  2,
  'FAILED',
  '70000000-0000-4000-8000-000000000002',
  '2030-01-03T00:01:00Z',
  jsonb_build_object(
    'eventId', '70000000-0000-4000-8000-000000000015',
    'organizationId', '70000000-0000-4000-8000-000000000001',
    'projectId', '70000000-0000-4000-8000-000000000003',
    'incidentId', '70000000-0000-4000-8000-000000000004',
    'runId', '70000000-0000-4000-8000-000000000013',
    'sequenceNo', 2,
    'eventType', 'FAILED',
    'actorId', '70000000-0000-4000-8000-000000000002',
    'occurredAt', '2030-01-03T00:01:00Z',
    'details', jsonb_build_object(
      'runId', '70000000-0000-4000-8000-000000000013',
      'reason', 'Phase 9 upgrade proof reconciled the legacy orphan.',
      'occurredAt', '2030-01-03T00:01:00Z'
    )
  )
);
COMMIT;
"
PGPASSWORD="$POSTGRES_PASSWORD" psql --no-password --no-psqlrc \
  --host "$PGHOST" --port "$PGPORT" --username "$POSTGRES_USER" \
  --dbname "$upgrade_database" \
  --file scripts/operations/investigation-workflow-cutover-inventory.sql
nonterminal_orphans_after_reconciliation="$(query_upgrade_database "
SELECT count(*)
FROM investigation_runs run
LEFT JOIN investigation_workflow_bindings binding
  ON binding.organization_id = run.organization_id
 AND binding.run_id = run.run_id
WHERE run.status IN ('CREATED', 'ANALYZING', 'WAITING_FOR_EVIDENCE')
  AND binding.run_id IS NULL;
")"
[[ "$nonterminal_orphans_after_reconciliation" == "0" ]]

printf 'Database=%s\nVersionBefore=%s\nEvidenceTableBefore=%s\nVersionSeven=%s\nEvidenceTableAfterSeven=%s\nVersionEight=%s\nVersionNine=%s\nVersionTen=%s\nVersionEleven=%s\nWorkflowBindingTableAfterTen=%s\nWorkflowEventFunctionAfterTen=%s\nWorkflowPreflightFunctionAfterEleven=%s\nWorkflowSettlementFunctionAfterEleven=%s\nWorkflowTerminalizerFunctionAfterEleven=%s\nWorkflowSettlementOwnerAfterEleven=%s\nLegacyTerminalRunsAfterTen=%s\nLegacyBindingCountAfterTen=%s\nNonterminalOrphansAfterTen=%s\nCutoverBlockExit=%s\nNonterminalOrphansAfterReconciliation=%s\nLegacyPayloadDigestStable=%s\nRollingLegacyWriteCount=%s\nInvalidAbstainRejected=%s\nUpgradeResult=PASS\n' \
  "$upgrade_database" "$version_before" "$table_before" \
  "$version_seven" "$table_after_seven" "$version_eight" "$version_nine" \
  "$version_ten" "$version_eleven" "$binding_table_after_ten" \
  "$workflow_event_function_after_ten" "$workflow_preflight_function_after_eleven" \
  "$workflow_settlement_function_after_eleven" "$workflow_terminalizer_function_after_eleven" \
  "$workflow_settlement_owner_after_eleven" \
  "$legacy_terminal_runs_after_ten" "$legacy_binding_count_after_ten" \
  "$nonterminal_orphans_after_ten" "$cutover_block_status" \
  "$nonterminal_orphans_after_reconciliation" \
  "$([[ "$legacy_digest_after" == "$legacy_digest_before" ]] && printf true || printf false)" \
  "$rolling_legacy_write_count" "$invalid_abstain_rejected"
