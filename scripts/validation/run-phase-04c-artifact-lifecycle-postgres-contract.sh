#!/usr/bin/env bash
set -euo pipefail

if [[ "${OPSMIND_EPHEMERAL_DB:-}" != "true" ]]; then
  echo "OPSMIND_EPHEMERAL_DB=true is required for the disposable lifecycle proof." >&2
  exit 2
fi
for required_name in \
  PGHOST PGPORT PGDATABASE POSTGRES_USER POSTGRES_PASSWORD \
  POSTGRES_APP_USER POSTGRES_APP_PASSWORD; do
  if [[ -z "${!required_name:-}" ]]; then
    echo "${required_name} is required." >&2
    exit 2
  fi
done
if [[ "$POSTGRES_APP_USER" != "opsmind_app" ]]; then
  echo "The artifact lifecycle contract requires the fixed opsmind_app login." >&2
  exit 2
fi

upgrade_database="${OPSMIND_PHASE4C_ARTIFACT_LIFECYCLE_DATABASE:-opsmind_phase4c_artifact_lifecycle}"
if [[ ! "$upgrade_database" =~ ^opsmind_phase4c_artifact_lifecycle(_[a-z0-9_]+)?$ ]]; then
  echo "Lifecycle database must use the opsmind_phase4c_artifact_lifecycle prefix." >&2
  exit 2
fi
if [[ "$upgrade_database" == "$PGDATABASE" ]]; then
  echo "Lifecycle database must differ from the primary database." >&2
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
    echo "ContractCleanup=PASS"
  else
    echo "ContractCleanup=BLOCK" >&2
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

admin_query() {
  local sql="$1"
  PGPASSWORD="$POSTGRES_PASSWORD" psql --no-password --no-psqlrc \
    --host "$PGHOST" --port "$PGPORT" --username "$POSTGRES_USER" \
    --dbname "$upgrade_database" --quiet --tuples-only --no-align \
    --set ON_ERROR_STOP=1 --command "$sql" | tr -d '\r'
}

admin_sql() {
  PGPASSWORD="$POSTGRES_PASSWORD" psql --no-password --no-psqlrc \
    --host "$PGHOST" --port "$PGPORT" --username "$POSTGRES_USER" \
    --dbname "$upgrade_database" --set ON_ERROR_STOP=1 "$@"
}

app_sql() {
  PGPASSWORD="$POSTGRES_APP_PASSWORD" psql --no-password --no-psqlrc \
    --host "$PGHOST" --port "$PGPORT" --username "$POSTGRES_APP_USER" \
    --dbname "$upgrade_database" --set ON_ERROR_STOP=1 "$@"
}

migrate_to 18
before_capability="$(admin_query "
SELECT CASE WHEN
  (SELECT max(version::integer) FROM flyway_schema_history WHERE success) = 18
  AND to_regprocedure(
    'public.opsmind_transition_evidence_artifact(uuid,uuid,uuid,uuid,uuid,bigint,bytea,character varying,bigint,character varying,timestamp with time zone)'
  ) IS NULL
  AND NOT has_table_privilege('opsmind_app', 'public.evidence_artifacts', 'UPDATE')
THEN 'PASS' ELSE 'BLOCK' END;")"
[[ "$before_capability" == "PASS" ]]
echo "LifecycleV018Boundary=PASS"

migrate_to 19
after_capability="$(admin_query "
SELECT CASE WHEN
  (SELECT max(version::integer) FROM flyway_schema_history WHERE success) = 19
  AND has_function_privilege(
    'opsmind_app',
    'public.opsmind_transition_evidence_artifact(uuid,uuid,uuid,uuid,uuid,bigint,bytea,character varying,bigint,character varying,timestamp with time zone)',
    'EXECUTE'
  )
  AND NOT has_table_privilege('opsmind_app', 'public.evidence_artifacts', 'UPDATE')
THEN 'PASS' ELSE 'BLOCK' END;")"
[[ "$after_capability" == "PASS" ]]
echo "LifecycleV019Capability=PASS"
echo "DirectRuntimeMutation=REVOKED"

admin_sql <<'SQL'
BEGIN;
SET LOCAL session_replication_role = replica;
INSERT INTO organizations (id, slug, name) VALUES
  ('a1900000-0000-4000-8000-000000000001', 'artifact-v19', 'Artifact V19');
INSERT INTO platform_users (id, issuer, subject, display_name) VALUES (
  'a1900000-0000-4000-8000-000000000002',
  'https://artifact-v19.opsmind.invalid',
  'artifact-v19-operator',
  'Artifact V19 operator'
);
INSERT INTO organization_memberships (organization_id, user_id, role) VALUES (
  'a1900000-0000-4000-8000-000000000001',
  'a1900000-0000-4000-8000-000000000002',
  'SRE'
);
INSERT INTO projects (id, organization_id, slug, name) VALUES (
  'a1900000-0000-4000-8000-000000000003',
  'a1900000-0000-4000-8000-000000000001',
  'artifact-api', 'Artifact API'
);
INSERT INTO project_memberships (organization_id, project_id, user_id, role) VALUES (
  'a1900000-0000-4000-8000-000000000001',
  'a1900000-0000-4000-8000-000000000003',
  'a1900000-0000-4000-8000-000000000002', 'SRE'
);
INSERT INTO incidents (
  id, organization_id, project_id, title, description, severity, status,
  created_by, updated_by, created_at, updated_at, version
) VALUES (
  'a1900000-0000-4000-8000-000000000004',
  'a1900000-0000-4000-8000-000000000001',
  'a1900000-0000-4000-8000-000000000003',
  'Artifact lifecycle proof', 'Proves V019 runtime capability.',
  'SEV2', 'OPEN',
  'a1900000-0000-4000-8000-000000000002',
  'a1900000-0000-4000-8000-000000000002',
  '2035-01-01T00:00:00Z', '2035-01-01T00:00:00Z', 0
);
INSERT INTO investigation_runs (
  run_id, organization_id, project_id, incident_id, actor_id, status,
  max_rounds, max_tool_calls, max_evidence_items, max_tokens, event_count,
  started_at, deadline_at
) VALUES (
  'a1900000-0000-4000-8000-000000000005',
  'a1900000-0000-4000-8000-000000000001',
  'a1900000-0000-4000-8000-000000000003',
  'a1900000-0000-4000-8000-000000000004',
  'a1900000-0000-4000-8000-000000000002',
  'CREATED', 2, 0, 2, 100, 1,
  '2035-01-01T00:00:00Z', '2035-01-01T00:02:00Z'
);
SELECT public.opsmind_set_tenant_context(
  'a1900000-0000-4000-8000-000000000001',
  'a1900000-0000-4000-8000-000000000002'
);
INSERT INTO evidence_artifacts (
  artifact_id, organization_id, project_id, incident_id, run_id, actor_id,
  idempotency_key, source_type, source_identity, source_version,
  data_classification, expected_content_digest, expected_byte_count,
  authorization_epoch, retention_class, residency_class, deletion_class,
  storage_key, lifecycle_state, lifecycle_version, storage_generation,
  storage_version_reference, encryption_metadata_reference,
  upload_attempt_id, upload_lease_expires_at, upload_attempt_count,
  created_at, lifecycle_updated_at
) VALUES
  (
    public.opsmind_evidence_artifact_id(
      'a1900000-0000-4000-8000-000000000001',
      'a1900000-0000-4000-8000-000000000005',
      'a1900000-0000-4000-8000-000000000006'
    ),
    'a1900000-0000-4000-8000-000000000001',
    'a1900000-0000-4000-8000-000000000003',
    'a1900000-0000-4000-8000-000000000004',
    'a1900000-0000-4000-8000-000000000005',
    'a1900000-0000-4000-8000-000000000002',
    'a1900000-0000-4000-8000-000000000006',
    'log', 'loki:artifact-v19-success', 'v1', 'internal',
    decode(repeat('ab', 32), 'hex'), 14, 7,
    'evidence-90d', 'singapore', 'delete-within-24h',
    'artifacts/v1/a1900000-0000-4000-8000-000000000001/'
      || public.opsmind_evidence_artifact_id(
        'a1900000-0000-4000-8000-000000000001',
        'a1900000-0000-4000-8000-000000000005',
        'a1900000-0000-4000-8000-000000000006'
      )::text || '/' || repeat('ab', 32),
    'STORED', 2, 1, 'version-1', 'aws-kms-profile-v1',
    'a1900000-0000-4000-8000-000000000007',
    clock_timestamp() + interval '1 minute', 1,
    clock_timestamp() - interval '1 minute', clock_timestamp() - interval '30 seconds'
  ),
  (
    public.opsmind_evidence_artifact_id(
      'a1900000-0000-4000-8000-000000000001',
      'a1900000-0000-4000-8000-000000000005',
      'a1900000-0000-4000-8000-000000000016'
    ),
    'a1900000-0000-4000-8000-000000000001',
    'a1900000-0000-4000-8000-000000000003',
    'a1900000-0000-4000-8000-000000000004',
    'a1900000-0000-4000-8000-000000000005',
    'a1900000-0000-4000-8000-000000000002',
    'a1900000-0000-4000-8000-000000000016',
    'log', 'loki:artifact-v19-rollback', 'v1', 'internal',
    decode(repeat('ac', 32), 'hex'), 14, 7,
    'evidence-90d', 'singapore', 'delete-within-24h',
    'artifacts/v1/a1900000-0000-4000-8000-000000000001/'
      || public.opsmind_evidence_artifact_id(
        'a1900000-0000-4000-8000-000000000001',
        'a1900000-0000-4000-8000-000000000005',
        'a1900000-0000-4000-8000-000000000016'
      )::text || '/' || repeat('ac', 32),
    'STORED', 2, 1, 'version-1', 'aws-kms-profile-v1',
    'a1900000-0000-4000-8000-000000000017',
    clock_timestamp() + interval '1 minute', 1,
    clock_timestamp() - interval '1 minute', clock_timestamp() - interval '30 seconds'
  );
COMMIT;
SQL

app_sql <<'SQL'
BEGIN;
SELECT public.opsmind_set_tenant_context(
  'a1900000-0000-4000-8000-000000000001',
  'a1900000-0000-4000-8000-000000000002'
);
DO $transition$
DECLARE
  organization_id constant uuid := 'a1900000-0000-4000-8000-000000000001';
  project_id constant uuid := 'a1900000-0000-4000-8000-000000000003';
  incident_id constant uuid := 'a1900000-0000-4000-8000-000000000004';
  run_id constant uuid := 'a1900000-0000-4000-8000-000000000005';
  actor_id constant uuid := 'a1900000-0000-4000-8000-000000000002';
  artifact_id constant uuid := public.opsmind_evidence_artifact_id(
    organization_id, run_id, 'a1900000-0000-4000-8000-000000000006'
  );
  occurred_at constant timestamptz := clock_timestamp();
  event_id uuid;
BEGIN
  IF NOT public.opsmind_transition_evidence_artifact(
    organization_id, project_id, incident_id, artifact_id, actor_id, 7,
    decode(repeat('ab', 32), 'hex'), 'STORED', 2, 'SCANNING', occurred_at
  ) THEN
    RAISE EXCEPTION 'lifecycle capability did not update the authoritative row';
  END IF;
  event_id := public.opsmind_evidence_artifact_control_event_id(
    organization_id, artifact_id, 3
  );
  INSERT INTO evidence_artifact_events (
    event_id, organization_id, project_id, incident_id, run_id, artifact_id,
    actor_id, lifecycle_version, lifecycle_from_state, lifecycle_to_state,
    occurred_at, audit_event_id, upload_attempt_id
  ) VALUES (
    event_id, organization_id, project_id, incident_id, run_id, artifact_id,
    actor_id, 3, 'STORED', 'SCANNING', occurred_at, event_id, NULL
  );
  INSERT INTO audit_events (
    event_id, organization_id, actor_id, action, resource_type, resource_id,
    correlation_id, occurred_at, payload, schema_version
  ) VALUES (
    event_id, organization_id, actor_id, 'ARTIFACT_LIFECYCLE_CHANGED',
    'evidence_artifact', artifact_id::text, artifact_id, occurred_at,
    jsonb_build_object(
      'eventId', event_id,
      'organizationId', organization_id,
      'projectId', project_id,
      'incidentId', incident_id,
      'runId', run_id,
      'artifactId', artifact_id,
      'actorId', actor_id,
      'lifecycleVersion', 3,
      'lifecycleState', 'SCANNING',
      'fromState', 'STORED',
      'toState', 'SCANNING',
      'occurredAt', occurred_at,
      'reason', 'contract.proof'
    ),
    'evidence-artifact-audit-v1'
  );
END
$transition$;
COMMIT;
SQL

persisted_contract="$(admin_query "
SELECT CASE WHEN
  (SELECT lifecycle_state = 'SCANNING' AND lifecycle_version = 3
     FROM evidence_artifacts
    WHERE artifact_id = public.opsmind_evidence_artifact_id(
      'a1900000-0000-4000-8000-000000000001',
      'a1900000-0000-4000-8000-000000000005',
      'a1900000-0000-4000-8000-000000000006'
    ))
  AND (SELECT count(*) = 1 FROM evidence_artifact_events
        WHERE artifact_id = public.opsmind_evidence_artifact_id(
          'a1900000-0000-4000-8000-000000000001',
          'a1900000-0000-4000-8000-000000000005',
          'a1900000-0000-4000-8000-000000000006'
        )
          AND lifecycle_from_state = 'STORED' AND lifecycle_to_state = 'SCANNING')
  AND (SELECT count(*) = 1 FROM audit_events
        WHERE correlation_id = public.opsmind_evidence_artifact_id(
          'a1900000-0000-4000-8000-000000000001',
          'a1900000-0000-4000-8000-000000000005',
          'a1900000-0000-4000-8000-000000000006'
        )
          AND action = 'ARTIFACT_LIFECYCLE_CHANGED')
THEN 'PASS' ELSE 'BLOCK' END;")"
[[ "$persisted_contract" == "PASS" ]]
echo "LifecycleMetadataEventAuditAtomicity=PASS"

set +e
rollback_output="$(app_sql --quiet --command "
BEGIN;
SELECT public.opsmind_set_tenant_context(
  'a1900000-0000-4000-8000-000000000001',
  'a1900000-0000-4000-8000-000000000002'
);
SELECT public.opsmind_transition_evidence_artifact(
  'a1900000-0000-4000-8000-000000000001',
  'a1900000-0000-4000-8000-000000000003',
  'a1900000-0000-4000-8000-000000000004',
  public.opsmind_evidence_artifact_id(
    'a1900000-0000-4000-8000-000000000001',
    'a1900000-0000-4000-8000-000000000005',
    'a1900000-0000-4000-8000-000000000016'
  ),
  'a1900000-0000-4000-8000-000000000002',
  7, decode(repeat('ac', 32), 'hex'), 'STORED', 2, 'SCANNING', clock_timestamp()
);
COMMIT;" 2>&1)"
rollback_status=$?
set -e
if [[ "$rollback_status" -eq 0 ]] \
  || ! grep -q "artifact lifecycle metadata requires its control event and audit row" \
    <<<"$rollback_output"; then
  echo "MissingEventAuditRollback=BLOCK" >&2
  exit 1
fi
rollback_state="$(admin_query "
SELECT lifecycle_state || '|' || lifecycle_version
  FROM evidence_artifacts
 WHERE artifact_id = public.opsmind_evidence_artifact_id(
   'a1900000-0000-4000-8000-000000000001',
   'a1900000-0000-4000-8000-000000000005',
   'a1900000-0000-4000-8000-000000000016'
 );")"
[[ "$rollback_state" == "STORED|2" ]]
echo "MissingEventAuditRollback=PASS"
echo "ArtifactLifecyclePostgresContractResult=PASS"
