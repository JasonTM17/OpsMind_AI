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
    --dbname "$upgrade_database" --tuples-only --no-align \
    --command "$sql"
}

migrate_to 6
version_before="$(query_upgrade_database "SELECT max(version::integer) FROM flyway_schema_history WHERE success;")"
table_before="$(query_upgrade_database "SELECT CASE WHEN to_regclass('public.evidence_records') IS NULL THEN 'ABSENT' ELSE 'PRESENT' END;")"
[[ "$version_before" == "6" ]]
[[ "$table_before" == "ABSENT" ]]

migrate_to 7
version_after="$(query_upgrade_database "SELECT max(version::integer) FROM flyway_schema_history WHERE success;")"
table_after="$(query_upgrade_database "SELECT CASE WHEN to_regclass('public.evidence_records') IS NULL THEN 'ABSENT' ELSE 'PRESENT' END;")"
[[ "$version_after" == "7" ]]
[[ "$table_after" == "PRESENT" ]]

printf 'Database=%s\nVersionBefore=%s\nEvidenceTableBefore=%s\nVersionAfter=%s\nEvidenceTableAfter=%s\nUpgradeResult=PASS\n' \
  "$upgrade_database" "$version_before" "$table_before" \
  "$version_after" "$table_after"
