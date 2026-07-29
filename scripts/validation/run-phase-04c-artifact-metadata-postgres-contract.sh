#!/usr/bin/env bash
set -euo pipefail

if [[ "${OPSMIND_EPHEMERAL_DB:-}" != "true" ]]; then
  echo "OPSMIND_EPHEMERAL_DB=true is required for the disposable artifact upgrade proof." >&2
  exit 2
fi
for required_name in PGHOST PGPORT PGDATABASE POSTGRES_USER POSTGRES_PASSWORD; do
  if [[ -z "${!required_name:-}" ]]; then
    echo "${required_name} is required." >&2
    exit 2
  fi
done

upgrade_database="${OPSMIND_PHASE4C_ARTIFACT_UPGRADE_DATABASE:-opsmind_phase4c_artifact_upgrade}"
if [[ ! "$upgrade_database" =~ ^opsmind_phase4c_artifact_upgrade(_[a-z0-9_]+)?$ ]]; then
  echo "Artifact upgrade database must use the opsmind_phase4c_artifact_upgrade prefix." >&2
  exit 2
fi
if [[ "$upgrade_database" == "$PGDATABASE" ]]; then
  echo "Artifact upgrade database must differ from the primary database." >&2
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
  --host "$PGHOST" --port "$PGPORT" --username "$POSTGRES_USER" "$upgrade_database"
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
    --set ON_ERROR_STOP=1 --command "$sql" | tr -d '\r'
}

migrate_to 13
version_before="$(query_upgrade_database "SELECT max(version::integer) FROM flyway_schema_history WHERE success;")"
artifact_before="$(query_upgrade_database "SELECT CASE WHEN to_regclass('public.evidence_artifacts') IS NULL THEN 'ABSENT' ELSE 'PRESENT' END;")"
[[ "$version_before" == "13" ]]
[[ "$artifact_before" == "ABSENT" ]]

migrate_to 14
version_after="$(query_upgrade_database "SELECT max(version::integer) FROM flyway_schema_history WHERE success;")"
artifact_after="$(query_upgrade_database "SELECT CASE WHEN to_regclass('public.evidence_artifacts') IS NULL THEN 'ABSENT' ELSE 'PRESENT' END;")"
event_after="$(query_upgrade_database "SELECT CASE WHEN to_regclass('public.evidence_artifact_events') IS NULL THEN 'ABSENT' ELSE 'PRESENT' END;")"
rls_after="$(query_upgrade_database "SELECT CASE WHEN relrowsecurity AND relforcerowsecurity THEN 'FORCED' ELSE 'UNSAFE' END FROM pg_class WHERE oid = 'public.evidence_artifacts'::regclass;")"
event_rls_after="$(query_upgrade_database "SELECT CASE WHEN relrowsecurity AND relforcerowsecurity THEN 'FORCED' ELSE 'UNSAFE' END FROM pg_class WHERE oid = 'public.evidence_artifact_events'::regclass;")"
audit_schema_after="$(query_upgrade_database "SELECT CASE WHEN pg_get_constraintdef(oid) LIKE '%evidence-artifact-audit-v1%' THEN 'PRESENT' ELSE 'ABSENT' END FROM pg_constraint WHERE conname = 'audit_events_schema_version_known';")"
app_identity_execute="$(query_upgrade_database "SELECT CASE WHEN has_function_privilege('opsmind_app', 'public.opsmind_evidence_artifact_id(uuid,uuid,uuid)'::regprocedure, 'EXECUTE') THEN 'GRANTED' ELSE 'REVOKED' END;")"
public_identity_execute="$(query_upgrade_database "SELECT CASE WHEN EXISTS (SELECT 1 FROM pg_proc proc CROSS JOIN LATERAL aclexplode(COALESCE(proc.proacl, acldefault('f', proc.proowner))) privilege WHERE proc.oid = 'public.opsmind_evidence_artifact_id(uuid,uuid,uuid)'::regprocedure AND privilege.grantee = 0 AND privilege.privilege_type = 'EXECUTE') THEN 'GRANTED' ELSE 'REVOKED' END;")"
app_storage_key_select="$(query_upgrade_database "SELECT CASE WHEN has_column_privilege('opsmind_app', 'public.evidence_artifacts', 'storage_key', 'SELECT') THEN 'GRANTED' ELSE 'REVOKED' END;")"
[[ "$version_after" == "14" ]]
[[ "$artifact_after" == "PRESENT" ]]
[[ "$event_after" == "PRESENT" ]]
[[ "$rls_after" == "FORCED" ]]
[[ "$event_rls_after" == "FORCED" ]]
[[ "$audit_schema_after" == "PRESENT" ]]
[[ "$app_identity_execute" == "GRANTED" ]]
[[ "$public_identity_execute" == "REVOKED" ]]
[[ "$app_storage_key_select" == "REVOKED" ]]

printf 'ArtifactUpgradeDatabase=%s\nVersionBefore=%s\nArtifactTableBefore=%s\nVersionAfter=%s\nArtifactTableAfter=%s\nArtifactEventTableAfter=%s\nArtifactRlsAfter=%s\nArtifactEventRlsAfter=%s\nArtifactAuditSchemaAfter=%s\nAppArtifactIdentityExecute=%s\nPublicArtifactIdentityExecute=%s\nAppArtifactStorageKeySelect=%s\nArtifactUpgradeResult=PASS\n' \
  "$upgrade_database" "$version_before" "$artifact_before" "$version_after" \
  "$artifact_after" "$event_after" "$rls_after" "$event_rls_after" "$audit_schema_after" \
  "$app_identity_execute" "$public_identity_execute" "$app_storage_key_select"
