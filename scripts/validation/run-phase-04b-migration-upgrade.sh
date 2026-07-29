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
    --dbname "$upgrade_database" --quiet --tuples-only --no-align \
    --set ON_ERROR_STOP=1 \
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
DO \$\$
DECLARE
  fixture_run_count bigint;
  fixture_event_count bigint;
  append_only_trigger_state \"char\";
BEGIN
  SELECT count(*)
    INTO fixture_run_count
    FROM investigation_runs
   WHERE started_at >= TIMESTAMPTZ '2031-01-01T00:00:00Z'
     AND started_at < TIMESTAMPTZ '2033-01-01T00:00:00Z';
  SELECT count(*)
    INTO fixture_event_count
    FROM investigation_run_events
   WHERE occurred_at >= TIMESTAMPTZ '2031-01-01T00:00:00Z'
     AND occurred_at < TIMESTAMPTZ '2033-01-01T00:00:00Z';
  SELECT trigger_row.tgenabled
    INTO append_only_trigger_state
    FROM pg_trigger trigger_row
   WHERE trigger_row.tgrelid = 'public.investigation_run_events'::regclass
     AND trigger_row.tgname = 'investigation_run_events_no_update'
     AND NOT trigger_row.tgisinternal;

  IF fixture_run_count IS DISTINCT FROM 60000
     OR fixture_event_count IS DISTINCT FROM 60000 THEN
    RAISE EXCEPTION
      'expected 60000 V009 investigation fixtures, found % runs and % events',
      fixture_run_count, fixture_event_count;
  END IF;
  IF append_only_trigger_state IS DISTINCT FROM 'O' THEN
    RAISE EXCEPTION 'V009 cleanup requires the append-only trigger to be enabled';
  END IF;
END
\$\$;
ALTER TABLE investigation_run_events
  DISABLE TRIGGER investigation_run_events_no_update;
DELETE FROM investigation_run_events
WHERE occurred_at >= TIMESTAMPTZ '2031-01-01T00:00:00Z'
  AND occurred_at < TIMESTAMPTZ '2033-01-01T00:00:00Z';
DELETE FROM investigation_runs
WHERE started_at >= TIMESTAMPTZ '2031-01-01T00:00:00Z'
  AND started_at < TIMESTAMPTZ '2033-01-01T00:00:00Z';
ALTER TABLE investigation_run_events
  ENABLE TRIGGER investigation_run_events_no_update;
DO \$\$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM investigation_runs
     WHERE started_at >= TIMESTAMPTZ '2031-01-01T00:00:00Z'
       AND started_at < TIMESTAMPTZ '2033-01-01T00:00:00Z'
  ) OR EXISTS (
    SELECT 1
      FROM investigation_run_events
     WHERE occurred_at >= TIMESTAMPTZ '2031-01-01T00:00:00Z'
       AND occurred_at < TIMESTAMPTZ '2033-01-01T00:00:00Z'
  ) THEN
    RAISE EXCEPTION 'V009 investigation fixtures remain after cleanup';
  END IF;
END
\$\$;
COMMIT;
"
printf 'V009UpgradeFixtureCleanup=PASS\n'

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

query_upgrade_database "
INSERT INTO service_accounts (
  id, organization_id, name, credential_ref, allowed_audiences,
  allowed_scopes, database_principal
) VALUES (
  '70000000-0000-4000-8000-000000000016',
  '70000000-0000-4000-8000-000000000001',
  'workflow-upgrade-dispatcher',
  'secret-manager://upgrade/workflow-dispatcher',
  '[\"opsmind-outbox-dispatcher\"]'::jsonb,
  '[\"outbox:dispatch\"]'::jsonb,
  'opsmind_dispatcher'
);
DO \$\$
DECLARE
  workflow_event_id uuid := public.opsmind_investigation_workflow_start_event_id(
    '70000000-0000-4000-8000-000000000001',
    '70000000-0000-4000-8000-000000000013'
  );
  request_digest bytea := digest(
    convert_to('legacy-v011-workflow-request', 'UTF8'),
    'sha256'
  );
  start_payload jsonb;
  start_payload_bytes bytea;
  start_payload_digest bytea;
BEGIN
  start_payload := jsonb_build_object(
    'organization_id', '70000000-0000-4000-8000-000000000001',
    'project_id', '70000000-0000-4000-8000-000000000003',
    'incident_id', '70000000-0000-4000-8000-000000000004',
    'run_id', '70000000-0000-4000-8000-000000000013',
    'actor_id', '70000000-0000-4000-8000-000000000002',
    'max_rounds', 2,
    'max_tool_calls', 0,
    'max_evidence_items', 1,
    'max_tokens', 100,
    'started_at', '2030-01-03T00:00:00Z',
    'deadline_at', '2030-01-03T00:02:00Z',
    'temporal_cluster_id', 'temporal-upgrade',
    'temporal_namespace', 'opsmind-upgrade',
    'workflow_id',
      'opsmind-investigation/70000000-0000-4000-8000-000000000001/'
        || '70000000-0000-4000-8000-000000000013',
    'workflow_type', 'opsmind-investigation-v1',
    'task_queue', 'opsmind-investigation-upgrade',
    'authorization_revision', 0,
    'request_digest', encode(request_digest, 'hex')
  );
  start_payload_bytes := convert_to(start_payload::text, 'UTF8');
  start_payload_digest := digest(start_payload_bytes, 'sha256');

  INSERT INTO investigation_workflow_bindings (
    organization_id, run_id, project_id, incident_id, actor_id,
    client_request_digest, start_payload_digest, start_event_id,
    temporal_cluster_id, temporal_namespace, workflow_id, workflow_type,
    task_queue, authorization_revision, started_at, deadline_at
  ) VALUES (
    '70000000-0000-4000-8000-000000000001',
    '70000000-0000-4000-8000-000000000013',
    '70000000-0000-4000-8000-000000000003',
    '70000000-0000-4000-8000-000000000004',
    '70000000-0000-4000-8000-000000000002',
    request_digest,
    start_payload_digest,
    workflow_event_id,
    'temporal-upgrade',
    'opsmind-upgrade',
    'opsmind-investigation/70000000-0000-4000-8000-000000000001/'
      || '70000000-0000-4000-8000-000000000013',
    'opsmind-investigation-v1',
    'opsmind-investigation-upgrade',
    0,
    '2030-01-03T00:00:00Z',
    '2030-01-03T00:02:00Z'
  );

  INSERT INTO outbox_events (
    event_id, organization_id, aggregate_type, aggregate_id,
    aggregate_sequence, event_type, schema_version, correlation_id,
    occurred_at, payload, payload_bytes, payload_digest, attempts, last_error
  ) VALUES (
    workflow_event_id,
    '70000000-0000-4000-8000-000000000001',
    'investigation-workflow',
    '70000000-0000-4000-8000-000000000013',
    1,
    'investigation.workflow-start.requested',
    '1',
    '70000000-0000-4000-8000-000000000013',
    '2030-01-03T00:00:00Z',
    start_payload,
    start_payload_bytes,
    start_payload_digest,
    1,
    'workflow.temporal-unavailable'
  );
END
\$\$;
"

migrate_to 12
version_twelve="$(query_upgrade_database "SELECT max(version::integer) FROM flyway_schema_history WHERE success;")"
workflow_claim_function_after_twelve="$(query_upgrade_database "
SELECT CASE
  WHEN to_regprocedure(
    'public.opsmind_claim_investigation_workflow_start(uuid,uuid,bigint)'
  ) IS NULL THEN 'ABSENT'
  ELSE 'PRESENT'
END;
")"
workflow_claim_owner_after_twelve="$(query_upgrade_database "
SELECT pg_get_userbyid(proowner)
FROM pg_proc
WHERE oid = 'public.opsmind_claim_investigation_workflow_start(uuid,uuid,bigint)'::regprocedure;
")"
workflow_claim_security_definer_after_twelve="$(query_upgrade_database "
SELECT CASE WHEN prosecdef THEN 'TRUE' ELSE 'FALSE' END
FROM pg_proc
WHERE oid = 'public.opsmind_claim_investigation_workflow_start(uuid,uuid,bigint)'::regprocedure;
")"
workflow_claim_dispatcher_execute_after_twelve="$(query_upgrade_database "
SELECT CASE WHEN has_function_privilege(
  'opsmind_dispatcher',
  'public.opsmind_claim_investigation_workflow_start(uuid,uuid,bigint)'::regprocedure,
  'EXECUTE'
) THEN 'GRANTED' ELSE 'REVOKED' END;
")"
workflow_claim_public_execute_after_twelve="$(query_upgrade_database "
SELECT CASE WHEN EXISTS (
  SELECT 1
  FROM pg_proc proc
  CROSS JOIN LATERAL aclexplode(
    COALESCE(proc.proacl, acldefault('f', proc.proowner))
  ) privilege
  WHERE proc.oid = 'public.opsmind_claim_investigation_workflow_start(uuid,uuid,bigint)'::regprocedure
    AND privilege.grantee = 0
    AND privilege.privilege_type = 'EXECUTE'
) THEN 'GRANTED' ELSE 'REVOKED' END;
")"
outbox_predecessor_function_after_twelve="$(query_upgrade_database "
SELECT CASE
  WHEN to_regprocedure(
    'public.opsmind_has_unpublished_outbox_predecessor(uuid,character varying,uuid,bigint)'
  ) IS NULL THEN 'ABSENT'
  ELSE 'PRESENT'
END;
")"
outbox_predecessor_owner_after_twelve="$(query_upgrade_database "
SELECT pg_get_userbyid(proowner)
FROM pg_proc
WHERE oid = 'public.opsmind_has_unpublished_outbox_predecessor(uuid,character varying,uuid,bigint)'::regprocedure;
")"
outbox_predecessor_security_definer_after_twelve="$(query_upgrade_database "
SELECT CASE WHEN prosecdef THEN 'TRUE' ELSE 'FALSE' END
FROM pg_proc
WHERE oid = 'public.opsmind_has_unpublished_outbox_predecessor(uuid,character varying,uuid,bigint)'::regprocedure;
")"
outbox_predecessor_dispatcher_execute_after_twelve="$(query_upgrade_database "
SELECT CASE WHEN has_function_privilege(
  'opsmind_dispatcher',
  'public.opsmind_has_unpublished_outbox_predecessor(uuid,character varying,uuid,bigint)'::regprocedure,
  'EXECUTE'
) THEN 'GRANTED' ELSE 'REVOKED' END;
")"
outbox_predecessor_public_execute_after_twelve="$(query_upgrade_database "
SELECT CASE WHEN EXISTS (
  SELECT 1
  FROM pg_proc proc
  CROSS JOIN LATERAL aclexplode(
    COALESCE(proc.proacl, acldefault('f', proc.proowner))
  ) privilege
  WHERE proc.oid = 'public.opsmind_has_unpublished_outbox_predecessor(uuid,character varying,uuid,bigint)'::regprocedure
    AND privilege.grantee = 0
    AND privilege.privilege_type = 'EXECUTE'
) THEN 'GRANTED' ELSE 'REVOKED' END;
")"
dispatcher_role_after_twelve="$(query_upgrade_database "
SELECT CASE WHEN rolcanlogin
  AND NOT rolsuper
  AND NOT rolbypassrls
  AND NOT rolinherit
  AND NOT EXISTS (
    SELECT 1
    FROM pg_auth_members membership
    WHERE membership.member = pg_roles.oid
  )
  AND NOT EXISTS (
    SELECT 1
    FROM pg_auth_members membership
    WHERE membership.roleid = pg_roles.oid
  )
THEN 'SAFE' ELSE 'UNSAFE' END
FROM pg_roles
WHERE rolname = 'opsmind_dispatcher';
")"
resolver_role_after_twelve="$(query_upgrade_database "
SELECT CASE WHEN NOT rolcanlogin
  AND NOT rolsuper
  AND NOT rolbypassrls
  AND NOT rolinherit
  AND NOT EXISTS (
    SELECT 1
    FROM pg_auth_members membership
    WHERE membership.member = pg_roles.oid
  )
  AND NOT EXISTS (
    SELECT 1
    FROM pg_auth_members membership
    JOIN pg_roles member_role ON member_role.oid = membership.member
    WHERE membership.roleid = pg_roles.oid
      AND (
        member_role.rolname <> session_user
        OR membership.admin_option
        OR NOT membership.inherit_option
        OR NOT membership.set_option
      )
  )
THEN 'SAFE' ELSE 'UNSAFE' END
FROM pg_roles
WHERE rolname = 'opsmind_dispatch_resolver';
")"
dispatcher_workflow_binding_privilege_after_twelve="$(query_upgrade_database "
SELECT CASE WHEN NOT EXISTS (
  SELECT 1
  FROM information_schema.role_table_grants
  WHERE grantee = 'opsmind_dispatcher'
    AND table_schema = 'public'
    AND table_name = 'investigation_workflow_bindings'
  UNION ALL
  SELECT 1
  FROM information_schema.column_privileges
  WHERE grantee = 'opsmind_dispatcher'
    AND table_schema = 'public'
    AND table_name = 'investigation_workflow_bindings'
) THEN 'REVOKED' ELSE 'GRANTED' END;
")"
dispatcher_inbox_privilege_after_twelve="$(query_upgrade_database "
SELECT CASE WHEN NOT EXISTS (
  SELECT 1
  FROM information_schema.role_table_grants
  WHERE grantee = 'opsmind_dispatcher'
    AND table_schema = 'public'
    AND table_name = 'inbox_events'
  UNION ALL
  SELECT 1
  FROM information_schema.column_privileges
  WHERE grantee = 'opsmind_dispatcher'
    AND table_schema = 'public'
    AND table_name = 'inbox_events'
) THEN 'REVOKED' ELSE 'GRANTED' END;
")"
dispatcher_workflow_exclusion_policy_after_twelve="$(query_upgrade_database "
SELECT CASE WHEN EXISTS (
  SELECT 1
  FROM pg_policy policy
  WHERE policy.polrelid = 'public.outbox_events'::regclass
    AND policy.polname = 'outbox_events_dispatcher_excludes_investigation_workflow_start'
    AND policy.polcmd = '*'
    AND policy.polpermissive = false
    AND 'opsmind_dispatcher'::regrole::oid = ANY(policy.polroles)
) THEN 'PRESENT' ELSE 'ABSENT' END;
")"
workflow_preflight_owner_after_twelve="$(query_upgrade_database "
SELECT pg_get_userbyid(proowner)
FROM pg_proc
WHERE oid = 'public.opsmind_preflight_investigation_workflow_start(uuid,uuid,uuid,bigint)'::regprocedure;
")"
workflow_tenant_selector_owner_after_twelve="$(query_upgrade_database "
SELECT pg_get_userbyid(proowner)
FROM pg_proc
WHERE oid = 'public.opsmind_list_investigation_workflow_start_tenants(integer)'::regprocedure;
")"
legacy_workflow_marker_after_twelve="$(query_upgrade_database "
SELECT count(*)
FROM outbox_events
WHERE organization_id = '70000000-0000-4000-8000-000000000001'
  AND aggregate_id = '70000000-0000-4000-8000-000000000013'
  AND event_type = 'investigation.workflow-start.requested'
  AND schema_version = '1'
  AND aggregate_type = 'investigation-workflow'
  AND aggregate_sequence = 1
  AND attempts = 1
  AND last_error = 'workflow.temporal-outcome-ambiguous'
  AND published_at IS NULL
  AND poisoned_at IS NULL;
")"
legacy_workflow_start_event_id_after_twelve="$(query_upgrade_database "
SELECT binding_row.start_event_id::text
FROM investigation_workflow_bindings binding_row
JOIN outbox_events event_row
  ON event_row.organization_id = binding_row.organization_id
 AND event_row.aggregate_id = binding_row.run_id
 AND event_row.event_id = binding_row.start_event_id
WHERE binding_row.organization_id = '70000000-0000-4000-8000-000000000001'
  AND binding_row.run_id = '70000000-0000-4000-8000-000000000013'
  AND event_row.event_type = 'investigation.workflow-start.requested'
  AND event_row.schema_version = '1'
  AND event_row.aggregate_type = 'investigation-workflow'
  AND event_row.aggregate_sequence = 1;
")"
if [[ ! "$legacy_workflow_start_event_id_after_twelve" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]]; then
  echo "Expected exactly one canonical lowercase workflow start event UUID." >&2
  exit 1
fi
legacy_workflow_claim_count_after_twelve="$(query_upgrade_database "
SET SESSION AUTHORIZATION opsmind_dispatcher;
SELECT count(*)
FROM public.opsmind_claim_investigation_workflow_start(
  '70000000-0000-4000-8000-000000000001',
  '70000000-0000-4000-8000-000000000017',
  300000
);
RESET SESSION AUTHORIZATION;
")"
legacy_workflow_preflight_after_twelve="$(query_upgrade_database "
SET SESSION AUTHORIZATION opsmind_dispatcher;
SELECT public.opsmind_preflight_investigation_workflow_start(
  '70000000-0000-4000-8000-000000000001',
  '${legacy_workflow_start_event_id_after_twelve}'::uuid,
  '70000000-0000-4000-8000-000000000017',
  600000
);
RESET SESSION AUTHORIZATION;
")"
legacy_workflow_park_after_twelve="$(query_upgrade_database "
SET SESSION AUTHORIZATION opsmind_dispatcher;
SELECT public.opsmind_settle_investigation_workflow_start(
  '70000000-0000-4000-8000-000000000001',
  '${legacy_workflow_start_event_id_after_twelve}'::uuid,
  '70000000-0000-4000-8000-000000000017',
  'RETRY',
  NULL,
  'workflow.reconciliation-required',
  100
);
RESET SESSION AUTHORIZATION;
")"
legacy_workflow_parked_after_twelve="$(query_upgrade_database "
SELECT count(*)
FROM outbox_events event_row
JOIN investigation_workflow_bindings binding_row
  ON binding_row.organization_id = event_row.organization_id
 AND binding_row.run_id = event_row.aggregate_id
 AND binding_row.start_event_id = event_row.event_id
WHERE event_row.organization_id = '70000000-0000-4000-8000-000000000001'
  AND event_row.aggregate_id = '70000000-0000-4000-8000-000000000013'
  AND event_row.last_error = 'workflow.reconciliation-required'
  AND event_row.published_at IS NULL
  AND event_row.poisoned_at IS NULL
  AND binding_row.status = 'PENDING';
")"
[[ "$version_twelve" == "12" ]]
[[ "$workflow_claim_function_after_twelve" == "PRESENT" ]]
[[ "$workflow_claim_owner_after_twelve" == "opsmind_dispatch_resolver" ]]
[[ "$workflow_claim_security_definer_after_twelve" == "TRUE" ]]
[[ "$workflow_claim_dispatcher_execute_after_twelve" == "GRANTED" ]]
[[ "$workflow_claim_public_execute_after_twelve" == "REVOKED" ]]
[[ "$outbox_predecessor_function_after_twelve" == "PRESENT" ]]
[[ "$outbox_predecessor_owner_after_twelve" == "opsmind_dispatch_resolver" ]]
[[ "$outbox_predecessor_security_definer_after_twelve" == "TRUE" ]]
[[ "$outbox_predecessor_dispatcher_execute_after_twelve" == "GRANTED" ]]
[[ "$outbox_predecessor_public_execute_after_twelve" == "REVOKED" ]]
[[ "$dispatcher_role_after_twelve" == "SAFE" ]]
[[ "$resolver_role_after_twelve" == "SAFE" ]]
[[ "$dispatcher_workflow_binding_privilege_after_twelve" == "REVOKED" ]]
[[ "$dispatcher_inbox_privilege_after_twelve" == "REVOKED" ]]
[[ "$dispatcher_workflow_exclusion_policy_after_twelve" == "PRESENT" ]]
[[ "$workflow_preflight_owner_after_twelve" == "opsmind_context_resolver" ]]
[[ "$workflow_tenant_selector_owner_after_twelve" == "opsmind_dispatch_resolver" ]]
[[ "$legacy_workflow_marker_after_twelve" == "1" ]]
[[ "$legacy_workflow_claim_count_after_twelve" == "1" ]]
[[ "$legacy_workflow_preflight_after_twelve" == "workflow.reconciliation-required" ]]
[[ "$legacy_workflow_park_after_twelve" == "workflow.retry-scheduled" ]]
[[ "$legacy_workflow_parked_after_twelve" == "1" ]]

query_upgrade_database "
DELETE FROM outbox_events
WHERE organization_id = '70000000-0000-4000-8000-000000000001'
  AND aggregate_id = '70000000-0000-4000-8000-000000000013';
DELETE FROM investigation_workflow_bindings
WHERE organization_id = '70000000-0000-4000-8000-000000000001'
  AND run_id = '70000000-0000-4000-8000-000000000013';
DELETE FROM service_accounts
WHERE id = '70000000-0000-4000-8000-000000000016';
"

cutover_block_output="${TMPDIR:-/tmp}/opsmind-phase9-cutover-block-${upgrade_database}.txt"
set +e
PGPASSWORD="$POSTGRES_PASSWORD" scripts/operations/run-investigation-workflow-cutover-inventory.sh \
  --no-password --no-psqlrc \
  --host "$PGHOST" --port "$PGPORT" --username "$POSTGRES_USER" \
  --dbname "$upgrade_database" \
  > "$cutover_block_output" 2>&1
cutover_block_status=$?
set -e
cat "$cutover_block_output"
cutover_expected_block=FAIL
if [[ "$cutover_block_status" == "3" ]] \
  && grep -Fqx 'FAILED: unresolved legacy investigation rows block Temporal admission.' "$cutover_block_output"; then
  cutover_expected_block=PASS
fi
[[ "$cutover_expected_block" == "PASS" ]]
grep -Fq 'FAILED: unresolved legacy investigation rows block Temporal admission.' "$cutover_block_output"
rm -f "$cutover_block_output"

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
PGPASSWORD="$POSTGRES_PASSWORD" scripts/operations/run-investigation-workflow-cutover-inventory.sh \
  --no-password --no-psqlrc \
  --host "$PGHOST" --port "$PGPORT" --username "$POSTGRES_USER" \
  --dbname "$upgrade_database"
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

migrate_to 13
version_thirteen="$(query_upgrade_database "
SELECT max(version::integer)
FROM flyway_schema_history
WHERE success;
")"
[[ "$version_thirteen" == "13" ]]
reconciliation_functions_after_thirteen="$(query_upgrade_database "
SELECT count(*)
FROM pg_proc procedure
JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
WHERE namespace.nspname = 'public'
  AND procedure.proname IN (
    'opsmind_claim_investigation_workflow_reconciliation',
    'opsmind_settle_investigation_workflow_reconciliation',
    'opsmind_get_investigation_workflow_reconciliation_status'
  )
  AND has_function_privilege(
    'opsmind_workflow_reconciler', procedure.oid, 'EXECUTE'
  );
")"
[[ "$reconciliation_functions_after_thirteen" == "3" ]]
reconciliation_trigger_public_denied_after_thirteen="$(query_upgrade_database "
SELECT NOT EXISTS (
  SELECT 1
  FROM pg_proc procedure
  CROSS JOIN LATERAL aclexplode(
    coalesce(procedure.proacl, acldefault('f', procedure.proowner))
  ) privilege
  WHERE procedure.oid =
    'public.opsmind_validate_investigation_workflow_binding_update()'::regprocedure
    AND privilege.grantee = 0
    AND privilege.privilege_type = 'EXECUTE'
);
")"
[[ "$reconciliation_trigger_public_denied_after_thirteen" == "t" ]]

printf 'VersionThirteen=%s\nReconciliationFunctionsAfterThirteen=%s\nReconciliationTriggerPublicDeniedAfterThirteen=%s\n' \
  "$version_thirteen" "$reconciliation_functions_after_thirteen" \
  "$reconciliation_trigger_public_denied_after_thirteen"

printf 'Database=%s\nVersionBefore=%s\nEvidenceTableBefore=%s\nVersionSeven=%s\nEvidenceTableAfterSeven=%s\nVersionEight=%s\nVersionNine=%s\nVersionTen=%s\nVersionEleven=%s\nVersionTwelve=%s\nWorkflowBindingTableAfterTen=%s\nWorkflowEventFunctionAfterTen=%s\nWorkflowPreflightFunctionAfterEleven=%s\nWorkflowSettlementFunctionAfterEleven=%s\nWorkflowTerminalizerFunctionAfterEleven=%s\nWorkflowSettlementOwnerAfterEleven=%s\nWorkflowClaimFunctionAfterTwelve=%s\nWorkflowClaimOwnerAfterTwelve=%s\nWorkflowClaimSecurityDefinerAfterTwelve=%s\nWorkflowClaimDispatcherExecuteAfterTwelve=%s\nWorkflowClaimPublicExecuteAfterTwelve=%s\nOutboxPredecessorFunctionAfterTwelve=%s\nOutboxPredecessorOwnerAfterTwelve=%s\nOutboxPredecessorSecurityDefinerAfterTwelve=%s\nOutboxPredecessorDispatcherExecuteAfterTwelve=%s\nOutboxPredecessorPublicExecuteAfterTwelve=%s\nDispatcherRoleAfterTwelve=%s\nResolverRoleAfterTwelve=%s\nDispatcherWorkflowBindingPrivilegeAfterTwelve=%s\nDispatcherInboxPrivilegeAfterTwelve=%s\nDispatcherWorkflowExclusionPolicyAfterTwelve=%s\nWorkflowPreflightOwnerAfterTwelve=%s\nWorkflowTenantSelectorOwnerAfterTwelve=%s\nLegacyWorkflowMarkerAfterTwelve=%s\nLegacyWorkflowClaimCountAfterTwelve=%s\nLegacyWorkflowPreflightAfterTwelve=%s\nLegacyWorkflowParkAfterTwelve=%s\nLegacyWorkflowParkedAfterTwelve=%s\nLegacyTerminalRunsAfterTen=%s\nLegacyBindingCountAfterTen=%s\nNonterminalOrphansAfterTen=%s\nCutoverExpectedBlock=%s\nNonterminalOrphansAfterReconciliation=%s\nLegacyPayloadDigestStable=%s\nRollingLegacyWriteCount=%s\nInvalidAbstainRejected=%s\nUpgradeResult=PASS\n' \
  "$upgrade_database" "$version_before" "$table_before" \
  "$version_seven" "$table_after_seven" "$version_eight" "$version_nine" \
  "$version_ten" "$version_eleven" "$version_twelve" "$binding_table_after_ten" \
  "$workflow_event_function_after_ten" "$workflow_preflight_function_after_eleven" \
  "$workflow_settlement_function_after_eleven" "$workflow_terminalizer_function_after_eleven" \
  "$workflow_settlement_owner_after_eleven" \
  "$workflow_claim_function_after_twelve" "$workflow_claim_owner_after_twelve" \
  "$workflow_claim_security_definer_after_twelve" \
  "$workflow_claim_dispatcher_execute_after_twelve" \
  "$workflow_claim_public_execute_after_twelve" \
  "$outbox_predecessor_function_after_twelve" \
  "$outbox_predecessor_owner_after_twelve" \
  "$outbox_predecessor_security_definer_after_twelve" \
  "$outbox_predecessor_dispatcher_execute_after_twelve" \
  "$outbox_predecessor_public_execute_after_twelve" \
  "$dispatcher_role_after_twelve" "$resolver_role_after_twelve" \
  "$dispatcher_workflow_binding_privilege_after_twelve" \
  "$dispatcher_inbox_privilege_after_twelve" \
  "$dispatcher_workflow_exclusion_policy_after_twelve" \
  "$workflow_preflight_owner_after_twelve" \
  "$workflow_tenant_selector_owner_after_twelve" \
  "$legacy_workflow_marker_after_twelve" \
  "$legacy_workflow_claim_count_after_twelve" \
  "$legacy_workflow_preflight_after_twelve" \
  "$legacy_workflow_park_after_twelve" \
  "$legacy_workflow_parked_after_twelve" \
  "$legacy_terminal_runs_after_ten" "$legacy_binding_count_after_ten" \
  "$nonterminal_orphans_after_ten" "$cutover_expected_block" \
  "$nonterminal_orphans_after_reconciliation" \
  "$([[ "$legacy_digest_after" == "$legacy_digest_before" ]] && printf true || printf false)" \
  "$rolling_legacy_write_count" "$invalid_abstain_rejected"
