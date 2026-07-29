#!/usr/bin/env bash
set -euo pipefail

if [[ "${OPSMIND_EPHEMERAL_DB:-}" != "true" ]]; then
  echo "OPSMIND_EPHEMERAL_DB=true is required for the disposable artifact object proof." >&2
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
  echo "The artifact object contract requires the fixed opsmind_app login." >&2
  exit 2
fi

upgrade_database="${OPSMIND_PHASE4C_ARTIFACT_OBJECT_UPGRADE_DATABASE:-opsmind_phase4c_artifact_object_upgrade}"
if [[ ! "$upgrade_database" =~ ^opsmind_phase4c_artifact_object_upgrade(_[a-z0-9_]+)?$ ]]; then
  echo "Artifact object database must use the opsmind_phase4c_artifact_object_upgrade prefix." >&2
  exit 2
fi
if [[ "$upgrade_database" == "$PGDATABASE" ]]; then
  echo "Artifact object upgrade database must differ from the primary database." >&2
  exit 2
fi

platform_jar="${OPSMIND_PLATFORM_JAR:-services/platform-api/target/platform-api.jar}"
if [[ ! -f "$platform_jar" ]]; then
  echo "Packaged Platform API JAR is required: ${platform_jar}" >&2
  exit 2
fi

database_created=false
claim_one_log=""
claim_two_log=""
cleanup() {
  local original_status=$?
  local cleanup_status=0
  trap - EXIT
  if [[ -n "$claim_one_log" ]]; then rm -f -- "$claim_one_log"; fi
  if [[ -n "$claim_two_log" ]]; then rm -f -- "$claim_two_log"; fi
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

primary_query() {
  local sql="$1"
  PGPASSWORD="$POSTGRES_PASSWORD" psql --no-password --no-psqlrc \
    --host "$PGHOST" --port "$PGPORT" --username "$POSTGRES_USER" \
    --dbname "$PGDATABASE" --quiet --tuples-only --no-align \
    --set ON_ERROR_STOP=1 --command "$sql" | tr -d '\r'
}

fresh_version="$(primary_query \
  "SELECT max(version::integer) FROM flyway_schema_history WHERE success;")"
if [[ "$fresh_version" != "15" ]]; then
  echo "FreshPrimaryMigration expected=15 actual=${fresh_version}" >&2
  exit 1
fi
echo "FreshPrimaryMigration=PASS"

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
    --dbname "$upgrade_database" --quiet --set ON_ERROR_STOP=1
}

app_query() {
  local sql="$1"
  PGPASSWORD="$POSTGRES_APP_PASSWORD" psql --no-password --no-psqlrc \
    --host "$PGHOST" --port "$PGPORT" --username "$POSTGRES_APP_USER" \
    --dbname "$upgrade_database" --quiet --tuples-only --no-align \
    --set ON_ERROR_STOP=1 --command "$sql" | tr -d '\r'
}

app_sql() {
  PGPASSWORD="$POSTGRES_APP_PASSWORD" psql --no-password --no-psqlrc \
    --host "$PGHOST" --port "$PGPORT" --username "$POSTGRES_APP_USER" \
    --dbname "$upgrade_database" --quiet --set ON_ERROR_STOP=1
}

expect_app_failure() {
  local label="$1"
  local sql="$2"
  if app_query "$sql" >/dev/null 2>&1; then
    echo "${label}=BLOCK" >&2
    exit 1
  fi
  echo "${label}=PASS"
}

claim_once() {
  local attempt_id="$1"
  local lease_ms="$2"
  app_query "
BEGIN;
SELECT public.opsmind_set_tenant_context(
  'a1500000-0000-4000-8000-000000000001',
  'a1500000-0000-4000-8000-000000000002'
);
SELECT upload_attempt_id, probe_required
  FROM public.opsmind_claim_evidence_artifact_upload(
    'a1500000-0000-4000-8000-000000000001',
    'a1500000-0000-4000-8000-000000000003',
    'a1500000-0000-4000-8000-000000000004',
    'a1500000-0000-4000-8000-000000000005',
    public.opsmind_evidence_artifact_id(
      'a1500000-0000-4000-8000-000000000001',
      'a1500000-0000-4000-8000-000000000005',
      'a1500000-0000-4000-8000-000000000006'
    ),
    '${attempt_id}', 1, ${lease_ms}
  );
COMMIT;"
}

migrate_to 14
version_before="$(admin_query \
  "SELECT max(version::integer) FROM flyway_schema_history WHERE success;")"
attempt_table_before="$(admin_query \
  "SELECT CASE WHEN to_regclass('public.evidence_artifact_upload_attempts') IS NULL THEN 'ABSENT' ELSE 'PRESENT' END;")"
[[ "$version_before" == "14" ]]
[[ "$attempt_table_before" == "ABSENT" ]]

admin_sql <<'SQL'
BEGIN;
SET LOCAL session_replication_role = replica;
INSERT INTO organizations (id, slug, name) VALUES
  ('a1500000-0000-4000-8000-000000000001', 'artifact-v15', 'Artifact V15');
INSERT INTO platform_users (id, issuer, subject, display_name) VALUES (
  'a1500000-0000-4000-8000-000000000002',
  'https://artifact-v15.opsmind.invalid',
  'artifact-v15-operator',
  'Artifact V15 operator'
);
INSERT INTO organization_memberships (organization_id, user_id, role) VALUES (
  'a1500000-0000-4000-8000-000000000001',
  'a1500000-0000-4000-8000-000000000002',
  'SRE'
);
INSERT INTO projects (id, organization_id, slug, name) VALUES (
  'a1500000-0000-4000-8000-000000000003',
  'a1500000-0000-4000-8000-000000000001',
  'artifact-api',
  'Artifact API'
);
INSERT INTO project_memberships (organization_id, project_id, user_id, role) VALUES (
  'a1500000-0000-4000-8000-000000000001',
  'a1500000-0000-4000-8000-000000000003',
  'a1500000-0000-4000-8000-000000000002',
  'SRE'
);
INSERT INTO incidents (
  id, organization_id, project_id, title, description, severity, status,
  created_by, updated_by, created_at, updated_at, version
) VALUES (
  'a1500000-0000-4000-8000-000000000004',
  'a1500000-0000-4000-8000-000000000001',
  'a1500000-0000-4000-8000-000000000003',
  'Artifact fencing proof',
  'Proves bounded upload settlement and audit atomicity.',
  'SEV2', 'OPEN',
  'a1500000-0000-4000-8000-000000000002',
  'a1500000-0000-4000-8000-000000000002',
  '2035-01-01T00:00:00Z', '2035-01-01T00:00:00Z', 0
);
INSERT INTO investigation_runs (
  run_id, organization_id, project_id, incident_id, actor_id, status,
  max_rounds, max_tool_calls, max_evidence_items, max_tokens, event_count,
  started_at, deadline_at
) VALUES (
  'a1500000-0000-4000-8000-000000000005',
  'a1500000-0000-4000-8000-000000000001',
  'a1500000-0000-4000-8000-000000000003',
  'a1500000-0000-4000-8000-000000000004',
  'a1500000-0000-4000-8000-000000000002',
  'CREATED', 2, 0, 1, 100, 1,
  '2035-01-01T00:00:00Z', '2035-01-01T00:02:00Z'
);
INSERT INTO investigation_run_events (
  event_id, organization_id, project_id, incident_id, run_id, sequence_no,
  event_type, actor_id, occurred_at, payload
) VALUES (
  'a1500000-0000-4000-8000-000000000007',
  'a1500000-0000-4000-8000-000000000001',
  'a1500000-0000-4000-8000-000000000003',
  'a1500000-0000-4000-8000-000000000004',
  'a1500000-0000-4000-8000-000000000005',
  1, 'RUN_STARTED',
  'a1500000-0000-4000-8000-000000000002',
  '2035-01-01T00:00:00Z', '{}'::jsonb
);
COMMIT;
SQL

app_sql <<'SQL'
DO $artifact$
DECLARE
  organization_id constant uuid := 'a1500000-0000-4000-8000-000000000001';
  project_id constant uuid := 'a1500000-0000-4000-8000-000000000003';
  incident_id constant uuid := 'a1500000-0000-4000-8000-000000000004';
  run_id constant uuid := 'a1500000-0000-4000-8000-000000000005';
  actor_id constant uuid := 'a1500000-0000-4000-8000-000000000002';
  idempotency_key constant uuid := 'a1500000-0000-4000-8000-000000000006';
  occurred_at constant timestamptz := '2035-01-01T00:00:00Z';
  expected_digest constant bytea := decode(repeat('ab', 32), 'hex');
  artifact_id uuid;
  event_id uuid;
BEGIN
  PERFORM public.opsmind_set_tenant_context(organization_id, actor_id);
  artifact_id := public.opsmind_evidence_artifact_id(
    organization_id, run_id, idempotency_key
  );
  event_id := public.opsmind_evidence_artifact_initial_event_id(
    organization_id, artifact_id
  );
  INSERT INTO evidence_artifacts (
    artifact_id, organization_id, project_id, incident_id, run_id, actor_id,
    idempotency_key, source_type, source_identity, source_version,
    data_classification, expected_content_digest, expected_byte_count,
    authorization_epoch, retention_class, residency_class, deletion_class,
    storage_key, lifecycle_state, lifecycle_version, storage_generation,
    upload_attempt_count, created_at, lifecycle_updated_at
  ) VALUES (
    artifact_id, organization_id, project_id, incident_id, run_id, actor_id,
    idempotency_key, 'metric', 'prometheus:artifact-v15', 'v1',
    'redacted-metrics', expected_digest, 14, 0, 'evidence-90d',
    'singapore', 'delete-within-24h',
    'artifacts/v1/' || organization_id::text || '/' || artifact_id::text
      || '/' || encode(expected_digest, 'hex'),
    'PENDING_UPLOAD', 1, 0, 0, occurred_at, occurred_at
  );
  INSERT INTO evidence_artifact_events (
    event_id, organization_id, project_id, incident_id, run_id, artifact_id,
    actor_id, lifecycle_version, lifecycle_from_state, lifecycle_to_state,
    occurred_at, audit_event_id
  ) VALUES (
    event_id, organization_id, project_id, incident_id, run_id, artifact_id,
    actor_id, 1, NULL, 'PENDING_UPLOAD', occurred_at, event_id
  );
  INSERT INTO audit_events (
    event_id, organization_id, actor_id, action, resource_type, resource_id,
    correlation_id, occurred_at, payload, schema_version
  ) VALUES (
    event_id, organization_id, actor_id, 'ARTIFACT_PENDING_UPLOAD',
    'evidence_artifact', artifact_id::text, artifact_id, occurred_at,
    jsonb_build_object(
      'eventId', event_id,
      'organizationId', organization_id,
      'projectId', project_id,
      'incidentId', incident_id,
      'runId', run_id,
      'artifactId', artifact_id,
      'actorId', actor_id,
      'lifecycleVersion', 1,
      'lifecycleState', 'PENDING_UPLOAD',
      'contentDigest', 'sha256:' || encode(expected_digest, 'hex'),
      'byteCount', 14,
      'dataClassification', 'redacted-metrics',
      'retentionClass', 'evidence-90d',
      'occurredAt', occurred_at
    ),
    'evidence-artifact-audit-v1'
  );
END
$artifact$;
SQL

migrate_to 15
schema_result="$(admin_query "
SELECT CASE WHEN
  (SELECT max(version::integer) FROM flyway_schema_history WHERE success) = 15
  AND to_regclass('public.evidence_artifact_upload_attempts') IS NOT NULL
  AND EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = 'public'
       AND table_name = 'evidence_artifacts'
       AND column_name = 'storage_version_reference'
  )
  AND EXISTS (
    SELECT 1 FROM pg_class
     WHERE oid = 'public.evidence_artifact_upload_attempts'::regclass
       AND relrowsecurity AND relforcerowsecurity
  )
  AND has_function_privilege(
    'opsmind_app',
    'public.opsmind_claim_evidence_artifact_upload(uuid,uuid,uuid,uuid,uuid,uuid,bigint,bigint)',
    'EXECUTE'
  )
  AND has_function_privilege(
    'opsmind_app',
    'public.opsmind_settle_evidence_artifact_upload(uuid,uuid,uuid,uuid,uuid,uuid,bigint,character varying,bytea,bigint,character varying,character varying,character varying)',
    'EXECUTE'
  )
  AND NOT has_table_privilege(
    'opsmind_app', 'public.evidence_artifact_upload_attempts', 'SELECT'
  )
  AND NOT has_table_privilege('opsmind_app', 'public.evidence_artifacts', 'UPDATE')
  AND EXISTS (
    SELECT 1 FROM evidence_artifacts
     WHERE lifecycle_state = 'PENDING_UPLOAD'
       AND lifecycle_version = 1
       AND storage_version_reference IS NULL
  )
THEN 'PASS' ELSE 'BLOCK' END;")"
[[ "$schema_result" == "PASS" ]]
echo "ArtifactObjectUpgrade=PASS"
echo "V014MetadataPreserved=PASS"
echo "ArtifactAttemptRls=PASS"
echo "ArtifactCapabilityGrants=PASS"

claim_one_log="$(mktemp)"
claim_two_log="$(mktemp)"
set +e
claim_once "a1510000-0000-4000-8000-000000000001" 5000 \
  >"$claim_one_log" 2>&1 &
claim_one_pid=$!
claim_once "a1520000-0000-4000-8000-000000000001" 5000 \
  >"$claim_two_log" 2>&1 &
claim_two_pid=$!
wait "$claim_one_pid"
claim_one_status=$?
wait "$claim_two_pid"
claim_two_status=$?
set -e
if [[ "$claim_one_status" -eq "$claim_two_status" ]]; then
  echo "ConcurrentClaimSingleWinner=BLOCK" >&2
  cat "$claim_one_log" "$claim_two_log" >&2
  exit 1
fi
winner_attempt="$(admin_query "
SELECT upload_attempt_id
  FROM evidence_artifact_upload_attempts
 WHERE status = 'CLAIMED';")"
if [[ "$winner_attempt" != "a1510000-0000-4000-8000-000000000001" \
   && "$winner_attempt" != "a1520000-0000-4000-8000-000000000001" ]]; then
  echo "ConcurrentClaimWinnerIdentity=BLOCK" >&2
  exit 1
fi
echo "ConcurrentClaimSingleWinner=PASS"
echo "ActiveLeaseDenial=PASS"

admin_query "SELECT pg_sleep(5.2);" >/dev/null
retry_output="$(claim_once "a1530000-0000-4000-8000-000000000001" 300000)"
if ! grep -q "a1530000-0000-4000-8000-000000000001|t" <<<"$retry_output"; then
  echo "ExpiredClaimProbeFence=BLOCK" >&2
  exit 1
fi
echo "ExpiredClaimProbeFence=PASS"

expect_app_failure "StaleAttemptSettlement" "
BEGIN;
SELECT public.opsmind_set_tenant_context(
  'a1500000-0000-4000-8000-000000000001',
  'a1500000-0000-4000-8000-000000000002'
);
SELECT * FROM public.opsmind_settle_evidence_artifact_upload(
  'a1500000-0000-4000-8000-000000000001',
  'a1500000-0000-4000-8000-000000000003',
  'a1500000-0000-4000-8000-000000000004',
  'a1500000-0000-4000-8000-000000000005',
  public.opsmind_evidence_artifact_id(
    'a1500000-0000-4000-8000-000000000001',
    'a1500000-0000-4000-8000-000000000005',
    'a1500000-0000-4000-8000-000000000006'
  ),
  '${winner_attempt}', 1, 'STORED', decode(repeat('ab', 32), 'hex'), 14,
  'version-1', 'aws-kms-profile-v1', NULL
);
COMMIT;"

expect_app_failure "CrossTenantClaimDenial" "
BEGIN;
SELECT public.opsmind_set_tenant_context(
  'a1500000-0000-4000-8000-000000000001',
  'a1500000-0000-4000-8000-000000000002'
);
SELECT * FROM public.opsmind_claim_evidence_artifact_upload(
  'b1500000-0000-4000-8000-000000000001',
  'a1500000-0000-4000-8000-000000000003',
  'a1500000-0000-4000-8000-000000000004',
  'a1500000-0000-4000-8000-000000000005',
  public.opsmind_evidence_artifact_id(
    'a1500000-0000-4000-8000-000000000001',
    'a1500000-0000-4000-8000-000000000005',
    'a1500000-0000-4000-8000-000000000006'
  ),
  'a1540000-0000-4000-8000-000000000001', 1, 300000
);
COMMIT;"

expect_app_failure "MissingStoredAuditRollback" "
BEGIN;
SELECT public.opsmind_set_tenant_context(
  'a1500000-0000-4000-8000-000000000001',
  'a1500000-0000-4000-8000-000000000002'
);
SELECT * FROM public.opsmind_settle_evidence_artifact_upload(
  'a1500000-0000-4000-8000-000000000001',
  'a1500000-0000-4000-8000-000000000003',
  'a1500000-0000-4000-8000-000000000004',
  'a1500000-0000-4000-8000-000000000005',
  public.opsmind_evidence_artifact_id(
    'a1500000-0000-4000-8000-000000000001',
    'a1500000-0000-4000-8000-000000000005',
    'a1500000-0000-4000-8000-000000000006'
  ),
  'a1530000-0000-4000-8000-000000000001', 1, 'STORED',
  decode(repeat('ab', 32), 'hex'), 14,
  'version-1', 'aws-kms-profile-v1', NULL
);
COMMIT;"
rollback_state="$(admin_query "
SELECT lifecycle_state || '|' || attempt.status
  FROM evidence_artifacts artifact
  JOIN evidence_artifact_upload_attempts attempt
    ON attempt.organization_id = artifact.organization_id
   AND attempt.upload_attempt_id = artifact.upload_attempt_id
 WHERE artifact.organization_id = 'a1500000-0000-4000-8000-000000000001';")"
[[ "$rollback_state" == "PENDING_UPLOAD|CLAIMED" ]]
echo "StoredAuditRollbackState=PASS"

app_sql <<'SQL'
DO $stored$
DECLARE
  organization_id constant uuid := 'a1500000-0000-4000-8000-000000000001';
  project_id constant uuid := 'a1500000-0000-4000-8000-000000000003';
  incident_id constant uuid := 'a1500000-0000-4000-8000-000000000004';
  run_id constant uuid := 'a1500000-0000-4000-8000-000000000005';
  actor_id constant uuid := 'a1500000-0000-4000-8000-000000000002';
  idempotency_key constant uuid := 'a1500000-0000-4000-8000-000000000006';
  attempt_id constant uuid := 'a1530000-0000-4000-8000-000000000001';
  expected_digest constant bytea := decode(repeat('ab', 32), 'hex');
  artifact_id uuid;
  event_id uuid;
  settled record;
BEGIN
  PERFORM public.opsmind_set_tenant_context(organization_id, actor_id);
  artifact_id := public.opsmind_evidence_artifact_id(
    organization_id, run_id, idempotency_key
  );
  SELECT * INTO settled
    FROM public.opsmind_settle_evidence_artifact_upload(
      organization_id, project_id, incident_id, run_id, artifact_id,
      attempt_id, 1, 'STORED', expected_digest, 14,
      'version-1', 'aws-kms-profile-v1', NULL
    );
  IF NOT settled.transition_applied
     OR settled.lifecycle_state <> 'STORED'
     OR settled.lifecycle_version <> 2
     OR settled.storage_generation <> 1 THEN
    RAISE EXCEPTION 'stored settlement did not apply exactly once';
  END IF;
  event_id := public.opsmind_evidence_artifact_lifecycle_event_id(
    organization_id, artifact_id, settled.lifecycle_version, attempt_id
  );
  INSERT INTO evidence_artifact_events (
    event_id, organization_id, project_id, incident_id, run_id, artifact_id,
    actor_id, lifecycle_version, lifecycle_from_state, lifecycle_to_state,
    occurred_at, audit_event_id, upload_attempt_id
  ) VALUES (
    event_id, organization_id, project_id, incident_id, run_id, artifact_id,
    actor_id, settled.lifecycle_version, 'PENDING_UPLOAD', 'STORED',
    settled.lifecycle_updated_at, event_id, attempt_id
  );
  INSERT INTO audit_events (
    event_id, organization_id, actor_id, action, resource_type, resource_id,
    correlation_id, occurred_at, payload, schema_version
  ) VALUES (
    event_id, organization_id, actor_id, 'ARTIFACT_STORED',
    'evidence_artifact', artifact_id::text, artifact_id,
    settled.lifecycle_updated_at,
    jsonb_build_object(
      'eventId', event_id,
      'organizationId', organization_id,
      'projectId', project_id,
      'incidentId', incident_id,
      'runId', run_id,
      'artifactId', artifact_id,
      'actorId', actor_id,
      'lifecycleVersion', settled.lifecycle_version,
      'lifecycleState', settled.lifecycle_state,
      'contentDigest', 'sha256:' || encode(expected_digest, 'hex'),
      'byteCount', 14,
      'dataClassification', 'redacted-metrics',
      'retentionClass', 'evidence-90d',
      'storageGeneration', settled.storage_generation,
      'occurredAt', settled.lifecycle_updated_at
    ),
    'evidence-artifact-audit-v1'
  );
END
$stored$;
SQL

repeat_output="$(app_query "
BEGIN;
SELECT public.opsmind_set_tenant_context(
  'a1500000-0000-4000-8000-000000000001',
  'a1500000-0000-4000-8000-000000000002'
);
SELECT transition_applied, lifecycle_state, lifecycle_version, storage_generation
  FROM public.opsmind_settle_evidence_artifact_upload(
    'a1500000-0000-4000-8000-000000000001',
    'a1500000-0000-4000-8000-000000000003',
    'a1500000-0000-4000-8000-000000000004',
    'a1500000-0000-4000-8000-000000000005',
    public.opsmind_evidence_artifact_id(
      'a1500000-0000-4000-8000-000000000001',
      'a1500000-0000-4000-8000-000000000005',
      'a1500000-0000-4000-8000-000000000006'
    ),
    'a1530000-0000-4000-8000-000000000001', 1, 'STORED',
    decode(repeat('ab', 32), 'hex'), 14,
    'version-1', 'aws-kms-profile-v1', NULL
  );
COMMIT;")"
if ! grep -q "f|STORED|2|1" <<<"$repeat_output"; then
  echo "ExactStoredReplay=BLOCK" >&2
  exit 1
fi
echo "ExactStoredReplay=PASS"

stored_contract="$(admin_query "
SELECT CASE WHEN
  artifact.lifecycle_state = 'STORED'
  AND artifact.lifecycle_version = 2
  AND artifact.storage_generation = 1
  AND attempt.status = 'STORED'
  AND (
    SELECT count(*) FROM evidence_artifact_events event_row
     WHERE event_row.organization_id = artifact.organization_id
       AND event_row.artifact_id = artifact.artifact_id
  ) = 2
  AND (
    SELECT count(*) FROM audit_events audit_row
     WHERE audit_row.organization_id = artifact.organization_id
       AND audit_row.resource_id = artifact.artifact_id::text
       AND audit_row.schema_version = 'evidence-artifact-audit-v1'
  ) = 2
  AND NOT EXISTS (
    SELECT 1 FROM audit_events audit_row
     WHERE audit_row.organization_id = artifact.organization_id
       AND audit_row.resource_id = artifact.artifact_id::text
       AND audit_row.payload ?| ARRAY[
         'storageKey', 'storageVersionReference',
         'encryptionMetadataReference', 'objectUrl', 'signedUrl'
       ]
  )
THEN 'PASS' ELSE 'BLOCK' END
  FROM evidence_artifacts artifact
  JOIN evidence_artifact_upload_attempts attempt
    ON attempt.organization_id = artifact.organization_id
   AND attempt.upload_attempt_id = artifact.upload_attempt_id
 WHERE artifact.organization_id = 'a1500000-0000-4000-8000-000000000001';")"
[[ "$stored_contract" == "PASS" ]]
echo "StoredEventAuditAtomicity=PASS"
echo "StoredAuditRedaction=PASS"

expect_app_failure "DirectArtifactMutationDenial" "
BEGIN;
SELECT public.opsmind_set_tenant_context(
  'a1500000-0000-4000-8000-000000000001',
  'a1500000-0000-4000-8000-000000000002'
);
UPDATE evidence_artifacts SET last_failure_code = 'forbidden'
 WHERE organization_id = 'a1500000-0000-4000-8000-000000000001';
COMMIT;"
expect_app_failure "DirectAttemptReadDenial" "
SELECT count(*) FROM evidence_artifact_upload_attempts;"

admin_sql <<'SQL'
BEGIN;
SET LOCAL session_replication_role = replica;
UPDATE incidents
   SET version = version + 1
 WHERE id = 'a1500000-0000-4000-8000-000000000004'
   AND organization_id = 'a1500000-0000-4000-8000-000000000001';
COMMIT;
SQL
expect_app_failure "AuthorizationEpochDriftDenial" "
BEGIN;
SELECT public.opsmind_set_tenant_context(
  'a1500000-0000-4000-8000-000000000001',
  'a1500000-0000-4000-8000-000000000002'
);
SELECT * FROM public.opsmind_settle_evidence_artifact_upload(
  'a1500000-0000-4000-8000-000000000001',
  'a1500000-0000-4000-8000-000000000003',
  'a1500000-0000-4000-8000-000000000004',
  'a1500000-0000-4000-8000-000000000005',
  public.opsmind_evidence_artifact_id(
    'a1500000-0000-4000-8000-000000000001',
    'a1500000-0000-4000-8000-000000000005',
    'a1500000-0000-4000-8000-000000000006'
  ),
  'a1530000-0000-4000-8000-000000000001', 1, 'STORED',
  decode(repeat('ab', 32), 'hex'), 14,
  'version-1', 'aws-kms-profile-v1', NULL
);
COMMIT;"

printf '%s\n' \
  "ArtifactObjectUpgradeDatabase=${upgrade_database}" \
  "VersionBefore=${version_before}" \
  "VersionAfter=15" \
  "ArtifactObjectContractResult=PASS"
